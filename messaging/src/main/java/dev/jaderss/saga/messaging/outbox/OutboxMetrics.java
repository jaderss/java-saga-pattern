package dev.jaderss.saga.messaging.outbox;

import static dev.jaderss.saga.messaging.db.Tables.OUTBOX;
import static dev.jaderss.saga.messaging.jooq.SqlTime.secondsSinceOldest;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.jooq.DSLContext;

public class OutboxMetrics implements MeterBinder {

    private final DSLContext db;

    public OutboxMetrics(DSLContext db) {
        this.db = db;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("outbox.depth", () -> db.fetchCount(OUTBOX))
                .description("Messages waiting for the relay")
                .register(registry);
        Gauge.builder(
                        "outbox.oldest.age",
                        () -> db.select(secondsSinceOldest(OUTBOX.CREATED_AT))
                                .from(OUTBOX)
                                .fetchSingle()
                                .value1())
                .baseUnit("seconds")
                .description("Age of the oldest message waiting for the relay")
                .register(registry);
    }
}
