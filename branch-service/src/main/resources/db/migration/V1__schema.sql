-- Branch database (BRANCH_A_PDB, BRANCH_B_PDB, ...): a copy of this branch's slice only.

-- The branch's products and prices, exactly as in the last applied snapshot.
CREATE TABLE branch_product (
    sku        VARCHAR2(20)  NOT NULL,
    name       VARCHAR2(100) NOT NULL,
    price      NUMBER(12, 2) NOT NULL,
    currency   VARCHAR2(3)   NOT NULL,
    synced_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_branch_product PRIMARY KEY (sku)
);

-- Every event received from the DC and what was done with it. The primary key makes a redelivered
-- event a no-op (idempotent consumer); occurred_at lets the branch ignore events older than its data.
CREATE TABLE applied_event (
    event_id       VARCHAR2(36) NOT NULL,
    type           VARCHAR2(10) NOT NULL,
    occurred_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    received_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    outcome        VARCHAR2(10) NOT NULL,
    product_count  NUMBER(6)    NOT NULL,
    CONSTRAINT pk_applied_event PRIMARY KEY (event_id),
    CONSTRAINT ck_applied_event_type CHECK (type IN ('SNAPSHOT', 'PURGE')),
    CONSTRAINT ck_applied_event_outcome CHECK (outcome IN ('APPLIED', 'STALE'))
);

CREATE INDEX ix_applied_event_occurred ON applied_event (outcome, occurred_at);
