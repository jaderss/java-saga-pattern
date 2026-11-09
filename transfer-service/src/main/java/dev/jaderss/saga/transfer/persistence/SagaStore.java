package dev.jaderss.saga.transfer.persistence;

import dev.jaderss.saga.messaging.contract.Fault;
import dev.jaderss.saga.messaging.contract.Money;
import dev.jaderss.saga.messaging.contract.Step;
import dev.jaderss.saga.messaging.contract.TransferTerms;
import dev.jaderss.saga.transfer.domain.SagaStatus;
import dev.jaderss.saga.transfer.domain.StepStatus;
import dev.jaderss.saga.transfer.domain.TransferOrder;
import dev.jaderss.saga.transfer.domain.TransferSaga;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class SagaStore {

    private static final String COLUMNS =
            "id, source_account_id, destination_account_id, amount, currency, faults, requires_approval, status, current_step,"
                    + " attempt, reason";

    private final JdbcClient jdbc;
    private final JsonMapper json;

    SagaStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void insert(TransferSaga saga, String idempotencyKey, OffsetDateTime now, OffsetDateTime deadline) {
        var order = saga.order();
        var terms = order.terms();
        jdbc.sql("""
                        INSERT INTO saga (id, idempotency_key, source_account_id, destination_account_id, amount, currency,
                                          faults, requires_approval, status, current_step, current_action, attempt,
                                          deadline, reason, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(
                        order.id(),
                        idempotencyKey,
                        terms.sourceAccountId(),
                        terms.destinationAccountId(),
                        terms.amount().amount(),
                        terms.amount().currency().getCurrencyCode(),
                        json.writeValueAsString(order.faults()),
                        order.requiresApproval(),
                        saga.status().name(),
                        saga.currentStep().name(),
                        saga.currentAction().name(),
                        saga.attempt(),
                        deadline,
                        saga.reason(),
                        now,
                        now)
                .update();
        for (var position = 0; position < saga.plan().size(); position++) {
            jdbc.sql("INSERT INTO saga_step (saga_id, position, step, status) VALUES (?, ?, ?, ?)")
                    .params(
                            order.id(),
                            position,
                            saga.plan().get(position).name(),
                            saga.steps().get(position).name())
                    .update();
        }
    }

    public void update(TransferSaga saga, OffsetDateTime now, OffsetDateTime deadline) {
        var id = saga.order().id();
        jdbc.sql("""
                        UPDATE saga SET status = ?, current_step = ?, current_action = ?, attempt = ?, deadline = ?,
                                        reason = ?, updated_at = ?
                        WHERE id = ?
                        """)
                .params(
                        saga.status().name(),
                        saga.currentStep().name(),
                        saga.currentAction().name(),
                        saga.attempt(),
                        deadline,
                        saga.reason(),
                        now,
                        id)
                .update();
        for (var position = 0; position < saga.steps().size(); position++) {
            jdbc.sql("UPDATE saga_step SET status = ? WHERE saga_id = ? AND position = ? AND status <> ?")
                    .params(
                            saga.steps().get(position).name(),
                            id,
                            position,
                            saga.steps().get(position).name())
                    .update();
        }
    }

    public Optional<TransferSaga> lock(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM saga WHERE id = ? FOR UPDATE")
                .param(id)
                .query(this::order)
                .optional()
                .map(this::withSteps);
    }

    public Optional<TransferOrder> findOrder(String idempotencyKey) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM saga WHERE idempotency_key = ?")
                .param(idempotencyKey)
                .query(this::order)
                .optional()
                .map(Loaded::order);
    }

    public List<UUID> dueIds(OffsetDateTime now, int limit) {
        return jdbc.sql("""
                        SELECT id FROM saga
                        WHERE status IN ('RUNNING', 'COMPENSATING') AND deadline < ?
                        ORDER BY deadline LIMIT ?
                        """).params(now, limit).query(UUID.class).list();
    }

    public Optional<TransferSaga> lockIfDue(UUID id, OffsetDateTime now) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM saga
                        WHERE id = ? AND status IN ('RUNNING', 'COMPENSATING') AND deadline < ?
                        FOR UPDATE SKIP LOCKED
                        """)
                .params(id, now)
                .query(this::order)
                .optional()
                .map(this::withSteps);
    }

    public void postpone(UUID id, OffsetDateTime deadline) {
        jdbc.sql("UPDATE saga SET deadline = ? WHERE id = ?")
                .params(deadline, id)
                .update();
    }

    private TransferSaga withSteps(Loaded loaded) {
        var steps = jdbc
                .sql("SELECT status FROM saga_step WHERE saga_id = ? ORDER BY position")
                .param(loaded.order().id())
                .query(String.class)
                .list()
                .stream()
                .map(StepStatus::valueOf)
                .toList();
        return new TransferSaga(
                loaded.order(), loaded.status(), steps, loaded.cursor(), loaded.attempt(), loaded.reason());
    }

    private Loaded order(ResultSet rs, int row) throws SQLException {
        var terms = new TransferTerms(
                rs.getString("source_account_id"),
                rs.getString("destination_account_id"),
                new Money(rs.getBigDecimal("amount"), Currency.getInstance(rs.getString("currency"))));
        var faults = List.of(json.readValue(rs.getString("faults"), Fault[].class));
        var order =
                new TransferOrder(rs.getObject("id", UUID.class), terms, faults, rs.getBoolean("requires_approval"));
        return new Loaded(
                order,
                SagaStatus.valueOf(rs.getString("status")),
                TransferSaga.planOf(order).indexOf(Step.valueOf(rs.getString("current_step"))),
                rs.getInt("attempt"),
                rs.getString("reason"));
    }

    private record Loaded(TransferOrder order, SagaStatus status, int cursor, int attempt, String reason) {}
}
