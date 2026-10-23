package com.example.autoauction.e2e;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
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
 * BUYNOW-01: the complete Buy Now lifecycle through the HTTP API on PostgreSQL, money checked after every
 * step. Buy Now is offered before the start (CREATED) and leads into the ordinary sale state machine:
 * CREATED -> WAITING_FOR_PAYMENT -> SOLD (manual payment / handover evidence, then completion) or
 * SALE_FAILED (deposit withheld once for the buyer's fault, released otherwise). Concurrent buyers,
 * repeated calls, stale screens (a lot that already started or was already bought), the KYC / trust /
 * own-lot gates and the impossibility of a bid winning the same lot are covered. No bank integration:
 * payment is the existing manual admin boundary (BANK-EPIC blocked).
 */
@AutoConfigureMockMvc
@DisplayName("BUYNOW-01: Buy Now -> payment -> SOLD / failure -> release once (PostgreSQL)")
@org.testcontainers.junit.jupiter.Testcontainers(disabledWithoutDocker = true)
class BuyNowCycleE2EPgIntegrationTest extends ApiE2ESupport {

    private Actor adminA;
    private Actor adminB;
    private Actor diagnostician;
    private Long templateId;
    private Long itemId;

    @BeforeEach
    void platform() throws Exception {
        adminA = staff("bnadmina", "ADMIN");
        adminB = staff("bnadminb", "ADMIN");
        diagnostician = staff("bndiag", "DIAGNOSTIC");

        call(put("/api/v1/admin/settings").contentType(MediaType.APPLICATION_JSON)
                .content("{\"diagnostic_fee_amount\":\"4000\",\"diagnostic_deposit_amount\":\"10000\""
                        + ",\"auction_participation_deposit_amount\":\"20000\"}"), adminA, 200);
        String draft = postJson("/api/v1/admin/policy/drafts", adminA, "{\"commissionRate\":0.05}", 201);
        postJson("/api/v1/admin/policy/drafts/" + id(draft, "$.id") + "/publish", adminB,
                "{\"observedConfigurationRevision\":" + id(draft, "$.configurationRevision") + "}", 200);

        String template = postJson("/api/v1/admin/diagnostic/templates", adminA, "{\"name\":\"BN " + run + System.nanoTime() + "\",\"isActive\":true}", 201);
        templateId = id(template, "$.id");
        String group = postJson("/api/v1/admin/diagnostic/templates/" + templateId + "/groups", adminA,
                "{\"name\":\"Кузов\",\"sortOrder\":0}", 201);
        itemId = id(postJson("/api/v1/admin/diagnostic/groups/" + id(group, "$.id") + "/items", adminA,
                "{\"title\":\"Состояние кузова\",\"fieldType\":\"RATING\",\"hasRating\":true,\"isRequired\":true}", 201), "$.id");
    }

    // ------------------------------------------------------------------ steps

    private Actor verifiedUser(String prefix, String topUp, boolean trusted) throws Exception {
        Actor u = register(prefix);
        verifyIdentity(u, adminA);
        topUp(adminA, u, topUp, "BANK-" + run + "-" + prefix);
        if (trusted) {
            call(patch("/api/v1/admin/users/" + u.id() + "/trust"), adminA, 200);
        }
        return u;
    }

    private record Lot(Long auctionId, Long vehicleId) {}

    /** Approved diagnostic of the seller's car and a CREATED auction with the given Buy Now price. */
    private Lot lot(Actor seller, String buyNowJson) throws Exception {
        Long requestId = id(postJson("/api/v1/diagnostic/requests", seller,
                "{\"brand\":\"Lexus\",\"model\":\"RX\",\"year\":2019,\"vin\":\"" + vin() + "\"}", 201), "$.id");
        postJson("/api/v1/diagnostic/work/" + requestId + "/assign", diagnostician, "{}", 200);
        postJson("/api/v1/diagnostic/work/" + requestId + "/template", diagnostician, "{\"templateId\":" + templateId + "}", 200);
        postJson("/api/v1/diagnostic/work/" + requestId + "/answers", diagnostician,
                "{\"submit\":true,\"answers\":[{\"itemId\":" + itemId + ",\"rating\":5,\"comment\":\"ok\"}]}", 200);
        call(post("/api/v1/admin/diagnostic/reports/" + requestId + "/approve"), adminA, 200);
        String vehicleVin = JsonPath.read(call(get("/api/v1/admin/diagnostic/reports/" + requestId), adminA, 200), "$.vin");
        List<Number> vehicleIds = JsonPath.read(call(get("/api/v1/admin/vehicles/approved"), adminA, 200),
                "$[?(@.vin == '" + vehicleVin + "')].id");
        Long vehicleId = vehicleIds.get(0).longValue();
        OffsetDateTime now = OffsetDateTime.now();
        DateTimeFormatter iso = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
        Long auctionId = id(postJson("/api/v1/admin/auctions", adminA, "{\"vehicleId\":" + vehicleId
                + ",\"startingPrice\":100000" + buyNowJson + ",\"startTime\":\"" + now.plusDays(1).format(iso)
                + "\",\"endTime\":\"" + now.plusDays(4).format(iso) + "\",\"mainPhotoUrl\":\"https://media.example/bn.jpg\"}", 201), "$.id");
        return new Lot(auctionId, vehicleId);
    }

    private String auction(Long auctionId) throws Exception {
        return call(get("/api/v1/admin/auctions/" + auctionId), adminA, 200);
    }

    private String vehicleStatus(Long vehicleId) throws Exception {
        return JsonPath.read(call(get("/api/v1/admin/vehicles/" + vehicleId), adminA, 200), "$.status");
    }

    private String buyNow(Long auctionId, Actor buyer, int expectedStatus) throws Exception {
        return call(post("/api/auctions/" + auctionId + "/buy-now"), buyer, expectedStatus);
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

    // ------------------------------------------------------------------ scenarios

    @Test
    @DisplayName("Buy Now -> WAITING_FOR_PAYMENT (vehicle IN_AUCTION) -> payment -> handover -> SOLD, money settled once")
    void buyNowThenSold() throws Exception {
        Actor seller = verifiedUser("bna", "50000", false);
        Actor buyer = verifiedUser("bnab", "300000", true);
        Lot lot = lot(seller, ",\"buyNowPrice\":200000");

        String bought = buyNow(lot.auctionId(), buyer, 200);
        assertThat((Boolean) JsonPath.read(bought, "$.buyNowUsed")).isTrue();
        String state = auction(lot.auctionId());
        assertThat((String) JsonPath.read(state, "$.status")).isEqualTo("WAITING_FOR_PAYMENT");
        assertThat(id(state, "$.winnerId")).isEqualTo(buyer.id());
        BigDecimal price = new BigDecimal(JsonPath.read(state, "$.winningBid").toString());
        assertThat(price).as("buyer-facing Buy Now price: seller price + 5 % commission").isEqualByComparingTo("210000");
        assertThat(vehicleStatus(lot.vehicleId())).isEqualTo("IN_AUCTION");
        assertMoney(buyer, "300000", "20000");
        assertMoney(seller, "50000", "10000");

        // the sale is visible with its payment deadline (the timeout path is the admin's overdue decision)
        String sale = call(get("/api/v1/admin/sale/" + lot.auctionId()), adminA, 200);
        assertThat((Object) JsonPath.read(sale, "$.paymentDeadline")).isNotNull();
        assertThat((Boolean) JsonPath.read(sale, "$.overdue")).isFalse();

        // no second outcome: bids, start, cancel, a second Buy Now
        Actor other = verifiedUser("bnao", "300000", true);
        postJson("/api/auctions/" + lot.auctionId() + "/bids", other, "{\"amount\":101000}", 409);
        call(post("/api/v1/admin/auctions/" + lot.auctionId() + "/start"), adminA, 409);
        call(post("/api/v1/admin/auctions/" + lot.auctionId() + "/cancel"), adminA, 409);
        buyNow(lot.auctionId(), other, 409);
        buyNow(lot.auctionId(), buyer, 409);
        assertMoney(other, "300000", "0");
        assertMoney(buyer, "300000", "20000");
        assertThat(ledgerCount(adminA, buyer, "BLOCK")).isEqualTo(1);

        postJson("/api/v1/admin/sale/" + lot.auctionId() + "/payment", adminA, "{\"amount\":" + price.toPlainString() + "}", 201);
        postJson("/api/v1/admin/sale/" + lot.auctionId() + "/handover", adminA, "{}", 201);
        String sold = postJson("/api/v1/admin/sale/" + lot.auctionId() + "/complete", adminA, "{}", 200);
        assertThat((String) JsonPath.read(call(get("/api/v1/admin/sale/" + lot.auctionId()), adminA, 200), "$.auctionStatus")).isEqualTo("SOLD");
        assertThat(sold).isNotBlank();
        assertThat(vehicleStatus(lot.vehicleId())).isEqualTo("SOLD");
        assertMoney(buyer, "300000", "0");    // deposit released on success, the price is paid outside the system
        assertMoney(seller, "50000", "0");    // guarantee returned in full (outcome A)

        String settled = call(get("/api/v1/admin/sale/" + lot.auctionId()), adminA, 200);
        assertThat(new BigDecimal(JsonPath.read(settled, "$.settlement.expectedSellerAmount").toString())).isEqualByComparingTo("200000");
        assertThat(new BigDecimal(JsonPath.read(settled, "$.settlement.expectedCommission").toString())).isEqualByComparingTo("10000");

        // repeated completion moves nothing
        postJson("/api/v1/admin/sale/" + lot.auctionId() + "/complete", adminA, "{}", 409);
        assertMoney(buyer, "300000", "0");
        assertMoney(seller, "50000", "0");
        assertThat(ledgerCount(adminA, buyer, "CHARGE")).isZero();
    }

    @Test
    @DisplayName("two buyers at once: exactly one Buy Now, the other refused with no money frozen")
    void concurrentBuyers() throws Exception {
        Actor seller = verifiedUser("bnc", "50000", false);
        Actor b1 = verifiedUser("bnc1", "300000", true);
        Actor b2 = verifiedUser("bnc2", "300000", true);
        Lot lot = lot(seller, ",\"buyNowPrice\":200000");

        List<Integer> statuses = concurrently(
                post("/api/auctions/" + lot.auctionId() + "/buy-now").with(b1.auth()),
                post("/api/auctions/" + lot.auctionId() + "/buy-now").with(b2.auth()));
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);

        Long winner = id(auction(lot.auctionId()), "$.winnerId");
        Actor won = winner.equals(b1.id()) ? b1 : b2;
        Actor lost = won == b1 ? b2 : b1;
        assertMoney(won, "300000", "20000");
        assertMoney(lost, "300000", "0");
        assertThat(ledgerCount(adminA, lost, "BLOCK")).isZero();
        assertThat(vehicleStatus(lot.vehicleId())).isEqualTo("IN_AUCTION");
    }

    @Test
    @DisplayName("Buy Now failed through the buyer: deposit withheld exactly once under two admins, vehicle back to APPROVED")
    void buyNowFailedBuyerFault() throws Exception {
        Actor seller = verifiedUser("bnd", "50000", false);
        Actor buyer = verifiedUser("bndb", "300000", true);
        Lot lot = lot(seller, ",\"buyNowPrice\":200000");
        buyNow(lot.auctionId(), buyer, 200);

        String body = "{\"outcome\":\"BUYER_FAULT\",\"reason\":\"не оплатил в срок\"}";
        List<Integer> statuses = concurrently(
                post("/api/v1/admin/sale/" + lot.auctionId() + "/fail").with(adminA.auth()).contentType(MediaType.APPLICATION_JSON).content(body),
                post("/api/v1/admin/sale/" + lot.auctionId() + "/fail").with(adminB.auth()).contentType(MediaType.APPLICATION_JSON).content(body));
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);

        assertMoney(buyer, "280000", "0");
        assertThat(ledgerCount(adminA, buyer, "CHARGE")).isEqualTo(1);
        assertMoney(seller, "50000", "0");
        assertThat(vehicleStatus(lot.vehicleId())).isEqualTo("APPROVED");
        postJson("/api/v1/admin/sale/" + lot.auctionId() + "/complete", adminA, "{}", 409);
        assertMoney(buyer, "280000", "0");
    }

    @Test
    @DisplayName("Buy Now failed through the platform after payment: buyer deposit released once, payment refunded, re-diagnostic")
    void buyNowFailedPlatformFault() throws Exception {
        Actor seller = verifiedUser("bne", "50000", false);
        Actor buyer = verifiedUser("bneb", "300000", true);
        Lot lot = lot(seller, ",\"buyNowPrice\":200000");
        buyNow(lot.auctionId(), buyer, 200);
        postJson("/api/v1/admin/sale/" + lot.auctionId() + "/payment", adminA, "{\"amount\":210000}", 201);

        String failed = postJson("/api/v1/admin/sale/" + lot.auctionId() + "/fail", adminA,
                "{\"outcome\":\"PLATFORM_FAULT\",\"reason\":\"ошибка диагностики\"}", 200);
        assertThat((String) JsonPath.read(failed, "$.faultParty")).isEqualTo("PLATFORM");
        assertMoney(buyer, "300000", "0");
        assertThat(ledgerCount(adminA, buyer, "UNBLOCK")).isEqualTo(1);
        assertMoney(seller, "50000", "0");
        assertThat(vehicleStatus(lot.vehicleId())).isEqualTo("NEEDS_REDIAGNOSTIC");
        postJson("/api/v1/admin/sale/" + lot.auctionId() + "/fail", adminA, "{\"outcome\":\"B\"}", 409);
        assertMoney(buyer, "300000", "0");
        assertThat(ledgerCount(adminA, buyer, "UNBLOCK")).isEqualTo(1);
    }

    @Test
    @DisplayName("gates: no Buy Now price, already started (stale screen), own lot, unverified, untrusted - nothing frozen")
    void gates() throws Exception {
        Actor seller = verifiedUser("bnf", "50000", true);
        Actor buyer = verifiedUser("bnfb", "300000", true);

        Lot noPrice = lot(seller, "");
        buyNow(noPrice.auctionId(), buyer, 409);

        Lot started = lot(seller, ",\"buyNowPrice\":200000");
        call(post("/api/v1/admin/auctions/" + started.auctionId() + "/start"), adminA, 200);
        buyNow(started.auctionId(), buyer, 409);

        Lot own = lot(seller, ",\"buyNowPrice\":200000");
        buyNow(own.auctionId(), seller, 400);

        Actor unverified = register("bnfu");
        topUp(adminA, unverified, "300000", "BANK-" + run + "-bnfu");
        call(patch("/api/v1/admin/users/" + unverified.id() + "/trust"), adminA, 200);
        buyNow(own.auctionId(), unverified, 403);

        Actor untrusted = verifiedUser("bnfn", "300000", false);
        buyNow(own.auctionId(), untrusted, 403);

        assertMoney(buyer, "300000", "0");
        assertMoney(unverified, "300000", "0");
        assertMoney(untrusted, "300000", "0");
        assertThat((String) JsonPath.read(auction(own.auctionId()), "$.status")).isEqualTo("CREATED");
        assertThat(vehicleStatus(own.vehicleId())).isEqualTo("APPROVED");
    }
}
