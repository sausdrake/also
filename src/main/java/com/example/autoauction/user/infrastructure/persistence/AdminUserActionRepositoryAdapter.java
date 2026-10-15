package com.example.autoauction.user.infrastructure.persistence;

import com.example.autoauction.user.domain.AdminUserAction;
import com.example.autoauction.user.domain.port.AdminUserActionRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
public class AdminUserActionRepositoryAdapter implements AdminUserActionRepository {

    private final SpringDataAdminUserActionJpaRepository jpaRepository;

    public AdminUserActionRepositoryAdapter(SpringDataAdminUserActionJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    @Transactional
    public AdminUserAction append(AdminUserAction a) {
        return toDomain(jpaRepository.save(new JpaAdminUserActionEntity(
                a.targetUserId(), a.adminId(), a.action(), a.details(), a.createdAt())));
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdminUserAction> findByTargetUserIdNewestFirst(Long targetUserId) {
        return jpaRepository.findByTargetUserIdOrderByCreatedAtDescIdDesc(targetUserId).stream()
                .map(AdminUserActionRepositoryAdapter::toDomain).toList();
    }

    private static AdminUserAction toDomain(JpaAdminUserActionEntity e) {
        return new AdminUserAction(e.getId(), e.getTargetUserId(), e.getAdminId(), e.getAction(), e.getDetails(),
                e.getCreatedAt());
    }
}
