-- Store notifications already handled (Pub/Sub message ids), to skip a push delivered twice.
CREATE TABLE store_events (
    store       text        NOT NULL CHECK (store IN ('play_store', 'app_store')),
    message_id  text        NOT NULL,
    received_at timestamptz NOT NULL,
    PRIMARY KEY (store, message_id)
);

CREATE INDEX store_events_received_idx ON store_events (received_at);
