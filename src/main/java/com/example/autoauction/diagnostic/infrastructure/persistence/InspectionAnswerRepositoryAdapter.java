package com.example.autoauction.diagnostic.infrastructure.persistence;

import com.example.autoauction.diagnostic.domain.InspectionAnswer;
import com.example.autoauction.diagnostic.domain.port.InspectionAnswerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Repository
@RequiredArgsConstructor
@Transactional
public class InspectionAnswerRepositoryAdapter implements InspectionAnswerRepository {

    private final SpringDataInspectionAnswerJpaRepository jpaRepository;
    private final InspectionAnswerMapper mapper;

    @Override
    public InspectionAnswer save(InspectionAnswer answer) {
        JpaInspectionAnswerEntity entity = mapper.toEntity(answer);
        JpaInspectionAnswerEntity saved = jpaRepository.save(entity);
        log.debug("Saved answer: requestId={}, itemId={}", saved.getDiagnosticRequestId(), saved.getInspectionItemId());
        return mapper.toDomain(saved);
    }

    @Override
    public List<InspectionAnswer> saveAll(List<InspectionAnswer> answers) {
        List<JpaInspectionAnswerEntity> entities = answers.stream()
                .map(mapper::toEntity)
                .collect(Collectors.toList());
        List<JpaInspectionAnswerEntity> saved = jpaRepository.saveAll(entities);
        log.info("Saved {} answers for requestId={}", saved.size(),
                saved.isEmpty() ? null : saved.get(0).getDiagnosticRequestId());
        return saved.stream().map(mapper::toDomain).collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<InspectionAnswer> findByDiagnosticRequestId(Long requestId) {
        return jpaRepository.findByDiagnosticRequestId(requestId)
                .stream()
                .map(mapper::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public void deleteByDiagnosticRequestId(Long requestId) {
        jpaRepository.deleteByDiagnosticRequestId(requestId);
        log.info("Deleted answers for requestId={}", requestId);
    }
}