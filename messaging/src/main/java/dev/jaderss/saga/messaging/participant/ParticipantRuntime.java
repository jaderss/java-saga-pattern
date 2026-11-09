package dev.jaderss.saga.messaging.participant;

import static dev.jaderss.saga.messaging.contract.Outcome.DONE;
import static dev.jaderss.saga.messaging.contract.Outcome.REJECTED;

import dev.jaderss.saga.messaging.contract.FaultKind;
import dev.jaderss.saga.messaging.contract.SagaCommand;
import dev.jaderss.saga.messaging.contract.SagaReply;
import dev.jaderss.saga.messaging.contract.Topics;
import dev.jaderss.saga.messaging.outbox.OutboxWriter;
import dev.jaderss.saga.messaging.participant.StepRecords.StepRecord;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

public class ParticipantRuntime {

    static final String ABORTED_REASON = "aborted by an earlier compensation";
    private static final Logger log = LoggerFactory.getLogger(ParticipantRuntime.class);

    private final StepHandler handler;
    private final StepRecords records;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final Duration stepLatency;

    ParticipantRuntime(
            StepHandler handler,
            StepRecords records,
            OutboxWriter outbox,
            TransactionTemplate tx,
            Duration stepLatency) {
        this.handler = handler;
        this.records = records;
        this.outbox = outbox;
        this.tx = tx;
        this.stepLatency = stepLatency;
    }

    public void handle(SagaCommand command) {
        pause();
        try {
            tx.executeWithoutResult(_ -> apply(command));
        } catch (SimulatedCrash e) {
            log.warn("{}, transaction rolled back", e.getMessage());
        }
    }

    private void apply(SagaCommand command) {
        var reply = switch (command.action()) {
            case EXECUTE -> execute(command);
            case COMPENSATE -> compensate(command);
        };
        if (command.injects(FaultKind.CRASH_BEFORE_COMMIT)) {
            throw new SimulatedCrash("simulated crash on %s %s attempt %d"
                    .formatted(command.action(), command.step(), command.attempt()));
        }
        if (command.injects(FaultKind.LOSE_REPLY)) {
            log.warn("simulated lost reply on {} {} attempt {}", command.action(), command.step(), command.attempt());
            return;
        }
        outbox.append(Topics.REPLIES, command.sagaId(), reply);
    }

    private SagaReply execute(SagaCommand command) {
        return records.lock(command.sagaId(), command.step())
                .map(existing -> replay(command, existing))
                .orElseGet(() -> switch (handler.execute(command)) {
                    case StepResult.Applied _ -> {
                        records.insert(command.sagaId(), command.step(), StepRecordStatus.APPLIED, null);
                        yield command.reply(DONE, null, false);
                    }
                    case StepResult.Rejected(var reason) -> {
                        records.insert(command.sagaId(), command.step(), StepRecordStatus.REJECTED, reason);
                        yield command.reply(REJECTED, reason, false);
                    }
                });
    }

    private static SagaReply replay(SagaCommand command, StepRecord existing) {
        return switch (existing.status()) {
            case APPLIED, COMPENSATED -> command.reply(DONE, null, true);
            case REJECTED -> command.reply(REJECTED, existing.reason(), true);
            case ABORTED -> command.reply(REJECTED, ABORTED_REASON, false);
        };
    }

    private SagaReply compensate(SagaCommand command) {
        var existing = records.lock(command.sagaId(), command.step());
        if (existing.isEmpty()) {
            records.insert(command.sagaId(), command.step(), StepRecordStatus.ABORTED, ABORTED_REASON);
            return command.reply(DONE, "nothing was applied", false);
        }
        if (existing.get().status() != StepRecordStatus.APPLIED) {
            return command.reply(DONE, null, true);
        }
        handler.compensate(command);
        records.markCompensated(command.sagaId(), command.step());
        return command.reply(DONE, null, false);
    }

    private void pause() {
        if (stepLatency.isZero()) {
            return;
        }
        try {
            Thread.sleep(stepLatency);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
