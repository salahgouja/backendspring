package com.amenbank.banking_webapp.service;

import com.amenbank.banking_webapp.dto.request.LoginRequest;
import com.amenbank.banking_webapp.dto.request.RegisterRequest;
import com.amenbank.banking_webapp.dto.response.AuthResponse;
import com.amenbank.banking_webapp.exception.BankingException;
import com.amenbank.banking_webapp.model.Account;
import com.amenbank.banking_webapp.model.Agency;
import com.amenbank.banking_webapp.model.Notification;
import com.amenbank.banking_webapp.model.User;
import com.amenbank.banking_webapp.repository.AccountRepository;
import com.amenbank.banking_webapp.repository.AgencyRepository;
import com.amenbank.banking_webapp.repository.NotificationRepository;
import com.amenbank.banking_webapp.repository.UserRepository;
import com.amenbank.banking_webapp.security.JwtTokenProvider;
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
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Optimized Auth Service with:
 * - Bean Life Cycle management
 * - Password strength validation
 * - Enhanced security checks
 * - Proper input validation
 * - Account number generation optimization
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private static final String BEAN_NAME = "AuthService";

    // Password validation patterns
    private static final Pattern PASSWORD_PATTERN = Pattern.compile(
            "^(?=.*[0-9])(?=.*[a-z])(?=.*[A-Z])(?=.*[@#$%^&+=])(?=\\S+$).{8,}$");

    private static final int ACCOUNT_NUMBER_LENGTH = 16;
    private static final String ACCOUNT_PREFIX = "AMEN";

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final AgencyRepository agencyRepository;
    private final NotificationRepository notificationRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final AuthenticationManager authenticationManager;

    // Configuration values
    @Value("${app.security.min-password-length:8}")
    private int minPasswordLength;

    // ============================================================
    // Bean Life Cycle - @PostConstruct
    // ============================================================
    @PostConstruct
    public void init() {
        log.info("=== {}: PostConstruct - Auth Service initialized ===", BEAN_NAME);
        log.info("{}: Minimum password length: {}", BEAN_NAME, minPasswordLength);
    }

    // ============================================================
    // Bean Life Cycle - @PreDestroy
    // ============================================================
    @PreDestroy
    public void destroy() {
        log.info("=== {}: PreDestroy - Auth Service cleanup ===", BEAN_NAME);
    }

    // ============================================================
    // Registration with Enhanced Validation
    // ============================================================
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        log.info("{}: Registration attempt for email: {}", BEAN_NAME, request.getEmail());

        // 1. Validate user type - block privileged roles
        validateUserType(request.getUserType());

        // 2. Validate password strength
        validatePassword(request.getPassword());

        // 3. Check for existing user (with proper error messages)
        checkExistingUser(request.getEmail(), request.getCin(), request.getPhone());

        // 4. Validate and resolve agency (required for PARTICULIER/COMMERCANT)
        Agency agency = resolveAgency(request);

        // 5. Create user with encoded password + linked agency
        User user = createUser(request, agency);

        // 6. Create default account with PENDING_APPROVAL status
        Account defaultAccount = createDefaultAccount(user);

        // 7. Notify agents of this agency about the new account request
        notifyAgencyAgents(user, agency, defaultAccount);

        // 8. Generate tokens
        String accessToken = jwtTokenProvider.generateToken(user.getEmail());
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getEmail());

        log.info("{}: User registered successfully: {} (account PENDING_APPROVAL)", BEAN_NAME, user.getEmail());

        return buildAuthResponse(user, accessToken, refreshToken);
    }

    // ============================================================
    // Login with Enhanced Security
    // ============================================================
    public AuthResponse login(LoginRequest request) {
        log.info("{}: Login attempt for email: {}", BEAN_NAME, request.getEmail());

        try {
            // 1. Authenticate with Spring Security
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            request.getEmail(),
                            request.getPassword()));

            // 2. Fetch user details
            User user = userRepository.findByEmail(request.getEmail())
                    .orElseThrow(() -> new BankingException.NotFoundException("User not found"));

            // 3. Additional security checks
            validateUserStatus(user);

            // 4. Generate tokens
            String accessToken = jwtTokenProvider.generateToken(authentication);
            String refreshToken = jwtTokenProvider.generateRefreshToken(user.getEmail());

            log.info("{}: User logged in successfully: {}", BEAN_NAME, user.getEmail());

            return buildAuthResponse(user, accessToken, refreshToken);

        } catch (DisabledException e) {
            log.warn("{}: Login failed - account disabled: {}", BEAN_NAME, request.getEmail());
            throw new DisabledException("Compte désactivé. Veuillez contacter le support.");
        } catch (LockedException e) {
            log.warn("{}: Login failed - account locked: {}", BEAN_NAME, request.getEmail());
            throw new LockedException("Compte verrouillé. Veuillez contacter le support.");
        } catch (BadCredentialsException e) {
            log.warn("{}: Login failed - invalid credentials: {}", BEAN_NAME, request.getEmail());
            throw new BadCredentialsException("Email ou mot de passe incorrect");
        }
    }

    // ============================================================
    // Token Refresh
    // ============================================================
    public AuthResponse refreshToken(String refreshToken) {
        log.debug("{}: Token refresh attempt", BEAN_NAME);

        if (!jwtTokenProvider.validateToken(refreshToken)) {
            throw new BankingException("Refresh token invalide ou expiré");
        }

        String tokenType = jwtTokenProvider.getTokenType(refreshToken);
        if (!"refresh".equals(tokenType)) {
            throw new BankingException("Token invalide - expected refresh token");
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
    // Private Validation Methods
    // ============================================================
    private void validateUserType(User.UserType userType) {
        if (userType == User.UserType.ADMIN || userType == User.UserType.AGENT) {
            log.warn("{}: Unauthorized registration attempt as ADMIN/AGENT", BEAN_NAME);
            throw new BankingException(
                    "Cannot self-register as ADMIN or AGENT. Contact an administrator.");
        }
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < minPasswordLength) {
            throw new BankingException(
                    String.format("Le mot de passe doit contenir au moins %d caractères",
                            minPasswordLength));
        }

        if (!PASSWORD_PATTERN.matcher(password).matches()) {
            throw new BankingException(
                    "Le mot de passe doit contenir au moins: " +
                            "une majuscule, une minuscule, un chiffre et un caractère spécial");
        }
    }

    private void checkExistingUser(String email, String cin, String phone) {
        if (userRepository.existsByEmail(email)) {
            throw new BankingException("Email déjà enregistré");
        }
        if (userRepository.existsByCin(cin)) {
            throw new BankingException("CIN déjà enregistré");
        }
        if (userRepository.existsByPhone(phone)) {
            throw new BankingException("Téléphone déjà enregistré");
        }
    }

    private void validateUserStatus(User user) {
        if (!user.getIsActive()) {
            log.warn("{}: Login attempt for inactive user: {}", BEAN_NAME, user.getEmail());
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
                .email(request.getEmail())
                .phone(request.getPhone())
                .hashedPassword(passwordEncoder.encode(request.getPassword()))
                .fullNameAr(request.getFullNameAr())
                .fullNameFr(request.getFullNameFr())
                .cin(request.getCin())
                .userType(request.getUserType())
                .agency(agency)
                .isActive(true)
                .is2faEnabled(false)
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
                .isActive(false) // Not active until approved
                .status(Account.AccountStatus.PENDING_APPROVAL)
                .currency("TND")
                .build();

        return accountRepository.save(defaultAccount);
    }

    // ============================================================
    // Notification to Agency Agents
    // ============================================================
    private void notifyAgencyAgents(User newUser, Agency agency, Account account) {
        if (agency == null)
            return;

        // Find all agents assigned to this agency
        List<User> agents = userRepository.findByUserTypeAndAgency(
                User.UserType.AGENT, agency);

        for (User agent : agents) {
            Notification notification = Notification.builder()
                    .user(agent)
                    .type(Notification.NotificationType.SECURITY)
                    .title("Nouvelle demande d'ouverture de compte")
                    .body(String.format("%s (%s) a demandé l'ouverture d'un compte %s à l'agence %s.",
                            newUser.getFullNameFr(), newUser.getCin(),
                            account.getAccountType(), agency.getBranchName()))
                    .build();
            notificationRepository.save(notification);
        }

        log.info("{}: Notified {} agent(s) at agency {} about new account request",
                BEAN_NAME, agents.size(), agency.getBranchName());
    }

    // ============================================================
    // Optimized Account Number Generation
    // ============================================================
    private synchronized String generateAccountNumber() {
        String accountNumber;
        int attempts = 0;
        final int maxAttempts = 10;

        do {
            accountNumber = ACCOUNT_PREFIX + String.format("%012d",
                    Math.abs(UUID.randomUUID().getLeastSignificantBits() % 1_000_000_000_000L));
            attempts++;
        } while (accountRepository.findByAccountNumber(accountNumber).isPresent() && attempts < maxAttempts);

        if (attempts >= maxAttempts) {
            log.error("{}: Failed to generate unique account number after {} attempts",
                    BEAN_NAME, maxAttempts);
            throw new BankingException("Erreur lors de la génération du numéro de compte");
        }

        return accountNumber;
    }

    // ============================================================
    // Response Builder
    // ============================================================
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
                .requires2fa(user.getIs2faEnabled())
                .build();
    }
}
