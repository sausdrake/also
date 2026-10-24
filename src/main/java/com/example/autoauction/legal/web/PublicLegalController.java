package com.example.autoauction.legal.web;

import com.example.autoauction.legal.application.LegalDocumentService;
import com.example.autoauction.legal.domain.LegalDocumentType;
import com.example.autoauction.legal.web.dto.LegalDtos.PublicDocument;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * LEGAL-DATA-01: documents in force, for everybody. Only ACTIVE versions - drafts and versions waiting for
 * legal review are never served here; a type with nothing in force answers 404 and the page says the document
 * is being prepared.
 */
@RestController
@RequestMapping("/api/v1/public/legal/documents")
public class PublicLegalController {

    private final LegalDocumentService documents;

    public PublicLegalController(LegalDocumentService documents) {
        this.documents = documents;
    }

    @GetMapping
    public List<PublicDocument> active() {
        return documents.active().stream().map(d -> PublicDocument.of(d, false)).toList();
    }

    @GetMapping("/{type}")
    public ResponseEntity<PublicDocument> active(@PathVariable LegalDocumentType type) {
        return documents.active(type)
                .map(d -> ResponseEntity.ok(PublicDocument.of(d, true)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
