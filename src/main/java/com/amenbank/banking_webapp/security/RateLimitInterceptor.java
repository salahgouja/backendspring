package com.amenbank.banking_webapp.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GAP-19: API Rate Limiting interceptor using Bucket4j (token bucket algorithm).
 * SEC-9 fix: Buckets are now evicted after 5 minutes of inactivity to prevent OOM.
 * Limits:
 *   - General API: 60 requests/minute per IP
 *   - Auth endpoints: 10 requests/minute per IP (brute-force protection)
 *   - Transfer endpoints: 20 requests/minute per IP
 */
@Component
@Slf4j
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final int MAX_BUCKETS = 10_000;
    private static final long EVICTION_TTL_MILLIS = 5 * 60 * 1000; // 5 minutes

    // Bucket + last access time for eviction
    private record BucketEntry(Bucket bucket, long lastAccessMillis) {}

    private final Map<String, BucketEntry> buckets = new ConcurrentHashMap<>();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String clientIp = getClientIp(request);
        String path = request.getRequestURI();

        // SEC-9: Hard limit on bucket count to prevent OOM under DDoS
        if (buckets.size() >= MAX_BUCKETS) {
            evictStaleBuckets();
        }

        // Determine rate limit tier
        String bucketKey;
        Bucket bucket;

        if (path.contains("/auth/login") || path.contains("/auth/register")
                || path.contains("/auth/forgot-password") || path.contains("/auth/reset-password")) {
            bucketKey = clientIp + ":auth";
            bucket = getOrCreateBucket(bucketKey, 10, 1);
        } else if (path.contains("/transfers") || path.contains("/credits/submit")) {
            bucketKey = clientIp + ":financial";
            bucket = getOrCreateBucket(bucketKey, 20, 1);
        } else {
            bucketKey = clientIp + ":general";
            bucket = getOrCreateBucket(bucketKey, 60, 1);
        }

        if (bucket.tryConsume(1)) {
            response.addHeader("X-Rate-Limit-Remaining",
                    String.valueOf(bucket.getAvailableTokens()));
            return true;
        } else {
            log.warn("Rate limit exceeded for IP: {} on path: {}", clientIp, path);
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("""
                    {"timestamp":"%s","status":429,"error":"Too Many Requests","message":"Trop de requêtes. Veuillez réessayer dans quelques instants."}
                    """.formatted(java.time.LocalDateTime.now()));
            return false;
        }
    }

    private Bucket getOrCreateBucket(String key, int capacityPerMinute, int refillMinutes) {
        long now = System.currentTimeMillis();
        BucketEntry entry = buckets.compute(key, (k, existing) -> {
            if (existing == null) {
                return new BucketEntry(createBucket(capacityPerMinute, refillMinutes), now);
            }
            return new BucketEntry(existing.bucket(), now); // update last access
        });
        return entry.bucket();
    }

    /**
     * SEC-9: Evict stale buckets every 5 minutes to prevent memory leak.
     */
    @Scheduled(fixedRate = 300_000) // every 5 minutes
    public void evictStaleBuckets() {
        long now = System.currentTimeMillis();
        int before = buckets.size();
        buckets.entrySet().removeIf(e -> (now - e.getValue().lastAccessMillis()) > EVICTION_TTL_MILLIS);
        int removed = before - buckets.size();
        if (removed > 0) {
            log.debug("Rate limiter: evicted {} stale buckets, {} remaining", removed, buckets.size());
        }
    }

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

