-- =====================================================================
-- Banking Management System (educational simulation) - core schema
-- All money columns are NUMERIC(19,2); all timestamps are TIMESTAMPTZ (UTC).
-- =====================================================================

-- ---------- Roles & users ----------
CREATE TABLE roles (
    id          SMALLINT     PRIMARY KEY,
    name        VARCHAR(20)  NOT NULL UNIQUE,
    description VARCHAR(200) NOT NULL
);

INSERT INTO roles (id, name, description) VALUES
    (1, 'ADMIN',    'System administrator: user management, oversight, reports'),
    (2, 'EMPLOYEE', 'Bank employee: customer onboarding and branch operations'),
    (3, 'CUSTOMER', 'Bank customer: own accounts only');

CREATE TABLE users (
    id                    BIGSERIAL    PRIMARY KEY,
    username              VARCHAR(50)  NOT NULL,
    email                 VARCHAR(254) NOT NULL,
    full_name             VARCHAR(120) NOT NULL,
    password_hash         VARCHAR(100) NOT NULL,
    role_id               SMALLINT     NOT NULL REFERENCES roles (id),
    status                VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
                          CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DEACTIVATED')),
    must_change_password  BOOLEAN      NOT NULL DEFAULT FALSE,
    failed_login_attempts INT          NOT NULL DEFAULT 0 CHECK (failed_login_attempts >= 0),
    locked_until          TIMESTAMPTZ,
    last_login_at         TIMESTAMPTZ,
    last_failed_login_at  TIMESTAMPTZ,
    password_changed_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- incremented on password/role/status changes; sessions carrying an older stamp are rejected
    security_stamp        INT          NOT NULL DEFAULT 0,
    demo                  BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version               BIGINT       NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_users_username ON users (lower(username));
CREATE UNIQUE INDEX ux_users_email ON users (lower(email));
CREATE INDEX ix_users_role_status ON users (role_id, status);
CREATE INDEX ix_users_full_name ON users (lower(full_name));

-- Fine-grained, configurable permissions for employees' sensitive operations
CREATE TABLE user_permissions (
    user_id    BIGINT      NOT NULL REFERENCES users (id),
    permission VARCHAR(40) NOT NULL CHECK (permission IN (
        'CUSTOMER_VIEW', 'CUSTOMER_REGISTER', 'ACCOUNT_CREATE',
        'CASH_DEPOSIT', 'CASH_WITHDRAWAL', 'TRANSFER', 'TRANSACTION_FLAG')),
    granted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, permission)
);

-- ---------- Customers & accounts ----------
CREATE SEQUENCE customer_number_seq START WITH 100001;
CREATE SEQUENCE account_number_seq START WITH 100000001;

CREATE TABLE customer_profiles (
    id              BIGSERIAL    PRIMARY KEY,
    user_id         BIGINT       NOT NULL UNIQUE REFERENCES users (id),
    customer_number VARCHAR(12)  NOT NULL UNIQUE,
    phone           VARCHAR(20),
    date_of_birth   DATE,
    address_line    VARCHAR(200),
    city            VARCHAR(80),
    postal_code     VARCHAR(12),
    created_by      BIGINT       REFERENCES users (id),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version         BIGINT       NOT NULL DEFAULT 0
);

CREATE TABLE bank_accounts (
    id             BIGSERIAL     PRIMARY KEY,
    account_number VARCHAR(16)   NOT NULL UNIQUE,
    customer_id    BIGINT        NOT NULL REFERENCES customer_profiles (id),
    account_type   VARCHAR(10)   NOT NULL CHECK (account_type IN ('SAVINGS', 'CURRENT')),
    currency       VARCHAR(3)    NOT NULL DEFAULT 'INR' CHECK (currency = 'INR'),
    -- Overdrafts are not offered: the database itself refuses a negative balance.
    balance        NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (balance >= 0),
    status         VARCHAR(12)   NOT NULL DEFAULT 'ACTIVE'
                   CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    opened_by      BIGINT        REFERENCES users (id),
    opened_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    closed_at      TIMESTAMPTZ,
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    demo           BOOLEAN       NOT NULL DEFAULT FALSE,
    version        BIGINT        NOT NULL DEFAULT 0
);
CREATE INDEX ix_accounts_customer ON bank_accounts (customer_id);
CREATE INDEX ix_accounts_status ON bank_accounts (status);

-- ---------- Transactions & double-entry ledger ----------
CREATE TABLE transactions (
    id                     BIGSERIAL     PRIMARY KEY,
    reference              VARCHAR(32)   NOT NULL UNIQUE,
    type                   VARCHAR(12)   NOT NULL CHECK (type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER')),
    status                 VARCHAR(12)   NOT NULL CHECK (status IN ('COMPLETED', 'FAILED')),
    amount                 NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    currency               VARCHAR(3)    NOT NULL DEFAULT 'INR' CHECK (currency = 'INR'),
    source_account_id      BIGINT        REFERENCES bank_accounts (id),
    destination_account_id BIGINT        REFERENCES bank_accounts (id),
    initiated_by           BIGINT        NOT NULL REFERENCES users (id),
    channel                VARCHAR(10)   NOT NULL CHECK (channel IN ('ONLINE', 'BRANCH')),
    description            VARCHAR(140),
    failure_reason         VARCHAR(200),
    simulated              BOOLEAN       NOT NULL DEFAULT TRUE,
    demo                   BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_txn_accounts CHECK (
        (type = 'DEPOSIT'    AND destination_account_id IS NOT NULL AND source_account_id IS NULL) OR
        (type = 'WITHDRAWAL' AND source_account_id IS NOT NULL AND destination_account_id IS NULL) OR
        (type = 'TRANSFER'   AND source_account_id IS NOT NULL AND destination_account_id IS NOT NULL
                             AND source_account_id <> destination_account_id)),
    CONSTRAINT ck_txn_failure CHECK ((status = 'FAILED') = (failure_reason IS NOT NULL))
);
CREATE INDEX ix_txn_source_created ON transactions (source_account_id, created_at DESC);
CREATE INDEX ix_txn_dest_created ON transactions (destination_account_id, created_at DESC);
CREATE INDEX ix_txn_created ON transactions (created_at DESC);
CREATE INDEX ix_txn_type_status ON transactions (type, status);
CREATE INDEX ix_txn_initiated_by ON transactions (initiated_by, created_at DESC);

-- One row per balance movement. For every account:
--   balance = SUM(CREDIT amounts) - SUM(DEBIT amounts)
-- A transfer always produces exactly one DEBIT and one CREDIT of equal amount.
CREATE TABLE ledger_entries (
    id             BIGSERIAL     PRIMARY KEY,
    transaction_id BIGINT        NOT NULL REFERENCES transactions (id),
    account_id     BIGINT        NOT NULL REFERENCES bank_accounts (id),
    entry_type     VARCHAR(6)    NOT NULL CHECK (entry_type IN ('DEBIT', 'CREDIT')),
    amount         NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    balance_after  NUMERIC(19,2) NOT NULL CHECK (balance_after >= 0),
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ux_ledger_txn_account_type UNIQUE (transaction_id, account_id, entry_type)
);
CREATE INDEX ix_ledger_account_created ON ledger_entries (account_id, created_at DESC);
CREATE INDEX ix_ledger_txn ON ledger_entries (transaction_id);

-- Retried requests carrying the same key never execute twice
CREATE TABLE idempotency_records (
    id              BIGSERIAL   PRIMARY KEY,
    user_id         BIGINT      NOT NULL REFERENCES users (id),
    idempotency_key VARCHAR(80) NOT NULL,
    operation       VARCHAR(12) NOT NULL,
    request_hash    CHAR(64)    NOT NULL,
    transaction_id  BIGINT      REFERENCES transactions (id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ux_idempotency_user_key UNIQUE (user_id, idempotency_key)
);

CREATE TABLE flagged_transactions (
    id             BIGSERIAL    PRIMARY KEY,
    transaction_id BIGINT       NOT NULL REFERENCES transactions (id),
    source         VARCHAR(10)  NOT NULL CHECK (source IN ('AUTO', 'MANUAL')),
    reason         VARCHAR(300) NOT NULL,
    flagged_by     BIGINT       REFERENCES users (id),
    status         VARCHAR(10)  NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'RESOLVED', 'DISMISSED')),
    reviewed_by    BIGINT       REFERENCES users (id),
    review_note    VARCHAR(300),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    reviewed_at    TIMESTAMPTZ,
    version        BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_flag_review CHECK ((status = 'OPEN') = (reviewed_at IS NULL))
);
CREATE UNIQUE INDEX ux_flag_open_per_txn ON flagged_transactions (transaction_id) WHERE status = 'OPEN';
CREATE INDEX ix_flag_status_created ON flagged_transactions (status, created_at DESC);

-- ---------- Activity & audit ----------
CREATE TABLE login_activity (
    id                 BIGSERIAL    PRIMARY KEY,
    user_id            BIGINT       REFERENCES users (id),
    username_attempted VARCHAR(254) NOT NULL,
    outcome            VARCHAR(20)  NOT NULL CHECK (outcome IN (
                           'SUCCESS', 'BAD_CREDENTIALS', 'LOCKED', 'DISABLED', 'LOGOUT', 'SESSION_REVOKED')),
    ip_address         VARCHAR(45),
    user_agent         VARCHAR(255),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_login_user_created ON login_activity (user_id, created_at DESC);
CREATE INDEX ix_login_created ON login_activity (created_at DESC);
CREATE INDEX ix_login_outcome ON login_activity (outcome, created_at DESC);

CREATE TABLE audit_logs (
    id             BIGSERIAL    PRIMARY KEY,
    actor_user_id  BIGINT       REFERENCES users (id),
    actor_username VARCHAR(50)  NOT NULL,
    actor_role     VARCHAR(20),
    action         VARCHAR(50)  NOT NULL,
    target_type    VARCHAR(30),
    target_id      VARCHAR(64),
    details        TEXT,
    ip_address     VARCHAR(45),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_created ON audit_logs (created_at DESC);
CREATE INDEX ix_audit_actor ON audit_logs (actor_user_id, created_at DESC);
CREATE INDEX ix_audit_action ON audit_logs (action, created_at DESC);
CREATE INDEX ix_audit_target ON audit_logs (target_type, target_id);

-- ---------- Append-only enforcement for financial and audit history ----------
CREATE FUNCTION bms_forbid_modification() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Table % is append-only: % is not permitted', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_transactions_append_only BEFORE UPDATE OR DELETE ON transactions
    FOR EACH ROW EXECUTE FUNCTION bms_forbid_modification();
CREATE TRIGGER trg_ledger_append_only BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION bms_forbid_modification();
CREATE TRIGGER trg_audit_append_only BEFORE UPDATE OR DELETE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION bms_forbid_modification();
CREATE TRIGGER trg_login_activity_append_only BEFORE UPDATE OR DELETE ON login_activity
    FOR EACH ROW EXECUTE FUNCTION bms_forbid_modification();
