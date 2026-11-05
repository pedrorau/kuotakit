-- Google Play can hold or pause a subscription: those purchases never grant access, but they are kept as they are.
ALTER TABLE purchases DROP CONSTRAINT purchases_status_check;
ALTER TABLE purchases
    ADD CONSTRAINT purchases_status_check
    CHECK (status IN ('active', 'expired', 'on_hold', 'paused', 'refunded', 'revoked'));
