package com.amenbank.banking_webapp.service;

import com.amenbank.banking_webapp.dto.request.ChangePasswordRequest;
import com.amenbank.banking_webapp.dto.request.LoginRequest;
import com.amenbank.banking_webapp.dto.request.RegisterRequest;
import com.amenbank.banking_webapp.dto.request.UpdateProfileRequest;
import com.amenbank.banking_webapp.dto.response.AuthResponse;
import com.amenbank.banking_webapp.dto.response.UserProfileResponse;
import com.amenbank.banking_webapp.exception.BankingException;
import com.amenbank.banking_webapp.model.*;
import com.amenbank.banking_webapp.repository.AccountRepository;
import com.amenbank.banking_webapp.repository.AgencyRepository;
import com.amenbank.banking_webapp.repository.NotificationRepository;
import com.amenbank.banking_webapp.repository.UserRepository;
import com.amenbank.banking_webapp.security.JwtTokenProvider;
import com.amenbank.banking_webapp.security.UserDetailsServiceImpl;
import dev.samstevens.totp.code.*;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private static final String BEAN_NAME = "AuthService";
    private static final Pattern PASSWORD_PATTERN = Pattern.compile(
            "^(?=.*[0-9])(?=.*[a-z])(?=.*[A-Z])(?=.*[@#$%^&+=])(?=\\S+$).{8,}$");
    private static final String ACCOUNT_PREFIX = "AMEN";
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final int LOCK_DURATION_MINUTES = 30;

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final AgencyRepository agencyRepository;
    private final NotificationRepository notificationRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final AuthenticationManager authenticationManager;
    private final AuditService auditService;
    private final UserDetailsServiceImpl userDetailsService;

    @Value("${app.security.min-password-length:8}")
    private int minPasswordLength;

    @PostConstruct
    public void init() {
        log.info("=== {}: PostConstruct - Auth Service initialized ===", BEAN_NAME);
    }

    @PreDestroy
    public void destroy() {
        log.info("=== {}: PreDestroy - Auth Service cleanup ===", BEAN_NAME);
    }

    // ============================================================
    // Registration
    // ============================================================
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        log.info("{}: Registration attempt for email: {}", BEAN_NAME, request.getEmail());

        validateUserType(request.getUserType());
        validatePassword(request.getPassword());
        checkExistingUser(request.getEmail(), request.getCin(), request.getPhone());

        Agency agency = resolveAgency(request);
        User user = createUser(request, agency);
        Account defaultAccount = createDefaultAccount(user);
        notifyAgencyAgents(user, agency, defaultAccount);

        String accessToken = jwtTokenProvider.generateToken(user.getEmail());
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getEmail());

        auditService.log(AuditLog.AuditAction.USER_REGISTERED, user.getEmail(),
                "User", user.getId().toString(), "New registration: " + user.getUserType());

        log.info("{}: User registered successfully: {}", BEAN_NAME, user.getEmail());
        return buildAuthResponse(user, accessToken, refreshToken);
    }

    // ============================================================
    // Login with Brute-Force Protection (fix #4)
    // ============================================================
    @Transactional
    public AuthResponse login(LoginRequest request) {
        log.info("{}: Login attempt for email: {}", BEAN_NAME, request.getEmail());

        // Check brute-force lockout before authentication
        userRepository.findByEmail(request.getEmail()).ifPresent(this::checkAccountLock);

        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            request.getEmail(), request.getPassword()));

            User user = userRepository.findByEmail(request.getEmail())
                    .orElseThrow(() -> new BankingException.NotFoundException("User not found"));

            validateUserStatus(user);

            // Reset failed attempts on successful login
            if (user.getFailedLoginAttempts() > 0) {
                user.setFailedLoginAttempts(0);
                user.setLockUntil(null);
                userRepository.save(user);
            }

            // If 2FA is enabled, return partial response requiring verification (fix #1)
            if (Boolean.TRUE.equals(user.getIs2faEnabled())) {
                String tempToken = jwtTokenProvider.generateTempToken(user.getEmail());
                auditService.log(AuditLog.AuditAction.USER_LOGIN, user.getEmail(),
                        "User", user.getId().toString(), "Login successful — awaiting 2FA");
                return AuthResponse.builder()
                        .tokenType("Bearer")
                        .userId(user.getId())
                        .email(user.getEmail())
                        .fullName(user.getFullNameFr())
                        .userType(user.getUserType())
                        .is2faEnabled(true)
                        .requires2fa(true)
                        .accessToken(tempToken)
                        .build();
            }

            String accessToken = jwtTokenProvider.generateToken(authentication);
            String refreshToken = jwtTokenProvider.generateRefreshToken(user.getEmail());

            auditService.log(AuditLog.AuditAction.USER_LOGIN, user.getEmail(),
                    "User", user.getId().toString(), "Login successful");

            return buildAuthResponse(user, accessToken, refreshToken);

        } catch (BadCredentialsException e) {
            handleFailedLogin(request.getEmail());
            throw new BadCredentialsException("Email ou mot de passe incorrect");
        } catch (DisabledException e) {
            throw new DisabledException("Compte désactivé. Veuillez contacter le support.");
        } catch (LockedException e) {
            throw new LockedException("Compte verrouillé. Veuillez contacter le support.");
        }
    }

    // ============================================================
    // 2FA — Enable (fix #1)
    // ============================================================
    @Transactional
    public Map<String, String> enable2fa(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BankingException.NotFoundException("Utilisateur introuvable"));

        if (Boolean.TRUE.equals(user.getIs2faEnabled())) {
            throw new BankingException("2FA est déjà activé");
        }

        SecretGenerator secretGenerator = new DefaultSecretGenerator();
        String secret = secretGenerator.generate();
        user.setTotpSecret(secret);
        userRepository.save(user);

        String otpAuthUrl = String.format(
                "otpauth://totp/AmenBank:%s?secret=%s&issuer=AmenBank",
                email, secret);

        return Map.of("secret", secret, "otpAuthUrl", otpAuthUrl);
    }

    // ============================================================
    // 2FA — Confirm Enable (verify first code then enable)
    // ============================================================
    @Transactional
    public Map<String, String> confirmEnable2fa(String email, String code) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BankingException.NotFoundException("Utilisateur introuvable"));

        if (user.getTotpSecret() == null) {
            throw new BankingException("Veuillez d'abord appeler enable-2fa pour générer le secret");
        }

        if (!verifyTotpCode(user.getTotpSecret(), code)) {
            throw new BankingException("Code 2FA invalide");
        }

        user.setIs2faEnabled(true);
        userRepository.save(user);
        userDetailsService.invalidateCache(email);

        auditService.log(AuditLog.AuditAction.TWO_FA_ENABLED, email,
                "User", user.getId().toString(), "2FA enabled");

        return Map.of("message", "2FA activé avec succès");
    }

    // ============================================================
    // 2FA — Verify on Login (fix #1)
    // ============================================================
    @Transactional
    public AuthResponse verify2fa(String tempToken, String code) {
        if (!jwtTokenProvider.validateToken(tempToken)) {
            throw new BankingException("Token temporaire invalide ou expiré");
        }

        String tokenType = jwtTokenProvider.getTokenType(tempToken);
        if (!"temp".equals(tokenType)) {
            throw new BankingException("Token invalide — utilisez le token temporaire de login");
        }

        String email = jwtTokenProvider.getEmailFromToken(tempToken);
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BankingException.NotFoundException("Utilisateur introuvable"));

        if (!verifyTotpCode(user.getTotpSecret(), code)) {
            auditService.log(AuditLog.AuditAction.USER_LOGIN_FAILED, email,
                    "User", user.getId().toString(), "Invalid 2FA code");
            throw new BankingException("Code 2FA invalide");
        }

        // Blacklist the temp token after use
        jwtTokenProvider.blacklistToken(tempToken);

        String accessToken = jwtTokenProvider.generateToken(email);
        String refreshToken = jwtTokenProvider.generateRefreshToken(email);

        auditService.log(AuditLog.AuditAction.USER_LOGIN, email,
                "User", user.getId().toString(), "2FA verified — full login granted");

        return buildAuthResponse(user, accessToken, refreshToken);
    }

    // ============================================================
    // 2FA — Disable
    // ============================================================
    @Transactional
    public Map<String, String> disable2fa(String email, String code) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BankingException.NotFoundException("Utilisateur introuvable"));

        if (!Boolean.TRUE.equals(user.getIs2faEnabled())) {
            throw new BankingException("2FA n'est pas activé");
        }

        if (!verifyTotpCode(user.getTotpSecret(), code)) {
            throw new BankingException("Code 2FA invalide");
        }

        user.setIs2faEnabled(false);
        user.setTotpSecret(null);
        userRepository.save(user);
        userDetailsService.invalidateCache(email);

        auditService.log(AuditLog.AuditAction.TWO_FA_DISABLED, email,
                "User", user.getId().toString(), "2FA disabled");

        return Map.of("message", "2FA désactivé avec succès");
    }

    // ============================================================
    // Logout (fix #2)
    // ============================================================
    public Map<String, String> logout(String token) {
        if (token != null && token.startsWith("Bearer ")) {
            token = token.substring(7);
        }
        if (token != null) {
            String email = jwtTokenProvider.getEmailFromToken(token);
            jwtTokenProvider.blacklistToken(token);
            userDetailsService.invalidateCache(email);
            auditService.log(AuditLog.AuditAction.USER_LOGOUT, email,
                    "User", "", "User logged out");
        }
        return Map.of("message", "Déconnexion réussie");
    }

    // ============================================================
    // Token Refresh
    // ============================================================
    public AuthResponse refreshToken(String refreshToken) {
        if (!jwtTokenProvider.validateToken(refreshToken)) {
            throw new BankingException("Refresh token invalide ou expiré");
        }
        String tokenType = jwtTokenProvider.getTokenType(refreshToken);
        if (!"refresh".equals(tokenType)) {
            throw new BankingException("Token invalide — expected refresh token");
        }
        String email = jwtTokenProvider.getEmailFromToken(refreshToken);
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BankingException.NotFoundException("Utilisateur introuvable"));
        validateUserStatus(user);

        String newAccessToken = jwtTokenProvider.generateToken(email);
        String newRefreshToken = jwtTokenProvider.generateRefreshToken(email);
        return buildAuthResponse(user, newAccessToken, newRefreshToken);
    }

    // ============================================================
    // Change Password (fix #11)
    // ============================================================
    @Transactional
    public Map<String, String> changePassword(String email, ChangePasswordRequest request) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BankingException.NotFoundException("Utilisateur introuvable"));

        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getHashedPassword())) {
            throw new BankingException("Mot de passe actuel incorrect");
        }

        validatePassword(request.getNewPassword());

        if (passwordEncoder.matches(request.getNewPassword(), user.getHashedPassword())) {
            throw new BankingException("Le nouveau mot de passe doit être différent de l'ancien");
        }

        user.setHashedPassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);
        userDetailsService.invalidateCache(email);

        auditService.log(AuditLog.AuditAction.PASSWORD_CHANGED, email,
                "User", user.getId().toString(), "Password changed");

        notificationRepository.save(Notification.builder()
                .user(user)
                .type(Notification.NotificationType.SECURITY)
                .title("Mot de passe modifié")
                .body("Votre mot de passe a été modifié avec succès. Si ce n'est pas vous, contactez votre agence immédiatement.")
                .build());

        return Map.of("message", "Mot de passe modifié avec succès");
    }

    // ============================================================
    // Update Profile (fix #33)
    // ============================================================
    @Transactional
    public UserProfileResponse updateProfile(String email, UpdateProfileRequest request) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BankingException.NotFoundException("Utilisateur introuvable"));

        if (request.getPhone() != null && !request.getPhone().isBlank()) {
            if (!request.getPhone().equals(user.getPhone()) && userRepository.existsByPhone(request.getPhone())) {
                throw new BankingException("Ce numéro de téléphone est déjà utilisé");
            }
            user.setPhone(sanitize(request.getPhone()));
        }
        if (request.getFullNameAr() != null && !request.getFullNameAr().isBlank()) {
            user.setFullNameAr(sanitize(request.getFullNameAr()));
        }
        if (request.getFullNameFr() != null && !request.getFullNameFr().isBlank()) {
            user.setFullNameFr(sanitize(request.getFullNameFr()));
        }

        userRepository.save(user);
        userDetailsService.invalidateCache(email);
        return toProfileResponse(user);
    }

    // ============================================================
    // Get Profile
    // ============================================================
    @Transactional(readOnly = true)
    public UserProfileResponse getProfile(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BankingException.NotFoundException("Utilisateur introuvable"));
        return toProfileResponse(user);
    }

    // ============================================================
    // Private — Brute-Force Protection (fix #4)
    // ============================================================
    private void checkAccountLock(User user) {
        if (user.getLockUntil() != null && user.getLockUntil().isAfter(LocalDateTime.now())) {
            long minutesLeft = java.time.Duration.between(LocalDateTime.now(), user.getLockUntil()).toMinutes();
            throw new LockedException(
                    String.format("Compte verrouillé suite à trop de tentatives. Réessayez dans %d minutes.", minutesLeft + 1));
        }
    }
    //@Transactional (making error )
    private void handleFailedLogin(String email) {
        userRepository.findByEmail(email).ifPresent(user -> {
            int attempts = user.getFailedLoginAttempts() + 1;
            user.setFailedLoginAttempts(attempts);
            if (attempts >= MAX_FAILED_ATTEMPTS) {
                user.setLockUntil(LocalDateTime.now().plusMinutes(LOCK_DURATION_MINUTES));
                log.warn("{}: Account locked for {} after {} failed attempts", BEAN_NAME, email, attempts);
            }
            userRepository.save(user);
            auditService.log(AuditLog.AuditAction.USER_LOGIN_FAILED, email,
                    "User", user.getId().toString(),
                    "Failed login attempt #" + attempts);
        });
    }

    // ============================================================
    // Private — TOTP Verification
    // ============================================================
    private boolean verifyTotpCode(String secret, String code) {
        CodeVerifier verifier = new DefaultCodeVerifier(
                new DefaultCodeGenerator(), new SystemTimeProvider());
        return verifier.isValidCode(secret, code);
    }

    // ============================================================
    // Private Validation Methods
    // ============================================================
    private void validateUserType(User.UserType userType) {
        if (userType == User.UserType.ADMIN || userType == User.UserType.AGENT) {
            throw new BankingException("Cannot self-register as ADMIN or AGENT. Contact an administrator.");
        }
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < minPasswordLength) {
            throw new BankingException(
                    String.format("Le mot de passe doit contenir au moins %d caractères", minPasswordLength));
        }
        if (!PASSWORD_PATTERN.matcher(password).matches()) {
            throw new BankingException(
                    "Le mot de passe doit contenir au moins: une majuscule, une minuscule, un chiffre et un caractère spécial");
        }
    }

    private void checkExistingUser(String email, String cin, String phone) {
        if (userRepository.existsByEmail(email)) throw new BankingException("Email déjà enregistré");
        if (userRepository.existsByCin(cin)) throw new BankingException("CIN déjà enregistré");
        if (userRepository.existsByPhone(phone)) throw new BankingException("Téléphone déjà enregistré");
    }

    private void validateUserStatus(User user) {
        if (!user.getIsActive()) {
            throw new DisabledException("Compte désactivé");
        }
    }

    // ============================================================
    // Private Creation Methods
    // ============================================================
    private Agency resolveAgency(RegisterRequest request) {
        if (request.getUserType() == User.UserType.PARTICULIER
                || request.getUserType() == User.UserType.COMMERCANT) {
            if (request.getAgencyId() == null) {
                throw new BankingException("L'agence est obligatoire pour les clients et commerçants");
            }
            return agencyRepository.findById(request.getAgencyId())
                    .orElseThrow(() -> new BankingException.NotFoundException("Agence introuvable"));
        }
        return null;
    }

    private User createUser(RegisterRequest request, Agency agency) {
        User user = User.builder()
                .email(sanitize(request.getEmail()))
                .phone(sanitize(request.getPhone()))
                .hashedPassword(passwordEncoder.encode(request.getPassword()))
                .fullNameAr(sanitize(request.getFullNameAr()))
                .fullNameFr(sanitize(request.getFullNameFr()))
                .cin(sanitize(request.getCin()))
                .userType(request.getUserType())
                .agency(agency)
                .isActive(true)
                .is2faEnabled(false)
                .failedLoginAttempts(0)
                .build();
        return userRepository.save(user);
    }

    private Account createDefaultAccount(User user) {
        Account defaultAccount = Account.builder()
                .user(user)
                .accountNumber(generateAccountNumber())
                .accountType(user.getUserType() == User.UserType.COMMERCANT
                        ? Account.AccountType.COMMERCIAL
                        : Account.AccountType.COURANT)
                .balance(BigDecimal.ZERO)
                .isActive(false)
                .status(Account.AccountStatus.PENDING_APPROVAL)
                .currency("TND")
                .build();
        return accountRepository.save(defaultAccount);
    }

    private void notifyAgencyAgents(User newUser, Agency agency, Account account) {
        if (agency == null) return;
        List<User> agents = userRepository.findByUserTypeAndAgency(User.UserType.AGENT, agency);
        for (User agent : agents) {
            notificationRepository.save(Notification.builder()
                    .user(agent)
                    .type(Notification.NotificationType.ACCOUNT)
                    .title("Nouvelle demande d'ouverture de compte")
                    .body(String.format("%s (%s) a demandé l'ouverture d'un compte %s à l'agence %s.",
                            newUser.getFullNameFr(), newUser.getCin(),
                            account.getAccountType(), agency.getBranchName()))
                    .build());
        }
    }

    private synchronized String generateAccountNumber() {
        String accountNumber;
        int attempts = 0;
        do {
            accountNumber = ACCOUNT_PREFIX + String.format("%012d",
                    Math.abs(UUID.randomUUID().getLeastSignificantBits() % 1_000_000_000_000L));
            attempts++;
        } while (accountRepository.findByAccountNumber(accountNumber).isPresent() && attempts < 10);
        if (attempts >= 10) {
            throw new BankingException("Erreur lors de la génération du numéro de compte");
        }
        return accountNumber;
    }

    /** Sanitize input to prevent XSS (fix #32) */
    private String sanitize(String input) {
        if (input == null) return null;
        return input.replaceAll("[<>\"'&]", "");
    }

    private AuthResponse buildAuthResponse(User user, String accessToken, String refreshToken) {
        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .userId(user.getId())
                .email(user.getEmail())
                .fullName(user.getFullNameFr())
                .userType(user.getUserType())
                .is2faEnabled(user.getIs2faEnabled())
                .requires2fa(false)
                .build();
    }

    private UserProfileResponse toProfileResponse(User user) {
        return UserProfileResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .phone(user.getPhone())
                .fullNameAr(user.getFullNameAr())
                .fullNameFr(user.getFullNameFr())
                .cin(user.getCin())
                .userType(user.getUserType())
                .isActive(user.getIsActive())
                .is2faEnabled(user.getIs2faEnabled())
                .agencyName(user.getAgency() != null ? user.getAgency().getBranchName() : null)
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }
}
