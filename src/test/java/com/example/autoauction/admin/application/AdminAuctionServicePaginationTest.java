// admin/application/AdminAuctionServicePaginationTest.java
package com.example.autoauction.admin.application;

import com.example.autoauction.auction.application.AuctionFinalizer;
import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.auction.web.dto.AuctionResponse;
import com.example.autoauction.vehicle.application.VehicleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Тесты пагинации в AdminAuctionService")
class AdminAuctionServicePaginationTest {

    @Mock
    private AuctionRepository auctionRepository;

    @Mock
    private VehicleService vehicleService;

    @Mock
    private AuctionValidator auctionValidator;

    @Mock
    private AuctionFinalizer auctionFinalizer;

    @InjectMocks
    private AdminAuctionService adminAuctionService;

    private Auction auction1;
    private Auction auction2;
    private Auction auction3;

    @BeforeEach
    void setUp() {
        OffsetDateTime now = OffsetDateTime.now();

        auction1 = createAuction(1L, AuctionStatus.CREATED, now);
        auction2 = createAuction(2L, AuctionStatus.ACTIVE, now);
        auction3 = createAuction(3L, AuctionStatus.COMPLETED, now);
    }

    private Auction createAuction(Long id, AuctionStatus status, OffsetDateTime now) {
        Auction auction = new Auction(
                id, new BigDecimal("1000000"), new BigDecimal("1200000"),
                new BigDecimal("1500000"), new BigDecimal("10000"),
                now.plusDays(1), now.plusDays(7), 1L
        );
        auction.setId(id);
        auction.setStatus(status);
        return auction;
    }

    @Test
    @DisplayName("Пагинация - первая страница с 2 элементами")
    void getAllAuctions_FirstPage_ShouldReturnPage() {
        // given
        Pageable pageable = PageRequest.of(0, 2);
        List<Auction> auctions = List.of(auction1, auction2);
        Page<Auction> page = new PageImpl<>(auctions, pageable, 3);

        when(auctionRepository.findAllWithPagination(pageable)).thenReturn(page);

        // when
        Page<AuctionResponse> result = adminAuctionService.getAllAuctions(pageable);

        // then
        assertThat(result).isNotNull();
        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getTotalElements()).isEqualTo(3);
        assertThat(result.getNumber()).isEqualTo(0);
        assertThat(result.getSize()).isEqualTo(2);
    }

    @Test
    @DisplayName("Пагинация - вторая страница")
    void getAllAuctions_SecondPage_ShouldReturnPage() {
        // given
        Pageable pageable = PageRequest.of(1, 2);
        List<Auction> auctions = List.of(auction3);
        Page<Auction> page = new PageImpl<>(auctions, pageable, 3);

        when(auctionRepository.findAllWithPagination(pageable)).thenReturn(page);

        // when
        Page<AuctionResponse> result = adminAuctionService.getAllAuctions(pageable);

        // then
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getNumber()).isEqualTo(1);
    }

    @Test
    @DisplayName("Пагинация по статусу - CREATED")
    void getAuctionsByStatus_WithPagination_ShouldReturnPage() {
        // given
        Pageable pageable = PageRequest.of(0, 10);
        List<Auction> auctions = List.of(auction1);
        Page<Auction> page = new PageImpl<>(auctions, pageable, 1);

        when(auctionRepository.findByStatusWithPagination(AuctionStatus.CREATED, pageable))
                .thenReturn(page);

        // when
        Page<AuctionResponse> result = adminAuctionService.getAuctionsByStatus("CREATED", pageable);

        // then
        assertThat(result).isNotNull();
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).status()).isEqualTo(AuctionStatus.CREATED);
    }

    @Test
    @DisplayName("Пустой результат пагинации")
    void getAllAuctions_EmptyPage_ShouldReturnEmptyPage() {
        // given
        Pageable pageable = PageRequest.of(0, 10);
        Page<Auction> page = Page.empty(pageable);

        when(auctionRepository.findAllWithPagination(pageable)).thenReturn(page);

        // when
        Page<AuctionResponse> result = adminAuctionService.getAllAuctions(pageable);

        // then
        assertThat(result).isNotNull();
        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
    }
}