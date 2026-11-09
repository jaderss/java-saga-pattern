package dev.jaderss.saga.transfer.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SagaPropertiesTest {

    private final SagaProperties properties = new SagaProperties(
            Duration.ofSeconds(3), Duration.ofSeconds(24), 3, 20, Duration.ofMinutes(1), Duration.ofMinutes(2));

    @ParameterizedTest
    @CsvSource({"1, 3", "2, 6", "3, 12", "4, 24", "5, 24", "40, 24"})
    void doublesTheWaitPerAttemptUpToTheCap(int attempt, long seconds) {
        assertThat(properties.waitFor(attempt)).isEqualTo(Duration.ofSeconds(seconds));
    }
}
