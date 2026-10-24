package com.example.autoauction.e2e;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * LEGAL-DATA-01 / PRIVACY-REQ-01 through the HTTP API on PostgreSQL: versioned documents (maker-checker,
 * frozen text, one edition in force), acceptance of an exact version (registration, re-acceptance after a new
 * edition, the gate on protected operations), consent withdrawal, and the privacy-request workflow.
 * The tests share one database, so each scenario works on its own document type or checks relative state.
 */
@AutoConfigureMockMvc
@DisplayName("LEGAL-DATA-01: versioned documents, acceptance, privacy requests (PostgreSQL)")
@org.testcontainers.junit.jupiter.Testcontainers(disabledWithoutDocker = true)
class LegalAcceptanceE2EPgIntegrationTest extends ApiE2ESupport {

    /** a distinct address per registration, so the per-IP registration limit never interferes */
    private static final java.util.concurrent.atomic.AtomicInteger IP = new java.util.concurrent.atomic.AtomicInteger();

    @Autowired private JdbcTemplate jdbc;

    private Actor adminA;
    private Actor adminB;

    /** the PostgreSQL test database is shared: leave no document in force for the other test classes */
    @org.junit.jupiter.api.AfterEach
    void retireEverything() {
        jdbc.update("UPDATE legal_document_version SET status = 'RETIRED', retired_at = now() WHERE status IN ('ACTIVE', 'APPROVED')");
    }

    @BeforeEach
    void admins() {
        // isolation: no document is in force at the start of a scenario
        jdbc.update("UPDATE legal_document_version SET status = 'RETIRED', retired_at = now() WHERE status = 'ACTIVE'");
        adminA = staff("lgadmina", "ADMIN");
        adminB = staff("lgadminb", "ADMIN");
    }

    private Long draft(String type, String version, String kind) throws Exception {
        return id(postJson("/api/v1/admin/legal/documents", adminA, "{\"documentType\":\"" + type + "\",\"version\":\"" + version
                + "\",\"title\":\"" + type + " " + version + "\",\"acceptanceKind\":\"" + kind + "\",\"content\":\"Текст " + type
                + " " + version + "\"}", 201), "$.id");
    }

    /** A draft taken through review, approval by the other admin and activation. */
    private Long inForce(String type, String version, String kind) throws Exception {
        Long id = draft(type, version, kind);
        call(post("/api/v1/admin/legal/documents/" + id + "/submit"), adminA, 200);
        postJson("/api/v1/admin/legal/documents/" + id + "/approve", adminB, "{\"note\":\"Проверено юристом 24.10\"}", 200);
        call(post("/api/v1/admin/legal/documents/" + id + "/activate"), adminB, 200);
        return id;
    }

    private int registerStatus(String name, String idsJson) throws Exception {
        int ip = IP.incrementAndGet();
        MvcResult r = mockMvc.perform(post("/api/auth/register").with(req -> { req.setRemoteAddr("10.66." + (ip / 250) + "." + (ip % 250 + 1)); return req; })
                .header("User-Agent", "LegalE2E/1.0")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + name + "\",\"email\":\"" + name + "@e2e.test\",\"password\":\"e2e-password-1\""
                        + (idsJson == null ? "" : ",\"acceptedDocumentVersionIds\":" + idsJson) + "}"))
                .andReturn();
        return r.getResponse().getStatus();
    }

    private List<Number> requiredIds(Actor user) throws Exception {
        return JsonPath.read(call(get("/api/v1/legal/acceptance/required"), user, 200), "$[*].id");
    }

    private int withdrawalStatus(Actor user) throws Exception {
        return mockMvc.perform(post("/api/v1/withdrawals").with(user.auth()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":1000,\"idempotencyKey\":\"lg-" + System.nanoTime() + "\"}")).andReturn().getResponse().getStatus();
    }

    // ------------------------------------------------------------------ scenarios

    @Test
    @DisplayName("lifecycle: nothing public before activation; author cannot approve; frozen text; one edition in force")
    void lifecycle() throws Exception {
        assertThat(mockMvc.perform(get("/api/v1/public/legal/documents/KYC_POLICY")).andReturn().getResponse().getStatus()).isEqualTo(404);
        Long id = draft("KYC_POLICY", "1.0-" + run, "INFORMATIONAL");
        call(put("/api/v1/admin/legal/documents/" + id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"KYC\",\"acceptanceKind\":\"INFORMATIONAL\",\"content\":\"Исправленный текст\"}"), adminA, 200);
        assertThat(mockMvc.perform(get("/api/v1/public/legal/documents/KYC_POLICY")).andReturn().getResponse().getStatus())
                .as("a draft is never public").isEqualTo(404);

        call(post("/api/v1/admin/legal/documents/" + id + "/activate"), adminA, 409);   // not approved
        call(post("/api/v1/admin/legal/documents/" + id + "/submit"), adminA, 200);
        call(put("/api/v1/admin/legal/documents/" + id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"KYC\",\"acceptanceKind\":\"INFORMATIONAL\",\"content\":\"подмена\"}"), adminA, 409);
        postJson("/api/v1/admin/legal/documents/" + id + "/approve", adminA, "{\"note\":\"сам себе\"}", 409);   // maker-checker
        postJson("/api/v1/admin/legal/documents/" + id + "/approve", adminB, "{\"note\":\" \"}", 400);
        postJson("/api/v1/admin/legal/documents/" + id + "/approve", adminB, "{\"note\":\"Юрист, 24.10\"}", 200);
        assertThat(mockMvc.perform(get("/api/v1/public/legal/documents/KYC_POLICY")).andReturn().getResponse().getStatus())
                .as("approved is not yet in force").isEqualTo(404);
        call(post("/api/v1/admin/legal/documents/" + id + "/activate"), adminB, 200);

        String published = body(mockMvc.perform(get("/api/v1/public/legal/documents/KYC_POLICY")).andReturn());
        assertThat((String) JsonPath.read(published, "$.content")).isEqualTo("Исправленный текст");
        assertThat(published).doesNotContain("createdBy").doesNotContain("approvalNote");

        Long next = inForce("KYC_POLICY", "2.0-" + run, "INFORMATIONAL");
        assertThat((String) JsonPath.read(call(get("/api/v1/admin/legal/documents/" + id), adminA, 200), "$.status")).isEqualTo("RETIRED");
        assertThat(id(body(mockMvc.perform(get("/api/v1/public/legal/documents/KYC_POLICY")).andReturn()), "$.id")).isEqualTo(next);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM legal_document_version WHERE document_type = 'KYC_POLICY' AND status = 'ACTIVE'", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("registration: nothing required while nothing is in force; afterwards the exact versions must be ticked and are recorded")
    void registration() throws Exception {
        assertThat(registerStatus("lgfree" + run, null)).isEqualTo(200);

        Long terms = inForce("TERMS_OF_USE", "r-" + run, "ACCEPTANCE");
        Long privacy = inForce("PRIVACY_POLICY", "r-" + run, "ACKNOWLEDGEMENT");
        assertThat(registerStatus("lgnone" + run, null)).isEqualTo(400);
        assertThat(registerStatus("lghalf" + run, "[" + terms + "]")).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE username IN (?, ?)", Integer.class, "lgnone" + run, "lghalf" + run))
                .as("a refused registration creates no account").isZero();

        String name = "lgfull" + run;
        assertThat(registerStatus(name, "[" + terms + "," + privacy + "]")).isEqualTo(200);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT a.* FROM legal_acceptance a JOIN users u ON u.id = a.user_id WHERE u.username = ? ORDER BY a.document_type", name);
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.get("source")).isEqualTo("REGISTRATION");
            assertThat(r.get("status")).isEqualTo("ACCEPTED");
            assertThat((String) r.get("ip_address")).startsWith("10.66.");
            assertThat(r.get("user_agent")).isEqualTo("LegalE2E/1.0");
            assertThat((String) r.get("content_sha256")).hasSize(64);
        });
        assertThat(rows.get(0).get("kind")).isEqualTo("ACKNOWLEDGEMENT");   // PRIVACY_POLICY
        assertThat(rows.get(1).get("kind")).isEqualTo("ACCEPTANCE");        // TERMS_OF_USE
    }

    @Test
    @DisplayName("gate: an account without the current acceptance is refused protected operations until it accepts; a new edition asks again")
    void gateAndReAcceptance() throws Exception {
        Actor user = register("lggate");
        verifyIdentity(user, adminA);
        assertThat(withdrawalStatus(user)).as("nothing in force - no gate").isNotEqualTo(403);

        Long terms = inForce("TERMS_OF_USE", "g1-" + run, "ACCEPTANCE");
        assertThat(requiredIds(user)).extracting(Number::longValue).containsExactly(terms);
        MvcResult refused = mockMvc.perform(post("/api/v1/withdrawals").with(user.auth()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":1000,\"idempotencyKey\":\"lg-gate-" + run + "\"}")).andReturn();
        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat((String) JsonPath.read(refused.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.code")).isEqualTo("LEGAL_ACCEPTANCE_REQUIRED");
        postJson("/api/auctions/1/bids", user, "{\"amount\":1}", 403);

        // a draft or retired version cannot be accepted
        Long draft = draft("TERMS_OF_USE", "g-draft-" + run, "ACCEPTANCE");
        postJson("/api/v1/legal/acceptance", user, "{\"documentVersionIds\":[" + draft + "]}", 409);

        // a double submit records once
        CyclicBarrier start = new CyclicBarrier(2);
        Callable<Integer> acceptOnce = () -> {
            start.await();
            return mockMvc.perform(post("/api/v1/legal/acceptance").with(user.auth()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"documentVersionIds\":[" + terms + "]}")).andReturn().getResponse().getStatus();
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Integer> statuses = new ArrayList<>();
        try {
            for (Future<Integer> f : pool.invokeAll(List.of(acceptOnce, acceptOnce))) statuses.add(f.get());
        } finally {
            pool.shutdownNow();
        }
        assertThat(statuses).containsOnly(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM legal_acceptance WHERE user_id = ? AND document_version_id = ?", Integer.class, user.id(), terms)).isEqualTo(1);
        assertThat(requiredIds(user)).isEmpty();
        assertThat(withdrawalStatus(user)).isNotEqualTo(403);

        Long terms2 = inForce("TERMS_OF_USE", "g2-" + run, "ACCEPTANCE");
        assertThat(requiredIds(user)).extracting(Number::longValue).containsExactly(terms2);
        assertThat(withdrawalStatus(user)).isEqualTo(403);
        postJson("/api/v1/legal/acceptance", user, "{\"documentVersionIds\":[" + terms2 + "]}", 200);
        assertThat(withdrawalStatus(user)).isNotEqualTo(403);

        List<Map<String, Object>> mine = JsonPath.read(call(get("/api/v1/legal/acceptance/my"), user, 200), "$");
        assertThat(mine).hasSize(2);
        assertThat(call(get("/api/v1/legal/acceptance/my"), user, 200)).doesNotContain("ipAddress").doesNotContain("userAgent");
    }

    @Test
    @DisplayName("consent is optional and withdrawable; acceptance and acknowledgement are not withdrawable")
    void consentWithdrawal() throws Exception {
        Actor user = register("lgconsent");
        Actor other = register("lgother");
        Long consent = inForce("PERSONAL_DATA_PROCESSING_POLICY", "c-" + run, "CONSENT");
        assertThat(requiredIds(user)).doesNotContain(consent);
        Long acceptanceId = id(postJson("/api/v1/legal/acceptance", user, "{\"documentVersionIds\":[" + consent + "]}", 200), "$[0].id");
        String withdrawn = call(post("/api/v1/legal/acceptance/" + acceptanceId + "/withdraw"), user, 200);
        assertThat((String) JsonPath.read(withdrawn, "$.status")).isEqualTo("WITHDRAWN");

        Long terms = inForce("TERMS_OF_USE", "c-" + run, "ACCEPTANCE");
        Long termsAcceptance = id(postJson("/api/v1/legal/acceptance", user, "{\"documentVersionIds\":[" + terms + "]}", 200), "$[0].id");
        call(post("/api/v1/legal/acceptance/" + termsAcceptance + "/withdraw"), user, 409);
        call(post("/api/v1/legal/acceptance/" + acceptanceId + "/withdraw"), other, 404);   // not theirs: as if absent
    }

    @Test
    @DisplayName("access: staff endpoints are admin-only; the public list shows only versions in force")
    void access() throws Exception {
        Actor user = register("lgplain");
        call(get("/api/v1/admin/legal/documents"), user, 403);
        postJson("/api/v1/admin/legal/documents", user, "{\"documentType\":\"TERMS_OF_USE\",\"version\":\"x\",\"title\":\"x\","
                + "\"acceptanceKind\":\"ACCEPTANCE\",\"content\":\"x\"}", 403);
        draft("DIAGNOSTIC_RULES", "a-" + run, "INFORMATIONAL");
        String list = body(mockMvc.perform(get("/api/v1/public/legal/documents")).andReturn());
        assertThat(list).doesNotContain("a-" + run);
        assertThat(mockMvc.perform(get("/api/v1/legal/acceptance/required")).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("privacy requests: own requests only, admin answers once, the user sees the answer; flooding refused")
    void privacyRequests() throws Exception {
        Actor user = register("lgpr");
        Actor stranger = register("lgprx");
        Long requestId = id(postJson("/api/v1/privacy/requests", user, "{\"type\":\"DELETION\",\"message\":\"Удалите мой аккаунт\"}", 201), "$.id");
        postJson("/api/v1/privacy/requests", user, "{\"type\":\"DELETION\",\"message\":\" \"}", 400);
        assertThat(call(get("/api/v1/privacy/requests/my"), stranger, 200)).doesNotContain("Удалите мой аккаунт");
        call(get("/api/v1/admin/privacy/requests"), user, 403);
        postJson("/api/v1/admin/privacy/requests/" + requestId + "/answer", user, "{\"answer\":\"x\",\"rejected\":false}", 403);

        call(post("/api/v1/admin/privacy/requests/" + requestId + "/take"), adminA, 200);
        postJson("/api/v1/admin/privacy/requests/" + requestId + "/answer", adminA,
                "{\"answer\":\"Аккаунт деактивирован; финансовые записи хранятся по закону\",\"rejected\":false}", 200);
        postJson("/api/v1/admin/privacy/requests/" + requestId + "/answer", adminB, "{\"answer\":\"второй ответ\",\"rejected\":true}", 409);
        String mine = call(get("/api/v1/privacy/requests/my"), user, 200);
        assertThat((String) JsonPath.read(mine, "$[0].status")).isEqualTo("ANSWERED");
        assertThat((String) JsonPath.read(mine, "$[0].answer")).contains("деактивирован");
        assertThat(call(get("/api/notifications"), user, 200)).contains("PRIVACY_REQUEST_ANSWERED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE id = ?", Integer.class, user.id()))
                .as("a request changes nothing by itself").isEqualTo(1);

        for (int i = 0; i < 5; i++) {
            postJson("/api/v1/privacy/requests", user, "{\"type\":\"ACCESS\",\"message\":\"Запрос " + i + "\"}", 201);
        }
        postJson("/api/v1/privacy/requests", user, "{\"type\":\"ACCESS\",\"message\":\"Шестой\"}", 409);
    }
}
