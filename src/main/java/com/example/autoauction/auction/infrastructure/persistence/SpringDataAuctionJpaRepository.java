// auction/infrastructure/persistence/SpringDataAuctionJpaRepository.java
package com.example.autoauction.auction.infrastructure.persistence;

import com.example.autoauction.auction.domain.AuctionStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface SpringDataAuctionJpaRepository extends JpaRepository<JpaAuctionEntity, Long> {

    List<JpaAuctionEntity> findByStatus(AuctionStatus status);

    Page<JpaAuctionEntity> findByStatus(AuctionStatus status, Pageable pageable);

    Page<JpaAuctionEntity> findAll(Pageable pageable);

    List<JpaAuctionEntity> findByVehicleId(Long vehicleId);

    Optional<JpaAuctionEntity> findFirstByVehicleIdOrderByCreatedAtDesc(Long vehicleId);

    boolean existsByVehicleIdAndStatus(Long vehicleId, AuctionStatus status);

    boolean existsByVehicleIdAndStatusIn(Long vehicleId, List<AuctionStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM JpaAuctionEntity a WHERE a.id = :id")
    Optional<JpaAuctionEntity> findByIdWithLock(@Param("id") Long id);

    // НОВЫЙ МЕТОД для шедулера
    @Query("SELECT a FROM JpaAuctionEntity a WHERE a.status = :status AND a.endTime < :endTime")
    List<JpaAuctionEntity> findByStatusAndEndTimeBefore(@Param("status") AuctionStatus status,
                                                        @Param("endTime") OffsetDateTime endTime);

    @Modifying
    @Query("UPDATE JpaAuctionEntity a SET a.status = 'CANCELLED', a.updatedAt = :now " +
            "WHERE a.status = 'CREATED' AND a.startTime < :now")
    int cancelExpiredCreatedAuctions(@Param("now") OffsetDateTime now);

    // Подтверждённые CREATED-аукционы, у которых startTime <= now
    @Query("SELECT a FROM JpaAuctionEntity a WHERE a.status = 'CREATED' " +
            "AND a.confirmedStart = true AND a.startTime <= :now")
    List<JpaAuctionEntity> findConfirmedReadyToStart(@Param("now") OffsetDateTime now);

    // CREATED-аукционы без подтверждения, у которых startTime - confirmHours <= now
    @Query("SELECT a FROM JpaAuctionEntity a WHERE a.status = 'CREATED' " +
            "AND a.confirmedStart = false AND a.startTime <= :deadline")
    List<JpaAuctionEntity> findUnconfirmedPastDeadline(@Param("deadline") OffsetDateTime deadline);

    @Query("SELECT a.status FROM JpaAuctionEntity a WHERE a.vehicleId = :vehicleId")
    List<AuctionStatus> findStatusesByVehicleId(@Param("vehicleId") Long vehicleId);
}