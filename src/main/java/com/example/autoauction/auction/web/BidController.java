package com.example.autoauction.auction.web;

import com.example.autoauction.auction.application.BidService;
import com.example.autoauction.auction.web.dto.BidRequest;
import com.example.autoauction.auction.web.dto.BidResponse;
import com.example.autoauction.auth.domain.UserPrincipal;
import com.example.autoauction.auth.infrastructure.security.CurrentUser;
import com.example.autoauction.identity.application.VerifiedIdentityGate;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/auctions")
@Tag(name = "Ставки", description = "Управление ставками на аукционах")
public class BidController {

    private final BidService bidService;
    private final VerifiedIdentityGate verifiedIdentityGate;

    public BidController(BidService bidService, VerifiedIdentityGate verifiedIdentityGate) {
        this.bidService = bidService;
        this.verifiedIdentityGate = verifiedIdentityGate;
    }

    @PostMapping("/{auctionId}/bids")
    @Operation(summary = "Сделать ставку на аукцион")
    public ResponseEntity<BidResponse> placeBid(
            @Parameter(description = "ID аукциона", required = true, example = "1")
            @PathVariable Long auctionId,
            @Valid @RequestBody BidRequest request,
            @Parameter(hidden = true)
            @CurrentUser UserPrincipal currentUser
    ) {
        Long bidderId = currentUser.getUserId();
        log.info("User {} placing bid on auction {}", bidderId, auctionId);
        verifiedIdentityGate.requireVerified(bidderId);

        BidResponse response = bidService.placeBid(auctionId, request.amount(), bidderId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{auctionId}/buy-now")
    @Operation(summary = "Купить автомобиль по цене Buy Now (до начала аукциона)")
    public ResponseEntity<BidResponse> buyNow(
            @Parameter(description = "ID аукциона", required = true)
            @PathVariable Long auctionId,
            @Parameter(hidden = true)
            @CurrentUser UserPrincipal currentUser
    ) {
        Long buyerId = currentUser.getUserId();
        log.info("User {} buying auction {} via Buy Now", buyerId, auctionId);
        verifiedIdentityGate.requireVerified(buyerId);

        BidResponse response = bidService.buyNow(auctionId, buyerId);
        return ResponseEntity.ok(response);
    }
}