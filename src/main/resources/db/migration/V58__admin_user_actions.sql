-- SEC-06: who did what to which user. Blocking, unblocking, trust decisions and role changes are
-- security-relevant administrative actions; until now block/unblock and role changes recorded no
-- actor at all, and trust kept only its latest decision. This is an append-only journal: rows are
-- inserted in the same transaction as the action and never updated or deleted by the application.
CREATE TABLE admin_user_actions (
    id              BIGSERIAL PRIMARY KEY,
    target_user_id  BIGINT NOT NULL REFERENCES users(id),
    admin_id        BIGINT NOT NULL,
    action          VARCHAR(16) NOT NULL,
    details         VARCHAR(255),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT admin_user_actions_action_check
        CHECK (action IN ('BLOCK', 'UNBLOCK', 'TRUST', 'UNTRUST', 'ROLE_SET', 'ROLE_ADD'))
);

CREATE INDEX idx_admin_user_actions_target_created ON admin_user_actions(target_user_id, created_at);
