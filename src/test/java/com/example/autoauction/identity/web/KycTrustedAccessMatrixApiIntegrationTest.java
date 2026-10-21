package com.example.autoauction.identity.web;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.auth.domain.TestUserPrincipal;
import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.identity.domain.DocumentType;
import com.example.autoauction.identity.domain.IdentityDocument;
import com.example.autoauction.identity.domain.IdentityVerification;
import com.example.autoauction.identity.domain.IdentityVerificationStatus;
import com.example.autoauction.identity.domain.port.IdentityDocumentRepository;
import com.example.autoauction.identity.domain.port.IdentityVerificationRepository;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * KYC-01: KYC (identity verification) and the trusted status are two independent gates. Matrix of the
 * combinations against withdrawal / bid / Buy Now, over the real security chain: each refusal carries the
 * code of the gate that actually refused, so a verified user is never asked to verify again.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("KYC-01: KYC x trusted access matrix - API behaviour")
class KycTrustedAccessMatrixApiIntegrationTest {

    private static final String KYC_CODE = "IDENTITY_VERIFICATION_REQUIRED";
    private static final String TRUST_CODE = "TRUSTED_PARTICIPANT_REQUIRED";
    private static final long NO_SUCH_AUCTION = 987_654_321L;

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private DepositService depositService;
    @Autowired private AuctionRepository auctionRepository;
    @Autowired private IdentityDocumentRepository identityDocumentRepository;
    @Autowired private IdentityVerificationRepository identityVerificationRepository;

    private Long user(boolean trusted, IdentityVerificationStatus kyc) {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        String name = "kyc01" + System.nanoTime();
        User u = trusted
                ? new User(null, name, name + "@test.com", "encoded", true, Set.of(role), true, 1L, OffsetDateTime.now())
                : new User(null, name, name + "@test.com", "encoded", true, Set.of(role));
        Long id = userRepository.save(u).getId();
        depositService.createDeposit(id, new BigDecimal("50000"));
        OffsetDateTime now = OffsetDateTime.now();
        Long doc = identityDocumentRepository.save(new IdentityDocument(
                "identity-documents/" + UUID.randomUUID(), id, "image/jpeg", 1000L, now)).getId();
        IdentityVerification iv = IdentityVerification.firstSubmission(id, "KG", DocumentType.NATIONAL_ID, "K" + System.nanoTime(), doc, doc, doc, now);
        switch (kyc) {
            case PENDING_REVIEW -> { }
            case VERIFIED -> iv.approve(now);
            case REJECTED -> iv.reject(now);
            case REVOKED -> { iv.approve(now); iv.revoke(now); }
        }
        identityVerificationRepository.save(iv);
        return id;
    }

    private Long activeAuction() {
        Role role = roleRepository.findByName("ROLE_USER").orElseThrow();
        String name = "kyc01s" + System.nanoTime();
        Long seller = userRepository.save(new User(null, name, name + "@test.com", "encoded", true, Set.of(role))).getId();
        OffsetDateTime now = OffsetDateTime.now();
        Auction a = new Auction(1L, new BigDecimal("1000000"), null, null, new BigDecimal("10000"),
                now.minusHours(1), now.plusDays(1), seller);
        a.setRequiredDepositAmount(new BigDecimal("10000"));
        a.start();
        return auctionRepository.save(a).getId();
    }

    private ResultActions as(Long userId, MockHttpServletRequestBuilder request) throws Exception {
        TestUserPrincipal p = TestUserPrincipal.createWithRoles(userId, "user" + userId, "USER");
        return mockMvc.perform(request.with(authentication(new UsernamePasswordAuthenticationToken(p, "password", p.getAuthorities()))));
    }

    private static MockHttpServletRequestBuilder bid(long auctionId, String amount) {
        return post("/api/auctions/" + auctionId + "/bids").contentType(MediaType.APPLICATION_JSON).content("{\"amount\":" + amount + "}");
    }

    private static MockHttpServletRequestBuilder buyNow() {
        return post("/api/auctions/" + NO_SUCH_AUCTION + "/buy-now");
    }

    private static MockHttpServletRequestBuilder withdraw() {
        return post("/api/v1/withdrawals").contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":100,\"idempotencyKey\":\"" + UUID.randomUUID() + "\"}");
    }

    private void refused(ResultActions r, String code) throws Exception {
        r.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(code)).andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    @DisplayName("PENDING + untrusted: withdrawal, bid and Buy Now are refused by the KYC gate")
    void pendingUntrusted() throws Exception {
        Long u = user(false, IdentityVerificationStatus.PENDING_REVIEW);
        refused(as(u, withdraw()), KYC_CODE);
        refused(as(u, bid(NO_SUCH_AUCTION, "1000")), KYC_CODE);
        refused(as(u, buyNow()), KYC_CODE);
    }

    @Test
    @DisplayName("VERIFIED + untrusted: withdrawal works; bid and Buy Now are refused by the TRUST gate, never by KYC")
    void verifiedUntrusted() throws Exception {
        Long u = user(false, IdentityVerificationStatus.VERIFIED);
        as(u, withdraw()).andExpect(status().isOk());
        refused(as(u, bid(NO_SUCH_AUCTION, "1000")), TRUST_CODE);
        refused(as(u, buyNow()), TRUST_CODE);
        as(u, bid(NO_SUCH_AUCTION, "1000")).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("доверенного")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Пройдите верификацию"))));
    }

    @Test
    @DisplayName("VERIFIED + trusted: withdrawal works and a real bid is accepted")
    void verifiedTrusted() throws Exception {
        Long u = user(true, IdentityVerificationStatus.VERIFIED);
        as(u, withdraw()).andExpect(status().isOk());
        as(u, bid(activeAuction(), "1010000")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("REJECTED + trusted: trust does not replace KYC - withdrawal, bid and Buy Now refused by the KYC gate")
    void rejectedTrusted() throws Exception {
        Long u = user(true, IdentityVerificationStatus.REJECTED);
        refused(as(u, withdraw()), KYC_CODE);
        refused(as(u, bid(NO_SUCH_AUCTION, "1000")), KYC_CODE);
        refused(as(u, buyNow()), KYC_CODE);
    }

    @Test
    @DisplayName("admin sees both, independently: identityStatus next to trusted in the user list and the user detail")
    void adminSeesKycAndTrustSeparately() throws Exception {
        Long verifiedUntrusted = user(false, IdentityVerificationStatus.VERIFIED);
        Long rejectedTrusted = user(true, IdentityVerificationStatus.REJECTED);
        TestUserPrincipal admin = TestUserPrincipal.createWithRoles(1L, "admin", "ADMIN");
        var auth = authentication(new UsernamePasswordAuthenticationToken(admin, "password", admin.getAuthorities()));

        mockMvc.perform(get("/api/v1/admin/users/" + verifiedUntrusted).with(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identityStatus").value("VERIFIED"))
                .andExpect(jsonPath("$.trusted").value(false));
        mockMvc.perform(get("/api/v1/admin/users").with(auth))
                .andExpect(jsonPath("$[?(@.id == " + rejectedTrusted + ")].identityStatus").value("REJECTED"))
                .andExpect(jsonPath("$[?(@.id == " + rejectedTrusted + ")].trusted").value(true));
        // trusting a user never changes the KYC status shown
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                        "/api/v1/admin/users/" + verifiedUntrusted + "/trust").with(auth))
                .andExpect(jsonPath("$.trusted").value(true))
                .andExpect(jsonPath("$.identityStatus").value("VERIFIED"));
    }
}
