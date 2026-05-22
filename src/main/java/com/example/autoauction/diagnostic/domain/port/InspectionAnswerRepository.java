package com.example.autoauction.diagnostic.domain.port;

import com.example.autoauction.diagnostic.domain.InspectionAnswer;
import java.util.List;

public interface InspectionAnswerRepository {

    InspectionAnswer save(InspectionAnswer answer);

    List<InspectionAnswer> saveAll(List<InspectionAnswer> answers);

    List<InspectionAnswer> findByDiagnosticRequestId(Long requestId);

    void deleteByDiagnosticRequestId(Long requestId);
}