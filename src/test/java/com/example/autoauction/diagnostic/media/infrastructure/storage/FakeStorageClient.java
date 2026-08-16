package com.example.autoauction.diagnostic.media.infrastructure.storage;

import com.example.autoauction.diagnostic.media.domain.port.StorageClient;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
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

    /** Test-only: simulates the browser's direct-to-storage PUT that would follow createPresignedUpload(). */
    public void simulateUpload(String storageKey, byte[] content, String contentType) {
        objects.put(storageKey, new StoredObject(content, contentType));
    }

    /** Test-only: simulates the object being missing (e.g. deleted out-of-band, or never actually uploaded). */
    public void forgetObject(String storageKey) {
        objects.remove(storageKey);
    }

    public boolean hasObject(String storageKey) {
        return objects.containsKey(storageKey);
    }

    @Override
    public PresignedUpload createPresignedUpload(String storageKey, String contentType, Duration ttl) {
        return new PresignedUpload(URI.create("https://fake-r2.test/" + storageKey), Instant.now().plus(ttl));
    }

    @Override
    public Optional<StoredObjectMetadata> headObject(String storageKey) {
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
}
