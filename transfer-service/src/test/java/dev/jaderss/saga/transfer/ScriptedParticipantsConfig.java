package dev.jaderss.saga.transfer;

import dev.jaderss.saga.messaging.contract.Outcome;
import dev.jaderss.saga.messaging.contract.SagaCommand;
import dev.jaderss.saga.messaging.contract.Step;
import dev.jaderss.saga.messaging.contract.Topics;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;

@TestConfiguration(proxyBeanMethods = false)
public class ScriptedParticipantsConfig {

    public static final String FROZEN = "FROZEN";
    public static final String SILENT_ONCE = "SILENT-ONCE";

    @Bean
    Scripted scriptedParticipants(KafkaTemplate<String, String> kafka, JsonMapper json) {
        return new Scripted(kafka, json);
    }

    public static class Scripted {

        final List<SagaCommand> received = new CopyOnWriteArrayList<>();
        private final KafkaTemplate<String, String> kafka;
        private final JsonMapper json;

        Scripted(KafkaTemplate<String, String> kafka, JsonMapper json) {
            this.kafka = kafka;
            this.json = json;
        }

        @KafkaListener(
                topics = {"saga.risk.commands", "saga.account.commands"},
                groupId = "scripted-participants")
        void on(ConsumerRecord<String, String> message) {
            var command = json.readValue(message.value(), SagaCommand.class);
            received.add(command);
            var destination = command.terms().destinationAccountId();
            if (destination.equals(SILENT_ONCE) && command.step() == Step.DEBIT_SOURCE && command.attempt() == 1) {
                return;
            }
            var reply = destination.equals(FROZEN) && command.step() == Step.CREDIT_DESTINATION
                    ? command.reply(Outcome.REJECTED, "account FROZEN is frozen", false)
                    : command.reply(Outcome.DONE, null, false);
            kafka.send(Topics.REPLIES, message.key(), json.writeValueAsString(reply));
        }

        public List<String> trail(String sagaId) {
            return received.stream()
                    .filter(command -> command.sagaId().toString().equals(sagaId))
                    .map(command -> command.action().name().charAt(0) + ":" + command.step() + "#" + command.attempt())
                    .toList();
        }
    }
}
