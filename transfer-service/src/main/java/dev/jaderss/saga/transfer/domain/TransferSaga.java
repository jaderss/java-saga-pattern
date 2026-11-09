package dev.jaderss.saga.transfer.domain;

import static dev.jaderss.saga.messaging.contract.Step.APPROVE_TRANSFER;
import static dev.jaderss.saga.messaging.contract.Step.CLEAR_TRANSFER;
import static dev.jaderss.saga.messaging.contract.Step.CREDIT_DESTINATION;
import static dev.jaderss.saga.messaging.contract.Step.DEBIT_SOURCE;
import static dev.jaderss.saga.messaging.contract.Step.RESERVE_LIMIT;
import static dev.jaderss.saga.transfer.domain.SagaEventType.COMMAND_SENT;
import static dev.jaderss.saga.transfer.domain.SagaEventType.REPLY_IGNORED;

import dev.jaderss.saga.messaging.contract.Action;
import dev.jaderss.saga.messaging.contract.Outcome;
import dev.jaderss.saga.messaging.contract.SagaCommand;
import dev.jaderss.saga.messaging.contract.SagaReply;
import dev.jaderss.saga.messaging.contract.Step;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;

public record TransferSaga(
        TransferOrder order, SagaStatus status, List<StepStatus> steps, int cursor, int attempt, String reason) {

    private static final List<Step> DIRECT = List.of(RESERVE_LIMIT, DEBIT_SOURCE, CREDIT_DESTINATION, CLEAR_TRANSFER);
    private static final List<Step> WITH_APPROVAL =
            List.of(RESERVE_LIMIT, DEBIT_SOURCE, APPROVE_TRANSFER, CREDIT_DESTINATION, CLEAR_TRANSFER);

    public TransferSaga {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(status, "status");
        steps = List.copyOf(steps);
        if (steps.size() != planOf(order).size()) {
            throw new IllegalArgumentException("saga needs %d step statuses, got %d"
                    .formatted(planOf(order).size(), steps.size()));
        }
    }

    public static List<Step> planOf(TransferOrder order) {
        return order.requiresApproval() ? WITH_APPROVAL : DIRECT;
    }

    public static Transition start(TransferOrder order) {
        var steps = new ArrayList<>(Collections.nCopies(planOf(order).size(), StepStatus.PENDING));
        steps.set(0, StepStatus.IN_PROGRESS);
        var saga = new TransferSaga(order, SagaStatus.RUNNING, steps, 0, 1, null);
        return saga.enter(
                SagaEvent.of(SagaEventType.STARTED, order.terms().amount().toString()));
    }

    public List<Step> plan() {
        return planOf(order);
    }

    public Step currentStep() {
        return plan().get(cursor);
    }

    public boolean awaitingApproval() {
        return status == SagaStatus.RUNNING && currentStep().manual();
    }

    public Action currentAction() {
        return status == SagaStatus.COMPENSATING ? Action.COMPENSATE : Action.EXECUTE;
    }

    public Transition onReply(SagaReply reply) {
        if (status.terminal() || reply.step() != currentStep() || reply.action() != currentAction()) {
            return unchanged(event(REPLY_IGNORED, reply, "no longer waiting for this reply"));
        }
        var replayed = reply.duplicate() ? "answered from the participant's step record" : null;
        if (reply.action() == Action.COMPENSATE) {
            var compensated = with(cursor, StepStatus.COMPENSATED);
            return compensated.compensateFrom(
                    cursor - 1, event(SagaEventType.STEP_COMPENSATED, reply, detail(reply.reason(), replayed)));
        }
        if (reply.outcome() == Outcome.REJECTED) {
            var rejected = with(cursor, StepStatus.REJECTED)
                    .withReason("%s rejected: %s".formatted(currentStep(), reply.reason()));
            return rejected.compensateFrom(cursor - 1, event(SagaEventType.STEP_REJECTED, reply, reply.reason()));
        }
        return advance(event(SagaEventType.STEP_SUCCEEDED, reply, replayed));
    }

    public Transition onDecision(boolean approved) {
        if (!awaitingApproval()) {
            throw new NotAwaitingApprovalException(order.id());
        }
        return approved ? advance(manualEvent(SagaEventType.STEP_SUCCEEDED, "approved")) : decline("declined");
    }

    public Transition onTimeout(int maxAttempts) {
        if (status.terminal()) {
            return unchanged();
        }
        if (awaitingApproval()) {
            return decline("no decision before the deadline");
        }
        var timedOut = new SagaEvent(SagaEventType.TIMED_OUT, currentStep(), currentAction(), attempt, "no reply");
        if (status == SagaStatus.COMPENSATING || attempt < maxAttempts) {
            return new TransferSaga(order, status, steps, cursor, attempt + 1, reason).send(timedOut);
        }
        var exhausted = with(cursor, StepStatus.TIMED_OUT)
                .withReason("%s: no reply after %d attempts".formatted(currentStep(), attempt));
        return exhausted.compensateFrom(
                cursor,
                timedOut,
                new SagaEvent(
                        SagaEventType.RETRIES_EXHAUSTED, currentStep(), currentAction(), attempt, "outcome unknown"));
    }

    public SagaCommand command() {
        return new SagaCommand(order.id(), currentStep(), currentAction(), attempt, order.terms(), order.faults());
    }

    private Transition advance(SagaEvent stepEvent) {
        var succeeded = with(cursor, StepStatus.SUCCEEDED);
        if (cursor == plan().size() - 1) {
            var done = new TransferSaga(order, SagaStatus.COMPLETED, succeeded.steps, cursor, attempt, null);
            return done.settled(stepEvent, SagaEvent.of(SagaEventType.COMPLETED, "every step applied"));
        }
        var next = new TransferSaga(order, status, succeeded.steps, cursor + 1, 1, reason)
                .with(cursor + 1, StepStatus.IN_PROGRESS);
        return next.enter(stepEvent);
    }

    private Transition decline(String why) {
        var declined = with(cursor, StepStatus.REJECTED).withReason("%s rejected: %s".formatted(currentStep(), why));
        return declined.compensateFrom(cursor - 1, manualEvent(SagaEventType.STEP_REJECTED, why));
    }

    private Transition compensateFrom(int from, SagaEvent... events) {
        var target = IntStream.iterate(from, i -> i >= 0, i -> i - 1)
                .filter(i -> steps.get(i).needsCompensation() && !plan().get(i).manual())
                .findFirst();
        if (target.isEmpty()) {
            var terminal = status == SagaStatus.COMPENSATING ? SagaStatus.COMPENSATED : SagaStatus.REJECTED;
            var finished = new TransferSaga(order, terminal, steps, cursor, attempt, reason);
            return finished.settled(append(events, SagaEvent.of(SagaEventType.valueOf(terminal.name()), reason)));
        }
        var i = target.getAsInt();
        return new TransferSaga(order, SagaStatus.COMPENSATING, steps, i, 1, reason)
                .with(i, StepStatus.COMPENSATING)
                .send(events);
    }

    private Transition enter(SagaEvent... events) {
        if (awaitingApproval()) {
            var waiting = manualEvent(SagaEventType.AWAITING_APPROVAL, null);
            return new Transition(this, List.of(), List.of(append(events, waiting)), true);
        }
        return send(events);
    }

    private Transition send(SagaEvent... events) {
        var command = command();
        var sent = new SagaEvent(COMMAND_SENT, command.step(), command.action(), command.attempt(), null);
        return new Transition(this, List.of(command), List.of(append(events, sent)), true);
    }

    private Transition settled(SagaEvent... events) {
        return new Transition(this, List.of(), List.of(events), true);
    }

    private Transition unchanged(SagaEvent... events) {
        return new Transition(this, List.of(), List.of(events), false);
    }

    private SagaEvent manualEvent(SagaEventType type, String detail) {
        return new SagaEvent(type, currentStep(), Action.EXECUTE, attempt, detail);
    }

    private TransferSaga with(int index, StepStatus stepStatus) {
        var updated = new ArrayList<>(steps);
        updated.set(index, stepStatus);
        return new TransferSaga(order, status, updated, cursor, attempt, reason);
    }

    private TransferSaga withReason(String newReason) {
        return new TransferSaga(order, status, steps, cursor, attempt, newReason);
    }

    private static SagaEvent event(SagaEventType type, SagaReply reply, String detail) {
        return new SagaEvent(type, reply.step(), reply.action(), reply.attempt(), detail);
    }

    private static String detail(String first, String second) {
        return first != null ? first : second;
    }

    private static SagaEvent[] append(SagaEvent[] events, SagaEvent last) {
        var all = new ArrayList<>(List.of(events));
        all.add(last);
        return all.toArray(SagaEvent[]::new);
    }
}
