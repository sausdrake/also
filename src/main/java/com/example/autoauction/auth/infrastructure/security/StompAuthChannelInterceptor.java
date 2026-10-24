package com.example.autoauction.auth.infrastructure.security;

import com.example.autoauction.auth.domain.UserPrincipal;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.regex.Pattern;

@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;

    public StompAuthChannelInterceptor(JwtService jwtService, UserDetailsService userDetailsService) {
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
    }

    /**
     * WS-SEC-01: the only destinations a client may subscribe to - the public events of one lot and the
     * user's own queues (Spring resolves {@code /user/queue/...} to this session's principal).
     */
    private static final Pattern ALLOWED_SUBSCRIPTION =
            Pattern.compile("^/topic/auction/\\d+$|^/user/queue/(notifications|auction-updates)$");

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor == null) {
            return message;
        }

        // WS-SEC-01: every event is server -> client; the server has no client command handler at all
        // (no @MessageMapping), so a client SEND can only be an attempt to write straight into a broker
        // topic - e.g. a forged "new leader" / "auction ended" on /topic/auction/{id}. Refused for every
        // destination. The server's own events go through the broker channel, never through here.
        if (StompCommand.SEND.equals(accessor.getCommand())) {
            throw new MessageDeliveryException(message, "WebSocket SEND rejected: clients may not publish");
        }
        if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            String destination = accessor.getDestination();
            if (destination == null || !ALLOWED_SUBSCRIPTION.matcher(destination).matches()) {
                throw new MessageDeliveryException(message, "WebSocket SUBSCRIBE rejected: destination not allowed");
            }
            return message;
        }

        if (!StompCommand.CONNECT.equals(accessor.getCommand())) {
            return message;
        }

        String authHeader = accessor.getFirstNativeHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new MessageDeliveryException(message,
                    "WebSocket CONNECT rejected: missing or malformed Authorization header");
        }

        String jwt = authHeader.substring(7);

        try {
            String username = jwtService.extractUsername(jwt);
            UserDetails userDetails = userDetailsService.loadUserByUsername(username);

            if (!jwtService.isTokenValid(jwt, userDetails)) {
                throw new MessageDeliveryException(message,
                        "WebSocket CONNECT rejected: invalid or expired token");
            }

            if (!userDetails.isEnabled()) {
                throw new MessageDeliveryException(message,
                        "WebSocket CONNECT rejected: user account is disabled");
            }

            Long userId = ((UserPrincipal) userDetails).getUserId();
            String principalName = userId.toString();
            Principal principal = () -> principalName;
            accessor.setUser(principal);

        } catch (MessageDeliveryException e) {
            throw e;
        } catch (Exception e) {
            throw new MessageDeliveryException(message,
                    "WebSocket CONNECT rejected: authentication failed");
        }

        return message;
    }
}
