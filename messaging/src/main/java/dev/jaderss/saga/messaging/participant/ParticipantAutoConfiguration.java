package dev.jaderss.saga.messaging.participant;

import dev.jaderss.saga.messaging.MessagingAutoConfiguration;
import dev.jaderss.saga.messaging.outbox.OutboxWriter;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@AutoConfiguration(after = MessagingAutoConfiguration.class)
@ConditionalOnBean(StepHandler.class)
public class ParticipantAutoConfiguration {

    @Bean
    ParticipantRuntime participantRuntime(
            StepHandler handler,
            JdbcClient jdbc,
            Clock clock,
            OutboxWriter outbox,
            TransactionTemplate tx,
            @Value("${participant.step-latency:0ms}") Duration stepLatency) {
        return new ParticipantRuntime(handler, new StepRecords(jdbc, clock), outbox, tx, stepLatency);
    }

    @Bean
    ParticipantListener participantListener(ParticipantRuntime runtime, JsonMapper json, StepHandler handler) {
        return new ParticipantListener(runtime, json, handler.participant().commandTopic());
    }
}
