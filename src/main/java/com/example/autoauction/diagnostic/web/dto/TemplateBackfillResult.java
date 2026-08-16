package com.example.autoauction.diagnostic.web.dto;

import java.util.Map;

/**
 * MEDIA-3.1B: summary of one historical template-backfill run. unresolvedByReason keys
 * are one of MULTIPLE_TEMPLATES, NO_ANSWERS, BROKEN_ITEM_REFERENCE,
 * BROKEN_GROUP_REFERENCE, BROKEN_TEMPLATE_REFERENCE.
 */
public record TemplateBackfillResult(
        int backfilled,
        int alreadyBound,
        Map<String, Integer> unresolvedByReason) {
}
