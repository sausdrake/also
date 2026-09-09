package com.example.autoauction.policy.domain;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * X3-E: stable, policy-version-agnostic identity for a seller-violation code (e.g.
 * "SELLER_REFUSED_HANDOVER"). Carries no penalty configuration of its own - that is
 * {@link PolicyVersionViolation}'s job, versioned independently. Deliberately minimal (no
 * "active"/deprecation flag): X3-E's locked scope seeds exactly six codes and explicitly
 * excludes catalogue mutation (add/retire) - see the X3-E final scope report, section A.
 *
 * The one exception is {@code VEHICLE_CONDITION_CHANGED_AFTER_DIAGNOSTIC}: it exists here
 * (selectable/configurable like any other code) but is refused at resolution time by
 * SellerViolationResolutionEngine's own fixed, code-level guard - not by any flag on this
 * entity - since enabling it for real requires a future evidence mechanism that does not
 * exist yet, not an admin-flippable setting (see the X3-E final scope report, section F).
 *
 * Immutable after construction - no setters. {@code code} is the identity every
 * PolicyVersionViolation/SellerViolationResolutionItem references by this entity's surrogate
 * id, never by the code string itself.
 */
public class ViolationDefinition {

    private Long id;
    private final String code;
    private final String description;
    private final OffsetDateTime createdAt;

    public ViolationDefinition(String code, String description, OffsetDateTime createdAt) {
        Objects.requireNonNull(code, "code не может быть null");
        Objects.requireNonNull(description, "description не может быть null");
        Objects.requireNonNull(createdAt, "createdAt не может быть null");
        if (code.isBlank()) {
            throw new IllegalArgumentException("code не может быть пустым");
        }
        this.code = code;
        this.description = description;
        this.createdAt = createdAt;
    }

    /**
     * Reconstruction constructor, used exclusively by the persistence mapper.
     */
    public ViolationDefinition(Long id, String code, String description, OffsetDateTime createdAt) {
        this(code, description, createdAt);
        this.id = id;
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getDescription() { return description; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
