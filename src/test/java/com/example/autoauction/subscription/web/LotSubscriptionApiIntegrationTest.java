package com.example.autoauction.subscription.web;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.BidStepPolicy;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.auth.domain.TestUserPrincipal;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * MVP certification sweep finding: subscribing to an auction that does not exist was a 500 (the swallowed
 * foreign-key failure left the transaction rollback-only). It is a 404 now; subscribing twice stays idempotent.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Lot subscriptions: missing auction -> 404, repeat subscribe idempotent")
class LotSubscriptionApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private AuctionRepository auctionRepository;
    @Autowired private JdbcTemplate jdbc;

    private Long user() {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        String name = "sub" + System.nanoTime();
        return userRepository.save(new User(null, name, name + "@test.com", "encoded", true, Set.of(role))).getId();
    }

    private Long auction(Long seller) {
        OffsetDateTime now = OffsetDateTime.now();
        BigDecimal start = new BigDecimal("100000");
        Auction a = new Auction(1L, start, null, null, BidStepPolicy.minimumBidStep(start), now.plusHours(1), now.plusDays(1), seller);
        a.setRequiredDepositAmount(new BigDecimal("10000"));
        return auctionRepository.save(a).getId();
    }

    private int as(Long userId, MockHttpServletRequestBuilder request) throws Exception {
        TestUserPrincipal p = TestUserPrincipal.createWithRoles(userId, "user" + userId, "USER");
        return mockMvc.perform(request.with(authentication(new UsernamePasswordAuthenticationToken(p, "password", p.getAuthorities()))))
                .andReturn().getResponse().getStatus();
    }

    @Test
    @DisplayName("a non-existent auction -> 404, nothing stored")
    void missingAuction() throws Exception {
        Long u = user();
        assertThat(as(u, post("/api/subscriptions/987654321"))).isEqualTo(404);
        assertThat(jdbc.queryForObject("select count(*) from lot_subscriptions where user_id = ?", Integer.class, u)).isZero();
    }

    @Test
    @DisplayName("subscribing twice -> 200 both times, one row")
    void idempotent() throws Exception {
        Long u = user();
        Long a = auction(user());
        assertThat(as(u, post("/api/subscriptions/" + a))).isEqualTo(200);
        assertThat(as(u, post("/api/subscriptions/" + a))).isEqualTo(200);
        assertThat(jdbc.queryForObject("select count(*) from lot_subscriptions where user_id = ? and auction_id = ?", Integer.class, u, a)).isEqualTo(1);
    }
}
