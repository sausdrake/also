-- H-7 MVP: минимальная модель для учёта загрузившего файл пользователя.
-- Используется только для авторизации DELETE /api/media (владелец или ADMIN).
-- Исторические файлы, загруженные до этой миграции, не имеют записи здесь —
-- это намеренно (см. MediaAssetService.authorizeDelete): такие файлы недоступны
-- для удаления никому, кроме администратора.

-- created_at использует TIMESTAMP(6) WITH TIME ZONE, а не просто TIMESTAMP,
-- по аналогии с refresh_tokens (V2) - ближайшей по роли таблицей
-- (лёгкая, не бизнес-сущность, привязанная к пользователю запись, используемая
-- только для целей авторизации). JpaMediaAssetEntity.createdAt = OffsetDateTime.
CREATE TABLE media_assets (
    id          BIGSERIAL PRIMARY KEY,
    storage_key VARCHAR(255) NOT NULL UNIQUE,
    uploader_id BIGINT NOT NULL REFERENCES users(id),
    created_at  TIMESTAMP(6) WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_media_assets_uploader_id ON media_assets(uploader_id);
