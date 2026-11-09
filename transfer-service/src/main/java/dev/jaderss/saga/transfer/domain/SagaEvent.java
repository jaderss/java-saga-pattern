package dev.jaderss.saga.transfer.domain;

import dev.jaderss.saga.messaging.contract.Action;
import dev.jaderss.saga.messaging.contract.Step;
import java.util.Objects;

public record SagaEvent(SagaEventType type, Step step, Action action, int attempt, String detail) {

    public SagaEvent {
        Objects.requireNonNull(type, "type");
    }

    static SagaEvent of(SagaEventType type, String detail) {
        return new SagaEvent(type, null, null, 0, detail);
    }
}
