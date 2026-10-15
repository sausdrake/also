package com.example.autoauction.auction.application;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.diagnostic.commitment.domain.port.SellerDiagnosticCommitmentRepository;
import com.example.autoauction.diagnostic.domain.DiagnosticRequest;
import com.example.autoauction.diagnostic.domain.port.DiagnosticRequestRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;

/**
 * SEC-09: the seller of an auction - the owner of the vehicle - through the same deterministic
 * chain SettlementService uses: Auction.sellerDiagnosticCommitmentId -> SellerDiagnosticCommitment
 * -> DiagnosticRequest.userId. Never Auction.createdBy: auctions are created by administrators,
 * so createdBy is an admin, not the seller.
 *
 * <p>Empty only for historical auctions created before the commitment linkage (every new auction
 * must carry a commitment - see AuctionCommitmentResolver).
 */
@Component
public class AuctionSellerResolver {

    private final SellerDiagnosticCommitmentRepository commitmentRepository;
    private final DiagnosticRequestRepository diagnosticRequestRepository;

    public AuctionSellerResolver(SellerDiagnosticCommitmentRepository commitmentRepository,
                                 DiagnosticRequestRepository diagnosticRequestRepository) {
        this.commitmentRepository = commitmentRepository;
        this.diagnosticRequestRepository = diagnosticRequestRepository;
    }

    @Transactional(readOnly = true)
    public Optional<Long> resolveSellerId(Auction auction) {
        Long commitmentId = auction.getSellerDiagnosticCommitmentId();
        if (commitmentId == null) {
            return Optional.empty();
        }
        return commitmentRepository.findById(commitmentId)
                .flatMap(commitment -> diagnosticRequestRepository.findById(commitment.getDiagnosticRequestId()))
                .map(DiagnosticRequest::getUserId);
    }

    /** True when {@code userId} is the seller of this auction (false when the seller is unknown). */
    public boolean isSeller(Auction auction, Long userId) {
        return resolveSellerId(auction).map(seller -> Objects.equals(seller, userId)).orElse(false);
    }
}
