// auction/domain/port/AuctionRepository.java
package com.example.autoauction.auction.domain.port;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

public interface AuctionRepository {
    // Базовые CRUD операции
    Auction save(Auction auction);
    Optional<Auction> findById(Long id);
    List<Auction> findAll();
    void deleteById(Long id);

    // НОВЫЙ МЕТОД с пагинацией
    Page<Auction> findAllWithPagination(Pageable pageable);

    // Поиск по статусу
    List<Auction> findByStatus(AuctionStatus status);

    // НОВЫЙ МЕТОД с пагинацией
    Page<Auction> findByStatusWithPagination(AuctionStatus status, Pageable pageable);

    // Поиск по автомобилю
    List<Auction> findByVehicleId(Long vehicleId);

    // Поиск последнего аукциона по автомобилю
    Optional<Auction> findFirstByVehicleIdOrderByCreatedAtDesc(Long vehicleId);

    // Проверка существования аукциона с конкретным статусом
    boolean existsByVehicleIdAndStatus(Long vehicleId, AuctionStatus status);

    // Проверка наличия любого из запрещённых статусов
    boolean existsByVehicleIdAndStatusIn(Long vehicleId, List<AuctionStatus> statuses);

    // Получение всех статусов автомобиля одним запросом
    List<AuctionStatus> findStatusesByVehicleId(Long vehicleId);

    // Поиск с пессимистичной блокировкой
    Optional<Auction> findByIdWithLock(Long id);

    // Для шедулера
    List<Auction> findByStatusAndEndTimeBefore(AuctionStatus status, java.time.OffsetDateTime endTime);

    // Подтверждённые аукционы, готовые к запуску
    List<Auction> findConfirmedAuctionsReadyToStart(java.time.OffsetDateTime now);

    // Неподтверждённые аукционы с истёкшим дедлайном
    List<Auction> findUnconfirmedAuctionsPastDeadline(java.time.OffsetDateTime now);

    // Найти по списку id
    List<Auction> findAllById(List<Long> ids);

    // Аукционы, выигранные конкретным пользователем
    Page<Auction> findByWinnerId(Long winnerId, Pageable pageable);

    // Активные аукционы, завершающиеся в заданном временном окне
    List<Auction> findActiveEndingBetween(java.time.OffsetDateTime from, java.time.OffsetDateTime to);

    // X3-C: application-level pre-check used by AuctionCommitmentResolver to reject a
    // commitment already linked to another auction with a clear error before attempting to
    // persist. The DB UNIQUE constraint on seller_diagnostic_commitment_id is the final,
    // authoritative backstop for the concurrent-creation race this check cannot itself close.
    boolean existsBySellerDiagnosticCommitmentId(Long sellerDiagnosticCommitmentId);
}