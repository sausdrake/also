package com.example.autoauction.auction.web;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.auction.web.dto.AuctionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "Публичные аукционы", description = "Просмотр аукционов без авторизации")
public class PublicAuctionController {

    private final AuctionRepository auctionRepository;

    // Legacy path
    @GetMapping("/api/public/auctions")
    @Operation(summary = "Список аукционов (ACTIVE и CREATED)")
    public Page<AuctionResponse> getActiveAuctionsLegacy(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status
    ) {
        return getAuctions(page, size, status);
    }

    // v1 path
    @GetMapping("/api/v1/public/auctions")
    @Operation(summary = "Список аукционов v1 (ACTIVE и CREATED)")
    public Page<AuctionResponse> getActiveAuctions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status
    ) {
        return getAuctions(page, size, status);
    }

    private Page<AuctionResponse> getAuctions(int page, int size, String statusFilter) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("id").descending());

        if (statusFilter != null && !statusFilter.isBlank()) {
            try {
                AuctionStatus auctionStatus = AuctionStatus.valueOf(statusFilter.toUpperCase());
                return auctionRepository.findByStatusWithPagination(auctionStatus, pageable)
                        .map(AuctionResponse::fromDomain);
            } catch (IllegalArgumentException e) {
                // Неизвестный статус — возвращаем ACTIVE+CREATED
            }
        }

        // По умолчанию — только ACTIVE и CREATED аукционы
        List<Auction> active = auctionRepository.findByStatus(AuctionStatus.ACTIVE);
        List<Auction> created = auctionRepository.findByStatus(AuctionStatus.CREATED);
        List<Auction> combined = new java.util.ArrayList<>();
        combined.addAll(active);
        combined.addAll(created);
        combined.sort((a, b) -> Long.compare(b.getId(), a.getId()));

        int start = (int) pageable.getOffset();
        int end = Math.min(start + pageable.getPageSize(), combined.size());
        List<Auction> pageContent = start >= combined.size() ? List.of() : combined.subList(start, end);

        return new PageImpl<>(
                pageContent.stream().map(AuctionResponse::fromDomain).toList(),
                pageable,
                combined.size()
        );
    }

    @GetMapping({"/api/public/auctions/{id}", "/api/v1/public/auctions/{id}"})
    @Operation(summary = "Получить аукцион по ID")
    public AuctionResponse getAuction(@PathVariable Long id) {
        Auction auction = auctionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Аукцион не найден: " + id));
        return AuctionResponse.fromDomain(auction);
    }
}
