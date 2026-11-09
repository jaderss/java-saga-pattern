package dev.jaderss.saga.messaging.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.jaderss.saga.messaging.contract.Topics;
import dev.jaderss.saga.messaging.test.MessagingTestApp;
import dev.jaderss.saga.messaging.test.SagaContainers;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;

@SpringBootTest(
        classes = MessagingTestApp.class,
        properties = {"spring.application.name=messaging-it", "spring.flyway.locations=classpath:db/messaging"})
@Import(SagaContainers.class)
class OutboxRelayIT {

    @Autowired
    OutboxWriter outbox;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DSLContext db;

    @Autowired
    KafkaContainer kafka;

    @Autowired
    KafkaTemplate<String, String> template;

    @Autowired
    OutboxRelay relay;

    @Autowired
    OutboxMetrics metrics;

    private static final int KEYS = 50;
    private static final int MESSAGES_PER_KEY = 40;

    @Test
    void reports_the_depth_and_the_age_of_the_oldest_waiting_message() {
        var registry = new SimpleMeterRegistry();
        metrics.bindTo(registry);

        tx.executeWithoutResult(status -> {
            jdbc.sql("""
                            INSERT INTO outbox (topic, msg_key, payload, created_at)
                            VALUES ('gauge-test', 'key', '{}', now() - interval '1 minute')
                            """).update();

            assertThat(registry.get("outbox.depth").gauge().value()).isPositive();
            assertThat(registry.get("outbox.oldest.age").gauge().value()).isGreaterThanOrEqualTo(60);
            status.setRollbackOnly();
        });
    }

    @Test
    void publishes_committed_rows_with_the_saga_id_as_key_and_deletes_them() {
        var saga = UUID.randomUUID();

        tx.executeWithoutResult(_ -> outbox.append(Topics.REPLIES, saga, Map.of("hello", "saga")));

        try (var consumer = consumer()) {
            consumer.subscribe(List.of(Topics.REPLIES));
            await().atMost(Duration.ofSeconds(20))
                    .untilAsserted(() -> assertThat(consumer.poll(Duration.ofMillis(200)))
                            .anySatisfy(message -> {
                                assertThat(message.key()).isEqualTo(saga.toString());
                                assertThat(message.value()).isEqualTo("{\"hello\":\"saga\"}");
                            }));
        }
        assertThat(jdbc.sql("SELECT count(*) FROM outbox").query(Long.class).single())
                .isZero();
    }

    @Test
    void relays_draining_at_the_same_time_keep_the_order_of_each_key() throws Exception {
        var topic = "relay-order-" + UUID.randomUUID();
        var relays = List.of(new OutboxRelay(db, template, tx), new OutboxRelay(db, template, tx), relay);
        var stop = new AtomicBoolean();
        try (var pods = Executors.newVirtualThreadPerTaskExecutor()) {
            relays.forEach(each -> pods.submit(() -> {
                while (!stop.get()) {
                    each.publishBatch();
                }
                return null;
            }));
            tx.executeWithoutResult(
                    _ -> jdbc.sql("""
                            INSERT INTO outbox (topic, msg_key, payload)
                            SELECT ?, 'key-' || key.n, message.seq::text
                            FROM generate_series(0, ?) AS message(seq), generate_series(1, ?) AS key(n)
                            ORDER BY message.seq, key.n
                            """).params(topic, MESSAGES_PER_KEY - 1, KEYS).update());
            await().atMost(Duration.ofSeconds(30))
                    .until(() -> jdbc.sql("SELECT count(*) FROM outbox")
                                    .query(Long.class)
                                    .single()
                            == 0);
            stop.set(true);
        }

        var received = new HashMap<String, List<Integer>>();
        try (var consumer = consumer()) {
            consumer.subscribe(List.of(topic));
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                consumer.poll(Duration.ofMillis(200))
                        .forEach(message -> received.computeIfAbsent(message.key(), _ -> new ArrayList<>())
                                .add(Integer.parseInt(message.value())));
                return received.values().stream().mapToInt(List::size).sum() == KEYS * MESSAGES_PER_KEY;
            });
        }
        var inOrder = IntStream.range(0, MESSAGES_PER_KEY).boxed().toList();
        assertThat(received)
                .hasSize(KEYS)
                .allSatisfy((_, values) -> assertThat(values).isEqualTo(inOrder));
    }

    @Test
    void the_producer_keeps_order_within_a_partition() {
        assertThat(template.getProducerFactory().getConfigurationProperties())
                .containsEntry(ProducerConfig.ACKS_CONFIG, "all")
                .containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true)
                .containsEntry(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
    }

    private KafkaConsumer<String, String> consumer() {
        return new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG,
                "relay-it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class));
    }
}
