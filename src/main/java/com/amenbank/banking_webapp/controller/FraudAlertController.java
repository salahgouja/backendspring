package com.amenbank.banking_webapp.controller;

import com.amenbank.banking_webapp.dto.response.FraudAlertResponse;
import com.amenbank.banking_webapp.model.FraudAlert;
import com.amenbank.banking_webapp.service.FraudService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/fraud-alerts")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasAnyRole('AGENT', 'ADMIN')")
@Tag(name = "Fraud Alerts", description = "Fraud detection and management (Agent/Admin)")
public class FraudAlertController {

    private final FraudService fraudService;

    @GetMapping
    @Operation(summary = "List all fraud alerts (paginated)")
    public ResponseEntity<Page<FraudAlertResponse>> getAllAlerts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(fraudService.getAllAlerts(page, size));
    }

    @GetMapping("/status/{status}")
    @Operation(summary = "List fraud alerts by status")
    public ResponseEntity<Page<FraudAlertResponse>> getAlertsByStatus(
            @PathVariable FraudAlert.AlertStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(fraudService.getAlertsByStatus(status, page, size));
    }

    @GetMapping("/open-count")
    @Operation(summary = "Count of open fraud alerts")
    public ResponseEntity<Map<String, Long>> getOpenCount() {
        return ResponseEntity.ok(Map.of("openAlerts", fraudService.getOpenAlertCount()));
    }

    @PutMapping("/{id}/resolve")
    @Operation(summary = "Resolve or update a fraud alert status")
    public ResponseEntity<FraudAlertResponse> resolveAlert(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal UserDetails userDetails) {
        FraudAlert.AlertStatus newStatus = FraudAlert.AlertStatus.valueOf(body.get("status"));
        return ResponseEntity.ok(fraudService.resolveAlert(id, userDetails.getUsername(), newStatus));
    }
}

