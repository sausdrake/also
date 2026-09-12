package com.example.autoauction.identity.web;

import com.example.autoauction.auth.domain.UserPrincipal;
import com.example.autoauction.auth.infrastructure.security.CurrentUser;
import com.example.autoauction.identity.application.AdminIdentityApprovalOrchestrator;
import com.example.autoauction.identity.application.AdminIdentityVerificationService;
import com.example.autoauction.identity.domain.IdentityVerification;
import com.example.autoauction.identity.domain.IdentityVerificationHistory;
import com.example.autoauction.identity.domain.RevocationReason;
import com.example.autoauction.identity.web.dto.IdentityDocumentUrlResponse;
import com.example.autoauction.identity.web.dto.IdentityHistoryEventResponse;
import com.example.autoauction.identity.web.dto.IdentityRejectRequest;
import com.example.autoauction.identity.web.dto.IdentityRevokeRequest;
import com.example.autoauction.identity.web.dto.IdentityStatusResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Phase 4 / Phase 4.2: admin-only identity-review endpoints. URL-level authorization is already
 * provided by SecurityConfig's {@code /api/v1/admin/**} matcher; the class-level
 * {@code @PreAuthorize} is the same defense-in-depth double-layer used by
 * {@code AdminSaleController}. Approval MUST go through
 * {@link AdminIdentityApprovalOrchestrator#approveWithConflictHandling}, never
 * {@link AdminIdentityVerificationService#approve} directly, so a losing concurrent admin gets a
 * handled 409 instead of an unhandled constraint/concurrency-exception 500 (Phase 3.1 §1 / Phase
 * 4.2 - the orchestrator is a separate bean specifically so the proxy-based
 * {@code @Transactional} on {@code approve()}/{@code recordFailedApprovalAttempt()} is not bypassed
 * by same-bean self-invocation).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/identity")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Администрирование подтверждения личности", description = "Проверка и решения по заявкам на подтверждение личности (только ADMIN)")
public class AdminIdentityController {

    private final AdminIdentityVerificationService adminIdentityVerificationService;
    private final AdminIdentityApprovalOrchestrator adminIdentityApprovalOrchestrator;

    public AdminIdentityController(AdminIdentityVerificationService adminIdentityVerificationService,
                                    AdminIdentityApprovalOrchestrator adminIdentityApprovalOrchestrator) {
        this.adminIdentityVerificationService = adminIdentityVerificationService;
        this.adminIdentityApprovalOrchestrator = adminIdentityApprovalOrchestrator;
    }

    @GetMapping("/pending")
    @Operation(summary = "Список заявок, ожидающих рассмотрения")
    public List<IdentityStatusResponse> listPending() {
        return adminIdentityVerificationService.listPending().stream()
                .map(IdentityStatusResponse::fromDomain)
                .toList();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Просмотреть заявку на подтверждение личности")
    public ResponseEntity<IdentityStatusResponse> inspect(@PathVariable Long id) {
        IdentityVerification iv = adminIdentityVerificationService.inspect(id);
        return ResponseEntity.ok(IdentityStatusResponse.fromDomain(iv));
    }

    @GetMapping("/{id}/document-url")
    @Operation(summary = "Получить временную ссылку на документ заявки")
    public ResponseEntity<IdentityDocumentUrlResponse> getDocumentUrl(@PathVariable Long id) {
        var url = adminIdentityVerificationService.getDocumentUrl(id);
        return ResponseEntity.ok(new IdentityDocumentUrlResponse(url.toString()));
    }

    @GetMapping("/{id}/history")
    @Operation(summary = "Получить историю заявки на подтверждение личности")
    public List<IdentityHistoryEventResponse> getHistory(@PathVariable Long id) {
        List<IdentityVerificationHistory> history = adminIdentityVerificationService.getHistory(id);
        return history.stream().map(IdentityHistoryEventResponse::fromDomainForAdmin).toList();
    }

    @PostMapping("/{id}/approve")
    @Operation(summary = "Подтвердить заявку на подтверждение личности")
    public ResponseEntity<IdentityStatusResponse> approve(
            @PathVariable Long id,
            @Parameter(hidden = true) @CurrentUser UserPrincipal currentUser
    ) {
        Long adminId = currentUser.getUserId();
        log.info("Admin {} approving identity verification {}", adminId, id);
        IdentityVerification iv = adminIdentityApprovalOrchestrator.approveWithConflictHandling(id, adminId);
        return ResponseEntity.ok(IdentityStatusResponse.fromDomain(iv));
    }

    @PostMapping("/{id}/reject")
    @Operation(summary = "Отклонить заявку на подтверждение личности")
    public ResponseEntity<IdentityStatusResponse> reject(
            @PathVariable Long id,
            @Valid @RequestBody IdentityRejectRequest request,
            @Parameter(hidden = true) @CurrentUser UserPrincipal currentUser
    ) {
        Long adminId = currentUser.getUserId();
        log.info("Admin {} rejecting identity verification {}", adminId, id);
        IdentityVerification iv = adminIdentityVerificationService.reject(id, adminId, request.reason());
        return ResponseEntity.ok(IdentityStatusResponse.fromDomain(iv));
    }

    @PostMapping("/{id}/revoke")
    @Operation(summary = "Отозвать подтверждение личности")
    public ResponseEntity<IdentityStatusResponse> revoke(
            @PathVariable Long id,
            @Valid @RequestBody IdentityRevokeRequest request,
            @Parameter(hidden = true) @CurrentUser UserPrincipal currentUser
    ) {
        Long adminId = currentUser.getUserId();
        RevocationReason reasonCode = RevocationReason.valueOf(request.reasonCode());
        log.info("Admin {} revoking identity verification {} (reason={})", adminId, id, reasonCode);
        IdentityVerification iv = adminIdentityVerificationService.revoke(id, adminId, reasonCode, request.reasonText());
        return ResponseEntity.ok(IdentityStatusResponse.fromDomain(iv));
    }
}
