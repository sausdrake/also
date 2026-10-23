package com.example.autoauction.e2e;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

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
class BusinessCycleE2EPgIntegrationTest extends ApiE2ESupport {

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
