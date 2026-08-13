package com.example.autoauction.common.media.infrastructure.persistence;

import com.example.autoauction.common.media.domain.MediaAsset;
import com.example.autoauction.common.media.domain.port.MediaAssetRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H-7: реальный (не мокированный) тест слоя persistence для MediaAsset.
 * Работает против H2 (ddl-auto=create-drop в test-профиле) - схема генерируется
 * Hibernate из JPA-аннотаций, а не из V20__media_assets.sql. Это подтверждает
 * корректность entity/mapper/repository, но НЕ является доказательством
 * поведения именно PostgreSQL TIMESTAMP(6) WITH TIME ZONE (см. финальный отчёт).
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({MediaAssetRepositoryAdapter.class, MediaAssetMapper.class})
@DisplayName("Тесты MediaAssetRepositoryAdapter - реальная persistence (H-7)")
class MediaAssetRepositoryAdapterTest {

    @Autowired
    private MediaAssetRepository mediaAssetRepository;

    @Test
    @DisplayName("save создаёт реальную строку в БД с сгенерированным id")
    void save_persistsRealRow() {
        MediaAsset saved = mediaAssetRepository.save(
                new MediaAsset(null, "abc-123.png", 42L, OffsetDateTime.now()));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getStorageKey()).isEqualTo("abc-123.png");
        assertThat(saved.getUploaderId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("findByStorageKey возвращает ранее сохранённый MediaAsset")
    void findByStorageKey_returnsCorrectAsset() {
        mediaAssetRepository.save(new MediaAsset(null, "find-me.png", 7L, OffsetDateTime.now()));

        Optional<MediaAsset> found = mediaAssetRepository.findByStorageKey("find-me.png");

        assertThat(found).isPresent();
        assertThat(found.get().getStorageKey()).isEqualTo("find-me.png");
        assertThat(found.get().getUploaderId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("findByStorageKey для несуществующего ключа возвращает Optional.empty()")
    void findByStorageKey_missingKey_returnsEmpty() {
        Optional<MediaAsset> found = mediaAssetRepository.findByStorageKey("does-not-exist.png");

        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("deleteByStorageKey реально удаляет строку")
    void deleteByStorageKey_removesRow() {
        mediaAssetRepository.save(new MediaAsset(null, "to-delete.png", 1L, OffsetDateTime.now()));
        assertThat(mediaAssetRepository.findByStorageKey("to-delete.png")).isPresent();

        mediaAssetRepository.deleteByStorageKey("to-delete.png");

        assertThat(mediaAssetRepository.findByStorageKey("to-delete.png")).isEmpty();
    }

    @Test
    @DisplayName("deleteByStorageKey для отсутствующего ключа не выбрасывает исключение")
    void deleteByStorageKey_missingKey_doesNotThrow() {
        mediaAssetRepository.deleteByStorageKey("never-existed.png");
        // не должно бросить исключение
    }

    @Test
    @DisplayName("uploaderId и storageKey переживают полный цикл mapper -> entity -> БД -> entity -> mapper")
    void roundTrip_preservesStorageKeyAndUploaderId() {
        mediaAssetRepository.save(new MediaAsset(null, "roundtrip.png", 999L, OffsetDateTime.now()));

        MediaAsset loaded = mediaAssetRepository.findByStorageKey("roundtrip.png").orElseThrow();

        assertThat(loaded.getStorageKey()).isEqualTo("roundtrip.png");
        assertThat(loaded.getUploaderId()).isEqualTo(999L);
    }

    @Test
    @DisplayName("created_at переживает цикл сохранения/чтения (сравнение по фактическому моменту времени)")
    void roundTrip_createdAtPreservesInstant() {
        OffsetDateTime before = OffsetDateTime.now();

        mediaAssetRepository.save(new MediaAsset(null, "timestamp-check.png", 1L, before));

        MediaAsset loaded = mediaAssetRepository.findByStorageKey("timestamp-check.png").orElseThrow();

        assertThat(loaded.getCreatedAt()).isNotNull();
        // Сравниваем по фактическому моменту времени (isEqual), а не по строковому
        // представлению со смещением (equals) - Hibernate/H2 может нормализовать
        // смещение при хранении, сохраняя при этом тот же самый момент времени.
        assertThat(loaded.getCreatedAt().truncatedTo(ChronoUnit.SECONDS))
                .isEqualTo(before.truncatedTo(ChronoUnit.SECONDS));
    }
}
