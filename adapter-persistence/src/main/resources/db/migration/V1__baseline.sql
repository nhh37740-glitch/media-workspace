-- Baseline schema for media-workspace.
--
-- InnoDB, utf8mb4, UTC DATETIME(6). Identifiers are CHAR(36) in canonical lowercase UUID form;
-- hashes are CHAR(64) lowercase hex. Constraints are enforced by the DDL, not only by Java:
-- a CHECK rejects an illegal state even if an application path is wrong.
--
-- Deletion of a media is a shadow (deleted_at), so history survives while every read path filters
-- it out.

CREATE TABLE app_user (
    id            CHAR(36)     NOT NULL,
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    enabled       TINYINT(1)   NOT NULL DEFAULT 1,
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_user_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE workspace (
    id                    CHAR(36)    NOT NULL,
    name                  VARCHAR(80) NOT NULL,
    owner_id              CHAR(36)    NOT NULL,
    source_quota_bytes    BIGINT      NOT NULL,
    used_source_bytes     BIGINT      NOT NULL DEFAULT 0,
    reserved_source_bytes BIGINT      NOT NULL DEFAULT 0,
    created_at            DATETIME(6) NOT NULL,
    updated_at            DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_workspace_owner FOREIGN KEY (owner_id) REFERENCES app_user (id),
    CONSTRAINT ck_workspace_quota CHECK (
        used_source_bytes >= 0
        AND reserved_source_bytes >= 0
        AND source_quota_bytes >= 0
        AND used_source_bytes + reserved_source_bytes <= source_quota_bytes)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE workspace_member (
    workspace_id CHAR(36)    NOT NULL,
    user_id      CHAR(36)    NOT NULL,
    role         VARCHAR(16) NOT NULL,
    created_at   DATETIME(6) NOT NULL,
    updated_at   DATETIME(6) NOT NULL,
    PRIMARY KEY (workspace_id, user_id),
    KEY ix_member_user (user_id, workspace_id),
    CONSTRAINT fk_member_workspace FOREIGN KEY (workspace_id) REFERENCES workspace (id),
    CONSTRAINT fk_member_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT ck_member_role CHECK (role IN ('OWNER', 'EDITOR', 'VIEWER'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE upload_session (
    id              CHAR(36)     NOT NULL,
    workspace_id    CHAR(36)     NOT NULL,
    owner_id        CHAR(36)     NOT NULL,
    filename        VARCHAR(200) NOT NULL,
    title           VARCHAR(120) NOT NULL,
    expected_size   BIGINT       NOT NULL,
    expected_hash   CHAR(64)     NOT NULL,
    chunk_size      INT          NOT NULL,
    chunk_count     INT          NOT NULL,
    state           VARCHAR(16)  NOT NULL,
    media_id        CHAR(36)     NULL,
    expires_at      DATETIME(6)  NOT NULL,
    finalize_epoch  BIGINT       NOT NULL DEFAULT 0,
    lease_until     DATETIME(6)  NULL,
    next_finalize_at DATETIME(6) NOT NULL,
    quota_reserved  TINYINT(1)   NOT NULL DEFAULT 1,
    error_code      VARCHAR(64)  NULL,
    trace_id        CHAR(36)     NOT NULL,
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY ix_upload_expiry (state, expires_at),
    KEY ix_upload_finalize (state, next_finalize_at, id),
    KEY ix_upload_lease (state, lease_until),
    KEY ix_upload_owner (workspace_id, owner_id, state),
    CONSTRAINT fk_upload_workspace FOREIGN KEY (workspace_id) REFERENCES workspace (id),
    CONSTRAINT fk_upload_owner FOREIGN KEY (owner_id) REFERENCES app_user (id),
    CONSTRAINT ck_upload_state CHECK (
        state IN ('OPEN', 'FINALIZING', 'COMPLETED', 'FAILED', 'EXPIRED', 'ABORTED')),
    CONSTRAINT ck_upload_geometry CHECK (
        expected_size > 0 AND chunk_size > 0 AND chunk_count > 0 AND finalize_epoch >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE upload_chunk (
    upload_id   CHAR(36)     NOT NULL,
    chunk_index INT          NOT NULL,
    hash        CHAR(64)     NOT NULL,
    size_bytes  BIGINT       NOT NULL,
    storage_key VARCHAR(255) NOT NULL,
    created_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (upload_id, chunk_index),
    UNIQUE KEY uk_chunk_storage_key (storage_key),
    CONSTRAINT fk_chunk_upload FOREIGN KEY (upload_id) REFERENCES upload_session (id),
    CONSTRAINT ck_chunk_index CHECK (chunk_index >= 0 AND size_bytes > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE media (
    id                CHAR(36)     NOT NULL,
    workspace_id      CHAR(36)     NOT NULL,
    uploader_id       CHAR(36)     NOT NULL,
    title             VARCHAR(120) NOT NULL,
    original_filename VARCHAR(200) NOT NULL,
    source_key        VARCHAR(255) NOT NULL,
    source_size       BIGINT       NOT NULL,
    source_hash       CHAR(64)     NOT NULL,
    status            VARCHAR(16)  NOT NULL,
    version           BIGINT       NOT NULL DEFAULT 0,
    duration_ms       BIGINT       NULL,
    width             INT          NULL,
    height            INT          NULL,
    output_key        VARCHAR(255) NULL,
    poster_key        VARCHAR(255) NULL,
    deleted_at        DATETIME(6)  NULL,
    created_at        DATETIME(6)  NOT NULL,
    updated_at        DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_media_source_key (source_key),
    KEY ix_media_list (workspace_id, deleted_at, created_at, id),
    CONSTRAINT fk_media_workspace FOREIGN KEY (workspace_id) REFERENCES workspace (id),
    CONSTRAINT fk_media_uploader FOREIGN KEY (uploader_id) REFERENCES app_user (id),
    CONSTRAINT ck_media_status CHECK (status IN ('PROCESSING', 'READY', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_media_size CHECK (source_size > 0 AND version >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE processing_task (
    id              CHAR(36)    NOT NULL,
    media_id        CHAR(36)    NOT NULL,
    generation      INT         NOT NULL DEFAULT 1,
    state           VARCHAR(16) NOT NULL,
    preset          VARCHAR(32) NOT NULL,
    attempt         INT         NOT NULL DEFAULT 0,
    execution_epoch BIGINT      NOT NULL DEFAULT 0,
    worker_id       VARCHAR(64) NULL,
    lease_until     DATETIME(6) NULL,
    next_run_at     DATETIME(6) NOT NULL,
    progress        INT         NOT NULL DEFAULT 0,
    version         BIGINT      NOT NULL DEFAULT 1,
    error_code      VARCHAR(64) NULL,
    trace_id        CHAR(36)    NOT NULL,
    created_at      DATETIME(6) NOT NULL,
    updated_at      DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_media (media_id),
    KEY ix_task_due (state, next_run_at, id),
    KEY ix_task_lease (state, lease_until),
    CONSTRAINT fk_task_media FOREIGN KEY (media_id) REFERENCES media (id),
    CONSTRAINT ck_task_state CHECK (state IN
        ('WAITING_EVENT', 'QUEUED', 'RUNNING', 'RETRY_WAIT', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_task_counters CHECK (
        generation >= 1 AND attempt >= 0 AND attempt <= 3 AND execution_epoch >= 0),
    CONSTRAINT ck_task_progress CHECK (progress BETWEEN 0 AND 100)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE task_attempt (
    id              CHAR(36)      NOT NULL,
    task_id         CHAR(36)      NOT NULL,
    generation      INT           NOT NULL,
    attempt         INT           NOT NULL,
    execution_epoch BIGINT        NOT NULL,
    worker_id       VARCHAR(64)   NOT NULL,
    state           VARCHAR(16)   NOT NULL,
    started_at      DATETIME(6)   NOT NULL,
    finished_at     DATETIME(6)   NULL,
    exit_code       INT           NULL,
    error_code      VARCHAR(64)   NULL,
    error_summary   VARCHAR(1024) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_attempt_ordinal (task_id, generation, attempt),
    CONSTRAINT fk_attempt_task FOREIGN KEY (task_id) REFERENCES processing_task (id),
    CONSTRAINT ck_attempt_state CHECK (
        state IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'LOST'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE outbox_event (
    event_id               CHAR(36)     NOT NULL,
    topic                  VARCHAR(128) NOT NULL,
    event_key              VARCHAR(64)  NOT NULL,
    body                   JSON         NOT NULL,
    state                  VARCHAR(16)  NOT NULL,
    publish_attempt        INT          NOT NULL DEFAULT 0,
    next_run_at            DATETIME(6)  NOT NULL,
    claim_token            CHAR(36)     NULL,
    claim_until            DATETIME(6)  NULL,
    published_at           DATETIME(6)  NULL,
    trace_id               CHAR(36)     NULL,
    producer_invocation_id CHAR(36)     NULL,
    causation_event_id     CHAR(36)     NULL,
    created_at             DATETIME(6)  NOT NULL,
    PRIMARY KEY (event_id),
    KEY ix_outbox_due (state, next_run_at, event_id),
    KEY ix_outbox_claim (state, claim_until),
    CONSTRAINT ck_outbox_state CHECK (state IN ('PENDING', 'CLAIMED', 'PUBLISHED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE inbox_event (
    consumer_group VARCHAR(64) NOT NULL,
    event_id       CHAR(36)    NOT NULL,
    body_hash      CHAR(64)    NOT NULL,
    processed_at   DATETIME(6) NOT NULL,
    PRIMARY KEY (consumer_group, event_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE poison_message (
    topic        VARCHAR(128) NOT NULL,
    partition_id INT          NOT NULL,
    offset_id    BIGINT       NOT NULL,
    error_code   VARCHAR(64)  NOT NULL,
    body_hash    CHAR(64)     NOT NULL,
    created_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (topic, partition_id, offset_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE share_link (
    id         CHAR(36)    NOT NULL,
    media_id   CHAR(36)    NOT NULL,
    creator_id CHAR(36)    NOT NULL,
    token_hash CHAR(64)    NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_share_token_hash (token_hash),
    KEY ix_share_media (media_id, revoked_at, expires_at),
    CONSTRAINT fk_share_media FOREIGN KEY (media_id) REFERENCES media (id),
    CONSTRAINT fk_share_creator FOREIGN KEY (creator_id) REFERENCES app_user (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE share_session (
    id         CHAR(36)    NOT NULL,
    share_id   CHAR(36)    NOT NULL,
    media_id   CHAR(36)    NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY ix_share_session_expiry (expires_at),
    CONSTRAINT fk_share_session_link FOREIGN KEY (share_id) REFERENCES share_link (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE audit_event (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    workspace_id    CHAR(36)    NOT NULL,
    actor_id        CHAR(36)    NULL,
    action          VARCHAR(64) NOT NULL,
    resource_id     CHAR(36)    NULL,
    request_id      CHAR(36)    NULL,
    detail          JSON        NULL,
    source_event_id CHAR(36)    NULL,
    created_at      DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_audit_source_event (source_event_id),
    KEY ix_audit_workspace (workspace_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE idempotency_record (
    user_id       CHAR(36)    NOT NULL,
    route         VARCHAR(80) NOT NULL,
    resource_id   VARCHAR(64) NOT NULL,
    key_hash      CHAR(64)    NOT NULL,
    request_hash  CHAR(64)    NOT NULL,
    response_json JSON        NOT NULL,
    http_status   INT         NOT NULL,
    created_at    DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id, route, resource_id, key_hash)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- The global unfinished-task counter. It is a single fixed row; locking it is what makes task
-- admission exact under concurrency. Created by migration, never by the application at runtime.
CREATE TABLE capacity_counter (
    name         VARCHAR(32) NOT NULL,
    active_count INT         NOT NULL DEFAULT 0,
    max_count    INT         NOT NULL,
    updated_at   DATETIME(6) NOT NULL,
    PRIMARY KEY (name),
    CONSTRAINT ck_capacity_count CHECK (active_count >= 0 AND max_count > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

INSERT INTO capacity_counter (name, active_count, max_count, updated_at)
VALUES ('processing', 0, 100, UTC_TIMESTAMP(6));
