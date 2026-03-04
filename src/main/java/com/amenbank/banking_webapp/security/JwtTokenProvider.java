package com.amenbank.banking_webapp.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Optimized JWT Token Provider with:
 * - Bean Life Cycle management (@PostConstruct/@PreDestroy)
 * - Parser caching for performance
 * - Token blacklist for logout functionality
 * - Automatic cleanup of expired tokens
 * - Proper logging and error handling
 */
@Component
@Slf4j
public class JwtTokenProvider {

    // ============================================================
    // Bean Life Cycle - Constants
    // ============================================================
    private static final String BEAN_NAME = "JwtTokenProvider";
    private static final int PARSER_CACHE_SIZE = 100;
    private static final long CLEANUP_INTERVAL_MINUTES = 5;

    // ============================================================
    // Configuration Properties
    // ============================================================
    private final String secret;
    private final long jwtExpiration;
    private final long refreshExpiration;

    // ============================================================
    // Cached Components (initialized in @PostConstruct)
    // ============================================================
    private SecretKey key;
    private JwtParser cachedParser; // Fix #27: cache parser instance

    // ============================================================
    // Token Blacklist for Logout
    // ============================================================
    private final ConcurrentHashMap<String, Long> tokenBlacklist = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanupExecutor = Executors.newSingleThreadScheduledExecutor();

    // ============================================================
    // Constructor Injection (Best Practice)
    // ============================================================
    public JwtTokenProvider(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.expiration}") long jwtExpiration,
            @Value("${jwt.refresh-expiration}") long refreshExpiration) {
        this.secret = secret;
        this.jwtExpiration = jwtExpiration;
        this.refreshExpiration = refreshExpiration;
        log.info("{}: Initialized with JWT expiration={}ms, Refresh expiration={}ms",
                BEAN_NAME, jwtExpiration, refreshExpiration);
    }

    // ============================================================
    // Bean Life Cycle - @PostConstruct
    // ============================================================
    @PostConstruct
    public void init() {
        log.info("=== {}: PostConstruct - Initializing JWT components ===", BEAN_NAME);
        
        // Initialize secret key once
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        
        // Fix #27: Cache parser instance
        this.cachedParser = Jwts.parser().verifyWith(key).build();

        // Start background cleanup thread
        cleanupExecutor.scheduleAtFixedRate(
                this::cleanupBlacklist,
                CLEANUP_INTERVAL_MINUTES,
                CLEANUP_INTERVAL_MINUTES,
                TimeUnit.MINUTES
        );
        
        log.info("{}: PostConstruct completed - Parser cache ready, cleanup scheduled", BEAN_NAME);
    }

    // ============================================================
    // Bean Life Cycle - @PreDestroy
    // ============================================================
    @PreDestroy
    public void destroy() {
        log.info("=== {}: PreDestroy - Cleaning up resources ===", BEAN_NAME);
        
        // Shutdown executor gracefully
        cleanupExecutor.shutdown();
        try {
            if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanupExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cleanupExecutor.shutdownNow();
        }
        
        // Clear blacklist
        tokenBlacklist.clear();
        
        log.info("{}: PreDestroy completed - All resources cleaned", BEAN_NAME);
    }

    // ============================================================
    // Token Generation Methods
    // ============================================================

    /**
     * Generate JWT token from Spring Security Authentication
     * Uses builder pattern for clean code
     */
    public String generateToken(Authentication authentication) {
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        return buildToken(userDetails.getUsername(), jwtExpiration, "access");
    }

    /**
     * Generate JWT token from email directly
     */
    public String generateToken(String email) {
        return buildToken(email, jwtExpiration, "access");
    }

    /**
     * Generate refresh token for token renewal
     */
    public String generateRefreshToken(String email) {
        return buildToken(email, refreshExpiration, "refresh");
    }

    /**
     * Generate short-lived temp token for 2FA verification (fix #1).
     * This token is NOT a full access token — only valid for /auth/verify-2fa.
     */
    public String generateTempToken(String email) {
        return buildToken(email, 300_000L, "temp"); // 5 minutes
    }

    // ============================================================
    // Private Token Building with Builder Pattern
    // ============================================================
    private String buildToken(String subject, long expiration, String type) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expiration);

        String token = Jwts.builder()
                .subject(subject)
                .claim("type", type)
                .issuedAt(now)
                .expiration(expiryDate)
                .id(generateTokenId())
                .signWith(key)
                .compact();

        log.debug("{}: Generated {} token for user: {}", BEAN_NAME, type, subject);
        return token;
    }

    private String generateTokenId() {
        return java.util.UUID.randomUUID().toString();
    }

    // ============================================================
    // Token Validation with Caching
    // ============================================================

    /**
     * Validate JWT token with comprehensive checks
     */
    public boolean validateToken(String token) {
        try {
            // Check blacklist first (O(1) lookup)
            if (isTokenBlacklisted(token)) {
                log.warn("{}: Token is blacklisted", BEAN_NAME);
                return false;
            }

            // Parse and validate using cached parser
            getParser().parseSignedClaims(token);
            return true;
            
        } catch (ExpiredJwtException e) {
            log.warn("{}: Token expired - {}", BEAN_NAME, e.getMessage());
            return false;
        } catch (UnsupportedJwtException e) {
            log.warn("{}: Unsupported token - {}", BEAN_NAME, e.getMessage());
            return false;
        } catch (MalformedJwtException e) {
            log.warn("{}: Malformed token - {}", BEAN_NAME, e.getMessage());
            return false;
        } catch (SecurityException e) {
            log.warn("{}: Token signature invalid - {}", BEAN_NAME, e.getMessage());
            return false;
        } catch (IllegalArgumentException e) {
            log.warn("{}: Token is empty or null - {}", BEAN_NAME, e.getMessage());
            return false;
        }
    }

    /**
     * Extract email from JWT token
     */
    public String getEmailFromToken(String token) {
        return getClaimsFromToken(token).getSubject();
    }

    /**
     * Get token type (access or refresh)
     */
    public String getTokenType(String token) {
        return getClaimsFromToken(token).get("type", String.class);
    }

    /**
     * Check if token is expired
     */
    public boolean isTokenExpired(String token) {
        try {
            return getClaimsFromToken(token).getExpiration().before(new Date());
        } catch (ExpiredJwtException e) {
            return true;
        }
    }

    // ============================================================
    // Token Blacklist Management
    // ============================================================

    /**
     * Add token to blacklist (for logout)
     */
    public void blacklistToken(String token) {
        try {
            var claims = getClaimsFromToken(token);
            long expirationTime = claims.getExpiration().getTime();
            long ttl = expirationTime - System.currentTimeMillis();
            
            if (ttl > 0) {
                tokenBlacklist.put(token, expirationTime);
                log.info("{}: Token blacklisted, TTL={}ms", BEAN_NAME, ttl);
            }
        } catch (Exception e) {
            log.warn("{}: Failed to blacklist token - {}", BEAN_NAME, e.getMessage());
        }
    }

    private boolean isTokenBlacklisted(String token) {
        Long expiration = tokenBlacklist.get(token);
        if (expiration == null) {
            return false;
        }
        
        // Remove if expired
        if (expiration < System.currentTimeMillis()) {
            tokenBlacklist.remove(token);
            return false;
        }
        return true;
    }

    private void cleanupBlacklist() {
        long now = System.currentTimeMillis();
        int beforeSize = tokenBlacklist.size();
        
        tokenBlacklist.entrySet().removeIf(entry -> entry.getValue() < now);
        
        int removed = beforeSize - tokenBlacklist.size();
        if (removed > 0) {
            log.debug("{}: Cleanup removed {} expired tokens from blacklist", BEAN_NAME, removed);
        }
    }

    // ============================================================
    // Private Helper Methods
    // ============================================================

    /**
     * Get configured JWT parser (cached - fix #27)
     */
    private JwtParser getParser() {
        return cachedParser;
    }

    /**
     * Extract claims from token (uses cached parser - fix #27)
     */
    private Claims getClaimsFromToken(String token) {
        return cachedParser.parseSignedClaims(token).getPayload();
    }

    // ============================================================
    // Getters for configuration values
    // ============================================================
    public long getJwtExpiration() {
        return jwtExpiration;
    }

    public long getRefreshExpiration() {
        return refreshExpiration;
    }
}
