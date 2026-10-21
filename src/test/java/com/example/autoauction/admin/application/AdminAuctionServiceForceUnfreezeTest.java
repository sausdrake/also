package com.example.autoauction.admin.application;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionParticipantRepository;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.deposit.application.DepositService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * BALANCE-01: force-unfreeze-all is refused while the lot still takes bids (ACTIVE) or Buy Now
 * (CREATED); every other status keeps the existing behaviour (all non-winners unfrozen).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BALANCE-01: force-unfreeze-all status guard")
class AdminAuctionServiceForceUnfreezeTest {

    private static final Long AUCTION_ID = 7L;
    private static final Long WINNER = 11L;
    private static final Long LOSER_A = 12L;
    private static final Long LOSER_B = 13L;

    @Mock private AuctionRepository auctionRepository;
    @Mock private AuctionParticipantRepository participantRepository;
    @Mock private DepositService depositService;

    @InjectMocks private AdminAuctionService adminAuctionService;

    private Auction auction(AuctionStatus status) {
        OffsetDateTime now = OffsetDateTime.now();
        Auction a = new Auction(1L, new BigDecimal("1000000"), null, null, new BigDecimal("10000"),
                now.minusHours(1), now.plusDays(1), 99L);
        a.setStatus(status);
        a.setWinnerId(WINNER);
        return a;
    }

    @ParameterizedTest
    @EnumSource(value = AuctionStatus.class, names = {"CREATED", "ACTIVE"})
    @DisplayName("a lot that still takes bids / Buy Now is refused and nothing is unfrozen")
    void refusedWhileTheLotTakesBids(AuctionStatus status) {
        when(auctionRepository.findById(AUCTION_ID)).thenReturn(Optional.of(auction(status)));

        assertThatThrownBy(() -> adminAuctionService.forceUnfreezeAll(AUCTION_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(status.name())
                .hasMessageContaining("ещё принимает ставки");

        verifyNoInteractions(depositService);
        verifyNoInteractions(participantRepository);
    }

    @ParameterizedTest
    @EnumSource(value = AuctionStatus.class, names = {"CREATED", "ACTIVE"}, mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("every other status keeps the existing behaviour: all non-winners unfrozen, the winner kept")
    void otherStatusesUnfreezeNonWinners(AuctionStatus status) {
        when(auctionRepository.findById(AUCTION_ID)).thenReturn(Optional.of(auction(status)));
        when(participantRepository.getParticipants(AUCTION_ID)).thenReturn(List.of(WINNER, LOSER_A, LOSER_B));

        Map<String, Object> result = adminAuctionService.forceUnfreezeAll(AUCTION_ID);

        verify(depositService).unfreezeForAuction(LOSER_A, AUCTION_ID);
        verify(depositService).unfreezeForAuction(LOSER_B, AUCTION_ID);
        verify(depositService, never()).unfreezeForAuction(WINNER, AUCTION_ID);
        assertThat(result).containsEntry("count", 2);
    }

    @Test
    @DisplayName("an unknown auction is still a not-found error (checked before the status)")
    void unknownAuction() {
        when(auctionRepository.findById(AUCTION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adminAuctionService.forceUnfreezeAll(AUCTION_ID))
                .isInstanceOf(IllegalArgumentException.class);
        verify(depositService, never()).unfreezeForAuction(any(), any());
    }
}
