package com.example.autoauction.diagnostic.media.infrastructure.storage;

import com.example.autoauction.diagnostic.media.domain.port.StorageClient.PresignedUpload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MEDIA-PROD-03: the storage endpoint. Without R2_ENDPOINT nothing changes (Cloudflare's account endpoint,
 * bucket as a sub-domain); with it, presigned URLs go to that endpoint, path-style. Presigning is local - no network.
 */
@DisplayName("MEDIA-PROD-03: R2 endpoint override")
class R2StorageClientEndpointTest {

    private static URI presignedPut(String endpointOverride) {
        R2StorageClient client = new R2StorageClient("acct123", "bucket-x", "test-key-id", "test-key-secret", endpointOverride);
        PresignedUpload upload = client.createPresignedUpload("identity-documents/a.jpg", "image/jpeg", Duration.ofMinutes(15));
        return upload.uploadUrl();
    }

    @Test
    @DisplayName("no override: https://<account>.r2.cloudflarestorage.com, bucket as sub-domain (unchanged)")
    void defaultEndpoint() {
        assertThat(R2StorageClient.endpoint("acct123", "")).isEqualTo(URI.create("https://acct123.r2.cloudflarestorage.com"));
        assertThat(R2StorageClient.endpoint("acct123", null)).isEqualTo(URI.create("https://acct123.r2.cloudflarestorage.com"));
        URI url = presignedPut("");
        assertThat(url.getScheme()).isEqualTo("https");
        assertThat(url.getHost()).isEqualTo("bucket-x.acct123.r2.cloudflarestorage.com");
        assertThat(url.getPath()).isEqualTo("/identity-documents/a.jpg");
    }

    @Test
    @DisplayName("override: presigned URL on that endpoint, bucket in the path")
    void overrideEndpoint() {
        URI url = presignedPut(" https://acct123.eu.r2.cloudflarestorage.com ");
        assertThat(url.getHost()).isEqualTo("acct123.eu.r2.cloudflarestorage.com");
        assertThat(url.getPath()).isEqualTo("/bucket-x/identity-documents/a.jpg");
        assertThat(url.getQuery()).contains("X-Amz-Signature=").doesNotContain("test-key-secret");
    }
}
