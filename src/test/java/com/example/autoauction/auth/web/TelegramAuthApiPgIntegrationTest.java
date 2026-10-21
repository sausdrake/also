package com.example.autoauction.auth.web;

import com.example.autoauction.auth.application.ExternalIdentityResolver;
import com.example.autoauction.auth.domain.UserPrincipal;
import com.example.autoauction.auth.telegram.FakeTelegramProvider;
import com.example.autoauction.auth.telegram.TelegramTestKeys;
import com.example.autoauction.support.AbstractPostgresIntegrationTest;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import com.jayway.jsonpath.JsonPath;
import io.jsonwebtoken.SignatureAlgorithm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AUTH-TELEGRAM-01 on real PostgreSQL (V63), through the HTTP API, with a fake Telegram that enforces PKCE S256 and
 * single-use codes and signs tokens with throw-away keys. Covers the test matrix T1-T25 plus the KYC / trusted gates.
 */
@AutoConfigureMockMvc
@DisplayName("AUTH-TELEGRAM-01: Telegram sign-in and linking (real PostgreSQL)")
class TelegramAuthApiPgIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final AtomicInteger IP = new AtomicInteger(1);

    @Autowired private MockMvc mockMvc;
    @Autowired private FakeTelegramProvider telegram;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;

    // ---------- helpers ----------

    /** Each round trip from its own address, so the per-IP limiter never interferes with the assertions. */
    private static MockHttpServletRequestBuilder fromIp(MockHttpServletRequestBuilder b, String ip) {
        return b.with(r -> { r.setRemoteAddr(ip); return r; });
    }

    private static String nextIp() {
        int n = IP.getAndIncrement();
        return "10.77." + (n / 250) + "." + (n % 250 + 1);
    }

    private static String binding() {
        return UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
    }

    private static String sub() {
        return "tg-" + UUID.randomUUID();
    }

    record Flow(String ip, String binding, String url) {}

    private Flow startLogin(boolean includePhone) throws Exception {
        String ip = nextIp(), binding = binding();
        String json = mockMvc.perform(fromIp(post("/api/auth/telegram/authorize"), ip).contentType("application/json")
                        .content("{\"binding\":\"" + binding + "\",\"includePhone\":" + includePhone + "}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new Flow(ip, binding, JsonPath.read(json, "$.authorizationUrl"));
    }

    private ResultActions callback(Flow flow, Map<String, String> codeState) throws Exception {
        return callback(flow.ip(), codeState.get("code"), codeState.get("state"), flow.binding());
    }

    private ResultActions callback(String ip, String code, String state, String binding) throws Exception {
        return mockMvc.perform(fromIp(post("/api/auth/telegram/callback"), ip).contentType("application/json")
                .content("{\"code\":\"" + code + "\",\"state\":\"" + state + "\",\"binding\":\"" + binding + "\"}"));
    }

    /** Full sign-in for {@code subject}; returns the AlSo access token. */
    private String signIn(String subject, Map<String, Object> claims) throws Exception {
        Flow flow = startLogin(false);
        String json = callback(flow, telegram.approve(flow.url(), subject, claims)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.accessToken");
    }

    private static Map<String, Object> payload(String jwt) {
        return JsonPath.parse(new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8)).json();
    }

    private static long userIdOf(String jwt) {
        return ((Number) payload(jwt).get("userId")).longValue();
    }

    private long identities(String subject) {
        return jdbc.queryForObject("SELECT count(*) FROM external_identity WHERE provider='TELEGRAM' AND provider_subject=?", Long.class, subject);
    }

    private long users() {
        return jdbc.queryForObject("SELECT count(*) FROM users", Long.class);
    }

    private Role role(String name) {
        return roleRepository.findByName(name).orElseGet(() -> roleRepository.save(new Role(null, name, name)));
    }

    private User passwordUser(String role) {
        String name = "pw" + System.nanoTime();
        return userRepository.save(new User(null, name, name + "@test.com", passwordEncoder.encode("secret-123"), true, Set.of(role(role))));
    }

    private static UserPrincipal principal(User u) {
        return new UserPrincipal(u.getUsername(), "x", u.getRoles().stream()
                .map(r -> new org.springframework.security.core.authority.SimpleGrantedAuthority(r.getName())).toList(), u.getId());
    }

    private Flow startLink(User u) throws Exception {
        String ip = nextIp(), binding = binding();
        String json = mockMvc.perform(fromIp(post("/api/v1/account/telegram/link/authorize"), ip)
                        .with(SecurityMockMvcRequestPostProcessors.user(principal(u)))
                        .contentType("application/json").content("{\"binding\":\"" + binding + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new Flow(ip, binding, JsonPath.read(json, "$.authorizationUrl"));
    }

    private ResultActions completeLink(User u, Flow flow, Map<String, String> codeState) throws Exception {
        return mockMvc.perform(fromIp(post("/api/v1/account/telegram/link/complete"), flow.ip())
                .with(SecurityMockMvcRequestPostProcessors.user(principal(u))).contentType("application/json")
                .content("{\"code\":\"" + codeState.get("code") + "\",\"state\":\"" + codeState.get("state")
                        + "\",\"binding\":\"" + flow.binding() + "\"}"));
    }

    // ---------- T1 / T2 / T15 / T16 / T19 / KYC / trusted ----------

    @Test
    @DisplayName("T1/T15/T16/T19: first Telegram sign-in creates a plain USER account - no email, no phone, no password, untrusted, no KYC")
    void firstSignInCreatesAccount() throws Exception {
        String subject = sub();
        Flow flow = startLogin(false);
        Map<String, String> query = FakeTelegramProvider.query(flow.url());
        assertThat(query).containsEntry("response_type", "code").containsEntry("code_challenge_method", "S256")
                .containsEntry("client_id", TelegramTestKeys.CLIENT_ID).containsEntry("scope", "openid profile")
                .containsKeys("state", "nonce", "code_challenge");
        assertThat(flow.url()).doesNotContain("secret");

        String jwt = JsonPath.read(callback(flow, telegram.approve(flow.url(), subject, null)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.accessToken");
        long userId = userIdOf(jwt);
        assertThat(payload(jwt).get("roles")).isEqualTo(List.of("ROLE_USER"));

        User user = userRepository.findById(userId).orElseThrow();
        assertThat(user.getEmail()).isNull();
        assertThat(user.getPasswordHash()).isEqualTo(ExternalIdentityResolver.NO_PASSWORD);
        assertThat(user.getUsername()).startsWith("user_").doesNotContain("test_user");
        assertThat(user.isTrusted()).isFalse();
        assertThat(user.getRoles()).extracting(Role::getName).containsExactly("ROLE_USER");
        assertThat(identities(subject)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_verification WHERE user_id=?", Long.class, userId)).isZero();
        assertThat(jdbc.queryForObject("SELECT balance FROM deposits WHERE user_id=?", java.math.BigDecimal.class, userId)).isZero();

        // Telegram authentication is NOT KYC: the KYC-gated withdrawal refuses this account
        mockMvc.perform(post("/api/v1/withdrawals").header("Authorization", "Bearer " + jwt).contentType("application/json")
                        .content("{\"amount\":100,\"idempotencyKey\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("IDENTITY_VERIFICATION_REQUIRED"));
        // the issued token is an ordinary AlSo token: protected endpoints accept it
        mockMvc.perform(get("/api/v1/account/telegram").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk()).andExpect(jsonPath("$.linked").value(true))
                .andExpect(jsonPath("$.botCanMessage").value(false));
    }

    @Test
    @DisplayName("T2/T13/T14: the same Telegram identity again -> the same account, even with a new name and @username")
    void sameIdentitySameAccount() throws Exception {
        String subject = sub();
        long first = userIdOf(signIn(subject, Map.of("preferred_username", "old_handle", "name", "Old Name")));
        long before = users();
        long second = userIdOf(signIn(subject, Map.of("preferred_username", "new_handle", "name", "New Name")));
        assertThat(second).isEqualTo(first);
        assertThat(users()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT provider_username FROM external_identity WHERE provider_subject=?", String.class, subject))
                .isEqualTo("new_handle");
    }

    @RepeatedTest(3)
    @DisplayName("T3: two simultaneous first sign-ins of one identity -> one account, one identity, both get tokens for it")
    void concurrentFirstSignIn() throws Exception {
        String subject = sub();
        Flow a = startLogin(false), b = startLogin(false);
        Map<String, String> ca = telegram.approve(a.url(), subject, null), cb = telegram.approve(b.url(), subject, null);
        long before = users();
        CountDownLatch go = new CountDownLatch(1);
        List<Long> ids = new ArrayList<>();
        List<Thread> threads = new ArrayList<>();
        for (Object[] f : new Object[][]{{a, ca}, {b, cb}}) {
            Thread t = new Thread(() -> {
                try {
                    go.await();
                    String json = callback((Flow) f[0], (Map<String, String>) f[1]).andReturn().getResponse().getContentAsString();
                    synchronized (ids) { ids.add(userIdOf(JsonPath.read(json, "$.accessToken"))); }
                } catch (Throwable e) {
                    synchronized (ids) { ids.add(-1L); }
                }
            });
            t.start(); threads.add(t);
        }
        go.countDown();
        for (Thread t : threads) t.join(30_000);
        assertThat(ids).hasSize(2).doesNotContain(-1L);
        assertThat(ids.get(0)).isEqualTo(ids.get(1));
        assertThat(identities(subject)).isEqualTo(1);
        assertThat(users()).isEqualTo(before + 1);
    }

    // ---------- T4-T8 through the API ----------

    @Test
    @DisplayName("T4-T8 via API: forged / wrong-issuer / wrong-audience / expired / wrong-nonce tokens -> 401, no account")
    void invalidTokensCreateNothing() throws Exception {
        Instant now = Instant.now();
        String subject = sub();
        List<String> tokens = List.of(
                TelegramTestKeys.builder(subject, now, null).setHeaderParam("kid", TelegramTestKeys.RSA_KID)
                        .signWith(TelegramTestKeys.FOREIGN_RSA.getPrivate(), SignatureAlgorithm.RS256).compact(),
                TelegramTestKeys.idToken(subject, now, Map.of("iss", "https://evil.example")),
                TelegramTestKeys.idToken(subject, now, Map.of("aud", "9999")),
                TelegramTestKeys.idToken(subject, now.minusSeconds(7200), null),
                TelegramTestKeys.idToken(subject, now, Map.of("nonce", "not-ours")));
        long before = users();
        for (String token : tokens) {
            Flow flow = startLogin(false);
            callback(flow, telegram.approveWithToken(flow.url(), token))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("TELEGRAM_INVALID_TOKEN"));
        }
        assertThat(users()).isEqualTo(before);
        assertThat(identities(subject)).isZero();
    }

    // ---------- T9 / T10 / PKCE ----------

    @Test
    @DisplayName("T9: unknown state, another browser's binding, an expired state, a link state at the login callback -> 400")
    void invalidState() throws Exception {
        Flow flow = startLogin(false);
        Map<String, String> cs = telegram.approve(flow.url(), sub(), null);
        callback(flow.ip(), cs.get("code"), "forged-state", flow.binding())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("TELEGRAM_INVALID_STATE"));
        callback(flow.ip(), cs.get("code"), cs.get("state"), binding())       // login CSRF: victim's browser, attacker's code
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("TELEGRAM_INVALID_STATE"));

        Flow expired = startLogin(false);
        Map<String, String> ce = telegram.approve(expired.url(), sub(), null);
        jdbc.update("UPDATE oidc_authorization_request SET expires_at = now() - interval '1 second' WHERE consumed_at IS NULL "
                + "AND binding_hash = encode(sha256(convert_to(?, 'UTF8')), 'hex')", expired.binding());
        callback(expired, ce).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("TELEGRAM_INVALID_STATE"));

        User u = passwordUser("ROLE_USER");
        Flow link = startLink(u);
        callback(link, telegram.approve(link.url(), sub(), null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("TELEGRAM_INVALID_STATE"));
    }

    @Test
    @DisplayName("T10: a replayed callback (same code + state) is refused and creates nothing more")
    void replay() throws Exception {
        Flow flow = startLogin(false);
        Map<String, String> cs = telegram.approve(flow.url(), sub(), null);
        callback(flow, cs).andExpect(status().isOk());
        long before = users();
        callback(flow, cs).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("TELEGRAM_INVALID_STATE"));
        assertThat(users()).isEqualTo(before);
    }

    @Test
    @DisplayName("PKCE: a code issued for another round trip (other challenge) cannot be redeemed with this state")
    void codeFromAnotherRoundTrip() throws Exception {
        Flow mine = startLogin(false), other = startLogin(false);
        Map<String, String> otherCode = telegram.approve(other.url(), sub(), null);
        Map<String, String> myState = telegram.approve(mine.url(), sub(), null);
        callback(mine.ip(), otherCode.get("code"), myState.get("state"), mine.binding())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("TELEGRAM_INVALID_CODE"));
    }

    // ---------- T11 / T12 / T23 / T24 linking ----------

    @Test
    @DisplayName("T11/T12/T25: Telegram linked to password user A signs in as A; user B cannot claim it; A's password still works")
    void linking() throws Exception {
        User a = passwordUser("ROLE_USER"), b = passwordUser("ROLE_USER");
        String subject = sub();
        Flow la = startLink(a);
        completeLink(a, la, telegram.approve(la.url(), subject, null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.linked").value(true));
        assertThat(userIdOf(signIn(subject, null))).isEqualTo(a.getId());

        Flow lb = startLink(b);
        completeLink(b, lb, telegram.approve(lb.url(), subject, null))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TELEGRAM_IDENTITY_CONFLICT"));
        assertThat(jdbc.queryForObject("SELECT user_id FROM external_identity WHERE provider_subject=?", Long.class, subject)).isEqualTo(a.getId());

        Flow again = startLink(a);                       // A already has a Telegram: another one is refused
        completeLink(a, again, telegram.approve(again.url(), sub(), null)).andExpect(status().isConflict());

        mockMvc.perform(fromIp(post("/api/auth/login"), nextIp()).contentType("application/json")
                        .content("{\"username\":\"" + a.getUsername() + "\",\"password\":\"secret-123\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("T23/T24: linking needs authentication; user B cannot finish a link round trip started by user A")
    void linkingOwnership() throws Exception {
        mockMvc.perform(post("/api/v1/account/telegram/link/authorize").contentType("application/json")
                .content("{\"binding\":\"" + binding() + "\"}")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/account/telegram/link/complete").contentType("application/json")
                .content("{\"code\":\"c\",\"state\":\"s\",\"binding\":\"" + binding() + "\"}")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/account/telegram")).andExpect(status().isUnauthorized());

        User a = passwordUser("ROLE_USER"), b = passwordUser("ROLE_USER");
        Flow la = startLink(a);
        completeLink(b, la, telegram.approve(la.url(), sub(), null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("TELEGRAM_INVALID_STATE"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM external_identity WHERE user_id IN (?, ?)", Long.class, a.getId(), b.getId())).isZero();
    }

    // ---------- T19 / T20 roles ----------

    @Test
    @DisplayName("T19/T20: admin and diagnostician accounts cannot link Telegram; a linked user later made admin cannot sign in with Telegram")
    void privilegedAccounts() throws Exception {
        for (String role : List.of("ROLE_ADMIN", "ROLE_DIAGNOSTIC")) {
            User privileged = passwordUser(role);
            mockMvc.perform(fromIp(post("/api/v1/account/telegram/link/authorize"), nextIp())
                            .with(SecurityMockMvcRequestPostProcessors.user(principal(privileged)))
                            .contentType("application/json").content("{\"binding\":\"" + binding() + "\"}"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TELEGRAM_LOGIN_NOT_ALLOWED"));
        }
        String subject = sub();
        long userId = userIdOf(signIn(subject, null));
        jdbc.update("INSERT INTO user_roles(user_id, role_id) SELECT ?, id FROM roles WHERE name='ROLE_ADMIN'", userId);
        Flow flow = startLogin(false);
        callback(flow, telegram.approve(flow.url(), subject, null))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TELEGRAM_LOGIN_NOT_ALLOWED"));
    }

    @Test
    @DisplayName("T20: Telegram sign-in never changes the account's roles or trust")
    void rolesAndTrustUntouched() throws Exception {
        User a = passwordUser("ROLE_USER");
        jdbc.update("UPDATE users SET trusted = true WHERE id = ?", a.getId());
        String subject = sub();
        Flow la = startLink(a);
        completeLink(a, la, telegram.approve(la.url(), subject, Map.of("roles", List.of("ROLE_ADMIN")))).andExpect(status().isOk());
        String jwt = signIn(subject, Map.of("roles", List.of("ROLE_ADMIN")));
        assertThat(payload(jwt).get("roles")).isEqualTo(List.of("ROLE_USER"));
        User after = userRepository.findById(a.getId()).orElseThrow();
        assertThat(after.isTrusted()).isTrue();
        assertThat(after.getRoles()).extracting(Role::getName).containsExactly("ROLE_USER");
    }

    // ---------- T17 / T18 phone ----------

    @Test
    @DisplayName("T17/T18: an opted-in verified phone is stored (never returned); without it sign-in still works")
    void phoneScope() throws Exception {
        String subject = sub();
        Flow flow = startLogin(true);
        assertThat(FakeTelegramProvider.query(flow.url())).containsEntry("scope", "openid profile phone");
        String json = callback(flow, telegram.approve(flow.url(), subject,
                Map.of("phone_number", "+996700123456", "phone_number_verified", true))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String jwt = JsonPath.read(json, "$.accessToken");
        assertThat(jdbc.queryForObject("SELECT phone_number FROM external_identity WHERE provider_subject=?", String.class, subject))
                .isEqualTo("+996700123456");
        String status = mockMvc.perform(get("/api/v1/account/telegram").header("Authorization", "Bearer " + jwt))
                .andExpect(jsonPath("$.phoneVerified").value(true)).andReturn().getResponse().getContentAsString();
        assertThat(status).doesNotContain("996700123456").doesNotContain(subject);
        assertThat(json).doesNotContain(subject);

        String other = sub();
        signIn(other, null);
        assertThat(jdbc.queryForObject("SELECT phone_number FROM external_identity WHERE provider_subject=?", String.class, other)).isNull();
    }

    // ---------- T21 / T22 / T25 ----------

    @Test
    @DisplayName("T21/T22: the browser cannot impersonate an identity - extra telegramId / userId / id_token fields are refused")
    void cannotImpersonate() throws Exception {
        Flow flow = startLogin(false);
        Map<String, String> cs = telegram.approve(flow.url(), sub(), null);
        long before = users();
        for (String extra : List.of("\"telegramId\":\"999\"", "\"userId\":1", "\"idToken\":\"x.y.z\"", "\"sub\":\"tg-victim\"")) {
            mockMvc.perform(fromIp(post("/api/auth/telegram/callback"), flow.ip()).contentType("application/json")
                    .content("{\"code\":\"" + cs.get("code") + "\",\"state\":\"" + cs.get("state") + "\",\"binding\":\""
                            + flow.binding() + "\"," + extra + "}"))
                    .andExpect(status().isBadRequest());
        }
        assertThat(users()).isEqualTo(before);
    }

    @Test
    @DisplayName("T25: a Telegram-created account has no usable password - password login is refused for any password")
    void telegramAccountHasNoPassword() throws Exception {
        long userId = userIdOf(signIn(sub(), null));
        String username = userRepository.findById(userId).orElseThrow().getUsername();
        for (String password : List.of(ExternalIdentityResolver.NO_PASSWORD, "secret-123", "!no-password")) {
            mockMvc.perform(fromIp(post("/api/auth/login"), nextIp()).contentType("application/json")
                            .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    @DisplayName("status endpoint: enabled with a configured client; secrets never in any public response")
    void statusAndNoSecrets() throws Exception {
        String body = mockMvc.perform(get("/api/auth/telegram/status")).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true)).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("test-secret");
        assertThat(startLogin(false).url()).doesNotContain("test-secret");
    }

    @Test
    @DisplayName("an inactive (blocked) account cannot sign in with Telegram")
    void blockedAccount() throws Exception {
        String subject = sub();
        long userId = userIdOf(signIn(subject, null));
        jdbc.update("UPDATE users SET active = false WHERE id = ?", userId);
        Flow flow = startLogin(false);
        callback(flow, telegram.approve(flow.url(), subject, null)).andExpect(status().isUnauthorized());
    }
}
