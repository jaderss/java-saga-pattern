package dev.jaderss.saga.transfer.persistence;

import dev.jaderss.saga.transfer.domain.SagaEvent;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SagaEvents {

    private final JdbcClient jdbc;

    SagaEvents(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void append(UUID sagaId, OffsetDateTime at, List<SagaEvent> events) {
        for (var event : events) {
            jdbc.sql("""
                            INSERT INTO saga_event (saga_id, occurred_at, type, step, action, attempt, detail)
                            VALUES (?, ?, ?, ?, ?, ?, ?)
                            """)
                    .params(
                            sagaId,
                            at,
                            event.type().name(),
                            event.step() == null ? null : event.step().name(),
                            event.action() == null ? null : event.action().name(),
                            event.attempt(),
                            event.detail())
                    .update();
        }
    }
}
