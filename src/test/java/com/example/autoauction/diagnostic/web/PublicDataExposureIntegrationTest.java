package com.example.autoauction.diagnostic.web;

import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.diagnostic.application.DiagnosticRequestService;
import com.example.autoauction.diagnostic.commitment.application.SellerDiagnosticCommitmentService;
import com.example.autoauction.diagnostic.domain.DiagnosticRequest;
import com.example.autoauction.diagnostic.domain.InspectionAnswer;
import com.example.autoauction.diagnostic.domain.InspectionGroup;
import com.example.autoauction.diagnostic.domain.InspectionItem;
import com.example.autoauction.diagnostic.domain.InspectionTemplate;
import com.example.autoauction.diagnostic.domain.port.InspectionAnswerRepository;
import com.example.autoauction.diagnostic.domain.port.InspectionTemplateRepository;
import com.example.autoauction.diagnostic.domain.port.DiagnosticRequestRepository;
import com.example.autoauction.policy.application.PolicyVersionService;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import com.example.autoauction.vehicle.domain.port.VehicleRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * SEC-12: the anonymous report and vehicle endpoints show the car and the inspection - never the
 * seller's identity, money, staff identities or internal decisions. Allowlist checked key by key.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("SEC-12: public report and vehicle expose only public data")
class PublicDataExposureIntegrationTest {

    private static final Long ADMIN_ID = 9961L;

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private DepositService depositService;
    @Autowired private PolicyVersionService policyVersionService;
    @Autowired private DiagnosticRequestRepository diagnosticRequestRepository;
    @Autowired private SellerDiagnosticCommitmentService commitmentService;
    @Autowired private DiagnosticRequestService diagnosticRequestService;
    @Autowired private VehicleRepository vehicleRepository;
    @Autowired private InspectionTemplateRepository templateRepository;
    @Autowired private InspectionAnswerRepository answerRepository;

    private record Approved(Long requestId, Long vehicleId, String sellerName) {}

    private Approved approvedReport() {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        String name = "sec12seller" + System.nanoTime();
        Long sellerId = userRepository.save(new User(null, name, name + "@test.com", "x", true, Set.of(role))).getId();
        depositService.createDeposit(sellerId, new BigDecimal("50000"));
        policyVersionService.publish(policyVersionService.create(new BigDecimal("0.05"), ADMIN_ID).getId(), ADMIN_ID + 1, 0L);

        String vin = ("S12" + System.nanoTime() + "00000000000000").substring(0, 17);
        DiagnosticRequest request = new DiagnosticRequest();
        request.setUserId(sellerId);
        request.setBrand("BMW");
        request.setModel("X5");
        request.setYear(2022);
        request.setVin(vin);
        request.setDepositFrozen(new BigDecimal("10000"));
        request.setStatus(DiagnosticRequest.DiagnosticRequestStatus.PENDING);
        request.setCreatedAt(OffsetDateTime.now());
        request.setUpdatedAt(OffsetDateTime.now());
        InspectionTemplate template = new InspectionTemplate();
        template.setName("SEC-12 " + System.nanoTime());
        template.setActive(true);
        template.setCreatedBy(ADMIN_ID);
        template.setCreatedAt(OffsetDateTime.now());
        template.setUpdatedAt(OffsetDateTime.now());
        Long templateId = templateRepository.saveTemplate(template).getId();
        InspectionGroup group = new InspectionGroup();
        group.setTemplateId(templateId);
        group.setName("Кузов");
        group.setSortOrder(0);
        group.setCreatedAt(OffsetDateTime.now());
        Long groupId = templateRepository.saveGroup(group).getId();
        InspectionItem item = new InspectionItem();
        item.setGroupId(groupId);
        item.setTitle("Состояние кузова");
        item.setFieldType("RATING");
        item.setHasRating(true);
        item.setSortOrder(0);
        item.setCreatedAt(OffsetDateTime.now());
        Long itemId = templateRepository.saveItem(item).getId();
        request.setTemplateId(templateId);
        Long requestId = diagnosticRequestRepository.save(request).getId();
        InspectionAnswer answer = new InspectionAnswer();
        answer.setDiagnosticRequestId(requestId);
        answer.setInspectionItemId(itemId);
        answer.setRating(4);
        answer.setComment("ok");
        answer.setPhotoUrls(List.of());
        answer.setVideoUrls(List.of());
        answer.setCreatedAt(OffsetDateTime.now());
        answer.setUpdatedAt(OffsetDateTime.now());
        answerRepository.save(answer);
        commitmentService.freezeCommitment(sellerId, requestId, new BigDecimal("10000"), new BigDecimal("1000"));
        DiagnosticRequest completed = diagnosticRequestRepository.findById(requestId).orElseThrow();
        completed.setStatus(DiagnosticRequest.DiagnosticRequestStatus.COMPLETED);
        completed.setAssignedTo(ADMIN_ID + 5);
        diagnosticRequestRepository.save(completed);
        diagnosticRequestService.approveReport(requestId, ADMIN_ID);
        return new Approved(requestId, vehicleRepository.findByVin(vin).orElseThrow().getId(), name);
    }

    private String anonymousGet(String url) throws Exception {
        MvcResult r = mockMvc.perform(get(url)).andReturn();
        assertThat(r.getResponse().getStatus()).as(url).isEqualTo(200);
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static Set<String> keys(String json) {
        Map<String, Object> map = JsonPath.read(json, "$");
        return map.keySet();
    }

    // VIN-AUTH-01: vinHidden tells the page that a VIN exists but is withheld from this (anonymous) viewer
    private static final Set<String> REPORT_KEYS = Set.of("id", "vin", "template", "existingAnswers", "vinHidden");
    private static final Set<String> TEMPLATE_KEYS = Set.of("name", "groups");
    private static final Set<String> GROUP_KEYS = Set.of("id", "name", "items");
    private static final Set<String> ITEM_KEYS = Set.of("id", "title", "fieldType", "description");
    private static final Set<String> ANSWER_KEYS = Set.of("inspectionItemId", "rating", "comment", "photoUrls", "skipped");

    private static Set<String> keysAt(String json, String path) {
        Map<String, Object> map = JsonPath.read(json, path);
        return map.keySet();
    }

    @Test
    @DisplayName("approved report, by id and by vehicle: only the car and the inspection; no seller, money or staff")
    void publicReport_allowlistOnly() throws Exception {
        Approved a = approvedReport();
        for (String url : new String[] {"/api/v1/public/reports/" + a.requestId(), "/api/v1/public/reports/by-vehicle/" + a.vehicleId()}) {
            String json = anonymousGet(url);
            assertThat(keys(json)).as(url).isEqualTo(REPORT_KEYS);
            assertThat(keysAt(json, "$.template")).isEqualTo(TEMPLATE_KEYS);
            assertThat(keysAt(json, "$.template.groups[0]")).isEqualTo(GROUP_KEYS);
            assertThat(keysAt(json, "$.template.groups[0].items[0]")).isEqualTo(ITEM_KEYS);
            assertThat(keysAt(json, "$.existingAnswers[0]")).isEqualTo(ANSWER_KEYS);
            assertThat(json).doesNotContain(a.sellerName(), "userId", "userName", "depositFrozen", "assignedTo",
                    "diagnosticName", "commitmentStatus", "violationResolution", "rediagnostic", "createdBy",
                    "createdAt", "updatedAt", "diagnosticRequestId", "templateId", "groupId", "rejected");
            assertThat(((Number) JsonPath.read(json, "$.id")).longValue()).isEqualTo(a.requestId());
            assertThat(((Number) JsonPath.read(json, "$.existingAnswers[0].rating")).intValue()).isEqualTo(4);
        }
    }

    @Test
    @DisplayName("a report that is not approved stays hidden from anonymous visitors")
    void notApprovedReport_hidden() throws Exception {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        String name = "sec12pending" + System.nanoTime();
        Long sellerId = userRepository.save(new User(null, name, name + "@test.com", "x", true, Set.of(role))).getId();
        DiagnosticRequest request = new DiagnosticRequest();
        request.setUserId(sellerId);
        request.setBrand("Audi");
        request.setModel("A6");
        request.setYear(2021);
        request.setVin(("P12" + System.nanoTime() + "00000000000000").substring(0, 17));
        request.setDepositFrozen(new BigDecimal("10000"));
        request.setStatus(DiagnosticRequest.DiagnosticRequestStatus.COMPLETED);
        request.setCreatedAt(OffsetDateTime.now());
        request.setUpdatedAt(OffsetDateTime.now());
        Long requestId = diagnosticRequestRepository.save(request).getId();

        MvcResult r = mockMvc.perform(get("/api/v1/public/reports/" + requestId)).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(404);
        assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain(name);
    }

    @Test
    @DisplayName("public vehicle: no licence plate, no staff ids or usernames, no rejection reason")
    void publicVehicle_allowlistOnly() throws Exception {
        Approved a = approvedReport();
        String json = anonymousGet("/api/v1/public/vehicles/" + a.vehicleId());
        assertThat(keys(json)).isEqualTo(Set.of("id", "brand", "model", "year", "vin", "color", "mileage",
                "engineCapacity", "fuelType", "transmission", "description", "vinHidden"));
        assertThat((Object) JsonPath.read(json, "$.vin")).as("VIN-AUTH-01: anonymous never gets the VIN").isNull();
        assertThat(json).doesNotContain("licensePlate", "adminId", "adminName", "diagnosticId", "diagnosticName",
                "rejectionReason", "status", "createdAt", "submittedAt", "reviewedAt");
        assertThat((String) JsonPath.read(json, "$.brand")).isEqualTo("BMW");
    }
}
