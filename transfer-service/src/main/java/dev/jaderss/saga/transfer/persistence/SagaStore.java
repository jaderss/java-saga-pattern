package dev.jaderss.saga.transfer.persistence;

import static dev.jaderss.saga.messaging.jooq.SqlTime.secondsSinceOldest;
import static dev.jaderss.saga.transfer.persistence.db.Tables.SAGA;
import static dev.jaderss.saga.transfer.persistence.db.Tables.SAGA_STEP;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.row;

import dev.jaderss.saga.messaging.contract.Fault;
import dev.jaderss.saga.messaging.contract.Money;
import dev.jaderss.saga.messaging.contract.TransferTerms;
import dev.jaderss.saga.transfer.domain.SagaStatus;
import dev.jaderss.saga.transfer.domain.TransferOrder;
import dev.jaderss.saga.transfer.domain.TransferSaga;
import dev.jaderss.saga.transfer.persistence.db.tables.records.SagaRecord;
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class SagaStore {

    private static final Condition UNFINISHED =
            SAGA.STATUS.in(inline(SagaStatus.RUNNING, SAGA.STATUS), inline(SagaStatus.COMPENSATING, SAGA.STATUS));

    private final DSLContext db;
    private final JsonMapper json;

    SagaStore(DSLContext db, JsonMapper json) {
        this.db = db;
        this.json = json;
    }

    public void insert(TransferSaga saga, String idempotencyKey, OffsetDateTime now, OffsetDateTime deadline) {
        var order = saga.order();
        var terms = order.terms();
        db.insertInto(SAGA)
                .set(SAGA.ID, order.id())
                .set(SAGA.IDEMPOTENCY_KEY, idempotencyKey)
                .set(SAGA.SOURCE_ACCOUNT_ID, terms.sourceAccountId())
                .set(SAGA.DESTINATION_ACCOUNT_ID, terms.destinationAccountId())
                .set(SAGA.AMOUNT, terms.amount().amount())
                .set(SAGA.CURRENCY, terms.amount().currency().getCurrencyCode())
                .set(SAGA.FAULTS, JSONB.valueOf(json.writeValueAsString(order.faults())))
                .set(SAGA.REQUIRES_APPROVAL, order.requiresApproval())
                .set(SAGA.STATUS, saga.status())
                .set(SAGA.CURRENT_STEP, saga.currentStep())
                .set(SAGA.CURRENT_ACTION, saga.currentAction())
                .set(SAGA.ATTEMPT, saga.attempt())
                .set(SAGA.DEADLINE, deadline)
                .set(SAGA.REASON, saga.reason())
                .set(SAGA.CREATED_AT, now)
                .set(SAGA.UPDATED_AT, now)
                .execute();
        db.insertInto(SAGA_STEP, SAGA_STEP.SAGA_ID, SAGA_STEP.POSITION, SAGA_STEP.STEP, SAGA_STEP.STATUS)
                .valuesOfRows(IntStream.range(0, saga.plan().size())
                        .mapToObj(position -> row(
                                order.id(),
                                position,
                                saga.plan().get(position),
                                saga.steps().get(position)))
                        .toList())
                .execute();
    }

    public void update(TransferSaga saga, OffsetDateTime now, OffsetDateTime deadline) {
        var id = saga.order().id();
        db.update(SAGA)
                .set(SAGA.STATUS, saga.status())
                .set(SAGA.CURRENT_STEP, saga.currentStep())
                .set(SAGA.CURRENT_ACTION, saga.currentAction())
                .set(SAGA.ATTEMPT, saga.attempt())
                .set(SAGA.DEADLINE, deadline)
                .set(SAGA.REASON, saga.reason())
                .set(SAGA.UPDATED_AT, now)
                .where(SAGA.ID.eq(id))
                .execute();
        db.batch(IntStream.range(0, saga.steps().size())
                        .mapToObj(position -> db.update(SAGA_STEP)
                                .set(SAGA_STEP.STATUS, saga.steps().get(position))
                                .where(
                                        SAGA_STEP.SAGA_ID.eq(id),
                                        SAGA_STEP.POSITION.eq(position),
                                        SAGA_STEP.STATUS.ne(saga.steps().get(position))))
                        .toList())
                .execute();
    }

    public Optional<TransferSaga> lock(UUID id) {
        return db.selectFrom(SAGA)
                .where(SAGA.ID.eq(id))
                .forUpdate()
                .fetchOptional(this::loaded)
                .map(this::withSteps);
    }

    public Optional<TransferOrder> findOrder(String idempotencyKey) {
        return db.selectFrom(SAGA)
                .where(SAGA.IDEMPOTENCY_KEY.eq(idempotencyKey))
                .fetchOptional(this::loaded)
                .map(Loaded::order);
    }

    public List<UUID> dueIds(OffsetDateTime now, int limit) {
        return db.select(SAGA.ID)
                .from(SAGA)
                .where(UNFINISHED, SAGA.DEADLINE.lt(now))
                .orderBy(SAGA.DEADLINE)
                .limit(limit)
                .fetch(SAGA.ID);
    }

    public Optional<TransferSaga> lockIfDue(UUID id, OffsetDateTime now) {
        return db.selectFrom(SAGA)
                .where(SAGA.ID.eq(id), UNFINISHED, SAGA.DEADLINE.lt(now))
                .forUpdate()
                .skipLocked()
                .fetchOptional(this::loaded)
                .map(this::withSteps);
    }

    public void postpone(UUID id, OffsetDateTime deadline) {
        db.update(SAGA).set(SAGA.DEADLINE, deadline).where(SAGA.ID.eq(id)).execute();
    }

    public int count(SagaStatus status) {
        return db.fetchCount(SAGA, SAGA.STATUS.eq(status));
    }

    public double secondsSinceOldestIn(SagaStatus status) {
        return db.select(secondsSinceOldest(SAGA.CREATED_AT))
                .from(SAGA)
                .where(SAGA.STATUS.eq(status))
                .fetchSingle()
                .value1();
    }

    static TransferTerms termsOf(SagaRecord saga) {
        return new TransferTerms(
                saga.getSourceAccountId(),
                saga.getDestinationAccountId(),
                new Money(saga.getAmount(), Currency.getInstance(saga.getCurrency())));
    }

    private TransferSaga withSteps(Loaded loaded) {
        var steps = db.select(SAGA_STEP.STATUS)
                .from(SAGA_STEP)
                .where(SAGA_STEP.SAGA_ID.eq(loaded.order().id()))
                .orderBy(SAGA_STEP.POSITION)
                .fetch(SAGA_STEP.STATUS);
        return new TransferSaga(
                loaded.order(), loaded.status(), steps, loaded.cursor(), loaded.attempt(), loaded.reason());
    }

    private Loaded loaded(SagaRecord saga) {
        var faults = List.of(json.readValue(saga.getFaults().data(), Fault[].class));
        var order = new TransferOrder(saga.getId(), termsOf(saga), faults, saga.getRequiresApproval());
        return new Loaded(
                order,
                saga.getStatus(),
                TransferSaga.planOf(order).indexOf(saga.getCurrentStep()),
                saga.getAttempt(),
                saga.getReason());
    }

    private record Loaded(TransferOrder order, SagaStatus status, int cursor, int attempt, String reason) {}
}
