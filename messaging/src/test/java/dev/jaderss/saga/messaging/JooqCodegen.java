package dev.jaderss.saga.messaging;

import static dev.jaderss.saga.messaging.test.JooqGenerator.enumColumns;

import dev.jaderss.saga.messaging.contract.Step;
import dev.jaderss.saga.messaging.participant.StepRecordStatus;
import dev.jaderss.saga.messaging.test.JooqGenerator;
import java.util.List;
import org.junit.jupiter.api.Test;

class JooqCodegen {

    @Test
    void generates_the_messaging_tables() throws Exception {
        JooqGenerator.generate(
                "dev.jaderss.saga.messaging.db",
                List.of("classpath:db/messaging", "classpath:db/participant"),
                enumColumns(StepRecordStatus.class, "step_record\\.status"),
                enumColumns(Step.class, "step_record\\.step"));
    }
}
