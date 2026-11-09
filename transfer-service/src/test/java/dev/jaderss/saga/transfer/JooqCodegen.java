package dev.jaderss.saga.transfer;

import static dev.jaderss.saga.messaging.test.JooqGenerator.enumColumns;
import static org.assertj.core.api.Assertions.assertThat;

import dev.jaderss.saga.messaging.contract.Action;
import dev.jaderss.saga.messaging.contract.Step;
import dev.jaderss.saga.messaging.test.JooqGenerator;
import dev.jaderss.saga.transfer.domain.SagaEventType;
import dev.jaderss.saga.transfer.domain.SagaStatus;
import dev.jaderss.saga.transfer.domain.StepStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

class JooqCodegen {

    @Test
    void generates_the_saga_tables() throws Exception {
        assertThat(JooqGenerator.generate(
                        "dev.jaderss.saga.transfer.persistence.db",
                        List.of("classpath:db/migration"),
                        enumColumns(SagaStatus.class, "saga\\.status"),
                        enumColumns(StepStatus.class, "saga_step\\.status"),
                        enumColumns(Step.class, "saga\\.current_step|saga_step\\.step|saga_event\\.step"),
                        enumColumns(Action.class, "saga\\.current_action|saga_event\\.action"),
                        enumColumns(SagaEventType.class, "saga_event\\.type")))
                .isDirectoryContaining("glob:**/Tables.java");
    }
}
