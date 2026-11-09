package dev.jaderss.saga.messaging;

import dev.jaderss.saga.messaging.contract.Participant;
import dev.jaderss.saga.messaging.contract.Topics;
import dev.jaderss.saga.messaging.outbox.OutboxMetrics;
import dev.jaderss.saga.messaging.outbox.OutboxRelay;
import dev.jaderss.saga.messaging.outbox.OutboxWriter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.jooq.DSLContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.jooq.autoconfigure.JooqAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.backoff.ExponentialBackOff;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@EnableScheduling
@AutoConfiguration(after = JooqAutoConfiguration.class)
public class MessagingAutoConfiguration {

    static final int PARTITIONS = 3;

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    DefaultKafkaProducerFactoryCustomizer orderedProducer() {
        return factory -> factory.updateConfigs(Map.of(
                ProducerConfig.ACKS_CONFIG,
                "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,
                true,
                ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION,
                5,
                ProducerConfig.MAX_BLOCK_MS_CONFIG,
                2_000,
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,
                5_000,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,
                9_000));
    }

    @Bean
    OutboxWriter outboxWriter(DSLContext db, JsonMapper json) {
        return new OutboxWriter(db, json);
    }

    @Bean
    OutboxRelay outboxRelay(DSLContext db, KafkaTemplate<String, String> kafka, TransactionTemplate tx) {
        return new OutboxRelay(db, kafka, tx);
    }

    @Bean
    KafkaAdmin.NewTopics sagaTopics() {
        var topics = Stream.concat(
                        Stream.of(Participant.values()).map(Participant::commandTopic), Stream.of(Topics.REPLIES))
                .toList();
        return new KafkaAdmin.NewTopics(Stream.concat(
                        topics.stream()
                                .map(name -> TopicBuilder.name(name)
                                        .partitions(PARTITIONS)
                                        .build()),
                        topics.stream()
                                .map(name -> TopicBuilder.name(name + Topics.DEAD_LETTER_SUFFIX)
                                        .partitions(1)
                                        .build()))
                .toArray(NewTopic[]::new));
    }

    @Bean
    OutboxMetrics outboxMetrics(DSLContext db) {
        return new OutboxMetrics(db);
    }

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafka, ObjectProvider<MeterRegistry> meters) {
        var deadLetter = new DeadLetterPublishingRecoverer(
                kafka, (message, _) -> new TopicPartition(message.topic() + Topics.DEAD_LETTER_SUFFIX, 0));
        ConsumerRecordRecoverer recoverer = (message, error) -> {
            meters.ifAvailable(registry -> registry.counter("kafka.dead.letter", "topic", message.topic())
                    .increment());
            deadLetter.accept(message, error);
        };
        var backOff = new ExponentialBackOff(200, 2);
        backOff.setMaxElapsedTime(5_000);
        var handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(
                JacksonException.class, IllegalArgumentException.class, NullPointerException.class);
        return handler;
    }
}
