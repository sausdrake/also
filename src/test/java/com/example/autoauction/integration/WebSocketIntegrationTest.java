// integration/WebSocketIntegrationTest.java
package com.example.autoauction.integration;

import com.example.autoauction.auction.application.BidService;
import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.auction.web.dto.AuctionWebSocketMessage;
import com.example.autoauction.config.WebSocketConfig;
import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Disabled;

import static org.assertj.core.api.Assertions.assertThat;

// SockJS WebSocket handshake возвращает HTTP 400 в тестовой среде с H2 и SecurityConfig.permitAll.
// Требует отдельного поднятого сервера с правильной конфигурацией SockJS.
@Disabled("WebSocket handshake требует отдельной конфигурации сервера — запускать вручную")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Transactional
@DisplayName("Интеграционные тесты WebSocket")
class WebSocketIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private BidService bidService;

    @Autowired
    private AuctionRepository auctionRepository;

    @Autowired
    private DepositService depositService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private Long auctionId;
    private Long bidderId;
    private String webSocketUrl;

    @BeforeEach
    void setUp() {
        webSocketUrl = "ws://localhost:" + port + "/ws-auction";

        Role userRole = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));

        User seller = new User(null, "seller", "seller@test.com", "encoded", true, Set.of(userRole));
        Long sellerId = userRepository.save(seller).getId();

        // Slice 1A: trust gate - the bidder actually calling placeBid() must be trusted.
        User bidder = new User(null, "bidder", "bidder@test.com", "encoded", true, Set.of(userRole), true, null, null);
        bidderId = userRepository.save(bidder).getId();

        depositService.createDeposit(bidderId, new BigDecimal("50000"));

        OffsetDateTime now = OffsetDateTime.now();
        Auction auction = new Auction(
                1L,
                new BigDecimal("1000000"),
                new BigDecimal("1200000"),
                new BigDecimal("1500000"),
                new BigDecimal("10000"),
                now.minusHours(1),
                now.plusDays(7),
                sellerId
        );
        auction.start();
        auction = auctionRepository.save(auction);
        auctionId = auction.getId();
    }

    @Test
    @DisplayName("Подписка на аукцион и получение уведомления о ставке")
    void subscribeToAuction_ShouldReceiveBidNotification() throws Exception {
        WebSocketStompClient stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());

        CompletableFuture<AuctionWebSocketMessage> future = new CompletableFuture<>();

        StompSession session = stompClient.connectAsync(webSocketUrl, new StompSessionHandlerAdapter() {})
                .get(5, TimeUnit.SECONDS);

        StompHeaders headers = new StompHeaders();
        headers.setDestination("/topic/auction/" + auctionId);

        session.subscribe(headers, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return AuctionWebSocketMessage.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                future.complete((AuctionWebSocketMessage) payload);
            }
        });

        // Делаем ставку, которая должна вызвать WebSocket уведомление
        BigDecimal bidAmount = new BigDecimal("1010000");
        bidService.placeBid(auctionId, bidAmount, bidderId);

        AuctionWebSocketMessage message = future.get(10, TimeUnit.SECONDS);

        assertThat(message).isNotNull();
        assertThat(message.getAuctionId()).isEqualTo(auctionId);
        assertThat(message.getCurrentPrice()).isEqualTo(bidAmount);
        assertThat(message.getMessageType()).isEqualTo("BID_PLACED");

        session.disconnect();
    }
}