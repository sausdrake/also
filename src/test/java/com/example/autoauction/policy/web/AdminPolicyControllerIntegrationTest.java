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
 * Slice 1 / Policy Administration: HTTP-layer contract for {@code /api/v1/admin/policy} -
 * authz boundary, input allow-list, error semantics, published immutability, maker/checker,
 * lineage, and review-to-publish freshness.
 *
 * <p>Fixture convention: drafts created directly via {@link #newDraftViaService} /
 * {@link #newPublishedViaService} use the reserved, out-of-band creator/checker ids
 * {@link #FIXTURE_CREATOR} / {@link #FIXTURE_CHECKER} (777 / 778) - deliberately distinct from
 * every {@code @WithMockCustomUser} id used by an individual test method in this file (1, 3, 7),
 * so that a test exercising some OTHER conflict (completeness, already-published, etc.) is never
 * accidentally short-circuited by an incidental maker/checker conflict. Maker/checker itself is
 * proven directly and explicitly by {@link #publish_ByDraftsOwnCreator_RejectedMakerChecker}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Slice 1: AdminPolicyController")
class AdminPolicyControllerIntegrationTest {

    private static final String BASE = "/api/v1/admin/policy";
    private static final Long FIXTURE_CREATOR = 777L;
    private static final Long FIXTURE_CHECKER = 778L;

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
        return policyVersionService.create(new BigDecimal(rate), FIXTURE_CREATOR).getId();
    }

    private Long newPublishedViaService(String rate) {
        Long id = newDraftViaService(rate);
        for (var d : violationDefinitionJpaRepository.findAll()) {
            policyVersionService.updateDraftViolation(id, d.getId(), PenaltyType.PERCENTAGE, new BigDecimal("100"), 0L);
        }
        PolicyVersion beforePublish = policyVersionService.getById(id);
        return policyVersionService.publish(id, FIXTURE_CHECKER, beforePublish.getConfigurationRevision()).getId();
    }

    private String publishBody(long observedConfigurationRevision) {
        return "{\"observedConfigurationRevision\":" + observedConfigurationRevision + "}";
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
        mockMvc.perform(patch(BASE + "/drafts/1").contentType(MediaType.APPLICATION_JSON).content("{\"commissionRate\":0.02,\"version\":0}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put(BASE + "/drafts/1/violations/1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"penaltyType\":\"PERCENTAGE\",\"penaltyValue\":10,\"version\":0}")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(BASE + "/drafts/1/publish").contentType(MediaType.APPLICATION_JSON).content(publishBody(0)))
                .andExpect(status().isUnauthorized());
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
        mockMvc.perform(post(BASE + "/drafts/1/publish").contentType(MediaType.APPLICATION_JSON).content(publishBody(0)))
                .andExpect(status().isForbidden());
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
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.configurationRevision").value(0))
                .andExpect(jsonPath("$.publishedBy").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        Long id = com.jayway.jsonpath.JsonPath.parse(location).read("$.id", Long.class);

        mockMvc.perform(patch(BASE + "/drafts/" + id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"commissionRate\":0.0250,\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commissionRate").value(0.025));

        mockMvc.perform(patch(BASE + "/drafts/" + id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"commissionRate\":1.5,\"version\":0}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch(BASE + "/drafts/" + id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"commissionRate\":-0.01,\"version\":0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockCustomUser(userId = 7L, roles = {"ADMIN"})
    @DisplayName("PATCH commission with a stale version -> 409 OPTIMISTIC_VERSION_CONFLICT")
    void patchCommission_StaleVersion_Returns409WithCode() throws Exception {
        String created = mockMvc.perform(post(BASE + "/drafts")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"commissionRate\":0.0200}"))
                .andReturn().getResponse().getContentAsString();
        Long id = com.jayway.jsonpath.JsonPath.parse(created).read("$.id", Long.class);

        mockMvc.perform(patch(BASE + "/drafts/" + id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"commissionRate\":0.03,\"version\":999}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPTIMISTIC_VERSION_CONFLICT"));
    }

    @Test
    @WithMockCustomUser(userId = 1L, roles = {"ADMIN"})
    @DisplayName("PUT violations: unknown definition -> 404; bad enum -> 400; PERCENTAGE > 100 -> 400")
    void configureViolationErrors() throws Exception {
        Long id = newDraftViaService("0.02");

        mockMvc.perform(put(BASE + "/drafts/" + id + "/violations/99999999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"penaltyType\":\"PERCENTAGE\",\"penaltyValue\":10,\"version\":0}"))
                .andExpect(status().isNotFound());

        Long defId = violationDefinitionJpaRepository.findAll().get(0).getId();
        mockMvc.perform(put(BASE + "/drafts/" + id + "/violations/" + defId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"penaltyType\":\"NONSENSE\",\"penaltyValue\":10,\"version\":0}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put(BASE + "/drafts/" + id + "/violations/" + defId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"penaltyType\":\"PERCENTAGE\",\"penaltyValue\":100.01,\"version\":0}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put(BASE + "/drafts/" + id + "/violations/" + defId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"penaltyType\":\"FIXED_AMOUNT\",\"penaltyValue\":-1,\"version\":0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockCustomUser(userId = 1L, roles = {"ADMIN"})
    @DisplayName("PUT violations: updating an existing row with a stale observedVersion -> 409 OPTIMISTIC_VERSION_CONFLICT " +
            "(the genuine concurrent first-time-insert race -> VIOLATION_INSERT_CONFLICT is proven with real threads in " +
            "PolicyVersionServiceIntegrationTest)")
    void configureViolation_StaleVersionOnExistingRow_Returns409WithCode() throws Exception {
        // A FRESH catalogue entry, never referenced by any policy published before this test
        // runs - otherwise, in this shared, non-transactional H2 database, create()'s
        // copy-forward could have already given the new draft a non-zero-version row for an
        // existing catalogue entry, making the very first PUT below an update instead of an
        // insert and invalidating this test's version-progression assumptions.
        Long defId = violationDefinitionJpaRepository.save(new JpaViolationDefinitionEntity(
                "APITEST_STALEVER_" + System.nanoTime(), "fixture", OffsetDateTime.now())).getId();
        Long id = newDraftViaService("0.02");

        // First configuration: a genuine insert.
        mockMvc.perform(put(BASE + "/drafts/" + id + "/violations/" + defId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"penaltyType\":\"PERCENTAGE\",\"penaltyValue\":10,\"version\":0}"))
                .andExpect(status().isOk());

        // The row now exists with version 0 - resubmitting with observedVersion 0 again is a
        // legitimate update (not a conflict): proves the update path, not the insert-race path.
        mockMvc.perform(put(BASE + "/drafts/" + id + "/violations/" + defId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"penaltyType\":\"PERCENTAGE\",\"penaltyValue\":20,\"version\":0}"))
                .andExpect(status().isOk());

        // Now the row is at version 1 - resubmitting observedVersion 0 again is stale.
        mockMvc.perform(put(BASE + "/drafts/" + id + "/violations/" + defId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"penaltyType\":\"PERCENTAGE\",\"penaltyValue\":30,\"version\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPTIMISTIC_VERSION_CONFLICT"));
    }

    @Test
    @WithMockCustomUser(userId = 1L, roles = {"ADMIN"})
    @DisplayName("publish: incomplete -> 409 with missing codes; unknown id -> 404; already published -> 409")
    void publishSemantics() throws Exception {
        // ensure the catalogue has >= 2 codes so an incomplete draft is possible
        violationDefinitionJpaRepository.save(new JpaViolationDefinitionEntity(
                "APITEST_INC_" + System.nanoTime(), "fixture", OffsetDateTime.now()));
        Long incompleteId = newDraftViaService("0.02"); // zero violation rows configured, created by FIXTURE_CREATOR

        mockMvc.perform(post(BASE + "/drafts/" + incompleteId + "/publish")
                        .contentType(MediaType.APPLICATION_JSON).content(publishBody(0)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("APITEST")));

        mockMvc.perform(post(BASE + "/drafts/99999999/publish")
                        .contentType(MediaType.APPLICATION_JSON).content(publishBody(0)))
                .andExpect(status().isNotFound());

        Long publishedId = newPublishedViaService("0.02");
        mockMvc.perform(post(BASE + "/drafts/" + publishedId + "/publish")
                        .contentType(MediaType.APPLICATION_JSON).content(publishBody(0)))
                .andExpect(status().isConflict());
        // and PATCH / PUT against a published id -> 409
        mockMvc.perform(patch(BASE + "/drafts/" + publishedId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"commissionRate\":0.03,\"version\":0}"))
                .andExpect(status().isConflict());
        Long defId = violationDefinitionJpaRepository.findAll().get(0).getId();
        mockMvc.perform(put(BASE + "/drafts/" + publishedId + "/violations/" + defId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"penaltyType\":\"PERCENTAGE\",\"penaltyValue\":5,\"version\":0}"))
                .andExpect(status().isConflict());
    }

    @Test
    @WithMockCustomUser(userId = 1L, roles = {"ADMIN"})
    @DisplayName("publish: draft's own creator is rejected (maker/checker, Decision 1) with a distinct 409 code")
    void publish_ByDraftsOwnCreator_RejectedMakerChecker() throws Exception {
        // Created via HTTP, so createdBy == the authenticated caller (1L) for THIS test.
        String created = mockMvc.perform(post(BASE + "/drafts")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"commissionRate\":0.0200}"))
                .andReturn().getResponse().getContentAsString();
        Long id = com.jayway.jsonpath.JsonPath.parse(created).read("$.id", Long.class);

        mockMvc.perform(post(BASE + "/drafts/" + id + "/publish")
                        .contentType(MediaType.APPLICATION_JSON).content(publishBody(0)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MAKER_CHECKER_VIOLATION"));
    }

    @Test
    @WithMockCustomUser(userId = 1L, roles = {"ADMIN"})
    @DisplayName("publish: stale observedConfigurationRevision -> 409 STALE_POLICY_CONFIGURATION")
    void publish_StaleConfigurationRevision_Returns409WithCode() throws Exception {
        violationDefinitionJpaRepository.save(new JpaViolationDefinitionEntity(
                "APITEST_FRESH_" + System.nanoTime(), "fixture", OffsetDateTime.now()));
        Long id = newDraftViaService("0.02");
        for (var d : violationDefinitionJpaRepository.findAll()) {
            mockMvc.perform(put(BASE + "/drafts/" + id + "/violations/" + d.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"penaltyType\":\"PERCENTAGE\",\"penaltyValue\":100,\"version\":0}"))
                    .andExpect(status().isOk());
        }

        // Publishing with an observedConfigurationRevision of 0 (as if the checker never reloaded
        // after the edits above) is stale - the real current revision is > 0 by now.
        mockMvc.perform(post(BASE + "/drafts/" + id + "/publish")
                        .contentType(MediaType.APPLICATION_JSON).content(publishBody(0)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_POLICY_CONFIGURATION"));
    }

    @Test
    @WithMockCustomUser(userId = 1L, roles = {"ADMIN"})
    @DisplayName("publish: stale lineage (a different, newer draft already published since this one was created) -> 409 STALE_POLICY_BASE")
    void publish_StaleLineage_Returns409WithCode() throws Exception {
        violationDefinitionJpaRepository.save(new JpaViolationDefinitionEntity(
                "APITEST_LINEAGE_" + System.nanoTime(), "fixture", OffsetDateTime.now()));
        Long staleDraftId = newDraftViaService("0.02");
        for (var d : violationDefinitionJpaRepository.findAll()) {
            policyVersionService.updateDraftViolation(staleDraftId, d.getId(), PenaltyType.PERCENTAGE, new BigDecimal("100"), 0L);
        }
        // A sibling publishes first, becoming the new current - staleDraftId's lineage is now stale.
        newPublishedViaService("0.03");

        PolicyVersion fresh = policyVersionService.getById(staleDraftId);
        mockMvc.perform(post(BASE + "/drafts/" + staleDraftId + "/publish")
                        .contentType(MediaType.APPLICATION_JSON).content(publishBody(fresh.getConfigurationRevision())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_POLICY_BASE"));
    }

    @Test
    @WithMockCustomUser(userId = 1L, roles = {"ADMIN"})
    @DisplayName("full happy path: create -> configure all -> publish -> 200 published; GET /current reflects it")
    void happyPathPublish() throws Exception {
        Long id = newDraftViaService("0.0200");
        long lastObservedRevision = 0;
        for (var d : violationDefinitionJpaRepository.findAll()) {
            String body = mockMvc.perform(put(BASE + "/drafts/" + id + "/violations/" + d.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"penaltyType\":\"PERCENTAGE\",\"penaltyValue\":100,\"version\":0}"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            lastObservedRevision = com.jayway.jsonpath.JsonPath.parse(body).read("$.configurationRevision", Long.class);
        }
        mockMvc.perform(post(BASE + "/drafts/" + id + "/publish")
                        .contentType(MediaType.APPLICATION_JSON).content(publishBody(lastObservedRevision)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.published").value(true))
                .andExpect(jsonPath("$.versionNumber").isNumber())
                .andExpect(jsonPath("$.publishedBy").value(1))
                .andExpect(jsonPath("$.publishedAt").isNotEmpty());

        mockMvc.perform(get(BASE + "/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.published").value(true))
                .andExpect(jsonPath("$.violations").isArray());
    }

    @Test
    @WithMockCustomUser(userId = 3L, roles = {"ADMIN"})
    @DisplayName("request tampering: server-owned fields in the body are ignored, including the new " +
            "publication/lineage/concurrency fields")
    void requestTamperingIgnored() throws Exception {
        String body = "{\"commissionRate\":0.0200,\"id\":123456,\"versionNumber\":999,"
                + "\"published\":true,\"createdBy\":424242,\"createdAt\":\"2000-01-01T00:00:00Z\","
                + "\"publishedBy\":555,\"publishedAt\":\"2000-01-01T00:00:00Z\","
                + "\"basedOnPublishedVersionId\":888,\"version\":777,\"configurationRevision\":666}";
        String json = mockMvc.perform(post(BASE + "/drafts")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.published").value(false))
                .andExpect(jsonPath("$.createdBy").value(3))
                .andExpect(jsonPath("$.publishedBy").doesNotExist())
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.configurationRevision").value(0))
                .andReturn().getResponse().getContentAsString();
        Long id = com.jayway.jsonpath.JsonPath.parse(json).read("$.id", Long.class);
        Integer versionNumber = com.jayway.jsonpath.JsonPath.parse(json).read("$.versionNumber", Integer.class);

        assertThat(id).isNotEqualTo(123456L);
        assertThat(versionNumber).isNotEqualTo(999);
        PolicyVersion persisted = policyVersionRepository.findById(id).orElseThrow();
        assertThat(persisted.isPublished()).isFalse();
        assertThat(persisted.getCreatedBy()).isEqualTo(3L);
        assertThat(persisted.getVersionNumber()).isNotEqualTo(999L);
        assertThat(persisted.getPublishedBy()).isNull();
        assertThat(persisted.getVersion()).isEqualTo(0L);
        assertThat(persisted.getConfigurationRevision()).isEqualTo(0L);
        assertThat(persisted.getBasedOnPublishedVersionId()).isNotEqualTo(888L);
    }
}
