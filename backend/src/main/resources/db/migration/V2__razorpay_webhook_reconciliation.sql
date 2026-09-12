-- V2__razorpay_webhook_reconciliation.sql — Razorpay webhook reconciliation foundation.
-- Stores only minimal reconciliation fields (no raw JSONB payload).
-- Supports database-backed idempotency via unique razorpay_event_id
-- and efficient lookup by order/payment for reconciliation.
-- Flyway V2; V1 must remain untouched.

-- -------------------------------------------------------------------------
-- webhook_events (idempotency + minimal audit for Razorpay deliveries)
-- -------------------------------------------------------------------------
CREATE TABLE webhook_events (
    id                  uuid                        NOT NULL,
    created_at          timestamp(6) with time zone NOT NULL,
    event_type          varchar(32)                 NOT NULL,
    processed_at        timestamp(6) with time zone NOT NULL,
    razorpay_event_id   varchar(64)                 NOT NULL,
    razorpay_order_id   varchar(64),
    razorpay_payment_id varchar(64),
    razorpay_refund_id  varchar(64),
    reason              varchar(256),
    status              varchar(16)                 NOT NULL,
    updated_at          timestamp(6) with time zone NOT NULL,
    CONSTRAINT webhook_events_pkey PRIMARY KEY (id),
    CONSTRAINT uk_webhook_events_razorpay_event_id UNIQUE (razorpay_event_id),
    CONSTRAINT webhook_events_status_check CHECK ((status)::text = ANY ((ARRAY['PROCESSED'::varchar,'IGNORED'::varchar])::text[]))
);

CREATE INDEX ix_webhook_events_razorpay_order_id ON webhook_events USING btree (razorpay_order_id);
CREATE INDEX ix_webhook_events_razorpay_payment_id ON webhook_events USING btree (razorpay_payment_id);

-- -------------------------------------------------------------------------
-- payments — add indexes for webhook reconciliation lookups
-- -------------------------------------------------------------------------
CREATE INDEX ix_payments_razorpay_order_id ON payments USING btree (razorpay_order_id);
CREATE INDEX ix_payments_razorpay_payment_id ON payments USING btree (razorpay_payment_id);
