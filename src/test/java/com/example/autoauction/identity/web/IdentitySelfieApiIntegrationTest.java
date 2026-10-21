package com.example.autoauction.identity.web;

import com.example.autoauction.auth.domain.TestUserPrincipal;
import com.example.autoauction.diagnostic.media.infrastructure.storage.FakeStorageClient;
import com.example.autoauction.identity.domain.DocumentType;
import com.example.autoauction.identity.domain.IdentityDocument;
import com.example.autoauction.identity.domain.IdentityVerification;
import com.example.autoauction.identity.domain.IdentityVerificationStatus;
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
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.OffsetDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVP-004: HTTP-layer proof that a KYC submission requires a separate live-selfie upload, that the
 * administrator can open the selfie next to the document before deciding, and that a submission
 * made before the selfie became mandatory cannot be approved (only rejected, so the user resubmits).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("MVP-004: identity submission with live selfie - API behaviour")
class IdentitySelfieApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private IdentityDocumentRepository documentRepository;
    @Autowired private IdentityVerificationRepository identityVerificationRepository;
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

    /** KYC-CAPTURE-01: a capture slot for one side (FRONT / BACK of an ID card, or SELFIE). */
    private String uploadedKey(Long userId, String side) throws Exception {
        String body = "SELFIE".equals(side)
                ? "{\"contentType\":\"image/jpeg\",\"side\":\"SELFIE\"}"
                : "{\"contentType\":\"image/jpeg\",\"side\":\"" + side + "\",\"documentType\":\"NATIONAL_ID\"}";
        String response = mockMvc.perform(post("/api/v1/identity/documents/upload-url")
                        .with(user(userId, "USER"))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String key = JsonPath.read(response, "$.storageKey");
        fakeStorageClient.simulateUpload(key, new byte[]{1, 2, 3}, "image/jpeg");
        return key;
    }

    private static String submission(String number, String documentKey, String backKey, String selfieKey) {
        return "{\"issuingCountry\":\"KG\",\"documentType\":\"NATIONAL_ID\",\"rawDocumentNumber\":\"" + number + "\","
                + "\"documentStorageKey\":\"" + documentKey + "\",\"documentBackStorageKey\":\"" + backKey + "\""
                + (selfieKey == null ? "" : ",\"selfieStorageKey\":\"" + selfieKey + "\"") + "}";
    }

    private static String number() {
        return "SF" + (System.nanoTime() % 10_000_000);
    }

    @Test
    @DisplayName("a submission without a selfie is refused with 400 and creates nothing")
    void submissionWithoutSelfie_400() throws Exception {
        Long userId = createUser("noselfie");
        String documentKey = uploadedKey(userId, "FRONT");

        mockMvc.perform(post("/api/v1/identity/submissions").with(user(userId, "USER"))
                        .contentType("application/json").content(submission(number(), documentKey, uploadedKey(userId, "BACK"), null)))
                .andExpect(status().isBadRequest());

        assertThat(identityVerificationRepository.findByUserId(userId)).isEmpty();
    }

    @Test
    @DisplayName("the document image re-sent as the selfie is refused with 400 and creates nothing")
    void documentReusedAsSelfie_400() throws Exception {
        Long userId = createUser("sameimage");
        String documentKey = uploadedKey(userId, "FRONT");

        mockMvc.perform(post("/api/v1/identity/submissions").with(user(userId, "USER"))
                        .contentType("application/json").content(submission(number(), documentKey, uploadedKey(userId, "BACK"), documentKey)))
                .andExpect(status().isBadRequest());

        assertThat(identityVerificationRepository.findByUserId(userId)).isEmpty();
    }

    @Test
    @DisplayName("full flow: document + selfie -> admin opens both images -> approve -> VERIFIED")
    void fullFlow_adminSeesSelfieAndApproves() throws Exception {
        Long userId = createUser("selfieflow");
        Long adminId = createUser("selfieadmin");
        String documentKey = uploadedKey(userId, "FRONT");
        String backKey = uploadedKey(userId, "BACK");
        String selfieKey = uploadedKey(userId, "SELFIE");

        String statusJson = mockMvc.perform(post("/api/v1/identity/submissions").with(user(userId, "USER"))
                        .contentType("application/json").content(submission(number(), documentKey, backKey, selfieKey)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.selfieProvided").value(true))
                .andExpect(jsonPath("$.documentBackProvided").value(true))
                .andReturn().getResponse().getContentAsString();
        Integer id = JsonPath.read(statusJson, "$.id");

        mockMvc.perform(get("/api/v1/admin/identity/{id}", id).with(user(adminId, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selfieProvided").value(true));
        String selfieUrl = JsonPath.read(mockMvc.perform(get("/api/v1/admin/identity/{id}/selfie-url", id)
                        .with(user(adminId, "ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.url");
        String documentUrl = JsonPath.read(mockMvc.perform(get("/api/v1/admin/identity/{id}/document-url", id)
                        .with(user(adminId, "ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.url");
        assertThat(selfieUrl).contains(selfieKey).isNotEqualTo(documentUrl);
        String backUrl = JsonPath.read(mockMvc.perform(get("/api/v1/admin/identity/{id}/document-back-url", id)
                        .with(user(adminId, "ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.url");
        assertThat(backUrl).contains(backKey).isNotEqualTo(documentUrl);

        mockMvc.perform(post("/api/v1/admin/identity/{id}/approve", id).with(user(adminId, "ADMIN")))
                .andExpect(status().isOk());
        assertThat(identityVerificationRepository.findByUserId(userId).orElseThrow().getStatus())
                .isEqualTo(IdentityVerificationStatus.VERIFIED);
    }

    @Test
    @DisplayName("the selfie URL is admin-only")
    void selfieUrl_plainUser_403() throws Exception {
        Long userId = createUser("selfieowner");
        String statusJson = mockMvc.perform(post("/api/v1/identity/submissions").with(user(userId, "USER"))
                        .contentType("application/json")
                        .content(submission(number(), uploadedKey(userId, "FRONT"), uploadedKey(userId, "BACK"), uploadedKey(userId, "SELFIE"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Integer id = JsonPath.read(statusJson, "$.id");

        mockMvc.perform(get("/api/v1/admin/identity/{id}/selfie-url", id).with(user(userId, "USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a pre-selfie PENDING_REVIEW submission cannot be approved (409), only rejected")
    void legacySubmissionWithoutSelfie_cannotBeApproved() throws Exception {
        Long userId = createUser("legacy");
        Long adminId = createUser("legacyadmin");
        OffsetDateTime now = OffsetDateTime.now();
        Long docId = documentRepository.save(new IdentityDocument(
                "identity-documents/legacy-" + System.nanoTime(), userId, "image/jpeg", 3L, now)).getId();
        IdentityVerification legacy = identityVerificationRepository.save(new IdentityVerification(null, userId,
                IdentityVerificationStatus.PENDING_REVIEW, "KG", DocumentType.NATIONAL_ID, number(), docId, docId, null,
                now, now, null));

        mockMvc.perform(get("/api/v1/admin/identity/{id}", legacy.getId()).with(user(adminId, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selfieProvided").value(false));
        mockMvc.perform(get("/api/v1/admin/identity/{id}/selfie-url", legacy.getId()).with(user(adminId, "ADMIN")))
                .andExpect(status().is4xxClientError());

        mockMvc.perform(post("/api/v1/admin/identity/{id}/approve", legacy.getId()).with(user(adminId, "ADMIN")))
                .andExpect(status().isConflict());
        assertThat(identityVerificationRepository.findByUserId(userId).orElseThrow().getStatus())
                .isEqualTo(IdentityVerificationStatus.PENDING_REVIEW);
    }
}
