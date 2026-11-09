package dev.jaderss.saga.messaging.contract;

import java.util.Objects;

public record TransferTerms(String sourceAccountId, String destinationAccountId, Money amount) {

    public TransferTerms {
        Objects.requireNonNull(sourceAccountId, "sourceAccountId");
        Objects.requireNonNull(destinationAccountId, "destinationAccountId");
        Objects.requireNonNull(amount, "amount");
        if (sourceAccountId.equals(destinationAccountId)) {
            throw new IllegalArgumentException("source and destination are the same account " + sourceAccountId);
        }
    }
}
