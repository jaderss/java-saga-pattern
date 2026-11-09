package dev.jaderss.saga.transfer.domain;

public enum SagaEventType {
    STARTED,
    COMMAND_SENT,
    AWAITING_APPROVAL,
    STEP_SUCCEEDED,
    STEP_REJECTED,
    STEP_COMPENSATED,
    TIMED_OUT,
    RETRIES_EXHAUSTED,
    REPLY_IGNORED,
    COMPLETED,
    COMPENSATED,
    REJECTED
}
