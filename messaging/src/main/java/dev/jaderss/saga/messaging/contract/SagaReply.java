package dev.jaderss.saga.messaging.contract;

import java.util.Objects;
import java.util.UUID;

public record SagaReply(
        UUID sagaId, Step step, Action action, int attempt, Outcome outcome, String reason, boolean duplicate) {

    public SagaReply {
        Objects.requireNonNull(sagaId, "sagaId");
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(outcome, "outcome");
    }
}
