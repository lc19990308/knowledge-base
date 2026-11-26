-- 本地账号密码登录（2026-11-22 决策修订：首期加入本地账号，与企业 OIDC 并存）。
-- 密码仅存 BCrypt 哈希，不存明文；账号来源仍是 (issuer, subject)，local 账号 issuer 固定为 'local'。
CREATE TABLE user_credential (
  user_id UUID PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE,
  password_hash VARCHAR(100) NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
