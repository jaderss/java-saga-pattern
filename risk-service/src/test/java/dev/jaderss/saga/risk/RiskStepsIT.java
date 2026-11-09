package dev.jaderss.saga.risk;

import static dev.jaderss.saga.messaging.contract.Action.COMPENSATE;
import static dev.jaderss.saga.messaging.contract.Action.EXECUTE;
import static dev.jaderss.saga.messaging.contract.Step.CLEAR_TRANSFER;
import static dev.jaderss.saga.messaging.contract.Step.RESERVE_LIMIT;
import static org.assertj.core.api.Assertions.assertThat;

import dev.jaderss.saga.messaging.contract.Action;
import dev.jaderss.saga.messaging.contract.Money;
import dev.jaderss.saga.messaging.contract.SagaCommand;
import dev.jaderss.saga.messaging.contract.Step;
import dev.jaderss.saga.messaging.contract.TransferTerms;
import dev.jaderss.saga.messaging.participant.StepResult;
import dev.jaderss.saga.messaging.test.SagaContainers;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest(properties = "risk.daily-limit=1000.00")
@Import(SagaContainers.class)
class RiskStepsIT {

    private static final String ALICE = "ACC-1001";
    private static final String BOB = "ACC-2002";
    private static final String MALLORY = "ACC-5005";

    @Autowired
    RiskSteps steps;

    @Autowired
    RiskStore risk;

    @Autowired
    Clock clock;

    @BeforeEach
    void seed() {
        risk.reset();
    }

    @Test
    void reserves_until_the_daily_limit_and_refuses_beyond_it() {
        assertThat(steps.execute(command(RESERVE_LIMIT, EXECUTE, BOB, "600.00")))
                .isEqualTo(StepResult.APPLIED);

        var result = steps.execute(command(RESERVE_LIMIT, EXECUTE, BOB, "400.01"));

        assertThat(result).isEqualTo(StepResult.rejected("daily limit of 1000.00 USD exceeded by ACC-1001"));
        assertThat(usage()).extracting(LimitUsage::reserved).isEqualTo(new BigDecimal("600.00"));
    }

    @Test
    void clearing_moves_the_reservation_to_used_and_unclearing_moves_it_back() {
        var saga = UUID.randomUUID();
        steps.execute(command(saga, RESERVE_LIMIT, EXECUTE, BOB, "300.00"));

        steps.execute(command(saga, CLEAR_TRANSFER, EXECUTE, BOB, "300.00"));
        assertThat(usage())
                .extracting(LimitUsage::reserved, LimitUsage::used)
                .containsExactly(zero(), amount("300.00"));

        steps.compensate(command(saga, CLEAR_TRANSFER, COMPENSATE, BOB, "300.00"));
        steps.compensate(command(saga, RESERVE_LIMIT, COMPENSATE, BOB, "300.00"));
        assertThat(usage()).extracting(LimitUsage::reserved, LimitUsage::used).containsExactly(zero(), zero());
    }

    @Test
    void refuses_to_clear_a_transfer_to_a_watchlisted_account() {
        var saga = UUID.randomUUID();
        steps.execute(command(saga, RESERVE_LIMIT, EXECUTE, MALLORY, "75.00"));

        var result = steps.execute(command(saga, CLEAR_TRANSFER, EXECUTE, MALLORY, "75.00"));

        assertThat(result)
                .isEqualTo(StepResult.rejected("destination ACC-5005 is on the watchlist: sanctions list match"));
    }

    private LimitUsage usage() {
        return risk.usage(LocalDate.now(clock), new BigDecimal("1000.00")).stream()
                .filter(usage -> usage.accountId().equals(ALICE))
                .findFirst()
                .orElseThrow();
    }

    private static BigDecimal amount(String value) {
        return new BigDecimal(value);
    }

    private static BigDecimal zero() {
        return amount("0.00");
    }

    private static SagaCommand command(Step step, Action action, String to, String amount) {
        return command(UUID.randomUUID(), step, action, to, amount);
    }

    private static SagaCommand command(UUID saga, Step step, Action action, String to, String amount) {
        return new SagaCommand(saga, step, action, 1, new TransferTerms(ALICE, to, Money.of(amount, "USD")), List.of());
    }
}
