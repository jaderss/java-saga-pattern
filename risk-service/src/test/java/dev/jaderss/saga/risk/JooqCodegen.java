package dev.jaderss.saga.risk;

import dev.jaderss.saga.messaging.test.JooqGenerator;
import java.util.List;
import org.junit.jupiter.api.Test;

class JooqCodegen {

    @Test
    void generates_the_risk_tables() throws Exception {
        JooqGenerator.generate("dev.jaderss.saga.risk.db", List.of("classpath:db/migration"));
    }
}
