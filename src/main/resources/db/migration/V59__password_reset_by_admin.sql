-- AUTH-01: an administrator can reset a user's forgotten password to a one-time temporary password
-- (after checking the caller's identity against the verified KYC document). Until the user sets a
-- password of their own, the account can do nothing but change it: one row here per such user.
-- The row is removed when the user changes the password; the reset itself stays in the journal.
CREATE TABLE password_change_requirements (
    user_id            BIGINT PRIMARY KEY REFERENCES users(id),
    reset_by_admin_id  BIGINT NOT NULL,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL
);

ALTER TABLE admin_user_actions DROP CONSTRAINT admin_user_actions_action_check;
ALTER TABLE admin_user_actions ADD CONSTRAINT admin_user_actions_action_check
    CHECK (action IN ('BLOCK', 'UNBLOCK', 'TRUST', 'UNTRUST', 'ROLE_SET', 'ROLE_ADD', 'PASSWORD_RESET'));
