package dev.jaderss.saga.transfer.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.jaderss.saga.messaging.test.SagaContainers;
import dev.jaderss.saga.transfer.ScriptedParticipantsConfig;
import dev.jaderss.saga.transfer.ScriptedParticipantsConfig.Scripted;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "saga.step-timeout=1s",
            "saga.max-backoff=2s",
            "saga.timeout-scan-interval=100ms",
            "outbox.relay-interval=50ms"
        })
@Import({SagaContainers.class, ScriptedParticipantsConfig.class})
class TransferFlowIT {

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    Scripted participants;

    @Autowired
    MeterRegistry meters;

    @Test
    void completes_a_transfer_through_all_four_steps() throws Exception {
        var id = start(UUID.randomUUID().toString(), body("ACC-1", "ACC-2", "10.00"))
                .get("transfer")
                .get("id")
                .asString();

        var view = awaitStatus(id, "COMPLETED");

        assertThat(view.get("steps").findValuesAsString("status")).containsOnly("SUCCEEDED");
        assertThat(view.get("events").valueStream().map(event -> event.get("id").asLong()))
                .isSorted();
        assertThat(view.get("events").findValuesAsString("at"))
                .allSatisfy(at -> assertThat(at).endsWith("Z"));
        assertThat(participants.trail(id))
                .containsExactly(
                        "E:RESERVE_LIMIT#1", "E:DEBIT_SOURCE#1", "E:CREDIT_DESTINATION#1", "E:CLEAR_TRANSFER#1");
    }

    @Test
    void a_rejected_credit_is_compensated_in_reverse_order() throws Exception {
        var id = start(UUID.randomUUID().toString(), body("ACC-1", ScriptedParticipantsConfig.FROZEN, "10.00"))
                .get("transfer")
                .get("id")
                .asString();

        var view = awaitStatus(id, "COMPENSATED");

        assertThat(view.get("transfer").get("reason").asString())
                .isEqualTo("CREDIT_DESTINATION rejected: account FROZEN is frozen");
        assertThat(participants.trail(id)).endsWith("E:CREDIT_DESTINATION#1", "C:DEBIT_SOURCE#1", "C:RESERVE_LIMIT#1");
    }

    @Test
    void a_missing_reply_is_resent_after_the_step_timeout() throws Exception {
        var id = start(UUID.randomUUID().toString(), body("ACC-1", ScriptedParticipantsConfig.SILENT_ONCE, "10.00"))
                .get("transfer")
                .get("id")
                .asString();

        var view = awaitStatus(id, "COMPLETED");

        assertThat(participants.trail(id)).contains("E:DEBIT_SOURCE#1", "E:DEBIT_SOURCE#2");
        assertThat(view.get("events").findValuesAsString("type")).contains("TIMED_OUT");
    }

    @Test
    void the_same_idempotency_key_returns_the_first_transfer_and_refuses_another_body() throws Exception {
        var key = UUID.randomUUID().toString();
        var first = post(key, body("ACC-1", "ACC-2", "10.00"));
        var again = post(key, body("ACC-1", "ACC-2", "10.00"));
        var other = post(key, body("ACC-1", "ACC-2", "11.00"));

        assertThat(first.statusCode()).isEqualTo(202);
        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(id(again)).isEqualTo(id(first));
        assertThat(other.statusCode()).isEqualTo(422);
    }

    @Test
    void rejects_a_transfer_to_the_same_account() throws Exception {
        assertThat(post(UUID.randomUUID().toString(), body("ACC-1", "ACC-1", "10.00"))
                        .statusCode())
                .isEqualTo(400);
    }

    @Test
    void streams_the_view_until_the_saga_ends() throws Exception {
        var id = start(UUID.randomUUID().toString(), body("ACC-1", "ACC-2", "10.00"))
                .get("transfer")
                .get("id")
                .asString();

        var response = http.send(
                HttpRequest.newBuilder(uri("/api/transfers/" + id + "/stream")).build(),
                HttpResponse.BodyHandlers.ofInputStream());

        String last = null;
        try (var lines = new BufferedReader(new InputStreamReader(response.body()))) {
            for (var line = lines.readLine(); line != null; line = lines.readLine()) {
                if (line.startsWith("data:")) {
                    last = line.substring(5);
                }
            }
        }
        assertThat(response.headers().firstValue("content-type"))
                .hasValueSatisfying(type -> assertThat(type).startsWith("text/event-stream"));
        assertThat(json.readTree(last).get("transfer").get("status").asString()).isEqualTo("COMPLETED");
    }

    @Test
    void an_approved_transfer_completes_and_a_declined_one_is_compensated() throws Exception {
        var approved = startWithApproval();
        var declined = startWithApproval();
        awaitStep(approved, "APPROVE_TRANSFER", "IN_PROGRESS");
        awaitStep(declined, "APPROVE_TRANSFER", "IN_PROGRESS");
        assertThat(meters.get("saga.active").tag("status", "RUNNING").gauge().value())
                .isGreaterThanOrEqualTo(2);

        assertThat(post("/api/transfers/" + approved + "/approve").statusCode()).isEqualTo(200);
        assertThat(post("/api/transfers/" + declined + "/decline").statusCode()).isEqualTo(200);

        awaitStatus(approved, "COMPLETED");
        awaitStatus(declined, "COMPENSATED");
        assertThat(participants.trail(declined))
                .containsExactly("E:RESERVE_LIMIT#1", "E:DEBIT_SOURCE#1", "C:DEBIT_SOURCE#1", "C:RESERVE_LIMIT#1");
        assertThat(post("/api/transfers/" + approved + "/approve").statusCode()).isEqualTo(409);
        assertThat(post("/api/transfers/" + UUID.randomUUID() + "/approve").statusCode())
                .isEqualTo(404);
    }

    @Test
    void lists_the_scenario_catalog() throws Exception {
        var scenarios = json.readTree(
                http.send(HttpRequest.newBuilder(uri("/api/scenarios")).build(), HttpResponse.BodyHandlers.ofString())
                        .body());

        assertThat(scenarios.findValuesAsString("id")).contains("happy-path", "unknown-outcome");
    }

    private JsonNode awaitStatus(String id, String status) {
        return await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(100))
                .until(
                        () -> view(id),
                        view -> view.get("transfer").get("status").asString().equals(status));
    }

    private String startWithApproval() throws Exception {
        var body = json.writeValueAsString(Map.of(
                "sourceAccountId", "ACC-1",
                "destinationAccountId", "ACC-2",
                "amount", "10.00",
                "currency", "USD",
                "requiresApproval", true));
        return start(UUID.randomUUID().toString(), body)
                .get("transfer")
                .get("id")
                .asString();
    }

    private void awaitStep(String id, String step, String status) {
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> view(id).get("steps")
                        .valueStream()
                        .anyMatch(node -> node.get("step").asString().equals(step)
                                && node.get("status").asString().equals(status)));
    }

    private HttpResponse<String> post(String path) throws Exception {
        return http.send(
                HttpRequest.newBuilder(uri(path))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode view(String id) throws Exception {
        return json.readTree(http.send(
                        HttpRequest.newBuilder(uri("/api/transfers/" + id)).build(),
                        HttpResponse.BodyHandlers.ofString())
                .body());
    }

    private JsonNode start(String key, String body) throws Exception {
        var response = post(key, body);
        assertThat(response.statusCode()).isEqualTo(202);
        return json.readTree(response.body());
    }

    private HttpResponse<String> post(String key, String body) throws Exception {
        return http.send(
                HttpRequest.newBuilder(uri("/api/transfers"))
                        .header("Content-Type", "application/json")
                        .header("Idempotency-Key", key)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String id(HttpResponse<String> response) {
        return json.readTree(response.body()).get("transfer").get("id").asString();
    }

    private String body(String from, String to, String amount) {
        return json.writeValueAsString(
                Map.of("sourceAccountId", from, "destinationAccountId", to, "amount", amount, "currency", "USD"));
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
