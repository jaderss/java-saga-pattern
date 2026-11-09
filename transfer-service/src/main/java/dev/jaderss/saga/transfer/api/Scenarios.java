package dev.jaderss.saga.transfer.api;

import static dev.jaderss.saga.messaging.contract.Action.COMPENSATE;
import static dev.jaderss.saga.messaging.contract.Action.EXECUTE;
import static dev.jaderss.saga.messaging.contract.FaultKind.CRASH_BEFORE_COMMIT;
import static dev.jaderss.saga.messaging.contract.FaultKind.LOSE_REPLY;
import static dev.jaderss.saga.messaging.contract.Step.CREDIT_DESTINATION;
import static dev.jaderss.saga.messaging.contract.Step.DEBIT_SOURCE;
import static dev.jaderss.saga.transfer.domain.SagaStatus.COMPENSATED;
import static dev.jaderss.saga.transfer.domain.SagaStatus.COMPLETED;
import static dev.jaderss.saga.transfer.domain.SagaStatus.REJECTED;

import dev.jaderss.saga.messaging.contract.Fault;
import java.math.BigDecimal;
import java.util.List;

final class Scenarios {

    private static final String ALICE = "ACC-1001";
    private static final String BOB = "ACC-2002";
    private static final String CAROL = "ACC-3003";
    private static final String DAVE = "ACC-4004";
    private static final String MALLORY = "ACC-5005";

    static List<Scenario> all(int maxAttempts) {
        var retried = maxAttempts - 1;
        var approval = new TransferRequest(ALICE, BOB, new BigDecimal("150.00"), "USD", List.of(), true);
        return List.of(
                new Scenario(
                        "happy-path",
                        "Happy path",
                        "Four steps across two services, no failure.",
                        transfer(ALICE, BOB, "120.00"),
                        COMPLETED),
                new Scenario(
                        "limit-exceeded",
                        "Daily limit exceeded",
                        "The first step fails, so there is nothing to undo.",
                        transfer(ALICE, BOB, "6000.00"),
                        REJECTED),
                new Scenario(
                        "insufficient-funds",
                        "Insufficient funds",
                        "The debit fails. The risk service releases the reserved limit.",
                        transfer(CAROL, BOB, "250.00"),
                        COMPENSATED),
                new Scenario(
                        "frozen-destination",
                        "Frozen destination",
                        "The credit fails. The debit is refunded and the limit released, across both services.",
                        transfer(ALICE, DAVE, "90.00"),
                        COMPENSATED),
                new Scenario(
                        "watchlisted-beneficiary",
                        "Watchlisted beneficiary",
                        "The last step fails. Credit, debit and limit are undone in reverse order.",
                        transfer(ALICE, MALLORY, "75.00"),
                        COMPENSATED),
                new Scenario(
                        "lost-reply",
                        "Lost reply",
                        "The debit commits but its reply is lost. The orchestrator resends and the account service answers from its step record. Money moves once.",
                        transfer(ALICE, BOB, "40.00", new Fault(DEBIT_SOURCE, EXECUTE, LOSE_REPLY, 1)),
                        COMPLETED),
                new Scenario(
                        "crash-then-recover",
                        "Crash, then recover",
                        "The credit rolls back before commit on every attempt but the last. The last attempt applies it.",
                        transfer(
                                ALICE,
                                BOB,
                                "55.00",
                                new Fault(CREDIT_DESTINATION, EXECUTE, CRASH_BEFORE_COMMIT, retried)),
                        COMPLETED),
                new Scenario(
                        "compensation-retried",
                        "Compensation retried",
                        "The refund crashes on the first attempts. Compensations retry until they succeed.",
                        transfer(
                                ALICE,
                                DAVE,
                                "65.00",
                                new Fault(DEBIT_SOURCE, COMPENSATE, CRASH_BEFORE_COMMIT, retried)),
                        COMPENSATED),
                new Scenario(
                        "unknown-outcome",
                        "Unknown outcome",
                        "Every credit reply is lost. Once the attempts run out the outcome is unknown, so the credit that did apply is reversed with the rest.",
                        transfer(ALICE, BOB, "30.00", new Fault(CREDIT_DESTINATION, EXECUTE, LOSE_REPLY, maxAttempts)),
                        COMPENSATED),
                new Scenario(
                        "manual-approval",
                        "Manual approval",
                        "The debit holds the money and the saga waits for you. Approve and it completes. Decline, or let it expire, and the debit and the limit are rolled back.",
                        approval,
                        COMPLETED));
    }

    private Scenarios() {}

    private static TransferRequest transfer(String from, String to, String amount, Fault... faults) {
        return new TransferRequest(from, to, new BigDecimal(amount), "USD", List.of(faults), false);
    }
}
