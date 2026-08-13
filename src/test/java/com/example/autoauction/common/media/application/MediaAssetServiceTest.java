package com.example.autoauction.common.media.application;

import com.example.autoauction.common.media.domain.MediaAsset;
import com.example.autoauction.common.media.domain.port.MediaAssetRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * H-7 MVP: owner-or-admin авторизация удаления медиа (Model A из принятой политики).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Тесты MediaAssetService — авторизация удаления (H-7)")
class MediaAssetServiceTest {

    @Mock
    private MediaAssetRepository mediaAssetRepository;

    @InjectMocks
    private MediaAssetService mediaAssetService;

    @Test
    @DisplayName("recordUpload сохраняет MediaAsset с переданным uploaderId")
    void recordUpload_savesAssetWithUploaderId() {
        mediaAssetService.recordUpload("abc.png", 42L);

        verify(mediaAssetRepository).save(argThat(asset ->
                asset.getStorageKey().equals("abc.png") && asset.getUploaderId().equals(42L)));
    }

    @Test
    @DisplayName("recordUpload пробрасывает исключение при сбое сохранения (не подавляется больше)")
    void recordUpload_repositoryFailure_propagates() {
        when(mediaAssetRepository.save(any())).thenThrow(new RuntimeException("db down"));

        assertThrows(RuntimeException.class, () -> mediaAssetService.recordUpload("abc.png", 42L));
    }

    @Test
    @DisplayName("authorizeDelete: ADMIN может удалить любой файл без обращения к репозиторию")
    void authorizeDelete_admin_alwaysAllowed() {
        assertDoesNotThrow(() -> mediaAssetService.authorizeDelete("someone-elses.png", 999L, true));

        verifyNoInteractions(mediaAssetRepository);
    }

    @Test
    @DisplayName("authorizeDelete: владелец может удалить собственный файл")
    void authorizeDelete_owner_allowed() {
        MediaAsset asset = new MediaAsset(1L, "mine.png", 42L, OffsetDateTime.now());
        when(mediaAssetRepository.findByStorageKey("mine.png")).thenReturn(Optional.of(asset));

        assertDoesNotThrow(() -> mediaAssetService.authorizeDelete("mine.png", 42L, false));
    }

    @Test
    @DisplayName("authorizeDelete: не-владелец получает отказ (SecurityException)")
    void authorizeDelete_nonOwner_denied() {
        MediaAsset asset = new MediaAsset(1L, "someones.png", 42L, OffsetDateTime.now());
        when(mediaAssetRepository.findByStorageKey("someones.png")).thenReturn(Optional.of(asset));

        assertThrows(SecurityException.class,
                () -> mediaAssetService.authorizeDelete("someones.png", 999L, false));
    }

    @Test
    @DisplayName("authorizeDelete: файл без записи MediaAsset -> отказ не-администратору (fail-closed)")
    void authorizeDelete_unregisteredFile_nonAdminDenied() {
        when(mediaAssetRepository.findByStorageKey("historical.png")).thenReturn(Optional.empty());

        assertThrows(SecurityException.class,
                () -> mediaAssetService.authorizeDelete("historical.png", 42L, false));
    }

    @Test
    @DisplayName("deleteRecord удаляет запись MediaAsset по storageKey")
    void deleteRecord_delegatesToRepository() {
        mediaAssetService.deleteRecord("abc.png");

        verify(mediaAssetRepository).deleteByStorageKey("abc.png");
    }
}
