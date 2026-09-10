package com.example.autoauction.policy.web;

import com.example.autoauction.auth.infrastructure.security.WithMockCustomUser;
import com.example.autoauction.policy.application.PolicyVersionService;
import com.example.autoauction.policy.domain.PenaltyType;
import com.example.autoauction.policy.domain.PolicyVersion;
import com.example.autoauction.policy.domain.port.PolicyVersionRepository;
import com.example.autoauction.policy.infrastructure.persistence.JpaViolationDefinitionEntity;
import com.example.autoauction.policy.infrastructure.persistence.SpringDataViolationDefinitionJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice 1 (Policy Administration): HTTP-layer contract for {@code /api/v1/admin/policy} -
 * authz boundary, input allow-list, error semantics, published immutability. Uses
 * {@code @SpringBootTest + @AutoConfigureMockMvc} (not the project's currently-failing
 * {@code @WebMvcTest} slice), mirroring {@code SellerViolationResolutionApiIntegrationTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Slice 1: AdminPolicyController")
class AdminPolicyControllerIntegrationTest {

    private static final String BASE = "/api/v1/admin/policy";

    @Autowired private MockMvc mockMvc;
    @Autowired private PolicyVersionService policyVersionService;
    @Autowired private PolicyVersionRepository policyVersionRepository;
    @Autowired private SpringDataViolationDefinitionJpaRepository violationDefinitionJpaRepository;

    /** Ensures at least one violation_definition exists so a draft can be made publishable in this shared H2 DB. */
    @BeforeEach
    void ensureCatalogue() {
        if (violationDefinitionJpaRepository.findAll().isEmpty()) {
            violationDefinitionJpaRepository.save(
                    new JpaViolationDefinitionEntity("APITEST_" + System.nanoTime(), "api-test fixture", OffsetDateTime.now()));
        }
    }

    private Long newDraftViaService(String rate) {
        return policyVersionService.create(new BigDecimal(rate), 1L).getId();
    }

    private Long newPublishedViaService(String rate) {
        Long id = newDraftViaService(rate);
        for (var d : violationDefinitionJpaRepository.findAll()) {
            policyVersionService.updateDraftViolation(id, d.getId(), PenaltyType.PERCENTAGE, new BigDecimal("100"));
        }
        return policyVersionService.publish(id).getId();
    }

    // ── Authorization matrix ───────────────────────────────────────────────────

    @Test
    @DisplayName("anonymous -> 401 on every endpoint")
    void anonymous_401() throws Exception {
        mockMvc.perform(get(BASE + "/current")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(BASE + "/1")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(BASE + "/violation-definitions")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(BASE + "/drafts").contentType(MediaType.APPLICATION_JSON).content("{\"commissionRate\":0.02}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(patch(BASE + "/drafts/1").contentType(MediaType.APPLICATION_JSON).content("{\"commissionRate\":0.02}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put(BASE + "/drafts/1/violations/1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"penaltyType\":\"PERCENTAGE\",\"penaltyValue\":10}")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(BASE + "/drafts/1/publish")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("ROLE_USER -> 403")
    void user_403() throws Exception {
        mockMvc.perform(get(BASE + "/current")).andExpect(status().isForbidden());
        mockMvc.perform(post(BASE + "/drafts").contentType(MediaType.APPLICATION_JSON).content("{\"commissionRate\":0.02}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "DIAGNOSTIC")
    @DisplayName("ROLE_DIAGNOSTIC -> 403")
    void diagnostic_403() throws Exception {
        mockMvc.perform(get(BASE + "/current")).andExpect(status().isForbidden());
        mockMvc.perform(post(BASE + "/drafts/1/publish")).andExpect(status().isForbidden());
    }

    // ── Reads ─────────────────────────────────────────────────────────────────

    @Test
    @WithMockCustomUser(userId = 1L, roles = {"ADMIN"})
    @DisplayName("GET /{id} unknown -> 404 (not 409); GET /violation-definitions -> 200 list")
    void reads_unknownId404_definitions200() throws Exception {
        mockMvc.perform(get(BASE + "/99999999")).andExpect(status().isNotFound());
        mockMvc.perform(get(BASE + "/violation-definitions")).andExpect(status().isOk());
        mockMvc.perform(get(BASE)).andExpect(status().isOk());
        mockMvc.perform(get(BASE).param("published", "true")).andExpect(status().isOk());
    }

    // ── Draft lifecycle ───────────────────────────────────────────────────────

    @Test
    @WithMockCustomUser(userId = 7L, roles = {"ADMIN"})
    @DisplayName("POST /drafts -> 201 unpublished; PATCH commission -> 200; out-of-range -> 400")
    void createAndPatchDraft() throws Exception {
        String location = mockMvc.perform(post(BASE + "/drafts")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"commissionRate\":0.0200}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.published").value(false))
                .andExpect(jsonPath("$.commissionRate").value(0.02))
                .andExpect(jsonPath("$.createdBy").value(7))
                .andReturn().getResponse().getContentAsString();
        Long id = com.jayway.jsonpath.JsonPath.parse(location).read("$.id", Long.class);

        mockMvc.perform(patch(BASE + "/drafts/" + id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"commissionRate\":0.0250}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commissionRate").value(0.025));

        mockMvc.perform(patch(BASE + "/drafts/" + id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"commissionRate\":1.5}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch(BASE + "/drafts/" + id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"commissionRate\":-0.01}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockCustomUser(userId = 1L, roles = {"ADMIN"})
    @DisplayName("PUT violations: unknown definition -> 404; bad enum -> 400; PERCENTAGE > 100 -> 400")
    void configureViolationErrors() throws Exception {
        Long id = newDraftViaService("0.02");

        mockMvc.perform(put(BASE + "/drafts/" + id + "/violations/99999999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"penaltyType\":\"PERCENTAGE\",\"penaltyValue\":10}"))
                .andExpect(status().isNotFound());

        Long defId = violationDefinitionJpaRepository.findAll().get(0).getId();
        mockMvc.perform(put(BASE + "/drafts/" + id + "/violations/" + defId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"penaltyType\":\"NONSENSE\",\"penaltyValue\":10}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put(BASE + "/drafts/" + id + "/violations/" + defId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"penaltyType\":\"PERCENTAGE\",\"penaltyValue\":100.01}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put(BASE + "/drafts/" + id + "/violations/" + defId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"penaltyType\":\"FIXED_AMOUNT\",\"penaltyValue\":-1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockCustomUser(userId = 1L, roles = {"ADMIN"})
    @DisplayName("publish: incomplete -> 409 with missing codes; unknown id -> 404; already published -> 409")
    void publishSemantics() throws Exception {
        // ensure the catalogue has >= 2 codes so an incomplete draft is possible
        violationDefinitionJpaRepository.save(new JpaViolationDefinitionEntity(
                "APITEST_INC_" + System.nanoTime(), "fixture", OffsetDateTime.now()));
        Long incompleteId = newDraftViaService("0.02"); // zero violation rows configured

        mockMvc.perform(post(BASE + "/drafts/" + incompleteId + "/publish"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("APITEST")));

        mockMvc.perform(post(BASE + "/drafts/99999999/publish")).andExpect(status().isNotFound());

        Long publishedId = newPublishedViaService("0.02");
        mockMvc.perform(post(BASE + "/drafts/" + publishedId + "/publish")).andExpect(status().isConflict());
        // and PATCH / PUT against a published id -> 409
        mockMvc.perform(patch(BASE + "/drafts/" + publishedId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"commissionRate\":0.03}"))
                .andExpect(status().isConflict());
        Long defId = violationDefinitionJpaRepository.findAll().get(0).getId();
        mockMvc.perform(put(BASE + "/drafts/" + publishedId + "/violations/" + defId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"penaltyType\":\"PERCENTAGE\",\"penaltyValue\":5}"))
                .andExpect(status().isConflict());
    }

    @Test
    @WithMockCustomUser(userId = 1L, roles = {"ADMIN"})
    @DisplayName("full happy path: create -> configure all -> publish -> 200 published; GET /current reflects it")
    void happyPathPublish() throws Exception {
        Long id = newDraftViaService("0.0200");
        for (var d : violationDefinitionJpaRepository.findAll()) {
            mockMvc.perform(put(BASE + "/drafts/" + id + "/violations/" + d.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"penaltyType\":\"PERCENTAGE\",\"penaltyValue\":100}"))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(post(BASE + "/drafts/" + id + "/publish"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.published").value(true))
                .andExpect(jsonPath("$.versionNumber").isNumber());

        mockMvc.perform(get(BASE + "/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.published").value(true))
                .andExpect(jsonPath("$.violations").isArray());
    }

    @Test
    @WithMockCustomUser(userId = 3L, roles = {"ADMIN"})
    @DisplayName("request tampering: id/versionNumber/published/createdBy in the body are ignored")
    void requestTamperingIgnored() throws Exception {
        String body = "{\"commissionRate\":0.0200,\"id\":123456,\"versionNumber\":999,"
                + "\"published\":true,\"createdBy\":424242,\"createdAt\":\"2000-01-01T00:00:00Z\"}";
        String json = mockMvc.perform(post(BASE + "/drafts")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.published").value(false))
                .andExpect(jsonPath("$.createdBy").value(3))
                .andReturn().getResponse().getContentAsString();
        Long id = com.jayway.jsonpath.JsonPath.parse(json).read("$.id", Long.class);
        Integer versionNumber = com.jayway.jsonpath.JsonPath.parse(json).read("$.versionNumber", Integer.class);

        assertThat(id).isNotEqualTo(123456L);
        assertThat(versionNumber).isNotEqualTo(999);
        PolicyVersion persisted = policyVersionRepository.findById(id).orElseThrow();
        assertThat(persisted.isPublished()).isFalse();
        assertThat(persisted.getCreatedBy()).isEqualTo(3L);
        assertThat(persisted.getVersionNumber()).isNotEqualTo(999L);
    }
}
