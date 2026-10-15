package com.example.autoauction.user.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SpringDataAdminUserActionJpaRepository extends JpaRepository<JpaAdminUserActionEntity, Long> {

    List<JpaAdminUserActionEntity> findByTargetUserIdOrderByCreatedAtDescIdDesc(Long targetUserId);
}
