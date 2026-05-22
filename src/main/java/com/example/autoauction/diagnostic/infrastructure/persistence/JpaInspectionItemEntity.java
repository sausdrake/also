package com.example.autoauction.diagnostic.infrastructure.persistence;

import jakarta.persistence.*;
import lombok.Data;
import java.time.OffsetDateTime;

@Entity
@Table(name = "inspection_items")
@Data
public class JpaInspectionItemEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(length = 1000)
    private String description;

    @Column(name = "has_photo", nullable = false)
    private boolean hasPhoto = false;

    @Column(name = "has_video", nullable = false)
    private boolean hasVideo = false;

    @Column(name = "has_rating", nullable = false)
    private boolean hasRating = false;

    @Column(name = "is_required", nullable = false)
    private boolean isRequired = true;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
    }
}