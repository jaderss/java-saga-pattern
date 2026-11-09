package dev.jaderss.saga.risk;

import static dev.jaderss.saga.risk.db.Tables.LIMIT_USAGE;
import static dev.jaderss.saga.risk.db.Tables.RESERVATION;
import static dev.jaderss.saga.risk.db.Tables.WATCHLIST;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.select;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class RiskStore {

    private static final ResourceDatabasePopulator RESET =
            new ResourceDatabasePopulator(new ClassPathResource("db/seed/reset.sql"));

    private final DSLContext db;
    private final DataSource dataSource;

    RiskStore(DSLContext db, DataSource dataSource) {
        this.db = db;
        this.dataSource = dataSource;
    }

    boolean reserve(UUID sagaId, String accountId, LocalDate day, BigDecimal amount, BigDecimal limit) {
        db.insertInto(LIMIT_USAGE, LIMIT_USAGE.ACCOUNT_ID, LIMIT_USAGE.DAY)
                .values(accountId, day)
                .onConflictDoNothing()
                .execute();
        var reserved = db.update(LIMIT_USAGE)
                        .set(LIMIT_USAGE.RESERVED, LIMIT_USAGE.RESERVED.plus(amount))
                        .where(
                                LIMIT_USAGE.ACCOUNT_ID.eq(accountId),
                                LIMIT_USAGE.DAY.eq(day),
                                LIMIT_USAGE
                                        .RESERVED
                                        .plus(LIMIT_USAGE.USED)
                                        .plus(amount)
                                        .le(limit))
                        .execute()
                == 1;
        if (reserved) {
            db.insertInto(RESERVATION, RESERVATION.SAGA_ID, RESERVATION.ACCOUNT_ID, RESERVATION.DAY, RESERVATION.AMOUNT)
                    .values(sagaId, accountId, day, amount)
                    .execute();
        }
        return reserved;
    }

    void clear(UUID sagaId) {
        onReservation(
                sagaId,
                Map.of(
                        LIMIT_USAGE.RESERVED, LIMIT_USAGE.RESERVED.minus(RESERVATION.AMOUNT),
                        LIMIT_USAGE.USED, LIMIT_USAGE.USED.plus(RESERVATION.AMOUNT)));
    }

    void unclear(UUID sagaId) {
        onReservation(
                sagaId,
                Map.of(
                        LIMIT_USAGE.RESERVED, LIMIT_USAGE.RESERVED.plus(RESERVATION.AMOUNT),
                        LIMIT_USAGE.USED, LIMIT_USAGE.USED.minus(RESERVATION.AMOUNT)));
    }

    void release(UUID sagaId) {
        onReservation(sagaId, Map.of(LIMIT_USAGE.RESERVED, LIMIT_USAGE.RESERVED.minus(RESERVATION.AMOUNT)));
    }

    private void onReservation(UUID sagaId, Map<Field<BigDecimal>, Field<BigDecimal>> assignments) {
        var updated = db.update(LIMIT_USAGE)
                .set(assignments)
                .from(RESERVATION)
                .where(
                        RESERVATION.SAGA_ID.eq(sagaId),
                        LIMIT_USAGE.ACCOUNT_ID.eq(RESERVATION.ACCOUNT_ID),
                        LIMIT_USAGE.DAY.eq(RESERVATION.DAY))
                .execute();
        if (updated != 1) {
            throw new IllegalStateException("no reservation for saga " + sagaId);
        }
    }

    Optional<String> watchlistReason(String accountId) {
        return db.select(WATCHLIST.REASON)
                .from(WATCHLIST)
                .where(WATCHLIST.ACCOUNT_ID.eq(accountId))
                .fetchOptional(WATCHLIST.REASON);
    }

    List<LimitUsage> usage(LocalDate day, BigDecimal limit) {
        var accounts = select(LIMIT_USAGE.ACCOUNT_ID)
                .from(LIMIT_USAGE)
                .where(LIMIT_USAGE.DAY.eq(day))
                .union(select(WATCHLIST.ACCOUNT_ID).from(WATCHLIST))
                .asTable("accounts");
        var accountId = accounts.field(LIMIT_USAGE.ACCOUNT_ID);
        return db.select(
                        accountId,
                        coalesce(LIMIT_USAGE.RESERVED, BigDecimal.ZERO),
                        coalesce(LIMIT_USAGE.USED, BigDecimal.ZERO),
                        WATCHLIST.REASON)
                .from(accounts)
                .leftJoin(LIMIT_USAGE)
                .on(LIMIT_USAGE.ACCOUNT_ID.eq(accountId), LIMIT_USAGE.DAY.eq(day))
                .leftJoin(WATCHLIST)
                .on(WATCHLIST.ACCOUNT_ID.eq(accountId))
                .orderBy(accountId)
                .fetch(row -> new LimitUsage(row.value1(), day, limit, row.value2(), row.value3(), row.value4()));
    }

    boolean empty() {
        return !db.fetchExists(WATCHLIST);
    }

    @Transactional
    void reset() {
        RESET.execute(dataSource);
    }
}
