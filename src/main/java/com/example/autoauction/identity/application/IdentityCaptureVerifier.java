package com.example.autoauction.identity.application;

import com.example.autoauction.diagnostic.media.domain.port.StorageClient;
import com.example.autoauction.identity.domain.DocumentType;
import com.example.autoauction.identity.domain.IdentityCaptureSide;
import com.example.autoauction.identity.domain.IdentityDocument;
import com.example.autoauction.identity.domain.port.IdentityDocumentRepository;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * KYC-CAPTURE-01: the server-side checks a camera capture must pass before a submission may use it. The
 * frontend is not a security boundary - a direct HTTP client gets exactly the same answers:
 * <ul>
 *   <li>the capture slot exists and belongs to the authenticated user (403 otherwise);</li>
 *   <li>it was issued for this side and, for a document side, for this document type;</li>
 *   <li>it is not consumed (single use, 409) and not expired;</li>
 *   <li>it is a JPEG - what the platform camera produces - and the bytes are in storage as a JPEG.</li>
 * </ul>
 * The server cannot prove the image came from a camera (the browser uploads straight to storage); these
 * checks bind each image to one capture step of one user. Anti-spoofing is KYC-ANTI-SPOOF-01.
 */
@Component
public class IdentityCaptureVerifier {

    /** The platform camera encodes captures as JPEG (canvas.toBlob) - the only accepted content type. */
    public static final String CAPTURE_CONTENT_TYPE = "image/jpeg";
    /** A capture is usable for a submission within this window of its slot - the presigned upload's own TTL. */
    public static final Duration CAPTURE_VALIDITY = Duration.ofMinutes(15);

    private final IdentityDocumentRepository documentRepository;
    private final StorageClient storageClient;

    public IdentityCaptureVerifier(IdentityDocumentRepository documentRepository, StorageClient storageClient) {
        this.documentRepository = documentRepository;
        this.storageClient = storageClient;
    }

    /**
     * Returns the capture for {@code storageKey} after every check above, or throws:
     * {@link IllegalArgumentException} (400), {@link SecurityException} (403), {@link IllegalStateException} (409).
     */
    public IdentityDocument require(Long userId, String storageKey, IdentityCaptureSide side,
                                    DocumentType documentType, OffsetDateTime now) {
        String label = label(side);
        if (storageKey == null || storageKey.isBlank()) {
            throw new IllegalArgumentException("Нужна фотография: " + label);
        }
        IdentityDocument capture = documentRepository.findByStorageKey(storageKey)
                .orElseThrow(() -> new IllegalArgumentException("Снимок не найден: " + label));
        if (!capture.isOwnedBy(userId)) {
            throw new SecurityException("Снимок не принадлежит текущему пользователю");
        }
        if (capture.getCaptureSide() != side) {
            throw new IllegalArgumentException("Снимок сделан не для этого шага: " + label);
        }
        if (side != IdentityCaptureSide.SELFIE && capture.getDocumentType() != documentType) {
            throw new IllegalArgumentException("Снимок сделан для другого типа документа: " + label);
        }
        if (capture.isConsumed()) {
            throw new IllegalStateException("Снимок уже использован в заявке. Сделайте новый снимок: " + label);
        }
        if (capture.isExpiredAt(now, CAPTURE_VALIDITY)) {
            throw new IllegalArgumentException("Срок действия снимка истёк. Сделайте новый снимок: " + label);
        }
        if (!CAPTURE_CONTENT_TYPE.equals(capture.getMimeType())) {
            throw new IllegalArgumentException("Снимок должен быть сделан камерой (JPEG): " + label);
        }
        StorageClient.StoredObjectMetadata stored = storageClient.headObject(storageKey)
                .orElseThrow(() -> new IllegalArgumentException("Файл снимка не был загружен: " + label));
        if (!CAPTURE_CONTENT_TYPE.equals(stored.contentType())) {
            throw new IllegalArgumentException("Снимок должен быть сделан камерой (JPEG): " + label);
        }
        return capture;
    }

    /**
     * Marks every capture as used, all-or-nothing within the caller's transaction. A capture already consumed
     * by a concurrent submission makes the whole submission fail (and roll back).
     */
    public void consume(List<IdentityDocument> captures, OffsetDateTime now) {
        for (IdentityDocument capture : captures) {
            Objects.requireNonNull(capture.getId(), "capture id не может быть null");
            if (!documentRepository.markConsumed(capture.getId(), now)) {
                throw new IllegalStateException("Снимок уже использован в другой заявке. Сделайте новые снимки.");
            }
        }
    }

    static String label(IdentityCaptureSide side) {
        return switch (side) {
            case FRONT -> "лицевая сторона";
            case BACK -> "обратная сторона";
            case DATA_PAGE -> "страница с данными";
            case SELFIE -> "селфи";
        };
    }
}
