package dev.jaderss.saga.messaging.contract;

import java.util.Objects;

public record Fault(Step step, Action action, FaultKind kind, int times) {

    public Fault {
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(kind, "kind");
        if (times < 1) {
            throw new IllegalArgumentException("fault times must be at least 1, was " + times);
        }
    }

    boolean hits(Step step, Action action, int attempt) {
        return this.step == step && this.action == action && attempt <= times;
    }
}
