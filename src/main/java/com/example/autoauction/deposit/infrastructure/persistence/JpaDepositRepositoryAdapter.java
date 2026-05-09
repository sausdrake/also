// deposit/infrastructure/persistence/JpaDepositRepositoryAdapter.java
package com.example.autoauction.deposit.infrastructure.persistence;

import com.example.autoauction.deposit.domain.Deposit;
import com.example.autoauction.deposit.domain.DepositTransaction;
import com.example.autoauction.deposit.domain.FreezeReason;
import com.example.autoauction.deposit.domain.FrozenDeposit;
import com.example.autoauction.deposit.domain.port.DepositRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Repository
@Transactional
public class JpaDepositRepositoryAdapter implements DepositRepository {

    private final SpringDataDepositJpaRepository depositJpaRepository;
    private final SpringDataDepositTransactionJpaRepository transactionJpaRepository;
    private final SpringDataDepositFrozenJpaRepository frozenJpaRepository;
    private final DepositMapper mapper;
    private final DepositFrozenMapper frozenMapper;

    public JpaDepositRepositoryAdapter(SpringDataDepositJpaRepository depositJpaRepository,
                                       SpringDataDepositTransactionJpaRepository transactionJpaRepository,
                                       SpringDataDepositFrozenJpaRepository frozenJpaRepository,
                                       DepositMapper mapper,
                                       DepositFrozenMapper frozenMapper) {
        this.depositJpaRepository = depositJpaRepository;
        this.transactionJpaRepository = transactionJpaRepository;
        this.frozenJpaRepository = frozenJpaRepository;
        this.mapper = mapper;
        this.frozenMapper = frozenMapper;
        log.info("JpaDepositRepositoryAdapter initialized");
    }

    @Override
    public Deposit save(Deposit deposit) {
        JpaDepositEntity entity = mapper.toEntity(deposit);
        JpaDepositEntity saved = depositJpaRepository.save(entity);
        return mapper.toDomain(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Deposit> findById(Long id) {
        return depositJpaRepository.findById(id).map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Deposit> findByUserId(Long userId) {
        return depositJpaRepository.findByUserId(userId).map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Deposit> findAll() {
        return depositJpaRepository.findAll().stream()
                .map(mapper::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public void deleteById(Long id) {
        depositJpaRepository.deleteById(id);
    }

    @Override
    public DepositTransaction saveTransaction(DepositTransaction transaction) {
        JpaDepositTransactionEntity entity = mapper.toEntity(transaction);
        JpaDepositTransactionEntity saved = transactionJpaRepository.save(entity);
        return mapper.toDomain(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DepositTransaction> findTransactionsByUserId(Long userId) {
        return transactionJpaRepository.findByUserId(userId).stream()
                .map(mapper::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<DepositTransaction> findTransactionsByDepositId(Long depositId) {
        return transactionJpaRepository.findByDepositId(depositId).stream()
                .map(mapper::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<DepositTransaction> findTransactionsByAuctionId(Long auctionId) {
        return transactionJpaRepository.findByAuctionId(auctionId).stream()
                .map(mapper::toDomain)
                .collect(Collectors.toList());
    }

    // НОВЫЕ МЕТОДЫ
    @Override
    public void freezeForAuction(Long depositId, Long auctionId, java.math.BigDecimal amount, FreezeReason reason) {
        if (frozenJpaRepository.existsByDepositIdAndAuctionId(depositId, auctionId)) {
            throw new IllegalStateException("Already frozen for this auction");
        }

        JpaDepositFrozenEntity entity = new JpaDepositFrozenEntity(depositId, auctionId, amount, reason);
        frozenJpaRepository.save(entity);
        log.debug("Frozen deposit {} for auction {} with amount {}", depositId, auctionId, amount);
    }

    @Override
    public void unfreezeForAuction(Long depositId, Long auctionId) {
        frozenJpaRepository.findByDepositIdAndAuctionId(depositId, auctionId)
                .ifPresent(frozenJpaRepository::delete);
        log.debug("Unfrozen deposit {} for auction {}", depositId, auctionId);
    }

    @Override
    public void markFrozenAsWinner(Long depositId, Long auctionId) {
        frozenJpaRepository.markAsWinner(depositId, auctionId);
        log.debug("Marked deposit {} as winner for auction {}", depositId, auctionId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<FrozenDeposit> getFrozenDepositsByDepositId(Long depositId) {
        return frozenJpaRepository.findByDepositId(depositId).stream()
                .map(frozenMapper::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<FrozenDeposit> getFrozenDepositsByAuctionId(Long auctionId) {
        return frozenJpaRepository.findByAuctionId(auctionId).stream()
                .map(frozenMapper::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isFrozenForAuction(Long depositId, Long auctionId) {
        return frozenJpaRepository.existsByDepositIdAndAuctionId(depositId, auctionId);
    }

    @Override
    public void clearFrozenForAuction(Long auctionId) {
        frozenJpaRepository.deleteByAuctionId(auctionId);
        log.debug("Cleared all frozen deposits for auction {}", auctionId);
    }
}