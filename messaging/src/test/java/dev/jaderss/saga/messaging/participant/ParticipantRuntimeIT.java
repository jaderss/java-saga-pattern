package dev.jaderss.saga.messaging.participant;

import static dev.jaderss.saga.messaging.contract.Action.COMPENSATE;
import static dev.jaderss.saga.messaging.contract.Action.EXECUTE;
import static dev.jaderss.saga.messaging.contract.Outcome.DONE;
import static dev.jaderss.saga.messaging.contract.Outcome.REJECTED;
import static dev.jaderss.saga.messaging.contract.Step.DEBIT_SOURCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import dev.jaderss.saga.messaging.contract.Action;
import dev.jaderss.saga.messaging.contract.Fault;
import dev.jaderss.saga.messaging.contract.FaultKind;
import dev.jaderss.saga.messaging.contract.Money;
import dev.jaderss.saga.messaging.contract.SagaCommand;
import dev.jaderss.saga.messaging.contract.SagaReply;
import dev.jaderss.saga.messaging.contract.TransferTerms;
import dev.jaderss.saga.messaging.test.MessagingTestApp;
import dev.jaderss.saga.messaging.test.SagaContainers;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
        classes = MessagingTestApp.class,
        properties = {
            "spring.application.name=messaging-it",
            "spring.flyway.locations=classpath:db/messaging,classpath:db/participant",
            "outbox.relay-interval=1h"
        })
@Import({SagaContainers.class, ParticipantRuntimeIT.Handler.class})
class ParticipantRuntimeIT {

    @Autowired
    ParticipantRuntime runtime;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JsonMapper json;

    @TestConfiguration(proxyBeanMethods = false)
    static class Handler {
        @Bean
        FakeHandler fakeHandler(JdbcClient jdbc) {
            jdbc.sql("CREATE TABLE IF NOT EXISTS fake_effect (saga_id UUID, step TEXT, kind TEXT)")
                    .update();
            return new FakeHandler(jdbc);
        }
    }

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM outbox").update();
        jdbc.sql("DELETE FROM fake_effect").update();
    }

    @Test
    void executes_once_and_replays_the_stored_outcome_on_a_duplicate() {
        var saga = UUID.randomUUID();

        runtime.handle(command(saga, EXECUTE, 1, "ACC-1"));
        runtime.handle(command(saga, EXECUTE, 2, "ACC-1"));

        assertThat(effects(saga)).containsExactly("EXECUTE");
        assertThat(replies(saga))
                .extracting(SagaReply::outcome, SagaReply::duplicate)
                .containsExactly(tuple(DONE, false), tuple(DONE, true));
    }

    @Test
    void replays_a_rejection_with_its_reason() {
        var saga = UUID.randomUUID();

        runtime.handle(command(saga, EXECUTE, 1, "REJECT-ME"));
        runtime.handle(command(saga, EXECUTE, 2, "REJECT-ME"));

        assertThat(replies(saga))
                .extracting(SagaReply::outcome, SagaReply::reason, SagaReply::duplicate)
                .containsExactly(
                        tuple(REJECTED, "rejected by the fake", false), tuple(REJECTED, "rejected by the fake", true));
    }

    @Test
    void compensates_an_applied_step_once() {
        var saga = UUID.randomUUID();

        runtime.handle(command(saga, EXECUTE, 1, "ACC-1"));
        runtime.handle(command(saga, COMPENSATE, 1, "ACC-1"));
        runtime.handle(command(saga, COMPENSATE, 2, "ACC-1"));

        assertThat(effects(saga)).containsExactly("EXECUTE", "COMPENSATE");
        assertThat(replies(saga)).extracting(SagaReply::duplicate).containsExactly(false, false, true);
    }

    @Test
    void compensation_before_execution_leaves_a_tombstone_that_refuses_the_late_command() {
        var saga = UUID.randomUUID();

        runtime.handle(command(saga, COMPENSATE, 1, "ACC-1"));
        runtime.handle(command(saga, EXECUTE, 1, "ACC-1"));

        assertThat(effects(saga)).isEmpty();
        assertThat(replies(saga))
                .extracting(SagaReply::action, SagaReply::outcome, SagaReply::reason)
                .containsExactly(
                        tuple(COMPENSATE, DONE, "nothing was applied"),
                        tuple(EXECUTE, REJECTED, ParticipantRuntime.ABORTED_REASON));
    }

    @Test
    void lost_reply_commits_the_step_and_the_retry_is_answered_from_the_record() {
        var saga = UUID.randomUUID();
        var fault = new Fault(DEBIT_SOURCE, EXECUTE, FaultKind.LOSE_REPLY, 1);

        runtime.handle(command(saga, EXECUTE, 1, "ACC-1", fault));
        assertThat(replies(saga)).isEmpty();

        runtime.handle(command(saga, EXECUTE, 2, "ACC-1", fault));

        assertThat(effects(saga)).containsExactly("EXECUTE");
        assertThat(replies(saga))
                .extracting(SagaReply::attempt, SagaReply::duplicate)
                .containsExactly(tuple(2, true));
    }

    @Test
    void crash_before_commit_rolls_back_until_the_fault_is_spent() {
        var saga = UUID.randomUUID();
        var fault = new Fault(DEBIT_SOURCE, EXECUTE, FaultKind.CRASH_BEFORE_COMMIT, 2);

        runtime.handle(command(saga, EXECUTE, 1, "ACC-1", fault));
        runtime.handle(command(saga, EXECUTE, 2, "ACC-1", fault));
        assertThat(effects(saga)).isEmpty();
        assertThat(replies(saga)).isEmpty();

        runtime.handle(command(saga, EXECUTE, 3, "ACC-1", fault));

        assertThat(effects(saga)).containsExactly("EXECUTE");
        assertThat(replies(saga))
                .extracting(SagaReply::attempt, SagaReply::duplicate)
                .containsExactly(tuple(3, false));
    }

    private static SagaCommand command(UUID saga, Action action, int attempt, String source, Fault... faults) {
        var terms = new TransferTerms(source, "ACC-2", Money.of("10.00", "USD"));
        return new SagaCommand(saga, DEBIT_SOURCE, action, attempt, terms, List.of(faults));
    }

    private List<String> effects(UUID saga) {
        return jdbc.sql("SELECT kind FROM fake_effect WHERE saga_id = ?")
                .param(saga)
                .query(String.class)
                .list();
    }

    private List<SagaReply> replies(UUID saga) {
        return jdbc
                .sql("SELECT payload FROM outbox WHERE msg_key = ? ORDER BY id")
                .param(saga.toString())
                .query(String.class)
                .list()
                .stream()
                .map(payload -> json.readValue(payload, SagaReply.class))
                .toList();
    }
}
