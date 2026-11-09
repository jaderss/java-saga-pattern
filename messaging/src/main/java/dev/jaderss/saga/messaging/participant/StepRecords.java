package dev.jaderss.saga.messaging.participant;

import static dev.jaderss.saga.messaging.db.Tables.STEP_RECORD;
import static org.jooq.Records.mapping;

import dev.jaderss.saga.messaging.contract.Step;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

class StepRecords {

    private final DSLContext db;
    private final Clock clock;

    StepRecords(DSLContext db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    Optional<StepRecord> lock(UUID sagaId, Step step) {
        return db.select(STEP_RECORD.STATUS, STEP_RECORD.REASON)
                .from(STEP_RECORD)
                .where(STEP_RECORD.SAGA_ID.eq(sagaId), STEP_RECORD.STEP.eq(step))
                .forUpdate()
                .fetchOptional(mapping(StepRecord::new));
    }

    void insert(UUID sagaId, Step step, StepRecordStatus status, String reason) {
        db.insertInto(
                        STEP_RECORD,
                        STEP_RECORD.SAGA_ID,
                        STEP_RECORD.STEP,
                        STEP_RECORD.STATUS,
                        STEP_RECORD.REASON,
                        STEP_RECORD.UPDATED_AT)
                .values(sagaId, step, status, reason, now())
                .execute();
    }

    void markCompensated(UUID sagaId, Step step) {
        db.update(STEP_RECORD)
                .set(STEP_RECORD.STATUS, StepRecordStatus.COMPENSATED)
                .set(STEP_RECORD.UPDATED_AT, now())
                .where(STEP_RECORD.SAGA_ID.eq(sagaId), STEP_RECORD.STEP.eq(step))
                .execute();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    record StepRecord(StepRecordStatus status, String reason) {}
}
