package com.example.autoauction.identity.infrastructure.persistence;

import com.example.autoauction.identity.domain.IdentityVerification;
import com.example.autoauction.identity.domain.IdentityVerificationStatus;
import com.example.autoauction.identity.domain.port.IdentityVerificationRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Phase 3.1 §1: {@link #save} ALWAYS uses {@code saveAndFlush}, never plain {@code save} -
 * deliberately, for every call, not only the approve path. An {@code UPDATE} on an
 * already-managed entity carries no automatic forcing-flush (unlike a
 * {@code GenerationType.IDENTITY} {@code INSERT}, which Hibernate must flush immediately to get
 * the generated key back - the reason {@code SettlementCorrectionRepositoryAdapter}'s plain
 * {@code save()} already catches its own race correctly). Without an explicit flush here, the
 * real {@code UPDATE ... SET status = 'VERIFIED'} statement - and therefore the partial unique
 * index violation it may trigger - could be deferred by Hibernate to the transaction's own
 * commit-time auto-flush, which happens inside Spring's transaction-advice AFTER the service
 * method has already returned, outside any try/catch the method body could write. Flushing here
 * makes the constraint check happen at a point in the call stack the calling service method can
 * actually catch. The application layer never needs to know this - it just calls
 * {@code repository.save(...)} like any other port method.
 */
@Component
public class IdentityVerificationRepositoryAdapter implements IdentityVerificationRepository {

    private final SpringDataIdentityVerificationJpaRepository jpaRepository;
    private final IdentityVerificationMapper mapper;

    public IdentityVerificationRepositoryAdapter(SpringDataIdentityVerificationJpaRepository jpaRepository,
                                                  IdentityVerificationMapper mapper) {
        this.jpaRepository = jpaRepository;
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public IdentityVerification save(IdentityVerification identityVerification) {
        return mapper.toDomain(jpaRepository.saveAndFlush(mapper.toEntity(identityVerification)));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<IdentityVerification> findById(Long id) {
        return jpaRepository.findById(id).map(mapper::toDomain);
    }

    @Override
    @Transactional
    public Optional<IdentityVerification> findByIdWithLock(Long id) {
        return jpaRepository.findByIdWithLock(id).map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<IdentityVerification> findByUserId(Long userId) {
        return jpaRepository.findByUserId(userId).map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<IdentityVerification> findByUserIdIn(Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return List.of();
        }
        return jpaRepository.findByUserIdIn(userIds).stream().map(mapper::toDomain).toList();
    }

    @Override
    @Transactional
    public Optional<IdentityVerification> findByUserIdWithLock(Long userId) {
        return jpaRepository.findByUserIdWithLock(userId).map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<IdentityVerification> findByStatusOrderByCreatedAtAsc(IdentityVerificationStatus status) {
        return jpaRepository.findByStatusOrderByCreatedAtAsc(status).stream().map(mapper::toDomain).toList();
    }
}
