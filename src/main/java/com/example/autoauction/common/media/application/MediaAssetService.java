package com.example.autoauction.common.media.application;

import com.example.autoauction.common.media.domain.MediaAsset;
import com.example.autoauction.common.media.domain.port.MediaAssetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class MediaAssetService {

    private final MediaAssetRepository mediaAssetRepository;

    /**
     * Фиксирует загрузившего файл пользователя. Сбой сохранения намеренно НЕ
     * подавляется здесь: вызывающая сторона (MediaController) должна знать, что
     * авторизационные метаданные не были записаны, чтобы не вернуть клиенту
     * ложный успех загрузки. См. MediaController.upload().
     */
    public void recordUpload(String storageKey, Long uploaderId) {
        mediaAssetRepository.save(new MediaAsset(null, storageKey, uploaderId, OffsetDateTime.now()));
    }

    /**
     * H-7 MVP: администратор может удалить любой файл; в остальных случаях —
     * только загрузивший его пользователь. Файл без записи MediaAsset
     * (исторический, до внедрения этой модели, либо потерянный из-за сбоя записи
     * при загрузке) недоступен для удаления никому, кроме администратора —
     * отказ по умолчанию, без обратного заполнения владельца.
     */
    public void authorizeDelete(String storageKey, Long currentUserId, boolean isAdmin) {
        if (isAdmin) {
            return;
        }
        Optional<MediaAsset> asset = mediaAssetRepository.findByStorageKey(storageKey);
        if (asset.isEmpty()) {
            throw new SecurityException("Файл не зарегистрирован — удаление недоступно");
        }
        if (!asset.get().getUploaderId().equals(currentUserId)) {
            throw new SecurityException("Вы можете удалять только собственные файлы");
        }
    }

    public void deleteRecord(String storageKey) {
        mediaAssetRepository.deleteByStorageKey(storageKey);
    }
}
