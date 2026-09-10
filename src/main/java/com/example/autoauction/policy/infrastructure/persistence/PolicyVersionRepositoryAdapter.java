package com.example.autoauction.policy.infrastructure.persistence;

import com.example.autoauction.policy.domain.PolicyVersion;
import com.example.autoauction.policy.domain.port.PolicyVersionRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Component
public class PolicyVersionRepositoryAdapter implements PolicyVersionRepository {

    private static final int COUNTER_ID = 1;

    private final SpringDataPolicyVersionJpaRepository jpaRepository;
    private final SpringDataPolicyVersionCounterJpaRepository counterRepository;
    private final PolicyVersionMapper mapper;

    public PolicyVersionRepositoryAdapter(SpringDataPolicyVersionJpaRepository jpaRepository,
                                           SpringDataPolicyVersionCounterJpaRepository counterRepository,
                                           PolicyVersionMapper mapper) {
        this.jpaRepository = jpaRepository;
        this.counterRepository = counterRepository;
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public PolicyVersion save(PolicyVersion policyVersion) {
        JpaPolicyVersionEntity entity = mapper.toEntity(policyVersion);
        if (entity.getVersionNumber() == null) {
            entity.setVersionNumber(claimNextVersionNumber());
        }
        JpaPolicyVersionEntity saved = jpaRepository.save(entity);
        return mapper.toDomain(saved);
    }

    /**
     * Locks the single counter row for the remainder of the current transaction, reads its
     * current next_value, increments and persists it, and returns the value claimed for this
     * PolicyVersion. Any concurrent transaction attempting the same claim blocks on the row
     * lock until this transaction commits or rolls back - no two callers can ever read the
     * same next_value.
     */
    private Long claimNextVersionNumber() {
        JpaPolicyVersionCounterEntity counter = counterRepository.findByIdWithLock(COUNTER_ID)
                .orElseGet(this::initializeCounter);
        Long assigned = counter.getNextValue();
        counter.setNextValue(assigned + 1);
        counterRepository.save(counter);
        return assigned;
    }

    /**
     * Self-heals the counter row if it does not yet exist. In a real deployment, V32's
     * migration already seeds it, so this path is never taken there; it exists purely because
     * this project's test profile derives its schema from Hibernate ddl-auto rather than
     * Flyway (see application-test.properties), so the migration's own seed INSERT is never
     * executed against the test database. If a concurrent caller wins the race to insert the
     * seed row first, this falls back to a locked read of the now-existing row rather than
     * failing.
     */
    private JpaPolicyVersionCounterEntity initializeCounter() {
        try {
            JpaPolicyVersionCounterEntity seed = new JpaPolicyVersionCounterEntity();
            seed.setId(COUNTER_ID);
            seed.setNextValue(1L);
            return counterRepository.saveAndFlush(seed);
        } catch (DataIntegrityViolationException raceLost) {
            return counterRepository.findByIdWithLock(COUNTER_ID)
                    .orElseThrow(() -> new IllegalStateException(
                            "policy_version_counter seed row could not be created or found", raceLost));
        }
    }

    @Override
    public Optional<PolicyVersion> findById(Long id) {
        return jpaRepository.findById(id).map(mapper::toDomain);
    }

    @Override
    public Optional<PolicyVersion> findCurrentPublished() {
        return jpaRepository.findFirstByPublishedTrueOrderByVersionNumberDesc().map(mapper::toDomain);
    }

    @Override
    public boolean claimPublish(Long id) {
        return jpaRepository.claimPublish(id) > 0;
    }

    @Override
    public List<PolicyVersion> findAllOrderByVersionNumberDesc() {
        return jpaRepository.findAllByOrderByVersionNumberDesc().stream().map(mapper::toDomain).toList();
    }

    @Override
    public List<PolicyVersion> findByPublishedOrderByVersionNumberDesc(boolean published) {
        return jpaRepository.findByPublishedOrderByVersionNumberDesc(published).stream().map(mapper::toDomain).toList();
    }
}
