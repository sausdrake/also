package com.example.autoauction.diagnostic.infrastructure.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface SpringDataInspectionTemplateJpaRepository extends JpaRepository<JpaInspectionTemplateEntity, Long> {

    Page<JpaInspectionTemplateEntity> findByIsActiveTrue(Pageable pageable);

    List<JpaInspectionTemplateEntity> findByVehicleType(String vehicleType);

    Optional<JpaInspectionTemplateEntity> findByIdAndIsActiveTrue(Long id);
}