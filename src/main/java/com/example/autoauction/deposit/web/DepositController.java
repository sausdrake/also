package com.example.autoauction.deposit.web;

import com.example.autoauction.auth.domain.UserPrincipal;
import com.example.autoauction.auth.infrastructure.security.CurrentUser;
import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.deposit.domain.DepositTransaction;
import com.example.autoauction.deposit.web.dto.DepositRequest;
import com.example.autoauction.deposit.web.dto.DepositResponse;
import com.example.autoauction.deposit.web.dto.DepositTransactionResponse;
import com.example.autoauction.deposit.web.dto.WithdrawRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/deposits")
@Tag(name = "Депозиты", description = "Управление депозитами пользователей")
@org.springframework.security.access.prepost.PreAuthorize("isAuthenticated()")
public class DepositController {

    private final DepositService depositService;

    public DepositController(DepositService depositService) {
        this.depositService = depositService;
        log.info("DepositController initialized");
    }
    @PostMapping("/deposit")
    @Operation(summary = "Пополнить депозит")
    public ResponseEntity<DepositResponse> deposit(
            @Valid @RequestBody DepositRequest request,
            @Parameter(hidden = true)
            @CurrentUser UserPrincipal currentUser
    ) {
        Long userId = currentUser.getUserId();
        log.info("User {} depositing: {}", userId, request.amount());

        DepositResponse response = depositService.deposit(userId, request.amount());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/withdraw")
    @Operation(summary = "Вывести средства с депозита")
    public ResponseEntity<DepositResponse> withdraw(
            @Valid @RequestBody WithdrawRequest request,
            @Parameter(hidden = true)
            @CurrentUser UserPrincipal currentUser
    ) {
        Long userId = currentUser.getUserId();
        log.info("User {} withdrawing: {}", userId, request.amount());

        DepositResponse response = depositService.withdraw(userId, request.amount(), request.idempotencyKey());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/my")
    @Operation(summary = "Получить мой депозит")
    public ResponseEntity<DepositResponse> getMyDeposit(
            @Parameter(hidden = true)
            @CurrentUser UserPrincipal currentUser
    ) {
        Long userId = currentUser.getUserId();
        log.debug("User {} getting deposit info", userId);

        DepositResponse response = depositService.getDepositByUserId(userId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/my/transactions")
    @Operation(summary = "Получить мои транзакции (с фильтрами)")
    public List<DepositTransactionResponse> getMyTransactions(
            @RequestParam(required = false) DepositTransaction.TransactionType type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @Parameter(hidden = true) @CurrentUser UserPrincipal currentUser
    ) {
        if (type != null || from != null || to != null) {
            return depositService.getTransactionsFiltered(currentUser.getUserId(), type, from, to);
        }
        return depositService.getTransactionsByUserId(currentUser.getUserId());
    }

    @GetMapping("/can-participate")
    @Operation(summary = "Проверить возможность участия в аукционе (по фиксированной сумме депозита лота)")
    public ResponseEntity<ParticipationResponse> canParticipate(
            @RequestParam(required = false) BigDecimal requiredDepositAmount,
            @Parameter(hidden = true)
            @CurrentUser UserPrincipal currentUser
    ) {
        Long userId = currentUser.getUserId();
        boolean canParticipate;

        if (requiredDepositAmount != null) {
            canParticipate = depositService.canParticipateInAuction(userId, requiredDepositAmount);
        } else {
            canParticipate = depositService.canParticipateInAuction(userId);
        }

        return ResponseEntity.ok(new ParticipationResponse(canParticipate));
    }

    @PostMapping("/admin/users/{userId}/adjust")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Административная корректировка депозита")
    public ResponseEntity<DepositResponse> adminAdjust(
            @PathVariable Long userId,
            @RequestBody Map<String, Object> body
    ) {
        BigDecimal amount = new BigDecimal(body.get("amount").toString());
        String comment = (String) body.get("comment");
        String idempotencyKey = (String) body.get("idempotencyKey");
        return ResponseEntity.ok(depositService.adminAdjust(userId, amount, comment, idempotencyKey));
    }

    public record ParticipationResponse(boolean canParticipate) {}
}