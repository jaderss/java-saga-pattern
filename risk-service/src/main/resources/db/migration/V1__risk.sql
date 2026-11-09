CREATE TABLE limit_usage (
    account_id  TEXT NOT NULL,
    day         DATE NOT NULL,
    reserved    NUMERIC(19, 2) NOT NULL DEFAULT 0 CHECK (reserved >= 0),
    used        NUMERIC(19, 2) NOT NULL DEFAULT 0 CHECK (used >= 0),
    PRIMARY KEY (account_id, day)
);

CREATE TABLE reservation (
    saga_id     UUID PRIMARY KEY,
    account_id  TEXT NOT NULL,
    day         DATE NOT NULL,
    amount      NUMERIC(19, 2) NOT NULL CHECK (amount > 0)
);

CREATE TABLE watchlist (
    account_id  TEXT PRIMARY KEY,
    reason      TEXT NOT NULL
);
