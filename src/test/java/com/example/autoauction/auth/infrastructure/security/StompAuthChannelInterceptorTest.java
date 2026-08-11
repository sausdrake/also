package com.example.autoauction.auth.infrastructure.security;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.web.AuctionWebSocketController;
import com.example.autoauction.auth.domain.UserPrincipal;
import com.example.autoauction.auction.web.dto.AuctionWebSocketMessage;
import com.example.autoauction.notification.application.NotificationService;
import com.example.autoauction.notification.infrastructure.persistence.JpaNotificationEntity;
import com.example.autoauction.notification.infrastructure.persistence.SpringDataNotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("WebSocket Security — тесты аутентификации STOMP и изоляции уведомлений")
class StompAuthChannelInterceptorTest {

    // ── interceptor unit-test dependencies ───────────────────────────────────

    @Mock
    private JwtService jwtService;

    @Mock
    private UserDetailsService userDetailsService;

    @InjectMocks
    private StompAuthChannelInterceptor interceptor;

    // ── shared fixtures ───────────────────────────────────────────────────────

    private static final String VALID_TOKEN  = "valid.jwt.token";
    private static final String BAD_TOKEN    = "malformed.jwt.value";
    private static final String USERNAME_A   = "alice";
    private static final String USERNAME_B   = "bob";
    private static final Long   USER_ID_A    = 42L;
    private static final Long   USER_ID_B    = 99L;

    private UserPrincipal alicePrincipal;
    private UserPrincipal bobPrincipal;
    private UserPrincipal disabledPrincipal;

    @BeforeEach
    void setUpPrincipals() {
        alicePrincipal = new UserPrincipal(
                USERNAME_A, "pw",
                List.of(new SimpleGrantedAuthority("ROLE_USER")),
                USER_ID_A);
        bobPrincipal = new UserPrincipal(
                USERNAME_B, "pw",
                List.of(new SimpleGrantedAuthority("ROLE_USER")),
                USER_ID_B);
        disabledPrincipal = new UserPrincipal(
                USERNAME_A, "pw",
                List.of(new SimpleGrantedAuthority("ROLE_USER")),
                USER_ID_A) {
            @Override public boolean isEnabled() { return false; }
        };
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Message<?> connectMessage(String authHeaderValue) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        if (authHeaderValue != null) {
            accessor.addNativeHeader("Authorization", authHeaderValue);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private StompHeaderAccessor mutableAccessorFor(Message<?> msg) {
        return org.springframework.messaging.support.MessageHeaderAccessor
                .getAccessor(msg, StompHeaderAccessor.class);
    }

    private Message<?> nonConnectMessage(StompCommand command) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Tests 1-2 — Missing / malformed Authorization header
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Тест 1-2 — Отсутствующий или некорректный заголовок Authorization")
    class MissingOrMalformedAuth {

        @Test
        @DisplayName("1. CONNECT без Authorization header → MessageDeliveryException")
        void connect_withoutAuthHeader_throws() {
            Message<?> msg = connectMessage(null);

            assertThatThrownBy(() -> interceptor.preSend(msg, null))
                    .isInstanceOf(MessageDeliveryException.class)
                    .hasMessageContaining("missing or malformed Authorization header");

            verifyNoInteractions(jwtService, userDetailsService);
        }

        @Test
        @DisplayName("2. CONNECT с 'Token xyz' вместо 'Bearer xyz' → MessageDeliveryException")
        void connect_withWrongAuthScheme_throws() {
            Message<?> msg = connectMessage("Token " + VALID_TOKEN);

            assertThatThrownBy(() -> interceptor.preSend(msg, null))
                    .isInstanceOf(MessageDeliveryException.class)
                    .hasMessageContaining("missing or malformed Authorization header");

            verifyNoInteractions(jwtService, userDetailsService);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Tests 3-4 — JWT validation failures
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Тест 3-4 — Невалидный или истёкший JWT")
    class JwtValidationFailures {

        @Test
        @DisplayName("3. CONNECT с синтаксически некорректным JWT → MessageDeliveryException")
        void connect_withUnparsableJwt_throws() {
            when(jwtService.extractUsername(BAD_TOKEN))
                    .thenThrow(new RuntimeException("JWT parse error"));

            Message<?> msg = connectMessage("Bearer " + BAD_TOKEN);

            assertThatThrownBy(() -> interceptor.preSend(msg, null))
                    .isInstanceOf(MessageDeliveryException.class)
                    .hasMessageContaining("authentication failed");

            verify(jwtService).extractUsername(BAD_TOKEN);
            verifyNoInteractions(userDetailsService);
        }

        @Test
        @DisplayName("4. CONNECT с истёкшим JWT (isTokenValid=false) → MessageDeliveryException")
        void connect_withExpiredJwt_throws() {
            when(jwtService.extractUsername(VALID_TOKEN)).thenReturn(USERNAME_A);
            when(userDetailsService.loadUserByUsername(USERNAME_A)).thenReturn(alicePrincipal);
            when(jwtService.isTokenValid(VALID_TOKEN, alicePrincipal)).thenReturn(false);

            Message<?> msg = connectMessage("Bearer " + VALID_TOKEN);

            assertThatThrownBy(() -> interceptor.preSend(msg, null))
                    .isInstanceOf(MessageDeliveryException.class)
                    .hasMessageContaining("invalid or expired token");
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Test 5 — Successful CONNECT
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Тест 5 — Успешная аутентификация STOMP CONNECT")
    class SuccessfulConnect {

        @Test
        @DisplayName("5. CONNECT с валидным JWT → принципал установлен, name = userId")
        void connect_withValidJwt_setsPrincipalToUserId() {
            when(jwtService.extractUsername(VALID_TOKEN)).thenReturn(USERNAME_A);
            when(userDetailsService.loadUserByUsername(USERNAME_A)).thenReturn(alicePrincipal);
            when(jwtService.isTokenValid(VALID_TOKEN, alicePrincipal)).thenReturn(true);

            Message<?> msg = connectMessage("Bearer " + VALID_TOKEN);
            StompHeaderAccessor accessor = mutableAccessorFor(msg);

            interceptor.preSend(msg, null);

            assertThat(accessor.getUser()).isNotNull();
            assertThat(accessor.getUser().getName()).isEqualTo(USER_ID_A.toString());
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Test 6 — Blocked / disabled user
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Тест 6 — Заблокированный пользователь")
    class DisabledUser {

        @Test
        @DisplayName("6. CONNECT с валидным JWT, но заблокированным аккаунтом → MessageDeliveryException")
        void connect_withDisabledUser_throws() {
            when(jwtService.extractUsername(VALID_TOKEN)).thenReturn(USERNAME_A);
            when(userDetailsService.loadUserByUsername(USERNAME_A)).thenReturn(disabledPrincipal);
            when(jwtService.isTokenValid(VALID_TOKEN, disabledPrincipal)).thenReturn(true);

            Message<?> msg = connectMessage("Bearer " + VALID_TOKEN);

            assertThatThrownBy(() -> interceptor.preSend(msg, null))
                    .isInstanceOf(MessageDeliveryException.class)
                    .hasMessageContaining("user account is disabled");
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Test 7 — Non-CONNECT commands pass through without auth check
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Тест 7 — Не-CONNECT команды пропускаются без проверки")
    class NonConnectPassthrough {

        @Test
        @DisplayName("7a. SUBSCRIBE проходит без вызова JwtService")
        void subscribe_passesThrough_withoutAuthCheck() {
            Message<?> msg = nonConnectMessage(StompCommand.SUBSCRIBE);
            Message<?> result = interceptor.preSend(msg, null);

            assertThat(result).isSameAs(msg);
            verifyNoInteractions(jwtService, userDetailsService);
        }

        @Test
        @DisplayName("7b. SEND проходит без вызова JwtService")
        void send_passesThrough_withoutAuthCheck() {
            Message<?> msg = nonConnectMessage(StompCommand.SEND);
            Message<?> result = interceptor.preSend(msg, null);

            assertThat(result).isSameAs(msg);
            verifyNoInteractions(jwtService, userDetailsService);
        }

        @Test
        @DisplayName("7c. DISCONNECT проходит без вызова JwtService")
        void disconnect_passesThrough_withoutAuthCheck() {
            Message<?> msg = nonConnectMessage(StompCommand.DISCONNECT);
            Message<?> result = interceptor.preSend(msg, null);

            assertThat(result).isSameAs(msg);
            verifyNoInteractions(jwtService, userDetailsService);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Tests 8-9 — Principal isolation: userId as principal name
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Тест 8-9 — Изоляция пользователей по userId как principal name")
    class PrincipalIsolation {

        @Test
        @DisplayName("8. Principal name — это userId (не username), чтобы convertAndSendToUser работал корректно")
        void connect_principalName_isUserId_notUsername() {
            when(jwtService.extractUsername(VALID_TOKEN)).thenReturn(USERNAME_A);
            when(userDetailsService.loadUserByUsername(USERNAME_A)).thenReturn(alicePrincipal);
            when(jwtService.isTokenValid(VALID_TOKEN, alicePrincipal)).thenReturn(true);

            Message<?> msg = connectMessage("Bearer " + VALID_TOKEN);
            StompHeaderAccessor accessor = mutableAccessorFor(msg);

            interceptor.preSend(msg, null);

            assertThat(accessor.getUser().getName())
                    .isEqualTo("42")
                    .isNotEqualTo(USERNAME_A);
        }

        @Test
        @DisplayName("9. Два разных пользователя получают разные userId в качестве principal name")
        void connect_differentUsers_getDifferentPrincipalNames() {
            String tokenA = "token.for.alice";
            String tokenB = "token.for.bob";

            when(jwtService.extractUsername(tokenA)).thenReturn(USERNAME_A);
            when(userDetailsService.loadUserByUsername(USERNAME_A)).thenReturn(alicePrincipal);
            when(jwtService.isTokenValid(tokenA, alicePrincipal)).thenReturn(true);

            when(jwtService.extractUsername(tokenB)).thenReturn(USERNAME_B);
            when(userDetailsService.loadUserByUsername(USERNAME_B)).thenReturn(bobPrincipal);
            when(jwtService.isTokenValid(tokenB, bobPrincipal)).thenReturn(true);

            Message<?> msgA = connectMessage("Bearer " + tokenA);
            Message<?> msgB = connectMessage("Bearer " + tokenB);
            StompHeaderAccessor accessorA = mutableAccessorFor(msgA);
            StompHeaderAccessor accessorB = mutableAccessorFor(msgB);

            interceptor.preSend(msgA, null);
            interceptor.preSend(msgB, null);

            assertThat(accessorA.getUser().getName()).isEqualTo(USER_ID_A.toString()); // "42"
            assertThat(accessorB.getUser().getName()).isEqualTo(USER_ID_B.toString()); // "99"
            assertThat(accessorA.getUser().getName()).isNotEqualTo(accessorB.getUser().getName());
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Tests 10-12 — Anonymous cannot reach private destinations; public topics
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Тест 10-12 — Анонимный доступ и публичные топики")
    class AnonymousAndPublicTopics {

        @Test
        @DisplayName("10. Анонимный клиент не может подключиться к WebSocket (CONNECT без JWT → исключение)")
        void anonymous_connectRejected_cannotReachAnyDestination() {
            Message<?> msg = connectMessage(null);

            assertThatThrownBy(() -> interceptor.preSend(msg, null))
                    .isInstanceOf(MessageDeliveryException.class);
        }

        @Test
        @DisplayName("11. Аутентифицированный пользователь может подписаться на публичный топик аукциона")
        void authenticated_user_canSubscribeToPublicAuctionTopic() {
            // Verify that the interceptor does NOT block SUBSCRIBE frames —
            // the public auction topic /topic/auction/{id} is open to all connected (authenticated) users.
            Message<?> subscribeMsg = nonConnectMessage(StompCommand.SUBSCRIBE);
            Message<?> result = interceptor.preSend(subscribeMsg, null);

            assertThat(result).isSameAs(subscribeMsg);
            verifyNoInteractions(jwtService);
        }

        @Test
        @DisplayName("12. NotificationService отправляет только в приватную очередь, а не в публичный топик")
        void notificationService_sendsToPrivateQueue_notPublicTopic() {
            // The correct destination is /queue/notifications (routed per-user via convertAndSendToUser),
            // NOT the public /topic/user/{userId} that any subscriber could read.
            SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
            SpringDataNotificationRepository repo = mock(SpringDataNotificationRepository.class);
            JpaNotificationEntity savedEntity = new JpaNotificationEntity();
            savedEntity.setId(1L);
            savedEntity.setUserId(42L);
            savedEntity.setType("TEST");
            savedEntity.setTitle("t");
            savedEntity.setMessage("m");
            when(repo.save(any())).thenReturn(savedEntity);

            com.example.autoauction.notification.application.NotificationService svc =
                    new com.example.autoauction.notification.application.NotificationService(repo, template);

            svc.send(42L, "TEST", "t", "m");

            // Must use convertAndSendToUser — never the public topic
            verify(template).convertAndSendToUser(eq("42"), eq("/queue/notifications"), any());
            verify(template, never()).convertAndSend(anyString(), any(Object.class));
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Tests 13-14 — Existing auction WebSocket functionality not broken
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Тест 13-14 — Функциональность аукционных WebSocket-уведомлений сохранена")
    class ExistingAuctionFunctionality {

        @Test
        @DisplayName("13. AuctionWebSocketController отправляет обновление аукциона на публичный топик")
        void auctionController_sendsUpdate_toPublicTopic() {
            SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
            AuctionWebSocketController controller = new AuctionWebSocketController(template);

            Auction auction = new Auction(
                    1L,
                    new BigDecimal("100000"),
                    new BigDecimal("120000"),
                    new BigDecimal("150000"),
                    new BigDecimal("5000"),
                    OffsetDateTime.now().minusHours(1),
                    OffsetDateTime.now().plusDays(1),
                    10L
            );
            auction.setId(1L);
            auction.start();

            controller.sendAuctionUpdate(auction, "BID_PLACED");

            verify(template).convertAndSend(eq("/topic/auction/1"), any(AuctionWebSocketMessage.class));
        }

        @Test
        @DisplayName("14. AuctionWebSocketController отправляет личное обновление через convertAndSendToUser")
        void auctionController_sendsPersonalUpdate_toUserQueue() {
            SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
            AuctionWebSocketController controller = new AuctionWebSocketController(template);

            Auction auction = new Auction(
                    2L,
                    new BigDecimal("100000"),
                    new BigDecimal("120000"),
                    new BigDecimal("150000"),
                    new BigDecimal("5000"),
                    OffsetDateTime.now().minusHours(1),
                    OffsetDateTime.now().plusDays(1),
                    10L
            );
            auction.setId(2L);
            auction.start();

            controller.sendPersonalUpdate(USER_ID_A, auction, "WON");

            verify(template).convertAndSendToUser(eq("42"), eq("/queue/auction-updates"), any(AuctionWebSocketMessage.class));
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Test 15 — JWT value is not exposed in exception messages
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Тест 15 — JWT не утекает в сообщениях об ошибках")
    class NoTokenLeakInErrors {

        @Test
        @DisplayName("15. Значение JWT не содержится в тексте MessageDeliveryException")
        void authenticationFailure_doesNotLeakJwtValue() {
            when(jwtService.extractUsername(VALID_TOKEN)).thenReturn(USERNAME_A);
            when(userDetailsService.loadUserByUsername(USERNAME_A)).thenReturn(alicePrincipal);
            when(jwtService.isTokenValid(VALID_TOKEN, alicePrincipal)).thenReturn(false);

            Message<?> msg = connectMessage("Bearer " + VALID_TOKEN);

            assertThatThrownBy(() -> interceptor.preSend(msg, null))
                    .isInstanceOf(MessageDeliveryException.class)
                    .hasMessageNotContaining(VALID_TOKEN)
                    .hasMessageNotContaining("Bearer");
        }
    }
}
