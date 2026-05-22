package com.example.autoauction.diagnostic.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface SpringDataInspectionGroupJpaRepository extends JpaRepository<JpaInspectionGroupEntity, Long> {

    List<JpaInspectionGroupEntity> findByTemplateIdOrderBySortOrderAsc(Long templateId);

    void deleteByTemplateId(Long templateId);
}