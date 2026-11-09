package dev.jaderss.saga.risk;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jaderss.saga.messaging.test.JooqGenerator;
import java.util.List;
import org.junit.jupiter.api.Test;

class JooqCodegen {

    @Test
    void generates_the_risk_tables() throws Exception {
        assertThat(JooqGenerator.generate("dev.jaderss.saga.risk.db", List.of("classpath:db/migration")))
                .isDirectoryContaining("glob:**/Tables.java");
    }
}
