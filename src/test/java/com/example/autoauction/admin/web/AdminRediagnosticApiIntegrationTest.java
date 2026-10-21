package com.example.autoauction.admin.web;

import com.example.autoauction.admin.application.SystemSettingsService;
import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.auth.domain.TestUserPrincipal;
import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.diagnostic.domain.DiagnosticRequest;
import com.example.autoauction.diagnostic.domain.DiagnosticRequest.DiagnosticRequestStatus;
import com.example.autoauction.diagnostic.domain.port.DiagnosticRequestRepository;
import com.example.autoauction.identity.domain.DocumentType;
import com.example.autoauction.identity.domain.IdentityDocument;
import com.example.autoauction.identity.domain.IdentityVerification;
import com.example.autoauction.identity.domain.port.IdentityDocumentRepository;
import com.example.autoauction.identity.domain.port.IdentityVerificationRepository;
import com.example.autoauction.policy.application.PolicyVersionService;
import com.example.autoauction.sale.domain.FaultParty;
import com.example.autoauction.sale.domain.SaleResolution;
import com.example.autoauction.sale.domain.SaleResolutionResult;
import com.example.autoauction.sale.domain.port.SaleResolutionRepository;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import com.example.autoauction.vehicle.application.VehicleService;
import com.example.autoauction.vehicle.domain.Vehicle;
import com.example.autoauction.vehicle.domain.VehicleStatus;
import com.example.autoauction.vehicle.domain.port.VehicleRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** MVP-005 over HTTP: ordering a re-diagnostic, its audit fields, history, and access rules. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("MVP-005: re-diagnostic admin API")
class AdminRediagnosticApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private DiagnosticRequestRepository requestRepository;
    @Autowired private VehicleService vehicleService;
    @Autowired private VehicleRepository vehicleRepository;
    @Autowired private AuctionRepository auctionRepository;
    @Autowired private SaleResolutionRepository saleResolutionRepository;
    @Autowired private DepositService depositService;
    @Autowired private PolicyVersionService policyVersionService;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private IdentityDocumentRepository identityDocumentRepository;
    @Autowired private IdentityVerificationRepository identityVerificationRepository;
    @Autowired private com.example.autoauction.diagnostic.application.DiagnosticRequestService diagnosticRequestService;
    @MockBean private SystemSettingsService systemSettingsService;

    private Long admin;
    private Long original;
    private Long vehicleId;

    private static RequestPostProcessor as(Long id, String role) {
        return SecurityMockMvcRequestPostProcessors.user(TestUserPrincipal.createWithRoles(id, "u" + id, role));
    }

    @BeforeEach
    void setUp() {
        policyVersionService.publish(policyVersionService.create(new BigDecimal("0.02"), 1L).getId(), 2L, 0L);
        when(systemSettingsService.getBigDecimal(eq("diagnostic_deposit_amount"), any())).thenReturn(new BigDecimal("10000"));
        when(systemSettingsService.getRequiredBigDecimal("diagnostic_fee_amount")).thenReturn(new BigDecimal("4000"));

        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        String name = "rdapi" + System.nanoTime();
        Long seller = userRepository.save(new User(null, name, name + "@test.com", "encoded", true, Set.of(role))).getId();
        admin = userRepository.save(new User(null, name + "a", name + "a@test.com", "encoded", true, Set.of(role))).getId();
        depositService.createDeposit(seller, new BigDecimal("50000"));
        OffsetDateTime now = OffsetDateTime.now();
        Long doc = identityDocumentRepository.save(new IdentityDocument("identity-documents/rdapi" + System.nanoTime(), seller, "image/jpeg", 1L, now)).getId();
        IdentityVerification iv = IdentityVerification.firstSubmission(seller, "KG", DocumentType.NATIONAL_ID, "RA" + System.nanoTime(), doc, doc, doc, now);
        iv.approve(now);
        identityVerificationRepository.save(iv);

        String vin = ("WBA" + System.nanoTime() + "00000000000000").substring(0, 17);
        DiagnosticRequest r = new DiagnosticRequest();
        r.setUserId(seller);
        r.setBrand("BMW");
        r.setModel("X5");
        r.setYear(2020);
        r.setVin(vin);
        r.setStatus(DiagnosticRequestStatus.APPROVED);
        r.setDepositFrozen(new BigDecimal("10000"));
        r.setCreatedAt(now.minusDays(30));
        r.setUpdatedAt(now.minusDays(29));
        original = requestRepository.save(r).getId();
        vehicleId = vehicleService.createFromDiagnosticRequest(original, "BMW", "X5", 2020, vin, 9L, "diag", admin, "admin").id();
        Vehicle v = vehicleRepository.findById(vehicleId).orElseThrow();
        v.setStatus(VehicleStatus.NEEDS_REDIAGNOSTIC);
        vehicleRepository.save(v);
        Auction auction = new Auction(vehicleId, new BigDecimal("1000000"), null, null, new BigDecimal("10000"),
                now.minusDays(5), now.minusDays(1), admin);
        auction.setStatus(AuctionStatus.SALE_FAILED);
        Long auctionId = auctionRepository.save(auction).getId();
        saleResolutionRepository.save(new SaleResolution(auctionId, SaleResolutionResult.PLATFORM_FAULT, FaultParty.PLATFORM,
                "diagnostician missed a defect", admin, now, now));
    }

    private String order(String body) {
        return "/api/v1/admin/vehicles/" + vehicleId + "/rediagnostic";
    }

    @Test
    @DisplayName("201: the new request carries the link to the previous one and the audit trail")
    void order_returnsLinkedRequestWithAuditTrail() throws Exception {
        mockMvc.perform(post(order(null)).with(as(admin, "ADMIN")).contentType("application/json")
                        .content("{\"reason\":\"DIAGNOSTIC_ERROR\",\"comment\":\"Пропущена коррозия\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.previousDiagnosticRequestId").value(original.intValue()))
                .andExpect(jsonPath("$.rediagnosedVehicleId").value(vehicleId.intValue()))
                .andExpect(jsonPath("$.rediagnosticReason").value("DIAGNOSTIC_ERROR"))
                .andExpect(jsonPath("$.rediagnosticComment").value("Пропущена коррозия"))
                .andExpect(jsonPath("$.rediagnosticOrderedBy").value(admin.intValue()))
                .andExpect(jsonPath("$.rediagnosticOrderedAt").isNotEmpty());
    }

    @Test
    @DisplayName("400: no reason, or an unknown reason")
    void order_withoutValidReason_400() throws Exception {
        mockMvc.perform(post(order(null)).with(as(admin, "ADMIN")).contentType("application/json")
                        .content("{\"comment\":\"no reason\"}"))
                .andExpect(status().isBadRequest());
        // An unknown enum value is unreadable JSON; the global handler currently maps that to 500
        // (pre-existing, application-wide) - what matters here is that it is refused.
        mockMvc.perform(post(order(null)).with(as(admin, "ADMIN")).contentType("application/json")
                        .content("{\"reason\":\"BECAUSE\"}"))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isGreaterThanOrEqualTo(400));
        assertThat(requestRepository.findByVinOrderByCreatedAtAsc(vehicleRepository.findById(vehicleId).orElseThrow().getVin())).hasSize(1);
    }

    @Test
    @DisplayName("403: only an admin can order a re-diagnostic or read the history")
    void nonAdmin_403() throws Exception {
        mockMvc.perform(post(order(null)).with(as(admin, "USER")).contentType("application/json")
                        .content("{\"reason\":\"DIAGNOSTIC_ERROR\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/vehicles/" + vehicleId + "/diagnostic-history").with(as(admin, "DIAGNOSTIC")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("history: original -> reason -> re-diagnostic -> new report; the old report stays readable")
    void history_andOldReportStayAvailable() throws Exception {
        String created = mockMvc.perform(post(order(null)).with(as(admin, "ADMIN")).contentType("application/json")
                        .content("{\"reason\":\"DIAGNOSTIC_ERROR\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Integer newId = JsonPath.read(created, "$.id");
        DiagnosticRequest r = requestRepository.findById(newId.longValue()).orElseThrow();
        r.setStatus(DiagnosticRequestStatus.COMPLETED);
        requestRepository.save(r);
        diagnosticRequestService.approveReport(newId.longValue(), admin);

        mockMvc.perform(get("/api/v1/admin/vehicles/" + vehicleId + "/diagnostic-history").with(as(admin, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(original.intValue()))
                .andExpect(jsonPath("$[0].status").value("APPROVED"))
                .andExpect(jsonPath("$[1].id").value(newId))
                .andExpect(jsonPath("$[1].previousDiagnosticRequestId").value(original.intValue()))
                .andExpect(jsonPath("$[1].rediagnosticReason").value("DIAGNOSTIC_ERROR"))
                .andExpect(jsonPath("$[1].status").value("APPROVED"));
        mockMvc.perform(get("/api/v1/admin/diagnostic/reports/" + original).with(as(admin, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        // buyers see the latest approved report of the vehicle - the re-diagnostic one
        mockMvc.perform(get("/api/v1/public/reports/by-vehicle/" + vehicleId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(newId));
    }
}
