package com.example.autoauction.vehicle.domain.port;

import com.example.autoauction.vehicle.domain.Vehicle;
import com.example.autoauction.vehicle.domain.VehicleStatus;

import java.util.List;
import java.util.Optional;

public interface VehicleRepository {
    Vehicle save(Vehicle vehicle);
    Optional<Vehicle> findById(Long id);
    List<Vehicle> findAll();
    List<Vehicle> findByStatus(VehicleStatus status);
    List<Vehicle> findByDiagnosticId(Long diagnosticId);
    List<Vehicle> findByDiagnosticIdAndStatus(Long diagnosticId, VehicleStatus status);
    List<Vehicle> findByStatusIn(List<VehicleStatus> statuses);
    Optional<Vehicle> findByVin(String vin);
    boolean existsByVin(String vin);
    /** Sale-UI: the one Vehicle created from this request, if any (diagnostic_request_id is UNIQUE). */
    Optional<Vehicle> findByDiagnosticRequestId(Long diagnosticRequestId);
    void deleteById(Long id);
}