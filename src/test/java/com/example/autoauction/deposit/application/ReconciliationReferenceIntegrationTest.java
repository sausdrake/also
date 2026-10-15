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
    @DisplayName("V56 normalisation: a legacy row stored with surrounding spaces is the same operation as the trimmed reference")
    void legacyUntrimmedReference_isTheSameOperation() {
        // H2 has no V56 index, so this isolates the application lookup: it must compare trimmed values
        Long userId = user("0");
        String reference = ref();
        Long depositId = depositRepository.findByUserId(userId).orElseThrow().getId();
        DepositTransaction legacy = new DepositTransaction(depositId, userId, DepositTransaction.TransactionType.ADMIN_ADJUSTMENT,
                new BigDecimal("5000"), BigDecimal.ZERO, new BigDecimal("5000"), "  " + reference + " ", "RECONCILIATION");
        legacy.setIdempotencyKey("k-legacy-" + System.nanoTime());
        depositRepository.saveTransaction(legacy);

        depositService.adminAdjust(userId, new BigDecimal("5000"), AdminAdjustmentReason.RECONCILIATION, reference,
                "k-" + System.nanoTime(), ADMIN_ID);
        assertThatThrownBy(() -> depositService.adminAdjust(userId, new BigDecimal("4000"), AdminAdjustmentReason.RECONCILIATION,
                " " + reference, "k-" + System.nanoTime(), ADMIN_ID))
                .isInstanceOf(IllegalStateException.class);

        assertThat(balance(userId)).as("no credit beyond the legacy row's").isEqualByComparingTo("0");
        assertThat(adjustments(userId)).hasSize(1);
    }

    /** D-F7-1: {existing raw reference, incoming reference, same external operation under btrim?} */
    private static final Object[][] BTRIM_CASES = {
            {"REF\t", "REF\t", true},      // 1 TAB is kept on both sides
            {"REF\n", "REF\n", true},      // 2 LF
            {"REF\r", "REF\r", true},      // 3 CR
            {"REF\t", " REF\t ", true},    // 4 only the outer spaces go
            {"REF", " REF ", true},         // 5 spaces
            {"REF\t", "REF", false},        // 6 TAB is part of the identifier
            {"REF", "REF\t", false},        // 6 (reverse)
            {"REF-A", "REF-B", false},      // 8 distinct references
    };

    @Test
    @DisplayName("D-F7-1: the application normalises exactly like btrim - TAB/LF/CR are kept, only U+0020 is stripped")
    void lookupMatchesBtrimSemantics() {
        for (Object[] c : BTRIM_CASES) {
            String suffix = "-" + System.nanoTime();
            String existing = ((String) c[0]).replace("REF", "REF" + suffix);
            String incoming = ((String) c[1]).replace("REF", "REF" + suffix);
            boolean same = (Boolean) c[2];
            String label = "[" + c[0] + "] vs [" + c[1] + "]";

            Long userId = user("0");
            Long depositId = depositRepository.findByUserId(userId).orElseThrow().getId();
            DepositTransaction legacy = new DepositTransaction(depositId, userId, DepositTransaction.TransactionType.ADMIN_ADJUSTMENT,
                    new BigDecimal("5000"), BigDecimal.ZERO, new BigDecimal("5000"), existing, "RECONCILIATION");
            legacy.setIdempotencyKey("k-legacy-" + System.nanoTime());
            depositRepository.saveTransaction(legacy);

            depositService.adminAdjust(userId, new BigDecimal("5000"), AdminAdjustmentReason.RECONCILIATION, incoming,
                    "k-" + System.nanoTime(), ADMIN_ID);

            if (same) {
                assertThat(balance(userId)).as(label + ": no second credit").isEqualByComparingTo("0");
                assertThat(adjustments(userId)).as(label).hasSize(1);
            } else {
                assertThat(balance(userId)).as(label + ": a different external operation is credited").isEqualByComparingTo("5000");
                assertThat(adjustments(userId)).as(label).hasSize(2);
            }
        }
    }

    @Test
    @DisplayName("D-F7-1: a stored reference keeps TAB/LF/CR and loses only its outer spaces")
    void storedReferenceIsBtrimmed() {
        Long userId = user("0");
        String reference = ref();
        depositService.adminAdjust(userId, new BigDecimal("100"), AdminAdjustmentReason.RECONCILIATION, "  " + reference + "\t ",
                "k-" + System.nanoTime(), ADMIN_ID);
        assertThat(adjustments(userId).get(0).getReference()).isEqualTo(reference + "\t");
        assertThat(DepositService.stripSpaces(" \t a b \n ")).isEqualTo("\t a b \n");
        assertThat(DepositService.stripSpaces("Ab")).isEqualTo("Ab");
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
