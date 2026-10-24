package com.example.autoauction.legal.web;

import com.example.autoauction.auth.domain.UserPrincipal;
import com.example.autoauction.auth.infrastructure.security.CurrentUser;
import com.example.autoauction.legal.application.PrivacyRequestService;
import com.example.autoauction.legal.domain.PrivacyRequestStatus;
import com.example.autoauction.legal.web.dto.LegalDtos.AnswerPrivacyRequest;
import com.example.autoauction.legal.web.dto.LegalDtos.CreatePrivacyRequest;
import com.example.autoauction.legal.web.dto.LegalDtos.PrivacyRequestView;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** PRIVACY-REQ-01: users file requests about their data; administrators take and answer them. */
@RestController
public class PrivacyRequestController {

    private final PrivacyRequestService requests;

    public PrivacyRequestController(PrivacyRequestService requests) {
        this.requests = requests;
    }

    @PostMapping("/api/v1/privacy/requests")
    public ResponseEntity<PrivacyRequestView> create(@CurrentUser UserPrincipal user, @Valid @RequestBody CreatePrivacyRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(PrivacyRequestView.of(requests.create(user.getUserId(), r.type(), r.message())));
    }

    @GetMapping("/api/v1/privacy/requests/my")
    public List<PrivacyRequestView> mine(@CurrentUser UserPrincipal user) {
        return requests.mine(user.getUserId()).stream().map(PrivacyRequestView::of).toList();
    }

    @GetMapping("/api/v1/admin/privacy/requests")
    @PreAuthorize("hasRole('ADMIN')")
    public List<PrivacyRequestView> all(@RequestParam(required = false) PrivacyRequestStatus status) {
        return requests.all(status).stream().map(PrivacyRequestView::of).toList();
    }

    @PostMapping("/api/v1/admin/privacy/requests/{id}/take")
    @PreAuthorize("hasRole('ADMIN')")
    public PrivacyRequestView take(@PathVariable Long id, @CurrentUser UserPrincipal admin) {
        return PrivacyRequestView.of(requests.take(id, admin.getUserId()));
    }

    @PostMapping("/api/v1/admin/privacy/requests/{id}/answer")
    @PreAuthorize("hasRole('ADMIN')")
    public PrivacyRequestView answer(@PathVariable Long id, @Valid @RequestBody AnswerPrivacyRequest r,
                                     @CurrentUser UserPrincipal admin) {
        return PrivacyRequestView.of(requests.answer(id, admin.getUserId(), r.answer(), r.rejected()));
    }
}
