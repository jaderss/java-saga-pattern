package dev.jaderss.saga.messaging.participant;

import java.util.Objects;

public sealed interface StepResult {

    StepResult APPLIED = new Applied();

    static StepResult rejected(String reason) {
        return new Rejected(reason);
    }

    record Applied() implements StepResult {}

    record Rejected(String reason) implements StepResult {
        public Rejected {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
