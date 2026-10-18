-- DC (HQ) database: every checkpoint's travel passes, and what was sent to which checkpoint.

-- An entry checkpoint (a "branch"). Each one gets only the passes of travellers entering through it.
CREATE TABLE checkpoint (
    code  VARCHAR(10)  NOT NULL,
    name  VARCHAR(100) NOT NULL,
    kind  VARCHAR(10)  NOT NULL,
    CONSTRAINT pk_checkpoint PRIMARY KEY (code),
    CONSTRAINT ck_checkpoint_kind CHECK (kind IN ('AIRPORT', 'LAND', 'SEA'))
);

-- An approved (or revoked) travel pass. checkpoint_code decides which branch receives it.
CREATE TABLE travel_pass (
    application_no   VARCHAR(20)  NOT NULL,
    passport_no      VARCHAR(20)  NOT NULL,
    full_name        VARCHAR(100) NOT NULL,
    nationality      VARCHAR(3)   NOT NULL,   -- ISO 3166-1 alpha-3
    date_of_birth    DATE         NOT NULL,
    pass_type        VARCHAR(10)  NOT NULL,
    checkpoint_code  VARCHAR(10)  NOT NULL,
    valid_from       DATE         NOT NULL,
    valid_until      DATE         NOT NULL,
    status           VARCHAR(10)  NOT NULL,
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT pk_travel_pass PRIMARY KEY (application_no),
    CONSTRAINT fk_travel_pass_checkpoint FOREIGN KEY (checkpoint_code) REFERENCES checkpoint (code),
    CONSTRAINT ck_travel_pass_type CHECK (pass_type IN ('TOURIST', 'BUSINESS', 'STUDENT', 'TRANSIT')),
    CONSTRAINT ck_travel_pass_status CHECK (status IN ('APPROVED', 'REVOKED')),
    CONSTRAINT ck_travel_pass_validity CHECK (valid_until >= valid_from)
);

-- A checkpoint's slice, in a stable order (sync, paging).
CREATE INDEX ix_travel_pass_checkpoint ON travel_pass (checkpoint_code, application_no);

-- Order of every change the DC sends: each event takes the next value. Higher = newer, so a branch
-- can tell a late, older event from a newer one for the same pass.
CREATE SEQUENCE event_seq;

-- Application numbers for passes created by the holiday rush (POST /rush): APP-2026-100001, ...
CREATE SEQUENCE application_no_seq START WITH 100001;

-- One row per request (sync, purge, rush): how many events it produced and whether Kafka took them all.
CREATE TABLE sync_log (
    request_id       UUID         NOT NULL,
    branch_code      VARCHAR(10)  NOT NULL,
    kind             VARCHAR(10)  NOT NULL,
    event_count      INTEGER      NOT NULL,
    first_seq        BIGINT,
    last_seq         BIGINT,
    status           VARCHAR(10)  NOT NULL,
    published_count  INTEGER      NOT NULL,
    error            VARCHAR(500),
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    published_at     TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_sync_log PRIMARY KEY (request_id),
    CONSTRAINT fk_sync_log_checkpoint FOREIGN KEY (branch_code) REFERENCES checkpoint (code),
    CONSTRAINT ck_sync_log_kind CHECK (kind IN ('SYNC', 'PURGE', 'RUSH')),
    CONSTRAINT ck_sync_log_status CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED'))
);

CREATE INDEX ix_sync_log_created ON sync_log (created_at);
CREATE INDEX ix_sync_log_branch ON sync_log (branch_code, created_at);
