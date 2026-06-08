package com.example.autoauction.auction.web;

import com.example.autoauction.auth.domain.UserPrincipal;
import com.example.autoauction.auth.infrastructure.security.CurrentUser;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.auction.web.dto.AuctionResponse;
import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.deposit.domain.DepositTransaction;
import com.example.autoauction.deposit.web.dto.DepositTransactionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/auctions")
@RequiredArgsConstructor
@Tag(name = "Аукционы пользователя", description = "Личные аукционы: выигранные лоты")
public class UserAuctionController {

    private final AuctionRepository auctionRepository;
    private final DepositService depositService;

    @GetMapping("/won")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Выигранные аукционы текущего пользователя")
    public Page<AuctionResponse> getWonAuctions(
            @CurrentUser UserPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return auctionRepository.findByWinnerId(
                principal.getUserId(),
                PageRequest.of(page, size, Sort.by("id").descending())
        ).map(AuctionResponse::fromDomain);
    }

    @GetMapping("/participated")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Аукционы, в которых участвовал пользователь")
    public List<AuctionResponse> getParticipatedAuctions(@CurrentUser UserPrincipal principal) {
        List<Long> auctionIds = depositService.getTransactionsByUserId(principal.getUserId())
                .stream()
                .filter(t -> t.type() == DepositTransaction.TransactionType.BLOCK && t.auctionId() != null)
                .map(t -> t.auctionId())
                .distinct()
                .collect(Collectors.toList());

        if (auctionIds.isEmpty()) return List.of();

        return auctionRepository.findAllById(auctionIds).stream()
                .map(AuctionResponse::fromDomain)
                .sorted((a, b) -> Long.compare(b.id(), a.id()))
                .collect(Collectors.toList());
    }
}
