package com.example.autoauction.deposit.application;

import com.example.autoauction.auth.domain.TestUserPrincipal;
import com.example.autoauction.deposit.domain.Deposit;
import com.example.autoauction.deposit.domain.DepositTransaction;
import com.example.autoauction.deposit.domain.WithdrawalRequest;
import com.example.autoauction.deposit.domain.WithdrawalRequestStatus;
import com.example.autoauction.deposit.domain.port.DepositRepository;
import com.example.autoauction.deposit.domain.port.WithdrawalRequestRepository;
import com.example.autoauction.identity.domain.DocumentType;
import com.example.autoauction.identity.domain.IdentityDocument;
import com.example.autoauction.identity.domain.IdentityVerification;
import com.example.autoauction.identity.domain.port.IdentityDocumentRepository;
import com.example.autoauction.identity.domain.port.IdentityVerificationRepository;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F6: withdrawal as a manual payout request - creation (KYC, amount, idempotency, the amount
 * becoming unavailable to everything else), the admin lifecycle PENDING -> PROCESSING -> PAID /
 * PENDING -> REJECTED with every illegal transition refused, and rollback of each step. After
 * every scenario the balance, the blocked amount, the ledger and the request are checked.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("F6: withdrawal requests (manual payout)")
class WithdrawalRequestIntegrationTest {

    private static final Long ADMIN_ID = 4242L;

    @Autowired private MockMvc mockMvc;
    @Autowired private DepositRepository depositRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private IdentityDocumentRepository identityDocumentRepository;
    @Autowired private IdentityVerificationRepository identityVerificationRepository;
    @Autowired private WithdrawalRequestService withdrawalRequestService;
    @SpyBean private DepositService depositService;
    @SpyBean private WithdrawalRequestRepository withdrawalRequestRepository;

    // ------------------------------------------------------------------ fixtures

    private static RequestPostProcessor as(Long id, String role) {
        return SecurityMockMvcRequestPostProcessors.user(TestUserPrincipal.createWithRoles(id, "u" + id, role));
    }

    private Long user(String balance, boolean verified) {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        String name = "wd" + System.nanoTime();
        Long id = userRepository.save(new User(null, name, name + "@test.com", "encoded", true, Set.of(role))).getId();
        depositService.createDeposit(id, new BigDecimal(balance));
        if (verified) {
            OffsetDateTime now = OffsetDateTime.now();
            Long doc = identityDocumentRepository.save(new IdentityDocument(
                    "identity-documents/wd" + System.nanoTime(), id, "image/jpeg", 1L, now)).getId();
            IdentityVerification iv = IdentityVerification.firstSubmission(id, "KG", DocumentType.NATIONAL_ID,
                    "WD" + System.nanoTime(), doc, doc, now);
            iv.approve(now);
            identityVerificationRepository.save(iv);
        }
        return id;
    }

    private ResultActions create(Long userId, String amount, String key) throws Exception {
        return mockMvc.perform(post("/api/v1/withdrawals").with(as(userId, "USER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":" + amount + ",\"idempotencyKey\":\"" + key + "\"}"));
    }

    private Long createOk(Long userId, String amount) throws Exception {
        String body = create(userId, amount, UUID.randomUUID().toString())
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private static final String PAYOUT_NOTE = "Перевод по номеру телефона из обращения #12, 15.10";

    /** A valid admin action as the UI sends it: reject on the status currently shown, paid with a note. */
    private ResultActions admin(Long requestId, String action) throws Exception {
        if (action.equals("reject")) {
            String seen = withdrawalRequestRepository.findById(requestId)
                    .map(r -> r.getStatus().name()).orElse("PENDING");
            return adminJson(requestId, "reject",
                    "{\"reason\":\"Неверные реквизиты\",\"payoutNotMade\":true,\"expectedStatus\":\"" + seen + "\"}");
        }
        if (action.equals("paid")) {
            return adminJson(requestId, "paid", "{\"payoutNote\":\"" + PAYOUT_NOTE + "\"}");
        }
        return mockMvc.perform(post("/api/v1/admin/withdrawals/" + requestId + "/" + action).with(as(ADMIN_ID, "ADMIN")));
    }

    private ResultActions adminJson(Long requestId, String action, String json) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/withdrawals/" + requestId + "/" + action).with(as(ADMIN_ID, "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private Long processingRequest(Long userId, String amount) throws Exception {
        Long id = createOk(userId, amount);
        admin(id, "processing").andExpect(status().isOk());
        return id;
    }

    private Deposit deposit(Long userId) {
        return depositRepository.findByUserId(userId).orElseThrow();
    }

    private void assertMoney(Long userId, String balance, String blocked) {
        Deposit d = deposit(userId);
        assertThat(d.getBalance()).as("balance").isEqualByComparingTo(balance);
        assertThat(d.getBlockedAmount()).as("blocked").isEqualByComparingTo(blocked);
    }

    private List<DepositTransaction> ledger(Long userId, DepositTransaction.TransactionType type) {
        return depositRepository.findTransactionsByUserId(userId).stream().filter(t -> t.getType() == type).toList();
    }

    private WithdrawalRequest request(Long id) {
        return withdrawalRequestRepository.findById(id).orElseThrow();
    }

    // ------------------------------------------------------------------ creation

    @Test
    @DisplayName("create: amount debited at once, one WITHDRAWAL ledger row linked to the request, PENDING")
    void create_debitsAndRecords() throws Exception {
        Long userId = user("1000", true);
        String body = create(userId, "300", "wd-create-" + System.nanoTime())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.amount").value(300))
                .andExpect(jsonPath("$.processedBy").doesNotExist())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Long id = ((Number) JsonPath.read(body, "$.id")).longValue();

        assertMoney(userId, "700", "0");
        List<DepositTransaction> debits = ledger(userId, DepositTransaction.TransactionType.WITHDRAWAL);
        assertThat(debits).hasSize(1);
        assertThat(debits.get(0).getAmount()).isEqualByComparingTo("300");
        assertThat(debits.get(0).getReference()).isEqualTo("WITHDRAWAL_REQUEST_" + id);
        assertThat(request(id).getStatus()).isEqualTo(WithdrawalRequestStatus.PENDING);
        assertThat(request(id).getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("retry after a lost response (same key + amount) returns the same request, no second debit")
    void retry_isIdempotent() throws Exception {
        Long userId = user("1000", true);
        String key = "wd-retry-" + System.nanoTime();
        String first = create(userId, "300", key).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String second = create(userId, "300.00", key).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat((Object) JsonPath.read(second, "$.id")).isEqualTo(JsonPath.read(first, "$.id"));
        assertMoney(userId, "700", "0");
        assertThat(ledger(userId, DepositTransaction.TransactionType.WITHDRAWAL)).hasSize(1);
        assertThat(withdrawalRequestService.listMine(userId)).hasSize(1);
    }

    @Test
    @DisplayName("duplicate key with another amount or another user is a 409, nothing is debited")
    void duplicateKey_otherAmountOrUser_conflict() throws Exception {
        Long a = user("1000", true);
        Long b = user("1000", true);
        String key = "wd-dup-" + System.nanoTime();
        create(a, "300", key).andExpect(status().isOk());

        create(a, "301", key).andExpect(status().isConflict());
        create(b, "300", key).andExpect(status().isConflict());

        assertMoney(a, "700", "0");
        assertMoney(b, "1000", "0");
        assertThat(ledger(b, DepositTransaction.TransactionType.WITHDRAWAL)).isEmpty();
        assertThat(withdrawalRequestService.listMine(b)).isEmpty();
    }

    @Test
    @DisplayName("a key already used by a pre-F6 withdrawal or another ledger row cannot become a request")
    void keyOfExistingLedgerRow_conflict() throws Exception {
        Long userId = user("1000", true);
        String legacyKey = "wd-legacy-" + System.nanoTime();
        depositService.withdraw(userId, new BigDecimal("100"), legacyKey);   // the pre-F6 immediate debit

        create(userId, "100", legacyKey).andExpect(status().isConflict());

        assertMoney(userId, "900", "0");
        assertThat(withdrawalRequestService.listMine(userId)).isEmpty();
    }

    @Test
    @DisplayName("keys the server reserves for its own ledger rows are refused")
    void reservedKeyPrefix_refused() throws Exception {
        Long userId = user("1000", true);
        create(userId, "100", "WITHDRAWAL_RETURN:1").andExpect(status().isBadRequest());
        create(userId, "100", "PAYMENT:TX-1").andExpect(status().isBadRequest());
        assertMoney(userId, "1000", "0");
    }

    @Test
    @DisplayName("negative, zero, sub-tyiyn amounts and an amount above the available balance are refused")
    void invalidAmounts_refused() throws Exception {
        Long userId = user("1000", true);
        create(userId, "-100", UUID.randomUUID().toString()).andExpect(status().isBadRequest());
        create(userId, "0", UUID.randomUUID().toString()).andExpect(status().isBadRequest());
        create(userId, "10.005", UUID.randomUUID().toString()).andExpect(status().isBadRequest());
        create(userId, "1000.01", UUID.randomUUID().toString()).andExpect(status().isConflict());

        assertMoney(userId, "1000", "0");
        assertThat(ledger(userId, DepositTransaction.TransactionType.WITHDRAWAL)).isEmpty();
        assertThat(withdrawalRequestService.listMine(userId)).as("the refused request rolled back with the debit").isEmpty();
    }

    @Test
    @DisplayName("unverified user: 403, nothing created or debited")
    void unverified_refused() throws Exception {
        Long userId = user("1000", false);
        create(userId, "100", UUID.randomUUID().toString()).andExpect(status().isForbidden());
        assertMoney(userId, "1000", "0");
        assertThat(withdrawalRequestService.listMine(userId)).isEmpty();
    }

    @Test
    @DisplayName("frozen money cannot be withdrawn, and a requested amount can no longer be frozen or charged")
    void requestedAmount_isUnavailableToFreezeAndCharge() throws Exception {
        Long userId = user("1000", true);
        depositService.freezeForAuction(userId, 990_001L, new BigDecimal("700"));
        create(userId, "400", UUID.randomUUID().toString()).andExpect(status().isConflict());
        assertMoney(userId, "1000", "700");

        createOk(userId, "300");   // exactly the available rest
        assertMoney(userId, "700", "700");

        assertThatThrownBy(() -> depositService.freezeForAuction(userId, 990_002L, new BigDecimal("1")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> depositService.chargeRediagnosticFee(userId, 77L, new BigDecimal("1")))
                .isInstanceOf(IllegalStateException.class);
        create(userId, "1", UUID.randomUUID().toString()).andExpect(status().isConflict());
        assertMoney(userId, "700", "700");
    }

    // ------------------------------------------------------------------ IDOR / access

    @Test
    @DisplayName("a user sees only own requests; admin routes are closed to users")
    void ownRequestsOnly_adminRoutesClosed() throws Exception {
        Long a = user("1000", true);
        Long b = user("1000", true);
        Long requestOfA = createOk(a, "100");

        mockMvc.perform(get("/api/v1/withdrawals/my").with(as(b, "USER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/v1/withdrawals/my").with(as(a, "USER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(requestOfA));

        mockMvc.perform(post("/api/v1/admin/withdrawals/" + requestOfA + "/reject").with(as(b, "USER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/withdrawals/" + requestOfA + "/processing").with(as(b, "USER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/withdrawals").with(as(b, "USER"))).andExpect(status().isForbidden());

        assertThat(request(requestOfA).getStatus()).isEqualTo(WithdrawalRequestStatus.PENDING);
        assertMoney(a, "900", "0");
    }

    @Test
    @DisplayName("the old immediate-withdraw route no longer exists")
    void oldRoute_gone() throws Exception {
        Long userId = user("1000", true);
        mockMvc.perform(post("/api/v1/deposits/withdraw").with(as(userId, "USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":100,\"idempotencyKey\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isIn(404, 405));
        assertMoney(userId, "1000", "0");
    }

    // ------------------------------------------------------------------ admin lifecycle

    @Test
    @DisplayName("PENDING -> PROCESSING -> PAID: audit trail recorded, no money moves, PAID is final")
    void processAndPay() throws Exception {
        Long userId = user("1000", true);
        Long id = createOk(userId, "300");

        admin(id, "processing").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.processingStartedBy").value(ADMIN_ID))
                .andExpect(jsonPath("$.processedAt").doesNotExist());
        assertMoney(userId, "700", "0");

        admin(id, "paid").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.processedBy").value(ADMIN_ID))
                .andExpect(jsonPath("$.processedAt").isNotEmpty())
                .andExpect(jsonPath("$.payoutNote").value(PAYOUT_NOTE))
                .andExpect(jsonPath("$.username").isNotEmpty());

        admin(id, "paid").andExpect(status().isConflict());          // PAID -> PAID
        admin(id, "reject").andExpect(status().isConflict());        // PAID -> REJECTED
        admin(id, "processing").andExpect(status().isConflict());    // process already paid

        WithdrawalRequest r = request(id);
        assertThat(r.getStatus()).isEqualTo(WithdrawalRequestStatus.PAID);
        assertThat(r.getProcessingStartedAt()).isNotNull();
        assertThat(r.getProcessedBy()).isEqualTo(ADMIN_ID);
        assertMoney(userId, "700", "0");
        assertThat(ledger(userId, DepositTransaction.TransactionType.WITHDRAWAL)).hasSize(1);
        assertThat(ledger(userId, DepositTransaction.TransactionType.WITHDRAWAL_RETURN)).isEmpty();
        // the owner sees the outcome, not which admin handled it
        mockMvc.perform(get("/api/v1/withdrawals/my").with(as(userId, "USER")))
                .andExpect(jsonPath("$[0].status").value("PAID"))
                .andExpect(jsonPath("$[0].processedAt").isNotEmpty())
                .andExpect(jsonPath("$[0].processedBy").doesNotExist())
                .andExpect(jsonPath("$[0].processingStartedBy").doesNotExist())
                .andExpect(jsonPath("$[0].payoutNote").doesNotExist());
        assertThat(request(id).getPayoutNote()).isEqualTo(PAYOUT_NOTE);
    }

    @Test
    @DisplayName("PENDING -> REJECTED: amount returned exactly once, request kept with reason; REJECTED is final")
    void reject_returnsOnce() throws Exception {
        Long userId = user("1000", true);
        Long id = createOk(userId, "300");

        admin(id, "reject").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectionReason").value("Неверные реквизиты"))
                .andExpect(jsonPath("$.processedBy").value(ADMIN_ID));
        assertMoney(userId, "1000", "0");

        admin(id, "reject").andExpect(status().isConflict());        // REJECTED -> REJECTED
        admin(id, "paid").andExpect(status().isConflict());          // REJECTED -> PAID
        admin(id, "processing").andExpect(status().isConflict());

        assertMoney(userId, "1000", "0");
        List<DepositTransaction> returns = ledger(userId, DepositTransaction.TransactionType.WITHDRAWAL_RETURN);
        assertThat(returns).hasSize(1);
        assertThat(returns.get(0).getAmount()).isEqualByComparingTo("300");
        assertThat(returns.get(0).getReference()).isEqualTo("WITHDRAWAL_REQUEST_" + id);
        assertThat(returns.get(0).getIdempotencyKey()).isEqualTo("WITHDRAWAL_RETURN:" + id);
        mockMvc.perform(get("/api/v1/withdrawals/my").with(as(userId, "USER")))
                .andExpect(jsonPath("$[0].status").value("REJECTED"))
                .andExpect(jsonPath("$[0].rejectionReason").value("Неверные реквизиты"));
    }

    @Test
    @DisplayName("PENDING cannot be paid directly")
    void illegalShortcuts_refused() throws Exception {
        Long userId = user("1000", true);
        Long pending = createOk(userId, "100");
        admin(pending, "paid").andExpect(status().isConflict());

        assertThat(request(pending).getStatus()).isEqualTo(WithdrawalRequestStatus.PENDING);
        assertThat(request(pending).getPayoutNote()).isNull();
        assertMoney(userId, "900", "0");
    }

    @Test
    @DisplayName("reject needs a reason (<= 1000 chars); unknown request is 404")
    void rejectValidation() throws Exception {
        Long userId = user("1000", true);
        Long id = createOk(userId, "100");
        adminJson(id, "reject", "{\"reason\":\"  \",\"payoutNotMade\":true,\"expectedStatus\":\"PENDING\"}")
                .andExpect(status().isBadRequest());
        adminJson(id, "reject", "{\"payoutNotMade\":true,\"expectedStatus\":\"PENDING\"}")
                .andExpect(status().isBadRequest());
        adminJson(id, "reject", "{\"reason\":\"" + "x".repeat(1001) + "\",\"payoutNotMade\":true,\"expectedStatus\":\"PENDING\"}")
                .andExpect(status().isBadRequest());
        admin(Long.MAX_VALUE, "processing").andExpect(status().isNotFound());

        assertThat(request(id).getStatus()).isEqualTo(WithdrawalRequestStatus.PENDING);
        assertMoney(userId, "900", "0");
    }

    @Test
    @DisplayName("admin list filters by status")
    void adminList_filtersByStatus() throws Exception {
        Long userId = user("1000", true);
        Long id = createOk(userId, "100");
        admin(id, "processing").andExpect(status().isOk());

        String body = mockMvc.perform(get("/api/v1/admin/withdrawals").param("status", "PROCESSING").with(as(ADMIN_ID, "ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<String> statuses = JsonPath.read(body, "$[*].status");
        List<Integer> ids = JsonPath.read(body, "$[*].id");
        assertThat(statuses).containsOnly("PROCESSING");
        assertThat(ids).contains(id.intValue());
    }

    // ------------------------------------------------------------------ rollback

    @Test
    @DisplayName("rollback during reservation: the debit fails -> no request, balance untouched")
    void rollback_duringReservation() throws Exception {
        Long userId = user("1000", true);
        doThrow(new IllegalStateException("simulated debit failure"))
                .when(depositService).withdraw(eq(userId), any(), anyString(), anyString(), anyString());

        create(userId, "300", UUID.randomUUID().toString()).andExpect(status().isConflict());

        assertMoney(userId, "1000", "0");
        assertThat(withdrawalRequestService.listMine(userId)).isEmpty();
        assertThat(ledger(userId, DepositTransaction.TransactionType.WITHDRAWAL)).isEmpty();
    }

    @Test
    @DisplayName("rollback during rejection: the return fails -> request stays PENDING, nothing returned")
    void rollback_duringRejection() throws Exception {
        Long userId = user("1000", true);
        Long id = createOk(userId, "300");
        doThrow(new IllegalStateException("simulated return failure"))
                .when(depositService).returnWithdrawal(eq(userId), eq(id), any());

        admin(id, "reject").andExpect(status().isConflict());

        WithdrawalRequest r = request(id);
        assertThat(r.getStatus()).isEqualTo(WithdrawalRequestStatus.PENDING);
        assertThat(r.getRejectionReason()).isNull();
        assertThat(r.getProcessedBy()).isNull();
        assertMoney(userId, "700", "0");
        assertThat(ledger(userId, DepositTransaction.TransactionType.WITHDRAWAL_RETURN)).isEmpty();

        // once the fault is gone, the rejection completes normally - exactly one return
        org.mockito.Mockito.reset(depositService);
        admin(id, "reject").andExpect(status().isOk());
        assertMoney(userId, "1000", "0");
        assertThat(ledger(userId, DepositTransaction.TransactionType.WITHDRAWAL_RETURN)).hasSize(1);
    }

    @Test
    @DisplayName("rollback during payout confirmation: saving PAID fails -> request stays PROCESSING")
    void rollback_duringPayoutConfirmation() throws Exception {
        Long userId = user("1000", true);
        Long id = createOk(userId, "300");
        admin(id, "processing").andExpect(status().isOk());
        doThrow(new IllegalStateException("simulated save failure"))
                .when(withdrawalRequestRepository).save(argThat(r -> r != null && r.getStatus() == WithdrawalRequestStatus.PAID));

        admin(id, "paid").andExpect(status().isConflict());

        WithdrawalRequest r = request(id);
        assertThat(r.getStatus()).isEqualTo(WithdrawalRequestStatus.PROCESSING);
        assertThat(r.getProcessedAt()).isNull();
        assertMoney(userId, "700", "0");
    }

    @Test
    @DisplayName("a second return for the same request is impossible even past the status guard")
    void secondReturn_blockedByLedger() throws Exception {
        Long userId = user("1000", true);
        Long id = createOk(userId, "300");
        admin(id, "reject").andExpect(status().isOk());

        assertThatThrownBy(() -> depositService.returnWithdrawal(userId, id, new BigDecimal("300")))
                .isInstanceOf(IllegalStateException.class);
        assertMoney(userId, "1000", "0");
        assertThat(ledger(userId, DepositTransaction.TransactionType.WITHDRAWAL_RETURN)).hasSize(1);
    }

    @Test
    @DisplayName("domain: a rejection needs a non-blank reason; transitions need an admin")
    void domainGuards() {
        WithdrawalRequest r = new WithdrawalRequest(1L, new BigDecimal("10"), "k", OffsetDateTime.now());
        assertThatThrownBy(() -> r.reject(1L, " ", true, OffsetDateTime.now())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> r.reject(1L, "причина", false, OffsetDateTime.now())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> r.markPaid(1L, "note", OffsetDateTime.now())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> r.startProcessing(null, OffsetDateTime.now())).isInstanceOf(NullPointerException.class);
        assertThat(r.getStatus()).isEqualTo(WithdrawalRequestStatus.PENDING);
        assertThatThrownBy(() -> new WithdrawalRequest(1L, new BigDecimal("10.001"), "k", OffsetDateTime.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WithdrawalRequest(1L, BigDecimal.ZERO, "k", OffsetDateTime.now()))
                .isInstanceOf(IllegalArgumentException.class);

        // a terminal request answers "wrong status" before anything else is looked at
        WithdrawalRequest paid = new WithdrawalRequest(1L, new BigDecimal("10"), "k", OffsetDateTime.now());
        paid.startProcessing(1L, OffsetDateTime.now());
        paid.markPaid(1L, "  наличными в офисе  ", OffsetDateTime.now());
        assertThat(paid.getPayoutNote()).isEqualTo("наличными в офисе");
        assertThatThrownBy(() -> paid.reject(1L, "", false, OffsetDateTime.now())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> paid.markPaid(1L, "again", OffsetDateTime.now())).isInstanceOf(IllegalStateException.class);
    }

    // ------------------------------------------------------------------ F6.1: PROCESSING -> REJECTED, payout note

    @Test
    @DisplayName("F6.1: PROCESSING -> REJECTED with confirmation returns once via WITHDRAWAL_RETURN:<id>, keeps processing audit")
    void processingToRejected() throws Exception {
        Long userId = user("1000", true);
        Long id = processingRequest(userId, "300");
        WithdrawalRequest before = request(id);

        adminJson(id, "reject", "{\"reason\":\" Реквизиты недействительны \",\"payoutNotMade\":true,\"expectedStatus\":\"PROCESSING\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectionReason").value("Реквизиты недействительны"))
                .andExpect(jsonPath("$.processingStartedBy").value(ADMIN_ID))
                .andExpect(jsonPath("$.processedBy").value(ADMIN_ID))
                .andExpect(jsonPath("$.payoutNote").doesNotExist());

        WithdrawalRequest r = request(id);
        assertThat(r.getStatus()).isEqualTo(WithdrawalRequestStatus.REJECTED);
        assertThat(r.getProcessingStartedAt()).isEqualTo(before.getProcessingStartedAt());
        assertThat(r.getProcessingStartedBy()).isEqualTo(before.getProcessingStartedBy());
        assertThat(r.getProcessedAt()).isNotNull();
        assertMoney(userId, "1000", "0");
        List<DepositTransaction> returns = ledger(userId, DepositTransaction.TransactionType.WITHDRAWAL_RETURN);
        assertThat(returns).hasSize(1);
        assertThat(returns.get(0).getIdempotencyKey()).isEqualTo("WITHDRAWAL_RETURN:" + id);
        assertThat(returns.get(0).getAmount()).isEqualByComparingTo("300");

        // retry of the same rejection: refused, no second return
        admin(id, "reject").andExpect(status().isConflict());
        admin(id, "paid").andExpect(status().isConflict());   // REJECTED -> PAID
        assertMoney(userId, "1000", "0");
        assertThat(ledger(userId, DepositTransaction.TransactionType.WITHDRAWAL_RETURN)).hasSize(1);
    }

    @Test
    @DisplayName("F6.1: rejecting without the payout-not-made confirmation is refused, from PENDING and from PROCESSING")
    void rejectWithoutConfirmation_refused() throws Exception {
        Long userId = user("1000", true);
        Long pending = createOk(userId, "100");
        Long processing = processingRequest(userId, "100");

        for (Long id : List.of(pending, processing)) {
            String seen = request(id).getStatus().name();
            adminJson(id, "reject", "{\"reason\":\"x\",\"payoutNotMade\":false,\"expectedStatus\":\"" + seen + "\"}")
                    .andExpect(status().isBadRequest());
            adminJson(id, "reject", "{\"reason\":\"x\",\"expectedStatus\":\"" + seen + "\"}")
                    .andExpect(status().isBadRequest());
        }
        // the service refuses it as well, independent of the HTTP layer
        assertThatThrownBy(() -> withdrawalRequestService.reject(processing, ADMIN_ID, "x", false, WithdrawalRequestStatus.PROCESSING))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(request(pending).getStatus()).isEqualTo(WithdrawalRequestStatus.PENDING);
        assertThat(request(processing).getStatus()).isEqualTo(WithdrawalRequestStatus.PROCESSING);
        assertMoney(userId, "800", "0");
        assertThat(ledger(userId, DepositTransaction.TransactionType.WITHDRAWAL_RETURN)).isEmpty();
    }

    @Test
    @DisplayName("F6.1: a stale expectedStatus (or none) is refused - no rejection on an outdated screen")
    void staleExpectedStatus_refused() throws Exception {
        Long userId = user("1000", true);
        Long processing = processingRequest(userId, "100");
        Long pending = createOk(userId, "100");

        // the admin still sees PENDING, but someone took the request into work meanwhile
        adminJson(processing, "reject", "{\"reason\":\"x\",\"payoutNotMade\":true,\"expectedStatus\":\"PENDING\"}")
                .andExpect(status().isConflict());
        adminJson(pending, "reject", "{\"reason\":\"x\",\"payoutNotMade\":true,\"expectedStatus\":\"PROCESSING\"}")
                .andExpect(status().isConflict());
        adminJson(pending, "reject", "{\"reason\":\"x\",\"payoutNotMade\":true}")
                .andExpect(status().isBadRequest());
        assertThatThrownBy(() -> withdrawalRequestService.reject(pending, ADMIN_ID, "x", true, null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(request(processing).getStatus()).isEqualTo(WithdrawalRequestStatus.PROCESSING);
        assertThat(request(pending).getStatus()).isEqualTo(WithdrawalRequestStatus.PENDING);
        assertMoney(userId, "800", "0");
        assertThat(ledger(userId, DepositTransaction.TransactionType.WITHDRAWAL_RETURN)).isEmpty();
    }

    @Test
    @DisplayName("F6.1: PAID needs a payout note (non-blank, <= 1000 chars); the note moves no money")
    void paidNeedsNote_andMovesNoMoney() throws Exception {
        Long userId = user("1000", true);
        Long id = processingRequest(userId, "300");
        int ledgerRowsBefore = depositRepository.findTransactionsByUserId(userId).size();

        adminJson(id, "paid", "{\"payoutNote\":\"   \"}").andExpect(status().isBadRequest());
        adminJson(id, "paid", "{}").andExpect(status().isBadRequest());
        adminJson(id, "paid", "{\"payoutNote\":\"" + "n".repeat(1001) + "\"}").andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/admin/withdrawals/" + id + "/paid").with(as(ADMIN_ID, "ADMIN")))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isGreaterThanOrEqualTo(400));
        assertThatThrownBy(() -> withdrawalRequestService.markPaid(id, ADMIN_ID, " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(request(id).getStatus()).isEqualTo(WithdrawalRequestStatus.PROCESSING);

        adminJson(id, "paid", "{\"payoutNote\":\"  " + PAYOUT_NOTE + "  \"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.payoutNote").value(PAYOUT_NOTE));

        assertMoney(userId, "700", "0");
        assertThat(depositRepository.findTransactionsByUserId(userId)).as("PAID writes no ledger row").hasSize(ledgerRowsBefore);
        assertThat(ledger(userId, DepositTransaction.TransactionType.WITHDRAWAL)).hasSize(1);
        assertThat(ledger(userId, DepositTransaction.TransactionType.WITHDRAWAL_RETURN)).isEmpty();
    }

    @Test
    @DisplayName("O1: only the JSON literal true confirms - \"true\", 1, \"1\", null, false, \"maybe\", {} and [] are 400 without effect")
    void payoutNotMade_onlyJsonTrue() throws Exception {
        Long userId = user("1000", true);
        Long id = processingRequest(userId, "100");
        for (String value : List.of("\"true\"", "1", "\"1\"", "null", "false", "\"maybe\"", "0", "{}", "[]", "[true]")) {
            adminJson(id, "reject", "{\"reason\":\"x\",\"payoutNotMade\":" + value + ",\"expectedStatus\":\"PROCESSING\"}")
                    .andExpect(status().isBadRequest());
            WithdrawalRequest r = request(id);
            assertThat(r.getStatus()).as(value).isEqualTo(WithdrawalRequestStatus.PROCESSING);
            assertThat(r.getRejectionReason()).as(value).isNull();
        }
        assertMoney(userId, "900", "0");
        assertThat(ledger(userId, DepositTransaction.TransactionType.WITHDRAWAL_RETURN)).isEmpty();

        adminJson(id, "reject", "{\"reason\":\"x\",\"payoutNotMade\":true,\"expectedStatus\":\"PROCESSING\"}")
                .andExpect(status().isOk());
        assertMoney(userId, "1000", "0");
        assertThat(ledger(userId, DepositTransaction.TransactionType.WITHDRAWAL_RETURN)).hasSize(1);
    }

    @Test
    @DisplayName("O2: a visually empty payout note (spaces, TAB, LF, NBSP, Unicode/zero-width spaces) is refused; real text is kept")
    void payoutNote_visuallyEmptyRefused() throws Exception {
        Long userId = user("1000", true);
        Long id = processingRequest(userId, "100");
        int ledgerRows = depositRepository.findTransactionsByUserId(userId).size();
        for (String note : List.of("", "   ", "\\t", "\\n", "\\u00a0\\u00a0", " \\t\\u00a0\\n\\u2007\\u202f\\u3000 ",
                "\\u200b\\ufeff\\u2060")) {
            adminJson(id, "paid", "{\"payoutNote\":\"" + note + "\"}").andExpect(status().isBadRequest());
            assertThat(request(id).getStatus()).as(note).isEqualTo(WithdrawalRequestStatus.PROCESSING);
            assertThat(request(id).getPayoutNote()).as(note).isNull();
        }
        assertThatThrownBy(() -> withdrawalRequestService.markPaid(id, ADMIN_ID, "\u00a0\u2007\u200b"))
                .isInstanceOf(IllegalArgumentException.class);

        adminJson(id, "paid", "{\"payoutNote\":\"\\u00a0Наличными\\u00a0в офисе\"}").andExpect(status().isOk());
        assertThat(request(id).getStatus()).isEqualTo(WithdrawalRequestStatus.PAID);
        assertThat(request(id).getPayoutNote()).isEqualTo("\u00a0Наличными\u00a0в офисе");
        assertMoney(userId, "900", "0");
        assertThat(depositRepository.findTransactionsByUserId(userId)).hasSize(ledgerRows);
    }

    @Test
    @DisplayName("F6.1: malformed reject bodies are refused without any effect")
    void malformedRejectBody_refused() throws Exception {
        Long userId = user("1000", true);
        Long id = processingRequest(userId, "100");

        adminJson(id, "reject", "{\"reason\":\"x\",\"payoutNotMade\":\"maybe\",\"expectedStatus\":\"PROCESSING\"}")
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isGreaterThanOrEqualTo(400));
        adminJson(id, "reject", "{\"reason\":\"x\",\"payoutNotMade\":true,\"expectedStatus\":\"BOGUS\"}")
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isGreaterThanOrEqualTo(400));
        adminJson(id, "reject", "not json").andExpect(r -> assertThat(r.getResponse().getStatus()).isGreaterThanOrEqualTo(400));

        assertThat(request(id).getStatus()).isEqualTo(WithdrawalRequestStatus.PROCESSING);
        assertMoney(userId, "900", "0");
        assertThat(ledger(userId, DepositTransaction.TransactionType.WITHDRAWAL_RETURN)).isEmpty();
    }

    @Test
    @DisplayName("F6.1: rollback during a PROCESSING rejection -> still PROCESSING, nothing returned; the retry returns once")
    void rollback_duringProcessingRejection() throws Exception {
        Long userId = user("1000", true);
        Long id = processingRequest(userId, "300");
        doThrow(new IllegalStateException("simulated return failure"))
                .when(depositService).returnWithdrawal(eq(userId), eq(id), any());

        admin(id, "reject").andExpect(status().isConflict());

        WithdrawalRequest r = request(id);
        assertThat(r.getStatus()).isEqualTo(WithdrawalRequestStatus.PROCESSING);
        assertThat(r.getRejectionReason()).isNull();
        assertThat(r.getProcessedAt()).isNull();
        assertMoney(userId, "700", "0");
        assertThat(ledger(userId, DepositTransaction.TransactionType.WITHDRAWAL_RETURN)).isEmpty();

        org.mockito.Mockito.reset(depositService);
        admin(id, "reject").andExpect(status().isOk());
        admin(id, "reject").andExpect(status().isConflict());
        assertMoney(userId, "1000", "0");
        assertThat(ledger(userId, DepositTransaction.TransactionType.WITHDRAWAL_RETURN)).hasSize(1);
    }
}
