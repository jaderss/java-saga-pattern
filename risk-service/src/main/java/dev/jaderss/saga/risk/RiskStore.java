package dev.jaderss.saga.risk;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class RiskStore {

    private static final ResourceDatabasePopulator RESET =
            new ResourceDatabasePopulator(new ClassPathResource("db/seed/reset.sql"));

    private final JdbcClient jdbc;
    private final DataSource dataSource;

    RiskStore(JdbcClient jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
    }

    boolean reserve(UUID sagaId, String accountId, LocalDate day, BigDecimal amount, BigDecimal limit) {
        jdbc.sql("INSERT INTO limit_usage (account_id, day) VALUES (?, ?) ON CONFLICT DO NOTHING")
                .params(accountId, day)
                .update();
        var reserved = jdbc.sql("""
                                UPDATE limit_usage SET reserved = reserved + :amount
                                WHERE account_id = :account AND day = :day AND reserved + used + :amount <= :limit
                                """)
                        .param("amount", amount)
                        .param("account", accountId)
                        .param("day", day)
                        .param("limit", limit)
                        .update()
                == 1;
        if (reserved) {
            jdbc.sql("INSERT INTO reservation (saga_id, account_id, day, amount) VALUES (?, ?, ?, ?)")
                    .params(sagaId, accountId, day, amount)
                    .update();
        }
        return reserved;
    }

    void clear(UUID sagaId) {
        onReservation(sagaId, "reserved = u.reserved - r.amount, used = u.used + r.amount");
    }

    void unclear(UUID sagaId) {
        onReservation(sagaId, "reserved = u.reserved + r.amount, used = u.used - r.amount");
    }

    void release(UUID sagaId) {
        onReservation(sagaId, "reserved = u.reserved - r.amount");
    }

    private void onReservation(UUID sagaId, String assignments) {
        var updated = jdbc.sql("UPDATE limit_usage u SET " + assignments + """
                         FROM reservation r
                        WHERE r.saga_id = ? AND u.account_id = r.account_id AND u.day = r.day
                        """)
                .param(sagaId)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("no reservation for saga " + sagaId);
        }
    }

    Optional<String> watchlistReason(String accountId) {
        return jdbc.sql("SELECT reason FROM watchlist WHERE account_id = ?")
                .param(accountId)
                .query(String.class)
                .optional();
    }

    List<LimitUsage> usage(LocalDate day, BigDecimal limit) {
        return jdbc.sql("""
                        SELECT a.account_id, CAST(:day AS DATE) AS day, CAST(:limit AS NUMERIC(19, 2)) AS daily_limit,
                               COALESCE(u.reserved, 0) AS reserved, COALESCE(u.used, 0) AS used,
                               w.reason AS watchlist_reason
                        FROM (SELECT account_id FROM limit_usage WHERE day = :day UNION SELECT account_id FROM watchlist) a
                        LEFT JOIN limit_usage u ON u.account_id = a.account_id AND u.day = :day
                        LEFT JOIN watchlist w ON w.account_id = a.account_id
                        ORDER BY a.account_id
                        """)
                .param("day", day)
                .param("limit", limit)
                .query(LimitUsage.class)
                .list();
    }

    boolean empty() {
        return jdbc.sql("SELECT count(*) FROM watchlist").query(Long.class).single() == 0;
    }

    @Transactional
    void reset() {
        RESET.execute(dataSource);
    }
}
