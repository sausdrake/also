package com.example.autoauction.diagnostic.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface SpringDataInspectionAnswerJpaRepository extends JpaRepository<JpaInspectionAnswerEntity, Long> {

    List<JpaInspectionAnswerEntity> findByDiagnosticRequestId(Long diagnosticRequestId);

    void deleteByDiagnosticRequestId(Long diagnosticRequestId);
}