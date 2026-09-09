// auction/infrastructure/persistence/JpaAuctionRepositoryAdapter.java
package com.example.autoauction.auction.infrastructure.persistence;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.config.AuctionExtensionProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Repository
@Transactional
public class JpaAuctionRepositoryAdapter implements AuctionRepository {

    private final SpringDataAuctionJpaRepository jpaRepository;
    private final AuctionMapper mapper;
    private final Clock clock;
    private final AuctionExtensionProperties extensionProperties;

    public JpaAuctionRepositoryAdapter(
            SpringDataAuctionJpaRepository jpaRepository,
            AuctionMapper mapper,
            Clock clock,
            AuctionExtensionProperties extensionProperties) {
        this.jpaRepository = jpaRepository;
        this.mapper = mapper;
        this.clock = clock;
        this.extensionProperties = extensionProperties;
        log.info("JpaAuctionRepositoryAdapter initialized with Clock and ExtensionProperties");
    }

    // Вспомогательный метод для внедрения зависимостей в доменный объект
    private Auction enrichDomain(Auction auction) {
        if (auction != null) {
            auction.setClock(clock);
            auction.setExtensionProperties(extensionProperties);
        }
        return auction;
    }

    @Override
    public Auction save(Auction auction) {
        JpaAuctionEntity entity = mapper.toEntity(auction);
        JpaAuctionEntity saved = jpaRepository.save(entity);
        return enrichDomain(mapper.toDomain(saved));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Auction> findById(Long id) {
        return jpaRepository.findById(id)
                .map(mapper::toDomain)
                .map(this::enrichDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Auction> findByIdWithLock(Long id) {
        return jpaRepository.findByIdWithLock(id)
                .map(mapper::toDomain)
                .map(this::enrichDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Auction> findAll() {
        return jpaRepository.findAll().stream()
                .map(mapper::toDomain)
                .map(this::enrichDomain)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public Page<Auction> findAllWithPagination(Pageable pageable) {
        return jpaRepository.findAll(pageable)
                .map(mapper::toDomain)
                .map(this::enrichDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Auction> findByStatus(AuctionStatus status) {
        return jpaRepository.findByStatus(status).stream()
                .map(mapper::toDomain)
                .map(this::enrichDomain)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public Page<Auction> findByStatusWithPagination(AuctionStatus status, Pageable pageable) {
        return jpaRepository.findByStatus(status, pageable)
                .map(mapper::toDomain)
                .map(this::enrichDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Auction> findByVehicleId(Long vehicleId) {
        return jpaRepository.findByVehicleId(vehicleId).stream()
                .map(mapper::toDomain)
                .map(this::enrichDomain)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Auction> findFirstByVehicleIdOrderByCreatedAtDesc(Long vehicleId) {
        return jpaRepository.findFirstByVehicleIdOrderByCreatedAtDesc(vehicleId)
                .map(mapper::toDomain)
                .map(this::enrichDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByVehicleIdAndStatus(Long vehicleId, AuctionStatus status) {
        return jpaRepository.existsByVehicleIdAndStatus(vehicleId, status);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByVehicleIdAndStatusIn(Long vehicleId, List<AuctionStatus> statuses) {
        return jpaRepository.existsByVehicleIdAndStatusIn(vehicleId, statuses);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsBySellerDiagnosticCommitmentId(Long sellerDiagnosticCommitmentId) {
        return jpaRepository.existsBySellerDiagnosticCommitmentId(sellerDiagnosticCommitmentId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AuctionStatus> findStatusesByVehicleId(Long vehicleId) {
        return jpaRepository.findByVehicleId(vehicleId).stream()
                .map(JpaAuctionEntity::getStatus)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<Auction> findByStatusAndEndTimeBefore(AuctionStatus status, OffsetDateTime endTime) {
        return jpaRepository.findByStatusAndEndTimeBefore(status, endTime).stream()
                .map(mapper::toDomain)
                .map(this::enrichDomain)
                .collect(Collectors.toList());
    }

    @Override
    public void deleteById(Long id) {
        jpaRepository.deleteById(id);
        log.debug("Deleted auction with id: {}", id);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Auction> findConfirmedAuctionsReadyToStart(OffsetDateTime now) {
        return jpaRepository.findConfirmedReadyToStart(now).stream()
                .map(mapper::toDomain)
                .map(this::enrichDomain)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<Auction> findUnconfirmedAuctionsPastDeadline(OffsetDateTime now) {
        return jpaRepository.findUnconfirmedPastDeadline(now).stream()
                .map(mapper::toDomain)
                .map(this::enrichDomain)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<Auction> findAllById(List<Long> ids) {
        return jpaRepository.findAllById(ids).stream()
                .map(mapper::toDomain)
                .map(this::enrichDomain)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public Page<Auction> findByWinnerId(Long winnerId, Pageable pageable) {
        return jpaRepository.findByWinnerId(winnerId, pageable)
                .map(mapper::toDomain)
                .map(this::enrichDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Auction> findActiveEndingBetween(OffsetDateTime from, OffsetDateTime to) {
        return jpaRepository.findActiveEndingBetween(from, to).stream()
                .map(mapper::toDomain)
                .map(this::enrichDomain)
                .collect(Collectors.toList());
    }
}