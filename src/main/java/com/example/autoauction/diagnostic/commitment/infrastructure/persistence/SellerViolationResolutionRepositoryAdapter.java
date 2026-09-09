package com.example.autoauction.diagnostic.commitment.infrastructure.persistence;

import com.example.autoauction.diagnostic.commitment.domain.SellerViolationResolution;
import com.example.autoauction.diagnostic.commitment.domain.SellerViolationResolutionItem;
import com.example.autoauction.diagnostic.commitment.domain.port.SellerViolationResolutionRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Component
public class SellerViolationResolutionRepositoryAdapter implements SellerViolationResolutionRepository {

    private final SpringDataSellerViolationResolutionJpaRepository resolutionJpaRepository;
    private final SpringDataSellerViolationResolutionItemJpaRepository itemJpaRepository;
    private final SellerViolationResolutionMapper mapper;

    public SellerViolationResolutionRepositoryAdapter(SpringDataSellerViolationResolutionJpaRepository resolutionJpaRepository,
                                                        SpringDataSellerViolationResolutionItemJpaRepository itemJpaRepository,
                                                        SellerViolationResolutionMapper mapper) {
        this.resolutionJpaRepository = resolutionJpaRepository;
        this.itemJpaRepository = itemJpaRepository;
        this.mapper = mapper;
    }

    /**
     * Header is saved first (its generated id is required to wire each item's FK) - both
     * inserts happen in the same caller transaction (no @Transactional REQUIRES_NEW here),
     * so a failure anywhere in the caller's own transaction still rolls back both.
     */
    @Override
    @Transactional
    public SellerViolationResolution saveWithItems(SellerViolationResolution resolution,
                                                    List<SellerViolationResolutionItem> items) {
        JpaSellerViolationResolutionEntity savedHeader = resolutionJpaRepository.save(mapper.toEntity(resolution));
        for (SellerViolationResolutionItem item : items) {
            itemJpaRepository.save(mapper.toItemEntity(item, savedHeader.getId()));
        }
        return mapper.toDomain(savedHeader);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SellerViolationResolution> findBySellerDiagnosticCommitmentId(Long sellerDiagnosticCommitmentId) {
        return resolutionJpaRepository.findBySellerDiagnosticCommitmentId(sellerDiagnosticCommitmentId).map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SellerViolationResolutionItem> findItemsByResolutionId(Long sellerViolationResolutionId) {
        return itemJpaRepository.findBySellerViolationResolutionId(sellerViolationResolutionId).stream()
                .map(mapper::toItemDomain).toList();
    }
}
