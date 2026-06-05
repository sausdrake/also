package com.example.autoauction.diagnostic.infrastructure.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import lombok.Data;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "inspection_answers")
@Data
public class JpaInspectionAnswerEntity {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> LIST_TYPE = new TypeReference<>() {};

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

    // Хранится как JSON-строка — совместимо с H2 и PostgreSQL (text)
    @Column(name = "photo_urls", columnDefinition = "text")
    private String photoUrlsJson;

    @Column(name = "video_urls", columnDefinition = "text")
    private String videoUrlsJson;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Transient
    public List<String> getPhotoUrls() {
        return parseJson(photoUrlsJson);
    }

    @Transient
    public void setPhotoUrls(List<String> urls) {
        this.photoUrlsJson = toJson(urls);
    }

    @Transient
    public List<String> getVideoUrls() {
        return parseJson(videoUrlsJson);
    }

    @Transient
    public void setVideoUrls(List<String> urls) {
        this.videoUrlsJson = toJson(urls);
    }

    private static List<String> parseJson(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return MAPPER.readValue(json, LIST_TYPE);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static String toJson(List<String> list) {
        if (list == null || list.isEmpty()) return "[]";
        try {
            return MAPPER.writeValueAsString(list);
        } catch (Exception e) {
            return "[]";
        }
    }

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
        updatedAt = OffsetDateTime.now();
        if (photoUrlsJson == null) photoUrlsJson = "[]";
        if (videoUrlsJson == null) videoUrlsJson = "[]";
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
