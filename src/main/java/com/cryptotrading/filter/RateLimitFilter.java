package com.cryptotrading.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Enforces per-user rate limits on the trade execution endpoint to prevent abuse.
 *
 * <p>Applies a sliding window rate limit of 10 requests per 10-second window, scoped to
 * {@code POST /api/trades} only. All read endpoints ({@code GET /api/prices},
 * {@code GET /api/wallets}, {@code GET /api/trades}) bypass this filter entirely.</p>
 *
 * <p>Rate limit counters are stored in-memory using
 * {@code ConcurrentHashMap<String, ConcurrentLinkedDeque<Long>>} — O(1) add/poll operations,
 * avoiding the O(n) array-copy overhead of {@code CopyOnWriteArrayList}.
 * A scheduled cleanup runs every 30 seconds to evict expired entries.</p>
 *
 * <p>Returns HTTP 429 with a {@code Retry-After} header when the limit is exceeded.</p>
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final int MAX_REQUESTS = 10;
    private static final long WINDOW_MS = 10_000;

    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<Long>> requestLog = new ConcurrentHashMap<>();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equals(request.getMethod()) && "/api/trades".equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        String userId = "1";
        long now = System.currentTimeMillis();
        long windowStart = now - WINDOW_MS;

        ConcurrentLinkedDeque<Long> timestamps = requestLog.computeIfAbsent(userId, k -> new ConcurrentLinkedDeque<>());

        // Evict expired entries from the head (oldest first)
        while (!timestamps.isEmpty()) {
            Long oldest = timestamps.peekFirst();
            if (oldest != null && oldest < windowStart) {
                timestamps.pollFirst();
            } else {
                break;
            }
        }

        if (timestamps.size() >= MAX_REQUESTS) {
            Long oldest = timestamps.peekFirst();
            long retryAfter = oldest != null
                    ? (WINDOW_MS - (now - oldest)) / 1000 + 1
                    : 1;

            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(retryAfter));
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Rate limit exceeded\",\"retryAfter\":" + retryAfter + "}");
            return;
        }

        timestamps.addLast(now);
        filterChain.doFilter(request, response);
    }

    @Scheduled(fixedRate = 30000)
    public void cleanup() {
        long windowStart = System.currentTimeMillis() - WINDOW_MS;
        requestLog.forEach((key, timestamps) -> {
            while (!timestamps.isEmpty()) {
                Long oldest = timestamps.peekFirst();
                if (oldest != null && oldest < windowStart) {
                    timestamps.pollFirst();
                } else {
                    break;
                }
            }
            if (timestamps.isEmpty()) {
                requestLog.remove(key);
            }
        });
    }
}
