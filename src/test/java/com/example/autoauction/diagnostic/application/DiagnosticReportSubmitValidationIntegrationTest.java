package com.example.autoauction.diagnostic.application;

import com.example.autoauction.diagnostic.domain.DiagnosticRequest;
import com.example.autoauction.diagnostic.domain.DiagnosticRequest.DiagnosticRequestStatus;
import com.example.autoauction.diagnostic.domain.InspectionTemplate;
import com.example.autoauction.diagnostic.domain.port.DiagnosticRequestRepository;
import com.example.autoauction.diagnostic.web.dto.InspectionAnswerDto;
import com.example.autoauction.diagnostic.web.dto.InspectionGroupDto;
import com.example.autoauction.diagnostic.web.dto.InspectionItemDto;
import com.example.autoauction.diagnostic.web.dto.InspectionTemplateDto;
import com.example.autoauction.diagnostic.web.dto.SubmitInspectionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Scenario 3 step 7: submitting a report is refused unless every required item is filled - by
 * the server, not only by the diagnostician UI - using the same per-type rules as the UI. Draft
 * saves stay unrestricted. Ratings are 1-5. Real template/request persistence (H2).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("Scenario 3: server-side validation of a submitted diagnostic report")
class DiagnosticReportSubmitValidationIntegrationTest {

    private static final Long DIAGNOSTICIAN = 7001L;

    @Autowired private DiagnosticRequestService diagnosticRequestService;
    @Autowired private InspectionTemplateService templateService;
    @Autowired private DiagnosticRequestRepository requestRepository;

    private final Map<String, Long> items = new HashMap<>();
    private Long requestId;

    private void item(Long groupId, String title, String fieldType, boolean required) {
        InspectionItemDto dto = new InspectionItemDto();
        dto.setTitle(title);
        dto.setFieldType(fieldType);
        dto.setRequired(required);
        dto.setSortOrder(items.size() + 1);
        items.put(title, templateService.addItem(groupId, dto).getId());
    }

    @BeforeEach
    void setUp() {
        InspectionTemplateDto templateDto = new InspectionTemplateDto();
        templateDto.setName("Submit-" + System.nanoTime());
        templateDto.setVehicleType("SEDAN");
        InspectionTemplate template = templateService.createTemplate(templateDto, 1L);
        InspectionGroupDto groupDto = new InspectionGroupDto();
        groupDto.setName("Кузов");
        groupDto.setSortOrder(1);
        Long groupId = templateService.addGroup(template.getId(), groupDto).getId();
        item(groupId, "Оценка ЛКП", "NUMBER", true);
        item(groupId, "Фото кузова", "PHOTO", true);
        item(groupId, "Комментарий по кузову", "TEXT", true);
        item(groupId, "Сколы на стекле", "CHECKBOX", true);
        item(groupId, "Доп. замечания", "TEXT", false);

        DiagnosticRequest request = new DiagnosticRequest();
        request.setUserId(1L);
        request.setBrand("BMW");
        request.setModel("X5");
        request.setYear(2020);
        request.setVin("WBA" + String.valueOf(System.nanoTime()).substring(0, 14));
        request.setStatus(DiagnosticRequestStatus.IN_PROGRESS);
        request.setAssignedTo(DIAGNOSTICIAN);
        request.setTemplateId(template.getId());
        request.setDepositFrozen(new BigDecimal("10000"));
        request.setCreatedAt(OffsetDateTime.now());
        request.setUpdatedAt(OffsetDateTime.now());
        requestId = requestRepository.save(request).getId();
    }

    private InspectionAnswerDto answer(String title, Integer rating, String comment, List<String> photos) {
        InspectionAnswerDto a = new InspectionAnswerDto();
        a.setItemId(items.get(title));
        a.setRating(rating);
        a.setComment(comment);
        a.setPhotoUrls(photos);
        return a;
    }

    /** Every required item filled correctly. */
    private List<InspectionAnswerDto> complete() {
        List<InspectionAnswerDto> answers = new ArrayList<>();
        answers.add(answer("Оценка ЛКП", 4, null, null));
        answers.add(answer("Фото кузова", null, null, List.of("https://cdn/x.jpg")));
        answers.add(answer("Комментарий по кузову", null, "Небольшая царапина на заднем бампере", null));
        answers.add(answer("Сколы на стекле", null, "false", null));
        return answers;
    }

    private void submit(List<InspectionAnswerDto> answers, boolean finalSubmit) {
        SubmitInspectionRequest dto = new SubmitInspectionRequest();
        dto.setAnswers(answers);
        dto.setSubmit(finalSubmit);
        diagnosticRequestService.saveInspectionAnswers(requestId, DIAGNOSTICIAN, dto);
    }

    private DiagnosticRequestStatus status() {
        return requestRepository.findById(requestId).orElseThrow().getStatus();
    }

    private List<InspectionAnswerDto> completeWith(String title, InspectionAnswerDto replacement) {
        List<InspectionAnswerDto> answers = new ArrayList<>(complete());
        answers.removeIf(a -> a.getItemId().equals(items.get(title)));
        if (replacement != null) {
            answers.add(replacement);
        }
        return answers;
    }

    @Test
    @DisplayName("all required items filled (optional item empty, checkbox = false) -> COMPLETED")
    void completeReport_isSubmitted() {
        submit(complete(), true);
        assertThat(status()).isEqualTo(DiagnosticRequestStatus.COMPLETED);
    }

    @ParameterizedTest(name = "missing required item [{0}] -> refused, still IN_PROGRESS")
    @ValueSource(strings = {"Оценка ЛКП", "Фото кузова", "Комментарий по кузову", "Сколы на стекле"})
    void missingRequiredItem_isRefused(String title) {
        assertThatThrownBy(() -> submit(completeWith(title, null), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(title);
        assertThat(status()).isEqualTo(DiagnosticRequestStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("present but empty answers do not count as filled")
    void emptyAnswers_areNotFilled() {
        assertThatThrownBy(() -> submit(completeWith("Комментарий по кузову",
                answer("Комментарий по кузову", null, "   ", null)), true)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> submit(completeWith("Фото кузова",
                answer("Фото кузова", null, null, List.of())), true)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> submit(completeWith("Сколы на стекле",
                answer("Сколы на стекле", null, "maybe", null)), true)).isInstanceOf(IllegalStateException.class);
        assertThat(status()).isEqualTo(DiagnosticRequestStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("a draft may be saved with required items still empty")
    void draft_isNotValidated() {
        submit(List.of(answer("Оценка ЛКП", 3, null, null)), false);
        assertThat(status()).isEqualTo(DiagnosticRequestStatus.IN_PROGRESS);
    }

    @ParameterizedTest(name = "rating {0} is refused even in a draft")
    @ValueSource(ints = {0, 6, -1})
    void ratingOutOfRange_isRefused(int rating) {
        assertThatThrownBy(() -> submit(List.of(answer("Оценка ЛКП", rating, null, null)), false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "rating {0} is accepted")
    @ValueSource(ints = {1, 5})
    void ratingBoundaries_areAccepted(int rating) {
        submit(completeWith("Оценка ЛКП", answer("Оценка ЛКП", rating, null, null)), true);
        assertThat(status()).isEqualTo(DiagnosticRequestStatus.COMPLETED);
    }
}
