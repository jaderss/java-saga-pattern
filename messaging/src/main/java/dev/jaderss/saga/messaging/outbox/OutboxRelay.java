package dev.jaderss.saga.messaging.outbox;

import static dev.jaderss.saga.messaging.db.Tables.OUTBOX;
import static org.jooq.Records.mapping;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.inline;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

public class OutboxRelay {

    static final int BATCH = 100;
    private static final Field<Boolean> TAKE_LEAD =
            field("pg_try_advisory_xact_lock(hashtext({0}))", Boolean.class, inline("outbox-relay"));
    private static final Duration ACK_TIMEOUT = Duration.ofSeconds(10);
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final DSLContext db;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;

    public OutboxRelay(DSLContext db, KafkaTemplate<String, String> kafka, TransactionTemplate tx) {
        this.db = db;
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
            if (!db.select(TAKE_LEAD).fetchSingle(TAKE_LEAD)) {
                return 0;
            }
            var rows = db.select(OUTBOX.ID, OUTBOX.TOPIC, OUTBOX.MSG_KEY, OUTBOX.PAYLOAD)
                    .from(OUTBOX)
                    .orderBy(OUTBOX.ID)
                    .limit(BATCH)
                    .forUpdate()
                    .skipLocked()
                    .fetch(mapping(Row::new));
            if (rows.isEmpty()) {
                return 0;
            }
            await(CompletableFuture.allOf(rows.stream()
                    .map(row -> kafka.send(row.topic(), row.msgKey(), row.payload()))
                    .toArray(CompletableFuture[]::new)));
            db.deleteFrom(OUTBOX)
                    .where(OUTBOX.ID.in(rows.stream().map(Row::id).toList()))
                    .execute();
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
