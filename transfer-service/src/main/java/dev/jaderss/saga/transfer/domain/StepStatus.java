package dev.jaderss.saga.transfer.domain;

public enum StepStatus {
    PENDING,
    IN_PROGRESS,
    SUCCEEDED,
    REJECTED,
    TIMED_OUT,
    COMPENSATING,
    COMPENSATED;

    boolean needsCompensation() {
        return this == SUCCEEDED || this == TIMED_OUT;
    }
}
