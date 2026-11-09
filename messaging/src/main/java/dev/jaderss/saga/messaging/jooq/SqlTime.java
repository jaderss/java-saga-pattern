package dev.jaderss.saga.messaging.jooq;

import static org.jooq.impl.DSL.field;

import java.time.OffsetDateTime;
import org.jooq.Field;

public final class SqlTime {

    private SqlTime() {}

    public static Field<Double> secondsSinceOldest(Field<OffsetDateTime> timestamp) {
        return field("coalesce(extract(epoch FROM now() - min({0})), 0)", Double.class, timestamp);
    }
}
