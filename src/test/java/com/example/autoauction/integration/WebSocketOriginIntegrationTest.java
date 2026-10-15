package com.example.autoauction.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.net.URI;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * SEC-03: the STOMP endpoints accept a browser handshake only from the configured origins (the
 * same allowed.origins as CORS; http://localhost:5173 in the test profile), no longer from any
 * origin. Clients that send no Origin header (wscat, server-side clients) still connect.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("SEC-03: WebSocket handshakes are limited to the allowed origins")
class WebSocketOriginIntegrationTest {

    private static final String ALLOWED = "http://localhost:5173";
    private static final String FOREIGN = "https://evil.example";

    @LocalServerPort private int port;
    // MockMvc, not TestRestTemplate: the JDK HTTP client silently drops the restricted Origin header.
    @Autowired private MockMvc mockMvc;

    private WebSocketSession connect(String origin) throws Exception {
        return connect(origin, "/ws");
    }

    private WebSocketSession connect(String origin, String path) throws Exception {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        if (origin != null) {
            headers.setOrigin(origin);
        }
        return new StandardWebSocketClient()
                .execute(new AbstractWebSocketHandler() {}, headers, URI.create("ws://localhost:" + port + path))
                .get(10, TimeUnit.SECONDS);
    }

    @Test
    void rawEndpoint_foreignOriginRefused_allowedAndOriginlessAccepted() throws Exception {
        assertThatThrownBy(() -> connect(FOREIGN)).isInstanceOf(ExecutionException.class);

        WebSocketSession allowed = connect(ALLOWED);
        assertThat(allowed.isOpen()).isTrue();
        allowed.close();

        WebSocketSession noOrigin = connect(null);
        assertThat(noOrigin.isOpen()).isTrue();
        noOrigin.close();
    }

    @Test
    void sockJsWebSocketTransport_foreignOriginRefused_allowedAccepted() throws Exception {
        assertThatThrownBy(() -> connect(FOREIGN, "/ws-auction/123/sec03a/websocket")).isInstanceOf(ExecutionException.class);
        WebSocketSession allowed = connect(ALLOWED, "/ws-auction/123/sec03b/websocket");
        assertThat(allowed.isOpen()).isTrue();
        allowed.close();
    }

    @Test
    void sockJsEndpoint_foreignOriginRefused() throws Exception {
        int foreign = sockJsInfo(FOREIGN);
        int allowed = sockJsInfo(ALLOWED);
        assertThat(foreign).isEqualTo(403);
        assertThat(allowed).isEqualTo(200);
    }

    private int sockJsInfo(String origin) throws Exception {
        return mockMvc.perform(get("/ws-auction/info").header("Origin", origin))
                .andReturn().getResponse().getStatus();
    }
}
