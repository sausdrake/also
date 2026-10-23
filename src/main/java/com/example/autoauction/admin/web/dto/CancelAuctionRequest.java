package com.example.autoauction.admin.web.dto;

import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * AUC-NOSALE-01: optional body of the admin cancellation. Empty or absent - the cancellation is not
 * the seller's fault and the guarantee is returned in full; otherwise the confirmed seller
 * violations whose penalty is withheld from the guarantee before the remainder is returned.
 */
public record CancelAuctionRequest(
        @Size(max = 20, message = "Не больше 20 нарушений в одном решении")
        List<Long> violationDefinitionIds
) {
}
