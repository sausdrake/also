package com.example.autoauction.auth.infrastructure.security;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.web.AuctionWebSocketController;
import com.example.autoauction.auth.domain.UserPrincipal;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.ByteArrayMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WS-SEC-01 against a real server (random port, raw STOMP over the plain "/ws" endpoint): a client can no longer
 * write into the broker. Before the fix any signed-in user could SEND to /topic/auction/{id} and every viewer of
 * that lot received the forged frame (proved live on the demo stand on 2026-10-23).
 * <ul>
 *   <li>authenticated client SEND to the lot topic - refused, the subscriber receives nothing;</li>
 *   <li>SEND to an application destination, to another user's queue - refused;</li>
 *   <li>SUBSCRIBE outside the lot topics and the caller's own queues - refused;</li>
 *   <li>a CONNECT without a valid access token - refused;</li>
 *   <li>the server's own event - still delivered.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("WS-SEC-01: clients cannot publish into broker topics (real STOMP)")
class WebSocketBrokerSecurityIntegrationTest {

    @LocalServerPort private int port;
    @Value("${allowed.origins}") private String allowedOrigins;
    @Autowired private JwtService jwtService;
    @Autowired private UserDetailsService userDetailsService;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private AuctionWebSocketController webSocketController;

    private WebSocketStompClient client;
    private final List<StompSession> sessions = new ArrayList<>();

    @BeforeEach
    void setUp() {
        client = new WebSocketStompClient(new StandardWebSocketClient());
        // raw bytes for every content type (the server sends application/json)
        client.setMessageConverter(new ByteArrayMessageConverter() {
            @Override
            protected boolean supportsMimeType(org.springframework.messaging.MessageHeaders headers) {
                return true;
            }
        });
    }

    @AfterEach
    void tearDown() {
        sessions.forEach(s -> { if (s.isConnected()) s.disconnect(); });
        client.stop();
    }

    private String accessTokenOfNewUser() {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "USER")));
        String name = "ws" + System.nanoTime();
        userRepository.save(new User(null, name, name + "@t.test", "x", true, Set.of(role)));
        UserPrincipal principal = (UserPrincipal) userDetailsService.loadUserByUsername(name);
        return jwtService.generateToken(principal);
    }

    /** Connects with the given bearer token; transport errors and ERROR frames land in {@code errors}. */
    private StompSession connect(String token, List<Throwable> errors) throws Exception {
        WebSocketHttpHeaders http = new WebSocketHttpHeaders();
        http.setOrigin(allowedOrigins.split(",")[0].trim());
        StompHeaders connect = new StompHeaders();
        if (token != null) {
            connect.add("Authorization", "Bearer " + token);
        }
        StompSession session = client.connectAsync("ws://localhost:" + port + "/ws", http, connect,
                new StompSessionHandlerAdapter() {
                    @Override
                    public void handleTransportError(StompSession s, Throwable exception) {
                        errors.add(exception);
                    }

                    @Override
                    public void handleException(StompSession s, org.springframework.messaging.simp.stomp.StompCommand command,
                                                StompHeaders headers, byte[] payload, Throwable exception) {
                        errors.add(exception);
                    }

                    @Override
                    public void handleFrame(StompHeaders headers, Object payload) {
                        errors.add(new IllegalStateException("ERROR frame: " + headers.getFirst("message")));
                    }
                }).get(10, TimeUnit.SECONDS);
        sessions.add(session);
        return session;
    }

    private BlockingQueue<String> subscribe(StompSession session, String destination) {
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                received.add(new String((byte[]) payload, java.nio.charset.StandardCharsets.UTF_8));
            }
        });
        return received;
    }

    private static void awaitClosed(StompSession session) throws InterruptedException {
        for (int i = 0; i < 50 && session.isConnected(); i++) {
            Thread.sleep(100);
        }
    }

    private Auction lot(long id) {
        Auction auction = new Auction(1L, new BigDecimal("1000000"), null, null, new BigDecimal("10000"),
                OffsetDateTime.now().minusHours(1), OffsetDateTime.now().plusDays(1), 1L);
        auction.setId(id);
        auction.setVehicleInfo("Toyota Camry 2019");
        return auction;
    }

    @Test
    @DisplayName("authenticated SEND to the lot topic is refused and never reaches the lot's viewers")
    void forgedLotEventRefused() throws Exception {
        List<Throwable> viewerErrors = new CopyOnWriteArrayList<>();
        StompSession viewer = connect(accessTokenOfNewUser(), viewerErrors);
        BlockingQueue<String> lotEvents = subscribe(viewer, "/topic/auction/77");
        Thread.sleep(300);   // the subscription is registered before the attack

        // control: the server's event reaches the viewer, so "nothing received" below is meaningful
        webSocketController.sendAuctionUpdate(lot(77), "NEW_BID");
        assertThat(lotEvents.poll(5, TimeUnit.SECONDS)).as("control event, errors: " + viewerErrors).isNotNull().contains("NEW_BID");

        List<Throwable> attackerErrors = new CopyOnWriteArrayList<>();
        StompSession attacker = connect(accessTokenOfNewUser(), attackerErrors);
        StompHeaders send = new StompHeaders();
        send.setDestination("/topic/auction/77");
        attacker.send(send, "{\"type\":\"AUCTION_ENDED\",\"winnerId\":1,\"winningBid\":1}".getBytes());

        assertThat(lotEvents.poll(1500, TimeUnit.MILLISECONDS)).as("forged frame must not be delivered").isNull();
        awaitClosed(attacker);
        assertThat(attacker.isConnected() && attackerErrors.isEmpty()).as("the attacker gets an ERROR / is disconnected").isFalse();
        assertThat(viewer.isConnected()).isTrue();

        // the server's own event still arrives
        webSocketController.sendAuctionUpdate(lot(77), "NEW_BID");
        String real = lotEvents.poll(5, TimeUnit.SECONDS);
        assertThat(real).as("errors: " + viewerErrors).isNotNull().contains("NEW_BID");
        assertThat(viewerErrors).isEmpty();
    }

    @Test
    @DisplayName("SEND to an application destination or a user queue is refused as well")
    void otherSendsRefused() throws Exception {
        for (String destination : List.of("/app/auction/77/bid", "/queue/notifications-user1", "/user/1/queue/notifications")) {
            List<Throwable> errors = new CopyOnWriteArrayList<>();
            StompSession session = connect(accessTokenOfNewUser(), errors);
            StompHeaders send = new StompHeaders();
            send.setDestination(destination);
            session.send(send, "{}".getBytes());
            awaitClosed(session);
            assertThat(session.isConnected() && errors.isEmpty()).as(destination).isFalse();
        }
    }

    @Test
    @DisplayName("SUBSCRIBE outside the lot topics and the caller's own queues is refused; allowed ones work")
    void subscriptionsRestricted() throws Exception {
        for (String destination : List.of("/topic/anything", "/queue/notifications", "/topic/auction/1/../2",
                "/user/5/queue/notifications", "/topic/auction/abc")) {
            List<Throwable> errors = new CopyOnWriteArrayList<>();
            StompSession session = connect(accessTokenOfNewUser(), errors);
            subscribe(session, destination);
            awaitClosed(session);
            assertThat(session.isConnected() && errors.isEmpty()).as(destination).isFalse();
        }
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        StompSession ok = connect(accessTokenOfNewUser(), errors);
        subscribe(ok, "/topic/auction/5");
        subscribe(ok, "/user/queue/notifications");
        subscribe(ok, "/user/queue/auction-updates");
        Thread.sleep(500);
        assertThat(ok.isConnected()).isTrue();
        assertThat(errors).isEmpty();
    }

    @Test
    @DisplayName("CONNECT without a valid access token is refused")
    void connectWithoutTokenRefused() {
        for (String token : new String[]{null, "not.a.jwt"}) {
            List<Throwable> errors = new CopyOnWriteArrayList<>();
            boolean connected;
            try {
                StompSession s = connect(token, errors);
                awaitClosed(s);
                connected = s.isConnected() && errors.isEmpty();
            } catch (Exception refused) {
                connected = false;
            }
            assertThat(connected).as("token " + token).isFalse();
        }
    }
}
