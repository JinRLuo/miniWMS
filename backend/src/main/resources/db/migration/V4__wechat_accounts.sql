-- Existing password accounts retain access. New WeChat accounts explicitly start unapproved.
ALTER TABLE users ADD COLUMN approved BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE users ADD COLUMN phone VARCHAR(40);
ALTER TABLE users ADD COLUMN login_provider VARCHAR(16) NOT NULL DEFAULT 'PASSWORD';

CREATE TABLE wechat_accounts (
    user_id BIGINT PRIMARY KEY,
    -- OpenID is case-sensitive, including on MySQL databases with a CI collation.
    app_id VARBINARY(64) NOT NULL,
    open_id VARBINARY(128) NOT NULL,
    UNIQUE (app_id, open_id),
    FOREIGN KEY (user_id) REFERENCES users(id)
);
