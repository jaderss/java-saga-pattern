package dev.jaderss.saga.transfer.persistence;

import static dev.jaderss.saga.transfer.persistence.db.Tables.SAGA;
import static dev.jaderss.saga.transfer.persistence.db.Tables.SAGA_EVENT;
import static dev.jaderss.saga.transfer.persistence.db.Tables.SAGA_STEP;
import static org.jooq.Records.mapping;
import static org.jooq.impl.DSL.multiset;
import static org.jooq.impl.DSL.select;

import dev.jaderss.saga.messaging.contract.Action;
import dev.jaderss.saga.messaging.contract.Step;
import dev.jaderss.saga.messaging.contract.TransferTerms;
import dev.jaderss.saga.transfer.domain.SagaEventType;
import dev.jaderss.saga.transfer.domain.SagaStatus;
import dev.jaderss.saga.transfer.domain.StepStatus;
import dev.jaderss.saga.transfer.persistence.db.tables.records.SagaRecord;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.springframework.stereotype.Repository;

@Repository
public class TransferViews {

    private static final Field<List<StepView>> STEPS = multiset(select(SAGA_STEP.STEP, SAGA_STEP.STATUS)
                    .from(SAGA_STEP)
                    .where(SAGA_STEP.SAGA_ID.eq(SAGA.ID))
                    .orderBy(SAGA_STEP.POSITION))
            .convertFrom(rows -> rows.map(mapping((step, status) -> new StepView(step, serviceOf(step), status))));

    private static final Field<List<EventView>> EVENTS = multiset(select(
                            SAGA_EVENT.ID,
                            SAGA_EVENT.OCCURRED_AT,
                            SAGA_EVENT.TYPE,
                            SAGA_EVENT.STEP,
                            SAGA_EVENT.ACTION,
                            SAGA_EVENT.ATTEMPT,
                            SAGA_EVENT.DETAIL)
                    .from(SAGA_EVENT)
                    .where(SAGA_EVENT.SAGA_ID.eq(SAGA.ID))
                    .orderBy(SAGA_EVENT.ID))
            .convertFrom(rows -> rows.map(mapping((id, at, type, step, action, attempt, detail) ->
                    new EventView(id, utc(at), type, step, action, attempt, detail))));

    private final DSLContext db;

    TransferViews(DSLContext db) {
        this.db = db;
    }

    public Optional<TransferView> find(UUID id) {
        return db.select(SAGA, STEPS, EVENTS)
                .from(SAGA)
                .where(SAGA.ID.eq(id))
                .fetchOptional(row -> new TransferView(summary(row.value1()), row.value2(), row.value3()));
    }

    public List<TransferSummary> recent(int limit) {
        return db.selectFrom(SAGA).orderBy(SAGA.CREATED_AT.desc()).limit(limit).fetch(TransferViews::summary);
    }

    private static TransferSummary summary(SagaRecord saga) {
        return new TransferSummary(
                saga.getId(), saga.getStatus(), SagaStore.termsOf(saga), saga.getReason(), utc(saga.getCreatedAt()));
    }

    private static OffsetDateTime utc(OffsetDateTime timestamp) {
        return timestamp.withOffsetSameInstant(ZoneOffset.UTC);
    }

    private static String serviceOf(Step step) {
        return step.participant()
                        .map(participant -> participant.name().toLowerCase(Locale.ROOT))
                        .orElse("transfer")
                + "-service";
    }

    public record TransferSummary(
            UUID id, SagaStatus status, TransferTerms terms, String reason, OffsetDateTime createdAt) {}

    public record TransferView(TransferSummary transfer, List<StepView> steps, List<EventView> events) {}

    public record StepView(Step step, String service, StepStatus status) {}

    public record EventView(
            long id, OffsetDateTime at, SagaEventType type, Step step, Action action, int attempt, String detail) {}
}
