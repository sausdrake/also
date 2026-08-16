package com.example.autoauction.diagnostic.media.infrastructure.storage;

import com.example.autoauction.diagnostic.media.domain.port.StorageClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Optional;

/**
 * MEDIA-3.2: Cloudflare R2 via its S3-compatible API (AWS SDK v2).
 * Endpoint/region per Cloudflare's current documented format:
 * https://{accountId}.r2.cloudflarestorage.com, region "auto". Credentials
 * come exclusively from environment variables (R2_ACCOUNT_ID, R2_BUCKET,
 * R2_ACCESS_KEY_ID, R2_SECRET_ACCESS_KEY) - same convention as
 * JWT_SECRET/DB_PASSWORD elsewhere in this project. Never logs secrets/signed
 * URLs.
 *
 * @Profile("!test") - never instantiated under the test profile, which uses
 * FakeStorageClient instead (no real R2 account is available or needed for
 * the test suite).
 */
@Slf4j
@Component
@Profile("!test")
public class R2StorageClient implements StorageClient {

    private final S3Client s3Client;
    private final S3Presigner presigner;
    private final String bucket;

    public R2StorageClient(
            @Value("${r2.account-id}") String accountId,
            @Value("${r2.bucket}") String bucket,
            @Value("${r2.access-key-id}") String accessKeyId,
            @Value("${r2.secret-access-key}") String secretAccessKey) {
        this.bucket = bucket;
        URI endpoint = URI.create("https://" + accountId + ".r2.cloudflarestorage.com");
        AwsBasicCredentials credentials = AwsBasicCredentials.create(accessKeyId, secretAccessKey);
        StaticCredentialsProvider credentialsProvider = StaticCredentialsProvider.create(credentials);

        this.s3Client = S3Client.builder()
                .endpointOverride(endpoint)
                .region(Region.of("auto"))
                .credentialsProvider(credentialsProvider)
                .build();
        this.presigner = S3Presigner.builder()
                .endpointOverride(endpoint)
                .region(Region.of("auto"))
                .credentialsProvider(credentialsProvider)
                .build();
        log.info("R2StorageClient initialized (bucket={})", bucket);
    }

    @Override
    public PresignedUpload createPresignedUpload(String storageKey, String contentType, Duration ttl) {
        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .putObjectRequest(b -> b.bucket(bucket).key(storageKey).contentType(contentType))
                .build();
        PresignedPutObjectRequest presigned = presigner.presignPutObject(presignRequest);
        return new PresignedUpload(toUri(presigned.url()), presigned.expiration());
    }

    @Override
    public Optional<StoredObjectMetadata> headObject(String storageKey) {
        try {
            var response = s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(storageKey).build());
            return Optional.of(new StoredObjectMetadata(response.contentLength(), response.contentType()));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            // S3-compatible HEAD on a missing key sometimes surfaces as a generic 404
            // S3Exception rather than NoSuchKeyException depending on the provider.
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public Optional<byte[]> fetchLeadingBytes(String storageKey, int byteCount) {
        try {
            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(storageKey)
                    .range("bytes=0-" + (byteCount - 1))
                    .build();
            byte[] bytes = s3Client.getObject(request, ResponseTransformer.toBytes()).asByteArray();
            return Optional.of(bytes);
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public URI createPresignedReadUrl(String storageKey, Duration ttl) {
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .getObjectRequest(b -> b.bucket(bucket).key(storageKey))
                .build();
        return toUri(presigner.presignGetObject(presignRequest).url());
    }

    @Override
    public void deleteObject(String storageKey) {
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(storageKey).build());
        } catch (SdkException e) {
            log.warn("Failed to delete R2 object {}: {}", storageKey, e.getMessage());
            throw e;
        }
    }

    private static URI toUri(java.net.URL url) {
        try {
            return url.toURI();
        } catch (URISyntaxException e) {
            throw new IllegalStateException("R2 returned an invalid presigned URL", e);
        }
    }
}
