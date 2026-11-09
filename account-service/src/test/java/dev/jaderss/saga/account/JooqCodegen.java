package dev.jaderss.saga.account;

import static dev.jaderss.saga.messaging.test.JooqGenerator.enumColumns;

import dev.jaderss.saga.messaging.test.JooqGenerator;
import java.util.List;
import org.junit.jupiter.api.Test;

class JooqCodegen {

    @Test
    void generates_the_account_tables() throws Exception {
        JooqGenerator.generate(
                "dev.jaderss.saga.account.db",
                List.of("classpath:db/migration"),
                enumColumns(AccountStatus.class, "account\\.status"));
    }
}
