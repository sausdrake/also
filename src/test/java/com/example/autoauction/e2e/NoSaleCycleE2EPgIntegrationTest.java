package com.example.autoauction.e2e;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * AUC-NOSALE-01 (approved policy) through the HTTP API on PostgreSQL, money checked after every step:
 * an auction that ends without a sale returns the seller's guarantee exactly once and sends the vehicle to
 * re-diagnostic.
 * <ul>
 *   <li>EXPIRED without bids and EXPIRED with the reserve not met: full return, bidders released;</li>
 *   <li>CANCELLED before the start and while active, without seller fault: full return;</li>
 *   <li>CANCELLED for a confirmed seller violation: the policy penalty withheld once, the rest returned;</li>
 *   <li>repeated and concurrent admin commands, and the repair of an auction stranded before this change;</li>
 *   <li>the NO_SALE re-diagnostic, after which the same vehicle can be auctioned again.</li>
 * </ul>
 * Invariant checked throughout: guarantee = penalty withheld + returned + still frozen.
 */
@AutoConfigureMockMvc
@DisplayName("AUC-NOSALE-01: EXPIRED / CANCELLED release the seller guarantee once (PostgreSQL)")
@org.testcontainers.junit.jupiter.Testcontainers(disabledWithoutDocker = true)
class NoSaleCycleE2EPgIntegrationTest extends ApiE2ESupport {

    private static final String PENALISED = "SELLER_UNREACHABLE";   // 30 % of the guarantee in this policy

    @Autowired private AuctionRepository auctionRepository;

    private Actor adminA;
    private Actor adminB;
    private Actor diagnostician;
    private Long templateId;
    private Long itemId;
    private Map<String, Long> violationIds;

    @BeforeEach
    void platform() throws Exception {
        adminA = staff("nsadmina", "ADMIN");
        adminB = staff("nsadminb", "ADMIN");
        diagnostician = staff("nsdiag", "DIAGNOSTIC");

        call(put("/api/v1/admin/settings").contentType(MediaType.APPLICATION_JSON)
                .content("{\"diagnostic_fee_amount\":\"4000\",\"diagnostic_deposit_amount\":\"10000\""
                        + ",\"auction_participation_deposit_amount\":\"20000\"}"), adminA, 200);

        String catalogue = call(get("/api/v1/admin/policy/violation-definitions"), adminA, 200);
        List<Map<String, Object>> definitions = JsonPath.read(catalogue, "$");
        violationIds = new java.util.HashMap<>();
        definitions.forEach(d -> violationIds.put((String) d.get("code"), ((Number) d.get("id")).longValue()));
        String draft = postJson("/api/v1/admin/policy/drafts", adminA, "{\"commissionRate\":0.05}", 201);
        Long draftId = id(draft, "$.id");
        for (Map.Entry<String, Long> v : violationIds.entrySet()) {
            List<Number> versions = JsonPath.read(draft, "$.violations[?(@.violationDefinitionId == " + v.getValue() + ")].version");
            long version = versions.isEmpty() ? 0 : versions.get(0).longValue();
            String value = PENALISED.equals(v.getKey()) ? "30" : "10";
            draft = call(put("/api/v1/admin/policy/drafts/" + draftId + "/violations/" + v.getValue())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"penaltyType\":\"PERCENTAGE\",\"penaltyValue\":" + value + ",\"version\":" + version + "}"), adminA, 200);
        }
        postJson("/api/v1/admin/policy/drafts/" + draftId + "/publish", adminB,
                "{\"observedConfigurationRevision\":" + id(draft, "$.configurationRevision") + "}", 200);

        String template = postJson("/api/v1/admin/diagnostic/templates", adminA, "{\"name\":\"NS " + run + System.nanoTime() + "\",\"isActive\":true}", 201);
        templateId = id(template, "$.id");
        String group = postJson("/api/v1/admin/diagnostic/templates/" + templateId + "/groups", adminA,
                "{\"name\":\"Кузов\",\"sortOrder\":0}", 201);
        itemId = id(postJson("/api/v1/admin/diagnostic/groups/" + id(group, "$.id") + "/items", adminA,
                "{\"title\":\"Состояние кузова\",\"fieldType\":\"RATING\",\"hasRating\":true,\"isRequired\":true}", 201), "$.id");
    }

    // ------------------------------------------------------------------ steps

    private Actor verifiedUser(String prefix, String topUp) throws Exception {
        Actor u = register(prefix);
        verifyIdentity(u, adminA);
        topUp(adminA, u, topUp, "BANK-" + run + "-" + prefix);
        return u;
    }

    private void inspect(Long requestId) throws Exception {
        postJson("/api/v1/diagnostic/work/" + requestId + "/assign", diagnostician, "{}", 200);
        postJson("/api/v1/diagnostic/work/" + requestId + "/template", diagnostician, "{\"templateId\":" + templateId + "}", 200);
        postJson("/api/v1/diagnostic/work/" + requestId + "/answers", diagnostician,
                "{\"submit\":true,\"answers\":[{\"itemId\":" + itemId + ",\"rating\":4,\"comment\":\"Осмотрено\"}]}", 200);
    }

    private String vehicleStatus(Long vehicleId) throws Exception {
        return JsonPath.read(call(get("/api/v1/admin/vehicles/" + vehicleId), adminA, 200), "$.status");
    }

    private record Lot(Long auctionId, Long vehicleId, Long requestId) {}

    /** Approved diagnostic of the seller's car, auction created (not started); reserve optional. */
    private Lot createdLot(Actor seller, String reserveJson) throws Exception {
        Long requestId = id(postJson("/api/v1/diagnostic/requests", seller,
                "{\"brand\":\"Toyota\",\"model\":\"Camry\",\"year\":2020,\"vin\":\"" + vin() + "\"}", 201), "$.id");
        assertMoney(seller, "50000", "10000");
        inspect(requestId);
        call(post("/api/v1/admin/diagnostic/reports/" + requestId + "/approve"), adminA, 200);
        String vehicleVin = JsonPath.read(call(get("/api/v1/admin/diagnostic/reports/" + requestId), adminA, 200), "$.vin");
        List<Number> vehicleIds = JsonPath.read(call(get("/api/v1/admin/vehicles/approved"), adminA, 200),
                "$[?(@.vin == '" + vehicleVin + "')].id");
        assertThat(vehicleIds).hasSize(1);
        return new Lot(createAuction(vehicleIds.get(0).longValue(), reserveJson, 201), vehicleIds.get(0).longValue(), requestId);
    }

    private Long createAuction(Long vehicleId, String reserveJson, int expectedStatus) throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        DateTimeFormatter iso = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
        String json = postJson("/api/v1/admin/auctions", adminA, "{\"vehicleId\":" + vehicleId
                + ",\"startingPrice\":100000" + reserveJson + ",\"startTime\":\"" + now.plusMinutes(1).format(iso)
                + "\",\"endTime\":\"" + now.plusDays(3).format(iso) + "\",\"mainPhotoUrl\":\"https://media.example/ns.jpg\"}",
                expectedStatus);
        return expectedStatus == 201 ? id(json, "$.id") : null;
    }

    private void startWithBids(Lot lot, Actor... bidders) throws Exception {
        call(post("/api/v1/admin/auctions/" + lot.auctionId() + "/start"), adminA, 200);
        assertThat(vehicleStatus(lot.vehicleId())).isEqualTo("IN_AUCTION");
        long amount = 101000;
        for (Actor u : bidders) {
            call(patch("/api/v1/admin/users/" + u.id() + "/trust"), adminA, 200);
            postJson("/api/auctions/" + lot.auctionId() + "/bids", u, "{\"amount\":" + amount + "}", 200);
            amount += 4000;
        }
    }

    private String auctionStatus(Long auctionId) throws Exception {
        return JsonPath.read(call(get("/api/v1/admin/auctions/" + auctionId), adminA, 200), "$.status");
    }

    private List<Integer> concurrently(MockHttpServletRequestBuilder a, MockHttpServletRequestBuilder b) throws Exception {
        CyclicBarrier start = new CyclicBarrier(2);
        List<Callable<Integer>> calls = new ArrayList<>();
        for (MockHttpServletRequestBuilder request : List.of(a, b)) {
            calls.add(() -> {
                start.await();
                MvcResult r = mockMvc.perform(request).andReturn();
                return r.getResponse().getStatus();
            });
        }
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Integer> statuses = new ArrayList<>();
        try {
            for (Future<Integer> f : pool.invokeAll(calls)) {
                statuses.add(f.get());
            }
        } finally {
            pool.shutdownNow();
        }
        return statuses;
    }

    private void assertSellerUnblockedOnce(Actor seller) throws Exception {
        assertThat(ledgerCount(adminA, seller, "UNBLOCK")).as("one guarantee release").isEqualTo(1);
        assertThat(ledgerCount(adminA, seller, "CHARGE")).as("no fee, no penalty").isZero();
    }

    // ------------------------------------------------------------------ scenarios

    @Test
    @DisplayName("EXPIRED without bids: guarantee back once, vehicle to re-diagnostic, NO_SALE re-diagnostic, auctionable again")
    void expiredWithoutBidsThenRelist() throws Exception {
        Actor seller = verifiedUser("nsa", "50000");
        Lot lot = createdLot(seller, "");
        startWithBids(lot);

        String ended = call(post("/api/v1/admin/auctions/" + lot.auctionId() + "/complete"), adminA, 200);
        assertThat((String) JsonPath.read(ended, "$.status")).isEqualTo("EXPIRED");
        assertMoney(seller, "50000", "0");
        assertThat(vehicleStatus(lot.vehicleId())).isEqualTo("NEEDS_REDIAGNOSTIC");

        // repeated finalisation, cancellation and repair move no money
        call(post("/api/v1/admin/auctions/" + lot.auctionId() + "/complete"), adminA, 409);
        call(post("/api/v1/admin/auctions/" + lot.auctionId() + "/cancel"), adminA, 409);
        call(post("/api/v1/admin/auctions/" + lot.auctionId() + "/resolve-no-sale"), adminA, 200);
        assertMoney(seller, "50000", "0");
        assertSellerUnblockedOnce(seller);
        createAuction(lot.vehicleId(), "", 400);   // the vehicle must be re-diagnosed first

        // the re-diagnostic reason must match: no failed sale behind this vehicle
        postJson("/api/v1/admin/vehicles/" + lot.vehicleId() + "/rediagnostic", adminA, "{\"reason\":\"DIAGNOSTIC_ERROR\"}", 409);
        postJson("/api/v1/admin/vehicles/" + lot.vehicleId() + "/rediagnostic", adminA, "{\"reason\":\"SELLER_FAULT\"}", 409);
        assertMoney(seller, "50000", "0");
        Long newRequest = id(postJson("/api/v1/admin/vehicles/" + lot.vehicleId() + "/rediagnostic", adminA,
                "{\"reason\":\"NO_SALE\",\"comment\":\"повторное выставление\"}", 201), "$.id");
        assertMoney(seller, "50000", "10000");   // a new guarantee; nothing charged when ordered
        postJson("/api/v1/admin/vehicles/" + lot.vehicleId() + "/rediagnostic", adminA, "{\"reason\":\"NO_SALE\"}", 409);

        inspect(newRequest);
        call(post("/api/v1/admin/diagnostic/reports/" + newRequest + "/approve"), adminA, 200);
        assertThat(vehicleStatus(lot.vehicleId())).isEqualTo("APPROVED");
        Long second = createAuction(lot.vehicleId(), "", 201);
        assertThat(second).isNotEqualTo(lot.auctionId());
        assertMoney(seller, "50000", "10000");
    }

    @Test
    @DisplayName("EXPIRED with the reserve not met: every bidder released, seller guarantee back once")
    void expiredReserveNotMet() throws Exception {
        Actor seller = verifiedUser("nsb", "50000");
        Actor b1 = verifiedUser("nsb1", "300000");
        Actor b2 = verifiedUser("nsb2", "300000");
        Lot lot = createdLot(seller, ",\"reservePrice\":500000");
        startWithBids(lot, b1, b2);
        assertMoney(b1, "300000", "20000");

        String ended = call(post("/api/v1/admin/auctions/" + lot.auctionId() + "/complete"), adminA, 200);
        assertThat((String) JsonPath.read(ended, "$.status")).isEqualTo("EXPIRED");
        assertMoney(b1, "300000", "0");
        assertMoney(b2, "300000", "0");
        assertMoney(seller, "50000", "0");
        assertSellerUnblockedOnce(seller);
        assertThat(vehicleStatus(lot.vehicleId())).isEqualTo("NEEDS_REDIAGNOSTIC");
    }

    @Test
    @DisplayName("CANCELLED before the start, no seller fault: guarantee back, APPROVED vehicle to re-diagnostic")
    void cancelledBeforeStart() throws Exception {
        Actor seller = verifiedUser("nsc", "50000");
        Lot lot = createdLot(seller, "");
        assertThat(vehicleStatus(lot.vehicleId())).isEqualTo("APPROVED");

        String cancelled = call(post("/api/v1/admin/auctions/" + lot.auctionId() + "/cancel"), adminA, 200);
        assertThat((String) JsonPath.read(cancelled, "$.status")).isEqualTo("CANCELLED");
        assertMoney(seller, "50000", "0");
        assertThat(vehicleStatus(lot.vehicleId())).isEqualTo("NEEDS_REDIAGNOSTIC");
        call(post("/api/v1/admin/auctions/" + lot.auctionId() + "/cancel"), adminA, 409);
        assertMoney(seller, "50000", "0");
        assertSellerUnblockedOnce(seller);
    }

    @Test
    @DisplayName("CANCELLED while active, two admins at once: one cancellation, bidders and seller released once")
    void cancelledWhileActiveConcurrently() throws Exception {
        Actor seller = verifiedUser("nsd", "50000");
        Actor b1 = verifiedUser("nsd1", "300000");
        Lot lot = createdLot(seller, "");
        startWithBids(lot, b1);

        List<Integer> statuses = concurrently(
                post("/api/v1/admin/auctions/" + lot.auctionId() + "/cancel").with(adminA.auth()),
                post("/api/v1/admin/auctions/" + lot.auctionId() + "/cancel").with(adminB.auth()));
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(auctionStatus(lot.auctionId())).isEqualTo("CANCELLED");
        assertMoney(b1, "300000", "0");
        assertMoney(seller, "50000", "0");
        assertSellerUnblockedOnce(seller);
        assertThat(vehicleStatus(lot.vehicleId())).isEqualTo("NEEDS_REDIAGNOSTIC");
    }

    @Test
    @DisplayName("CANCELLED for a confirmed seller violation: penalty withheld once, remainder returned, repeats refused")
    void cancelledForSellerViolation() throws Exception {
        Actor seller = verifiedUser("nse", "50000");
        Actor b1 = verifiedUser("nse1", "300000");
        Lot lot = createdLot(seller, "");
        startWithBids(lot, b1);

        String body = "{\"violationDefinitionIds\":[" + violationIds.get(PENALISED) + "]}";
        call(post("/api/v1/admin/auctions/" + lot.auctionId() + "/cancel")
                .contentType(MediaType.APPLICATION_JSON).content("{\"violationDefinitionIds\":[987654321]}"), adminA, 404);
        assertThat(auctionStatus(lot.auctionId())).as("an unknown violation cancels nothing").isEqualTo("ACTIVE");
        assertMoney(seller, "50000", "10000");

        call(post("/api/v1/admin/auctions/" + lot.auctionId() + "/cancel")
                .contentType(MediaType.APPLICATION_JSON).content(body), adminA, 200);
        // guarantee 10000 = penalty 3000 + returned 7000 + frozen 0
        assertMoney(seller, "47000", "0");
        assertMoney(b1, "300000", "0");
        assertThat(ledgerCount(adminA, seller, "CHARGE")).isEqualTo(1);
        assertThat(vehicleStatus(lot.vehicleId())).isEqualTo("NEEDS_REDIAGNOSTIC");

        call(post("/api/v1/admin/auctions/" + lot.auctionId() + "/cancel")
                .contentType(MediaType.APPLICATION_JSON).content(body), adminA, 409);
        call(post("/api/v1/admin/auctions/" + lot.auctionId() + "/resolve-no-sale"), adminA, 200);   // no-op
        assertMoney(seller, "47000", "0");
        assertThat(ledgerCount(adminA, seller, "CHARGE")).isEqualTo(1);
        assertThat(ledgerCount(adminA, seller, "UNBLOCK")).isEqualTo(1);
    }

    @Test
    @DisplayName("repair of an auction stranded before this change: two admins at once release the guarantee exactly once")
    void strandedAuctionRepairedOnce() throws Exception {
        Actor seller = verifiedUser("nsf", "50000");
        Lot lot = createdLot(seller, "");
        startWithBids(lot);
        // the state the old code left behind: EXPIRED, vehicle IN_AUCTION, guarantee still frozen
        Auction stranded = auctionRepository.findById(lot.auctionId()).orElseThrow();
        stranded.setStatus(AuctionStatus.EXPIRED);
        auctionRepository.save(stranded);
        assertMoney(seller, "50000", "10000");

        call(post("/api/v1/admin/auctions/" + lot.auctionId() + "/resolve-no-sale"), seller, 403);
        List<Integer> statuses = concurrently(
                post("/api/v1/admin/auctions/" + lot.auctionId() + "/resolve-no-sale").with(adminA.auth()),
                post("/api/v1/admin/auctions/" + lot.auctionId() + "/resolve-no-sale").with(adminB.auth()));
        assertThat(statuses).containsOnly(200);
        assertMoney(seller, "50000", "0");
        assertSellerUnblockedOnce(seller);
        assertThat(vehicleStatus(lot.vehicleId())).isEqualTo("NEEDS_REDIAGNOSTIC");

        call(post("/api/v1/admin/auctions/" + lot.auctionId() + "/resolve-no-sale"), adminA, 200);
        assertMoney(seller, "50000", "0");
        assertSellerUnblockedOnce(seller);
    }

    @Test
    @DisplayName("repair is refused for an auction that is not EXPIRED / CANCELLED")
    void repairRefusedForLiveAuction() throws Exception {
        Actor seller = verifiedUser("nsg", "50000");
        Lot lot = createdLot(seller, "");
        call(post("/api/v1/admin/auctions/" + lot.auctionId() + "/resolve-no-sale"), adminA, 409);
        startWithBids(lot);
        call(post("/api/v1/admin/auctions/" + lot.auctionId() + "/resolve-no-sale"), adminA, 409);
        assertMoney(seller, "50000", "10000");
        assertThat(vehicleStatus(lot.vehicleId())).isEqualTo("IN_AUCTION");
    }
}
