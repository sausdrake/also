package com.example.autoauction.diagnostic.web.dto;

import lombok.Data;
import java.util.List;

@Data
public class InspectionAnswerDto {
    private Long itemId;
    private Integer rating;
    private String comment;
    private List<String> photoUrls;
    private List<String> videoUrls;
}