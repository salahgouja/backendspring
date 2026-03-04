package com.amenbank.banking_webapp.controller;

import com.amenbank.banking_webapp.dto.request.RegisterRequest;
import com.amenbank.banking_webapp.model.User;
import com.amenbank.banking_webapp.repository.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
@Tag(name = "Admin", description = "Administration endpoints — ADMIN only")
public class AdminController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    // ── Create Agent/Admin account ────────────────────────
    @PostMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Create an AGENT or ADMIN user (admin only)")
    public ResponseEntity<Map<String, Object>> createPrivilegedUser(@Valid @RequestBody RegisterRequest request) {
        // Only allow creating AGENT or ADMIN
        if (request.getUserType() != User.UserType.ADMIN && request.getUserType() != User.UserType.AGENT) {
            throw new RuntimeException("Use /auth/register for PARTICULIER and COMMERCANT accounts");
        }

        if (userRepository.existsByEmail(request.getEmail())) {
            throw new RuntimeException("Email already registered");
        }
        if (userRepository.existsByCin(request.getCin())) {
            throw new RuntimeException("CIN already registered");
        }

        User user = User.builder()
                .email(request.getEmail())
                .phone(request.getPhone())
                .hashedPassword(passwordEncoder.encode(request.getPassword()))
                .fullNameAr(request.getFullNameAr())
                .fullNameFr(request.getFullNameFr())
                .cin(request.getCin())
                .userType(request.getUserType())
                .build();

        user = userRepository.save(user);

        Map<String, Object> response = new HashMap<>();
        response.put("id", user.getId());
        response.put("email", user.getEmail());
        response.put("fullName", user.getFullNameFr());
        response.put("userType", user.getUserType());
        response.put("message", "User created successfully");
        return ResponseEntity.ok(response);
    }

    // ── List all users ────────────────────────────────────
    @GetMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "List all users (admin only)")
    public ResponseEntity<List<Map<String, Object>>> listUsers(
            @RequestParam(required = false) User.UserType userType) {

        List<User> users;
        if (userType != null) {
            users = userRepository.findAll().stream()
                    .filter(u -> u.getUserType() == userType)
                    .collect(Collectors.toList());
        } else {
            users = userRepository.findAll();
        }

        List<Map<String, Object>> result = users.stream().map(u -> {
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
            map.put("createdAt", u.getCreatedAt());
            return map;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    // ── Deactivate a user ─────────────────────────────────
    @PutMapping("/users/{userId}/deactivate")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Deactivate a user account (admin only)")
    public ResponseEntity<Map<String, Object>> deactivateUser(@PathVariable UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (user.getUserType() == User.UserType.ADMIN) {
            throw new RuntimeException("Cannot deactivate another admin");
        }

        user.setIsActive(false);
        userRepository.save(user);

        Map<String, Object> response = new HashMap<>();
        response.put("message", "User deactivated: " + user.getEmail());
        response.put("userId", userId);
        return ResponseEntity.ok(response);
    }

    // ── Activate a user ───────────────────────────────────
    @PutMapping("/users/{userId}/activate")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Re-activate a user account (admin only)")
    public ResponseEntity<Map<String, Object>> activateUser(@PathVariable UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        user.setIsActive(true);
        userRepository.save(user);

        Map<String, Object> response = new HashMap<>();
        response.put("message", "User activated: " + user.getEmail());
        response.put("userId", userId);
        return ResponseEntity.ok(response);
    }

    // ── Dashboard stats ───────────────────────────────────
    @GetMapping("/stats")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Get system statistics (admin only)")
    public ResponseEntity<Map<String, Object>> getStats() {
        List<User> allUsers = userRepository.findAll();

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalUsers", allUsers.size());
        stats.put("particuliers", allUsers.stream().filter(u -> u.getUserType() == User.UserType.PARTICULIER).count());
        stats.put("commercants", allUsers.stream().filter(u -> u.getUserType() == User.UserType.COMMERCANT).count());
        stats.put("agents", allUsers.stream().filter(u -> u.getUserType() == User.UserType.AGENT).count());
        stats.put("admins", allUsers.stream().filter(u -> u.getUserType() == User.UserType.ADMIN).count());
        stats.put("activeUsers", allUsers.stream().filter(User::getIsActive).count());
        stats.put("inactiveUsers", allUsers.stream().filter(u -> !u.getIsActive()).count());
        stats.put("users2faEnabled", allUsers.stream().filter(User::getIs2faEnabled).count());

        return ResponseEntity.ok(stats);
    }
}
