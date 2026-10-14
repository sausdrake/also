package com.example.autoauction.identity.web;

import com.example.autoauction.auth.domain.UserPrincipal;
import com.example.autoauction.auth.infrastructure.security.CurrentUser;
import com.example.autoauction.identity.application.IdentityVerificationService;
import com.example.autoauction.identity.domain.DocumentType;
import com.example.autoauction.identity.domain.IdentityVerification;
import com.example.autoauction.identity.domain.IdentityVerificationHistory;
import com.example.autoauction.identity.web.dto.IdentityDocumentUrlResponse;
import com.example.autoauction.identity.web.dto.IdentityHistoryEventResponse;
import com.example.autoauction.identity.web.dto.IdentityStatusResponse;
import com.example.autoauction.identity.web.dto.IdentitySubmissionRequest;
import com.example.autoauction.identity.web.dto.IdentityUploadUrlRequest;
import com.example.autoauction.identity.web.dto.IdentityUploadUrlResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Phase 4: self-service identity-verification endpoints. The actor is always taken from
 * {@link UserPrincipal} - no body/path field for the subject's own user id exists anywhere in
 * this controller (Phase 3.1 §4/Phase 4 §6).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/identity")
@PreAuthorize("isAuthenticated()")
@Tag(name = "Подтверждение личности", description = "Самостоятельная отправка и статус верификации личности")
public class IdentityController {

    private final IdentityVerificationService identityVerificationService;

    public IdentityController(IdentityVerificationService identityVerificationService) {
        this.identityVerificationService = identityVerificationService;
    }

    @PostMapping("/documents/upload-url")
    @Operation(summary = "Получить URL для загрузки документа (ключ генерируется сервером)")
    public ResponseEntity<IdentityUploadUrlResponse> requestUploadUrl(
            @Valid @RequestBody IdentityUploadUrlRequest request,
            @Parameter(hidden = true) @CurrentUser UserPrincipal currentUser
    ) {
        Long userId = currentUser.getUserId();
        log.info("User {} requesting identity document upload slot", userId);
        var slot = identityVerificationService.requestUploadSlot(userId, request.contentType());
        return ResponseEntity.ok(IdentityUploadUrlResponse.fromSlot(slot));
    }

    @PostMapping("/submissions")
    @Operation(summary = "Отправить заявку на подтверждение личности")
    public ResponseEntity<IdentityStatusResponse> submit(
            @Valid @RequestBody IdentitySubmissionRequest request,
            @Parameter(hidden = true) @CurrentUser UserPrincipal currentUser
    ) {
        Long userId = currentUser.getUserId();
        DocumentType documentType = DocumentType.valueOf(request.documentType());
        log.info("User {} submitting identity verification", userId);
        IdentityVerification iv = identityVerificationService.submit(
                userId, request.issuingCountry(), documentType, request.rawDocumentNumber(), request.documentStorageKey(),
                request.selfieStorageKey());
        return ResponseEntity.ok(IdentityStatusResponse.fromDomain(iv));
    }

    @GetMapping("/me")
    @Operation(summary = "Получить мой статус подтверждения личности")
    public ResponseEntity<IdentityStatusResponse> getMyStatus(
            @Parameter(hidden = true) @CurrentUser UserPrincipal currentUser
    ) {
        IdentityVerification iv = identityVerificationService.getOwnStatus(currentUser.getUserId());
        return ResponseEntity.ok(IdentityStatusResponse.fromDomain(iv));
    }

    @GetMapping("/me/history")
    @Operation(summary = "Получить историю моих заявок на подтверждение личности")
    public List<IdentityHistoryEventResponse> getMyHistory(
            @Parameter(hidden = true) @CurrentUser UserPrincipal currentUser
    ) {
        List<IdentityVerificationHistory> history = identityVerificationService.getOwnHistory(currentUser.getUserId());
        return history.stream().map(IdentityHistoryEventResponse::fromDomainForOwner).toList();
    }

    @GetMapping("/me/document-url")
    @Operation(summary = "Получить временную ссылку на мой загруженный документ")
    public ResponseEntity<IdentityDocumentUrlResponse> getMyDocumentUrl(
            @Parameter(hidden = true) @CurrentUser UserPrincipal currentUser
    ) {
        var url = identityVerificationService.getOwnDocumentUrl(currentUser.getUserId());
        return ResponseEntity.ok(new IdentityDocumentUrlResponse(url.toString()));
    }
}
