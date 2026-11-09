package dev.jaderss.saga.transfer.app;

import dev.jaderss.saga.transfer.domain.SagaStatus;
import dev.jaderss.saga.transfer.persistence.SagaStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

@Component
class SagaMetrics implements MeterBinder {

    private final SagaStore sagas;

    SagaMetrics(SagaStore sagas) {
        this.sagas = sagas;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        for (var status : SagaStatus.values()) {
            if (!status.terminal()) {
                Gauge.builder("saga.active", () -> sagas.count(status))
                        .tag("status", status.name())
                        .description("Sagas not finished yet")
                        .register(registry);
            }
        }
        Gauge.builder("saga.compensating.oldest.age", () -> sagas.secondsSinceOldestIn(SagaStatus.COMPENSATING))
                .baseUnit("seconds")
                .description("Age of the oldest saga still compensating")
                .register(registry);
    }
}
