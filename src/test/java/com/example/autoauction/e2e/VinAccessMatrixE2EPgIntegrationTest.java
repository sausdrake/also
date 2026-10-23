package com.example.autoauction.e2e;

import com.example.autoauction.notification.application.NotificationService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * VIN-AUTH-01 security matrix through the HTTP API on PostgreSQL: the server - not the page - decides
 * who sees a VIN.
 * <pre>
 *   anonymous                      DENY      verified user (buyer / seller)  ALLOW
 *   signed in, no KYC              DENY      diagnostician                   ALLOW (staff)
 *   KYC pending / rejected / revoked DENY    administrator                   ALLOW (staff)
 * </pre>
 * Checked on every public surface that ever carried it: the vehicle card, the report by vehicle and by id,
 * the catalogue and lot (the display name used to be "Brand Model Year (VIN: …)"), and notification texts
 * stored before the fix. A forged or garbage token never upgrades the viewer.
 */
@AutoConfigureMockMvc
@DisplayName("VIN-AUTH-01: VIN only for verified users and staff (PostgreSQL)")
@org.testcontainers.junit.jupiter.Testcontainers(disabledWithoutDocker = true)
class VinAccessMatrixE2EPgIntegrationTest extends ApiE2ESupport {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private NotificationService notificationService;

    private Actor admin;
    private Actor diagnostician;
    private Actor seller;
    private Long vehicleId;
    private Long requestId;
    private Long auctionId;
    private String vin;

    private static final Actor ANONYMOUS = new Actor(null, "anonymous", request -> request);

    @BeforeEach
    void lot() throws Exception {
        admin = staff("vnadmin", "ADMIN");
        diagnostician = staff("vndiag", "DIAGNOSTIC");
        call(put("/api/v1/admin/settings").contentType(MediaType.APPLICATION_JSON)
                .content("{\"diagnostic_fee_amount\":\"4000\",\"diagnostic_deposit_amount\":\"10000\""
                        + ",\"auction_participation_deposit_amount\":\"20000\"}"), admin, 200);
        Actor checker = staff("vnadminb", "ADMIN");
        String draft = postJson("/api/v1/admin/policy/drafts", admin, "{\"commissionRate\":0.05}", 201);
        postJson("/api/v1/admin/policy/drafts/" + id(draft, "$.id") + "/publish", checker,
                "{\"observedConfigurationRevision\":" + id(draft, "$.configurationRevision") + "}", 200);
        Long templateId = id(postJson("/api/v1/admin/diagnostic/templates", admin,
                "{\"name\":\"VN " + run + System.nanoTime() + "\",\"isActive\":true}", 201), "$.id");
        Long groupId = id(postJson("/api/v1/admin/diagnostic/templates/" + templateId + "/groups", admin,
                "{\"name\":\"Кузов\",\"sortOrder\":0}", 201), "$.id");
        Long itemId = id(postJson("/api/v1/admin/diagnostic/groups/" + groupId + "/items", admin,
                "{\"title\":\"Кузов\",\"fieldType\":\"RATING\",\"hasRating\":true,\"isRequired\":true}", 201), "$.id");

        seller = register("vnseller");
        verifyIdentity(seller, admin);
        topUp(admin, seller, "50000", "BANK-" + run + "-vnseller");
        vin = vin();
        requestId = id(postJson("/api/v1/diagnostic/requests", seller,
                "{\"brand\":\"Toyota\",\"model\":\"Camry\",\"year\":2019,\"vin\":\"" + vin + "\"}", 201), "$.id");
        postJson("/api/v1/diagnostic/work/" + requestId + "/assign", diagnostician, "{}", 200);
        postJson("/api/v1/diagnostic/work/" + requestId + "/template", diagnostician, "{\"templateId\":" + templateId + "}", 200);
        postJson("/api/v1/diagnostic/work/" + requestId + "/answers", diagnostician,
                "{\"submit\":true,\"answers\":[{\"itemId\":" + itemId + ",\"rating\":4,\"comment\":\"ok\"}]}", 200);
        call(post("/api/v1/admin/diagnostic/reports/" + requestId + "/approve"), admin, 200);
        List<Number> ids = JsonPath.read(call(get("/api/v1/admin/vehicles/approved"), admin, 200), "$[?(@.vin == '" + vin + "')].id");
        vehicleId = ids.get(0).longValue();
        OffsetDateTime now = OffsetDateTime.now();
        DateTimeFormatter iso = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
        auctionId = id(postJson("/api/v1/admin/auctions", admin, "{\"vehicleId\":" + vehicleId
                + ",\"startingPrice\":100000,\"startTime\":\"" + now.plusDays(1).format(iso) + "\",\"endTime\":\""
                + now.plusDays(4).format(iso) + "\",\"mainPhotoUrl\":\"https://media.example/vn.jpg\"}", 201), "$.id");
    }

    private String anon(String url) throws Exception {
        MvcResult r = mockMvc.perform(get(url)).andReturn();
        assertThat(r.getResponse().getStatus()).as(url).isEqualTo(200);
        return body(r);
    }

    private String as(Actor who, String url) throws Exception {
        return who == ANONYMOUS ? anon(url) : call(get(url), who, 200);
    }

    /** Every public surface for this viewer: VIN visible exactly when expected, never in display names. */
    private void assertVin(Actor who, boolean visible) throws Exception {
        String card = as(who, "/api/v1/public/vehicles/" + vehicleId);
        String byVehicle = as(who, "/api/v1/public/reports/by-vehicle/" + vehicleId);
        String byId = as(who, "/api/v1/public/reports/" + requestId);
        for (String json : List.of(card, byVehicle, byId)) {
            if (visible) {
                assertThat((String) JsonPath.read(json, "$.vin")).as(who.username()).isEqualTo(vin);
                assertThat((Boolean) JsonPath.read(json, "$.vinHidden")).isFalse();
            } else {
                assertThat((Object) JsonPath.read(json, "$.vin")).as(who.username() + " must not see the VIN").isNull();
                assertThat((Boolean) JsonPath.read(json, "$.vinHidden")).isTrue();
                assertThat(json).doesNotContain(vin);
            }
        }
        // the catalogue and the lot never carry it, for nobody
        assertThat(as(who, "/api/v1/public/auctions")).doesNotContain(vin).doesNotContain("VIN:");
        String lot = as(who, "/api/v1/public/auctions/" + auctionId);
        assertThat(lot).doesNotContain(vin);
        assertThat((String) JsonPath.read(lot, "$.vehicleInfo")).isEqualTo("Toyota Camry 2019");
    }

    @Test
    @DisplayName("anonymous: DENY")
    void anonymous() throws Exception {
        assertVin(ANONYMOUS, false);
    }

    @Test
    @DisplayName("signed in without KYC: DENY; a garbage or forged token never upgrades the viewer")
    void unverified() throws Exception {
        assertVin(register("vnplain"), false);
        // a forged signature is refused outright (no response body to leak from) or served as anonymous
        String forgedBody;
        try {
            forgedBody = body(mockMvc.perform(get("/api/v1/public/vehicles/" + vehicleId)
                    .header("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ2bmFkbWluIn0.AAAA")).andReturn());
        } catch (Exception refused) {
            forgedBody = "";
        }
        assertThat(forgedBody).doesNotContain(vin);
    }

    @Test
    @DisplayName("KYC pending, rejected, revoked: DENY")
    void kycNotVerified() throws Exception {
        Actor pending = register("vnpend");
        submitIdentity(pending);
        assertVin(pending, false);

        Actor rejected = register("vnrej");
        Long rejectedId = submitIdentity(rejected);
        postJson("/api/v1/admin/identity/" + rejectedId + "/reject", admin, "{\"reason\":\"нечитаемо\"}", 200);
        assertVin(rejected, false);

        Actor revoked = register("vnrev");
        Long revokedId = submitIdentity(revoked);
        call(post("/api/v1/admin/identity/" + revokedId + "/approve"), admin, 200);
        assertVin(revoked, true);
        postJson("/api/v1/admin/identity/" + revokedId + "/revoke", admin, "{\"reasonCode\":\"DOCUMENT_INVALID\",\"reasonText\":\"e2e\"}", 200);
        assertVin(revoked, false);
    }

    @Test
    @DisplayName("verified buyer and the verified seller: ALLOW")
    void verified() throws Exception {
        Actor buyer = register("vnbuyer");
        verifyIdentity(buyer, admin);
        assertVin(buyer, true);
        assertVin(seller, true);
    }

    @Test
    @DisplayName("staff: diagnostician and administrator ALLOW")
    void staffAllowed() throws Exception {
        assertVin(diagnostician, true);
        assertVin(admin, true);
    }

    @Test
    @DisplayName("display names and notification texts stored before the fix never hand the VIN out")
    void legacyTextsStripped() throws Exception {
        jdbc.update("UPDATE auctions SET vehicle_info = ? WHERE id = ?", "Toyota Camry 2019 (VIN: " + vin + ")", auctionId);
        Actor follower = register("vnfollow");
        notificationService.send(follower.id(), "AUCTION_ENDED", "Аукцион завершён",
                "Автомобиль \"Toyota Camry 2019 (VIN: " + vin + ")\" куплен по цене Buy Now.", auctionId, "AUCTION");

        assertThat(anon("/api/v1/public/auctions")).doesNotContain(vin);
        assertThat((String) JsonPath.read(anon("/api/v1/public/auctions/" + auctionId), "$.vehicleInfo")).isEqualTo("Toyota Camry 2019");
        String notifications = call(get("/api/notifications"), follower, 200);
        assertThat(notifications).doesNotContain(vin).contains("Toyota Camry 2019");
        List<Map<String, Object>> content = JsonPath.read(notifications, "$.content");
        assertThat(content).hasSize(1);
        // stored history is not rewritten - only what leaves the server is cleaned
        assertThat(jdbc.queryForObject("SELECT message FROM user_notifications WHERE user_id = ?", String.class, follower.id()))
                .contains(vin);
    }
}
