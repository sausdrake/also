// diagnostic/media/application/LegacyMediaMigrationOrphanTest.java
package com.example.autoauction.diagnostic.media.application;

import com.example.autoauction.common.media.domain.MediaAsset;
import com.example.autoauction.common.media.domain.port.MediaAssetRepository;
import com.example.autoauction.diagnostic.domain.DiagnosticRequest;
import com.example.autoauction.diagnostic.domain.InspectionAnswer;
import com.example.autoauction.diagnostic.domain.InspectionGroup;
import com.example.autoauction.diagnostic.domain.InspectionItem;
import com.example.autoauction.diagnostic.domain.InspectionTemplate;
import com.example.autoauction.diagnostic.domain.port.DiagnosticRequestRepository;
import com.example.autoauction.diagnostic.domain.port.InspectionAnswerRepository;
import com.example.autoauction.diagnostic.domain.port.InspectionTemplateRepository;
import com.example.autoauction.diagnostic.media.domain.port.DiagnosticMediaRepository;
import com.example.autoauction.diagnostic.media.infrastructure.storage.FakeStorageClient;
import com.example.autoauction.diagnostic.media.web.dto.LegacyMediaMigrationResult;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * MEDIA-3.3 review, focused audit: proves the orphaned-R2-object risk when
 * DiagnosticMedia persistence fails AFTER a successful R2 putObject()/
 * headObject() - and proves the compensating storageClient.deleteObject()
 * call added to migrateOnePosition() actually removes the object rather
 * than leaking it. Needs its own @MockBean-backed context (separate from
 * LegacyMediaMigrationServiceIntegrationTest's) because the real JPA-backed
 * DiagnosticMediaRepository has no failure-injection hook - FakeStorageClient
 * alone cannot simulate a DB-layer failure, only a storage-layer one.
 * @MockBean is an established convention in this codebase (see
 * DiagnosticMediaSecurityTest, AdminAuctionControllerTest, etc.).
 *
 * @Transactional (rollback-per-test): the request/answer/media-asset rows
 * this test creates go through the REAL (un-mocked) repositories against the
 * shared H2 instance - without a rollback boundary they would leak into
 * other @SpringBootTest classes' migrate() sweeps in the same test run
 * (LegacyMediaMigrationServiceIntegrationTest's migrate() scans ALL
 * COMPLETED/APPROVED requests globally - see that class's own @Transactional
 * doc comment for the identical reasoning).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("MEDIA-3.3 review: orphaned R2 object on DB persistence failure")
class LegacyMediaMigrationOrphanTest {

    @Autowired
    private LegacyMediaMigrationService migrationService;

    @MockBean
    private DiagnosticMediaRepository mediaRepository;

    @Autowired
    private DiagnosticRequestRepository requestRepository;

    @Autowired
    private InspectionAnswerRepository answerRepository;

    @Autowired
    private InspectionTemplateRepository templateRepository;

    @Autowired
    private MediaAssetRepository mediaAssetRepository;

    @Autowired
    private FakeStorageClient storageClient;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Value("${media.upload.dir}")
    private String uploadDirProp;

    @Value("${media.base-url}")
    private String mediaBaseUrl;

    private static final byte[] VALID_JPEG = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x01, 0x02, 0x03};

    private Path uploadDir() {
        return Path.of(uploadDirProp).toAbsolutePath().normalize();
    }

    private Long createUser(String prefix) {
        Role userRole = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        User u = new User(null, prefix + System.nanoTime(), prefix + System.nanoTime() + "@test.com",
                "encoded", true, Set.of(userRole));
        return userRepository.save(u).getId();
    }

    private Long createTemplateWithItem() {
        InspectionTemplate t = new InspectionTemplate();
        t.setName("T-" + System.nanoTime());
        t.setActive(true);
        return templateRepository.saveTemplate(t).getId();
    }

    private Long createItem(Long templateId) {
        InspectionGroup g = new InspectionGroup();
        g.setTemplateId(templateId);
        g.setName("G-" + System.nanoTime());
        g.setSortOrder(1);
        Long groupId = templateRepository.saveGroup(g).getId();

        InspectionItem i = new InspectionItem();
        i.setGroupId(groupId);
        i.setTitle("I-" + System.nanoTime());
        i.setFieldType("TEXT");
        i.setHasPhoto(true);
        i.setSortOrder(1);
        return templateRepository.saveItem(i).getId();
    }

    private Long createCompletedRequest(Long templateId) {
        Long ownerId = createUser("owner");
        Long diagnosticId = createUser("diag");
        DiagnosticRequest request = new DiagnosticRequest();
        request.setUserId(ownerId);
        request.setBrand("BMW");
        request.setModel("X5");
        request.setYear(2022);
        request.setVin("O" + (System.nanoTime() % 100000000L));
        request.setDepositFrozen(new BigDecimal("10000"));
        request.setStatus(DiagnosticRequest.DiagnosticRequestStatus.COMPLETED);
        request.setAssignedTo(diagnosticId);
        request.setCreatedAt(OffsetDateTime.now());
        request.setUpdatedAt(OffsetDateTime.now());
        Long requestId = requestRepository.save(request).getId();
        requestRepository.bindTemplateIfUnset(requestId, templateId);
        return requestId;
    }

    private void createAnswer(Long requestId, Long itemId, String photoUrl) {
        InspectionAnswer answer = new InspectionAnswer();
        answer.setDiagnosticRequestId(requestId);
        answer.setInspectionItemId(itemId);
        answer.setPhotoUrls(List.of(photoUrl));
        answer.setCreatedAt(OffsetDateTime.now());
        answer.setUpdatedAt(OffsetDateTime.now());
        answerRepository.save(answer);
    }

    private String writeLegacyFile(byte[] content) throws IOException {
        Files.createDirectories(uploadDir());
        String filename = UUID.randomUUID() + ".jpg";
        Files.write(uploadDir().resolve(filename), content);
        return mediaBaseUrl + "/" + filename;
    }

    private void registerUploader(String legacyUrl, Long uploaderId) {
        String filename = legacyUrl.substring(mediaBaseUrl.length() + 1);
        mediaAssetRepository.save(new MediaAsset(null, filename, uploaderId, OffsetDateTime.now()));
    }

    @Test
    @DisplayName("Сбой mediaRepository.save() ПОСЛЕ успешной R2-загрузки/верификации -> компенсирующий deleteObject() устраняет orphan-объект")
    void dbPersistenceFailureAfterR2Upload_TriggersCompensatingDelete() throws IOException {
        Long templateId = createTemplateWithItem();
        Long itemId = createItem(templateId);
        Long requestId = createCompletedRequest(templateId);
        String legacyUrl = writeLegacyFile(VALID_JPEG);
        registerUploader(legacyUrl, createUser("uploader"));
        createAnswer(requestId, itemId, legacyUrl);

        // no already-migrated rows (first run), but persistence of the new row always fails
        when(mediaRepository.findByRequestAndItem(anyLong(), anyLong())).thenReturn(List.of());
        when(mediaRepository.save(any())).thenThrow(new RuntimeException("simulated DB persistence failure"));

        assertThat(storageClient.objectCount()).isZero(); // sanity: nothing uploaded yet

        LegacyMediaMigrationResult result = migrationService.migrate();

        assertThat(result.migrated()).isZero();
        assertThat(result.failedByReason()).containsEntry("DB_PERSISTENCE_FAILED", 1);
        // proves the compensating delete actually ran: the R2 object that WAS successfully
        // uploaded and verified before the DB save failed does not remain orphaned afterwards
        assertThat(storageClient.objectCount())
                .as("compensating deleteObject() must remove the just-uploaded R2 object, leaving no orphan")
                .isZero();
    }
}
