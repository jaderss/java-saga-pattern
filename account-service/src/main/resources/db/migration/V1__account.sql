CREATE TABLE account (
    id          TEXT PRIMARY KEY,
    owner_name  TEXT NOT NULL,
    currency    CHAR(3) NOT NULL,
    balance     NUMERIC(19, 2) NOT NULL,
    status      TEXT NOT NULL
);

CREATE TABLE account_entry (
    id          BIGSERIAL PRIMARY KEY,
    account_id  TEXT NOT NULL REFERENCES account (id),
    saga_id     UUID NOT NULL,
    kind        TEXT NOT NULL,
    amount      NUMERIC(19, 2) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL
);

CREATE INDEX account_entry_by_account ON account_entry (account_id, id);
