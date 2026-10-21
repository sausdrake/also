package com.example.autoauction.auction.web;

import com.example.autoauction.auction.application.BidService;
import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.BidStepPolicy;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.auth.domain.TestUserPrincipal;
import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.identity.domain.DocumentType;
import com.example.autoauction.identity.domain.IdentityDocument;
import com.example.autoauction.identity.domain.IdentityVerification;
import com.example.autoauction.identity.domain.port.IdentityDocumentRepository;
import com.example.autoauction.identity.domain.port.IdentityVerificationRepository;
import com.example.autoauction.support.AbstractPostgresIntegrationTest;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AUCTION-01 on real PostgreSQL: the 3-decimal step survives the column (V61), the bid threshold is exact,
 * bids stay money (2 decimals, within NUMERIC(10,2)) and every bad amount is a 400, never a 500 or a silent
 * database rounding; two simultaneous bids at the same price produce exactly one winner.
 */
@AutoConfigureMockMvc
@DisplayName("AUCTION-01: derived bid step - API and PostgreSQL")
class BidStepApiPgIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final BigDecimal DEPOSIT = new BigDecimal("10000.00");

    @Autowired private MockMvc mockMvc;
    @Autowired private BidService bidService;
    @Autowired private AuctionRepository auctionRepository;
    @Autowired private DepositService depositService;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private IdentityDocumentRepository identityDocumentRepository;
    @Autowired private IdentityVerificationRepository identityVerificationRepository;
    @Autowired private JdbcTemplate jdbc;

    private Role role() {
        return roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
    }

    /** A bidder who passes every gate: trusted, KYC verified, enough balance for the deposit. */
    private Long bidder() {
        String name = "auc01" + System.nanoTime();
        Long id = userRepository.save(new User(null, name, name + "@test.com", "encoded", true, Set.of(role()),
                true, 1L, OffsetDateTime.now())).getId();
        depositService.createDeposit(id, new BigDecimal("50000"));
        OffsetDateTime now = OffsetDateTime.now();
        Long doc = identityDocumentRepository.save(new IdentityDocument(
                "identity-documents/" + UUID.randomUUID(), id, "image/jpeg", 1000L, now)).getId();
        IdentityVerification iv = IdentityVerification.firstSubmission(id, "KG", DocumentType.NATIONAL_ID,
                "K" + System.nanoTime(), doc, doc, doc, now);
        iv.approve(now);
        identityVerificationRepository.save(iv);
        return id;
    }

    private Long activeAuction(String startingPrice) {
        String name = "auc01s" + System.nanoTime();
        Long seller = userRepository.save(new User(null, name, name + "@test.com", "encoded", true, Set.of(role()))).getId();
        BigDecimal start = new BigDecimal(startingPrice);
        OffsetDateTime now = OffsetDateTime.now();
        Auction a = new Auction(1L, start, null, null, BidStepPolicy.minimumBidStep(start),
                now.minusHours(1), now.plusDays(1), seller);
        a.setRequiredDepositAmount(DEPOSIT);
        a.start();
        return auctionRepository.save(a).getId();
    }

    private ResultActions as(Long userId, String role, MockHttpServletRequestBuilder request) throws Exception {
        TestUserPrincipal p = TestUserPrincipal.createWithRoles(userId, "user" + userId, role);
        return mockMvc.perform(request.with(authentication(new UsernamePasswordAuthenticationToken(p, "password", p.getAuthorities()))));
    }

    private static MockHttpServletRequestBuilder bid(long auctionId, String amount) {
        return post("/api/auctions/" + auctionId + "/bids").contentType(MediaType.APPLICATION_JSON).content("{\"amount\":" + amount + "}");
    }

    private BigDecimal storedStep(Long auctionId) {
        return jdbc.queryForObject("select min_bid_step from auctions where id = ?", BigDecimal.class, auctionId);
    }

    private BigDecimal storedPrice(Long auctionId) {
        return jdbc.queryForObject("select current_price from auctions where id = ?", BigDecimal.class, auctionId);
    }

    private int bidRows(Long auctionId) {
        return jdbc.queryForObject("select total_bids from auctions where id = ?", Integer.class, auctionId);
    }

    @Test
    @DisplayName("V61: the 3-decimal step is stored exactly; a legacy 2-decimal step keeps its value")
    void stepColumnKeepsThreeDecimals() {
        Long id = activeAuction("123.45");
        assertThat(storedStep(id)).isEqualTo(new BigDecimal("1.235"));
        assertThat(auctionRepository.findById(id).orElseThrow().getMinBidStep()).isEqualByComparingTo("1.235");

        Long seller = userRepository.save(new User(null, "auc01l" + System.nanoTime(), "l" + System.nanoTime() + "@test.com",
                "encoded", true, Set.of(role()))).getId();
        OffsetDateTime now = OffsetDateTime.now();
        Auction legacy = new Auction(1L, new BigDecimal("1000000"), null, null, new BigDecimal("15000.00"),
                now.plusHours(2), now.plusDays(2), seller);
        Long legacyId = auctionRepository.save(legacy).getId();
        assertThat(storedStep(legacyId)).isEqualByComparingTo("15000");   // existing lots keep their own step
    }

    @Test
    @DisplayName("first bid: starting price / one tyiyn below / exact-but-3-decimals are refused (400); the smallest money bid wins")
    void firstBidBoundariesOverHttp() throws Exception {
        Long id = activeAuction("123.45");       // step 1.235 -> threshold 124.685 -> 124.69
        Long u = bidder();
        as(u, "USER", get("/api/v1/public/auctions/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.minBidStep").value(1.235))
                .andExpect(jsonPath("$.minimumNextBid").value(124.69));

        for (String refused : List.of("123.45", "124.68", "124.685", "124.689")) {
            as(u, "USER", bid(id, refused)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").isNotEmpty());
        }
        assertThat(bidRows(id)).isZero();
        assertThat(storedPrice(id)).isEqualByComparingTo("123.45");

        as(u, "USER", bid(id, "124.69")).andExpect(status().is2xxSuccessful());
        assertThat(storedPrice(id)).isEqualTo(new BigDecimal("124.69"));
        as(u, "USER", get("/api/v1/public/auctions/" + id)).andExpect(jsonPath("$.minimumNextBid").value(125.93));
    }

    @ParameterizedTest(name = "amount {0} -> 400")
    @ValueSource(strings = {"0", "-1", "-0.01", "0.001", "1000000.001", "100000000", "99999999.991",
            "1e30", "\"NaN\"", "\"Infinity\"", "\"abc\"", "null", "1234567890123456789012345678901234567890"})
    @DisplayName("ADVERSARIAL: malformed / negative / zero / too precise / overflowing amounts are a 400, never a 500, nothing stored")
    void adversarialAmounts(String amount) throws Exception {
        Long id = activeAuction("1000000");
        Long u = bidder();
        as(u, "USER", bid(id, amount)).andExpect(status().isBadRequest());
        assertThat(bidRows(id)).isZero();
        assertThat(storedPrice(id)).isEqualByComparingTo("1000000");
    }

    @Test
    @DisplayName("ADVERSARIAL (service level, past the web layer): over-capacity and 3-decimal amounts refused by the domain, not by a DB error")
    void domainRefusesUnstorableAmounts() {
        Long id = activeAuction("1000000");
        Long u = bidder();
        assertThatThrownBy(() -> bidService.placeBid(id, new BigDecimal("100000000.00"), u))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bidService.placeBid(id, new BigDecimal("1010000.005"), u))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(bidRows(id)).isZero();
        assertThat(depositService.getFrozenAmountForAuction(u, id)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("STALE: a bid computed from an outdated price is refused after a concurrent higher bid")
    void staleBidRefused() {
        Long id = activeAuction("123.45");
        Long a = bidder(), b = bidder();
        bidService.placeBid(id, new BigDecimal("124.69"), a);
        // b still sees 123.45 and sends that page's minimum
        assertThatThrownBy(() -> bidService.placeBid(id, new BigDecimal("124.69"), b))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("125.93");
        bidService.placeBid(id, new BigDecimal("125.93"), b);
        assertThat(storedPrice(id)).isEqualByComparingTo("125.93");
        assertThat(bidRows(id)).isEqualTo(2);
    }

    @RepeatedTest(3)
    @DisplayName("CONCURRENCY: two bidders send the same minimum at once -> exactly one bid accepted, the other refused, price consistent")
    void twoSimultaneousBidsAtTheSamePrice() throws Exception {
        Long id = activeAuction("123.45");
        Long a = bidder(), b = bidder();
        CountDownLatch go = new CountDownLatch(1);
        List<Throwable> errors = new ArrayList<>();
        List<Thread> threads = new ArrayList<>();
        for (Long u : List.of(a, b)) {
            Thread t = new Thread(() -> {
                try { go.await(); bidService.placeBid(id, new BigDecimal("124.69"), u); }
                catch (Throwable e) { synchronized (errors) { errors.add(e); } }
            });
            t.start(); threads.add(t);
        }
        go.countDown();
        for (Thread t : threads) t.join(30_000);

        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).isNotInstanceOf(NullPointerException.class);
        assertThat(bidRows(id)).isEqualTo(1);
        assertThat(storedPrice(id)).isEqualByComparingTo("124.69");
        Auction after = auctionRepository.findById(id).orElseThrow();
        assertThat(after.getTotalBids()).isEqualTo(1);
        assertThat(after.getWinnerId()).isIn(a, b);
        Long refused = after.getWinnerId().equals(a) ? b : a;
        assertThat(depositService.getFrozenAmountForAuction(after.getWinnerId(), id)).isEqualByComparingTo(DEPOSIT);
        assertThat(depositService.getFrozenAmountForAuction(refused, id))
                .as("the refused bid rolled back its deposit freeze").isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("admin preview: the form shows the server's values for the BD-02 example")
    void adminPreview() throws Exception {
        as(1L, "ADMIN", get("/api/v1/admin/auctions/bid-step").param("startingPrice", "1234567"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.minimumBidStep").value(12345.670))
                .andExpect(jsonPath("$.minimumFirstBid").value(1246912.67));
        as(1L, "ADMIN", get("/api/v1/admin/auctions/bid-step").param("startingPrice", "123.45"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.minimumBidStep").value(1.235))
                .andExpect(jsonPath("$.minimumFirstBid").value(124.69));
    }

    @ParameterizedTest(name = "startingPrice {0} -> 400")
    @ValueSource(strings = {"0", "-1", "123.4567", "100000000", "0.04", "abc", "NaN", ""})
    @DisplayName("admin preview: invalid starting prices are a 400")
    void adminPreviewInvalid(String price) throws Exception {
        as(1L, "ADMIN", get("/api/v1/admin/auctions/bid-step").param("startingPrice", price))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("SECURITY: the preview is admin-only")
    void adminPreviewForbiddenForUsers() throws Exception {
        as(bidder(), "USER", get("/api/v1/admin/auctions/bid-step").param("startingPrice", "1000"))
                .andExpect(status().isForbidden());
    }
}
