package com.example.autoauction.policy.web;

import com.example.autoauction.auth.domain.UserPrincipal;
import com.example.autoauction.auth.infrastructure.security.CurrentUser;
import com.example.autoauction.policy.application.PolicyVersionService;
import com.example.autoauction.policy.domain.PenaltyType;
import com.example.autoauction.policy.domain.PolicyVersion;
import com.example.autoauction.policy.domain.ViolationDefinition;
import com.example.autoauction.policy.web.dto.ConfigureViolationRequest;
import com.example.autoauction.policy.web.dto.CreatePolicyDraftRequest;
import com.example.autoauction.policy.web.dto.PolicyVersionDetailResponse;
import com.example.autoauction.policy.web.dto.PolicyVersionSummaryResponse;
import com.example.autoauction.policy.web.dto.PublishPolicyRequest;
import com.example.autoauction.policy.web.dto.UpdatePolicyCommissionRequest;
import com.example.autoauction.policy.web.dto.ViolationDefinitionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Slice 1 (Policy Administration): the ADMIN-only operational surface for the pricing/violation
 * PolicyVersion lifecycle. A thin HTTP adapter - every operation delegates to
 * {@link PolicyVersionService}; no business logic here.
 *
 * <p>All mutation routes live under {@code /drafts/{id}/...} on purpose: a published
 * PolicyVersion has no mutation route at all, and the service + domain + DB triggers reject a
 * published id passed to one (409).
 *
 * <p>URL-level authorization is already provided by SecurityConfig's {@code /api/v1/admin/**}
 * matcher; the class-level {@code @PreAuthorize} is defense-in-depth, matching
 * {@code AdminDiagnosticController}.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/policy")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Администрирование ценовой политики",
        description = "Просмотр, черновики и публикация неизменяемых PolicyVersion (только ADMIN)")
public class AdminPolicyController {

    private final PolicyVersionService policyVersionService;

    public AdminPolicyController(PolicyVersionService policyVersionService) {
        this.policyVersionService = policyVersionService;
    }

    @GetMapping("/current")
    @Operation(summary = "Текущая опубликованная ценовая политика")
    public PolicyVersionDetailResponse current() {
        return toDetail(policyVersionService.getCurrentPublished());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Ценовая политика по id (черновик или опубликованная)")
    public PolicyVersionDetailResponse byId(@PathVariable Long id) {
        return toDetail(policyVersionService.getById(id));
    }

    @GetMapping
    @Operation(summary = "История версий политики (новые сверху); ?published=true|false — фильтр")
    public List<PolicyVersionSummaryResponse> list(@RequestParam(required = false) Boolean published) {
        return policyVersionService.listVersions(published).stream()
                .map(PolicyVersionSummaryResponse::fromDomain)
                .toList();
    }

    @GetMapping("/violation-definitions")
    @Operation(summary = "Стабильный каталог кодов нарушений")
    public List<ViolationDefinitionResponse> violationDefinitions() {
        return policyVersionService.listViolationDefinitions().stream()
                .map(ViolationDefinitionResponse::fromDomain)
                .toList();
    }

    @PostMapping("/drafts")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Создать черновик (копирует конфигурацию нарушений из текущей опубликованной версии)")
    public PolicyVersionDetailResponse createDraft(@Valid @RequestBody CreatePolicyDraftRequest request,
                                                   @CurrentUser UserPrincipal admin) {
        PolicyVersion draft = policyVersionService.create(request.commissionRate(), admin.getUserId());
        log.info("Admin {} created policy draft {} (commissionRate={})", admin.getUserId(), draft.getId(), request.commissionRate());
        return toDetail(draft);
    }

    @PatchMapping("/drafts/{id}")
    @Operation(summary = "Изменить ставку комиссии черновика")
    public PolicyVersionDetailResponse updateCommission(@PathVariable Long id,
                                                        @Valid @RequestBody UpdatePolicyCommissionRequest request) {
        return toDetail(policyVersionService.updateDraftCommission(id, request.commissionRate(), request.version()));
    }

    @PutMapping("/drafts/{id}/violations/{violationDefinitionId}")
    @Operation(summary = "Настроить штраф за одно нарушение в черновике")
    public PolicyVersionDetailResponse configureViolation(@PathVariable Long id,
                                                          @PathVariable Long violationDefinitionId,
                                                          @Valid @RequestBody ConfigureViolationRequest request) {
        PenaltyType penaltyType = parsePenaltyType(request.penaltyType());
        policyVersionService.updateDraftViolation(id, violationDefinitionId, penaltyType, request.penaltyValue(), request.version());
        return toDetail(policyVersionService.getById(id));
    }

    @PostMapping("/drafts/{id}/publish")
    @Operation(summary = "Опубликовать полный черновик -> новая неизменяемая версия (maker/checker: другой администратор)")
    public PolicyVersionDetailResponse publish(@PathVariable Long id,
                                               @Valid @RequestBody PublishPolicyRequest request,
                                               @CurrentUser UserPrincipal admin) {
        // Explicit 404 for an unknown id BEFORE publish() (whose claims would otherwise report
        // "not found" as a 409 conflict). publish()'s own behaviour is unchanged.
        policyVersionService.getById(id);
        PolicyVersion published = policyVersionService.publish(id, admin.getUserId(), request.observedConfigurationRevision());
        log.info("Admin {} published PolicyVersion {} (versionNumber={})",
                admin.getUserId(), published.getId(), published.getVersionNumber());
        return toDetail(published);
    }

    // ---------------------------------------------------------------------------

    private PenaltyType parsePenaltyType(String raw) {
        try {
            return PenaltyType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Недопустимый penaltyType: '" + raw + "'. Ожидается PERCENTAGE или FIXED_AMOUNT");
        }
    }

    private PolicyVersionDetailResponse toDetail(PolicyVersion version) {
        Map<Long, String> codeById = policyVersionService.listViolationDefinitions().stream()
                .collect(Collectors.toMap(ViolationDefinition::getId, ViolationDefinition::getCode, (a, b) -> a));
        return PolicyVersionDetailResponse.fromDomain(
                version, policyVersionService.getViolations(version.getId()), codeById);
    }
}
