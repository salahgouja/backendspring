package com.amenbank.banking_webapp.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GAP-19: API Rate Limiting interceptor using Bucket4j (token bucket algorithm).
 * Limits:
 *   - General API: 60 requests/minute per IP
 *   - Auth endpoints: 10 requests/minute per IP (brute-force protection)
 *   - Transfer endpoints: 20 requests/minute per IP
 */
@Component
@Slf4j
public class RateLimitInterceptor implements HandlerInterceptor {

    // In-memory buckets keyed by IP + category
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String clientIp = getClientIp(request);
        String path = request.getRequestURI();

        // Determine rate limit tier
        String bucketKey;
        Bucket bucket;

        if (path.contains("/auth/login") || path.contains("/auth/register")
                || path.contains("/auth/forgot-password") || path.contains("/auth/reset-password")) {
            // Auth endpoints: strict — 10 req/min
            bucketKey = clientIp + ":auth";
            bucket = buckets.computeIfAbsent(bucketKey, k -> createBucket(10, 1));
        } else if (path.contains("/transfers") || path.contains("/credits/submit")) {
            // Financial ops: 20 req/min
            bucketKey = clientIp + ":financial";
            bucket = buckets.computeIfAbsent(bucketKey, k -> createBucket(20, 1));
        } else {
            // General API: 60 req/min
            bucketKey = clientIp + ":general";
            bucket = buckets.computeIfAbsent(bucketKey, k -> createBucket(60, 1));
        }

        if (bucket.tryConsume(1)) {
            // Add rate limit headers
            response.addHeader("X-Rate-Limit-Remaining",
                    String.valueOf(bucket.getAvailableTokens()));
            return true;
        } else {
            log.warn("Rate limit exceeded for IP: {} on path: {}", clientIp, path);
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("""
                    {"timestamp":"%s","status":429,"error":"Too Many Requests","message":"Trop de requêtes. Veuillez r��essayer dans quelques instants."}
                    """.formatted(java.time.LocalDateTime.now()));
            return false;
        }
    }

    /**
     * Create a token bucket with the given capacity and refill rate.
     *
     * @param capacityPerMinute max tokens (requests) per minute
     * @param refillMinutes     refill period in minutes
     */
    private Bucket createBucket(int capacityPerMinute, int refillMinutes) {
        Bandwidth limit = Bandwidth.builder()
                .capacity(capacityPerMinute)
                .refillGreedy(capacityPerMinute, Duration.ofMinutes(refillMinutes))
                .build();
        return Bucket.builder().addLimit(limit).build();
    }

    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty()) {
            return xRealIp;
        }
        return request.getRemoteAddr();
    }
}

