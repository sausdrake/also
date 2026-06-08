package com.example.autoauction.subscription.infrastructure.persistence;

import com.example.autoauction.subscription.domain.port.LotSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class JpaLotSubscriptionRepositoryAdapter implements LotSubscriptionRepository {

    private final SpringDataLotSubscriptionJpaRepository jpaRepo;

    @Override
    public boolean isSubscribed(Long userId, Long auctionId) {
        return jpaRepo.existsByUserIdAndAuctionId(userId, auctionId);
    }

    @Override
    @Transactional
    public void subscribe(Long userId, Long auctionId) {
        if (!jpaRepo.existsByUserIdAndAuctionId(userId, auctionId)) {
            jpaRepo.save(new JpaLotSubscriptionEntity(userId, auctionId));
        }
    }

    @Override
    @Transactional
    public void unsubscribe(Long userId, Long auctionId) {
        jpaRepo.deleteByUserIdAndAuctionId(userId, auctionId);
    }

    @Override
    public List<Long> getAuctionIds(Long userId) {
        return jpaRepo.findByUserId(userId).stream()
                .map(JpaLotSubscriptionEntity::getAuctionId)
                .collect(Collectors.toList());
    }

    @Override
    public List<Long> getSubscriberIds(Long auctionId) {
        return jpaRepo.findByAuctionId(auctionId).stream()
                .map(JpaLotSubscriptionEntity::getUserId)
                .collect(Collectors.toList());
    }
}
