package com.example.autoauction.diagnostic.web.dto;

import lombok.Data;
import java.util.List;

@Data
public class SubmitInspectionRequest {
    private List<InspectionAnswerDto> answers;
    private boolean submit;  // true = отправить на проверку, false = сохранить черновик
}