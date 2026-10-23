package com.example.autoauction.e2e;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

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
 * E2E-03: the failure paths of the MVP cycle through the HTTP API on PostgreSQL, money checked after every
 * step - the counterpart of E2E-02 (the successful sale):
 * <ul>
 *   <li>a diagnostic report rejected for the vehicle's condition (outcome B: the fee is charged once, the
 *       rest of the seller's guarantee released);</li>
 *   <li>a report rejected for a seller violation, then the penalty taken from the published policy;</li>
 *   <li>a failed sale through the buyer's fault (the winner's deposit is withheld once, even under two
 *       concurrent admin calls; the seller's guarantee is released in full);</li>
 *   <li>a failed sale through a platform / diagnostic error, then the re-diagnostic at AlSo's cost and the
 *       vehicle approved again;</li>
 *   <li>a failed sale through a seller violation (outcome C), then the re-diagnostic paid by the seller.</li>
 * </ul>
 * The amounts follow the approved rules already pinned by the service-level tests
 * (SaleCommitmentResolutionIntegrationTest, AdminRediagnosticIntegrationTest, SellerViolationResolutionEngineTest);
 * this class proves them end to end through the real endpoints, migrations and row locks.
 */
@AutoConfigureMockMvc
@DisplayName("E2E-03: failure paths - rejected diagnostic, seller violation, failed sale, re-diagnostic (PostgreSQL)")
@org.testcontainers.junit.jupiter.Testcontainers(disabledWithoutDocker = true)
class FailureCycleE2EPgIntegrationTest extends ApiE2ESupport {

    private static final String PENALISED = "SELLER_UNREACHABLE";
    private static final String INERT = "VEHICLE_CONDITION_CHANGED_AFTER_DIAGNOSTIC";

    private Actor adminA;
    private Actor adminB;
    private Actor diagnostician;
    private Long templateId;
    private Long itemId;
    private Map<String, Long> violationIds;

    @BeforeEach
    void platform() throws Exception {
        adminA = staff("f3admina", "ADMIN");
        adminB = staff("f3adminb", "ADMIN");
        diagnostician = staff("f3diag", "DIAGNOSTIC");

        call(put("/api/v1/admin/settings").contentType(MediaType.APPLICATION_JSON)
                .content("{\"diagnostic_fee_amount\":\"4000\",\"diagnostic_deposit_amount\":\"10000\""
                        + ",\"auction_participation_deposit_amount\":\"20000\"}"), adminA, 200);

        // the policy: 5 % commission, SELLER_UNREACHABLE = 30 % of the seller's guarantee; maker-checker publish
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

        String template = postJson("/api/v1/admin/diagnostic/templates", adminA, "{\"name\":\"F3 " + run + System.nanoTime() + "\",\"isActive\":true}", 201);
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

    private Long requestDiagnostic(Actor seller) throws Exception {
        return id(postJson("/api/v1/diagnostic/requests", seller,
                "{\"brand\":\"Toyota\",\"model\":\"Camry\",\"year\":2020,\"vin\":\"" + vin() + "\"}", 201), "$.id");
    }

    private void inspect(Long requestId) throws Exception {
        postJson("/api/v1/diagnostic/work/" + requestId + "/assign", diagnostician, "{}", 200);
        postJson("/api/v1/diagnostic/work/" + requestId + "/template", diagnostician, "{\"templateId\":" + templateId + "}", 200);
        postJson("/api/v1/diagnostic/work/" + requestId + "/answers", diagnostician,
                "{\"submit\":true,\"answers\":[{\"itemId\":" + itemId + ",\"rating\":4,\"comment\":\"Осмотрено\"}]}", 200);
    }

    private String reject(Long requestId, String category, int expectedStatus) throws Exception {
        return call(post("/api/v1/admin/diagnostic/reports/" + requestId + "/reject")
                .param("reason", "Отклонено в E2E-03").param("category", category), adminA, expectedStatus);
    }

    private String vehicleStatus(Long vehicleId) throws Exception {
        return JsonPath.read(call(get("/api/v1/admin/vehicles/" + vehicleId), adminA, 200), "$.status");
    }

    private record Sale(Long auctionId, Long vehicleId, Long requestId) {}

    /** Seller's diagnostic approved, auction started, two trusted bidders, ended -> WAITING_FOR_PAYMENT. */
    private Sale waitingForPayment(Actor seller, Actor winner, Actor loser) throws Exception {
        Long requestId = requestDiagnostic(seller);
        inspect(requestId);
        call(post("/api/v1/admin/diagnostic/reports/" + requestId + "/approve"), adminA, 200);
        String vehicleVin = JsonPath.read(call(get("/api/v1/admin/diagnostic/reports/" + requestId), adminA, 200), "$.vin");
        List<Number> vehicleIds = JsonPath.read(call(get("/api/v1/admin/vehicles/approved"), adminA, 200),
                "$[?(@.vin == '" + vehicleVin + "')].id");
        assertThat(vehicleIds).hasSize(1);
        Long vehicleId = vehicleIds.get(0).longValue();

        OffsetDateTime now = OffsetDateTime.now();
        DateTimeFormatter iso = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
        Long auctionId = id(postJson("/api/v1/admin/auctions", adminA, "{\"vehicleId\":" + vehicleId
                + ",\"startingPrice\":100000,\"startTime\":\"" + now.plusMinutes(1).format(iso) + "\",\"endTime\":\""
                + now.plusDays(3).format(iso) + "\",\"mainPhotoUrl\":\"https://media.example/f3.jpg\"}", 201), "$.id");
        call(post("/api/v1/admin/auctions/" + auctionId + "/start"), adminA, 200);
        for (Actor u : List.of(winner, loser)) {
            call(patch("/api/v1/admin/users/" + u.id() + "/trust"), adminA, 200);
        }
        postJson("/api/auctions/" + auctionId + "/bids", loser, "{\"amount\":101000}", 200);
        postJson("/api/auctions/" + auctionId + "/bids", winner, "{\"amount\":105000}", 200);
        String ended = call(post("/api/v1/admin/auctions/" + auctionId + "/complete"), adminA, 200);
        assertThat((String) JsonPath.read(ended, "$.status")).isEqualTo("WAITING_FOR_PAYMENT");
        assertThat(id(ended, "$.winnerId")).isEqualTo(winner.id());
        assertMoney(winner, "300000", "20000");
        assertMoney(loser, "300000", "0");
        assertMoney(seller, "50000", "10000");
        return new Sale(auctionId, vehicleId, requestId);
    }

    private String fail(Long auctionId, String json, int expectedStatus) throws Exception {
        return postJson("/api/v1/admin/sale/" + auctionId + "/fail", adminA, json, expectedStatus);
    }

    private String orderRediagnostic(Long vehicleId, String reason, int expectedStatus) throws Exception {
        return postJson("/api/v1/admin/vehicles/" + vehicleId + "/rediagnostic", adminA,
                "{\"reason\":\"" + reason + "\",\"comment\":\"E2E-03\"}", expectedStatus);
    }

    // ------------------------------------------------------------------ scenarios

    @Test
    @DisplayName("report rejected for the vehicle's condition: fee charged once, guarantee remainder released, no vehicle")
    void rejectedForVehicleCondition() throws Exception {
        Actor seller = verifiedUser("f3sa", "50000");
        Long requestId = requestDiagnostic(seller);
        assertMoney(seller, "50000", "10000");
        inspect(requestId);

        String rejected = reject(requestId, "VEHICLE_CONDITION", 200);
        assertThat((String) JsonPath.read(rejected, "$.status")).isEqualTo("REJECTED");
        assertMoney(seller, "46000", "0");

        // repeated rejection and the admin's finalization retry move no more money
        reject(requestId, "VEHICLE_CONDITION", 409);
        call(post("/api/v1/admin/diagnostic/reports/" + requestId + "/retry-finalization"), adminA, 200);
        assertMoney(seller, "46000", "0");
        assertThat(ledgerCount(adminA, seller, "CHARGE")).isEqualTo(1);
        List<Number> vehicles = JsonPath.read(call(get("/api/v1/admin/vehicles/approved"), adminA, 200),
                "$[?(@.vin == '" + JsonPath.read(call(get("/api/v1/admin/diagnostic/reports/" + requestId), adminA, 200), "$.vin") + "')].id");
        assertThat(vehicles).as("a rejected report creates no vehicle").isEmpty();
    }

    @Test
    @DisplayName("report rejected for a seller violation: penalty from the policy snapshot, charged once, rest released")
    void rejectedForSellerViolation() throws Exception {
        Actor seller = verifiedUser("f3sb", "50000");
        Long requestId = requestDiagnostic(seller);
        inspect(requestId);

        reject(requestId, "SELLER_VIOLATION", 200);
        assertMoney(seller, "50000", "10000");   // the penalty is a separate admin decision

        String path = "/api/v1/admin/diagnostic/reports/" + requestId + "/seller-violation-resolution";
        postJson(path, adminA, "{\"violationDefinitionIds\":[" + violationIds.get(INERT) + "]}", 409);
        assertMoney(seller, "50000", "10000");

        String resolved = postJson(path, adminA, "{\"violationDefinitionIds\":[" + violationIds.get(PENALISED) + "]}", 200);
        assertThat(new java.math.BigDecimal(JsonPath.read(resolved, "$.appliedPenaltyAmount").toString())).isEqualByComparingTo("3000");
        assertThat(new java.math.BigDecimal(JsonPath.read(resolved, "$.refundAmount").toString())).isEqualByComparingTo("7000");
        assertMoney(seller, "47000", "0");

        // the same decision again is a no-op; a different one is refused - the seller is never charged twice
        postJson(path, adminA, "{\"violationDefinitionIds\":[" + violationIds.get(PENALISED) + "]}", 200);
        postJson(path, adminA, "{\"violationDefinitionIds\":[" + violationIds.get("SELLER_REFUSED_HANDOVER") + "]}", 409);
        assertMoney(seller, "47000", "0");
        assertThat(ledgerCount(adminA, seller, "CHARGE")).isEqualTo(1);
    }

    @Test
    @DisplayName("sale failed through the buyer: deposit withheld once under two concurrent admin calls, seller released")
    void saleFailedBuyerFault() throws Exception {
        Actor seller = verifiedUser("f3sc", "50000");
        Actor winner = verifiedUser("f3wc", "300000");
        Actor loser = verifiedUser("f3lc", "300000");
        Sale sale = waitingForPayment(seller, winner, loser);

        CyclicBarrier start = new CyclicBarrier(2);
        Callable<Integer> attempt = () -> {
            start.await();
            MvcResult r = mockMvc.perform(post("/api/v1/admin/sale/" + sale.auctionId() + "/fail").with(adminA.auth())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"outcome\":\"BUYER_FAULT\",\"reason\":\"не оплатил\"}"))
                    .andReturn();
            return r.getResponse().getStatus();
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Integer> statuses = new ArrayList<>();
        try {
            for (Future<Integer> f : pool.invokeAll(List.of(attempt, attempt))) {
                statuses.add(f.get());
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);

        assertMoney(winner, "280000", "0");
        assertThat(ledgerCount(adminA, winner, "CHARGE")).isEqualTo(1);
        assertMoney(loser, "300000", "0");
        assertMoney(seller, "50000", "0");   // outcome A for the seller: full guarantee back, no fee
        String state = call(get("/api/v1/admin/sale/" + sale.auctionId()), adminA, 200);
        assertThat((String) JsonPath.read(state, "$.auctionStatus")).isEqualTo("SALE_FAILED");
        assertThat(vehicleStatus(sale.vehicleId())).isEqualTo("APPROVED");

        // a failed sale is final: neither payment, completion nor another failure moves money
        postJson("/api/v1/admin/sale/" + sale.auctionId() + "/payment", adminA, "{\"amount\":105000}", 409);
        postJson("/api/v1/admin/sale/" + sale.auctionId() + "/complete", adminA, "{}", 409);
        fail(sale.auctionId(), "{\"outcome\":\"PLATFORM_FAULT\"}", 409);
        assertMoney(winner, "280000", "0");
        assertMoney(seller, "50000", "0");
    }

    @Test
    @DisplayName("sale failed through a diagnostic error: buyer released, re-diagnostic at AlSo's cost, vehicle approved again")
    void saleFailedPlatformFaultThenRediagnostic() throws Exception {
        Actor seller = verifiedUser("f3sd", "50000");
        Actor winner = verifiedUser("f3wd", "300000");
        Actor loser = verifiedUser("f3ld", "300000");
        Sale sale = waitingForPayment(seller, winner, loser);

        postJson("/api/v1/admin/sale/" + sale.auctionId() + "/payment", adminA, "{\"amount\":105000}", 201);
        String failed = fail(sale.auctionId(), "{\"outcome\":\"PLATFORM_FAULT\",\"reason\":\"ошибка диагностики\"}", 200);
        assertThat((String) JsonPath.read(failed, "$.faultParty")).isEqualTo("PLATFORM");
        assertMoney(winner, "300000", "0");
        assertMoney(seller, "50000", "0");
        assertThat(vehicleStatus(sale.vehicleId())).isEqualTo("NEEDS_REDIAGNOSTIC");

        // the reason must match the recorded fault: a platform error cannot be billed to the seller
        orderRediagnostic(sale.vehicleId(), "SELLER_FAULT", 400);
        assertMoney(seller, "50000", "0");

        Long newRequest = id(orderRediagnostic(sale.vehicleId(), "DIAGNOSTIC_ERROR", 201), "$.id");
        assertThat(newRequest).isNotEqualTo(sale.requestId());
        assertMoney(seller, "50000", "10000");   // a new guarantee, no fee
        orderRediagnostic(sale.vehicleId(), "DIAGNOSTIC_ERROR", 409);
        assertMoney(seller, "50000", "10000");

        inspect(newRequest);
        call(post("/api/v1/admin/diagnostic/reports/" + newRequest + "/approve"), adminA, 200);
        assertThat(vehicleStatus(sale.vehicleId())).isEqualTo("APPROVED");
        List<Number> history = JsonPath.read(call(get("/api/v1/admin/vehicles/" + sale.vehicleId() + "/diagnostic-history"), adminA, 200), "$[*].id");
        assertThat(history).extracting(Number::longValue).contains(sale.requestId(), newRequest);
        assertThat(ledgerCount(adminA, seller, "CHARGE")).isZero();
        assertThat(ledgerCount(adminA, winner, "CHARGE")).isZero();
    }

    @Test
    @DisplayName("sale failed through a seller violation (C): penalty once, buyer released, re-diagnostic paid by the seller")
    void saleFailedSellerViolationThenRediagnostic() throws Exception {
        Actor seller = verifiedUser("f3se", "50000");
        Actor winner = verifiedUser("f3we", "300000");
        Actor loser = verifiedUser("f3le", "300000");
        Sale sale = waitingForPayment(seller, winner, loser);

        fail(sale.auctionId(), "{\"outcome\":\"C\",\"violationDefinitionIds\":[]}", 400);   // C needs the violation
        assertMoney(seller, "50000", "10000");
        String failed = fail(sale.auctionId(), "{\"outcome\":\"C\",\"violationDefinitionIds\":[" + violationIds.get(PENALISED) + "]}", 200);
        assertThat((String) JsonPath.read(failed, "$.faultParty")).isEqualTo("SELLER");
        assertMoney(seller, "47000", "0");   // 30 % of the 10000 guarantee
        assertMoney(winner, "300000", "0");
        assertThat(vehicleStatus(sale.vehicleId())).isEqualTo("NEEDS_REDIAGNOSTIC");

        orderRediagnostic(sale.vehicleId(), "DIAGNOSTIC_ERROR", 400);   // a seller fault is not billed to AlSo
        assertMoney(seller, "47000", "0");
        orderRediagnostic(sale.vehicleId(), "SELLER_FAULT", 201);
        assertMoney(seller, "43000", "10000");   // the fee once, a new guarantee; the penalty is not charged again
        assertThat(ledgerCount(adminA, seller, "CHARGE")).isEqualTo(2);
        assertThat(ledgerCount(adminA, winner, "CHARGE")).isZero();
    }
}
