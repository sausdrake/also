package com.example.autoauction.diagnostic.infrastructure.persistence;

import com.example.autoauction.diagnostic.domain.InspectionAnswer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.ArrayList;

@Slf4j
@Component
@RequiredArgsConstructor
public class InspectionAnswerMapper {

    public JpaInspectionAnswerEntity toEntity(InspectionAnswer domain) {
        if (domain == null) return null;

        JpaInspectionAnswerEntity entity = new JpaInspectionAnswerEntity();
        entity.setId(domain.getId());
        entity.setDiagnosticRequestId(domain.getDiagnosticRequestId());
        entity.setInspectionItemId(domain.getInspectionItemId());
        entity.setRating(domain.getRating());
        entity.setComment(domain.getComment());
        entity.setPhotoUrls(domain.getPhotoUrls() != null ? domain.getPhotoUrls() : new ArrayList<>());
        entity.setVideoUrls(domain.getVideoUrls() != null ? domain.getVideoUrls() : new ArrayList<>());
        entity.setSkipped(domain.isSkipped());
        entity.setCreatedAt(domain.getCreatedAt());
        entity.setUpdatedAt(domain.getUpdatedAt());
        return entity;
    }

    public InspectionAnswer toDomain(JpaInspectionAnswerEntity entity) {
        if (entity == null) return null;

        InspectionAnswer domain = new InspectionAnswer();
        domain.setId(entity.getId());
        domain.setDiagnosticRequestId(entity.getDiagnosticRequestId());
        domain.setInspectionItemId(entity.getInspectionItemId());
        domain.setRating(entity.getRating());
        domain.setComment(entity.getComment());
        domain.setPhotoUrls(entity.getPhotoUrls() != null ? entity.getPhotoUrls() : new ArrayList<>());
        domain.setVideoUrls(entity.getVideoUrls() != null ? entity.getVideoUrls() : new ArrayList<>());
        domain.setSkipped(entity.isSkipped());
        domain.setCreatedAt(entity.getCreatedAt());
        domain.setUpdatedAt(entity.getUpdatedAt());
        return domain;
    }
}