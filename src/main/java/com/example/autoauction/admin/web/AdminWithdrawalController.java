package com.example.autoauction.admin.web;

import com.example.autoauction.auth.domain.UserPrincipal;
import com.example.autoauction.auth.infrastructure.security.CurrentUser;
import com.example.autoauction.deposit.application.WithdrawalRequestService;
import com.example.autoauction.deposit.domain.WithdrawalRequest;
import com.example.autoauction.deposit.domain.WithdrawalRequestStatus;
import com.example.autoauction.deposit.web.dto.RejectWithdrawalRequest;
import com.example.autoauction.deposit.web.dto.WithdrawalRequestResponse;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

/**
 * F6: admin processing of manual payout requests. The acting admin is always the authenticated
 * principal. PAID records that AlSo paid the user by hand - it is not a bank operation.
 */
@RestController
@RequestMapping("/api/v1/admin/withdrawals")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Вывод средств (Администратор)", description = "Обработка заявок на ручную выплату")
public class AdminWithdrawalController {

    private final WithdrawalRequestService withdrawalRequestService;
    private final UserRepository userRepository;

    public AdminWithdrawalController(WithdrawalRequestService withdrawalRequestService, UserRepository userRepository) {
        this.withdrawalRequestService = withdrawalRequestService;
        this.userRepository = userRepository;
    }

    @GetMapping
    @Operation(summary = "Заявки на вывод (фильтр: status), новые сверху")
    public List<WithdrawalRequestResponse> list(@RequestParam(required = false) WithdrawalRequestStatus status) {
        return withdrawalRequestService.listAll(status).stream().map(this::toResponse).toList();
    }

    @PostMapping("/{id}/processing")
    @Operation(summary = "Взять заявку в работу (выплата ещё не выполнена)")
    public WithdrawalRequestResponse startProcessing(@PathVariable Long id,
                                                     @Parameter(hidden = true) @CurrentUser UserPrincipal currentUser) {
        return toResponse(withdrawalRequestService.startProcessing(id, currentUser.getUserId()));
    }

    @PostMapping("/{id}/paid")
    @Operation(summary = "Подтвердить, что ручная выплата пользователю выполнена")
    public WithdrawalRequestResponse markPaid(@PathVariable Long id,
                                              @Parameter(hidden = true) @CurrentUser UserPrincipal currentUser) {
        return toResponse(withdrawalRequestService.markPaid(id, currentUser.getUserId()));
    }

    @PostMapping("/{id}/reject")
    @Operation(summary = "Отклонить заявку и вернуть сумму на депозит")
    public WithdrawalRequestResponse reject(@PathVariable Long id, @Valid @RequestBody RejectWithdrawalRequest body,
                                            @Parameter(hidden = true) @CurrentUser UserPrincipal currentUser) {
        return toResponse(withdrawalRequestService.reject(id, currentUser.getUserId(), body.reason()));
    }

    private WithdrawalRequestResponse toResponse(WithdrawalRequest r) {
        Optional<User> user = userRepository.findById(r.getUserId());
        return WithdrawalRequestResponse.forAdmin(r,
                user.map(User::getUsername).orElse(null), user.map(User::getEmail).orElse(null));
    }
}
