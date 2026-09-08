package com.example.autoauction.sale.infrastructure.persistence;

import com.example.autoauction.sale.domain.FinancialResolutionStatus;
import com.example.autoauction.sale.domain.SaleResolution;
import com.example.autoauction.sale.domain.port.SaleResolutionRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class SaleResolutionRepositoryAdapter implements SaleResolutionRepository {

    private final SpringDataSaleResolutionJpaRepository jpaRepository;
    private final SaleResolutionMapper mapper;

    public SaleResolutionRepositoryAdapter(SpringDataSaleResolutionJpaRepository jpaRepository,
                                            SaleResolutionMapper mapper) {
        this.jpaRepository = jpaRepository;
        this.mapper = mapper;
    }

    @Override
    public SaleResolution save(SaleResolution saleResolution) {
        JpaSaleResolutionEntity entity = mapper.toEntity(saleResolution);
        JpaSaleResolutionEntity saved = jpaRepository.save(entity);
        return mapper.toDomain(saved);
    }

    @Override
    public Optional<SaleResolution> findById(Long id) {
        return jpaRepository.findById(id).map(mapper::toDomain);
    }

    @Override
    public Optional<SaleResolution> findByAuctionId(Long auctionId) {
        return jpaRepository.findByAuctionId(auctionId).map(mapper::toDomain);
    }

    @Override
    public boolean existsByAuctionId(Long auctionId) {
        return jpaRepository.existsByAuctionId(auctionId);
    }

    @Override
    public boolean claimFinancialResolutionDone(Long id) {
        return jpaRepository.claimFinancialResolutionTransition(
                id, FinancialResolutionStatus.PENDING, FinancialResolutionStatus.DONE) > 0;
    }

    @Override
    public boolean claimFinancialResolutionFailed(Long id) {
        return jpaRepository.claimFinancialResolutionTransition(
                id, FinancialResolutionStatus.PENDING, FinancialResolutionStatus.FAILED) > 0;
    }

    @Override
    public boolean resetFinancialResolutionToPending(Long id) {
        return jpaRepository.claimFinancialResolutionTransition(
                id, FinancialResolutionStatus.FAILED, FinancialResolutionStatus.PENDING) > 0;
    }
}
