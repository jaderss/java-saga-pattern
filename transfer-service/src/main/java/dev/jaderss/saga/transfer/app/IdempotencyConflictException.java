package dev.jaderss.saga.transfer.app;

public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String key) {
        super("Idempotency-Key %s was already used for a different transfer".formatted(key));
    }
}
