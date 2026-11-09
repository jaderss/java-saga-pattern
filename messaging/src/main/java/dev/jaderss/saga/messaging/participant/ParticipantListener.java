package dev.jaderss.saga.messaging.participant;

import dev.jaderss.saga.messaging.contract.SagaCommand;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import tools.jackson.databind.json.JsonMapper;

public class ParticipantListener {

    private final ParticipantRuntime runtime;
    private final JsonMapper json;
    private final String topic;

    ParticipantListener(ParticipantRuntime runtime, JsonMapper json, String topic) {
        this.runtime = runtime;
        this.json = json;
        this.topic = topic;
    }

    public String topic() {
        return topic;
    }

    @KafkaListener(topics = "#{__listener.topic()}", groupId = "${spring.application.name}")
    void on(ConsumerRecord<String, String> message) {
        try (var _ = MDC.putCloseable("sagaId", message.key())) {
            runtime.handle(json.readValue(message.value(), SagaCommand.class));
        }
    }
}
