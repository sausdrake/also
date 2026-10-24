package com.example.autoauction.legal.web;

import com.example.autoauction.auth.domain.UserPrincipal;
import com.example.autoauction.auth.infrastructure.security.CurrentUser;
import com.example.autoauction.legal.application.LegalAcceptanceService;
import com.example.autoauction.legal.domain.AcceptanceSource;
import com.example.autoauction.legal.web.dto.LegalDtos.AcceptRequest;
import com.example.autoauction.legal.web.dto.LegalDtos.AcceptanceView;
import com.example.autoauction.legal.web.dto.LegalDtos.PublicDocument;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** LEGAL-DATA-01: the signed-in user's acceptances. */
@RestController
@RequestMapping("/api/v1/legal/acceptance")
public class LegalAcceptanceController {

    private final LegalAcceptanceService acceptances;

    public LegalAcceptanceController(LegalAcceptanceService acceptances) {
        this.acceptances = acceptances;
    }

    /** Versions in force the user still has to accept (a new edition after a change shows up here). */
    @GetMapping("/required")
    public List<PublicDocument> required(@CurrentUser UserPrincipal currentUser) {
        return acceptances.missing(currentUser.getUserId()).stream().map(d -> PublicDocument.of(d, false)).toList();
    }

    @PostMapping
    public List<AcceptanceView> accept(@CurrentUser UserPrincipal currentUser, @Valid @RequestBody AcceptRequest request,
                                       HttpServletRequest http) {
        return acceptances.accept(currentUser.getUserId(), request.documentVersionIds(), AcceptanceSource.RE_ACCEPTANCE,
                        http.getRemoteAddr(), http.getHeader("User-Agent"))
                .stream().map(AcceptanceView::of).toList();
    }

    @GetMapping("/my")
    public List<AcceptanceView> mine(@CurrentUser UserPrincipal currentUser) {
        return acceptances.mine(currentUser.getUserId()).stream().map(AcceptanceView::of).toList();
    }

    @PostMapping("/{id}/withdraw")
    public AcceptanceView withdraw(@CurrentUser UserPrincipal currentUser, @PathVariable Long id) {
        return AcceptanceView.of(acceptances.withdraw(currentUser.getUserId(), id));
    }
}
