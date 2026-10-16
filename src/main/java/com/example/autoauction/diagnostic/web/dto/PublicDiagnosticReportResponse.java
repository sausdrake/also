package com.example.autoauction.diagnostic.web.dto;

import com.example.autoauction.diagnostic.domain.InspectionAnswer;
import com.example.autoauction.diagnostic.domain.InspectionGroup;
import com.example.autoauction.diagnostic.domain.InspectionItem;
import com.example.autoauction.diagnostic.domain.InspectionTemplate;

import java.util.List;

/**
 * SEC-12: what an anonymous visitor sees of an approved diagnostic report - exactly what the public
 * auction page renders, field by field (allowlist). Nothing about the seller, money, staff, the
 * commitment, violations or audit: no userId/userName, depositFrozen, assignedTo/diagnosticName,
 * commitmentStatus, violationResolution, rejection or re-diagnostic data, and no createdBy,
 * timestamps or request ids inside the template and answers. {@link DiagnosticRequestResponse}
 * stays the authenticated, role-checked view. {@code id} is kept: clients follow the newest report
 * of a vehicle by it (re-diagnostic).
 */
public record PublicDiagnosticReportResponse(
        Long id,
        String vin,
        PublicTemplate template,
        List<PublicAnswer> existingAnswers
) {
    public record PublicTemplate(String name, List<PublicGroup> groups) {}

    public record PublicGroup(Long id, String name, List<PublicItem> items) {}

    public record PublicItem(Long id, String title, String fieldType, String description) {}

    public record PublicAnswer(Long inspectionItemId, Integer rating, String comment, List<String> photoUrls,
                               boolean skipped) {}

    public static PublicDiagnosticReportResponse from(DiagnosticRequestResponse r) {
        return new PublicDiagnosticReportResponse(r.getId(), r.getVin(), template(r.getTemplate()),
                r.getExistingAnswers() == null ? List.of()
                        : r.getExistingAnswers().stream().map(PublicDiagnosticReportResponse::answer).toList());
    }

    private static PublicTemplate template(InspectionTemplate t) {
        if (t == null) {
            return null;
        }
        List<PublicGroup> groups = t.getGroups() == null ? List.of() : t.getGroups().stream()
                .map(PublicDiagnosticReportResponse::group).toList();
        return new PublicTemplate(t.getName(), groups);
    }

    private static PublicGroup group(InspectionGroup g) {
        List<PublicItem> items = g.getItems() == null ? List.of() : g.getItems().stream()
                .map(PublicDiagnosticReportResponse::item).toList();
        return new PublicGroup(g.getId(), g.getName(), items);
    }

    private static PublicItem item(InspectionItem i) {
        return new PublicItem(i.getId(), i.getTitle(), i.getFieldType(), i.getDescription());
    }

    private static PublicAnswer answer(InspectionAnswer a) {
        return new PublicAnswer(a.getInspectionItemId(), a.getRating(), a.getComment(), a.getPhotoUrls(), a.isSkipped());
    }
}
