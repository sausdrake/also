package com.example.autoauction.vehicle.infrastructure.persistence;

import com.example.autoauction.vehicle.domain.Vehicle;
import com.example.autoauction.vehicle.domain.VehicleStatus;
import com.example.autoauction.vehicle.domain.port.VehicleRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Repository
@Transactional
public class JpaVehicleRepositoryAdapter implements VehicleRepository {

    private final SpringDataVehicleJpaRepository jpaRepository;
    private final VehicleMapper mapper;

    public JpaVehicleRepositoryAdapter(
            SpringDataVehicleJpaRepository jpaRepository,
            VehicleMapper mapper) {
        this.jpaRepository = jpaRepository;
        this.mapper = mapper;
    }

    @Override
    public Vehicle save(Vehicle vehicle) {
        JpaVehicleEntity entity = mapper.toEntity(vehicle);
        JpaVehicleEntity saved = jpaRepository.save(entity);
        return mapper.toDomain(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Vehicle> findById(Long id) {
        return jpaRepository.findById(id)
                .map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Vehicle> findAll() {
        return jpaRepository.findAll().stream()
                .map(mapper::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<Vehicle> findByStatus(VehicleStatus status) {
        return jpaRepository.findByStatus(status).stream()
                .map(mapper::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<Vehicle> findByDiagnosticId(Long diagnosticId) {
        return jpaRepository.findByDiagnosticId(diagnosticId).stream()
                .map(mapper::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<Vehicle> findByDiagnosticIdAndStatus(Long diagnosticId, VehicleStatus status) {
        return jpaRepository.findByDiagnosticIdAndStatus(diagnosticId, status).stream()
                .map(mapper::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<Vehicle> findByStatusIn(List<VehicleStatus> statuses) {
        return jpaRepository.findByStatusIn(statuses).stream()
                .map(mapper::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Vehicle> findByVin(String vin) {
        return jpaRepository.findByVin(vin)
                .map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByVin(String vin) {
        return jpaRepository.existsByVin(vin);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Vehicle> findByDiagnosticRequestId(Long diagnosticRequestId) {
        return jpaRepository.findByDiagnosticRequestId(diagnosticRequestId)
                .map(mapper::toDomain);
    }

    @Override
    public void deleteById(Long id) {
        jpaRepository.deleteById(id);
    }
}