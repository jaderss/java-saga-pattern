package dev.jaderss.saga.transfer.domain;

import static dev.jaderss.saga.transfer.domain.StepStatus.COMPENSATED;
import static dev.jaderss.saga.transfer.domain.StepStatus.IN_PROGRESS;
import static dev.jaderss.saga.transfer.domain.StepStatus.PENDING;
import static dev.jaderss.saga.transfer.domain.StepStatus.REJECTED;
import static dev.jaderss.saga.transfer.domain.StepStatus.SUCCEEDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jaderss.saga.messaging.contract.Outcome;
import org.junit.jupiter.api.Test;

class TransferSagaTest {

    private static final int MAX_ATTEMPTS = 3;

    @Test
    void completes_after_four_successful_steps() {
        var driver = new SagaDriver().done().done().done().done();

        assertThat(driver.saga.status()).isEqualTo(SagaStatus.COMPLETED);
        assertThat(driver.saga.steps()).containsOnly(SUCCEEDED);
        assertThat(driver.trail())
                .isEqualTo("E:RESERVE_LIMIT#1 E:DEBIT_SOURCE#1 E:CREDIT_DESTINATION#1 E:CLEAR_TRANSFER#1");
    }

    @Test
    void rejection_of_the_first_step_ends_with_nothing_to_undo() {
        var driver = new SagaDriver().reject("over the limit");

        assertThat(driver.saga.status()).isEqualTo(SagaStatus.REJECTED);
        assertThat(driver.saga.reason()).isEqualTo("RESERVE_LIMIT rejected: over the limit");
        assertThat(driver.trail()).isEqualTo("E:RESERVE_LIMIT#1");
    }

    @Test
    void rejection_of_the_credit_undoes_debit_then_limit() {
        var driver = new SagaDriver().done().done().reject("frozen");

        assertThat(driver.saga.status()).isEqualTo(SagaStatus.COMPENSATING);

        driver.done().done();

        assertThat(driver.saga.status()).isEqualTo(SagaStatus.COMPENSATED);
        assertThat(driver.saga.steps()).containsExactly(COMPENSATED, COMPENSATED, REJECTED, PENDING);
        assertThat(driver.trail()).endsWith("E:CREDIT_DESTINATION#1 C:DEBIT_SOURCE#1 C:RESERVE_LIMIT#1");
    }

    @Test
    void rejection_of_the_last_step_undoes_the_three_before_it_in_reverse_order() {
        var driver = new SagaDriver()
                .done()
                .done()
                .done()
                .reject("watchlist")
                .done()
                .done()
                .done();

        assertThat(driver.saga.status()).isEqualTo(SagaStatus.COMPENSATED);
        assertThat(driver.trail()).endsWith("C:CREDIT_DESTINATION#1 C:DEBIT_SOURCE#1 C:RESERVE_LIMIT#1");
    }

    @Test
    void a_reply_for_a_step_already_passed_is_ignored() {
        var driver = new SagaDriver().done();
        var stale = driver.sent.getFirst().reply(Outcome.DONE, null, true);
        var before = driver.saga;

        var transition = before.onReply(stale);

        assertThat(transition.saga()).isEqualTo(before);
        assertThat(transition.commands()).isEmpty();
        assertThat(transition.changesState()).isFalse();
        assertThat(transition.events()).extracting(SagaEvent::type).containsExactly(SagaEventType.REPLY_IGNORED);
    }

    @Test
    void a_timeout_resends_the_same_step_with_the_next_attempt() {
        var driver = new SagaDriver().done().timeout(MAX_ATTEMPTS).done();

        assertThat(driver.trail())
                .isEqualTo("E:RESERVE_LIMIT#1 E:DEBIT_SOURCE#1 E:DEBIT_SOURCE#2 E:CREDIT_DESTINATION#1");
    }

    @Test
    void exhausted_retries_compensate_the_unknown_step_too() {
        var driver = new SagaDriver()
                .done()
                .done()
                .timeout(MAX_ATTEMPTS)
                .timeout(MAX_ATTEMPTS)
                .timeout(MAX_ATTEMPTS);

        assertThat(driver.saga.status()).isEqualTo(SagaStatus.COMPENSATING);
        assertThat(driver.saga.reason()).isEqualTo("CREDIT_DESTINATION: no reply after 3 attempts");
        assertThat(driver.trail()).endsWith("E:CREDIT_DESTINATION#3 C:CREDIT_DESTINATION#1");
        assertThat(driver.events).extracting(SagaEvent::type).contains(SagaEventType.RETRIES_EXHAUSTED);

        driver.done().done().done();

        assertThat(driver.saga.status()).isEqualTo(SagaStatus.COMPENSATED);
    }

    @Test
    void compensations_retry_past_the_forward_attempt_limit() {
        var driver = new SagaDriver().done().reject("no funds");
        for (var i = 0; i < 10; i++) {
            driver.timeout(MAX_ATTEMPTS);
        }

        assertThat(driver.saga.status()).isEqualTo(SagaStatus.COMPENSATING);
        assertThat(driver.sent.getLast().attempt()).isEqualTo(11);

        driver.done();

        assertThat(driver.saga.status()).isEqualTo(SagaStatus.COMPENSATED);
    }

    @Test
    void a_timeout_after_the_end_does_nothing() {
        var driver = new SagaDriver().reject("over the limit");

        var transition = driver.saga.onTimeout(MAX_ATTEMPTS);

        assertThat(transition.commands()).isEmpty();
        assertThat(transition.events()).isEmpty();
    }

    @Test
    void an_approval_saga_waits_after_the_debit_without_sending_a_command() {
        var driver = new SagaDriver(true).done().done();

        assertThat(driver.saga.awaitingApproval()).isTrue();
        assertThat(driver.saga.steps()).containsExactly(SUCCEEDED, SUCCEEDED, IN_PROGRESS, PENDING, PENDING);
        assertThat(driver.trail()).isEqualTo("E:RESERVE_LIMIT#1 E:DEBIT_SOURCE#1");
        assertThat(driver.events.getLast().type()).isEqualTo(SagaEventType.AWAITING_APPROVAL);
    }

    @Test
    void approval_lets_the_saga_go_on_to_credit_and_clear() {
        var driver = new SagaDriver(true).done().done().decide(true).done().done();

        assertThat(driver.saga.status()).isEqualTo(SagaStatus.COMPLETED);
        assertThat(driver.saga.steps()).containsOnly(SUCCEEDED);
        assertThat(driver.trail())
                .isEqualTo("E:RESERVE_LIMIT#1 E:DEBIT_SOURCE#1 E:CREDIT_DESTINATION#1 E:CLEAR_TRANSFER#1");
    }

    @Test
    void declining_refunds_the_debit_then_releases_the_limit() {
        var driver = new SagaDriver(true).done().done().decide(false).done().done();

        assertThat(driver.saga.status()).isEqualTo(SagaStatus.COMPENSATED);
        assertThat(driver.saga.reason()).isEqualTo("APPROVE_TRANSFER rejected: declined");
        assertThat(driver.saga.steps()).containsExactly(COMPENSATED, COMPENSATED, REJECTED, PENDING, PENDING);
        assertThat(driver.trail()).endsWith("E:DEBIT_SOURCE#1 C:DEBIT_SOURCE#1 C:RESERVE_LIMIT#1");
    }

    @Test
    void an_approval_that_expires_is_declined() {
        var driver = new SagaDriver(true).done().done().timeout(MAX_ATTEMPTS);

        assertThat(driver.saga.status()).isEqualTo(SagaStatus.COMPENSATING);
        assertThat(driver.saga.reason()).isEqualTo("APPROVE_TRANSFER rejected: no decision before the deadline");
        assertThat(driver.trail()).endsWith("C:DEBIT_SOURCE#1");
    }

    @Test
    void a_failure_after_approval_skips_the_approval_when_compensating() {
        var driver = new SagaDriver(true)
                .done()
                .done()
                .decide(true)
                .reject("frozen")
                .done()
                .done();

        assertThat(driver.saga.status()).isEqualTo(SagaStatus.COMPENSATED);
        assertThat(driver.saga.steps()).containsExactly(COMPENSATED, COMPENSATED, SUCCEEDED, REJECTED, PENDING);
        assertThat(driver.trail()).endsWith("E:CREDIT_DESTINATION#1 C:DEBIT_SOURCE#1 C:RESERVE_LIMIT#1");
    }

    @Test
    void a_decision_is_refused_when_the_saga_is_not_waiting_for_one() {
        var driver = new SagaDriver(true).done();

        assertThatThrownBy(() -> driver.decide(true)).isInstanceOf(NotAwaitingApprovalException.class);
    }

    @Test
    void a_late_reply_after_the_end_does_not_change_the_saga() {
        var driver = new SagaDriver().reject("over the limit");
        var late = driver.sent.getFirst().reply(Outcome.DONE, null, true);

        assertThat(driver.saga.onReply(late).changesState()).isFalse();
    }
}
