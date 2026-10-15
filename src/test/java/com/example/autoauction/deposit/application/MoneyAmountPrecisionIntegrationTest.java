package com.example.autoauction.deposit.application;

import com.example.autoauction.auth.infrastructure.security.WithMockCustomUser;
import com.example.autoauction.deposit.domain.AdminAdjustmentReason;
import com.example.autoauction.deposit.domain.port.DepositRepository;
import com.example.autoauction.payment.domain.PaymentAmountPolicy;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S11 audit: every money column is NUMERIC(19,2), so an amount with fractional digits beyond the
 * tyiyn (e.g. 100.005) used to be accepted and then silently rounded by the database - the stored
 * ledger amount no longer matched the balance delta, and a replay with the same idempotency key
 * compared the rounded stored amount with the original one and was refused as a conflict. Such
 * amounts are now rejected up front, on the HTTP boundary and again in the services.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("S11: money amounts are limited to two decimal places")
class MoneyAmountPrecisionIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private DepositService depositService;
    @Autowired private DepositRepository depositRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;

    private Long userWithDeposit(String balance) {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        String name = "scale" + System.nanoTime();
        Long id = userRepository.save(new User(null, name, name + "@test.com", "encoded", true, Set.of(role))).getId();
        depositService.createDeposit(id, new BigDecimal(balance));
        return id;
    }

    private BigDecimal balanceOf(Long userId) {
        return depositRepository.findByUserId(userId).orElseThrow().getBalance();
    }

    @Test
    @DisplayName("withdraw: sub-tyiyn amount is refused, nothing is written; trailing zeros are fine")
    void withdraw_rejectsMoreThanTwoDecimals() {
        Long userId = userWithDeposit("1000");
        String key = "scale-w-" + System.nanoTime();

        assertThatThrownBy(() -> depositService.withdraw(userId, new BigDecimal("100.005"), key))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(balanceOf(userId)).isEqualByComparingTo("1000");
        assertThat(depositRepository.findTransactionByIdempotencyKey(key)).isEmpty();

        depositService.withdraw(userId, new BigDecimal("100.100"), key);
        assertThat(balanceOf(userId)).isEqualByComparingTo("899.90");
        // replay with the same key and amount is still an idempotent no-op
        depositService.withdraw(userId, new BigDecimal("100.10"), key);
        assertThat(balanceOf(userId)).isEqualByComparingTo("899.90");
    }

    @Test
    @DisplayName("adminAdjust: sub-tyiyn credit and debit are both refused")
    void adminAdjust_rejectsMoreThanTwoDecimals() {
        Long userId = userWithDeposit("1000");

        assertThatThrownBy(() -> depositService.adminAdjust(userId, new BigDecimal("10.001"),
                AdminAdjustmentReason.OTHER, "T-1", "scale-a1-" + System.nanoTime(), 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> depositService.adminAdjust(userId, new BigDecimal("-10.001"),
                AdminAdjustmentReason.OTHER, "T-2", "scale-a2-" + System.nanoTime(), 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(balanceOf(userId)).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("payment top-up policy: sub-tyiyn amount is refused")
    void paymentPolicy_rejectsMoreThanTwoDecimals() {
        assertThatThrownBy(() -> PaymentAmountPolicy.validateAmount(new BigDecimal("100.005")))
                .isInstanceOf(IllegalArgumentException.class);
        PaymentAmountPolicy.validateAmount(new BigDecimal("100.50"));
    }

    @Test
    @DisplayName("HTTP: withdraw, payment creation and admin adjust answer 400 for a sub-tyiyn amount")
    @WithMockCustomUser(userId = 1L, username = "admin", roles = {"ADMIN"})
    void http_rejectsMoreThanTwoDecimals() throws Exception {
        mockMvc.perform(post("/api/v1/withdrawals").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":100.005,\"idempotencyKey\":\"k-scale-1\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/payments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":100.005}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/admin/deposits/1/adjust").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":10.001,\"reason\":\"OTHER\",\"reference\":\"T\",\"idempotencyKey\":\"k-scale-2\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("HTTP: a withdraw idempotency key longer than its column (100) answers 400")
    @WithMockCustomUser(userId = 1L, username = "admin", roles = {"ADMIN"})
    void http_rejectsOverlongWithdrawKey() throws Exception {
        mockMvc.perform(post("/api/v1/withdrawals").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":100,\"idempotencyKey\":\"" + "k".repeat(101) + "\"}"))
                .andExpect(status().isBadRequest());
    }
}
