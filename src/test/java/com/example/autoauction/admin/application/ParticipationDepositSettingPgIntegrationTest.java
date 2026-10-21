package com.example.autoauction.admin.application;

import com.example.autoauction.auction.application.BidService;
import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.auth.domain.TestUserPrincipal;
import com.example.autoauction.auction.web.dto.AuctionCreateRequest;
import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.deposit.domain.DepositTransaction;
import com.example.autoauction.deposit.domain.port.DepositRepository;
import com.example.autoauction.diagnostic.application.DiagnosticRequestService;
import com.example.autoauction.diagnostic.commitment.application.SellerDiagnosticCommitmentService;
import com.example.autoauction.diagnostic.domain.DiagnosticRequest;
import com.example.autoauction.diagnostic.domain.port.DiagnosticRequestRepository;
import com.example.autoauction.policy.application.PolicyVersionService;
import com.example.autoauction.support.AbstractPostgresIntegrationTest;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import com.example.autoauction.vehicle.domain.port.VehicleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DEPOSIT-01 (BD-01) on real PostgreSQL: the participation deposit is one global admin setting, snapshotted
 * into each new auction; changing it never alters existing / active auctions or deposits already frozen.
 */
@AutoConfigureMockMvc
@DisplayName("DEPOSIT-01: global participation deposit (real PostgreSQL)")
class ParticipationDepositSettingPgIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String KEY = SystemSettingsService.PARTICIPATION_DEPOSIT_KEY;

    @Autowired private MockMvc mockMvc;
    @Autowired private SystemSettingsService settings;
    @Autowired private AdminAuctionService adminAuctionService;
    @Autowired private BidService bidService;
    @Autowired private AuctionRepository auctionRepository;
    @Autowired private DepositService depositService;
    @Autowired private DepositRepository depositRepository;
    @Autowired private DiagnosticRequestService diagnosticRequestService;
    @Autowired private SellerDiagnosticCommitmentService commitmentService;
    @Autowired private DiagnosticRequestRepository diagnosticRequestRepository;
    @Autowired private VehicleRepository vehicleRepository;
    @Autowired private PolicyVersionService policyVersionService;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;

    private Long user(boolean trusted) {
        Role role = roleRepository.findByName("ROLE_USER").orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "u")));
        String n = "dep01" + System.nanoTime();
        Long id = userRepository.save(trusted
                ? new User(null, n, n + "@test.com", "encoded", true, Set.of(role), true, 1L, OffsetDateTime.now())
                : new User(null, n, n + "@test.com", "encoded", true, Set.of(role))).getId();
        depositService.createDeposit(id, new BigDecimal("100000"));
        return id;
    }

    /**
     * An APPROVED vehicle with an AWAITING_FINALIZATION commitment that no live auction references. Other
     * PostgreSQL test classes share this database and create auctions with a synthetic vehicleId (1L ...),
     * so a fresh vehicle whose id happens to equal one of them is skipped.
     */
    private Long eligibleVehicle() {
        for (int attempt = 0; attempt < 5; attempt++) {
            Long id = approvedVehicleWithCommitment();
            if (!auctionRepository.existsByVehicleIdAndStatusIn(id, List.of(
                    com.example.autoauction.auction.domain.AuctionStatus.CREATED,
                    com.example.autoauction.auction.domain.AuctionStatus.ACTIVE,
                    com.example.autoauction.auction.domain.AuctionStatus.WAITING_FOR_PAYMENT))) {
                return id;
            }
        }
        throw new IllegalStateException("no free vehicle id after 5 attempts");
    }

    private Long approvedVehicleWithCommitment() {
        policyVersionService.publish(policyVersionService.create(new BigDecimal("0.05"), 999L).getId(), 998L, 0L);
        Long seller = user(false);
        // unique across the shared PostgreSQL container (nanoTime modulo repeats and findByVin would pick an old vehicle)
        String vin = "DEP" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 14).toUpperCase();
        DiagnosticRequest r = new DiagnosticRequest();
        r.setUserId(seller); r.setBrand("Toyota"); r.setModel("Camry"); r.setYear(2020); r.setVin(vin);
        r.setDepositFrozen(new BigDecimal("10000")); r.setStatus(DiagnosticRequest.DiagnosticRequestStatus.PENDING);
        r.setCreatedAt(OffsetDateTime.now()); r.setUpdatedAt(OffsetDateTime.now());
        Long requestId = diagnosticRequestRepository.save(r).getId();
        commitmentService.freezeCommitment(seller, requestId, new BigDecimal("10000"), new BigDecimal("1000"));
        DiagnosticRequest completed = diagnosticRequestRepository.findById(requestId).orElseThrow();
        completed.setStatus(DiagnosticRequest.DiagnosticRequestStatus.COMPLETED);
        diagnosticRequestRepository.save(completed);
        diagnosticRequestService.approveReport(requestId, 999L);
        return vehicleRepository.findByVin(vin).orElseThrow().getId();
    }

    private Long createAuction() {
        OffsetDateTime start = OffsetDateTime.now().plusDays(1);
        return adminAuctionService.createAuction(new AuctionCreateRequest(eligibleVehicle(), new BigDecimal("1000000"), null, null, start, start.plusDays(2), "https://media.example/dep01.jpg", null), 999L).id();
    }

    private BigDecimal snapshot(Long auctionId) {
        return auctionRepository.findById(auctionId).orElseThrow().getRequiredDepositAmount();
    }

    private ResultActions putSetting(String role, String value) throws Exception {
        TestUserPrincipal p = TestUserPrincipal.createWithRoles(1L, "u1", role);
        return mockMvc.perform(put("/api/v1/admin/settings").with(authentication(new UsernamePasswordAuthenticationToken(p, "x", p.getAuthorities())))
                .contentType(MediaType.APPLICATION_JSON).content("{\"" + KEY + "\":" + (value == null ? "null" : "\"" + value + "\"") + "}"));
    }

    @Test
    @DisplayName("new auctions snapshot the current setting; changing it (10 000 -> 15 000) never touches an existing auction")
    void snapshotAtCreation() throws Exception {
        putSetting("ADMIN", "10000").andExpect(status().isOk()).andExpect(jsonPath("$." + KEY).value("10000.00"));
        Long old = createAuction();
        putSetting("ADMIN", "15000").andExpect(status().isOk());
        Long fresh = createAuction();

        assertThat(snapshot(old)).isEqualByComparingTo("10000");
        assertThat(snapshot(fresh)).isEqualByComparingTo("15000");
    }

    @Test
    @DisplayName("active auction: the setting change neither alters its deposit nor frozen amounts; new bids freeze the snapshot")
    void activeAuctionAndFrozenDepositsUnchanged() throws Exception {
        putSetting("ADMIN", "10000").andExpect(status().isOk());
        Long auctionId = createAuction();
        Auction a = auctionRepository.findById(auctionId).orElseThrow();
        a.start();
        auctionRepository.save(a);
        Long b1 = user(true), b2 = user(true);
        bidService.placeBid(auctionId, new BigDecimal("1010000"), b1);

        putSetting("ADMIN", "15000").andExpect(status().isOk());
        bidService.placeBid(auctionId, new BigDecimal("1020000"), b2);

        assertThat(snapshot(auctionId)).isEqualByComparingTo("10000");
        for (Long b : List.of(b1, b2)) {
            assertThat(depositService.getFrozenAmountForAuction(b, auctionId)).as("frozen for " + b).isEqualByComparingTo("10000");
            assertThat(depositRepository.findByUserId(b).orElseThrow().getBlockedAmount()).isEqualByComparingTo("10000");
            assertThat(depositRepository.findTransactionsByUserId(b).stream()
                    .filter(t -> t.getType() == DepositTransaction.TransactionType.BLOCK).map(DepositTransaction::getAmount).toList())
                    .singleElement().satisfies(x -> assertThat(x).isEqualByComparingTo("10000"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-5", "abc", "", "1.234", "100000000", "NaN", "Infinity"})
    @DisplayName("invalid values are refused with 400 and the stored value stays")
    void invalidValuesRefused(String value) throws Exception {
        putSetting("ADMIN", "12345").andExpect(status().isOk());
        putSetting("ADMIN", value).andExpect(status().isBadRequest());
        assertThat(settings.getAll().get(KEY)).isEqualTo("12345.00");
    }

    @Test
    @DisplayName("boundaries: 0.01 and the column capacity 99 999 999.99 are accepted and normalised to 2 decimals")
    void boundaries() throws Exception {
        putSetting("ADMIN", "0.01").andExpect(jsonPath("$." + KEY).value("0.01"));
        putSetting("ADMIN", " 99999999.99 ").andExpect(jsonPath("$." + KEY).value("99999999.99"));
        putSetting("ADMIN", "2500.50").andExpect(jsonPath("$." + KEY).value("2500.50"));
    }

    @Test
    @DisplayName("not set -> auction creation fails closed with a clear message, nothing is created")
    void notSetFailsClosed() {
        new org.springframework.jdbc.core.JdbcTemplate(
                ((org.springframework.jdbc.core.JdbcTemplate) org.springframework.test.util.ReflectionTestUtils.getField(settings, "jdbc")).getDataSource())
                .update("DELETE FROM system_settings WHERE setting_key = ?", KEY);
        settings.setAll(java.util.Map.of());   // evicts the settings cache
        long before = auctionRepository.findAll().size();
        assertThatThrownBy(this::createAuction).isInstanceOf(IllegalStateException.class).hasMessageContaining("Депозит для участия");
        assertThat(auctionRepository.findAll()).hasSize((int) before);
    }

    @Test
    @DisplayName("SECURITY: a non-admin cannot change it (403), the value stays")
    void onlyAdmin() throws Exception {
        putSetting("ADMIN", "10000").andExpect(status().isOk());
        putSetting("USER", "1").andExpect(status().isForbidden());
        putSetting("DIAGNOSTIC", "1").andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/admin/settings").contentType(MediaType.APPLICATION_JSON).content("{\"" + KEY + "\":\"1\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(settings.getAll().get(KEY)).isEqualTo("10000.00");
    }

    @Test
    @DisplayName("CONCURRENCY: a setting change racing an auction creation - the auction gets one committed value, the next one the new value")
    void settingChangeRacingCreation() throws Exception {
        putSetting("ADMIN", "10000").andExpect(status().isOk());
        Long vehicle = eligibleVehicle();
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> change = pool.submit(() -> { barrier.await(); settings.set(KEY, "20000"); return null; });
            Future<Long> create = pool.submit(() -> {
                barrier.await();
                OffsetDateTime start = OffsetDateTime.now().plusDays(1);
                return adminAuctionService.createAuction(new AuctionCreateRequest(vehicle, new BigDecimal("1000000"), null, null, start, start.plusDays(2), "https://media.example/dep01.jpg", null), 999L).id();
            });
            change.get(30, TimeUnit.SECONDS);
            assertThat(snapshot(create.get(30, TimeUnit.SECONDS))).isIn(new BigDecimal("10000.00"), new BigDecimal("20000.00"));
        } finally {
            pool.shutdownNow();
        }
        assertThat(snapshot(createAuction())).as("after the change every new auction uses it").isEqualByComparingTo("20000");
    }
}
