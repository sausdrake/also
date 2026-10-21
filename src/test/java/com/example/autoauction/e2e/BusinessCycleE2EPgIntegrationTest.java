package com.example.autoauction.e2e;

import com.example.autoauction.auth.domain.TestUserPrincipal;
import com.example.autoauction.diagnostic.media.infrastructure.storage.FakeStorageClient;
import com.example.autoauction.support.AbstractPostgresIntegrationTest;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * E2E-02: the whole MVP business cycle through the HTTP API, with the money checked after every step:
 * registration -> KYC -> manual top-up (RECONCILIATION) -> seller's diagnostic request -> diagnostician's
 * inspection -> admin approval -> auction -> bids -> win -> sale (payment, handover, completion) ->
 * settlement payout to the seller -> withdrawals: one PAID, one REJECTED (returned exactly once).
 *
 * Buyers and the seller register through /api/auth/register and act with their own JWTs. Administrators
 * and the diagnostician cannot be created through the API (ADM-01 bootstraps the first admin), so they
 * are stored directly and act through the security test principal. The browser's upload to R2 is the
 * shared FakeStorageClient. Nothing else is stubbed.
 *
 * <p>Runs on PostgreSQL with the real migrations (V1..latest): system settings, the CHECK constraints
 * of the money tables and the row locks are the production ones - none of them exist on H2.
 */
@AutoConfigureMockMvc
@DisplayName("E2E-02: the MVP business cycle through the API (PostgreSQL)")
@org.testcontainers.junit.jupiter.Testcontainers(disabledWithoutDocker = true)
class BusinessCycleE2EPgIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final AtomicInteger IP = new AtomicInteger(1);
    private static final String PASSWORD = "e2e-password-1";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private FakeStorageClient fakeStorageClient;

    private final String run = Long.toString(System.nanoTime() % 1_000_000_000L, 36);

    // ------------------------------------------------------------------ actors

    private record Actor(Long id, String username, RequestPostProcessor auth) {}

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

    private Actor staff(String prefix, String role) {
        Role r = roleRepository.findByName("ROLE_" + role)
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_" + role, role)));
        String name = prefix + run;
        Long id = userRepository.save(new User(null, name, name + "@e2e.test", "x", true, Set.of(r))).getId();
        return new Actor(id, name, SecurityMockMvcRequestPostProcessors.user(TestUserPrincipal.createWithRoles(id, name, role)));
    }

    private Actor register(String prefix) throws Exception {
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

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String call(MockHttpServletRequestBuilder request, Actor who, int expectedStatus) throws Exception {
        MvcResult r = mockMvc.perform(request.with(who.auth())).andReturn();
        assertThat(r.getResponse().getStatus()).as(request.toString() + " -> " + body(r)).isEqualTo(expectedStatus);
        return body(r);
    }

    private String postJson(String url, Actor who, String json, int expectedStatus) throws Exception {
        return call(post(url).contentType(MediaType.APPLICATION_JSON).content(json), who, expectedStatus);
    }

    private static Long id(String json, String path) {
        return ((Number) JsonPath.read(json, path)).longValue();
    }

    // ------------------------------------------------------------------ money

    private record Money(BigDecimal balance, BigDecimal blocked) {}

    private Money money(Actor user) throws Exception {
        String json = call(get("/api/v1/deposits/my"), user, 200);
        return new Money(new BigDecimal(JsonPath.read(json, "$.balance").toString()),
                new BigDecimal(JsonPath.read(json, "$.blockedAmount").toString()));
    }

    private void assertMoney(Actor user, String balance, String blocked) throws Exception {
        Money m = money(user);
        assertThat(m.balance()).as(user.username() + " balance").isEqualByComparingTo(balance);
        assertThat(m.blocked()).as(user.username() + " blocked").isEqualByComparingTo(blocked);
    }

    private List<Map<String, Object>> ledger(Actor admin, Actor user) throws Exception {
        String json = call(get("/api/v1/admin/deposits/" + user.id() + "/transactions"), admin, 200);
        Object content = JsonPath.read(json, "$");
        return content instanceof List ? JsonPath.read(json, "$") : JsonPath.read(json, "$.content");
    }

    private long ledgerCount(Actor admin, Actor user, String type) throws Exception {
        return ledger(admin, user).stream().filter(t -> type.equals(t.get("type"))).count();
    }

    private void topUp(Actor admin, Actor user, String amount, String bankReference) throws Exception {
        postJson("/api/v1/admin/deposits/" + user.id() + "/adjust", admin,
                "{\"amount\":" + amount + ",\"reason\":\"RECONCILIATION\",\"reference\":\"" + bankReference
                        + "\",\"idempotencyKey\":\"" + bankReference + "-key\"}", 200);
    }

    // ------------------------------------------------------------------ steps

    private void verifyIdentity(Actor user, Actor admin) throws Exception {
        String doc = JsonPath.read(postJson("/api/v1/identity/documents/upload-url", user, "{\"contentType\":\"image/jpeg\"}", 200), "$.storageKey");
        fakeStorageClient.simulateUpload(doc, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1}, "image/jpeg");
        String selfie = JsonPath.read(postJson("/api/v1/identity/documents/upload-url", user, "{\"contentType\":\"image/jpeg\"}", 200), "$.storageKey");
        fakeStorageClient.simulateUpload(selfie, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 2}, "image/jpeg");
        String submission = postJson("/api/v1/identity/submissions", user,
                "{\"issuingCountry\":\"KG\",\"documentType\":\"NATIONAL_ID\",\"rawDocumentNumber\":\"E2E" + user.id() + run
                        + "\",\"documentStorageKey\":\"" + doc + "\",\"selfieStorageKey\":\"" + selfie + "\"}", 200);
        call(post("/api/v1/admin/identity/" + id(submission, "$.id") + "/approve"), admin, 200);
        assertThat((String) JsonPath.read(call(get("/api/v1/identity/me"), user, 200), "$.status")).isEqualTo("VERIFIED");
    }

    private String vin() {
        String digits = Long.toString(System.nanoTime());
        return ("E2E" + run.toUpperCase().replaceAll("[IOQ]", "X") + digits + "00000000000000000").substring(0, 17);
    }

    @Test
    @DisplayName("seller -> diagnostic -> auction -> win -> sale -> settlement -> withdrawals, money checked at every step")
    void fullBusinessCycle() throws Exception {
        // ---- platform set-up by two administrators (maker-checker for the commission policy)
        Actor adminA = staff("e2eadmina", "ADMIN");
        Actor adminB = staff("e2eadminb", "ADMIN");
        Actor diagnostician = staff("e2ediag", "DIAGNOSTIC");

        call(put("/api/v1/admin/settings").contentType(MediaType.APPLICATION_JSON)
                .content("{\"diagnostic_fee_amount\":\"4000\",\"diagnostic_deposit_amount\":\"10000\""
                        + ",\"auction_participation_deposit_amount\":\"20000\"}"), adminA, 200);   // DEPOSIT-01: global deposit
        String draft = postJson("/api/v1/admin/policy/drafts", adminA, "{\"commissionRate\":0.05}", 201);
        postJson("/api/v1/admin/policy/drafts/" + id(draft, "$.id") + "/publish", adminB,
                "{\"observedConfigurationRevision\":" + id(draft, "$.configurationRevision") + "}", 200);

        String template = postJson("/api/v1/admin/diagnostic/templates", adminA,
                "{\"name\":\"E2E " + run + "\",\"isActive\":true}", 201);
        Long templateId = id(template, "$.id");
        String group = postJson("/api/v1/admin/diagnostic/templates/" + templateId + "/groups", adminA,
                "{\"name\":\"Кузов\",\"sortOrder\":0}", 201);
        String item = postJson("/api/v1/admin/diagnostic/groups/" + id(group, "$.id") + "/items", adminA,
                "{\"title\":\"Состояние кузова\",\"fieldType\":\"RATING\",\"hasRating\":true,\"isRequired\":true}", 201);
        Long itemId = id(item, "$.id");

        // ---- registration and KYC
        Actor seller = register("e2eseller");
        Actor winner = register("e2ewinner");
        Actor loser = register("e2eloser");
        for (Actor u : List.of(seller, winner, loser)) {
            verifyIdentity(u, adminA);
            assertMoney(u, "0", "0");
        }

        // ---- manual top-up: one bank operation -> one RECONCILIATION credit
        topUp(adminA, seller, "50000", "BANK-" + run + "-S");
        topUp(adminA, winner, "300000", "BANK-" + run + "-W");
        topUp(adminA, loser, "300000", "BANK-" + run + "-L");
        assertMoney(seller, "50000", "0");
        assertMoney(winner, "300000", "0");

        // ---- seller's diagnostic request: the diagnostic deposit is frozen
        String request = postJson("/api/v1/diagnostic/requests", seller,
                "{\"brand\":\"Toyota\",\"model\":\"Camry\",\"year\":2020,\"vin\":\"" + vin() + "\"}", 201);
        Long requestId = id(request, "$.id");
        assertMoney(seller, "50000", "10000");

        // ---- the diagnostician inspects
        postJson("/api/v1/diagnostic/work/" + requestId + "/assign", diagnostician, "{}", 200);
        postJson("/api/v1/diagnostic/work/" + requestId + "/template", diagnostician, "{\"templateId\":" + templateId + "}", 200);
        postJson("/api/v1/diagnostic/work/" + requestId + "/answers", diagnostician,
                "{\"submit\":true,\"answers\":[{\"itemId\":" + itemId + ",\"rating\":4,\"comment\":\"Без повреждений\"}]}", 200);

        // ---- the administrator approves the report: a vehicle appears
        call(post("/api/v1/admin/diagnostic/reports/" + requestId + "/approve"), adminA, 200);
        String vehicles = call(get("/api/v1/admin/vehicles/approved"), adminA, 200);
        List<Number> vehicleIds = JsonPath.read(vehicles, "$[?(@.vin == '" + JsonPath.read(request, "$.vin") + "')].id");
        assertThat(vehicleIds).hasSize(1);
        Long vehicleId = vehicleIds.get(0).longValue();
        assertMoney(seller, "50000", "10000");

        // ---- the auction: created by the admin and started; the commitment links it to the seller
        OffsetDateTime now = OffsetDateTime.now();
        DateTimeFormatter iso = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
        String auction = postJson("/api/v1/admin/auctions", adminA, "{\"vehicleId\":" + vehicleId
                + ",\"startingPrice\":100000"
                + ",\"startTime\":\"" + now.plusMinutes(1).format(iso) + "\",\"endTime\":\"" + now.plusDays(3).format(iso)
                + "\",\"mainPhotoUrl\":\"https://media.example/e2e.jpg\"}", 201);
        Long auctionId = id(auction, "$.id");
        assertThat((String) JsonPath.read(auction, "$.status")).isEqualTo("CREATED");
        call(post("/api/v1/admin/auctions/" + auctionId + "/start"), adminA, 200);

        // ---- bids: each bidder's required deposit is frozen once; the seller cannot bid (SEC-09)
        for (Actor u : List.of(winner, loser, seller)) {   // the seller is trusted too: SEC-09 must still refuse
            call(patch("/api/v1/admin/users/" + u.id() + "/trust"), adminA, 200);
        }
        postJson("/api/auctions/" + auctionId + "/bids", loser, "{\"amount\":101000}", 200);
        assertMoney(loser, "300000", "20000");
        postJson("/api/auctions/" + auctionId + "/bids", winner, "{\"amount\":105000}", 200);
        assertMoney(winner, "300000", "20000");
        assertMoney(loser, "300000", "20000");
        MvcResult own = mockMvc.perform(post("/api/auctions/" + auctionId + "/bids").with(seller.auth())
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":110000}")).andReturn();
        assertThat(own.getResponse().getStatus()).as("the seller bids on their own vehicle").isEqualTo(400);
        assertMoney(seller, "50000", "10000");

        // ---- the admin ends the auction: the winner keeps the deposit frozen, the loser's is released
        String ended = call(post("/api/v1/admin/auctions/" + auctionId + "/complete"), adminA, 200);
        assertThat((String) JsonPath.read(ended, "$.status")).isEqualTo("WAITING_FOR_PAYMENT");
        assertThat(id(ended, "$.winnerId")).isEqualTo(winner.id());
        assertThat(new BigDecimal(JsonPath.read(ended, "$.winningBid").toString())).isEqualByComparingTo("105000");
        assertMoney(winner, "300000", "20000");
        assertMoney(loser, "300000", "0");

        // ---- the sale: paid outside the system, handed over, completed. The winner's deposit is released;
        // the seller's diagnostic commitment resolves as outcome A (successful sale: no fee, full release).
        postJson("/api/v1/admin/sale/" + auctionId + "/payment", adminA, "{\"amount\":105000}", 201);
        postJson("/api/v1/admin/sale/" + auctionId + "/handover", adminA, "{}", 201);
        String sold = postJson("/api/v1/admin/sale/" + auctionId + "/complete", adminA, "{}", 200);
        assertThat((String) JsonPath.read(sold, "$.result")).isEqualTo("SUCCESS");
        String sale = call(get("/api/v1/admin/sale/" + auctionId), adminA, 200);
        assertThat((String) JsonPath.read(sale, "$.auctionStatus")).isEqualTo("SOLD");
        assertThat(id(sale, "$.settlement.sellerId")).as("settlement goes to the vehicle owner").isEqualTo(seller.id());
        assertThat(new BigDecimal(JsonPath.read(sale, "$.settlement.expectedCommission").toString())).isEqualByComparingTo("5000");
        assertThat(new BigDecimal(JsonPath.read(sale, "$.settlement.expectedSellerAmount").toString())).isEqualByComparingTo("100000");
        assertThat((String) JsonPath.read(sale, "$.settlement.status")).isEqualTo("PENDING");
        assertMoney(winner, "300000", "0");
        assertMoney(loser, "300000", "0");
        assertMoney(seller, "50000", "0");

        // ---- settlement: AlSo paid the seller outside the system; recording it moves no deposit money
        String settled = postJson("/api/v1/admin/sale/" + auctionId + "/settlement/payout", adminA, "{\"attestedAmount\":100000}", 200);
        assertThat((String) JsonPath.read(settled, "$.status")).isNotEqualTo("PENDING");
        assertMoney(seller, "50000", "0");

        // ---- withdrawals: one paid by hand (PAID moves nothing more), one rejected (returned exactly once)
        String paidRequest = postJson("/api/v1/withdrawals", seller, "{\"amount\":20000,\"idempotencyKey\":\"wd-" + run + "-1\"}", 200);
        Long paidId = id(paidRequest, "$.id");
        assertMoney(seller, "30000", "0");
        call(post("/api/v1/admin/withdrawals/" + paidId + "/processing"), adminA, 200);
        String paid = postJson("/api/v1/admin/withdrawals/" + paidId + "/paid", adminB, "{\"payoutNote\":\"Перевод на счёт, п/п 77\"}", 200);
        assertThat((String) JsonPath.read(paid, "$.status")).isEqualTo("PAID");
        assertMoney(seller, "30000", "0");

        String rejectedRequest = postJson("/api/v1/withdrawals", seller, "{\"amount\":10000,\"idempotencyKey\":\"wd-" + run + "-2\"}", 200);
        Long rejectedId = id(rejectedRequest, "$.id");
        assertMoney(seller, "20000", "0");
        String rejected = postJson("/api/v1/admin/withdrawals/" + rejectedId + "/reject", adminA,
                "{\"reason\":\"Реквизиты не совпадают с KYC\",\"payoutNotMade\":true,\"expectedStatus\":\"PENDING\"}", 200);
        assertThat((String) JsonPath.read(rejected, "$.status")).isEqualTo("REJECTED");
        assertMoney(seller, "30000", "0");
        postJson("/api/v1/admin/withdrawals/" + rejectedId + "/reject", adminA,
                "{\"reason\":\"Повтор\",\"payoutNotMade\":true,\"expectedStatus\":\"PENDING\"}", 409);
        assertMoney(seller, "30000", "0");

        // ---- the ledger tells the same story: one credit per bank operation, one return per rejection
        assertThat(ledgerCount(adminA, seller, "ADMIN_ADJUSTMENT")).isEqualTo(1);
        assertThat(ledgerCount(adminA, seller, "WITHDRAWAL")).isEqualTo(2);
        assertThat(ledgerCount(adminA, seller, "WITHDRAWAL_RETURN")).isEqualTo(1);
        assertThat(ledgerCount(adminA, winner, "ADMIN_ADJUSTMENT")).isEqualTo(1);
    }
}
