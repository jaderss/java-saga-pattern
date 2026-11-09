package dev.jaderss.saga.transfer.app;

import dev.jaderss.saga.messaging.contract.SagaReply;
import dev.jaderss.saga.messaging.contract.Topics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
class ReplyListener {

    private final TransferSagas sagas;
    private final JsonMapper json;

    ReplyListener(TransferSagas sagas, JsonMapper json) {
        this.sagas = sagas;
        this.json = json;
    }

    @KafkaListener(topics = Topics.REPLIES, groupId = "${spring.application.name}")
    void on(ConsumerRecord<String, String> message) {
        try (var _ = MDC.putCloseable("sagaId", message.key())) {
            sagas.onReply(json.readValue(message.value(), SagaReply.class));
        }
    }
}
