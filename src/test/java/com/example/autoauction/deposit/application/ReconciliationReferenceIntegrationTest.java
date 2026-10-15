package com.example.autoauction.deposit.application;

import com.example.autoauction.auth.infrastructure.security.WithMockCustomUser;
import com.example.autoauction.deposit.domain.AdminAdjustmentReason;
import com.example.autoauction.deposit.domain.DepositTransaction;
import com.example.autoauction.deposit.domain.port.DepositRepository;
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
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F7: for a RECONCILIATION adjustment the reference is the external unique identifier of the
 * money operation - one external operation, at most one credit. A repeat for the same user and
 * amount is a no-op, a repeat for another user or amount a conflict; other reasons keep their
 * non-unique references. (The race of two attempts is proven on PostgreSQL, see
 * {@code ReconciliationReferenceConcurrencyPgIntegrationTest}.)
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("F7: RECONCILIATION reference = one external operation, one credit")
class ReconciliationReferenceIntegrationTest {

    private static final Long ADMIN_ID = 42L;

    @Autowired private MockMvc mockMvc;
    @Autowired private DepositService depositService;
    @Autowired private DepositRepository depositRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;

    private Long user(String balance) {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        String name = "rec" + System.nanoTime();
        Long id = userRepository.save(new User(null, name, name + "@test.com", "encoded", true, Set.of(role))).getId();
        depositService.createDeposit(id, new BigDecimal(balance));
        return id;
    }

    private static String ref() {
        return "EXT-" + System.nanoTime();
    }

    private ResultActions adjust(Long userId, String amount, String reason, String reference) throws Exception {
        String referenceJson = reference == null ? "" : ",\"reference\":\"" + reference + "\"";
        return mockMvc.perform(post("/api/v1/admin/deposits/" + userId + "/adjust")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":" + amount + ",\"reason\":\"" + reason + "\"" + referenceJson
                        + ",\"idempotencyKey\":\"" + UUID.randomUUID() + "\"}"));
    }

    private BigDecimal balance(Long userId) {
        return depositRepository.findByUserId(userId).orElseThrow().getBalance();
    }

    private List<DepositTransaction> adjustments(Long userId) {
        return depositRepository.findTransactionsByUserId(userId).stream()
                .filter(t -> t.getType() == DepositTransaction.TransactionType.ADMIN_ADJUSTMENT).toList();
    }

    @Test
    @DisplayName("1+2: a new reference credits; the same reference again (new key) credits nothing more")
    @WithMockCustomUser(userId = 42L, username = "admin", roles = {"ADMIN"})
    void sameReference_creditsOnce() throws Exception {
        Long userId = user("0");
        String reference = ref();

        adjust(userId, "5000", "RECONCILIATION", reference).andExpect(status().isOk());
        adjust(userId, "5000", "RECONCILIATION", reference).andExpect(status().isOk());
        adjust(userId, "5000.00", "RECONCILIATION", " " + reference + " ").andExpect(status().isOk());

        assertThat(balance(userId)).isEqualByComparingTo("5000");
        assertThat(adjustments(userId)).hasSize(1);
        assertThat(adjustments(userId).get(0).getReference()).isEqualTo(reference);
    }

    @Test
    @DisplayName("3: the same reference for another user is refused, nobody is credited twice")
    @WithMockCustomUser(userId = 42L, username = "admin", roles = {"ADMIN"})
    void sameReference_otherUser_refused() throws Exception {
        Long a = user("0");
        Long b = user("0");
        String reference = ref();
        adjust(a, "5000", "RECONCILIATION", reference).andExpect(status().isOk());

        adjust(b, "5000", "RECONCILIATION", reference).andExpect(status().isConflict());

        assertThat(balance(a)).isEqualByComparingTo("5000");
        assertThat(balance(b)).isEqualByComparingTo("0");
        assertThat(adjustments(b)).isEmpty();
    }

    @Test
    @DisplayName("4: the same reference with another amount is refused")
    @WithMockCustomUser(userId = 42L, username = "admin", roles = {"ADMIN"})
    void sameReference_otherAmount_refused() throws Exception {
        Long userId = user("0");
        String reference = ref();
        adjust(userId, "5000", "RECONCILIATION", reference).andExpect(status().isOk());

        adjust(userId, "5001", "RECONCILIATION", reference).andExpect(status().isConflict());

        assertThat(balance(userId)).isEqualByComparingTo("5000");
        assertThat(adjustments(userId)).hasSize(1);
    }

    @Test
    @DisplayName("6+7: a too long or missing reference is a validation error, nothing is credited")
    @WithMockCustomUser(userId = 42L, username = "admin", roles = {"ADMIN"})
    void referenceValidation() throws Exception {
        Long userId = user("0");
        adjust(userId, "5000", "RECONCILIATION", "R".repeat(101)).andExpect(status().isBadRequest());
        adjust(userId, "5000", "RECONCILIATION", null).andExpect(status().isBadRequest());
        adjust(userId, "5000", "RECONCILIATION", "   ").andExpect(status().isBadRequest());

        // the service enforces the same, independent of the HTTP layer
        assertThatThrownBy(() -> depositService.adminAdjust(userId, new BigDecimal("5000"),
                AdminAdjustmentReason.RECONCILIATION, "R".repeat(101), "k-" + System.nanoTime(), ADMIN_ID))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> depositService.adminAdjust(userId, new BigDecimal("5000"),
                AdminAdjustmentReason.RECONCILIATION, " ", "k-" + System.nanoTime(), ADMIN_ID))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(balance(userId)).isEqualByComparingTo("0");
        assertThat(adjustments(userId)).isEmpty();
    }

    @Test
    @DisplayName("8: the payment flow is untouched - an automatic credit works and still blocks a RECONCILIATION of the same key")
    void paymentFlow_unchanged() {
        Long userId = user("0");
        String paymentKey = "PAYMENT:TX-" + System.nanoTime();
        depositService.creditFromPayment(new DepositCreditCommand(userId, new BigDecimal("3000"), "PAY-ref", paymentKey));
        assertThat(balance(userId)).isEqualByComparingTo("3000");

        assertThatThrownBy(() -> depositService.adminAdjust(userId, new BigDecimal("3000"),
                AdminAdjustmentReason.RECONCILIATION, paymentKey, "k-" + System.nanoTime(), ADMIN_ID))
                .isInstanceOf(IllegalStateException.class);
        assertThat(balance(userId)).isEqualByComparingTo("3000");
    }

    @Test
    @DisplayName("other reasons keep non-unique references: two SUPPORT_CORRECTIONs with one ticket both apply")
    @WithMockCustomUser(userId = 42L, username = "admin", roles = {"ADMIN"})
    void otherReasons_referenceNotUnique() throws Exception {
        Long userId = user("0");
        String ticket = "TICKET-" + System.nanoTime();
        adjust(userId, "100", "SUPPORT_CORRECTION", ticket).andExpect(status().isOk());
        adjust(userId, "100", "SUPPORT_CORRECTION", ticket).andExpect(status().isOk());

        assertThat(balance(userId)).isEqualByComparingTo("200");
        assertThat(adjustments(userId)).hasSize(2);
    }

    @Test
    @DisplayName("a RECONCILIATION reference does not collide with the same text used by another reason")
    @WithMockCustomUser(userId = 42L, username = "admin", roles = {"ADMIN"})
    void reconciliationVsOtherReason_independent() throws Exception {
        Long userId = user("0");
        String reference = ref();
        adjust(userId, "100", "SUPPORT_CORRECTION", reference).andExpect(status().isOk());
        adjust(userId, "5000", "RECONCILIATION", reference).andExpect(status().isOk());

        assertThat(balance(userId)).isEqualByComparingTo("5100");
    }
}
