package com.example.autoauction.auction.web;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.auction.web.dto.AuctionResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/public/auctions")
@RequiredArgsConstructor
public class PublicAuctionController {

    private final AuctionRepository auctionRepository;

    @GetMapping
    public Page<AuctionResponse> getActiveAuctions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("id").descending());
        
        // Показываем только CREATED и ACTIVE аукционы
        // TODO: добавить фильтр по статусу в репозиторий
        return auctionRepository.findAllWithPagination(pageable)
                .map(AuctionResponse::fromDomain);
    }

    @GetMapping("/{id}")
    public AuctionResponse getAuction(@PathVariable Long id) {
        Auction auction = auctionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Аукцион не найден: " + id));
        return AuctionResponse.fromDomain(auction);
    }
}