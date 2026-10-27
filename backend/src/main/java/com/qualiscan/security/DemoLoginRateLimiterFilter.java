package com.qualiscan.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory login rate limiter active only in the "demo" profile.
 * Limits login attempts to 10 per minute per client IP, returning 429 Too Many Requests on the 11th attempt.
 */
@Component
@Profile("demo")
public class DemoLoginRateLimiterFilter extends OncePerRequestFilter {

    private static final int MAX_ATTEMPTS_PER_MINUTE = 10;
    private final ConcurrentHashMap<String, Deque<Instant>> attemptsByIp = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        if ("/api/auth/login".equals(request.getRequestURI()) && "POST".equalsIgnoreCase(request.getMethod())) {
            String clientIp = extractClientIp(request);
            Instant now = Instant.now();
            Instant windowStart = now.minusSeconds(60);

            Deque<Instant> attempts = attemptsByIp.computeIfAbsent(clientIp, k -> new ArrayDeque<>());
            synchronized (attempts) {
                while (!attempts.isEmpty() && attempts.peekFirst().isBefore(windowStart)) {
                    attempts.pollFirst();
                }

                if (attempts.size() >= MAX_ATTEMPTS_PER_MINUTE) {
                    response.setStatus(429);
                    response.setContentType("application/json");
                    response.setCharacterEncoding("UTF-8");
                    response.getWriter().write("{\"timestamp\":\"" + now + "\",\"status\":429,\"error\":\"Too Many Requests\",\"message\":\"Too many login attempts. Please wait a minute before trying again.\",\"path\":\"" + request.getRequestURI() + "\"}");
                    return;
                }

                attempts.addLast(now);
            }
        }

        filterChain.doFilter(request, response);
    }

    private String extractClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr() != null ? request.getRemoteAddr() : "unknown";
    }

    /**
     * Helper to clear rate limiter state for tests.
     */
    public void reset() {
        attemptsByIp.clear();
    }
}
