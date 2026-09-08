package com.example.autoauction.auction.infrastructure.persistence;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.config.AuctionExtensionProperties;
import com.example.autoauction.config.TestClockConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import({JpaAuctionRepositoryAdapter.class, AuctionMapper.class, AuctionExtensionProperties.class, TestClockConfig.class})
@DisplayName("Тесты репозитория аукционов")
class JpaAuctionRepositoryAdapterTest {

    @Autowired
    private AuctionRepository auctionRepository;

    private Auction buildAuction() {
        OffsetDateTime now = OffsetDateTime.now();
        return new Auction(
                1L,
                new BigDecimal("1000000"),
                null,
                null,
                new BigDecimal("10000"),
                now.plusDays(1),
                now.plusDays(7),
                1L
        );
    }

    @Test
    @DisplayName("Сохранение аукциона — должен присвоиться id")
    void save_ShouldPersistAuction() {
        Auction auction = buildAuction();

        Auction saved = auctionRepository.save(auction);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(AuctionStatus.CREATED);
        assertThat(saved.getStartingPrice()).isEqualByComparingTo(new BigDecimal("1000000"));
    }

    @Test
    @DisplayName("Сохранение и чтение additionalPhotoUrls как JSON-строки")
    void save_ShouldPersistAdditionalPhotoUrls() {
        Auction auction = buildAuction();
        String photoUrls = "[\"http://example.com/photo1.jpg\",\"http://example.com/photo2.jpg\"]";
        auction.setAdditionalPhotoUrls(photoUrls);

        Auction saved = auctionRepository.save(auction);
        Optional<Auction> loaded = auctionRepository.findById(saved.getId());

        assertThat(loaded).isPresent();
        assertThat(loaded.get().getAdditionalPhotoUrls()).isEqualTo(photoUrls);
    }

    @Test
    @DisplayName("Сохранение и чтение mainPhotoUrl и videoUrl")
    void save_ShouldPersistMediaUrls() {
        Auction auction = buildAuction();
        auction.setMainPhotoUrl("http://example.com/main.jpg");
        auction.setVideoUrl("http://example.com/video.mp4");

        Auction saved = auctionRepository.save(auction);
        Optional<Auction> loaded = auctionRepository.findById(saved.getId());

        assertThat(loaded).isPresent();
        assertThat(loaded.get().getMainPhotoUrl()).isEqualTo("http://example.com/main.jpg");
        assertThat(loaded.get().getVideoUrl()).isEqualTo("http://example.com/video.mp4");
    }

    @Test
    @DisplayName("findById — несуществующий id возвращает empty")
    void findById_NotExists_ShouldReturnEmpty() {
        Optional<Auction> result = auctionRepository.findById(999999L);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("findByStatus — возвращает аукционы с нужным статусом")
    void findByStatus_ShouldReturnMatchingAuctions() {
        auctionRepository.save(buildAuction());
        auctionRepository.save(buildAuction());

        var auctions = auctionRepository.findByStatus(AuctionStatus.CREATED);

        assertThat(auctions).hasSize(2);
        assertThat(auctions).allMatch(a -> a.getStatus() == AuctionStatus.CREATED);
    }

    @Test
    @DisplayName("deleteById — аукцион удаляется из БД")
    void deleteById_ShouldRemoveAuction() {
        Auction saved = auctionRepository.save(buildAuction());
        Long id = saved.getId();

        auctionRepository.deleteById(id);

        assertThat(auctionRepository.findById(id)).isEmpty();
    }

    @Test
    @DisplayName("Slice 1b-2: policyVersionId не задан (legacy) — сохраняется и читается как null")
    void save_LegacyAuction_PolicyVersionIdShouldRoundTripAsNull() {
        Auction auction = buildAuction();

        Auction saved = auctionRepository.save(auction);
        Optional<Auction> loaded = auctionRepository.findById(saved.getId());

        assertThat(saved.getPolicyVersionId()).isNull();
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getPolicyVersionId()).isNull();
    }

    @Test
    @DisplayName("Slice 1b-2: policyVersionId сохраняется и читается без запроса к PolicyVersion")
    void save_PolicyBoundAuction_PolicyVersionIdShouldRoundTrip() {
        Auction auction = buildAuction();
        auction.setPolicyVersionId(7L);

        Auction saved = auctionRepository.save(auction);
        Optional<Auction> loaded = auctionRepository.findById(saved.getId());

        assertThat(saved.getPolicyVersionId()).isEqualTo(7L);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getPolicyVersionId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("Сохранение с confirmedStart — поле сохраняется корректно")
    void save_ShouldPersistConfirmedStart() {
        Auction auction = buildAuction();
        auction.setConfirmedStart(true);
        auction.setConfirmationDeadline(OffsetDateTime.now().plusHours(2));

        Auction saved = auctionRepository.save(auction);
        Optional<Auction> loaded = auctionRepository.findById(saved.getId());

        assertThat(loaded).isPresent();
        assertThat(loaded.get().isConfirmedStart()).isTrue();
        assertThat(loaded.get().getConfirmationDeadline()).isNotNull();
    }
}
