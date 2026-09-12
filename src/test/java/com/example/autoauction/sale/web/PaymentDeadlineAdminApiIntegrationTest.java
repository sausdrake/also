package com.example.autoauction.sale.web;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.auth.infrastructure.security.WithMockCustomUser;
import com.example.autoauction.vehicle.domain.Vehicle;
import com.example.autoauction.vehicle.domain.VehicleType;
import com.example.autoauction.vehicle.domain.port.VehicleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice 1B: full HTTP-layer, real-DB proof of the two admin-only deadline endpoints under
 * {@code /api/v1/admin/sale/{auctionId}/deadline/...}. Mirrors {@code AdminSaleApiIntegrationTest}'s
 * own established convention ({@code @SpringBootTest} + {@code @AutoConfigureMockMvc}, not
 * class-level {@code @Transactional}) - a separate file rather than appending to that one, to
 * avoid touching its already-pending, unrelated working-tree changes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Slice 1B: Admin Payment Deadline API (extend / history)")
class PaymentDeadlineAdminApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private AuctionRepository auctionRepository;
    @Autowired private VehicleRepository vehicleRepository;

    private static String base(Long auctionId) {
        return "/api/v1/admin/sale/" + auctionId;
    }

    /** Fresh IN_AUCTION vehicle + WAITING_FOR_PAYMENT auction with an initial deadline already set. */
    private Long waitingForPaymentWithDeadline(OffsetDateTime deadline) {
        Long winnerId = 820_000L + (System.nanoTime() % 100_000_000L);
        Vehicle vehicle = new Vehicle("Toyota", "Camry", 2020,
                "SD" + (System.nanoTime() % 1_000_000_000L), VehicleType.SEDAN, 1L, "owner");
        vehicle.submitForReview();
        vehicle.approve(1L, "admin");
        vehicle.markInAuction();
        Long vehicleId = vehicleRepository.save(vehicle).getId();

        OffsetDateTime now = OffsetDateTime.now();
        Auction auction = new Auction(vehicleId, new BigDecimal("1000000"), null, null,
                new BigDecimal("10000"), now.minusDays(7), now.minusHours(1), 10L);
        auction.setStatus(AuctionStatus.WAITING_FOR_PAYMENT);
        auction.setWinnerId(winnerId);
        auction.setWinningBid(new BigDecimal("1020000.00"));
        auction.setPaymentDeadline(deadline);
        return auctionRepository.save(auction).getId();
    }

    private static String iso(OffsetDateTime t) {
        return t.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    // ==================== extend: authorization ====================

    @Test
    @DisplayName("ADMIN может продлить платёжный дедлайн")
    @WithMockCustomUser(userId = 42L, roles = {"ADMIN"})
    void extend_Admin_Succeeds() throws Exception {
        Long auctionId = waitingForPaymentWithDeadline(OffsetDateTime.now().plusDays(1));
        OffsetDateTime newDeadline = OffsetDateTime.now().plusDays(3);

        mockMvc.perform(post(base(auctionId) + "/deadline/extend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newDeadline\": \"" + iso(newDeadline) + "\", \"reason\": \"покупатель попросил отсрочку\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.auctionId").value(auctionId))
                .andExpect(jsonPath("$.overdue").value(false));

        assertThat(auctionRepository.findById(auctionId).orElseThrow().getPaymentDeadline())
                .isCloseTo(newDeadline, within(1, java.time.temporal.ChronoUnit.SECONDS));
    }

    @Test
    @DisplayName("Неаутентифицированный запрос отклоняется (401)")
    void extend_Anonymous_Rejected() throws Exception {
        Long auctionId = waitingForPaymentWithDeadline(OffsetDateTime.now().plusDays(1));

        mockMvc.perform(post(base(auctionId) + "/deadline/extend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newDeadline\": \"" + iso(OffsetDateTime.now().plusDays(3)) + "\", \"reason\": \"x\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Запрос роли USER отклоняется (403)")
    @WithMockCustomUser(userId = 2L, roles = {"USER"})
    void extend_UserRole_Rejected() throws Exception {
        Long auctionId = waitingForPaymentWithDeadline(OffsetDateTime.now().plusDays(1));

        mockMvc.perform(post(base(auctionId) + "/deadline/extend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newDeadline\": \"" + iso(OffsetDateTime.now().plusDays(3)) + "\", \"reason\": \"x\"}"))
                .andExpect(status().isForbidden());
    }

    // ==================== extend: validation ====================

    @Test
    @DisplayName("Пустой reason отклоняется (400)")
    @WithMockCustomUser(userId = 42L, roles = {"ADMIN"})
    void extend_BlankReason_Rejected() throws Exception {
        Long auctionId = waitingForPaymentWithDeadline(OffsetDateTime.now().plusDays(1));

        mockMvc.perform(post(base(auctionId) + "/deadline/extend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newDeadline\": \"" + iso(OffsetDateTime.now().plusDays(3)) + "\", \"reason\": \"   \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Аукцион не в WAITING_FOR_PAYMENT -> отклоняется (409)")
    @WithMockCustomUser(userId = 42L, roles = {"ADMIN"})
    void extend_NotWaitingForPayment_Rejected() throws Exception {
        Long auctionId = waitingForPaymentWithDeadline(OffsetDateTime.now().plusDays(1));
        Auction auction = auctionRepository.findById(auctionId).orElseThrow();
        auction.markSold();
        auctionRepository.save(auction);

        mockMvc.perform(post(base(auctionId) + "/deadline/extend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newDeadline\": \"" + iso(OffsetDateTime.now().plusDays(3)) + "\", \"reason\": \"x\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Actor всегда берётся из аутентифицированного контекста - контракт тела запроса не содержит поля для admin id")
    @WithMockCustomUser(userId = 99L, roles = {"ADMIN"})
    void extend_ActorAlwaysFromAuthenticatedContext() throws Exception {
        Long auctionId = waitingForPaymentWithDeadline(OffsetDateTime.now().plusDays(1));

        // PaymentDeadlineExtendRequest has exactly two fields (newDeadline, reason) - there is no
        // field for a client to even attempt to supply an admin id through.
        mockMvc.perform(post(base(auctionId) + "/deadline/extend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newDeadline\": \"" + iso(OffsetDateTime.now().plusDays(3))
                                + "\", \"reason\": \"x\"}"))
                .andExpect(status().isOk());

        var history = mockMvc.perform(get(base(auctionId) + "/deadline/history"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(history).contains("\"changedByAdminId\":99");
    }

    // ==================== history ====================

    @Test
    @DisplayName("История содержит previous/new значения; несколько продлений создают несколько записей")
    @WithMockCustomUser(userId = 42L, roles = {"ADMIN"})
    void history_MultipleExtensions_CreateMultipleRows() throws Exception {
        OffsetDateTime initial = OffsetDateTime.now().plusDays(1);
        Long auctionId = waitingForPaymentWithDeadline(initial);
        OffsetDateTime second = initial.plusDays(1);
        OffsetDateTime third = second.plusDays(1);

        mockMvc.perform(post(base(auctionId) + "/deadline/extend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newDeadline\": \"" + iso(second) + "\", \"reason\": \"first extension\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post(base(auctionId) + "/deadline/extend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newDeadline\": \"" + iso(third) + "\", \"reason\": \"second extension\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get(base(auctionId) + "/deadline/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].changeType").value("ADMIN_EXTENSION"))
                .andExpect(jsonPath("$[0].reason").value("first extension"))
                .andExpect(jsonPath("$[0].changedByAdminId").value(42))
                .andExpect(jsonPath("$[1].reason").value("second extension"));
    }

    @Test
    @DisplayName("История пуста для аукциона, у которого никогда не было дедлайна (никакой истории не изобретается)")
    @WithMockCustomUser(userId = 42L, roles = {"ADMIN"})
    void history_AuctionWithNoDeadline_ReturnsEmpty() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        Vehicle vehicle = new Vehicle("Toyota", "Camry", 2020,
                "SE" + (System.nanoTime() % 1_000_000_000L), VehicleType.SEDAN, 1L, "owner");
        vehicle.submitForReview();
        vehicle.approve(1L, "admin");
        vehicle.markInAuction();
        Long vehicleId = vehicleRepository.save(vehicle).getId();
        Auction auction = new Auction(vehicleId, new BigDecimal("1000000"), null, null,
                new BigDecimal("10000"), now.minusDays(7), now.minusHours(1), 10L);
        auction.setStatus(AuctionStatus.WAITING_FOR_PAYMENT);
        Long auctionId = auctionRepository.save(auction).getId();

        mockMvc.perform(get(base(auctionId) + "/deadline/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
