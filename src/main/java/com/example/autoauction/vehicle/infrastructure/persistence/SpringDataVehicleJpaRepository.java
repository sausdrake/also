package com.example.autoauction.vehicle.infrastructure.persistence;

import com.example.autoauction.vehicle.domain.VehicleStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SpringDataVehicleJpaRepository extends JpaRepository<JpaVehicleEntity, Long> {

    Optional<JpaVehicleEntity> findByVin(String vin);

    boolean existsByVin(String vin);

    List<JpaVehicleEntity> findByStatus(VehicleStatus status);

    List<JpaVehicleEntity> findByDiagnosticId(Long diagnosticId);

    List<JpaVehicleEntity> findByDiagnosticIdAndStatus(Long diagnosticId, VehicleStatus status);

    List<JpaVehicleEntity> findByStatusIn(List<VehicleStatus> statuses);

    Optional<JpaVehicleEntity> findByDiagnosticRequestId(Long diagnosticRequestId);
}