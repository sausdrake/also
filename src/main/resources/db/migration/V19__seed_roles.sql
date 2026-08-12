INSERT INTO roles (name, description)
VALUES
    ('ROLE_USER',       'Обычный пользователь'),
    ('ROLE_DIAGNOSTIC', 'Диагностик'),
    ('ROLE_ADMIN',      'Администратор')
ON CONFLICT (name) DO NOTHING;
