package com.qualiscan.websocket;

import com.qualiscan.security.JwtService;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.security.Principal;
import java.util.List;

/**
 * WebSocket and STOMP message broker configuration.
 */
@Configuration
@EnableWebSocketMessageBroker
@Order(Ordered.HIGHEST_PRECEDENCE + 99)
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtService jwtService;
    private final String allowedOriginsStr;

    public WebSocketConfig(
            JwtService jwtService,
            @org.springframework.beans.factory.annotation.Value("${qualiscan.cors.allowed-origins:http://localhost:5173,http://localhost:3000}") String allowedOriginsStr) {
        this.jwtService = jwtService;
        this.allowedOriginsStr = allowedOriginsStr;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Plain WebSocket endpoint /ws with permitted CORS origins
        java.util.List<String> origins = new java.util.ArrayList<>(java.util.Arrays.stream(allowedOriginsStr.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList());
        if (!origins.contains("https://*.vercel.app")) {
            origins.add("https://*.vercel.app");
        }
        if (!origins.contains("http://localhost:*")) {
            origins.add("http://localhost:*");
        }

        var registration = registry.addEndpoint("/ws");
        registration.setAllowedOriginPatterns(origins.toArray(new String[0]));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // In-memory simple broker on /topic and application prefix /app
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new StompAuthInterceptor(jwtService));
    }

    public static class StompAuthInterceptor implements ChannelInterceptor {
        private final JwtService jwtService;

        public StompAuthInterceptor(JwtService jwtService) {
            this.jwtService = jwtService;
        }

        @Override
        public Message<?> preSend(Message<?> message, MessageChannel channel) {
            StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
            if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
                // Reject device key connection attempts
                if (accessor.getFirstNativeHeader("X-API-Key") != null) {
                    throw new AccessDeniedException("Device API keys are not permitted to connect via WebSocket");
                }

                String authHeader = accessor.getFirstNativeHeader("Authorization");
                if (authHeader == null) {
                    authHeader = accessor.getFirstNativeHeader("authorization");
                }

                if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                    throw new AccessDeniedException("Missing or invalid Authorization header");
                }

                String token = authHeader.substring(7).trim();
                try {
                    var claims = jwtService.parse(token).getPayload();
                    String username = claims.getSubject();
                    String role = claims.get("role", String.class);

                    if (role == null || "DEVICE".equalsIgnoreCase(role)) {
                        throw new AccessDeniedException("Device API keys are not permitted to connect via WebSocket");
                    }

                    UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                            username,
                            null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + role))
                    );
                    accessor.setUser(auth);
                } catch (AccessDeniedException ex) {
                    throw ex;
                } catch (Exception ex) {
                    throw new AccessDeniedException("Invalid or expired JWT: " + ex.getMessage());
                }
            } else if (accessor != null && StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
                Principal user = accessor.getUser();
                if (user == null) {
                    throw new AccessDeniedException("Subscriptions require an authenticated user");
                }
            }

            return message;
        }
    }
}
