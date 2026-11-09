CREATE TABLE outbox (
    id          BIGSERIAL PRIMARY KEY,
    topic       TEXT NOT NULL,
    msg_key     TEXT NOT NULL,
    payload     TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
