package com.example.autoauction.deposit.web;

import com.example.autoauction.auth.domain.UserPrincipal;
import com.example.autoauction.auth.infrastructure.security.CurrentUser;
import com.example.autoauction.deposit.application.WithdrawalRequestService;
import com.example.autoauction.deposit.web.dto.WithdrawRequest;
import com.example.autoauction.deposit.web.dto.WithdrawalRequestResponse;
import com.example.autoauction.identity.application.VerifiedIdentityGate;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * F6: the user's side of manual payout requests. The user is always the authenticated principal;
 * no route takes a user or request id, so another user's request is unreachable.
 */
@RestController
@RequestMapping("/api/v1/withdrawals")
@PreAuthorize("isAuthenticated()")
@Tag(name = "Вывод средств", description = "Заявки на вывод средств с депозита (ручная выплата)")
public class WithdrawalController {

    private final WithdrawalRequestService withdrawalRequestService;
    private final VerifiedIdentityGate verifiedIdentityGate;

    public WithdrawalController(WithdrawalRequestService withdrawalRequestService,
                                VerifiedIdentityGate verifiedIdentityGate) {
        this.withdrawalRequestService = withdrawalRequestService;
        this.verifiedIdentityGate = verifiedIdentityGate;
    }

    @PostMapping
    @Operation(summary = "Создать заявку на вывод средств (сумма сразу списывается с доступного баланса)")
    public WithdrawalRequestResponse create(@Valid @RequestBody WithdrawRequest request,
                                            @Parameter(hidden = true) @CurrentUser UserPrincipal currentUser) {
        Long userId = currentUser.getUserId();
        verifiedIdentityGate.requireVerified(userId);
        return WithdrawalRequestResponse.forOwner(
                withdrawalRequestService.create(userId, request.amount(), request.idempotencyKey()));
    }

    @GetMapping("/my")
    @Operation(summary = "Мои заявки на вывод средств")
    public List<WithdrawalRequestResponse> my(@Parameter(hidden = true) @CurrentUser UserPrincipal currentUser) {
        return withdrawalRequestService.listMine(currentUser.getUserId()).stream()
                .map(WithdrawalRequestResponse::forOwner).toList();
    }
}
