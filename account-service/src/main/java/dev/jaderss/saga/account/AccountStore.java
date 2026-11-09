package dev.jaderss.saga.account;

import dev.jaderss.saga.messaging.contract.Money;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
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
class AccountStore {

    private static final ResourceDatabasePopulator RESET =
            new ResourceDatabasePopulator(new ClassPathResource("db/seed/reset.sql"));

    private final JdbcClient jdbc;
    private final DataSource dataSource;
    private final Clock clock;

    AccountStore(JdbcClient jdbc, DataSource dataSource, Clock clock) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.clock = clock;
    }

    List<Account> all() {
        return jdbc.sql("SELECT id, owner_name, currency, balance, status FROM account ORDER BY id")
                .query(Account.class)
                .list();
    }

    Optional<Account> find(String id) {
        return jdbc.sql("SELECT id, owner_name, currency, balance, status FROM account WHERE id = ?")
                .param(id)
                .query(Account.class)
                .optional();
    }

    boolean debit(String id, Money money) {
        return jdbc.sql("""
                        UPDATE account SET balance = balance - :amount
                        WHERE id = :id AND status = 'ACTIVE' AND currency = :currency AND balance >= :amount
                        """)
                        .param("id", id)
                        .param("amount", money.amount())
                        .param("currency", money.currency().getCurrencyCode())
                        .update()
                == 1;
    }

    boolean credit(String id, Money money) {
        return jdbc.sql("""
                        UPDATE account SET balance = balance + :amount
                        WHERE id = :id AND status = 'ACTIVE' AND currency = :currency
                        """)
                        .param("id", id)
                        .param("amount", money.amount())
                        .param("currency", money.currency().getCurrencyCode())
                        .update()
                == 1;
    }

    void correct(String id, BigDecimal signedAmount) {
        var updated = jdbc.sql("UPDATE account SET balance = balance + ? WHERE id = ?")
                .params(signedAmount, id)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("account " + id + " vanished before its correction");
        }
    }

    void entry(String accountId, UUID sagaId, EntryKind kind, BigDecimal signedAmount) {
        jdbc.sql("""
                        INSERT INTO account_entry (account_id, saga_id, kind, amount, created_at)
                        VALUES (?, ?, ?, ?, ?)
                        """)
                .params(accountId, sagaId, kind.name(), signedAmount, OffsetDateTime.now(clock))
                .update();
    }

    boolean empty() {
        return jdbc.sql("SELECT count(*) FROM account").query(Long.class).single() == 0;
    }

    @Transactional
    void reset() {
        RESET.execute(dataSource);
    }
}
