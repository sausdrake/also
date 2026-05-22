package com.example.autoauction.diagnostic.infrastructure.persistence;

import io.hypersistence.utils.hibernate.type.array.ListArrayType;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.Type;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "inspection_answers")
@Data
public class JpaInspectionAnswerEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "diagnostic_request_id", nullable = false)
    private Long diagnosticRequestId;

    @Column(name = "inspection_item_id", nullable = false)
    private Long inspectionItemId;

    private Integer rating;

    @Column(length = 2000)
    private String comment;

    @Type(ListArrayType.class)
    @Column(name = "photo_urls", columnDefinition = "text[]")
    private List<String> photoUrls = new ArrayList<>();

    @Type(ListArrayType.class)
    @Column(name = "video_urls", columnDefinition = "text[]")
    private List<String> videoUrls = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
        updatedAt = OffsetDateTime.now();
        if (photoUrls == null) photoUrls = new ArrayList<>();
        if (videoUrls == null) videoUrls = new ArrayList<>();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}