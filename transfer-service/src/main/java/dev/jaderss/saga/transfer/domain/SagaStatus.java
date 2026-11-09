package dev.jaderss.saga.transfer.domain;

public enum SagaStatus {
    RUNNING,
    COMPENSATING,
    COMPLETED,
    COMPENSATED,
    REJECTED;

    public boolean terminal() {
        return this != RUNNING && this != COMPENSATING;
    }
}
