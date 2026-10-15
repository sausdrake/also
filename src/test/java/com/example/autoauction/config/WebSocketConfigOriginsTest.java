package com.example.autoauction.config;

import com.example.autoauction.auth.infrastructure.security.StompAuthChannelInterceptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.config.annotation.SockJsServiceRegistration;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.StompWebSocketEndpointRegistration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SEC-03: both STOMP endpoints are registered with the exact configured origins (trimmed), never
 * with an origin pattern. The Spring Security CORS filter already refuses foreign origins in
 * front of them; this pins the endpoints' own check, which must hold even if CORS changes.
 */
@DisplayName("SEC-03: STOMP endpoints use the configured origins")
class WebSocketConfigOriginsTest {

    @Test
    void endpointsRegisteredWithExactOrigins() {
        StompEndpointRegistry registry = mock(StompEndpointRegistry.class);
        StompWebSocketEndpointRegistration sockJsEndpoint = mock(StompWebSocketEndpointRegistration.class);
        StompWebSocketEndpointRegistration rawEndpoint = mock(StompWebSocketEndpointRegistration.class);
        when(registry.addEndpoint("/ws-auction")).thenReturn(sockJsEndpoint);
        when(registry.addEndpoint("/ws")).thenReturn(rawEndpoint);
        when(sockJsEndpoint.setAllowedOrigins(any(String[].class))).thenReturn(sockJsEndpoint);
        when(rawEndpoint.setAllowedOrigins(any(String[].class))).thenReturn(rawEndpoint);
        when(sockJsEndpoint.withSockJS()).thenReturn(mock(SockJsServiceRegistration.class));

        new WebSocketConfig(mock(StompAuthChannelInterceptor.class), "https://a.example, https://b.example ,")
                .registerStompEndpoints(registry);

        for (StompWebSocketEndpointRegistration endpoint : new StompWebSocketEndpointRegistration[]{sockJsEndpoint, rawEndpoint}) {
            verify(endpoint, times(1)).setAllowedOrigins("https://a.example", "https://b.example");
            verify(endpoint, never()).setAllowedOriginPatterns(anyString());
            verify(endpoint, never()).setAllowedOriginPatterns(any(String[].class));
        }
        verify(sockJsEndpoint).withSockJS();
    }
}
