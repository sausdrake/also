package com.example.autoauction.payment.infrastructure.persistence;

import com.example.autoauction.payment.domain.ReconciliationCase;
import com.example.autoauction.payment.domain.ReconciliationCaseStatus;
import com.example.autoauction.payment.domain.ReconciliationCaseType;
import com.example.autoauction.payment.domain.port.ReconciliationCaseRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Component
public class ReconciliationCaseRepositoryAdapter implements ReconciliationCaseRepository {

    private final SpringDataReconciliationCaseJpaRepository jpaRepository;
    private final ReconciliationCaseMapper mapper;

    public ReconciliationCaseRepositoryAdapter(SpringDataReconciliationCaseJpaRepository jpaRepository,
                                               ReconciliationCaseMapper mapper) {
        this.jpaRepository = jpaRepository;
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public ReconciliationCase save(ReconciliationCase reconciliationCase) {
        return mapper.toDomain(jpaRepository.saveAndFlush(mapper.toEntity(reconciliationCase)));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ReconciliationCase> findById(Long id) {
        return jpaRepository.findById(id).map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ReconciliationCase> findByPaymentIdAndType(Long paymentId, ReconciliationCaseType type) {
        return jpaRepository.findByPaymentIdAndType(paymentId, type).map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReconciliationCase> findOpen() {
        return jpaRepository.findByStatus(ReconciliationCaseStatus.OPEN).stream().map(mapper::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReconciliationCase> findAllNewestFirst() {
        return jpaRepository.findAllByOrderByOpenedAtDescIdDesc().stream().map(mapper::toDomain).toList();
    }
}
