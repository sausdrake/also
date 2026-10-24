package com.example.autoauction.legal.web;

import com.example.autoauction.auth.domain.UserPrincipal;
import com.example.autoauction.auth.infrastructure.security.CurrentUser;
import com.example.autoauction.legal.application.LegalDocumentService;
import com.example.autoauction.legal.web.dto.LegalDtos.AdminDocument;
import com.example.autoauction.legal.web.dto.LegalDtos.ApproveDocumentRequest;
import com.example.autoauction.legal.web.dto.LegalDtos.CreateDocumentRequest;
import com.example.autoauction.legal.web.dto.LegalDtos.EditDocumentRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** LEGAL-DATA-01: administrators prepare, approve (maker-checker), activate and retire document versions. */
@RestController
@RequestMapping("/api/v1/admin/legal/documents")
@PreAuthorize("hasRole('ADMIN')")
public class AdminLegalDocumentController {

    private final LegalDocumentService documents;

    public AdminLegalDocumentController(LegalDocumentService documents) {
        this.documents = documents;
    }

    @GetMapping
    public List<AdminDocument> all() {
        return documents.all().stream().map(AdminDocument::of).toList();
    }

    @GetMapping("/{id}")
    public AdminDocument get(@PathVariable Long id) {
        return AdminDocument.of(documents.get(id));
    }

    @PostMapping
    public ResponseEntity<AdminDocument> create(@Valid @RequestBody CreateDocumentRequest r, @CurrentUser UserPrincipal admin) {
        return ResponseEntity.status(HttpStatus.CREATED).body(AdminDocument.of(documents.createDraft(
                r.documentType(), r.version(), r.title(), r.acceptanceKind(), r.content(), admin.getUserId())));
    }

    @PutMapping("/{id}")
    public AdminDocument edit(@PathVariable Long id, @Valid @RequestBody EditDocumentRequest r) {
        return AdminDocument.of(documents.editDraft(id, r.title(), r.acceptanceKind(), r.content()));
    }

    @PostMapping("/{id}/submit")
    public AdminDocument submit(@PathVariable Long id) {
        return AdminDocument.of(documents.submit(id));
    }

    @PostMapping("/{id}/approve")
    public AdminDocument approve(@PathVariable Long id, @Valid @RequestBody ApproveDocumentRequest r,
                                 @CurrentUser UserPrincipal admin) {
        return AdminDocument.of(documents.approve(id, admin.getUserId(), r.note()));
    }

    @PostMapping("/{id}/activate")
    public AdminDocument activate(@PathVariable Long id, @CurrentUser UserPrincipal admin) {
        return AdminDocument.of(documents.activate(id, admin.getUserId()));
    }

    @PostMapping("/{id}/retire")
    public AdminDocument retire(@PathVariable Long id) {
        return AdminDocument.of(documents.retire(id));
    }
}
