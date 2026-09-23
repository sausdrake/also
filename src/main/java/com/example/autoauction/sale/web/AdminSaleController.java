package com.example.autoauction.sale.web;

import com.example.autoauction.auth.domain.UserPrincipal;
import com.example.autoauction.auth.infrastructure.security.CurrentUser;
import com.example.autoauction.auction.application.SaleAdministrationService;
import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.sale.application.HandoverEvidenceService;
import com.example.autoauction.sale.application.PaymentEvidenceService;
import com.example.autoauction.sale.application.SaleStatusQueryService;
import com.example.autoauction.sale.domain.HandoverEvidence;
import com.example.autoauction.sale.domain.PaymentEvidence;
import com.example.autoauction.sale.domain.SaleResolution;
import com.example.autoauction.sale.domain.SaleResolutionResult;
import com.example.autoauction.sale.web.dto.FailRequest;
import com.example.autoauction.sale.web.dto.HandoverEvidenceResponse;
import com.example.autoauction.sale.web.dto.PaymentDeadlineExtendRequest;
import com.example.autoauction.sale.web.dto.PaymentDeadlineHistoryResponse;
import com.example.autoauction.sale.web.dto.PaymentDeadlineResponse;
import com.example.autoauction.sale.web.dto.PaymentEvidenceResponse;
import com.example.autoauction.sale.web.dto.PaymentRequest;
import com.example.autoauction.sale.web.dto.RecordPayoutRequest;
import com.example.autoauction.sale.web.dto.SaleResolutionResponse;
import com.example.autoauction.sale.web.dto.SaleStatusResponse;
import com.example.autoauction.sale.web.dto.SettlementCorrectionRequest;
import com.example.autoauction.sale.web.dto.SettlementCorrectionResponse;
import com.example.autoauction.sale.web.dto.SettlementResponse;
import com.example.autoauction.settlement.application.SettlementService;
import com.example.autoauction.settlement.domain.Settlement;
import com.example.autoauction.settlement.domain.SettlementCorrection;
import com.example.autoauction.settlement.domain.SettlementCorrectionType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Slice 1 (Sale Completion &amp; Failure API): the four admin-only operations that move a
 * {@code WAITING_FOR_PAYMENT} auction through the confirmed-sale workflow to its terminal
 * outcome.
 *
 * <pre>
 *   POST /api/v1/admin/sale/{auctionId}/payment    -&gt; 201 PaymentEvidenceResponse
 *   POST /api/v1/admin/sale/{auctionId}/handover   -&gt; 201 HandoverEvidenceResponse
 *   POST /api/v1/admin/sale/{auctionId}/complete   -&gt; 200 SaleResolutionResponse
 *   POST /api/v1/admin/sale/{auctionId}/fail       -&gt; 200 SaleResolutionResponse
 * </pre>
 *
 * URL-level authorization is provided by SecurityConfig's {@code /api/v1/admin/**} matcher;
 * the class-level {@code @PreAuthorize} is the same defense-in-depth double-layer used by
 * {@code AdminDiagnosticController}. The authenticated admin identity is taken exclusively from
 * {@link UserPrincipal}; no actor id, buyer id, vehicle id, faultParty, penalty, commission or
 * refund amount is ever read from the request body. The controller holds no repository - all
 * transaction/lock orchestration lives in the application services.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/sale")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Администрирование продаж", description = "Подтверждение оплаты/передачи и финализация продажи (только ADMIN)")
public class AdminSaleController {

    private final PaymentEvidenceService paymentEvidenceService;
    private final HandoverEvidenceService handoverEvidenceService;
    private final SaleAdministrationService saleAdministrationService;
    private final SettlementService settlementService;
    private final SaleStatusQueryService saleStatusQueryService;
    private final Clock clock;

    public AdminSaleController(PaymentEvidenceService paymentEvidenceService,
                              HandoverEvidenceService handoverEvidenceService,
                              SaleAdministrationService saleAdministrationService,
                              SettlementService settlementService,
                              SaleStatusQueryService saleStatusQueryService,
                              Clock clock) {
        this.paymentEvidenceService = paymentEvidenceService;
        this.handoverEvidenceService = handoverEvidenceService;
        this.saleAdministrationService = saleAdministrationService;
        this.settlementService = settlementService;
        this.saleStatusQueryService = saleStatusQueryService;
        this.clock = clock;
    }

    @GetMapping("/{auctionId}")
    @Operation(summary = "Текущее состояние продажи: дедлайн + payment/handover evidence + resolution (только чтение)")
    public ResponseEntity<SaleStatusResponse> getSaleStatus(
            @Parameter(description = "ID аукциона", required = true) @PathVariable Long auctionId
    ) {
        return ResponseEntity.ok(saleStatusQueryService.getSaleStatus(auctionId));
    }

    @PostMapping("/{auctionId}/payment")
    @Operation(summary = "Зафиксировать физическое получение полной выигрышной суммы (admin-witnessed)")
    public ResponseEntity<PaymentEvidenceResponse> recordPayment(
            @Parameter(description = "ID аукциона", required = true) @PathVariable Long auctionId,
            @Valid @RequestBody PaymentRequest request,
            @Parameter(hidden = true) @CurrentUser UserPrincipal currentUser
    ) {
        Long adminId = currentUser.getUserId();
        log.info("Admin {} recording payment for auction {}", adminId, auctionId);
        PaymentEvidence evidence = paymentEvidenceService.recordPayment(auctionId, request.amount(), adminId);
        return ResponseEntity.status(HttpStatus.CREATED).body(PaymentEvidenceResponse.fromDomain(evidence));
    }

    @PostMapping("/{auctionId}/handover")
    @Operation(summary = "Зафиксировать передачу автомобиля и документов покупателю (требует существующей PaymentEvidence)")
    public ResponseEntity<HandoverEvidenceResponse> recordHandover(
            @Parameter(description = "ID аукциона", required = true) @PathVariable Long auctionId,
            @Parameter(hidden = true) @CurrentUser UserPrincipal currentUser
    ) {
        Long adminId = currentUser.getUserId();
        log.info("Admin {} recording handover for auction {}", adminId, auctionId);
        HandoverEvidence evidence = handoverEvidenceService.recordHandover(auctionId, adminId);
        return ResponseEntity.status(HttpStatus.CREATED).body(HandoverEvidenceResponse.fromDomain(evidence));
    }

    @PostMapping("/{auctionId}/complete")
    @Operation(summary = "Финализировать успешную продажу: WAITING_FOR_PAYMENT -> SOLD (требует Payment + Handover)")
    public ResponseEntity<SaleResolutionResponse> completeSale(
            @Parameter(description = "ID аукциона", required = true) @PathVariable Long auctionId,
            @Parameter(hidden = true) @CurrentUser UserPrincipal currentUser
    ) {
        Long adminId = currentUser.getUserId();
        log.info("Admin {} completing sale for auction {}", adminId, auctionId);
        SaleResolution resolution = saleAdministrationService.completeSale(auctionId, adminId);
        return ResponseEntity.ok(SaleResolutionResponse.fromDomain(resolution));
    }

    @PostMapping("/{auctionId}/fail")
    @Operation(summary = "Финализировать неудачную продажу: WAITING_FOR_PAYMENT -> SALE_FAILED (faultParty выводится сервером из outcome)")
    public ResponseEntity<SaleResolutionResponse> failSale(
            @Parameter(description = "ID аукциона", required = true) @PathVariable Long auctionId,
            @Valid @RequestBody FailRequest request,
            @Parameter(hidden = true) @CurrentUser UserPrincipal currentUser
    ) {
        Long adminId = currentUser.getUserId();
        SaleResolutionResult outcome = parseOutcome(request.outcome());
        log.info("Admin {} failing sale for auction {} as {}", adminId, auctionId, outcome);
        SaleResolution resolution = saleAdministrationService.failSale(
                auctionId, outcome, request.violationDefinitionIds(), request.reason(), adminId);
        return ResponseEntity.ok(SaleResolutionResponse.fromDomain(resolution));
    }

    @PostMapping("/{auctionId}/deadline/extend")
    @Operation(summary = "Изменить/продлить платёжный дедлайн (admin-only, unlimited, только для WAITING_FOR_PAYMENT)")
    public ResponseEntity<PaymentDeadlineResponse> extendPaymentDeadline(
            @Parameter(description = "ID аукциона", required = true) @PathVariable Long auctionId,
            @Valid @RequestBody PaymentDeadlineExtendRequest request,
            @Parameter(hidden = true) @CurrentUser UserPrincipal currentUser
    ) {
        Long adminId = currentUser.getUserId();
        log.info("Admin {} extending payment deadline for auction {}", adminId, auctionId);
        Auction auction = saleAdministrationService.extendPaymentDeadline(
                auctionId, request.newDeadline(), request.reason(), adminId);
        return ResponseEntity.ok(PaymentDeadlineResponse.fromDomain(auction, OffsetDateTime.now(clock)));
    }

    @GetMapping("/{auctionId}/deadline/history")
    @Operation(summary = "История изменений платёжного дедлайна (INITIAL + все ADMIN_EXTENSION), по возрастанию времени")
    public ResponseEntity<List<PaymentDeadlineHistoryResponse>> getPaymentDeadlineHistory(
            @Parameter(description = "ID аукциона", required = true) @PathVariable Long auctionId
    ) {
        List<PaymentDeadlineHistoryResponse> history = saleAdministrationService.getPaymentDeadlineHistory(auctionId)
                .stream().map(PaymentDeadlineHistoryResponse::fromDomain).toList();
        return ResponseEntity.ok(history);
    }

    @PostMapping("/{auctionId}/settlement/payout")
    @Operation(summary = "Зафиксировать attestation физической выплаты продавцу (admin-witnessed, не проверяется системой)")
    public ResponseEntity<SettlementResponse> recordSettlementPayout(
            @Parameter(description = "ID аукциона", required = true) @PathVariable Long auctionId,
            @Valid @RequestBody RecordPayoutRequest request,
            @Parameter(hidden = true) @CurrentUser UserPrincipal currentUser
    ) {
        Long adminId = currentUser.getUserId();
        log.info("Admin {} recording settlement payout for auction {}", adminId, auctionId);
        Settlement settlement = settlementService.recordPayout(auctionId, request.attestedAmount(), adminId);
        return ResponseEntity.ok(SettlementResponse.fromDomain(settlement));
    }

    @PostMapping("/{auctionId}/settlement/corrections")
    @Operation(summary = "Зафиксировать корректировку Settlement (сумма/продавец/недействительность исходного payout)")
    public ResponseEntity<SettlementCorrectionResponse> recordSettlementCorrection(
            @Parameter(description = "ID аукциона", required = true) @PathVariable Long auctionId,
            @Valid @RequestBody SettlementCorrectionRequest request,
            @Parameter(hidden = true) @CurrentUser UserPrincipal currentUser
    ) {
        Long adminId = currentUser.getUserId();
        SettlementCorrectionType type = parseCorrectionType(request.correctionType());
        log.info("Admin {} recording settlement correction for auction {}: type={}", adminId, auctionId, type);
        SettlementCorrection correction = settlementService.recordCorrection(auctionId, type,
                request.correctedAmount(), request.correctedSellerId(), request.reason(), adminId,
                request.idempotencyKey());
        return ResponseEntity.status(HttpStatus.CREATED).body(SettlementCorrectionResponse.fromDomain(correction));
    }

    /**
     * Parses the admin-selected correction type string. An unknown value is a 400 - this API
     * never invents a fourth correction category.
     */
    private SettlementCorrectionType parseCorrectionType(String raw) {
        try {
            return SettlementCorrectionType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Недопустимый correctionType: '" + raw
                    + "'. Ожидается один из: AMOUNT_CORRECTION, SELLER_CORRECTION, PAYOUT_INVALIDATED");
        }
    }

    /**
     * Parses the admin-selected outcome string. An unknown value (or SUCCESS) is a 400 - the
     * failed-sale API never finalizes a success, and never invents an outcome.
     */
    private SaleResolutionResult parseOutcome(String raw) {
        SaleResolutionResult parsed;
        try {
            parsed = SaleResolutionResult.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Недопустимый outcome: '" + raw
                    + "'. Ожидается один из: B, BUYER_FAULT, PLATFORM_FAULT, C");
        }
        if (parsed == SaleResolutionResult.SUCCESS) {
            throw new IllegalArgumentException(
                    "outcome=SUCCESS недопустим для /fail - используйте /complete");
        }
        return parsed;
    }
}
