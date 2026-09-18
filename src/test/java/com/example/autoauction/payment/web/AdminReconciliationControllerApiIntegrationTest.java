package com.example.autoauction.payment.web;

import com.example.autoauction.auth.domain.TestUserPrincipal;
import com.example.autoauction.payment.domain.Payment;
import com.example.autoauction.payment.domain.PaymentEvent;
import com.example.autoauction.payment.domain.PaymentEventSource;
import com.example.autoauction.payment.domain.PaymentEventType;
import com.example.autoauction.payment.domain.ReconciliationCase;
import com.example.autoauction.payment.domain.ReconciliationCaseStatus;
import com.example.autoauction.payment.domain.ReconciliationCaseType;
import com.example.autoauction.payment.domain.port.PaymentEventRepository;
import com.example.autoauction.payment.domain.port.PaymentRepository;
import com.example.autoauction.payment.domain.port.ReconciliationCaseRepository;
import com.example.autoauction.payment.web.dto.AdminReconciliationCaseListItemResponse;
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
import java.util.Set;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 20 §15: HTTP-layer proof for {@code /api/v1/admin/reconciliation/**} - ADMIN-only access
 * (non-admin USER and DIAGNOSTIC both denied, unauthenticated denied), correct detail assembly,
 * RECONCILIATION-only evidence filtering in chronological order, the case-not-found vs.
 * broken-payment-reference distinction, and that a GET causes zero mutation. Mirrors {@code
 * AdminIdentityControllerApiIntegrationTest}'s own conventions exactly.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Reconciliation admin API - authorization boundary and detail assembly")
class AdminReconciliationControllerApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private ReconciliationCaseRepository reconciliationCaseRepository;
    @Autowired private PaymentEventRepository paymentEventRepository;
    @Autowired private ObjectMapper objectMapper;

    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-09-18T10:00:00Z");

    private static RequestPostProcessor user(Long userId, String... roles) {
        return SecurityMockMvcRequestPostProcessors.user(TestUserPrincipal.createWithRoles(userId, "u" + userId, roles));
    }

    private Long newOwningUser() {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        User u = new User(null, "recadmin" + System.nanoTime(), "recadmin" + System.nanoTime() + "@test.com",
                "encoded", true, Set.of(role));
        return userRepository.save(u).getId();
    }

    private Payment newPayment(Long userId) {
        Payment p = new Payment(userId, "merchant-ref-" + System.nanoTime(), new BigDecimal("500.00"),
                null, T0, T0.plusMinutes(30));
        return paymentRepository.save(p);
    }

    private ReconciliationCase newCase(Long paymentId, ReconciliationCaseType type) {
        return reconciliationCaseRepository.save(new ReconciliationCase(paymentId, type, T0));
    }

    private void appendEvent(Long paymentId, PaymentEventType type, PaymentEventSource source, String payload, OffsetDateTime at) {
        paymentEventRepository.append(new PaymentEvent(paymentId, type, source, payload, at));
    }

    // ==================== Authorization ====================

    @Test
    @DisplayName("GET /cases/{id} without authentication -> 401")
    void unauthenticated_Denied() throws Exception {
        mockMvc.perform(get("/api/v1/admin/reconciliation/cases/1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /cases/{id} as a plain USER -> 403")
    void user_Denied() throws Exception {
        Long userId = newOwningUser();
        Payment p = newPayment(userId);
        ReconciliationCase c = newCase(p.getId(), ReconciliationCaseType.MISMATCH);

        mockMvc.perform(get("/api/v1/admin/reconciliation/cases/{id}", c.getId()).with(user(999001L, "USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /cases/{id} as DIAGNOSTIC -> 403 (not an admin role)")
    void diagnostic_Denied() throws Exception {
        Long userId = newOwningUser();
        Payment p = newPayment(userId);
        ReconciliationCase c = newCase(p.getId(), ReconciliationCaseType.MISMATCH);

        mockMvc.perform(get("/api/v1/admin/reconciliation/cases/{id}", c.getId()).with(user(999002L, "DIAGNOSTIC")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /cases/{id} as ADMIN -> 200")
    void admin_Allowed() throws Exception {
        Long userId = newOwningUser();
        Payment p = newPayment(userId);
        ReconciliationCase c = newCase(p.getId(), ReconciliationCaseType.MISMATCH);

        mockMvc.perform(get("/api/v1/admin/reconciliation/cases/{id}", c.getId()).with(user(999003L, "ADMIN")))
                .andExpect(status().isOk());
    }

    // ==================== Detail assembly ====================

    @Test
    @DisplayName("detail includes case + payment + chronologically-ordered RECONCILIATION-only evidence")
    void detail_AssemblesCorrectly_FiltersAndOrdersEvidence() throws Exception {
        Long userId = newOwningUser();
        Payment p = newPayment(userId);
        ReconciliationCase c = newCase(p.getId(), ReconciliationCaseType.MISMATCH);

        // A non-RECONCILIATION event must NOT appear in the evidence list.
        appendEvent(p.getId(), PaymentEventType.CREATED, PaymentEventSource.CLIENT, null, T0.minusMinutes(5));
        // Two RECONCILIATION events, inserted out of chronological order, to prove the response
        // still reflects occurredAt ASC, id ASC - not insertion order.
        appendEvent(p.getId(), PaymentEventType.RECONCILIATION, PaymentEventSource.PROVIDER_POLL,
                "requestedAmount=500.00;providerConfirmedAmount=480.00", T0.plusMinutes(10));
        appendEvent(p.getId(), PaymentEventType.RECONCILIATION, PaymentEventSource.PROVIDER_POLL,
                "requestedAmount=500.00;providerConfirmedAmount=470.00", T0.plusMinutes(2));

        mockMvc.perform(get("/api/v1/admin/reconciliation/cases/{id}", c.getId()).with(user(999004L, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reconciliationCase.id").value(c.getId()))
                .andExpect(jsonPath("$.reconciliationCase.paymentId").value(p.getId()))
                .andExpect(jsonPath("$.reconciliationCase.type").value("MISMATCH"))
                .andExpect(jsonPath("$.reconciliationCase.status").value("OPEN"))
                .andExpect(jsonPath("$.payment.id").value(p.getId()))
                .andExpect(jsonPath("$.payment.amountRequested").value(500.00))
                .andExpect(jsonPath("$.payment.currency").value("KGS"))
                .andExpect(jsonPath("$.evidence.length()").value(2))
                .andExpect(jsonPath("$.evidence[0].rawPayload").value("requestedAmount=500.00;providerConfirmedAmount=470.00"))
                .andExpect(jsonPath("$.evidence[1].rawPayload").value("requestedAmount=500.00;providerConfirmedAmount=480.00"));
    }

    @Test
    @DisplayName("case with zero RECONCILIATION events -> empty evidence list, not an error")
    void detail_ZeroEvidence_ReturnsEmptyList() throws Exception {
        Long userId = newOwningUser();
        Payment p = newPayment(userId);
        ReconciliationCase c = newCase(p.getId(), ReconciliationCaseType.STUCK);

        mockMvc.perform(get("/api/v1/admin/reconciliation/cases/{id}", c.getId()).with(user(999005L, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evidence.length()").value(0));
    }

    @Test
    @DisplayName("unknown case id -> 404")
    void detail_UnknownCase_404() throws Exception {
        mockMvc.perform(get("/api/v1/admin/reconciliation/cases/{id}", 999_999_999L).with(user(999006L, "ADMIN")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("case referencing a non-existent payment id -> explicit 409, not a silently incomplete response")
    void detail_BrokenPaymentReference_ExplicitFailure() throws Exception {
        // A case whose paymentId does not resolve to any Payment row - a data-integrity break,
        // deliberately distinguished from the ordinary case-not-found 404 above.
        ReconciliationCase orphan = reconciliationCaseRepository.save(
                new ReconciliationCase(999_888_777L, ReconciliationCaseType.MISMATCH, T0));

        mockMvc.perform(get("/api/v1/admin/reconciliation/cases/{id}", orphan.getId()).with(user(999008L, "ADMIN")))
                .andExpect(status().isConflict());
    }

    // ==================== Read-only guarantee ====================

    @Test
    @DisplayName("GET causes zero mutation: case status/version and payment-event count unchanged")
    void detail_IsReadOnly_NoMutation() throws Exception {
        Long userId = newOwningUser();
        Payment p = newPayment(userId);
        ReconciliationCase c = newCase(p.getId(), ReconciliationCaseType.MISMATCH);
        appendEvent(p.getId(), PaymentEventType.RECONCILIATION, PaymentEventSource.PROVIDER_POLL, "x", T0);

        ReconciliationCase before = reconciliationCaseRepository.findById(c.getId()).orElseThrow();
        int eventCountBefore = paymentEventRepository.findByPaymentId(p.getId()).size();

        mockMvc.perform(get("/api/v1/admin/reconciliation/cases/{id}", c.getId()).with(user(999007L, "ADMIN")))
                .andExpect(status().isOk());

        ReconciliationCase after = reconciliationCaseRepository.findById(c.getId()).orElseThrow();
        int eventCountAfter = paymentEventRepository.findByPaymentId(p.getId()).size();

        assertThat(after.getStatus()).isEqualTo(ReconciliationCaseStatus.OPEN);
        assertThat(after.getStatus()).isEqualTo(before.getStatus());
        assertThat(after.getVersion()).isEqualTo(before.getVersion());
        assertThat(after.getResolvedByAdminId()).isNull();
        assertThat(eventCountAfter).isEqualTo(eventCountBefore);
    }

    // ==================== List: helpers ====================

    /**
     * Runs the list endpoint and deserializes into the real production DTO type - not a
     * test-local re-declaration - so these tests exercise the actual wire contract. The test
     * class shares one H2 database across all methods (no {@code @Transactional} rollback, no
     * fixed execution order), so every list assertion below finds ITS OWN rows by id within
     * whatever else may be present, rather than assuming an exact total size or absolute index.
     */
    private List<AdminReconciliationCaseListItemResponse> listCases(Long callerId, String status, String type) throws Exception {
        String json = mockMvc.perform(buildListRequest(callerId, status, type))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readValue(json, new TypeReference<>() {});
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder buildListRequest(
            Long callerId, String status, String type) {
        StringBuilder url = new StringBuilder("/api/v1/admin/reconciliation/cases");
        String sep = "?";
        if (status != null) {
            url.append(sep).append("status=").append(status);
            sep = "&";
        }
        if (type != null) {
            url.append(sep).append("type=").append(type);
        }
        return get(url.toString()).with(user(callerId, "ADMIN"));
    }

    private static AdminReconciliationCaseListItemResponse findById(
            List<AdminReconciliationCaseListItemResponse> items, Long id) {
        return items.stream().filter(i -> i.id().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("case " + id + " not found in list response: " + items));
    }

    // ==================== List: authorization ====================

    @Test
    @DisplayName("GET /cases without authentication -> 401")
    void list_Unauthenticated_401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/reconciliation/cases")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /cases as a plain USER -> 403")
    void list_User_403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/reconciliation/cases").with(user(999101L, "USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /cases as DIAGNOSTIC -> 403")
    void list_Diagnostic_403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/reconciliation/cases").with(user(999102L, "DIAGNOSTIC")))
                .andExpect(status().isForbidden());
    }

    // ==================== List: retrieval, ordering, enrichment, evidence boundary ====================

    @Test
    @DisplayName("GET /cases as ADMIN, no filters: contains a known case, enriched with Payment amount/currency, no evidence field on the wire")
    void list_NoFilters_ContainsKnownCase_EnrichedWithPayment_NoEvidenceField() throws Exception {
        Long userId = newOwningUser();
        Payment p = newPayment(userId);
        ReconciliationCase c = newCase(p.getId(), ReconciliationCaseType.MISMATCH);

        String rawJson = mockMvc.perform(buildListRequest(999103L, null, null))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(rawJson).as("list DTO must never carry evidence/PaymentEvent data on the wire")
                .doesNotContain("\"evidence\"").doesNotContain("rawPayload");

        List<AdminReconciliationCaseListItemResponse> items = objectMapper.readValue(rawJson, new TypeReference<>() {});
        AdminReconciliationCaseListItemResponse row = findById(items, c.getId());
        assertThat(row.paymentId()).isEqualTo(p.getId());
        assertThat(row.paymentExists()).isTrue();
        assertThat(row.requestedAmount()).isEqualByComparingTo("500.00");
        assertThat(row.requestedCurrency()).isEqualTo("KGS");
        assertThat(row.type()).isEqualTo("MISMATCH");
        assertThat(row.status()).isEqualTo("OPEN");
        assertThat(row.resolvedAt()).as("an OPEN case must have null resolution fields").isNull();
        assertThat(row.resolvedByAdminId()).isNull();
    }

    @Test
    @DisplayName("newer openedAt sorts before older openedAt (openedAt DESC)")
    void list_Ordering_NewestOpenedAtFirst() throws Exception {
        Long userId = newOwningUser();
        Payment pOlder = newPayment(userId);
        Payment pNewer = newPayment(userId);
        ReconciliationCase older = reconciliationCaseRepository.save(
                new ReconciliationCase(pOlder.getId(), ReconciliationCaseType.MISMATCH, T0.plusYears(10)));
        ReconciliationCase newer = reconciliationCaseRepository.save(
                new ReconciliationCase(pNewer.getId(), ReconciliationCaseType.MISMATCH, T0.plusYears(11)));

        List<AdminReconciliationCaseListItemResponse> items = listCases(999104L, null, null);
        int idxNewer = indexOfId(items, newer.getId());
        int idxOlder = indexOfId(items, older.getId());
        assertThat(idxNewer).as("newer openedAt must appear before older openedAt").isLessThan(idxOlder);
    }

    @Test
    @DisplayName("identical openedAt: higher id sorts first (deterministic tiebreak, id DESC)")
    void list_Ordering_IdenticalOpenedAt_TieBreaksByIdDesc() throws Exception {
        Long userId = newOwningUser();
        OffsetDateTime tied = T0.plusYears(20);
        Payment p1 = newPayment(userId);
        Payment p2 = newPayment(userId);
        ReconciliationCase first = reconciliationCaseRepository.save(new ReconciliationCase(p1.getId(), ReconciliationCaseType.MISMATCH, tied));
        ReconciliationCase second = reconciliationCaseRepository.save(new ReconciliationCase(p2.getId(), ReconciliationCaseType.MISMATCH, tied));
        // second.getId() > first.getId() (sequential identity generation) - second must sort first.

        List<AdminReconciliationCaseListItemResponse> items = listCases(999105L, null, null);
        int idxSecond = indexOfId(items, second.getId());
        int idxFirst = indexOfId(items, first.getId());
        assertThat(idxSecond).as("higher id must sort before lower id when openedAt ties").isLessThan(idxFirst);
    }

    private static int indexOfId(List<AdminReconciliationCaseListItemResponse> items, Long id) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id().equals(id)) return i;
        }
        throw new AssertionError("case " + id + " not found in list response");
    }

    @Test
    @DisplayName("status+type filtering: AND semantics, resolved case shows resolution fields, non-matching combination is empty")
    void list_StatusAndTypeFiltering_AndSemantics_ResolutionFieldsWhenResolved() throws Exception {
        Long userId = newOwningUser();
        Payment pOpen = newPayment(userId);
        Payment pResolved = newPayment(userId);
        // DUPLICATE is not used by any other test in this class - safe, unambiguous marker type.
        ReconciliationCase openCase = newCase(pOpen.getId(), ReconciliationCaseType.LATE_AFTER_EXPIRY);
        ReconciliationCase resolvedCase = reconciliationCaseRepository.save(
                new ReconciliationCase(pResolved.getId(), ReconciliationCaseType.DUPLICATE, T0));
        resolvedCase.resolve(555L, "handled out of band", T0.plusHours(1));
        reconciliationCaseRepository.save(resolvedCase);

        // type filter alone -> exactly the matching type's case(s), open one has null resolution fields.
        List<AdminReconciliationCaseListItemResponse> byType = listCases(999106L, null, "LATE_AFTER_EXPIRY");
        AdminReconciliationCaseListItemResponse openRow = findById(byType, openCase.getId());
        assertThat(openRow.status()).isEqualTo("OPEN");
        assertThat(openRow.resolvedAt()).isNull();
        assertThat(openRow.resolvedByAdminId()).isNull();

        // status filter alone -> the RESOLVED case is present with resolution fields populated.
        List<AdminReconciliationCaseListItemResponse> byStatus = listCases(999106L, "RESOLVED", null);
        AdminReconciliationCaseListItemResponse resolvedRow = findById(byStatus, resolvedCase.getId());
        assertThat(resolvedRow.status()).isEqualTo("RESOLVED");
        assertThat(resolvedRow.resolvedByAdminId()).isEqualTo(555L);
        assertThat(resolvedRow.resolvedAt()).isNotNull();

        // AND semantics, positive: matching combination returns the case.
        List<AdminReconciliationCaseListItemResponse> matching = listCases(999106L, "RESOLVED", "DUPLICATE");
        assertThat(matching).anyMatch(i -> i.id().equals(resolvedCase.getId()));

        // AND semantics, negative: a combination that does NOT match (this DUPLICATE case is
        // RESOLVED, not OPEN) must exclude it - proving AND, not OR.
        List<AdminReconciliationCaseListItemResponse> nonMatching = listCases(999106L, "OPEN", "DUPLICATE");
        assertThat(nonMatching).noneMatch(i -> i.id().equals(resolvedCase.getId()));
    }

    // ==================== List: broken Payment reference ====================

    @Test
    @DisplayName("broken Payment reference: row remains with paymentExists=false and null amount/currency; other rows unaffected")
    void list_BrokenPaymentReference_RowRemains_OthersUnaffected() throws Exception {
        Long userId = newOwningUser();
        Payment validPayment = newPayment(userId);
        ReconciliationCase validCase = newCase(validPayment.getId(), ReconciliationCaseType.MISMATCH);
        ReconciliationCase orphanA = reconciliationCaseRepository.save(
                new ReconciliationCase(999_777_001L, ReconciliationCaseType.STUCK, T0));
        ReconciliationCase orphanB = reconciliationCaseRepository.save(
                new ReconciliationCase(999_777_002L, ReconciliationCaseType.STUCK, T0));

        List<AdminReconciliationCaseListItemResponse> items = listCases(999107L, null, null);

        AdminReconciliationCaseListItemResponse validRow = findById(items, validCase.getId());
        assertThat(validRow.paymentExists()).isTrue();
        assertThat(validRow.requestedAmount()).isNotNull();

        AdminReconciliationCaseListItemResponse orphanRowA = findById(items, orphanA.getId());
        assertThat(orphanRowA.paymentExists()).isFalse();
        assertThat(orphanRowA.requestedAmount()).isNull();
        assertThat(orphanRowA.requestedCurrency()).isNull();
        assertThat(orphanRowA.paymentId()).isEqualTo(999_777_001L);

        AdminReconciliationCaseListItemResponse orphanRowB = findById(items, orphanB.getId());
        assertThat(orphanRowB.paymentExists()).isFalse();
    }

    // ==================== List: read-only guarantee ====================

    @Test
    @DisplayName("GET /cases causes zero mutation: case fields and payment-event count unchanged")
    void list_IsReadOnly_NoMutation() throws Exception {
        Long userId = newOwningUser();
        Payment p = newPayment(userId);
        ReconciliationCase c = newCase(p.getId(), ReconciliationCaseType.MISMATCH);
        appendEvent(p.getId(), PaymentEventType.RECONCILIATION, PaymentEventSource.PROVIDER_POLL, "x", T0);

        ReconciliationCase before = reconciliationCaseRepository.findById(c.getId()).orElseThrow();
        Payment paymentBefore = paymentRepository.findById(p.getId()).orElseThrow();
        int eventCountBefore = paymentEventRepository.findByPaymentId(p.getId()).size();

        listCases(999108L, null, null);

        ReconciliationCase after = reconciliationCaseRepository.findById(c.getId()).orElseThrow();
        Payment paymentAfter = paymentRepository.findById(p.getId()).orElseThrow();
        int eventCountAfter = paymentEventRepository.findByPaymentId(p.getId()).size();

        assertThat(after.getStatus()).isEqualTo(before.getStatus());
        assertThat(after.getVersion()).isEqualTo(before.getVersion());
        assertThat(after.getResolvedByAdminId()).isEqualTo(before.getResolvedByAdminId());
        assertThat(paymentAfter.getStatus()).isEqualTo(paymentBefore.getStatus());
        assertThat(paymentAfter.getAmountConfirmed()).isEqualTo(paymentBefore.getAmountConfirmed());
        assertThat(eventCountAfter).as("list must never append a PaymentEvent").isEqualTo(eventCountBefore);
    }
}
