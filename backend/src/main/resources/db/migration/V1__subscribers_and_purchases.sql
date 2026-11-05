-- Subscribers and the purchases validated with the stores (docs/api-spec.md, "Data model").
CREATE TABLE subscribers (
    app_user_id text PRIMARY KEY,
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE purchases (
    id                        bigserial PRIMARY KEY,
    app_user_id               text        NOT NULL REFERENCES subscribers (app_user_id),
    store                     text        NOT NULL CHECK (store IN ('play_store', 'app_store')),
    product_id                text        NOT NULL,
    -- The Play purchase token or the App Store original transaction id.
    purchase_token            text        NOT NULL,
    kind                      text        NOT NULL CHECK (kind IN ('subscription', 'non_consumable')),
    status                    text        NOT NULL CHECK (status IN ('active', 'expired', 'refunded', 'revoked')),
    purchased_at              timestamptz NOT NULL,
    expires_at                timestamptz,
    auto_renewing             boolean     NOT NULL,
    period_type               text        NOT NULL CHECK (period_type IN ('normal', 'intro', 'trial', 'unknown')),
    billing_issue_detected_at timestamptz,
    updated_at                timestamptz NOT NULL,
    -- A purchase belongs to one user: the same token can never be linked twice.
    CONSTRAINT purchases_store_token_unique UNIQUE (store, purchase_token)
);

CREATE INDEX purchases_app_user_idx ON purchases (app_user_id);
