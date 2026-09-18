package com.example.autoauction.payment.web;

import com.example.autoauction.auth.domain.TestUserPrincipal;
import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.deposit.domain.DepositTransaction;
import com.example.autoauction.deposit.domain.port.DepositRepository;
import com.example.autoauction.payment.domain.Payment;
import com.example.autoauction.payment.domain.PaymentStatus;
import com.example.autoauction.payment.domain.ReconciliationCaseType;
import com.example.autoauction.payment.domain.port.PaymentEventRepository;
import com.example.autoauction.payment.domain.port.PaymentRepository;
import com.example.autoauction.payment.domain.port.ReconciliationCaseRepository;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 26: HTTP-layer proof for {@code /api/v1/admin/payments/late-successes} - ADMIN-only
 * access, correct {@code lateSuccessAfterExpiry}-only filtering (never merely
 * {@code status==SUCCEEDED}), newest-succeeded-first ordering, correct DTO field mapping, and
 * that a GET causes zero mutation anywhere (Payment, Deposit, PaymentEvent, ReconciliationCase).
 * Mirrors {@code AdminReconciliationControllerApiIntegrationTest}'s own conventions exactly.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Admin payment API - late-success observability")
class AdminPaymentControllerApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private DepositRepository depositRepository;
    @Autowired private DepositService depositService;
    @Autowired private PaymentEventRepository paymentEventRepository;
    @Autowired private ReconciliationCaseRepository reconciliationCaseRepository;
    @Autowired private ObjectMapper objectMapper;

    private static final String URL = "/api/v1/admin/payments/late-successes";

    private static RequestPostProcessor user(Long userId, String... roles) {
        return SecurityMockMvcRequestPostProcessors.user(TestUserPrincipal.createWithRoles(userId, "u" + userId, roles));
    }

    private Long newOwningUser() {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        User u = new User(null, "latepay" + System.nanoTime(), "latepay" + System.nanoTime() + "@test.com",
                "encoded", true, Set.of(role));
        Long userId = userRepository.save(u).getId();
        depositService.createDeposit(userId, BigDecimal.ZERO);
        return userId;
    }

    /** Real domain transition: CREATED -&gt; PENDING -&gt; EXPIRED -&gt; SUCCEEDED (via {@code markSucceeded},
     * the same method the real EXPIRED-&gt;SUCCEEDED confirmation path uses), so the persisted
     * {@code lateSuccessAfterExpiry} flag is authentic, not fabricated. */
    private Payment newLateSuccessPayment(Long userId, BigDecimal amountRequested, BigDecimal amountConfirmed) {
        OffsetDateTime now = OffsetDateTime.now();
        Payment p = new Payment(userId, "late-ref-" + System.nanoTime(), amountRequested, null, now, now.plusMinutes(30));
        p.markPending("psp-ref-" + System.nanoTime());
        p.markExpired();
        p.markSucceeded("txn-" + System.nanoTime(), amountConfirmed);
        return paymentRepository.save(p);
    }

    private Payment newPaymentInStatus(Long userId, PaymentStatus status) {
        OffsetDateTime now = OffsetDateTime.now();
        Payment p = new Payment(userId, "ref-" + System.nanoTime(), new BigDecimal("500.00"), null, now, now.plusMinutes(30));
        if (status == PaymentStatus.PENDING || status == PaymentStatus.EXPIRED || status == PaymentStatus.FAILED
                || status == PaymentStatus.SUCCEEDED) {
            p.markPending("psp-ref-" + System.nanoTime());
        }
        if (status == PaymentStatus.EXPIRED) {
            p.markExpired();
        }
        if (status == PaymentStatus.FAILED) {
            p.markFailed();
        }
        if (status == PaymentStatus.SUCCEEDED) {
            p.markSucceeded("txn-" + System.nanoTime(), new BigDecimal("500.00"));
        }
        return paymentRepository.save(p);
    }

    private List<java.util.Map<String, Object>> listLateSuccesses(Long adminId) throws Exception {
        String json = mockMvc.perform(get(URL).with(user(adminId, "ADMIN")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readValue(json, new TypeReference<>() {});
    }

    // ==================== Authorization ====================

    @Test
    @DisplayName("GET /late-successes without authentication -> 401")
    void unauthenticated_401() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /late-successes as a plain USER -> 403")
    void user_403() throws Exception {
        mockMvc.perform(get(URL).with(user(999301L, "USER"))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /late-successes as DIAGNOSTIC -> 403")
    void diagnostic_403() throws Exception {
        mockMvc.perform(get(URL).with(user(999302L, "DIAGNOSTIC"))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /late-successes as ADMIN -> 200")
    void admin_200() throws Exception {
        mockMvc.perform(get(URL).with(user(999303L, "ADMIN"))).andExpect(status().isOk());
    }

    // ==================== Filtering ====================

    @Test
    @DisplayName("a real late-success payment (EXPIRED -> SUCCEEDED) appears in the list")
    void lateSuccess_Included() throws Exception {
        Long userId = newOwningUser();
        Payment p = newLateSuccessPayment(userId, new BigDecimal("500.00"), new BigDecimal("500.00"));

        List<java.util.Map<String, Object>> items = listLateSuccesses(999304L);
        assertThat(items).anyMatch(i -> Long.valueOf(((Number) i.get("id")).longValue()).equals(p.getId()));
    }

    @Test
    @DisplayName("an ordinary (never-expired) SUCCEEDED payment does not appear")
    void ordinarySuccess_Excluded() throws Exception {
        Long userId = newOwningUser();
        Payment p = newPaymentInStatus(userId, PaymentStatus.SUCCEEDED);
        assertThat(p.isLateSuccessAfterExpiry()).isFalse();

        List<java.util.Map<String, Object>> items = listLateSuccesses(999305L);
        assertThat(items).noneMatch(i -> Long.valueOf(((Number) i.get("id")).longValue()).equals(p.getId()));
    }

    @Test
    @DisplayName("an EXPIRED payment with no provider success does not appear")
    void expiredWithoutSuccess_Excluded() throws Exception {
        Long userId = newOwningUser();
        Payment p = newPaymentInStatus(userId, PaymentStatus.EXPIRED);

        List<java.util.Map<String, Object>> items = listLateSuccesses(999306L);
        assertThat(items).noneMatch(i -> Long.valueOf(((Number) i.get("id")).longValue()).equals(p.getId()));
    }

    @Test
    @DisplayName("a FAILED payment does not appear")
    void failed_Excluded() throws Exception {
        Long userId = newOwningUser();
        Payment p = newPaymentInStatus(userId, PaymentStatus.FAILED);

        List<java.util.Map<String, Object>> items = listLateSuccesses(999307L);
        assertThat(items).noneMatch(i -> Long.valueOf(((Number) i.get("id")).longValue()).equals(p.getId()));
    }

    @Test
    @DisplayName("a non-terminal (PENDING, e.g. a not-yet-resolved mismatch candidate) payment does not appear")
    void nonTerminalPending_Excluded() throws Exception {
        Long userId = newOwningUser();
        Payment p = newPaymentInStatus(userId, PaymentStatus.PENDING);

        List<java.util.Map<String, Object>> items = listLateSuccesses(999308L);
        assertThat(items).noneMatch(i -> Long.valueOf(((Number) i.get("id")).longValue()).equals(p.getId()));
    }

    // ==================== Ordering ====================

    @Test
    @DisplayName("multiple late-success payments are ordered by updatedAt DESC (most recently succeeded first)")
    void ordering_NewestUpdatedAtFirst() throws Exception {
        Long userId = newOwningUser();
        Payment older = newLateSuccessPayment(userId, new BigDecimal("100.00"), new BigDecimal("100.00"));
        Thread.sleep(5); // ensure a strictly later updatedAt for the second payment
        Payment newer = newLateSuccessPayment(userId, new BigDecimal("200.00"), new BigDecimal("200.00"));

        List<java.util.Map<String, Object>> items = listLateSuccesses(999309L);
        int idxNewer = indexOfId(items, newer.getId());
        int idxOlder = indexOfId(items, older.getId());
        assertThat(idxNewer).as("more recently succeeded payment must appear first").isLessThan(idxOlder);
    }

    private static int indexOfId(List<java.util.Map<String, Object>> items, Long id) {
        for (int i = 0; i < items.size(); i++) {
            if (Long.valueOf(((Number) items.get(i).get("id")).longValue()).equals(id)) return i;
        }
        throw new AssertionError("payment " + id + " not found in list response");
    }

    // ==================== DTO field mapping ====================

    @Test
    @DisplayName("response contains all required fields, correctly mapped")
    void dto_FieldsMappedCorrectly() throws Exception {
        Long userId = newOwningUser();
        Payment p = newLateSuccessPayment(userId, new BigDecimal("750.00"), new BigDecimal("750.00"));

        List<java.util.Map<String, Object>> items = listLateSuccesses(999310L);
        java.util.Map<String, Object> row = items.stream()
                .filter(i -> Long.valueOf(((Number) i.get("id")).longValue()).equals(p.getId()))
                .findFirst().orElseThrow();

        assertThat(((Number) row.get("userId")).longValue()).isEqualTo(userId);
        assertThat(new BigDecimal(row.get("amountRequested").toString())).isEqualByComparingTo("750.00");
        assertThat(row.get("currency")).isEqualTo("KGS");
        assertThat(new BigDecimal(row.get("amountConfirmed").toString())).isEqualByComparingTo("750.00");
        assertThat(row.get("providerTransactionId")).isNotNull();
        assertThat(row.get("status")).isEqualTo("SUCCEEDED");
        assertThat(row.get("createdAt")).isNotNull();
        assertThat(row.get("expiresAt")).isNotNull();
        assertThat(row.get("updatedAt")).isNotNull();
        assertThat(row.get("lateSuccessAfterExpiry")).isEqualTo(true);
    }

    // ==================== Read-only / financial isolation ====================

    @Test
    @DisplayName("GET causes zero mutation: Payment, Deposit, PaymentEvent, and ReconciliationCase are all unchanged")
    void isReadOnly_NoMutationAnywhere() throws Exception {
        Long userId = newOwningUser();
        Payment p = newLateSuccessPayment(userId, new BigDecimal("300.00"), new BigDecimal("300.00"));

        Payment paymentBefore = paymentRepository.findById(p.getId()).orElseThrow();
        var depositBefore = depositRepository.findByUserId(userId).orElseThrow();
        int eventCountBefore = paymentEventRepository.findByPaymentId(p.getId()).size();
        long caseCountBefore = reconciliationCaseRepository.findOpen().size();
        List<DepositTransaction> txBefore = depositRepository.findTransactionsByUserId(userId);

        listLateSuccesses(999311L);

        Payment paymentAfter = paymentRepository.findById(p.getId()).orElseThrow();
        var depositAfter = depositRepository.findByUserId(userId).orElseThrow();
        int eventCountAfter = paymentEventRepository.findByPaymentId(p.getId()).size();
        long caseCountAfter = reconciliationCaseRepository.findOpen().size();
        List<DepositTransaction> txAfter = depositRepository.findTransactionsByUserId(userId);

        assertThat(paymentAfter.getStatus()).isEqualTo(paymentBefore.getStatus());
        assertThat(paymentAfter.getAmountConfirmed()).isEqualTo(paymentBefore.getAmountConfirmed());
        assertThat(paymentAfter.getUpdatedAt()).isEqualTo(paymentBefore.getUpdatedAt());
        assertThat(depositAfter.getBalance()).isEqualTo(depositBefore.getBalance());
        assertThat(eventCountAfter).as("GET must never append a PaymentEvent").isEqualTo(eventCountBefore);
        assertThat(caseCountAfter).as("GET must never create a ReconciliationCase").isEqualTo(caseCountBefore);
        assertThat(txAfter).as("GET must never create a DepositTransaction").hasSameSizeAs(txBefore);
    }

    // ==================== Adversarial: empty result ====================

    @Test
    @DisplayName("no late-success payments exist for this filter -> 200 with a (possibly empty) list, never 404")
    void emptyOrNonEmptyResult_Always200() throws Exception {
        mockMvc.perform(get(URL).with(user(999312L, "ADMIN"))).andExpect(status().isOk());
    }
}
