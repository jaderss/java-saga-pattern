package dev.jaderss.saga.messaging.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.jdbc.core.simple.JdbcClient;

public class OutboxMetrics implements MeterBinder {

    private final JdbcClient jdbc;

    public OutboxMetrics(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("outbox.depth", () -> number("SELECT count(*) FROM outbox"))
                .description("Messages waiting for the relay")
                .register(registry);
        Gauge.builder(
                        "outbox.oldest.age",
                        () -> number("SELECT coalesce(extract(epoch FROM now() - min(created_at)), 0) FROM outbox"))
                .baseUnit("seconds")
                .description("Age of the oldest message waiting for the relay")
                .register(registry);
    }

    private Number number(String sql) {
        return jdbc.sql(sql).query(Double.class).single();
    }
}
