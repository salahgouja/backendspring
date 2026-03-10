package com.amenbank.banking_webapp.controller;

import com.amenbank.banking_webapp.dto.request.BatchTransferRequest;
import com.amenbank.banking_webapp.dto.request.ScheduledTransferRequest;
import com.amenbank.banking_webapp.dto.request.TransferRequest;
import com.amenbank.banking_webapp.dto.response.BatchTransferResponse;
import com.amenbank.banking_webapp.dto.response.TransferResponse;
import com.amenbank.banking_webapp.service.TransferService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/transfers")
@RequiredArgsConstructor
@Tag(name = "Virements", description = "Gestion des virements bancaires — immédiats, programmés, permanents et groupés")
@SecurityRequirement(name = "bearerAuth")
public class TransferController {

    private final TransferService transferService;

    // ── Immediate Transfer ────────────────────────────────
    @PostMapping
    @Operation(summary = "Effectuer un virement immédiat",
            description = "Débite le compte émetteur et crédite le destinataire. Si 2FA est activé, le champ totpCode est requis.")
    public ResponseEntity<TransferResponse> createTransfer(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody TransferRequest request) {
        return ResponseEntity.ok(transferService.createTransfer(userDetails.getUsername(), request));
    }

    // ── GAP-11: Self-Transfer (between own accounts) ──────
    @PostMapping("/internal")
    @Operation(summary = "Virement interne entre vos propres comptes",
            description = "Transfère de l'argent entre deux comptes vous appartenant. Pas de 2FA requis, pas de limite journalière.")
    public ResponseEntity<TransferResponse> createSelfTransfer(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody TransferRequest request) {
        return ResponseEntity.ok(transferService.createSelfTransfer(userDetails.getUsername(), request));
    }

    // ── GAP-4: Scheduled (one-time future) Transfer ───────
    @PostMapping("/scheduled")
    @Operation(summary = "Programmer un virement unique",
            description = "Crée un virement qui sera exécuté automatiquement à la date spécifiée (scheduledAt).")
    public ResponseEntity<TransferResponse> createScheduledTransfer(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody ScheduledTransferRequest request) {
        return ResponseEntity.ok(transferService.createScheduledTransfer(userDetails.getUsername(), request));
    }

    // ── GAP-4: Recurring (permanent) Transfer ─────────────
    @PostMapping("/recurring")
    @Operation(summary = "Créer un virement permanent (récurrent)",
            description = "Planifie un virement tous les N mois. Notification 24h avant chaque exécution.")
    public ResponseEntity<TransferResponse> createRecurringTransfer(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody ScheduledTransferRequest request) {
        return ResponseEntity.ok(transferService.createRecurringTransfer(userDetails.getUsername(), request));
    }

    // ── GAP-4: List scheduled/recurring transfers ─────────
    @GetMapping("/scheduled")
    @Operation(summary = "Lister mes virements programmés et permanents (paginé)")
    public ResponseEntity<Page<TransferResponse>> getScheduledTransfers(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(transferService.getScheduledTransfers(userDetails.getUsername(), page, size));
    }

    // ── GAP-4: Cancel a scheduled/recurring transfer ──────
    @DeleteMapping("/scheduled/{id}")
    @Operation(summary = "Annuler un virement programmé ou permanent",
            description = "Annule un virement en attente (PENDING). Ne peut pas annuler un virement déjà exécuté.")
    public ResponseEntity<TransferResponse> cancelScheduledTransfer(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable UUID id) {
        return ResponseEntity.ok(transferService.cancelScheduledTransfer(userDetails.getUsername(), id));
    }

    // ── GAP-3: Batch (Grouped) Transfer ───────────────────
    @PostMapping("/batch")
    @Operation(summary = "Effectuer un virement groupé",
            description = "Envoie un virement vers plusieurs bénéficiaires en une seule demande. Chaque sous-virement est traité individuellement.")
    public ResponseEntity<BatchTransferResponse> createBatchTransfer(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody BatchTransferRequest request) {
        return ResponseEntity.ok(transferService.createBatchTransfer(userDetails.getUsername(), request));
    }

    // ── GAP-3: Get Batch Transfer Details ─────────────────
    @GetMapping("/batch/{id}")
    @Operation(summary = "Détail d'un virement groupé",
            description = "Retourne le statut de chaque sous-virement du lot.")
    public ResponseEntity<BatchTransferResponse> getBatchTransfer(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable UUID id) {
        return ResponseEntity.ok(transferService.getBatchTransferById(userDetails.getUsername(), id));
    }

    // ── GAP-3: List my batch transfers ────────────────────
    @GetMapping("/batch")
    @Operation(summary = "Lister mes virements groupés (paginé)")
    public ResponseEntity<Page<BatchTransferResponse>> getMyBatchTransfers(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(transferService.getMyBatchTransfers(userDetails.getUsername(), page, size));
    }

    // ── Existing: List sent transfers ─────────────────────
    @GetMapping
    @Operation(summary = "Lister mes virements envoyés (paginé)")
    public ResponseEntity<Page<TransferResponse>> getMyTransfers(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(transferService.getUserTransfersPaged(userDetails.getUsername(), page, size));
    }

    @GetMapping("/received")
    @Operation(summary = "Lister les virements reçus (paginé)")
    public ResponseEntity<Page<TransferResponse>> getReceivedTransfers(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(transferService.getReceivedTransfers(userDetails.getUsername(), page, size));
    }

    @GetMapping("/all")
    @Operation(summary = "Lister tous mes virements — envoyés et reçus (paginé)")
    public ResponseEntity<Page<TransferResponse>> getAllTransfers(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(transferService.getAllUserTransfers(userDetails.getUsername(), page, size));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Détail d'un virement")
    public ResponseEntity<TransferResponse> getTransfer(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable UUID id) {
        return ResponseEntity.ok(transferService.getTransferById(userDetails.getUsername(), id));
    }
}
