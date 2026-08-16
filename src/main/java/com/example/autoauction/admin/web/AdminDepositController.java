package com.example.autoauction.admin.web;

import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.deposit.domain.port.DepositRepository;
import com.example.autoauction.deposit.web.dto.DepositResponse;
import com.example.autoauction.deposit.web.dto.DepositTransactionResponse;
import com.example.autoauction.user.application.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/deposits")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Tag(name = "Депозиты (Администратор)", description = "Управление депозитами всех пользователей")
public class AdminDepositController {

    private final DepositRepository depositRepository;
    private final DepositService depositService;

    @GetMapping
    @Operation(summary = "Список всех депозитов пользователей")
    public ResponseEntity<List<DepositResponse>> getAllDeposits() {
        List<DepositResponse> deposits = depositRepository.findAll()
                .stream()
                .map(DepositResponse::fromDomain)
                .toList();
        return ResponseEntity.ok(deposits);
    }

    @GetMapping("/{userId}/transactions")
    @Operation(summary = "Транзакции по депозиту пользователя")
    public ResponseEntity<List<DepositTransactionResponse>> getUserTransactions(@PathVariable Long userId) {
        return ResponseEntity.ok(depositService.getTransactionsByUserId(userId));
    }

    @PostMapping("/{userId}/adjust")
    @Operation(summary = "Корректировка депозита пользователя")
    public ResponseEntity<DepositResponse> adjustDeposit(
            @PathVariable Long userId,
            @RequestBody Map<String, Object> body
    ) {
        BigDecimal amount = new BigDecimal(body.get("amount").toString());
        String comment = (String) body.get("comment");
        String idempotencyKey = (String) body.get("idempotencyKey");
        return ResponseEntity.ok(depositService.adminAdjust(userId, amount, comment, idempotencyKey));
    }
}
