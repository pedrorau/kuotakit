-- Refunds and revocations that arrived before the purchase did, to apply them when its receipt comes.
CREATE TABLE pending_endings (
    store          text        NOT NULL CHECK (store IN ('play_store', 'app_store')),
    purchase_token text        NOT NULL,
    status         text        NOT NULL CHECK (status IN ('refunded', 'revoked')),
    received_at    timestamptz NOT NULL,
    PRIMARY KEY (store, purchase_token)
);

CREATE INDEX pending_endings_received_idx ON pending_endings (received_at);
