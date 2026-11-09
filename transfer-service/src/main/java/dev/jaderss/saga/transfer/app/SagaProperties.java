package dev.jaderss.saga.transfer.app;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("saga")
public record SagaProperties(
        @NotNull Duration stepTimeout,
        @NotNull Duration maxBackoff,
        @Min(1) int maxAttempts,
        @Min(1) int timeoutBatch,
        @NotNull Duration parkOnFailure,
        @NotNull Duration approvalTimeout) {

    public Duration waitFor(int attempt) {
        var doubled = stepTimeout.multipliedBy(1L << Math.min(attempt - 1, 20));
        return doubled.compareTo(maxBackoff) > 0 ? maxBackoff : doubled;
    }
}
