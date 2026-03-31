package com.amenbank.banking_webapp.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Optimized JWT Authentication Filter with:
 * - Bean Life Cycle callbacks
 * - Enhanced logging
 * - Token blacklist support for logout
 * - Performance monitoring
 * - Security enhancements
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String BEAN_NAME = "JwtAuthFilter";
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;
    private final UserDetailsService userDetailsService;

    // ============================================================
    // Bean Life Cycle - Post-Init (via @PostConstruct equivalent)
    // ============================================================
    @jakarta.annotation.PostConstruct
    public void init() {
        log.info("=== {}: PostConstruct - JWT Authentication Filter initialized ===", BEAN_NAME);
    }

    // ============================================================
    // Main Filter Logic
    // ============================================================
    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        long startTime = System.currentTimeMillis();
        String requestUri = request.getRequestURI();

        try {
            // Skip if no auth header
            String token = extractTokenFromRequest(request);
            if (!StringUtils.hasText(token)) {
                filterChain.doFilter(request, response);
                return;
            }

            // Validate token
            if (!jwtTokenProvider.validateToken(token)) {
                log.debug("{}: Invalid or expired token for URI: {}", BEAN_NAME, requestUri);
                filterChain.doFilter(request, response);
                return;
            }

            // Extract email and load user
            String email = jwtTokenProvider.getEmailFromToken(token);
            
            // Security: Check if user already authenticated to avoid overwriting
            if (SecurityContextHolder.getContext().getAuthentication() == null || 
                !SecurityContextHolder.getContext().getAuthentication().isAuthenticated()) {
                
                UserDetails userDetails = userDetailsService.loadUserByUsername(email);

                // Create authentication token with authorities
                UsernamePasswordAuthenticationToken authentication = 
                        new UsernamePasswordAuthenticationToken(
                                userDetails, 
                                null, 
                                userDetails.getAuthorities());

                // Attach request details (IP, session, etc.)
                authentication.setDetails(
                        new WebAuthenticationDetailsSource().buildDetails(request));

                // Set authentication in security context
                SecurityContextHolder.getContext().setAuthentication(authentication);
                
                log.debug("{}: Successfully authenticated user: {} for URI: {}", 
                        BEAN_NAME, email, requestUri);
            }

        } catch (org.springframework.security.core.userdetails.UsernameNotFoundException e) {
            // User from JWT no longer exists (e.g. DB was recreated) — not an error, just skip auth
            log.debug("{}: JWT user not found (DB may have been recreated): {}", BEAN_NAME, e.getMessage());
        } catch (Exception e) {
            log.error("{}: Error processing JWT token for URI: {} - Error: {}", 
                    BEAN_NAME, requestUri, e.getMessage());
        } finally {
            long duration = System.currentTimeMillis() - startTime;
            if (duration > 100) {
                log.warn("{}: Slow request detected: {} took {}ms", BEAN_NAME, requestUri, duration);
            }
        }

        filterChain.doFilter(request, response);
    }

    // ============================================================
    // Token Extraction
    // ============================================================
    private String extractTokenFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader(AUTHORIZATION_HEADER);
        
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith(BEARER_PREFIX)) {
            return bearerToken.substring(BEARER_PREFIX.length());
        }
        
        // Fix #3: Removed query parameter token extraction — leaks tokens in logs/history
        return null;
    }

    // ============================================================
    // Determine which requests to filter
    // ============================================================
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Use getServletPath() which excludes the context-path (/api) — SEC-10 fix
        String path = request.getServletPath();

        // Skip public endpoints
        return path.equals("/auth/register") ||
               path.equals("/auth/login") ||
               path.equals("/auth/refresh") ||
               path.equals("/auth/2fa/verify") ||
               path.equals("/auth/forgot-password") ||
               path.equals("/auth/reset-password") ||
               path.startsWith("/agencies/") ||
               path.startsWith("/swagger-ui/") ||
               path.startsWith("/api-docs/") ||
               path.startsWith("/v3/api-docs") ||
               path.startsWith("/actuator/health");
    }

    // ============================================================
    // Bean Life Cycle - Pre-Destroy
    // ============================================================
    @jakarta.annotation.PreDestroy
    public void destroy() {
        log.info("=== {}: PreDestroy - Cleaning up JWT Filter resources ===", BEAN_NAME);
        // Clear security context
        SecurityContextHolder.clearContext();
    }
}
