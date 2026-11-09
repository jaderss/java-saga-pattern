package dev.jaderss.saga.transfer.app;

import dev.jaderss.saga.messaging.contract.SagaReply;
import dev.jaderss.saga.messaging.outbox.OutboxWriter;
import dev.jaderss.saga.transfer.domain.TransferOrder;
import dev.jaderss.saga.transfer.domain.TransferSaga;
import dev.jaderss.saga.transfer.domain.Transition;
import dev.jaderss.saga.transfer.persistence.SagaEvents;
import dev.jaderss.saga.transfer.persistence.SagaStore;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TransferSagas {

    private static final Logger log = LoggerFactory.getLogger(TransferSagas.class);

    private final SagaStore sagas;
    private final SagaEvents events;
    private final OutboxWriter outbox;
    private final ApplicationEventPublisher publisher;
    private final MeterRegistry meters;
    private final SagaProperties properties;
    private final Clock clock;
    private final TransactionTemplate tx;

    TransferSagas(
            SagaStore sagas,
            SagaEvents events,
            OutboxWriter outbox,
            ApplicationEventPublisher publisher,
            MeterRegistry meters,
            SagaProperties properties,
            Clock clock,
            TransactionTemplate tx) {
        this.sagas = sagas;
        this.events = events;
        this.outbox = outbox;
        this.publisher = publisher;
        this.meters = meters;
        this.properties = properties;
        this.clock = clock;
        this.tx = tx;
    }

    @Transactional
    public Started start(String idempotencyKey, TransferOrder order) {
        var existing = sagas.findOrder(idempotencyKey);
        if (existing.isPresent()) {
            var first = existing.get();
            if (!first.sameRequestAs(order)) {
                throw new IdempotencyConflictException(idempotencyKey);
            }
            return new Started(first.id(), false);
        }
        var transition = TransferSaga.start(order);
        var now = now();
        sagas.insert(transition.saga(), idempotencyKey, now, deadline(transition.saga(), now));
        publish(transition, now);
        return new Started(order.id(), true);
    }

    @Transactional
    public void onReply(SagaReply reply) {
        sagas.lock(reply.sagaId())
                .ifPresentOrElse(
                        saga -> apply(saga.onReply(reply)),
                        () -> log.warn("reply for unknown saga {} ignored", reply.sagaId()));
    }

    @Transactional
    public boolean decide(UUID id, boolean approved) {
        var saga = sagas.lock(id);
        saga.ifPresent(found -> {
            apply(found.onDecision(approved));
            meters.counter("saga.decisions", "approved", String.valueOf(approved))
                    .increment();
            log.info("saga {} {} by the approver", id, approved ? "approved" : "declined");
        });
        return saga.isPresent();
    }

    @Scheduled(fixedDelayString = "${saga.timeout-scan-interval:250ms}")
    public void resendOverdue() {
        for (var id : sagas.dueIds(now(), properties.timeoutBatch())) {
            try (var _ = MDC.putCloseable("sagaId", id.toString())) {
                resendIfDue(id);
            }
        }
    }

    private void resendIfDue(UUID id) {
        try {
            tx.executeWithoutResult(
                    _ -> sagas.lockIfDue(id, now()).ifPresent(saga -> apply(saga.onTimeout(properties.maxAttempts()))));
        } catch (RuntimeException e) {
            log.error("timeout handling failed, saga parked for {}", properties.parkOnFailure(), e);
            meters.counter("saga.parked").increment();
            tx.executeWithoutResult(_ -> sagas.postpone(id, now().plus(properties.parkOnFailure())));
        }
    }

    private void apply(Transition transition) {
        var now = now();
        var saga = transition.saga();
        if (transition.changesState()) {
            sagas.update(saga, now, deadline(saga, now));
        }
        publish(transition, now);
        if (saga.status().terminal() && transition.changesState()) {
            meters.counter("saga.finished", "status", saga.status().name()).increment();
            log.info("saga {} finished as {}", saga.order().id(), saga.status());
        }
    }

    private OffsetDateTime deadline(TransferSaga saga, OffsetDateTime now) {
        if (saga.status().terminal()) {
            return null;
        }
        return now.plus(saga.awaitingApproval() ? properties.approvalTimeout() : properties.waitFor(saga.attempt()));
    }

    private void publish(Transition transition, OffsetDateTime now) {
        var id = transition.saga().order().id();
        transition.commands().forEach(command -> {
            outbox.append(command.step().participant().orElseThrow().commandTopic(), id, command);
            if (command.attempt() > 1) {
                meters.counter(
                                "saga.retries",
                                "step",
                                command.step().name(),
                                "action",
                                command.action().name())
                        .increment();
            }
        });
        events.append(id, now, transition.events());
        if (!transition.events().isEmpty()) {
            publisher.publishEvent(new SagaChanged(id));
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    public record Started(UUID id, boolean created) {}
}
