package com.example.autoauction.settlement.infrastructure.persistence;

import com.example.autoauction.settlement.domain.SettlementCorrection;
import com.example.autoauction.settlement.domain.port.SettlementCorrectionRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Component
public class SettlementCorrectionRepositoryAdapter implements SettlementCorrectionRepository {

    private final SpringDataSettlementCorrectionJpaRepository jpaRepository;
    private final SettlementCorrectionMapper mapper;

    public SettlementCorrectionRepositoryAdapter(SpringDataSettlementCorrectionJpaRepository jpaRepository,
                                                  SettlementCorrectionMapper mapper) {
        this.jpaRepository = jpaRepository;
        this.mapper = mapper;
    }

    /**
     * Phase 5 corrective fix: REQUIRES_NEW deliberately, not the default REQUIRED - mirrors
     * {@code DepositService.unfreezeForAuction}'s own established reasoning (R-1.6) for the same
     * underlying hazard. {@code idempotency_key} is enforced by a real unique constraint (V44);
     * on a concurrent same-key race the loser's INSERT fails with a
     * {@code DataIntegrityViolationException}, and PostgreSQL marks the ENTIRE transaction that
     * statement ran in as aborted - no further statement can succeed on it. The sole caller,
     * {@code SettlementService.recordCorrection}, must run a recovery read
     * ({@code findByIdempotencyKey}) immediately after catching that exception to distinguish a
     * safe replay from a genuine conflict; without REQUIRES_NEW here, that recovery read would
     * share the caller's Hibernate session with the entity that just failed to insert, and any
     * later flush (including the outer transaction's own commit-time flush) throws
     * {@code AssertionFailure: null id ... (don't flush the Session after an exception occurs)}
     * instead of completing cleanly. REQUIRES_NEW gives the INSERT attempt its own independent
     * transaction/connection/session: a failure here rolls back only this one insert, invisibly
     * to the caller's still-healthy transaction, which is then free to safely read the
     * now-committed winning row. There is exactly one caller of this method in the codebase
     * today, and it performs no other write in the same business transaction that would need to
     * be atomic with this insert - so this is a safe, self-contained transactional boundary, not
     * a behavior change for any other use case.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SettlementCorrection save(SettlementCorrection correction) {
        return mapper.toDomain(jpaRepository.save(mapper.toEntity(correction)));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SettlementCorrection> findByIdempotencyKey(String idempotencyKey) {
        return jpaRepository.findByIdempotencyKey(idempotencyKey).map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SettlementCorrection> findBySettlementIdOrderByCorrectedAtAscIdAsc(Long settlementId) {
        return jpaRepository.findBySettlementIdOrderByCorrectedAtAscIdAsc(settlementId).stream()
                .map(mapper::toDomain).toList();
    }
}
