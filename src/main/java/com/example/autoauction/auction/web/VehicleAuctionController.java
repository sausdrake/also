package com.example.autoauction.auction.web;

import com.example.autoauction.auction.application.VehicleAuctionQueryService;
import com.example.autoauction.auction.web.dto.AuctionResponse;
import com.example.autoauction.auth.domain.UserPrincipal;
import com.example.autoauction.auth.infrastructure.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sale-UI (seller/vehicle-owner visibility): the one authenticated, non-admin-scoped read
 * exposing "which auction is my vehicle in, and what's its status" - the piece the seller-side
 * post-approval journey was otherwise missing entirely. Owner-or-admin only; see
 * {@link VehicleAuctionQueryService} for the exact ownership check.
 */
@RestController
@RequestMapping("/api/v1/vehicles")
@Tag(name = "Аукцион автомобиля", description = "Просмотр аукциона своего автомобиля (владелец или ADMIN)")
public class VehicleAuctionController {

    private final VehicleAuctionQueryService vehicleAuctionQueryService;

    public VehicleAuctionController(VehicleAuctionQueryService vehicleAuctionQueryService) {
        this.vehicleAuctionQueryService = vehicleAuctionQueryService;
    }

    @GetMapping("/{vehicleId}/auction")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Аукцион для автомобиля (владелец заявки на диагностику или ADMIN)")
    public AuctionResponse getAuctionForVehicle(
            @PathVariable Long vehicleId,
            @CurrentUser UserPrincipal currentUser
    ) {
        boolean isAdmin = currentUser.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        return vehicleAuctionQueryService.getAuctionForVehicle(vehicleId, currentUser.getUserId(), isAdmin);
    }
}
