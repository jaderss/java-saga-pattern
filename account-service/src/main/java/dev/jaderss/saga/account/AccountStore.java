package dev.jaderss.saga.account;

import static dev.jaderss.saga.account.db.Tables.ACCOUNT;
import static dev.jaderss.saga.account.db.Tables.ACCOUNT_ENTRY;
import static org.jooq.Records.mapping;

import dev.jaderss.saga.messaging.contract.Money;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.Record5;
import org.jooq.SelectJoinStep;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class AccountStore {

    private static final ResourceDatabasePopulator RESET =
            new ResourceDatabasePopulator(new ClassPathResource("db/seed/reset.sql"));

    private final DSLContext db;
    private final DataSource dataSource;
    private final Clock clock;

    AccountStore(DSLContext db, DataSource dataSource, Clock clock) {
        this.db = db;
        this.dataSource = dataSource;
        this.clock = clock;
    }

    List<Account> all() {
        return selectAccounts().orderBy(ACCOUNT.ID).fetch(mapping(Account::new));
    }

    Optional<Account> find(String id) {
        return selectAccounts().where(ACCOUNT.ID.eq(id)).fetchOptional(mapping(Account::new));
    }

    boolean debit(String id, Money money) {
        return db.update(ACCOUNT)
                        .set(ACCOUNT.BALANCE, ACCOUNT.BALANCE.minus(money.amount()))
                        .where(
                                ACCOUNT.ID.eq(id),
                                ACCOUNT.STATUS.eq(AccountStatus.ACTIVE),
                                ACCOUNT.CURRENCY.eq(money.currency().getCurrencyCode()),
                                ACCOUNT.BALANCE.ge(money.amount()))
                        .execute()
                == 1;
    }

    boolean credit(String id, Money money) {
        return db.update(ACCOUNT)
                        .set(ACCOUNT.BALANCE, ACCOUNT.BALANCE.plus(money.amount()))
                        .where(
                                ACCOUNT.ID.eq(id),
                                ACCOUNT.STATUS.eq(AccountStatus.ACTIVE),
                                ACCOUNT.CURRENCY.eq(money.currency().getCurrencyCode()))
                        .execute()
                == 1;
    }

    void correct(String id, BigDecimal signedAmount) {
        var updated = db.update(ACCOUNT)
                .set(ACCOUNT.BALANCE, ACCOUNT.BALANCE.plus(signedAmount))
                .where(ACCOUNT.ID.eq(id))
                .execute();
        if (updated != 1) {
            throw new IllegalStateException("account " + id + " vanished before its correction");
        }
    }

    void entry(String accountId, UUID sagaId, EntryKind kind, BigDecimal signedAmount) {
        db.insertInto(
                        ACCOUNT_ENTRY,
                        ACCOUNT_ENTRY.ACCOUNT_ID,
                        ACCOUNT_ENTRY.SAGA_ID,
                        ACCOUNT_ENTRY.KIND,
                        ACCOUNT_ENTRY.AMOUNT,
                        ACCOUNT_ENTRY.CREATED_AT)
                .values(accountId, sagaId, kind.name(), signedAmount, OffsetDateTime.now(clock))
                .execute();
    }

    boolean empty() {
        return !db.fetchExists(ACCOUNT);
    }

    @Transactional
    void reset() {
        RESET.execute(dataSource);
    }

    private SelectJoinStep<Record5<String, String, String, BigDecimal, AccountStatus>> selectAccounts() {
        return db.select(ACCOUNT.ID, ACCOUNT.OWNER_NAME, ACCOUNT.CURRENCY, ACCOUNT.BALANCE, ACCOUNT.STATUS)
                .from(ACCOUNT);
    }
}
