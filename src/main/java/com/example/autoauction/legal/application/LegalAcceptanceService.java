package com.example.autoauction.legal.application;

import com.example.autoauction.legal.domain.AcceptanceSource;
import com.example.autoauction.legal.domain.AcceptanceStatus;
import com.example.autoauction.legal.domain.LegalDocumentStatus;
import com.example.autoauction.legal.infrastructure.persistence.JpaLegalAcceptanceEntity;
import com.example.autoauction.legal.infrastructure.persistence.JpaLegalDocumentVersionEntity;
import com.example.autoauction.legal.infrastructure.persistence.SpringDataLegalAcceptanceRepository;
import com.example.autoauction.legal.infrastructure.persistence.SpringDataLegalDocumentVersionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * LEGAL-DATA-01: records which exact document version a user accepted (or acknowledged / consented to), when,
 * where from (registration, re-acceptance after a new edition, profile), with IP and user-agent as evidence.
 * Required are the ACTIVE versions whose kind is ACCEPTANCE or ACKNOWLEDGEMENT; consent is always optional.
 * While no document is in force nothing is required - the platform never asks to accept a text that does not
 * exist yet.
 */
@Slf4j
@Service
public class LegalAcceptanceService {

    private final SpringDataLegalDocumentVersionRepository documents;
    private final SpringDataLegalAcceptanceRepository acceptances;
    private final Clock clock;

    public LegalAcceptanceService(SpringDataLegalDocumentVersionRepository documents,
                                  SpringDataLegalAcceptanceRepository acceptances, Clock clock) {
        this.documents = documents;
        this.acceptances = acceptances;
        this.clock = clock;
    }

    /** Active required versions the user has not accepted yet (empty when nothing is in force). */
    @Transactional(readOnly = true)
    public List<JpaLegalDocumentVersionEntity> missing(Long userId) {
        List<JpaLegalDocumentVersionEntity> missing = new ArrayList<>();
        for (JpaLegalDocumentVersionEntity doc : documents.findByStatusOrderByDocumentTypeAsc(LegalDocumentStatus.ACTIVE)) {
            if (doc.getAcceptanceKind().requiredForAccount()
                    && (userId == null || !acceptances.existsByUserIdAndDocumentVersionIdAndStatus(userId, doc.getId(), AcceptanceStatus.ACCEPTED))) {
                missing.add(doc);
            }
        }
        return missing;
    }

    /** The gate at the user-initiated protected operations, next to the KYC gate. */
    @Transactional(readOnly = true)
    public void requireCurrent(Long userId) {
        if (!missing(userId).isEmpty()) {
            throw new LegalAcceptanceRequiredException();
        }
    }

    /**
     * Before an account exists (registration): every required active version must be among the ids the user
     * ticked, otherwise the registration is refused before anything is written.
     */
    @Transactional(readOnly = true)
    public void requireAllRequiredIn(Collection<Long> documentVersionIds) {
        Set<Long> given = documentVersionIds == null ? Set.of() : new LinkedHashSet<>(documentVersionIds);
        for (JpaLegalDocumentVersionEntity doc : missing(null)) {
            if (!given.contains(doc.getId())) {
                throw new IllegalArgumentException("Для регистрации примите: " + doc.getTitle() + " (редакция " + doc.getVersion() + ")");
            }
        }
    }

    /**
     * Records the user's acceptance of each given version. Only ACTIVE versions can be accepted; a version the
     * user already accepted is not recorded twice (idempotent; concurrent requests of one user are serialised on
     * the user's row, and the unique (user, version) constraint backs it up).
     */
    @Transactional
    public List<JpaLegalAcceptanceEntity> accept(Long userId, Collection<Long> documentVersionIds, AcceptanceSource source,
                                                 String ipAddress, String userAgent) {
        Objects.requireNonNull(userId, "userId");
        if (documentVersionIds == null || documentVersionIds.isEmpty()) {
            throw new IllegalArgumentException("Выберите документы, которые принимаете");
        }
        if (acceptances.lockUser(userId) == null) {
            throw new IllegalArgumentException("Пользователь не найден: " + userId);
        }
        List<JpaLegalAcceptanceEntity> recorded = new ArrayList<>();
        OffsetDateTime now = OffsetDateTime.now(clock);
        for (Long id : new LinkedHashSet<>(documentVersionIds)) {
            JpaLegalDocumentVersionEntity doc = documents.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Документ не найден: " + id));
            if (doc.getStatus() != LegalDocumentStatus.ACTIVE) {
                throw new IllegalStateException("Принять можно только действующую редакцию документа");
            }
            if (doc.getAcceptanceKind() == com.example.autoauction.legal.domain.AcceptanceKind.INFORMATIONAL) {
                throw new IllegalArgumentException("Документ «" + doc.getTitle() + "» не требует принятия");
            }
            var existing = acceptances.findByUserIdAndDocumentVersionId(userId, id);
            if (existing.isPresent()) {
                recorded.add(existing.get());
                continue;
            }
            recorded.add(acceptances.save(new JpaLegalAcceptanceEntity(userId, doc, source, ipAddress, userAgent, now)));
        }
        log.info("User {} accepted {} legal document version(s) via {}", userId, recorded.size(), source);
        return recorded;
    }

    @Transactional(readOnly = true)
    public List<JpaLegalAcceptanceEntity> mine(Long userId) {
        return acceptances.findByUserIdOrderByAcceptedAtDesc(userId);
    }

    /** Withdraws the user's own consent; only kind CONSENT (see the entity). */
    @Transactional
    public JpaLegalAcceptanceEntity withdraw(Long userId, Long acceptanceId) {
        JpaLegalAcceptanceEntity acceptance = acceptances.findById(acceptanceId)
                .filter(a -> a.getUserId().equals(userId))
                .orElseThrow(() -> new IllegalArgumentException("Запись не найдена: " + acceptanceId));
        acceptance.withdraw(OffsetDateTime.now(clock));
        return acceptances.save(acceptance);
    }
}
