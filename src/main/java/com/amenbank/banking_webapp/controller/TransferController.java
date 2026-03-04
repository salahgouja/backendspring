package com.amenbank.banking_webapp.controller;

import com.amenbank.banking_webapp.dto.request.TransferRequest;
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
@Tag(name = "Virements", description = "Gestion des virements bancaires")
@SecurityRequirement(name = "bearerAuth")
public class TransferController {

    private final TransferService transferService;

    @PostMapping
    @Operation(summary = "Effectuer un virement", description = "Débite le compte émetteur et crédite le compte destinataire. Crée les transactions et notifications associées.")
    public ResponseEntity<TransferResponse> createTransfer(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody TransferRequest request) {
        return ResponseEntity.ok(transferService.createTransfer(userDetails.getUsername(), request));
    }

    @GetMapping
    @Operation(summary = "Lister mes virements envoyés (paginé)", description = "Retourne tous les virements envoyés par l'utilisateur connecté.")
    public ResponseEntity<Page<TransferResponse>> getMyTransfers(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(transferService.getUserTransfersPaged(userDetails.getUsername(), page, size));
    }

    // ── Received transfers (fix #13) ─────────────────────
    @GetMapping("/received")
    @Operation(summary = "Lister les virements reçus (paginé)")
    public ResponseEntity<Page<TransferResponse>> getReceivedTransfers(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(transferService.getReceivedTransfers(userDetails.getUsername(), page, size));
    }

    // ── All transfers — sent + received (fix #13) ────────
    @GetMapping("/all")
    @Operation(summary = "Lister tous mes virements — envoyés et reçus (paginé)")
    public ResponseEntity<Page<TransferResponse>> getAllTransfers(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(transferService.getAllUserTransfers(userDetails.getUsername(), page, size));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Détail d'un virement", description = "Retourne les détails d'un virement spécifique.")
    public ResponseEntity<TransferResponse> getTransfer(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable UUID id) {
        return ResponseEntity.ok(transferService.getTransferById(userDetails.getUsername(), id));
    }
}
