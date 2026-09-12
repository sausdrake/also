package com.example.autoauction.identity.web;

import com.example.autoauction.auth.domain.TestUserPrincipal;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 4 §15 API-security tests: HTTP-layer proof for the self-service
 * {@code /api/v1/identity/**} endpoints - authentication required, self-access-only, and the
 * owner-never-sees-reviewer-identity visibility rule (Phase 3 §17). Mirrors {@code
 * DiagnosticMediaSecurityTest}'s own proven convention in this codebase
 * ({@code @SpringBootTest} + {@code @AutoConfigureMockMvc}, {@code TestUserPrincipal}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Identity self-service API - authentication and visibility")
class IdentityControllerApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;

    private Long createUser(String prefix) {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        User u = new User(null, prefix + System.nanoTime(), prefix + System.nanoTime() + "@test.com",
                "encoded", true, Set.of(role));
        return userRepository.save(u).getId();
    }

    private static RequestPostProcessor user(Long userId, String... roles) {
        return SecurityMockMvcRequestPostProcessors.user(TestUserPrincipal.createWithRoles(userId, "u" + userId, roles));
    }

    @Test
    @DisplayName("POST /documents/upload-url without authentication -> 401")
    void requestUploadUrl_Unauthenticated_401() throws Exception {
        mockMvc.perform(post("/api/v1/identity/documents/upload-url")
                        .contentType("application/json")
                        .content("{\"contentType\":\"image/jpeg\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /me without authentication -> 401")
    void getMyStatus_Unauthenticated_401() throws Exception {
        mockMvc.perform(get("/api/v1/identity/me")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("authenticated user with no submission yet -> 404 on GET /me")
    void getMyStatus_NoSubmission_404() throws Exception {
        Long userId = createUser("noidentity");
        mockMvc.perform(get("/api/v1/identity/me").with(user(userId, "USER")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /documents/upload-url -> server-generated key returned, never client-influenced")
    void requestUploadUrl_Authenticated_ReturnsServerGeneratedKey() throws Exception {
        Long userId = createUser("uploader");
        mockMvc.perform(post("/api/v1/identity/documents/upload-url")
                        .with(user(userId, "USER"))
                        .contentType("application/json")
                        .content("{\"contentType\":\"image/jpeg\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storageKey").value(org.hamcrest.Matchers.startsWith("identity-documents/")));
    }

    @Test
    @DisplayName("full self-service flow: upload-url -> submit -> GET /me reflects PENDING_REVIEW, own user id visible")
    void fullSelfServiceFlow_SubmitThenGetOwnStatus() throws Exception {
        Long userId = createUser("flowuser");
        String uploadResponse = mockMvc.perform(post("/api/v1/identity/documents/upload-url")
                        .with(user(userId, "USER"))
                        .contentType("application/json")
                        .content("{\"contentType\":\"image/jpeg\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String storageKey = com.jayway.jsonpath.JsonPath.read(uploadResponse, "$.storageKey");

        // Simulate the real browser upload directly into the shared FakeStorageClient bean.
        uploadFakeObject(storageKey);

        mockMvc.perform(post("/api/v1/identity/submissions")
                        .with(user(userId, "USER"))
                        .contentType("application/json")
                        .content("{\"issuingCountry\":\"KG\",\"documentType\":\"NATIONAL_ID\","
                                + "\"rawDocumentNumber\":\"AB1234567\",\"documentStorageKey\":\"" + storageKey + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.userId").value(userId));

        mockMvc.perform(get("/api/v1/identity/me").with(user(userId, "USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"));
    }

    @Test
    @DisplayName("own history never exposes reviewerAdminId, even after an admin decision")
    void getMyHistory_NeverExposesReviewerAdminId() throws Exception {
        Long userId = createUser("historyuser");
        Long adminId = createUser("historyadmin");
        String uploadResponse = mockMvc.perform(post("/api/v1/identity/documents/upload-url")
                        .with(user(userId, "USER"))
                        .contentType("application/json")
                        .content("{\"contentType\":\"image/jpeg\"}"))
                .andReturn().getResponse().getContentAsString();
        String storageKey = com.jayway.jsonpath.JsonPath.read(uploadResponse, "$.storageKey");
        uploadFakeObject(storageKey);

        mockMvc.perform(post("/api/v1/identity/submissions")
                .with(user(userId, "USER"))
                .contentType("application/json")
                .content("{\"issuingCountry\":\"KG\",\"documentType\":\"NATIONAL_ID\","
                        + "\"rawDocumentNumber\":\"CD7654321\",\"documentStorageKey\":\"" + storageKey + "\"}"));

        String statusJson = mockMvc.perform(get("/api/v1/identity/me").with(user(userId, "USER")))
                .andReturn().getResponse().getContentAsString();
        Integer ivId = com.jayway.jsonpath.JsonPath.read(statusJson, "$.id");

        mockMvc.perform(post("/api/v1/admin/identity/{id}/approve", ivId).with(user(adminId, "ADMIN")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/identity/me/history").with(user(userId, "USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[1].reviewerAdminId").doesNotExist());
    }

    private void uploadFakeObject(String storageKey) {
        fakeStorageClient.simulateUpload(storageKey, new byte[]{1, 2, 3}, "image/jpeg");
    }

    @Autowired
    private com.example.autoauction.diagnostic.media.infrastructure.storage.FakeStorageClient fakeStorageClient;
}
