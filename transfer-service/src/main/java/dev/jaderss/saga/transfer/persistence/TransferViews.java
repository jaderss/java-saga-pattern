package dev.jaderss.saga.transfer.persistence;

import dev.jaderss.saga.messaging.contract.Action;
import dev.jaderss.saga.messaging.contract.Money;
import dev.jaderss.saga.messaging.contract.Step;
import dev.jaderss.saga.messaging.contract.TransferTerms;
import dev.jaderss.saga.transfer.domain.SagaEventType;
import dev.jaderss.saga.transfer.domain.SagaStatus;
import dev.jaderss.saga.transfer.domain.StepStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TransferViews {

    private static final String SUMMARY =
            "SELECT id, status, source_account_id, destination_account_id, amount, currency, reason, created_at FROM saga";

    private final JdbcClient jdbc;

    TransferViews(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<TransferView> find(UUID id) {
        return jdbc.sql(SUMMARY + " WHERE id = ?")
                .param(id)
                .query(TransferViews::summary)
                .optional()
                .map(summary -> new TransferView(summary, steps(id), events(id)));
    }

    public List<TransferSummary> recent(int limit) {
        return jdbc.sql(SUMMARY + " ORDER BY created_at DESC LIMIT ?")
                .param(limit)
                .query(TransferViews::summary)
                .list();
    }

    private List<StepView> steps(UUID id) {
        return jdbc.sql("SELECT step, status FROM saga_step WHERE saga_id = ? ORDER BY position")
                .param(id)
                .query((rs, _) -> {
                    var step = Step.valueOf(rs.getString("step"));
                    return new StepView(step, serviceOf(step), StepStatus.valueOf(rs.getString("status")));
                })
                .list();
    }

    private static String serviceOf(Step step) {
        return step.participant()
                        .map(participant -> participant.name().toLowerCase(Locale.ROOT))
                        .orElse("transfer")
                + "-service";
    }

    private List<EventView> events(UUID id) {
        return jdbc.sql("""
                        SELECT id, occurred_at, type, step, action, attempt, detail
                        FROM saga_event WHERE saga_id = ? ORDER BY id
                        """)
                .param(id)
                .query((rs, _) -> new EventView(
                        rs.getLong("id"),
                        rs.getObject("occurred_at", OffsetDateTime.class),
                        SagaEventType.valueOf(rs.getString("type")),
                        enumOrNull(Step.class, rs.getString("step")),
                        enumOrNull(Action.class, rs.getString("action")),
                        rs.getInt("attempt"),
                        rs.getString("detail")))
                .list();
    }

    private static TransferSummary summary(ResultSet rs, int row) throws SQLException {
        return new TransferSummary(
                rs.getObject("id", UUID.class),
                SagaStatus.valueOf(rs.getString("status")),
                new TransferTerms(
                        rs.getString("source_account_id"),
                        rs.getString("destination_account_id"),
                        new Money(rs.getBigDecimal("amount"), Currency.getInstance(rs.getString("currency")))),
                rs.getString("reason"),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private static <E extends Enum<E>> E enumOrNull(Class<E> type, String name) {
        return name == null ? null : Enum.valueOf(type, name);
    }

    public record TransferSummary(
            UUID id, SagaStatus status, TransferTerms terms, String reason, OffsetDateTime createdAt) {}

    public record TransferView(TransferSummary transfer, List<StepView> steps, List<EventView> events) {}

    public record StepView(Step step, String service, StepStatus status) {}

    public record EventView(
            long id, OffsetDateTime at, SagaEventType type, Step step, Action action, int attempt, String detail) {}
}
