package dev.jaderss.saga.messaging.participant;

public enum StepRecordStatus {
    APPLIED,
    REJECTED,
    COMPENSATED,
    ABORTED
}
