// diagnostic/application/DiagnosticRequestTemplateBackfillIntegrationTest.java
package com.example.autoauction.diagnostic.application;

import com.example.autoauction.diagnostic.domain.DiagnosticRequest;
import com.example.autoauction.diagnostic.domain.InspectionAnswer;
import com.example.autoauction.diagnostic.domain.InspectionGroup;
import com.example.autoauction.diagnostic.domain.InspectionItem;
import com.example.autoauction.diagnostic.domain.InspectionTemplate;
import com.example.autoauction.diagnostic.domain.port.DiagnosticRequestRepository;
import com.example.autoauction.diagnostic.domain.port.InspectionAnswerRepository;
import com.example.autoauction.diagnostic.domain.port.InspectionTemplateRepository;
import com.example.autoauction.diagnostic.web.dto.TemplateBackfillResult;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MEDIA-3.1B: real-database (H2, test profile) proof of the historical
 * template-backfill classification rules from MEDIA-3.1B Phase 1/10/11 - one
 * scenario per test, exactly matching the required test matrix and adversarial
 * checklist. No SQL-level backfill is used; this exercises the real
 * DiagnosticRequestService.backfillHistoricalTemplateIds() application-level
 * implementation end to end.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("MEDIA-3.1B: исторический backfill DiagnosticRequest.templateId")
class DiagnosticRequestTemplateBackfillIntegrationTest {

    @Autowired
    private DiagnosticRequestService diagnosticRequestService;

    @Autowired
    private DiagnosticRequestRepository diagnosticRequestRepository;

    @Autowired
    private InspectionTemplateRepository templateRepository;

    @Autowired
    private InspectionAnswerRepository answerRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    private Long userId;

    private Long createUser() {
        Role userRole = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        User u = new User(null, "bf" + System.nanoTime(), "bf" + System.nanoTime() + "@test.com",
                "encoded", true, Set.of(userRole));
        return userRepository.save(u).getId();
    }

    private Long createRequest(DiagnosticRequest.DiagnosticRequestStatus status) {
        if (userId == null) userId = createUser();
        DiagnosticRequest request = new DiagnosticRequest();
        request.setUserId(userId);
        request.setBrand("BMW");
        request.setModel("X5");
        request.setYear(2022);
        request.setVin("B" + (System.nanoTime() % 100000000L));
        request.setDepositFrozen(new BigDecimal("10000"));
        request.setStatus(status);
        request.setCreatedAt(OffsetDateTime.now());
        request.setUpdatedAt(OffsetDateTime.now());
        return diagnosticRequestRepository.save(request).getId();
    }

    private Long createTemplate(String name) {
        InspectionTemplate t = new InspectionTemplate();
        t.setName(name);
        t.setActive(true);
        return templateRepository.saveTemplate(t).getId();
    }

    private Long createGroup(Long templateId) {
        InspectionGroup g = new InspectionGroup();
        g.setTemplateId(templateId);
        g.setName("Группа " + System.nanoTime());
        g.setSortOrder(1);
        return templateRepository.saveGroup(g).getId();
    }

    private Long createItem(Long groupId) {
        InspectionItem i = new InspectionItem();
        i.setGroupId(groupId);
        i.setTitle("Пункт " + System.nanoTime());
        i.setFieldType("TEXT");
        i.setSortOrder(1);
        return templateRepository.saveItem(i).getId();
    }

    private void createAnswer(Long requestId, Long itemId) {
        InspectionAnswer a = new InspectionAnswer();
        a.setDiagnosticRequestId(requestId);
        a.setInspectionItemId(itemId);
        a.setCreatedAt(OffsetDateTime.now());
        a.setUpdatedAt(OffsetDateTime.now());
        answerRepository.save(a);
    }

    // --- A: PENDING request - never touched ---
    @Test
    @DisplayName("A: PENDING заявка - templateId остаётся NULL, не рассматривается вообще")
    void pending_NeverBackfilled() {
        Long requestId = createRequest(DiagnosticRequest.DiagnosticRequestStatus.PENDING);

        diagnosticRequestService.backfillHistoricalTemplateIds();

        assertThat(diagnosticRequestRepository.findById(requestId).orElseThrow().getTemplateId()).isNull();
    }

    // --- B: deterministic single template ---
    @Test
    @DisplayName("B: все ответы принадлежат одному шаблону - backfill успешен")
    void deterministicSingleTemplate_Backfilled() {
        Long templateId = createTemplate("T-B-" + System.nanoTime());
        Long groupId = createGroup(templateId);
        Long item1 = createItem(groupId);
        Long item2 = createItem(groupId);
        Long requestId = createRequest(DiagnosticRequest.DiagnosticRequestStatus.COMPLETED);
        createAnswer(requestId, item1);
        createAnswer(requestId, item2);

        TemplateBackfillResult result = diagnosticRequestService.backfillHistoricalTemplateIds();

        assertThat(result.backfilled()).isGreaterThanOrEqualTo(1);
        assertThat(diagnosticRequestRepository.findById(requestId).orElseThrow().getTemplateId())
                .isEqualTo(templateId);
    }

    // --- C: ambiguous multi-template ---
    @Test
    @DisplayName("C: ответы из ДВУХ разных шаблонов - templateId остаётся NULL")
    void ambiguousMultiTemplate_StaysNull() {
        Long templateA = createTemplate("T-C-A-" + System.nanoTime());
        Long groupA = createGroup(templateA);
        Long itemA = createItem(groupA);
        Long templateB = createTemplate("T-C-B-" + System.nanoTime());
        Long groupB = createGroup(templateB);
        Long itemB = createItem(groupB);

        Long requestId = createRequest(DiagnosticRequest.DiagnosticRequestStatus.COMPLETED);
        createAnswer(requestId, itemA);
        createAnswer(requestId, itemB);

        TemplateBackfillResult result = diagnosticRequestService.backfillHistoricalTemplateIds();

        assertThat(result.unresolvedByReason().getOrDefault("MULTIPLE_TEMPLATES", 0)).isGreaterThanOrEqualTo(1);
        assertThat(diagnosticRequestRepository.findById(requestId).orElseThrow().getTemplateId()).isNull();
    }

    // --- D: assigned/non-pending with zero answers ---
    @Test
    @DisplayName("D: заявка не PENDING, но без ответов - templateId остаётся NULL")
    void nonPendingZeroAnswers_StaysNull() {
        Long requestId = createRequest(DiagnosticRequest.DiagnosticRequestStatus.IN_PROGRESS);

        TemplateBackfillResult result = diagnosticRequestService.backfillHistoricalTemplateIds();

        assertThat(result.unresolvedByReason().getOrDefault("NO_ANSWERS", 0)).isGreaterThanOrEqualTo(1);
        assertThat(diagnosticRequestRepository.findById(requestId).orElseThrow().getTemplateId()).isNull();
    }

    // --- E: broken item reference ---
    @Test
    @DisplayName("E: ответ ссылается на несуществующий InspectionItem - NULL и флаг BROKEN_ITEM_REFERENCE")
    void brokenItemReference_StaysNullAndFlagged() {
        Long requestId = createRequest(DiagnosticRequest.DiagnosticRequestStatus.COMPLETED);
        createAnswer(requestId, 999_999_001L); // никогда не существовавший InspectionItem

        TemplateBackfillResult result = diagnosticRequestService.backfillHistoricalTemplateIds();

        assertThat(result.unresolvedByReason().getOrDefault("BROKEN_ITEM_REFERENCE", 0)).isGreaterThanOrEqualTo(1);
        assertThat(diagnosticRequestRepository.findById(requestId).orElseThrow().getTemplateId()).isNull();
    }

    // --- F: broken group reference (item exists, points to a nonexistent group) ---
    @Test
    @DisplayName("F: InspectionItem ссылается на несуществующую InspectionGroup - NULL и флаг BROKEN_GROUP_REFERENCE")
    void brokenGroupReference_StaysNullAndFlagged() {
        InspectionItem orphanItem = new InspectionItem();
        orphanItem.setGroupId(999_999_002L); // никогда не существовавшая группа
        orphanItem.setTitle("Orphan " + System.nanoTime());
        orphanItem.setFieldType("TEXT");
        orphanItem.setSortOrder(1);
        Long orphanItemId = templateRepository.saveItem(orphanItem).getId();

        Long requestId = createRequest(DiagnosticRequest.DiagnosticRequestStatus.COMPLETED);
        createAnswer(requestId, orphanItemId);

        TemplateBackfillResult result = diagnosticRequestService.backfillHistoricalTemplateIds();

        assertThat(result.unresolvedByReason().getOrDefault("BROKEN_GROUP_REFERENCE", 0)).isGreaterThanOrEqualTo(1);
        assertThat(diagnosticRequestRepository.findById(requestId).orElseThrow().getTemplateId()).isNull();
    }

    // --- G: existing templateId is never overwritten ---
    @Test
    @DisplayName("G: у заявки уже есть templateId - backfill его не перезаписывает даже при " +
            "конфликтующих ответах")
    void existingTemplateId_NeverOverwritten() {
        Long boundTemplateId = createTemplate("T-G-bound-" + System.nanoTime());
        Long otherTemplateId = createTemplate("T-G-other-" + System.nanoTime());
        Long otherGroupId = createGroup(otherTemplateId);
        Long otherItemId = createItem(otherGroupId);

        Long requestId = createRequest(DiagnosticRequest.DiagnosticRequestStatus.APPROVED);
        // answers point to a DIFFERENT template than the one already bound
        createAnswer(requestId, otherItemId);
        boolean bound = diagnosticRequestRepository.bindTemplateIfUnset(requestId, boundTemplateId);
        assertThat(bound).isTrue();

        diagnosticRequestService.backfillHistoricalTemplateIds();

        assertThat(diagnosticRequestRepository.findById(requestId).orElseThrow().getTemplateId())
                .as("backfill must never overwrite an existing binding, even a seemingly-inconsistent one")
                .isEqualTo(boundTemplateId);
    }

    // --- H: idempotent rerun ---
    @Test
    @DisplayName("H: повторный запуск backfill даёт идентичный итоговый результат")
    void backfill_IsIdempotent() {
        Long templateId = createTemplate("T-H-" + System.nanoTime());
        Long groupId = createGroup(templateId);
        Long itemId = createItem(groupId);
        Long requestId = createRequest(DiagnosticRequest.DiagnosticRequestStatus.COMPLETED);
        createAnswer(requestId, itemId);

        TemplateBackfillResult first = diagnosticRequestService.backfillHistoricalTemplateIds();
        Long afterFirst = diagnosticRequestRepository.findById(requestId).orElseThrow().getTemplateId();
        TemplateBackfillResult second = diagnosticRequestService.backfillHistoricalTemplateIds();
        Long afterSecond = diagnosticRequestRepository.findById(requestId).orElseThrow().getTemplateId();

        assertThat(afterFirst).isEqualTo(templateId);
        assertThat(afterSecond).isEqualTo(templateId);
        // second run finds nothing new to do for this request (already bound, filtered out up front)
        assertThat(second.backfilled()).isEqualTo(0);
    }

    // --- Adversarial 1/3: template (de)activation does not change historical resolution ---
    @Test
    @DisplayName("Adversarial: деактивация шаблона ПОСЛЕ backfill не меняет уже привязанную заявку")
    void templateDeactivatedAfterBackfill_ResolutionUnchanged() {
        Long templateId = createTemplate("T-deact-" + System.nanoTime());
        Long groupId = createGroup(templateId);
        Long itemId = createItem(groupId);
        Long requestId = createRequest(DiagnosticRequest.DiagnosticRequestStatus.APPROVED);
        createAnswer(requestId, itemId);

        diagnosticRequestService.backfillHistoricalTemplateIds();
        assertThat(diagnosticRequestRepository.findById(requestId).orElseThrow().getTemplateId())
                .isEqualTo(templateId);

        InspectionTemplate t = templateRepository.findTemplateById(templateId).orElseThrow();
        t.setActive(false);
        templateRepository.saveTemplate(t);

        // resolution is unaffected: the binding is by ID, not by active status
        assertThat(diagnosticRequestRepository.findById(requestId).orElseThrow().getTemplateId())
                .isEqualTo(templateId);
    }

    // --- Adversarial 9: rerun with partially processed data ---
    @Test
    @DisplayName("Adversarial: повторный запуск при частично обработанных данных безопасно " +
            "довершает оставшиеся детерминированные заявки, не трогая уже привязанные")
    void rerunWithPartiallyProcessedData_CompletesRemainingSafely() {
        Long templateId = createTemplate("T-partial-" + System.nanoTime());
        Long groupId = createGroup(templateId);
        Long item1 = createItem(groupId);
        Long item2 = createItem(groupId);

        Long alreadyBoundRequestId = createRequest(DiagnosticRequest.DiagnosticRequestStatus.APPROVED);
        createAnswer(alreadyBoundRequestId, item1);
        diagnosticRequestRepository.bindTemplateIfUnset(alreadyBoundRequestId, templateId);

        Long stillUnresolvedRequestId = createRequest(DiagnosticRequest.DiagnosticRequestStatus.COMPLETED);
        createAnswer(stillUnresolvedRequestId, item2);

        TemplateBackfillResult result = diagnosticRequestService.backfillHistoricalTemplateIds();

        assertThat(diagnosticRequestRepository.findById(alreadyBoundRequestId).orElseThrow().getTemplateId())
                .as("already-bound request must be untouched").isEqualTo(templateId);
        assertThat(diagnosticRequestRepository.findById(stillUnresolvedRequestId).orElseThrow().getTemplateId())
                .as("the remaining deterministic request must be backfilled by this run")
                .isEqualTo(templateId);
    }
}
