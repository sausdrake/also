package com.example.autoauction.subscription.web;

import com.example.autoauction.auth.domain.UserPrincipal;
import com.example.autoauction.auth.infrastructure.security.CurrentUser;
import com.example.autoauction.subscription.application.LotSubscriptionService;
import com.example.autoauction.subscription.web.dto.SubscribedAuctionDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/subscriptions")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class LotSubscriptionController {

    private final LotSubscriptionService subscriptionService;

    @PostMapping("/{auctionId}")
    public ResponseEntity<Void> subscribe(@PathVariable Long auctionId,
                                          @CurrentUser UserPrincipal currentUser) {
        subscriptionService.subscribe(currentUser.getUserId(), auctionId);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{auctionId}")
    public ResponseEntity<Void> unsubscribe(@PathVariable Long auctionId,
                                            @CurrentUser UserPrincipal currentUser) {
        subscriptionService.unsubscribe(currentUser.getUserId(), auctionId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{auctionId}/status")
    public ResponseEntity<Map<String, Boolean>> status(@PathVariable Long auctionId,
                                                       @CurrentUser UserPrincipal currentUser) {
        boolean sub = subscriptionService.isSubscribed(currentUser.getUserId(), auctionId);
        return ResponseEntity.ok(Map.of("subscribed", sub));
    }

    @GetMapping
    public ResponseEntity<List<SubscribedAuctionDto>> getAll(@CurrentUser UserPrincipal currentUser) {
        return ResponseEntity.ok(subscriptionService.getSubscribedAuctions(currentUser.getUserId()));
    }
}
