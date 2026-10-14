package com.example.autoauction.payment.web;

import com.example.autoauction.auth.domain.UserPrincipal;
import com.example.autoauction.auth.infrastructure.security.CurrentUser;
import com.example.autoauction.identity.application.VerifiedIdentityGate;
import com.example.autoauction.payment.application.PaymentCreationOrchestrator;
import com.example.autoauction.payment.application.PaymentCreationView;
import com.example.autoauction.payment.web.dto.CreatePaymentRequest;
import com.example.autoauction.payment.web.dto.PaymentResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * P1.5: self-service payment creation only - mirrors {@code DepositController}'s own
 * conventions exactly (class-level {@code isAuthenticated()}, {@code @CurrentUser} for actor
 * identity, never a client-supplied userId). No admin route, no read-by-id route, no update
 * route - only what the locked P1.5 contract actually requires.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/payments")
@Tag(name = "Платежи", description = "Создание платежей (пополнение депозита)")
@PreAuthorize("isAuthenticated()")
public class PaymentController {

    private final PaymentCreationOrchestrator paymentCreationOrchestrator;
    private final VerifiedIdentityGate verifiedIdentityGate;

    public PaymentController(PaymentCreationOrchestrator paymentCreationOrchestrator,
                             VerifiedIdentityGate verifiedIdentityGate) {
        this.paymentCreationOrchestrator = paymentCreationOrchestrator;
        this.verifiedIdentityGate = verifiedIdentityGate;
        log.info("PaymentController initialized");
    }

    @PostMapping
    @Operation(summary = "Создать платёж (пополнение депозита)")
    public ResponseEntity<PaymentResponse> createPayment(
            @Valid @RequestBody CreatePaymentRequest request,
            @Parameter(hidden = true)
            @CurrentUser UserPrincipal currentUser
    ) {
        Long userId = currentUser.getUserId();
        log.info("User {} creating payment: amount={}", userId, request.amount());
        verifiedIdentityGate.requireVerified(userId);

        PaymentCreationView view = paymentCreationOrchestrator.createPayment(
                userId, request.amount(), request.idempotencyKey());
        return ResponseEntity.ok(PaymentResponse.from(view));
    }
}
