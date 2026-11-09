package dev.jaderss.saga.account;

import static dev.jaderss.saga.messaging.contract.Action.COMPENSATE;
import static dev.jaderss.saga.messaging.contract.Action.EXECUTE;
import static dev.jaderss.saga.messaging.contract.Step.CREDIT_DESTINATION;
import static dev.jaderss.saga.messaging.contract.Step.DEBIT_SOURCE;
import static org.assertj.core.api.Assertions.assertThat;

import dev.jaderss.saga.messaging.contract.Action;
import dev.jaderss.saga.messaging.contract.Money;
import dev.jaderss.saga.messaging.contract.SagaCommand;
import dev.jaderss.saga.messaging.contract.Step;
import dev.jaderss.saga.messaging.contract.TransferTerms;
import dev.jaderss.saga.messaging.participant.StepResult;
import dev.jaderss.saga.messaging.test.SagaContainers;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(SagaContainers.class)
class AccountStepsIT {

    private static final String ALICE = "ACC-1001";
    private static final String BOB = "ACC-2002";
    private static final String CAROL = "ACC-3003";
    private static final String DAVE = "ACC-4004";

    @Autowired
    AccountSteps steps;

    @Autowired
    AccountStore accounts;

    @BeforeEach
    void seed() {
        accounts.reset();
    }

    @Test
    void debit_and_credit_move_the_money() {
        var saga = UUID.randomUUID();

        assertThat(steps.execute(command(saga, DEBIT_SOURCE, EXECUTE, ALICE, BOB, "120.00")))
                .isEqualTo(StepResult.APPLIED);
        assertThat(steps.execute(command(saga, CREDIT_DESTINATION, EXECUTE, ALICE, BOB, "120.00")))
                .isEqualTo(StepResult.APPLIED);

        assertThat(balance(ALICE)).isEqualByComparingTo("9880.00");
        assertThat(balance(BOB)).isEqualByComparingTo("2620.00");
    }

    @Test
    void refuses_a_debit_above_the_balance() {
        var result = steps.execute(command(UUID.randomUUID(), DEBIT_SOURCE, EXECUTE, CAROL, BOB, "250.00"));

        assertThat(result).isEqualTo(StepResult.rejected("insufficient funds in ACC-3003 for 250.00 USD"));
        assertThat(balance(CAROL)).isEqualByComparingTo("80.00");
    }

    @Test
    void refuses_a_credit_to_a_frozen_account() {
        var result = steps.execute(command(UUID.randomUUID(), CREDIT_DESTINATION, EXECUTE, ALICE, DAVE, "90.00"));

        assertThat(result).isEqualTo(StepResult.rejected("account ACC-4004 is frozen"));
    }

    @Test
    void refuses_another_currency() {
        var terms = new TransferTerms(ALICE, BOB, Money.of("10.00", "EUR"));
        var result = steps.execute(new SagaCommand(UUID.randomUUID(), DEBIT_SOURCE, EXECUTE, 1, terms, List.of()));

        assertThat(result).isEqualTo(StepResult.rejected("account ACC-1001 holds USD, not EUR"));
    }

    @Test
    void refuses_an_unknown_account() {
        var result = steps.execute(command(UUID.randomUUID(), DEBIT_SOURCE, EXECUTE, "ACC-9999", BOB, "1.00"));

        assertThat(result).isEqualTo(StepResult.rejected("account ACC-9999 does not exist"));
    }

    @Test
    void refund_and_reversal_restore_both_balances() {
        var saga = UUID.randomUUID();
        steps.execute(command(saga, DEBIT_SOURCE, EXECUTE, ALICE, BOB, "75.00"));
        steps.execute(command(saga, CREDIT_DESTINATION, EXECUTE, ALICE, BOB, "75.00"));

        steps.compensate(command(saga, CREDIT_DESTINATION, COMPENSATE, ALICE, BOB, "75.00"));
        steps.compensate(command(saga, DEBIT_SOURCE, COMPENSATE, ALICE, BOB, "75.00"));

        assertThat(balance(ALICE)).isEqualByComparingTo("10000.00");
        assertThat(balance(BOB)).isEqualByComparingTo("2500.00");
    }

    private BigDecimal balance(String id) {
        return accounts.find(id).orElseThrow().balance();
    }

    private static SagaCommand command(UUID saga, Step step, Action action, String from, String to, String amount) {
        return new SagaCommand(saga, step, action, 1, new TransferTerms(from, to, Money.of(amount, "USD")), List.of());
    }
}
