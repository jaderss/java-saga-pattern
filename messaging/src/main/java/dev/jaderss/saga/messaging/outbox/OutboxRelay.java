package dev.jaderss.saga.messaging.outbox;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

public class OutboxRelay {

    static final int BATCH = 100;
    private static final String TAKE_LEAD = "SELECT pg_try_advisory_xact_lock(hashtext('outbox-relay'))";
    private static final Duration ACK_TIMEOUT = Duration.ofSeconds(10);
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcClient jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;

    public OutboxRelay(JdbcClient jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.tx = tx;
    }

    @Scheduled(fixedDelayString = "${outbox.relay-interval:100ms}")
    public void drain() {
        try {
            while (publishBatch() == BATCH) {
                log.debug("full outbox batch published, polling again");
            }
        } catch (RuntimeException e) {
            log.warn("outbox batch rolled back, rows stay for the next round: {}", e.getMessage());
        }
    }

    int publishBatch() {
        return tx.execute(_ -> {
            if (!jdbc.sql(TAKE_LEAD).query(Boolean.class).single()) {
                return 0;
            }
            var rows = jdbc.sql(
                            "SELECT id, topic, msg_key, payload FROM outbox ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED")
                    .param(BATCH)
                    .query(Row.class)
                    .list();
            if (rows.isEmpty()) {
                return 0;
            }
            await(CompletableFuture.allOf(rows.stream()
                    .map(row -> kafka.send(row.topic(), row.msgKey(), row.payload()))
                    .toArray(CompletableFuture[]::new)));
            jdbc.sql("DELETE FROM outbox WHERE id IN (:ids)")
                    .param("ids", rows.stream().map(Row::id).toList())
                    .update();
            return rows.size();
        });
    }

    private static void await(CompletableFuture<Void> sends) {
        try {
            sends.get(ACK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for Kafka acks", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Kafka send failed: " + e.getMessage(), e);
        }
    }

    record Row(long id, String topic, String msgKey, String payload) {}
}
