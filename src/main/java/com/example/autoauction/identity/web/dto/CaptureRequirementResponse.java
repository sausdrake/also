package com.example.autoauction.identity.web.dto;

import java.util.List;

/** KYC-CAPTURE-01: the sides a document type must be photographed with, in capture order. */
public record CaptureRequirementResponse(String documentType, List<String> sides) {
}
