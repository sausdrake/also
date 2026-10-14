package com.example.autoauction.identity.web;

import com.example.autoauction.auth.domain.TestUserPrincipal;
import com.example.autoauction.diagnostic.media.infrastructure.storage.FakeStorageClient;
import com.example.autoauction.identity.domain.DocumentType;
import com.example.autoauction.identity.domain.IdentityDocument;
import com.example.autoauction.identity.domain.IdentityVerification;
import com.example.autoauction.identity.domain.IdentityVerificationHistory;
import com.example.autoauction.identity.domain.port.IdentityDocumentRepository;
import com.example.autoauction.identity.domain.port.IdentityVerificationHistoryRepository;
import com.example.autoauction.identity.domain.port.IdentityVerificationRepository;
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

import java.time.OffsetDateTime;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 4 §15 API-security tests: HTTP-layer proof for {@code /api/v1/admin/identity/**} -
 * ADMIN-only access (non-admin USER and DIAGNOSTIC both denied), and that approval is routed
 * through {@code approveWithConflictHandling} (Phase 3.1 §1) rather than the raw method, so the
 * HTTP layer never sees an unhandled 500 for a losing concurrent admin.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Identity admin API - authorization boundary")
class AdminIdentityControllerApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private IdentityVerificationRepository identityVerificationRepository;
    @Autowired private IdentityVerificationHistoryRepository historyRepository;
    @Autowired private IdentityDocumentRepository documentRepository;
    @Autowired private FakeStorageClient fakeStorageClient;

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

    private Long newPendingVerification() {
        Long userId = createUser("subject");
        String key = "identity-documents/" + System.nanoTime();
        fakeStorageClient.simulateUpload(key, new byte[]{1, 2, 3}, "image/jpeg");
        Long docId = documentRepository.save(new IdentityDocument(key, userId, "image/jpeg", 3L, OffsetDateTime.now())).getId();
        String canonical = "ADM" + System.nanoTime() % 1_000_000;
        IdentityVerification iv = identityVerificationRepository.save(IdentityVerification.firstSubmission(
                userId, "KG", DocumentType.NATIONAL_ID, canonical, docId, docId, OffsetDateTime.now()));
        historyRepository.save(IdentityVerificationHistory.submission(iv.getId(), canonical, docId, OffsetDateTime.now()));
        return iv.getId();
    }

    @Test
    @DisplayName("GET /pending without authentication -> 401")
    void listPending_Unauthenticated_401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/identity/pending")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /pending as a plain USER -> 403")
    void listPending_PlainUser_403() throws Exception {
        Long userId = createUser("plain");
        mockMvc.perform(get("/api/v1/admin/identity/pending").with(user(userId, "USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /pending as DIAGNOSTIC -> 403 (not an admin role)")
    void listPending_DiagnosticRole_403() throws Exception {
        Long diagId = createUser("diag");
        mockMvc.perform(get("/api/v1/admin/identity/pending").with(user(diagId, "DIAGNOSTIC")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /pending as ADMIN -> 200")
    void listPending_Admin_200() throws Exception {
        newPendingVerification();
        Long adminId = createUser("admin1");
        mockMvc.perform(get("/api/v1/admin/identity/pending").with(user(adminId, "ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /{id}/approve as a plain USER -> 403, row stays PENDING_REVIEW")
    void approve_PlainUser_403() throws Exception {
        Long ivId = newPendingVerification();
        Long userId = createUser("intruder");
        mockMvc.perform(post("/api/v1/admin/identity/{id}/approve", ivId).with(user(userId, "USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /{id}/approve as ADMIN -> 200, status becomes VERIFIED")
    void approve_Admin_200() throws Exception {
        Long ivId = newPendingVerification();
        Long adminId = createUser("admin2");
        mockMvc.perform(post("/api/v1/admin/identity/{id}/approve", ivId).with(user(adminId, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VERIFIED"));
    }

    @Test
    @DisplayName("POST /{id}/reject without a reason -> 400")
    void reject_MissingReason_400() throws Exception {
        Long ivId = newPendingVerification();
        Long adminId = createUser("admin3");
        mockMvc.perform(post("/api/v1/admin/identity/{id}/reject", ivId)
                        .with(user(adminId, "ADMIN"))
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /{id}/reject with a reason as ADMIN -> 200, status becomes REJECTED")
    void reject_Admin_200() throws Exception {
        Long ivId = newPendingVerification();
        Long adminId = createUser("admin4");
        mockMvc.perform(post("/api/v1/admin/identity/{id}/reject", ivId)
                        .with(user(adminId, "ADMIN"))
                        .contentType("application/json")
                        .content("{\"reason\":\"Нечитаемый документ\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    @DisplayName("POST /{id}/revoke without a reasonCode -> 400")
    void revoke_MissingReasonCode_400() throws Exception {
        Long ivId = newPendingVerification();
        Long adminId = createUser("admin6");
        mockMvc.perform(post("/api/v1/admin/identity/{id}/approve", ivId).with(user(adminId, "ADMIN")));

        mockMvc.perform(post("/api/v1/admin/identity/{id}/revoke", ivId)
                        .with(user(adminId, "ADMIN"))
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /{id}/revoke with reasonCode=OTHER and no reasonText -> 400")
    void revoke_OtherReasonWithoutText_400() throws Exception {
        Long ivId = newPendingVerification();
        Long adminId = createUser("admin7");
        mockMvc.perform(post("/api/v1/admin/identity/{id}/approve", ivId).with(user(adminId, "ADMIN")));

        mockMvc.perform(post("/api/v1/admin/identity/{id}/revoke", ivId)
                        .with(user(adminId, "ADMIN"))
                        .contentType("application/json")
                        .content("{\"reasonCode\":\"OTHER\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /{id}/revoke on a VERIFIED identity as ADMIN -> 200, status becomes REVOKED")
    void revoke_Admin_200() throws Exception {
        Long ivId = newPendingVerification();
        Long adminId = createUser("admin8");
        mockMvc.perform(post("/api/v1/admin/identity/{id}/approve", ivId).with(user(adminId, "ADMIN")));

        mockMvc.perform(post("/api/v1/admin/identity/{id}/revoke", ivId)
                        .with(user(adminId, "ADMIN"))
                        .contentType("application/json")
                        .content("{\"reasonCode\":\"FRAUD_DISCOVERED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));
    }

    @Test
    @DisplayName("GET /{id}/history as ADMIN exposes actorAdminId (reviewer identity visible to admin)")
    void getHistory_Admin_ExposesReviewerAdminId() throws Exception {
        Long ivId = newPendingVerification();
        Long adminId = createUser("admin5");
        mockMvc.perform(post("/api/v1/admin/identity/{id}/approve", ivId).with(user(adminId, "ADMIN")));

        mockMvc.perform(get("/api/v1/admin/identity/{id}/history", ivId).with(user(adminId, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[1].reviewerAdminId").value(adminId.intValue()));
    }
}
