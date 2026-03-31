package com.amenbank.banking_webapp.controller;

import com.amenbank.banking_webapp.dto.request.ChangePasswordRequest;
import com.amenbank.banking_webapp.dto.request.ForgotPasswordRequest;
import com.amenbank.banking_webapp.dto.request.LoginRequest;
import com.amenbank.banking_webapp.dto.request.RegisterRequest;
import com.amenbank.banking_webapp.dto.request.ResetPasswordRequest;
import com.amenbank.banking_webapp.dto.request.UpdateProfileRequest;
import com.amenbank.banking_webapp.dto.request.TwoFactorCodeRequest;
import com.amenbank.banking_webapp.dto.request.TwoFactorVerifyRequest;
import com.amenbank.banking_webapp.dto.response.AuthResponse;
import com.amenbank.banking_webapp.dto.response.UserProfileResponse;
import com.amenbank.banking_webapp.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.Resource;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Register, login, logout, 2FA, password management")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    @Operation(summary = "Register a new user")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @PostMapping("/login")
    @Operation(summary = "Login with email and password", description = "If 2FA is enabled, requires2fa=true and a temp token is returned")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    // ── Logout (fix #2) ──────────────────────────────────
    @PostMapping("/logout")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Logout — blacklists the current token")
    public ResponseEntity<Map<String, String>> logout(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        return ResponseEntity.ok(authService.logout(authHeader));
    }

    // ── Token Refresh ────────────────────────────────────
    @PostMapping("/refresh")
    @Operation(summary = "Refresh access token using refresh token")
    public ResponseEntity<AuthResponse> refreshToken(@RequestBody Map<String, String> body) {
        return ResponseEntity.ok(authService.refreshToken(body.get("refreshToken")));
    }

    // ── 2FA Enable (fix #1) ──────────────────────────────
    @PostMapping("/2fa/enable")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Start 2FA setup — returns TOTP secret and QR code URL")
    public ResponseEntity<Map<String, String>> enable2fa(
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(authService.enable2fa(userDetails.getUsername()));
    }

    @PostMapping("/2fa/confirm")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Confirm 2FA setup with a TOTP code from your authenticator app")
    public ResponseEntity<Map<String, String>> confirmEnable2fa(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody TwoFactorCodeRequest request) {
        return ResponseEntity.ok(authService.confirmEnable2fa(userDetails.getUsername(), request.getCode()));
    }

    @PostMapping("/2fa/verify")
    @Operation(summary = "Verify 2FA code after login (completes the login flow)")
    public ResponseEntity<AuthResponse> verify2fa(@Valid @RequestBody TwoFactorVerifyRequest request) {
        return ResponseEntity.ok(authService.verify2fa(request.getTempToken(), request.getCode()));
    }

    @PostMapping("/2fa/disable")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Disable 2FA — requires a valid TOTP code to confirm")
    public ResponseEntity<Map<String, String>> disable2fa(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody TwoFactorCodeRequest request) {
        return ResponseEntity.ok(authService.disable2fa(userDetails.getUsername(), request.getCode()));
    }

    // ── Password Change ────────────────────────
    @PutMapping("/change-password")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Change password — requires current password")
    public ResponseEntity<Map<String, String>> changePassword(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody ChangePasswordRequest request) {
        return ResponseEntity.ok(authService.changePassword(userDetails.getUsername(), request));
    }

    // ── Profile  ────────────────────────────────
    @GetMapping("/profile")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Get current user profile")
    public ResponseEntity<UserProfileResponse> getProfile(
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(authService.getProfile(userDetails.getUsername()));
    }

    @PutMapping("/profile")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Update user profile (phone, name)")
    public ResponseEntity<UserProfileResponse> updateProfile(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody UpdateProfileRequest request) {
        return ResponseEntity.ok(authService.updateProfile(userDetails.getUsername(), request));
    }

    @PostMapping(value = "/profile-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Upload current user profile image")
    public ResponseEntity<Map<String, String>> uploadProfileImage(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestPart("file") MultipartFile file) {
        return ResponseEntity.ok(authService.uploadProfileImage(userDetails.getUsername(), file));
    }

    @GetMapping("/profile-images/{fileName:.+}")
    @Operation(summary = "Get profile image by file name")
    public ResponseEntity<Resource> getProfileImage(@PathVariable String fileName) {
        Resource resource = authService.loadProfileImage(fileName);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }

    // ──  Forgot Password (send OTP) ──────────────
    @PostMapping("/forgot-password")
    @Operation(summary = "Request password reset — sends OTP code",
            description = "Sends a 6-digit OTP code to the user's notification inbox (in production: via email/SMS). OTP expires in 15 minutes.")
    public ResponseEntity<Map<String, String>> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request) {
        return ResponseEntity.ok(authService.forgotPassword(request.getEmail()));
    }

    // ── Reset Password (verify OTP) ─────────────
    @PostMapping("/reset-password")
    @Operation(summary = "Reset password using OTP code",
            description = "Verifies the OTP and sets a new password. The OTP is single-use and expires in 15 minutes.")
    public ResponseEntity<Map<String, String>> resetPassword(
            @RequestBody ResetPasswordRequest request) {
        return ResponseEntity.ok(authService.resetPassword(
                request.getEmail(), request.getOtpCode(), request.getNewPassword()));
    }
}
