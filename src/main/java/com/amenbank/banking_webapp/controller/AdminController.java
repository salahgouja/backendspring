package com.amenbank.banking_webapp.controller;

import com.amenbank.banking_webapp.dto.request.RegisterRequest;
import com.amenbank.banking_webapp.exception.BankingException;
import com.amenbank.banking_webapp.model.Agency;
import com.amenbank.banking_webapp.model.AuditLog;
import com.amenbank.banking_webapp.model.User;
import com.amenbank.banking_webapp.repository.AccountRepository;
import com.amenbank.banking_webapp.repository.AgencyRepository;
import com.amenbank.banking_webapp.repository.UserRepository;
import com.amenbank.banking_webapp.security.UserDetailsServiceImpl;
import com.amenbank.banking_webapp.service.AuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
@Tag(name = "Admin", description = "Administration endpoints — ADMIN only")
public class AdminController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AgencyRepository agencyRepository;
    private final AccountRepository accountRepository;
    private final AuditService auditService;
    private final UserDetailsServiceImpl userDetailsService;

    // ── Create Agent/Admin account (fix #6, #21) ─────────
    @PostMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Create an AGENT or ADMIN user (admin only)")
    public ResponseEntity<Map<String, Object>> createPrivilegedUser(
            @Valid @RequestBody RegisterRequest request,
            @AuthenticationPrincipal UserDetails adminDetails) {

        // Fix #6: Use BankingException instead of RuntimeException
        if (request.getUserType() != User.UserType.ADMIN && request.getUserType() != User.UserType.AGENT) {
            throw new BankingException("Use /auth/register for PARTICULIER and COMMERCANT accounts");
        }
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new BankingException("Email already registered");
        }
        if (userRepository.existsByCin(request.getCin())) {
            throw new BankingException("CIN already registered");
        }

        // Fix #21: Resolve and link agency for AGENT users
        Agency agency = null;
        if (request.getUserType() == User.UserType.AGENT && request.getAgencyId() != null) {
            agency = agencyRepository.findById(request.getAgencyId())
                    .orElseThrow(() -> new BankingException.NotFoundException("Agence introuvable: " + request.getAgencyId()));
        }

        User user = User.builder()
                .email(request.getEmail())
                .phone(request.getPhone())
                .hashedPassword(passwordEncoder.encode(request.getPassword()))
                .fullNameAr(request.getFullNameAr())
                .fullNameFr(request.getFullNameFr())
                .cin(request.getCin())
                .userType(request.getUserType())
                .agency(agency)
                .failedLoginAttempts(0)
                .build();

        user = userRepository.save(user);

        auditService.log(AuditLog.AuditAction.AGENT_CREATED, adminDetails.getUsername(),
                "User", user.getId().toString(),
                "Created " + user.getUserType() + " user: " + user.getEmail()
                + (agency != null ? " at agency " + agency.getBranchName() : ""));

        Map<String, Object> response = new HashMap<>();
        response.put("id", user.getId());
        response.put("email", user.getEmail());
        response.put("fullName", user.getFullNameFr());
        response.put("userType", user.getUserType());
        response.put("agencyName", agency != null ? agency.getBranchName() : null);
        response.put("message", "User created successfully");
        return ResponseEntity.ok(response);
    }

    // ── List all users (BUG-5 fix: paginated) ───────────
    @GetMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "List all users — paginated (admin only)")
    public ResponseEntity<Page<Map<String, Object>>> listUsers(
            @RequestParam(required = false) User.UserType userType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        PageRequest pageRequest = PageRequest.of(page, size, Sort.by("createdAt").descending());

        Page<User> usersPage;
        if (userType != null) {
            usersPage = userRepository.findByUserType(userType, pageRequest);
        } else {
            usersPage = userRepository.findAll(pageRequest);
        }

        Page<Map<String, Object>> result = usersPage.map(u -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", u.getId());
            map.put("email", u.getEmail());
            map.put("fullNameFr", u.getFullNameFr());
            map.put("fullNameAr", u.getFullNameAr());
            map.put("phone", u.getPhone());
            map.put("cin", u.getCin());
            map.put("userType", u.getUserType());
            map.put("isActive", u.getIsActive());
            map.put("is2faEnabled", u.getIs2faEnabled());
            map.put("agencyName", u.getAgency() != null ? u.getAgency().getBranchName() : null);
            map.put("createdAt", u.getCreatedAt());
            return map;
        });

        return ResponseEntity.ok(result);
    }

    // ── Deactivate a user (fix #6, #28) ───────────────────
    @PutMapping("/users/{userId}/deactivate")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Deactivate a user account (admin only)")
    public ResponseEntity<Map<String, Object>> deactivateUser(
            @PathVariable UUID userId,
            @AuthenticationPrincipal UserDetails adminDetails) {

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BankingException.NotFoundException("User not found"));

        if (user.getUserType() == User.UserType.ADMIN) {
            throw new BankingException.ForbiddenException("Cannot deactivate another admin");
        }

        user.setIsActive(false);
        userRepository.save(user);

        // Fix #28: Invalidate user cache on deactivation
        userDetailsService.invalidateCache(user.getEmail());

        auditService.log(AuditLog.AuditAction.USER_DEACTIVATED, adminDetails.getUsername(),
                "User", userId.toString(), "User deactivated: " + user.getEmail());

        Map<String, Object> response = new HashMap<>();
        response.put("message", "User deactivated: " + user.getEmail());
        response.put("userId", userId);
        return ResponseEntity.ok(response);
    }

    // ── Activate a user (fix #6, #28) ─────────────────────
    @PutMapping("/users/{userId}/activate")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Re-activate a user account (admin only)")
    public ResponseEntity<Map<String, Object>> activateUser(
            @PathVariable UUID userId,
            @AuthenticationPrincipal UserDetails adminDetails) {

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BankingException.NotFoundException("User not found"));

        user.setIsActive(true);
        user.setFailedLoginAttempts(0);
        user.setLockUntil(null);
        userRepository.save(user);

        // Fix #28: Invalidate user cache on activation
        userDetailsService.invalidateCache(user.getEmail());

        auditService.log(AuditLog.AuditAction.USER_ACTIVATED, adminDetails.getUsername(),
                "User", userId.toString(), "User activated: " + user.getEmail());

        Map<String, Object> response = new HashMap<>();
        response.put("message", "User activated: " + user.getEmail());
        response.put("userId", userId);
        return ResponseEntity.ok(response);
    }

    // ── Dashboard stats — optimized with count queries (fix #23) ──
    @GetMapping("/stats")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Get system statistics (admin only)")
    public ResponseEntity<Map<String, Object>> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalUsers", userRepository.count());
        stats.put("particuliers", userRepository.countByUserType(User.UserType.PARTICULIER));
        stats.put("commercants", userRepository.countByUserType(User.UserType.COMMERCANT));
        stats.put("agents", userRepository.countByUserType(User.UserType.AGENT));
        stats.put("admins", userRepository.countByUserType(User.UserType.ADMIN));
        stats.put("activeUsers", userRepository.countByIsActiveTrue());
        stats.put("inactiveUsers", userRepository.countByIsActiveFalse());
        stats.put("users2faEnabled", userRepository.countByIs2faEnabledTrue());
        stats.put("pendingAccounts", accountRepository.countByStatus(
                com.amenbank.banking_webapp.model.Account.AccountStatus.PENDING_APPROVAL));
        return ResponseEntity.ok(stats);
    }

    // ── Audit Logs (fix #24) ──────────────────────────────
    @GetMapping("/audit-logs")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "View audit logs (admin only)")
    public ResponseEntity<Page<Map<String, Object>>> getAuditLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(auditService.getAuditLogs(page, size));
    }

    // ── GAP-21: Agent Dashboard (agency-specific stats) ──
    @GetMapping("/agent/dashboard")
    @PreAuthorize("hasAnyRole('AGENT', 'ADMIN')")
    @Operation(summary = "Get agency-specific dashboard stats (Agent/Admin)")
    public ResponseEntity<Map<String, Object>> getAgentDashboard(
            @AuthenticationPrincipal UserDetails adminDetails) {

        User agent = userRepository.findByEmail(adminDetails.getUsername())
                .orElseThrow(() -> new BankingException.NotFoundException("Agent not found"));

        Map<String, Object> stats = new HashMap<>();
        stats.put("agentName", agent.getFullNameFr());
        stats.put("userType", agent.getUserType().name());

        if (agent.getAgency() != null) {
            stats.put("agencyName", agent.getAgency().getBranchName());
            stats.put("agencyGovernorate", agent.getAgency().getGovernorate());

            UUID agencyId = agent.getAgency().getId();
            // BUG-6 fix: Use count queries instead of loading all users
            long agencyClients = userRepository.countByAgencyIdAndUserTypeIn(agencyId,
                    List.of(User.UserType.PARTICULIER, User.UserType.COMMERCANT));
            stats.put("agencyClients", agencyClients);

            // BUG-6 fix: Use count queries instead of .size()
            long pendingAccounts = accountRepository.countByUserAgencyIdAndStatus(
                    agencyId, com.amenbank.banking_webapp.model.Account.AccountStatus.PENDING_APPROVAL);
            stats.put("pendingAccounts", pendingAccounts);

            long activeAccounts = accountRepository.countByUserAgencyIdAndStatusIn(
                    agencyId, List.of(com.amenbank.banking_webapp.model.Account.AccountStatus.ACTIVE));
            stats.put("activeAccounts", activeAccounts);
        } else {
            // Admin with no agency — show global stats
            stats.put("agencyName", "N/A (Siège)");
            stats.put("totalClients", userRepository.countByUserType(User.UserType.PARTICULIER)
                    + userRepository.countByUserType(User.UserType.COMMERCANT));
            stats.put("pendingAccounts", accountRepository.countByStatus(
                    com.amenbank.banking_webapp.model.Account.AccountStatus.PENDING_APPROVAL));
        }

        return ResponseEntity.ok(stats);
    }
}
