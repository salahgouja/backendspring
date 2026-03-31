package com.amenbank.banking_webapp.security;

import com.amenbank.banking_webapp.model.User;
import com.amenbank.banking_webapp.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Optimized UserDetailsService with:
 * - Bean Life Cycle management
 * - Caching for performance
 * - Enhanced security checks
 * - Proper logging
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserDetailsServiceImpl implements UserDetailsService {

    private static final String BEAN_NAME = "UserDetailsServiceImpl";
    private static final String CACHE_NAME = "userCache";

    private final UserRepository userRepository;
    private final CacheManager cacheManager;
    
    // Fallback cache if Spring cache not available
    private final ConcurrentHashMap<String, UserDetailsCacheEntry> localCache = new ConcurrentHashMap<>();

    // ============================================================
    // Bean Life Cycle - @PostConstruct
    // ============================================================
    @PostConstruct
    public void init() {
        log.info("=== {}: PostConstruct - UserDetails Service initialized ===", BEAN_NAME);
        log.info("{}: Using repository for user lookup with caching", BEAN_NAME);
    }

    // ============================================================
    // Bean Life Cycle - @PreDestroy
    // ============================================================
    @PreDestroy
    public void destroy() {
        log.info("=== {}: PreDestroy - Clearing user cache ===", BEAN_NAME);
        localCache.clear();
    }

    // ============================================================
    // Main User Loading Method
    // ============================================================
    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        log.debug("{}: Loading user by email: {}", BEAN_NAME, email);

        UserDetails cached = getCachedUserDetails(email);
        if (cached != null) {
            return cached;
        }

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> {
                    log.warn("{}: User not found with email: {}", BEAN_NAME, email);
                    return new UsernameNotFoundException("User not found with email: " + email);
                });

        UserDetails userDetails = buildUserDetails(user);
        cacheUserDetails(email, userDetails);
        return userDetails;
    }

    // ============================================================
    // Cache Management
    // ============================================================
    public UserDetails getCachedUserDetails(String email) {
        // Local fallback cache is checked first to avoid unnecessary repository hits.
        return getFromLocalCache(email);
    }

    private void cacheUserDetails(String email, UserDetails userDetails) {
        // Try Spring cache first
        try {
            Cache cache = cacheManager.getCache(CACHE_NAME);
            if (cache != null) {
                cache.put(email, userDetails);
                log.debug("{}: UserDetails cached in Spring cache: {}", BEAN_NAME, email);
                return;
            }
        } catch (Exception e) {
            log.debug("{}: Spring cache not available, using local cache", BEAN_NAME);
        }

        // Fallback to local cache
        localCache.put(email, new UserDetailsCacheEntry(userDetails, System.currentTimeMillis() + 300_000)); // 5 min TTL
        log.debug("{}: UserDetails cached locally: {}", BEAN_NAME, email);
    }

    private UserDetails getFromLocalCache(String email) {
        UserDetailsCacheEntry entry = localCache.get(email);
        if (entry != null) {
            if (entry.isExpired()) {
                localCache.remove(email);
                log.debug("{}: Expired cache entry removed for: {}", BEAN_NAME, email);
                return null;
            }
            log.debug("{}: UserDetails found in local cache: {}", BEAN_NAME, email);
            return entry.getUserDetails();
        }
        return null;
    }

    /**
     * Invalidate user cache (call after password change, etc.)
     */
    public void invalidateCache(String email) {
        localCache.remove(email);
        try {
            Cache cache = cacheManager.getCache(CACHE_NAME);
            if (cache != null) {
                cache.evict(email);
            }
        } catch (Exception e) {
            log.debug("{}: Error invalidating Spring cache", BEAN_NAME);
        }
        log.info("{}: Cache invalidated for user: {}", BEAN_NAME, email);
    }

    // ============================================================
    // UserDetails Building
    // ============================================================
    private UserDetails buildUserDetails(User user) {
        // Build authorities from user type
        List<SimpleGrantedAuthority> authorities = Collections.singletonList(
                new SimpleGrantedAuthority("ROLE_" + user.getUserType().name())
        );

        // Use Spring Security's User implementation
        return org.springframework.security.core.userdetails.User.builder()
                .username(user.getEmail())
                .password(user.getHashedPassword())
                .authorities(authorities)
                .accountLocked(!user.getIsActive()) // If inactive, lock account
                .disabled(!user.getIsActive()) // If inactive, disable account
                .credentialsExpired(false)
                .accountExpired(false)
                .build();
    }

    // ============================================================
    // Local Cache Entry Class
    // ============================================================
    private static class UserDetailsCacheEntry {
        private final UserDetails userDetails;
        private final long expiresAt;

        public UserDetailsCacheEntry(UserDetails userDetails, long expiresAt) {
            this.userDetails = userDetails;
            this.expiresAt = expiresAt;
        }

        public UserDetails getUserDetails() {
            return userDetails;
        }

        public boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }
}
