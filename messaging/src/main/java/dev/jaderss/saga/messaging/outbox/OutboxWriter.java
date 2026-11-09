package dev.jaderss.saga.messaging.outbox;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

public class OutboxWriter {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public OutboxWriter(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(String topic, UUID key, Object message) {
        jdbc.sql("INSERT INTO outbox (topic, msg_key, payload) VALUES (?, ?, ?)")
                .params(topic, key.toString(), json.writeValueAsString(message))
                .update();
    }
}
