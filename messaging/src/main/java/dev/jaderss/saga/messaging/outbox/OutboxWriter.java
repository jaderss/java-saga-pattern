package dev.jaderss.saga.messaging.outbox;

import static dev.jaderss.saga.messaging.db.Tables.OUTBOX;

import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

public class OutboxWriter {

    private final DSLContext db;
    private final JsonMapper json;

    public OutboxWriter(DSLContext db, JsonMapper json) {
        this.db = db;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(String topic, UUID key, Object message) {
        db.insertInto(OUTBOX, OUTBOX.TOPIC, OUTBOX.MSG_KEY, OUTBOX.PAYLOAD)
                .values(topic, key.toString(), json.writeValueAsString(message))
                .execute();
    }
}
