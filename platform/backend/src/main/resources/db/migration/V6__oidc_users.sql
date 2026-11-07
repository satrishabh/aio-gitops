ALTER TABLE app_users RENAME COLUMN github_login TO login;
ALTER TABLE app_users ADD COLUMN subject VARCHAR(500);
CREATE UNIQUE INDEX ux_app_users_subject ON app_users(subject) WHERE subject IS NOT NULL;
