CREATE TABLE saga (
    id                      UUID PRIMARY KEY,
    idempotency_key         TEXT NOT NULL UNIQUE,
    source_account_id       TEXT NOT NULL,
    destination_account_id  TEXT NOT NULL,
    amount                  NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    currency                CHAR(3) NOT NULL,
    faults                  JSONB NOT NULL DEFAULT '[]',
    status                  TEXT NOT NULL,
    current_step            TEXT NOT NULL,
    current_action          TEXT NOT NULL,
    attempt                 INT NOT NULL,
    deadline                TIMESTAMPTZ,
    reason                  TEXT,
    created_at              TIMESTAMPTZ NOT NULL,
    updated_at              TIMESTAMPTZ NOT NULL
);

CREATE INDEX saga_due ON saga (deadline) WHERE status IN ('RUNNING', 'COMPENSATING');
CREATE INDEX saga_recent ON saga (created_at DESC);

CREATE TABLE saga_step (
    saga_id   UUID NOT NULL REFERENCES saga (id),
    position  INT NOT NULL,
    step      TEXT NOT NULL,
    status    TEXT NOT NULL,
    PRIMARY KEY (saga_id, position)
);

CREATE TABLE saga_event (
    id           BIGSERIAL PRIMARY KEY,
    saga_id      UUID NOT NULL REFERENCES saga (id),
    occurred_at  TIMESTAMPTZ NOT NULL,
    type         TEXT NOT NULL,
    step         TEXT,
    action       TEXT,
    attempt      INT NOT NULL,
    detail       TEXT
);

CREATE INDEX saga_event_by_saga ON saga_event (saga_id, id);
