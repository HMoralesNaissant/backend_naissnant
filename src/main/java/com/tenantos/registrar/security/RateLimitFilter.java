package com.tenantos.registrar.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple in-memory rate limiter per IP using a sliding window (timestamps queue).
 * Not suitable for distributed systems — for production use a distributed store (Redis) or API gateway.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final Map<String, Deque<Long>> requests = new ConcurrentHashMap<>();
    private final int maxRequests;
    private final int windowSeconds;
    private final String limitedPathPrefix;

    public RateLimitFilter(int maxRequests, int windowSeconds, String limitedPathPrefix) {
        this.maxRequests = maxRequests;
        this.windowSeconds = windowSeconds;
        this.limitedPathPrefix = limitedPathPrefix;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        String ip = request.getRemoteAddr();
        // One shared limit for the whole funnel under limitedPathPrefix - new endpoints
        // added under it are covered automatically, no path enumeration to maintain.
        if (request.getRequestURI().startsWith(limitedPathPrefix)) {
            Deque<Long> queue = requests.computeIfAbsent(ip, k -> new ArrayDeque<>());
            long now = Instant.now().getEpochSecond();
            synchronized (queue) {
                while (!queue.isEmpty() && queue.peekFirst() <= now - windowSeconds) {
                    queue.pollFirst();
                }
                if (queue.size() >= maxRequests) {
                    response.setStatus(429);
                    response.getWriter().write("Too many requests");
                    return;
                }
                queue.addLast(now);
            }
        }
        filterChain.doFilter(request, response);
    }
}
