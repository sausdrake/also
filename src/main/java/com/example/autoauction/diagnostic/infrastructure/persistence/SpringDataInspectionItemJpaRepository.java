package com.example.autoauction.diagnostic.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface SpringDataInspectionItemJpaRepository extends JpaRepository<JpaInspectionItemEntity, Long> {

    List<JpaInspectionItemEntity> findByGroupIdOrderBySortOrderAsc(Long groupId);

    void deleteByGroupId(Long groupId);
}