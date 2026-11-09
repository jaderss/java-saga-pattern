CREATE TABLE step_record (
    saga_id     UUID NOT NULL,
    step        TEXT NOT NULL,
    status      TEXT NOT NULL,
    reason      TEXT,
    updated_at  TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (saga_id, step)
);
