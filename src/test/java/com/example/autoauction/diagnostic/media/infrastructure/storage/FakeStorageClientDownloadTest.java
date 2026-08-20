// diagnostic/media/infrastructure/storage/FakeStorageClientDownloadTest.java
package com.example.autoauction.diagnostic.media.infrastructure.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MEDIA-3.4 Phase 3: StorageClient.downloadToFile() contract, proven against
 * FakeStorageClient directly - no Spring context needed, FakeStorageClient
 * has no injected dependencies.
 */
@DisplayName("MEDIA-3.4: StorageClient.downloadToFile()")
class FakeStorageClientDownloadTest {

    private static final byte[] CONTENT = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x01, 0x02, 0x03};

    @Test
    @DisplayName("Успешная загрузка сохраняет точное содержимое объекта в целевой файл")
    void successfulDownload_WritesExactContent() throws Exception {
        FakeStorageClient client = new FakeStorageClient();
        client.simulateUpload("diagnostic-media/1/video.mp4", CONTENT, "video/mp4");
        Path destination = Files.createTempFile("download-test", ".mp4");

        client.downloadToFile("diagnostic-media/1/video.mp4", destination);

        assertThat(Files.readAllBytes(destination)).isEqualTo(CONTENT);
        Files.deleteIfExists(destination);
    }

    @Test
    @DisplayName("Отсутствующий объект -> исключение, файл не создаётся")
    void missingObject_Throws() throws Exception {
        FakeStorageClient client = new FakeStorageClient();
        Path destination = Files.createTempFile("download-test-missing", ".mp4");
        Files.delete(destination); // must not exist beforehand, to prove downloadToFile never wrote it

        assertThatThrownBy(() -> client.downloadToFile("diagnostic-media/nonexistent.mp4", destination))
                .isInstanceOf(NoSuchElementException.class);
        assertThat(Files.exists(destination)).isFalse();
    }

    @Test
    @DisplayName("Сбой транспортного уровня -> исключение (инъекция через failNextDownloadToFile)")
    void storageFailure_Throws() {
        FakeStorageClient client = new FakeStorageClient();
        client.simulateUpload("diagnostic-media/1/video.mp4", CONTENT, "video/mp4");
        client.failNextDownloadToFile();

        Path destination = Path.of(System.getProperty("java.io.tmpdir"), "download-test-fail.mp4");
        assertThatThrownBy(() -> client.downloadToFile("diagnostic-media/1/video.mp4", destination))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("simulated");

        // one-shot: the SAME call retried afterward succeeds normally
        client.downloadToFile("diagnostic-media/1/video.mp4", destination);
        assertThat(destination).exists();
    }
}
