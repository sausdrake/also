package com.example.autoauction.vehicle.domain;

public enum VehicleStatus {
    DRAFT,              // Черновик (диагностик создает)
    PENDING_REVIEW,     // На проверке у админа
    NEEDS_FIXES,        // Требует доработки
    APPROVED,           // Одобрен, ждет аукциона
    IN_AUCTION,         // В аукционе
    SOLD,               // Продан
    WITHDRAWN,          // Снят

    /**
     * X3-A (SALE_FAILED outcomes PLATFORM_FAULT/C): the auction failed in a way requiring a
     * fresh diagnostic cycle before the vehicle can be re-auctioned. Reachable only from
     * IN_AUCTION (see Vehicle.markNeedsRediagnostic()). The workflow that actually performs
     * that new diagnostic cycle is explicitly out of scope for X3-A - this value only marks
     * that the vehicle is no longer eligible for AdminAuctionService.createAuction (which
     * requires APPROVED) until that future workflow completes.
     */
    NEEDS_REDIAGNOSTIC
}