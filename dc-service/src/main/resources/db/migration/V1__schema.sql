-- DC database (DC_PDB): the master data of every branch.

CREATE TABLE branch (
    code  VARCHAR2(10)  NOT NULL,
    name  VARCHAR2(100) NOT NULL,
    CONSTRAINT pk_branch PRIMARY KEY (code)
);

CREATE TABLE product (
    sku   VARCHAR2(20)  NOT NULL,
    name  VARCHAR2(100) NOT NULL,
    CONSTRAINT pk_product PRIMARY KEY (sku)
);

-- Which branch sells which product, at what price. A branch's "slice" = its rows here.
CREATE TABLE branch_price (
    id           NUMBER GENERATED ALWAYS AS IDENTITY,
    branch_code  VARCHAR2(10)  NOT NULL,
    sku          VARCHAR2(20)  NOT NULL,
    price        NUMBER(12, 2) NOT NULL,
    currency     VARCHAR2(3)   NOT NULL,
    CONSTRAINT pk_branch_price PRIMARY KEY (id),
    CONSTRAINT fk_branch_price_branch FOREIGN KEY (branch_code) REFERENCES branch (code),
    CONSTRAINT fk_branch_price_product FOREIGN KEY (sku) REFERENCES product (sku),
    CONSTRAINT uq_branch_price UNIQUE (branch_code, sku),
    CONSTRAINT ck_branch_price_positive CHECK (price > 0)
);

-- One row per sync/purge request: what was sent to which branch, and whether Kafka accepted it.
CREATE TABLE sync_log (
    event_id       VARCHAR2(36) NOT NULL,
    branch_code    VARCHAR2(10) NOT NULL,
    type           VARCHAR2(10) NOT NULL,
    product_count  NUMBER(6)    NOT NULL,
    status         VARCHAR2(10) NOT NULL,
    error          VARCHAR2(500),
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    published_at   TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_sync_log PRIMARY KEY (event_id),
    CONSTRAINT fk_sync_log_branch FOREIGN KEY (branch_code) REFERENCES branch (code),
    CONSTRAINT ck_sync_log_type CHECK (type IN ('SNAPSHOT', 'PURGE')),
    CONSTRAINT ck_sync_log_status CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED'))
);

CREATE INDEX ix_sync_log_created ON sync_log (created_at);
