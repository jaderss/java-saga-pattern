package dev.jaderss.saga.transfer.persistence;

import static dev.jaderss.saga.transfer.persistence.db.Tables.SAGA_EVENT;
import static org.jooq.impl.DSL.row;

import dev.jaderss.saga.transfer.domain.SagaEvent;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class SagaEvents {

    private final DSLContext db;

    SagaEvents(DSLContext db) {
        this.db = db;
    }

    public void append(UUID sagaId, OffsetDateTime at, List<SagaEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        db.insertInto(
                        SAGA_EVENT,
                        SAGA_EVENT.SAGA_ID,
                        SAGA_EVENT.OCCURRED_AT,
                        SAGA_EVENT.TYPE,
                        SAGA_EVENT.STEP,
                        SAGA_EVENT.ACTION,
                        SAGA_EVENT.ATTEMPT,
                        SAGA_EVENT.DETAIL)
                .valuesOfRows(events.stream()
                        .map(event -> row(
                                sagaId,
                                at,
                                event.type(),
                                event.step(),
                                event.action(),
                                event.attempt(),
                                event.detail()))
                        .toList())
                .execute();
    }
}
