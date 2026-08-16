// diagnostic/application/DiagnosticRequestTemplateBindingConcurrencyIntegrationTest.java
package com.example.autoauction.diagnostic.application;

import com.example.autoauction.diagnostic.domain.DiagnosticRequest;
import com.example.autoauction.diagnostic.domain.InspectionTemplate;
import com.example.autoauction.diagnostic.domain.port.DiagnosticRequestRepository;
import com.example.autoauction.diagnostic.domain.port.InspectionTemplateRepository;
import com.example.autoauction.diagnostic.web.dto.DiagnosticRequestResponse;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MEDIA-3.1A: real-thread, real-database (H2, test profile) proof of the
 * concurrency semantics designed in MEDIA-3 Phase 1.6 and required by Step 5
 * of the implementation prompt - specifically the distinction between a
 * same-template race (idempotent success for both callers) and a
 * different-template race (exactly one winner, the other gets a 409-mapped
 * IllegalStateException, never a silently-substituted "success" for the
 * template it did not request).
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("MEDIA-3.1A: конкурентная привязка шаблона к заявке")
class DiagnosticRequestTemplateBindingConcurrencyIntegrationTest {

    @Autowired
    private DiagnosticRequestService diagnosticRequestService;

    @Autowired
    private DiagnosticRequestRepository diagnosticRequestRepository;

    @Autowired
    private InspectionTemplateRepository templateRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    private Long diagnosticId;

    private Long createInProgressRequest(Long diagnosticId) {
        Role userRole = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        User applicant = new User(null, "tplrace" + System.nanoTime(), "tplrace" + System.nanoTime() + "@test.com",
                "encoded", true, Set.of(userRole));
        Long userId = userRepository.save(applicant).getId();

        DiagnosticRequest request = new DiagnosticRequest();
        request.setUserId(userId);
        request.setBrand("BMW");
        request.setModel("X5");
        request.setYear(2022);
        request.setVin("T" + (System.nanoTime() % 100000000L));
        request.setDepositFrozen(new java.math.BigDecimal("10000"));
        request.setStatus(DiagnosticRequest.DiagnosticRequestStatus.IN_PROGRESS);
        request.setAssignedTo(diagnosticId);
        request.setCreatedAt(OffsetDateTime.now());
        request.setUpdatedAt(OffsetDateTime.now());
        return diagnosticRequestRepository.save(request).getId();
    }

    private Long createActiveTemplate(String name) {
        InspectionTemplate template = new InspectionTemplate();
        template.setName(name);
        template.setActive(true);
        return templateRepository.saveTemplate(template).getId();
    }

    private Long createDiagnostician() {
        Role diagRole = roleRepository.findByName("ROLE_DIAGNOSTIC")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_DIAGNOSTIC", "Диагност")));
        User diagnostician = new User(null, "diag" + System.nanoTime(), "diag" + System.nanoTime() + "@test.com",
                "encoded", true, Set.of(diagRole));
        return userRepository.save(diagnostician).getId();
    }

    @RepeatedTest(5)
    @DisplayName("Два одновременных bind() с РАЗНЫМИ шаблонами: ровно один побеждает, " +
            "второй получает конфликт (а не чужой шаблон как 'успех')")
    void concurrentDifferentTemplateBinds_ExactlyOneWinner_LoserGetsConflict() throws Exception {
        diagnosticId = createDiagnostician();
        Long requestId = createInProgressRequest(diagnosticId);
        Long templateAId = createActiveTemplate("Шаблон A " + System.nanoTime());
        Long templateBId = createActiveTemplate("Шаблон B " + System.nanoTime());

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicReference<DiagnosticRequestResponse> resultA = new AtomicReference<>();
        AtomicReference<DiagnosticRequestResponse> resultB = new AtomicReference<>();
        AtomicReference<Throwable> errA = new AtomicReference<>();
        AtomicReference<Throwable> errB = new AtomicReference<>();

        executor.submit(() -> {
            try {
                barrier.await(5, TimeUnit.SECONDS);
                resultA.set(diagnosticRequestService.bindTemplate(requestId, diagnosticId, templateAId));
            } catch (Throwable t) {
                errA.set(t);
            }
        });
        executor.submit(() -> {
            try {
                barrier.await(5, TimeUnit.SECONDS);
                resultB.set(diagnosticRequestService.bindTemplate(requestId, diagnosticId, templateBId));
            } catch (Throwable t) {
                errB.set(t);
            }
        });
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        // Exactly one of the two calls must have succeeded, and it must have
        // succeeded with EXACTLY the template it requested.
        boolean aSucceeded = errA.get() == null && resultA.get() != null;
        boolean bSucceeded = errB.get() == null && resultB.get() != null;
        assertThat(aSucceeded ^ bSucceeded)
                .as("exactly one of the two different-template bind attempts must succeed").isTrue();

        if (aSucceeded) {
            assertThat(resultA.get().getTemplate().getId()).isEqualTo(templateAId);
            assertThat(errB.get()).as("the loser must receive a conflict, not a silent substitution")
                    .isInstanceOf(IllegalStateException.class);
        } else {
            assertThat(resultB.get().getTemplate().getId()).isEqualTo(templateBId);
            assertThat(errA.get()).as("the loser must receive a conflict, not a silent substitution")
                    .isInstanceOf(IllegalStateException.class);
        }

        DiagnosticRequest finalState = diagnosticRequestRepository.findById(requestId).orElseThrow();
        assertThat(finalState.getTemplateId())
                .as("the bound template must be exactly one of the two requested, never null, never both")
                .isIn(templateAId, templateBId);
    }

    @RepeatedTest(5)
    @DisplayName("Два одновременных bind() с ОДИНАКОВЫМ шаблоном: оба завершаются успешно " +
            "(идемпотентно), итоговый шаблон один и тот же")
    void concurrentSameTemplateBinds_BothSucceedIdempotently() throws Exception {
        diagnosticId = createDiagnostician();
        Long requestId = createInProgressRequest(diagnosticId);
        Long templateId = createActiveTemplate("Общий шаблон " + System.nanoTime());

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicReference<DiagnosticRequestResponse> resultA = new AtomicReference<>();
        AtomicReference<DiagnosticRequestResponse> resultB = new AtomicReference<>();
        AtomicReference<Throwable> errA = new AtomicReference<>();
        AtomicReference<Throwable> errB = new AtomicReference<>();

        executor.submit(() -> {
            try {
                barrier.await(5, TimeUnit.SECONDS);
                resultA.set(diagnosticRequestService.bindTemplate(requestId, diagnosticId, templateId));
            } catch (Throwable t) {
                errA.set(t);
            }
        });
        executor.submit(() -> {
            try {
                barrier.await(5, TimeUnit.SECONDS);
                resultB.set(diagnosticRequestService.bindTemplate(requestId, diagnosticId, templateId));
            } catch (Throwable t) {
                errB.set(t);
            }
        });
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        assertThat(errA.get()).as("same-template race must not throw for either caller").isNull();
        assertThat(errB.get()).as("same-template race must not throw for either caller").isNull();
        assertThat(resultA.get().getTemplate().getId()).isEqualTo(templateId);
        assertThat(resultB.get().getTemplate().getId()).isEqualTo(templateId);

        DiagnosticRequest finalState = diagnosticRequestRepository.findById(requestId).orElseThrow();
        assertThat(finalState.getTemplateId()).isEqualTo(templateId);
    }

    @Test
    @DisplayName("Повторный bind() того же шаблона после успешной привязки - идемпотентный успех " +
            "(последовательно, не гонка)")
    void sequentialSameTemplateRebind_IsIdempotent() {
        diagnosticId = createDiagnostician();
        Long requestId = createInProgressRequest(diagnosticId);
        Long templateId = createActiveTemplate("Последовательный шаблон " + System.nanoTime());

        DiagnosticRequestResponse first = diagnosticRequestService.bindTemplate(requestId, diagnosticId, templateId);
        DiagnosticRequestResponse second = diagnosticRequestService.bindTemplate(requestId, diagnosticId, templateId);

        assertThat(first.getTemplate().getId()).isEqualTo(templateId);
        assertThat(second.getTemplate().getId()).isEqualTo(templateId);
    }

    @Test
    @DisplayName("Попытка привязать другой шаблон после успешной привязки - конфликт (последовательно)")
    void sequentialDifferentTemplateRebind_ThrowsConflict() {
        diagnosticId = createDiagnostician();
        Long requestId = createInProgressRequest(diagnosticId);
        Long templateId = createActiveTemplate("Первый шаблон " + System.nanoTime());
        Long otherTemplateId = createActiveTemplate("Второй шаблон " + System.nanoTime());

        diagnosticRequestService.bindTemplate(requestId, diagnosticId, templateId);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> diagnosticRequestService.bindTemplate(requestId, diagnosticId, otherTemplateId));

        DiagnosticRequest finalState = diagnosticRequestRepository.findById(requestId).orElseThrow();
        assertThat(finalState.getTemplateId())
                .as("the original binding must survive an attempted replacement").isEqualTo(templateId);
    }
}
