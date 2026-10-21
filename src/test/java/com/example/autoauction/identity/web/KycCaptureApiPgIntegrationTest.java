package com.example.autoauction.identity.web;

import com.example.autoauction.auth.domain.TestUserPrincipal;
import com.example.autoauction.diagnostic.media.infrastructure.storage.FakeStorageClient;
import com.example.autoauction.identity.domain.DocumentType;
import com.example.autoauction.identity.domain.IdentityCaptureSide;
import com.example.autoauction.identity.domain.IdentityDocument;
import com.example.autoauction.identity.domain.IdentityVerification;
import com.example.autoauction.identity.domain.IdentityVerificationStatus;
import com.example.autoauction.identity.domain.port.IdentityDocumentRepository;
import com.example.autoauction.identity.domain.port.IdentityVerificationRepository;
import com.example.autoauction.support.AbstractPostgresIntegrationTest;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * KYC-CAPTURE-01 on real PostgreSQL (V62): the backend - not the frontend - enforces the camera-capture
 * contract. ID card / driver licence = FRONT + BACK, passport = DATA_PAGE only; every capture is a JPEG slot
 * of the authenticated user, issued for one side and document type, valid 15 minutes, usable once.
 * Every refusal leaves no verification row and consumes nothing.
 */
@AutoConfigureMockMvc
@DisplayName("KYC-CAPTURE-01: camera-only document capture - server enforcement (real PostgreSQL)")
class KycCaptureApiPgIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private IdentityDocumentRepository documentRepository;
    @Autowired private IdentityVerificationRepository identityVerificationRepository;
    @Autowired private FakeStorageClient storage;
    @Autowired private JdbcTemplate jdbc;

    private Long newUser() {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        String name = "kcap" + System.nanoTime();
        return userRepository.save(new User(null, name, name + "@test.com", "encoded", true, Set.of(role))).getId();
    }

    private static RequestPostProcessor as(Long userId, String... roles) {
        return SecurityMockMvcRequestPostProcessors.user(TestUserPrincipal.createWithRoles(userId, "u" + userId, roles));
    }

    private ResultActions slot(Long userId, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/identity/documents/upload-url").with(as(userId, "USER"))
                .contentType("application/json").content(body));
    }

    /** A capture slot for {@code side} (of {@code type}, null for the selfie), filled with JPEG bytes. */
    private String capture(Long userId, String side, String type) throws Exception {
        String body = type == null
                ? "{\"contentType\":\"image/jpeg\",\"side\":\"" + side + "\"}"
                : "{\"contentType\":\"image/jpeg\",\"side\":\"" + side + "\",\"documentType\":\"" + type + "\"}";
        String key = JsonPath.read(slot(userId, body).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.storageKey");
        storage.simulateUpload(key, new byte[]{(byte) 0xFF, (byte) 0xD8, 1}, "image/jpeg");
        return key;
    }

    private static String number() {
        return "KC" + (System.nanoTime() % 100_000_000);
    }

    private ResultActions submit(Long userId, String type, String front, String back, String selfie) throws Exception {
        return submit(userId, type, number(), front, back, selfie);
    }

    private ResultActions submit(Long userId, String type, String number, String front, String back, String selfie) throws Exception {
        String body = "{\"issuingCountry\":\"KG\",\"documentType\":\"" + type + "\",\"rawDocumentNumber\":\"" + number + "\""
                + (front == null ? "" : ",\"documentStorageKey\":\"" + front + "\"")
                + (back == null ? "" : ",\"documentBackStorageKey\":\"" + back + "\"")
                + (selfie == null ? "" : ",\"selfieStorageKey\":\"" + selfie + "\"") + "}";
        return mockMvc.perform(post("/api/v1/identity/submissions").with(as(userId, "USER"))
                .contentType("application/json").content(body));
    }

    private boolean consumed(String key) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT consumed_at IS NOT NULL FROM identity_document WHERE storage_key = ?", Boolean.class, key));
    }

    private void assertNothingCreated(Long userId, String... keys) {
        assertThat(identityVerificationRepository.findByUserId(userId)).isEmpty();
        for (String key : keys) {
            if (key != null) {
                assertThat(consumed(key)).as("capture " + key + " must stay unused").isFalse();
            }
        }
    }

    // ---------- happy paths ----------

    @Test
    @DisplayName("ID card: FRONT + BACK + selfie -> PENDING_REVIEW, both sides stored, every capture consumed, history keeps the back")
    void idCardFrontAndBack() throws Exception {
        Long u = newUser();
        String front = capture(u, "FRONT", "NATIONAL_ID"), back = capture(u, "BACK", "NATIONAL_ID"), selfie = capture(u, "SELFIE", null);

        submit(u, "NATIONAL_ID", front, back, selfie).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.documentBackProvided").value(true));

        IdentityVerification iv = identityVerificationRepository.findByUserId(u).orElseThrow();
        assertThat(documentRepository.findById(iv.getCurrentDocumentId()).orElseThrow().getStorageKey()).isEqualTo(front);
        assertThat(documentRepository.findById(iv.getCurrentDocumentBackId()).orElseThrow().getStorageKey()).isEqualTo(back);
        assertThat(consumed(front) && consumed(back) && consumed(selfie)).isTrue();
        assertThat(jdbc.queryForObject("SELECT document_back_reference_id FROM identity_verification_history "
                + "WHERE identity_verification_id = ? AND event_type = 'SUBMISSION'", Long.class, iv.getId()))
                .isEqualTo(iv.getCurrentDocumentBackId());
    }

    @Test
    @DisplayName("driver licence: FRONT + BACK -> accepted")
    void driverLicenceFrontAndBack() throws Exception {
        Long u = newUser();
        submit(u, "DRIVER_LICENSE", capture(u, "FRONT", "DRIVER_LICENSE"), capture(u, "BACK", "DRIVER_LICENSE"),
                capture(u, "SELFIE", null))
                .andExpect(status().isOk()).andExpect(jsonPath("$.documentType").value("DRIVER_LICENSE"));
    }

    @Test
    @DisplayName("passport: DATA_PAGE only -> accepted, no back side anywhere")
    void passportDataPageOnly() throws Exception {
        Long u = newUser();
        submit(u, "INTERNATIONAL_PASSPORT", capture(u, "DATA_PAGE", "INTERNATIONAL_PASSPORT"), null, capture(u, "SELFIE", null))
                .andExpect(status().isOk()).andExpect(jsonPath("$.documentBackProvided").value(false));
        assertThat(identityVerificationRepository.findByUserId(u).orElseThrow().getCurrentDocumentBackId()).isNull();
    }

    @Test
    @DisplayName("the capture rule is served by the backend (the frontend never hard-codes it)")
    void captureRequirements() throws Exception {
        Long u = newUser();
        mockMvc.perform(get("/api/v1/identity/capture-requirements").with(as(u, "USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.documentType=='NATIONAL_ID')].sides[*]").value(org.hamcrest.Matchers.contains("FRONT", "BACK")))
                .andExpect(jsonPath("$[?(@.documentType=='DRIVER_LICENSE')].sides[*]").value(org.hamcrest.Matchers.contains("FRONT", "BACK")))
                .andExpect(jsonPath("$[?(@.documentType=='INTERNATIONAL_PASSPORT')].sides[*]").value(org.hamcrest.Matchers.contains("DATA_PAGE")));
    }

    // ---------- A / B: missing back ----------

    @ParameterizedTest(name = "{0}: FRONT without BACK -> 400")
    @ValueSource(strings = {"NATIONAL_ID", "DRIVER_LICENSE"})
    @DisplayName("A/B: ID card / driver licence with the FRONT only is refused")
    void frontOnlyRefused(String type) throws Exception {
        Long u = newUser();
        String front = capture(u, "FRONT", type), selfie = capture(u, "SELFIE", null);
        submit(u, type, front, null, selfie).andExpect(status().isBadRequest());
        assertNothingCreated(u, front, selfie);
    }

    // ---------- C: passport with a back ----------

    @Test
    @DisplayName("C: passport DATA_PAGE + a BACK capture is refused; a BACK slot cannot even be issued for a passport")
    void passportWithBackRefused() throws Exception {
        Long u = newUser();
        String page = capture(u, "DATA_PAGE", "INTERNATIONAL_PASSPORT"), selfie = capture(u, "SELFIE", null);
        String idBack = capture(u, "BACK", "NATIONAL_ID");
        submit(u, "INTERNATIONAL_PASSPORT", page, idBack, selfie).andExpect(status().isBadRequest());
        assertNothingCreated(u, page, selfie, idBack);
        slot(u, "{\"contentType\":\"image/jpeg\",\"side\":\"BACK\",\"documentType\":\"INTERNATIONAL_PASSPORT\"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("C: passport submitted with ID-card FRONT (+ BACK) captures instead of the data page is refused")
    void passportWithFrontAndBackRefused() throws Exception {
        Long u = newUser();
        String front = capture(u, "FRONT", "NATIONAL_ID"), back = capture(u, "BACK", "NATIONAL_ID"), selfie = capture(u, "SELFIE", null);
        submit(u, "INTERNATIONAL_PASSPORT", front, back, selfie).andExpect(status().isBadRequest());
        submit(u, "INTERNATIONAL_PASSPORT", back, null, selfie).andExpect(status().isBadRequest());
        assertNothingCreated(u, front, back, selfie);
    }

    // ---------- D: IDOR ----------

    @Test
    @DisplayName("D: user A submitting user B's capture -> 403; B's capture stays unused and B can still submit it")
    void foreignCaptureRefused() throws Exception {
        Long a = newUser(), b = newUser();
        String bFront = capture(b, "FRONT", "NATIONAL_ID");
        String aBack = capture(a, "BACK", "NATIONAL_ID"), aSelfie = capture(a, "SELFIE", null);

        submit(a, "NATIONAL_ID", bFront, aBack, aSelfie).andExpect(status().isForbidden());
        assertNothingCreated(a, bFront, aBack, aSelfie);

        submit(b, "NATIONAL_ID", bFront, capture(b, "BACK", "NATIONAL_ID"), capture(b, "SELFIE", null)).andExpect(status().isOk());
    }

    // ---------- E: expired ----------

    @Test
    @DisplayName("E: a capture older than its 15-minute window is refused")
    void expiredCaptureRefused() throws Exception {
        Long u = newUser();
        String stale = "identity-documents/stale-" + System.nanoTime();
        documentRepository.save(new IdentityDocument(null, stale, u, "image/jpeg", null, OffsetDateTime.now().minusMinutes(16),
                IdentityCaptureSide.FRONT, DocumentType.NATIONAL_ID, null));
        storage.simulateUpload(stale, new byte[]{1}, "image/jpeg");
        String back = capture(u, "BACK", "NATIONAL_ID"), selfie = capture(u, "SELFIE", null);

        submit(u, "NATIONAL_ID", stale, back, selfie).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Срок действия снимка истёк")));
        assertNothingCreated(u, stale, back, selfie);
    }

    // ---------- F: replay ----------

    @Test
    @DisplayName("F: the same captures re-submitted after a rejection -> 409; a resubmission needs new photos")
    void replayRefused() throws Exception {
        Long u = newUser(), admin = newUser();
        String front = capture(u, "FRONT", "NATIONAL_ID"), back = capture(u, "BACK", "NATIONAL_ID"), selfie = capture(u, "SELFIE", null);
        String num = number();
        submit(u, "NATIONAL_ID", num, front, back, selfie).andExpect(status().isOk());
        Long id = identityVerificationRepository.findByUserId(u).orElseThrow().getId();
        mockMvc.perform(post("/api/v1/admin/identity/{id}/reject", id).with(as(admin, "ADMIN"))
                .contentType("application/json").content("{\"reason\":\"нечитаемо\"}")).andExpect(status().isOk());

        submit(u, "NATIONAL_ID", num, front, back, selfie).andExpect(status().isConflict());
        assertThat(identityVerificationRepository.findByUserId(u).orElseThrow().getStatus()).isEqualTo(IdentityVerificationStatus.REJECTED);

        submit(u, "NATIONAL_ID", num, capture(u, "FRONT", "NATIONAL_ID"), capture(u, "BACK", "NATIONAL_ID"), capture(u, "SELFIE", null))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING_REVIEW"));
    }

    @RepeatedTest(3)
    @DisplayName("F: the same request sent twice at once -> exactly one submission, the captures consumed once")
    void concurrentDoubleSubmit() throws Exception {
        Long u = newUser();
        String front = capture(u, "FRONT", "NATIONAL_ID"), back = capture(u, "BACK", "NATIONAL_ID"), selfie = capture(u, "SELFIE", null);
        String num = number();
        CountDownLatch go = new CountDownLatch(1);
        List<Integer> codes = new ArrayList<>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Thread t = new Thread(() -> {
                try {
                    go.await();
                    int code = submit(u, "NATIONAL_ID", num, front, back, selfie).andReturn().getResponse().getStatus();
                    synchronized (codes) { codes.add(code); }
                } catch (Exception e) {
                    synchronized (codes) { codes.add(-1); }
                }
            });
            t.start(); threads.add(t);
        }
        go.countDown();
        for (Thread t : threads) t.join(30_000);

        assertThat(codes).hasSize(2).containsOnlyOnce(200);
        assertThat(codes).allMatch(c -> c == 200 || c == 409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_verification_history h JOIN identity_verification v "
                + "ON v.id = h.identity_verification_id WHERE v.user_id = ?", Integer.class, u)).isEqualTo(1);
    }

    // ---------- G: invalid side ----------

    @ParameterizedTest(name = "slot {0} -> 400")
    @ValueSource(strings = {
            "{\"contentType\":\"image/jpeg\",\"side\":\"TOP\",\"documentType\":\"NATIONAL_ID\"}",
            "{\"contentType\":\"image/jpeg\",\"side\":\"DATA_PAGE\",\"documentType\":\"NATIONAL_ID\"}",
            "{\"contentType\":\"image/jpeg\",\"side\":\"FRONT\",\"documentType\":\"INTERNATIONAL_PASSPORT\"}",
            "{\"contentType\":\"image/jpeg\",\"side\":\"FRONT\"}",
            "{\"contentType\":\"image/jpeg\",\"side\":\"SELFIE\",\"documentType\":\"NATIONAL_ID\"}",
            "{\"contentType\":\"image/jpeg\",\"side\":\"FRONT\",\"documentType\":\"MILITARY_ID\"}",
            "{\"contentType\":\"image/jpeg\"}",
            "{\"contentType\":\"application/pdf\",\"side\":\"FRONT\",\"documentType\":\"NATIONAL_ID\"}",
            "{\"contentType\":\"image/png\",\"side\":\"FRONT\",\"documentType\":\"NATIONAL_ID\"}"
    })
    @DisplayName("G/I: an invalid side, a side the document does not have, or a non-camera content type gets no slot")
    void invalidSlotRefused(String body) throws Exception {
        Long u = newUser();
        long before = jdbc.queryForObject("SELECT count(*) FROM identity_document WHERE uploader_user_id = ?", Long.class, u);
        slot(u, body).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_document WHERE uploader_user_id = ?", Long.class, u)).isEqualTo(before);
    }

    @Test
    @DisplayName("G: captures swapped between steps (BACK as FRONT, selfie as BACK, FRONT as selfie) are refused")
    void swappedSidesRefused() throws Exception {
        Long u = newUser();
        String front = capture(u, "FRONT", "NATIONAL_ID"), back = capture(u, "BACK", "NATIONAL_ID"), selfie = capture(u, "SELFIE", null);
        submit(u, "NATIONAL_ID", back, front, selfie).andExpect(status().isBadRequest());
        submit(u, "NATIONAL_ID", front, selfie, back).andExpect(status().isBadRequest());
        submit(u, "NATIONAL_ID", front, back, front).andExpect(status().isBadRequest());
        assertNothingCreated(u, front, back, selfie);
    }

    // ---------- H / I: bypassing the frontend ----------

    @Test
    @DisplayName("H: direct API - no document key / a never-uploaded slot (400), an arbitrary key (404) -> nothing created")
    void directApiBypassRefused() throws Exception {
        Long u = newUser();
        String back = capture(u, "BACK", "NATIONAL_ID"), selfie = capture(u, "SELFIE", null);
        submit(u, "NATIONAL_ID", null, back, selfie).andExpect(status().isBadRequest());

        String neverUploaded = JsonPath.read(slot(u, "{\"contentType\":\"image/jpeg\",\"side\":\"FRONT\",\"documentType\":\"NATIONAL_ID\"}")
                .andReturn().getResponse().getContentAsString(), "$.storageKey");
        submit(u, "NATIONAL_ID", neverUploaded, back, selfie).andExpect(status().isBadRequest());
        // an unknown key is "not found" in the existing error contract (GlobalExceptionHandler: 404)
        submit(u, "NATIONAL_ID", "identity-documents/made-up", back, selfie).andExpect(status().isNotFound());
        assertNothingCreated(u, back, selfie, neverUploaded);
    }

    @Test
    @DisplayName("I: a file from the generic media upload or a PNG put into a camera slot is not a KYC capture")
    void genericUploadAndForeignFileRefused() throws Exception {
        Long u = newUser();
        String back = capture(u, "BACK", "NATIONAL_ID"), selfie = capture(u, "SELFIE", null);
        submit(u, "NATIONAL_ID", "http://localhost:8080/uploads/" + System.nanoTime() + ".jpg", back, selfie)
                .andExpect(status().isNotFound());   // not an identity capture at all

        String slotKey = JsonPath.read(slot(u, "{\"contentType\":\"image/jpeg\",\"side\":\"FRONT\",\"documentType\":\"NATIONAL_ID\"}")
                .andReturn().getResponse().getContentAsString(), "$.storageKey");
        storage.simulateUpload(slotKey, new byte[]{(byte) 0x89, 'P', 'N', 'G'}, "image/png");
        submit(u, "NATIONAL_ID", slotKey, back, selfie).andExpect(status().isBadRequest());
        assertNothingCreated(u, back, selfie, slotKey);
    }

    // ---------- J: wrong document type ----------

    @Test
    @DisplayName("J: ID-card captures submitted as a driver licence (and vice versa) are refused")
    void wrongDocumentTypeRefused() throws Exception {
        Long u = newUser();
        String front = capture(u, "FRONT", "NATIONAL_ID"), back = capture(u, "BACK", "NATIONAL_ID"), selfie = capture(u, "SELFIE", null);
        submit(u, "DRIVER_LICENSE", front, back, selfie).andExpect(status().isBadRequest());
        String dlFront = capture(u, "FRONT", "DRIVER_LICENSE");
        submit(u, "NATIONAL_ID", dlFront, back, selfie).andExpect(status().isBadRequest());
        assertNothingCreated(u, front, back, selfie, dlFront);
    }

    // ---------- admin ----------

    @Test
    @DisplayName("admin: a legacy pending ID card without a back side cannot be approved (409); the back URL is admin-only")
    void legacyWithoutBackNotApprovable() throws Exception {
        Long u = newUser(), admin = newUser();
        OffsetDateTime now = OffsetDateTime.now();
        Long doc = documentRepository.save(new IdentityDocument("identity-documents/legacy-" + System.nanoTime(), u, "image/jpeg", 3L, now)).getId();
        IdentityVerification legacy = identityVerificationRepository.save(new IdentityVerification(null, u,
                IdentityVerificationStatus.PENDING_REVIEW, "KG", DocumentType.NATIONAL_ID, number(), doc, null, doc, now, now, null));

        mockMvc.perform(get("/api/v1/admin/identity/{id}", legacy.getId()).with(as(admin, "ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.documentBackProvided").value(false));
        mockMvc.perform(post("/api/v1/admin/identity/{id}/approve", legacy.getId()).with(as(admin, "ADMIN")))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/v1/admin/identity/{id}/document-back-url", legacy.getId()).with(as(u, "USER")))
                .andExpect(status().isForbidden());
        assertThat(identityVerificationRepository.findByUserId(u).orElseThrow().getStatus()).isEqualTo(IdentityVerificationStatus.PENDING_REVIEW);
    }
}
