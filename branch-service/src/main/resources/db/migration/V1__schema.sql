-- Branch (checkpoint) database: a copy of this checkpoint's travel passes only.

-- One row per pass this branch has ever been told about. event_seq is the DC sequence number of the event
-- the row came from: an event is applied only if its seq is higher (see BranchStore). A delete keeps the
-- row as a "tombstone" (deleted = true, personal data removed), so a late, older upsert can't bring the
-- pass back.
CREATE TABLE branch_pass (
    application_no  VARCHAR(20)  NOT NULL,
    passport_no     VARCHAR(20),
    full_name       VARCHAR(100),
    nationality     VARCHAR(3),
    date_of_birth   DATE,
    pass_type       VARCHAR(10),
    valid_from      DATE,
    valid_until     DATE,
    status          VARCHAR(10),
    deleted         BOOLEAN      NOT NULL,
    event_seq       BIGINT       NOT NULL,
    event_id        UUID         NOT NULL,
    occurred_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    received_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_branch_pass PRIMARY KEY (application_no),
    CONSTRAINT ck_branch_pass_type CHECK (pass_type IN ('TOURIST', 'BUSINESS', 'STUDENT', 'TRANSIT')),
    CONSTRAINT ck_branch_pass_status CHECK (status IN ('APPROVED', 'REVOKED')),
    -- a live pass is complete; a tombstone holds no personal data
    CONSTRAINT ck_branch_pass_live CHECK (deleted OR (passport_no IS NOT NULL AND full_name IS NOT NULL
        AND nationality IS NOT NULL AND date_of_birth IS NOT NULL AND pass_type IS NOT NULL
        AND valid_from IS NOT NULL AND valid_until IS NOT NULL AND status IS NOT NULL)),
    CONSTRAINT ck_branch_pass_tombstone CHECK (NOT deleted OR (passport_no IS NULL AND full_name IS NULL
        AND nationality IS NULL AND date_of_birth IS NULL))
);

-- The counter lookup: an officer scans a passport.
CREATE INDEX ix_branch_pass_passport ON branch_pass (passport_no) WHERE NOT deleted;
-- Newest first (dashboard) and the newest seq applied (summary).
CREATE INDEX ix_branch_pass_seq ON branch_pass (event_seq);
