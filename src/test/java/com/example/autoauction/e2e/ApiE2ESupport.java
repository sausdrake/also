package com.example.autoauction.e2e;

import com.example.autoauction.auth.domain.TestUserPrincipal;
import com.example.autoauction.diagnostic.media.infrastructure.storage.FakeStorageClient;
import com.example.autoauction.support.AbstractPostgresIntegrationTest;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import com.jayway.jsonpath.JsonPath;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Shared actors, HTTP and money helpers of the API end-to-end tests on PostgreSQL (E2E-02 and the
 * failure-path cycle). Users register through the API and act with their own JWTs; administrators
 * and diagnosticians cannot be created through the API, so they are stored directly and act through
 * the security test principal. The browser's upload to R2 is the shared FakeStorageClient.
 */
abstract class ApiE2ESupport extends AbstractPostgresIntegrationTest {

    private static final AtomicInteger IP = new AtomicInteger(1);
    private static final String PASSWORD = "e2e-password-1";

    @Autowired protected MockMvc mockMvc;
    @Autowired protected UserRepository userRepository;
    @Autowired protected RoleRepository roleRepository;
    @Autowired protected FakeStorageClient fakeStorageClient;

    protected final String run = Long.toString(System.nanoTime() % 1_000_000_000L, 36);

    // ------------------------------------------------------------------ actors

    protected record Actor(Long id, String username, RequestPostProcessor auth) {}

    private static RequestPostProcessor freshIp() {
        return request -> {
            request.setRemoteAddr("10.88." + (IP.get() / 250) + "." + (IP.getAndIncrement() % 250 + 1));
            return request;
        };
    }

    private static RequestPostProcessor bearer(String token) {
        return request -> {
            request.addHeader("Authorization", "Bearer " + token);
            return request;
        };
    }

    protected Actor staff(String prefix, String role) {
        Role r = roleRepository.findByName("ROLE_" + role)
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_" + role, role)));
        String name = prefix + run;
        Long id = userRepository.save(new User(null, name, name + "@e2e.test", "x", true, Set.of(r))).getId();
        return new Actor(id, name, SecurityMockMvcRequestPostProcessors.user(TestUserPrincipal.createWithRoles(id, name, role)));
    }

    protected Actor register(String prefix) throws Exception {
        roleRepository.findByName("ROLE_USER").orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "USER")));
        String name = prefix + run;
        MvcResult r = mockMvc.perform(post("/api/auth/register").with(freshIp()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + name + "\",\"email\":\"" + name + "@e2e.test\",\"password\":\"" + PASSWORD + "\"}"))
                .andReturn();
        assertThat(r.getResponse().getStatus()).as("register " + name + ": " + body(r)).isEqualTo(200);
        String token = JsonPath.read(body(r), "$.accessToken");
        Long id = userRepository.findByUsername(name).orElseThrow().getId();
        return new Actor(id, name, bearer(token));
    }

    // ------------------------------------------------------------------ http

    protected static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    protected String call(MockHttpServletRequestBuilder request, Actor who, int expectedStatus) throws Exception {
        MvcResult r = mockMvc.perform(request.with(who.auth())).andReturn();
        assertThat(r.getResponse().getStatus()).as(request.toString() + " -> " + body(r)).isEqualTo(expectedStatus);
        return body(r);
    }

    protected String postJson(String url, Actor who, String json, int expectedStatus) throws Exception {
        return call(post(url).contentType(MediaType.APPLICATION_JSON).content(json), who, expectedStatus);
    }

    protected static Long id(String json, String path) {
        return ((Number) JsonPath.read(json, path)).longValue();
    }

    // ------------------------------------------------------------------ money

    protected record Money(BigDecimal balance, BigDecimal blocked) {}

    protected Money money(Actor user) throws Exception {
        String json = call(get("/api/v1/deposits/my"), user, 200);
        return new Money(new BigDecimal(JsonPath.read(json, "$.balance").toString()),
                new BigDecimal(JsonPath.read(json, "$.blockedAmount").toString()));
    }

    protected void assertMoney(Actor user, String balance, String blocked) throws Exception {
        Money m = money(user);
        assertThat(m.balance()).as(user.username() + " balance").isEqualByComparingTo(balance);
        assertThat(m.blocked()).as(user.username() + " blocked").isEqualByComparingTo(blocked);
    }

    protected List<Map<String, Object>> ledger(Actor admin, Actor user) throws Exception {
        String json = call(get("/api/v1/admin/deposits/" + user.id() + "/transactions"), admin, 200);
        Object content = JsonPath.read(json, "$");
        return content instanceof List ? JsonPath.read(json, "$") : JsonPath.read(json, "$.content");
    }

    protected long ledgerCount(Actor admin, Actor user, String type) throws Exception {
        return ledger(admin, user).stream().filter(t -> type.equals(t.get("type"))).count();
    }

    protected void topUp(Actor admin, Actor user, String amount, String bankReference) throws Exception {
        postJson("/api/v1/admin/deposits/" + user.id() + "/adjust", admin,
                "{\"amount\":" + amount + ",\"reason\":\"RECONCILIATION\",\"reference\":\"" + bankReference
                        + "\",\"idempotencyKey\":\"" + bankReference + "-key\"}", 200);
    }

    // ------------------------------------------------------------------ steps

    private String capture(Actor user, String slotRequest, int marker) throws Exception {
        String key = JsonPath.read(postJson("/api/v1/identity/documents/upload-url", user, slotRequest, 200), "$.storageKey");
        fakeStorageClient.simulateUpload(key, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) marker}, "image/jpeg");
        return key;
    }

    protected void verifyIdentity(Actor user, Actor admin) throws Exception {
        // KYC-CAPTURE-01: an ID card is photographed front and back, plus the live selfie - each its own camera slot
        String doc = capture(user, "{\"contentType\":\"image/jpeg\",\"side\":\"FRONT\",\"documentType\":\"NATIONAL_ID\"}", 1);
        String back = capture(user, "{\"contentType\":\"image/jpeg\",\"side\":\"BACK\",\"documentType\":\"NATIONAL_ID\"}", 2);
        String selfie = capture(user, "{\"contentType\":\"image/jpeg\",\"side\":\"SELFIE\"}", 3);
        String submission = postJson("/api/v1/identity/submissions", user,
                "{\"issuingCountry\":\"KG\",\"documentType\":\"NATIONAL_ID\",\"rawDocumentNumber\":\"E2E" + user.id() + run
                        + "\",\"documentStorageKey\":\"" + doc + "\",\"documentBackStorageKey\":\"" + back
                        + "\",\"selfieStorageKey\":\"" + selfie + "\"}", 200);
        call(post("/api/v1/admin/identity/" + id(submission, "$.id") + "/approve"), admin, 200);
        assertThat((String) JsonPath.read(call(get("/api/v1/identity/me"), user, 200), "$.status")).isEqualTo("VERIFIED");
    }

    protected String vin() {
        String digits = Long.toString(System.nanoTime());
        return ("E2E" + run.toUpperCase().replaceAll("[IOQ]", "X") + digits + "00000000000000000").substring(0, 17);
    }
}
