package dev.jaderss.saga.messaging.participant;

import dev.jaderss.saga.messaging.contract.Step;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

class StepRecords {

    private final JdbcClient jdbc;
    private final Clock clock;

    StepRecords(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    Optional<StepRecord> lock(UUID sagaId, Step step) {
        return jdbc.sql("SELECT status, reason FROM step_record WHERE saga_id = ? AND step = ? FOR UPDATE")
                .params(sagaId, step.name())
                .query(StepRecord.class)
                .optional();
    }

    void insert(UUID sagaId, Step step, StepRecordStatus status, String reason) {
        jdbc.sql("INSERT INTO step_record (saga_id, step, status, reason, updated_at) VALUES (?, ?, ?, ?, ?)")
                .params(sagaId, step.name(), status.name(), reason, now())
                .update();
    }

    void markCompensated(UUID sagaId, Step step) {
        jdbc.sql("UPDATE step_record SET status = ?, updated_at = ? WHERE saga_id = ? AND step = ?")
                .params(StepRecordStatus.COMPENSATED.name(), now(), sagaId, step.name())
                .update();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    record StepRecord(StepRecordStatus status, String reason) {}
}
