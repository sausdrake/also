package com.example.autoauction.diagnostic.media.infrastructure.storage;

import com.example.autoauction.diagnostic.media.domain.port.StorageClient;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MEDIA-3.2: in-memory StorageClient test double, used only under the test
 * profile (no MinIO, no real R2 account needed to run the suite). Tests
 * simulate a successful browser upload via simulateUpload(); real R2 object
 * existence/HEAD/GET-range behavior is approximated in memory.
 */
@Component
@Profile("test")
public class FakeStorageClient implements StorageClient {

    private record StoredObject(byte[] content, String contentType) {}

    private final Map<String, StoredObject> objects = new ConcurrentHashMap<>();
    private volatile boolean failNextPutObject = false;
    private volatile boolean failNextHeadObject = false;
    private volatile boolean failNextDownloadToFile = false;

    /** Test-only: simulates the browser's direct-to-storage PUT that would follow createPresignedUpload(). */
    public void simulateUpload(String storageKey, byte[] content, String contentType) {
        objects.put(storageKey, new StoredObject(content, contentType));
    }

    /** Test-only: the next putObject() call throws instead of succeeding (one-shot, then resets). */
    public void failNextPutObject() {
        failNextPutObject = true;
    }

    /** Test-only: the next headObject() call throws instead of returning (one-shot, then resets). */
    public void failNextHeadObject() {
        failNextHeadObject = true;
    }

    /** Test-only: the next downloadToFile() call throws instead of succeeding (one-shot, then resets). */
    public void failNextDownloadToFile() {
        failNextDownloadToFile = true;
    }

    /** Test-only: simulates the object being missing (e.g. deleted out-of-band, or never actually uploaded). */
    public void forgetObject(String storageKey) {
        objects.remove(storageKey);
    }

    public boolean hasObject(String storageKey) {
        return objects.containsKey(storageKey);
    }

    /** Test-only: total number of objects currently "stored" - used to prove no orphan remains. */
    public int objectCount() {
        return objects.size();
    }

    @Override
    public PresignedUpload createPresignedUpload(String storageKey, String contentType, Duration ttl) {
        return new PresignedUpload(URI.create("https://fake-r2.test/" + storageKey), Instant.now().plus(ttl));
    }

    @Override
    public void putObject(String storageKey, InputStream input, long sizeBytes, String contentType) {
        if (failNextPutObject) {
            failNextPutObject = false;
            throw new RuntimeException("simulated R2 putObject transport failure");
        }
        try {
            byte[] content = input.readAllBytes();
            objects.put(storageKey, new StoredObject(content, contentType));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<StoredObjectMetadata> headObject(String storageKey) {
        if (failNextHeadObject) {
            failNextHeadObject = false;
            throw new RuntimeException("simulated R2 headObject transport failure");
        }
        StoredObject obj = objects.get(storageKey);
        if (obj == null) return Optional.empty();
        return Optional.of(new StoredObjectMetadata(obj.content().length, obj.contentType()));
    }

    @Override
    public Optional<byte[]> fetchLeadingBytes(String storageKey, int byteCount) {
        StoredObject obj = objects.get(storageKey);
        if (obj == null) return Optional.empty();
        return Optional.of(Arrays.copyOf(obj.content(), Math.min(byteCount, obj.content().length)));
    }

    @Override
    public URI createPresignedReadUrl(String storageKey, Duration ttl) {
        return URI.create("https://fake-r2.test/" + storageKey + "?signed=1");
    }

    @Override
    public void deleteObject(String storageKey) {
        objects.remove(storageKey);
    }

    @Override
    public void downloadToFile(String storageKey, Path destination) {
        if (failNextDownloadToFile) {
            failNextDownloadToFile = false;
            throw new RuntimeException("simulated R2 downloadToFile transport failure");
        }
        StoredObject obj = objects.get(storageKey);
        if (obj == null) {
            throw new NoSuchElementException("No such object: " + storageKey);
        }
        try {
            Files.write(destination, obj.content());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
