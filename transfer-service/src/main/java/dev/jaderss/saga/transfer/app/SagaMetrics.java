package dev.jaderss.saga.transfer.app;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class SagaMetrics implements MeterBinder {

    private final JdbcClient jdbc;

    SagaMetrics(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        for (var status : List.of("RUNNING", "COMPENSATING")) {
            Gauge.builder("saga.active", () -> number("SELECT count(*) FROM saga WHERE status = ?", status))
                    .tag("status", status)
                    .description("Sagas not finished yet")
                    .register(registry);
        }
        Gauge.builder(
                        "saga.compensating.oldest.age",
                        () -> number(
                                "SELECT coalesce(extract(epoch FROM now() - min(created_at)), 0) FROM saga WHERE status = ?",
                                "COMPENSATING"))
                .baseUnit("seconds")
                .description("Age of the oldest saga still compensating")
                .register(registry);
    }

    private Number number(String sql, String status) {
        return jdbc.sql(sql).param(status).query(Double.class).single();
    }
}
