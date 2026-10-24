package com.example.autoauction.legal.application;

import com.example.autoauction.legal.domain.AcceptanceKind;
import com.example.autoauction.legal.domain.LegalDocumentStatus;
import com.example.autoauction.legal.domain.LegalDocumentType;
import com.example.autoauction.legal.infrastructure.persistence.JpaLegalDocumentVersionEntity;
import com.example.autoauction.legal.infrastructure.persistence.SpringDataLegalDocumentVersionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * LEGAL-DATA-01: the lifecycle of versioned legal documents. Nothing is published until an administrator
 * other than the author approves the text (recording who reviewed it - lawyer / owner) and an administrator
 * activates it: draft texts prepared for legal review are never shown to users as if they were in force.
 */
@Slf4j
@Service
public class LegalDocumentService {

    private final SpringDataLegalDocumentVersionRepository documents;
    private final Clock clock;

    public LegalDocumentService(SpringDataLegalDocumentVersionRepository documents, Clock clock) {
        this.documents = documents;
        this.clock = clock;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    @Transactional(readOnly = true)
    public List<JpaLegalDocumentVersionEntity> active() {
        return documents.findByStatusOrderByDocumentTypeAsc(LegalDocumentStatus.ACTIVE);
    }

    @Transactional(readOnly = true)
    public Optional<JpaLegalDocumentVersionEntity> active(LegalDocumentType type) {
        return documents.findByDocumentTypeAndStatus(type, LegalDocumentStatus.ACTIVE);
    }

    @Transactional(readOnly = true)
    public List<JpaLegalDocumentVersionEntity> all() {
        return documents.findAllByOrderByDocumentTypeAscCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public JpaLegalDocumentVersionEntity get(Long id) {
        return documents.findById(id).orElseThrow(() -> new IllegalArgumentException("Документ не найден: " + id));
    }

    @Transactional
    public JpaLegalDocumentVersionEntity createDraft(LegalDocumentType type, String version, String title,
                                                     AcceptanceKind kind, String content, Long adminId) {
        if (type == null || kind == null) {
            throw new IllegalArgumentException("Тип документа и вид принятия обязательны");
        }
        if (version != null && documents.existsByDocumentTypeAndVersion(type, version.trim())) {
            throw new IllegalStateException("Версия " + version.trim() + " документа " + type + " уже существует");
        }
        JpaLegalDocumentVersionEntity saved = documents.save(
                new JpaLegalDocumentVersionEntity(type, version, title, content, kind, adminId, now()));
        log.info("Legal document draft {} created: {} v{} by admin {}", saved.getId(), type, saved.getVersion(), adminId);
        return saved;
    }

    @Transactional
    public JpaLegalDocumentVersionEntity editDraft(Long id, String title, AcceptanceKind kind, String content) {
        JpaLegalDocumentVersionEntity doc = lock(id);
        doc.edit(title, content, kind);
        return documents.save(doc);
    }

    @Transactional
    public JpaLegalDocumentVersionEntity submit(Long id) {
        JpaLegalDocumentVersionEntity doc = lock(id);
        doc.submitForLegalReview(now());
        return documents.save(doc);
    }

    @Transactional
    public JpaLegalDocumentVersionEntity approve(Long id, Long adminId, String note) {
        JpaLegalDocumentVersionEntity doc = lock(id);
        doc.approve(adminId, note, now());
        log.info("Legal document {} ({} v{}) approved by admin {}", id, doc.getDocumentType(), doc.getVersion(), adminId);
        return documents.save(doc);
    }

    /** The approved version becomes the one in force; the previous ACTIVE version of the type is retired. */
    @Transactional
    public JpaLegalDocumentVersionEntity activate(Long id, Long adminId) {
        JpaLegalDocumentVersionEntity doc = lock(id);
        documents.findByDocumentTypeAndStatusForUpdate(doc.getDocumentType(), LegalDocumentStatus.ACTIVE)
                .ifPresent(previous -> {
                    previous.retire(now());
                    documents.saveAndFlush(previous);
                });
        doc.activate(adminId, now());
        log.info("Legal document {} ({} v{}) activated by admin {}", id, doc.getDocumentType(), doc.getVersion(), adminId);
        return documents.save(doc);
    }

    @Transactional
    public JpaLegalDocumentVersionEntity retire(Long id) {
        JpaLegalDocumentVersionEntity doc = lock(id);
        doc.retire(now());
        return documents.save(doc);
    }

    private JpaLegalDocumentVersionEntity lock(Long id) {
        return documents.findByIdForUpdate(id).orElseThrow(() -> new IllegalArgumentException("Документ не найден: " + id));
    }
}
