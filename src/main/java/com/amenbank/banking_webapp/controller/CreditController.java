package com.amenbank.banking_webapp.controller;

import com.amenbank.banking_webapp.dto.request.CreditReviewRequest;
import com.amenbank.banking_webapp.dto.request.CreditSimulationRequest;
import com.amenbank.banking_webapp.dto.response.CreditResponse;
import com.amenbank.banking_webapp.service.CreditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/credits")
@RequiredArgsConstructor
@Tag(name = "Credits", description = "Simulation, demande, suivi et gestion des crédits")
public class CreditController {

    private final CreditService creditService;

    // ── Client endpoints ──────────────────────────────────

    @PostMapping("/simulate")
    @Operation(summary = "Simuler un crédit (pas de sauvegarde)")
    public ResponseEntity<CreditResponse> simulate(@Valid @RequestBody CreditSimulationRequest request) {
        return ResponseEntity.ok(creditService.simulate(request));
    }

    @PostMapping("/submit")
    @Operation(summary = "Soumettre une demande de crédit")
    public ResponseEntity<CreditResponse> submit(
            Authentication auth,
            @Valid @RequestBody CreditSimulationRequest request) {
        return ResponseEntity.ok(creditService.submit(auth.getName(), request));
    }

    @GetMapping
    @Operation(summary = "Lister mes demandes de crédit")
    public ResponseEntity<List<CreditResponse>> myCredits(Authentication auth) {
        return ResponseEntity.ok(creditService.getUserCredits(auth.getName()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Détail d'une demande de crédit")
    public ResponseEntity<CreditResponse> getCredit(Authentication auth, @PathVariable UUID id) {
        return ResponseEntity.ok(creditService.getCreditById(auth.getName(), id));
    }

    // ── Cancel credit (fix #20) ──────────────────────────
    @PutMapping("/{id}/cancel")
    @Operation(summary = "Annuler une demande de crédit (avant revue)")
    public ResponseEntity<CreditResponse> cancelCredit(Authentication auth, @PathVariable UUID id) {
        return ResponseEntity.ok(creditService.cancelCredit(auth.getName(), id));
    }

    // ── Agent / Admin endpoints ───────────────────────────

    @GetMapping("/pending")
    @PreAuthorize("hasAnyRole('AGENT', 'ADMIN')")
    @Operation(summary = "Lister les demandes de crédit en attente (Agent: même agence, Admin: toutes)")
    public ResponseEntity<List<CreditResponse>> pendingCredits(Authentication auth) {
        return ResponseEntity.ok(creditService.getPendingCredits(auth.getName()));
    }

    @PutMapping("/{id}/review")
    @PreAuthorize("hasAnyRole('AGENT', 'ADMIN')")
    @Operation(summary = "Approuver ou rejeter une demande de crédit (Agent/Admin)")
    public ResponseEntity<CreditResponse> reviewCredit(
            @PathVariable UUID id,
            @Valid @RequestBody CreditReviewRequest request) {
        return ResponseEntity.ok(creditService.reviewCredit(id, request));
    }

    // ── Admin only ────────────────────────────────────────

    @PutMapping("/{id}/disburse")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Décaisser un crédit approuvé (Admin uniquement)")
    public ResponseEntity<CreditResponse> disburseCredit(@PathVariable UUID id) {
        return ResponseEntity.ok(creditService.disburseCredit(id));
    }
}
