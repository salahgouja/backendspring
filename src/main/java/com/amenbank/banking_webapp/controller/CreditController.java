package com.amenbank.banking_webapp.controller;

import com.amenbank.banking_webapp.dto.request.CreditReviewRequest;
import com.amenbank.banking_webapp.dto.request.CreditSimulationRequest;
import com.amenbank.banking_webapp.dto.response.CreditDocumentResponse;
import com.amenbank.banking_webapp.dto.response.CreditResponse;
import com.amenbank.banking_webapp.model.CreditDocument;
import com.amenbank.banking_webapp.service.CreditDocumentService;
import com.amenbank.banking_webapp.service.CreditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/credits")
@RequiredArgsConstructor
@Tag(name = "Credits", description = "Simulation, demande, suivi, gestion des crédits et pièces justificatives")
public class CreditController {

    private final CreditService creditService;
    private final CreditDocumentService creditDocumentService;

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
            Authentication auth,
            @PathVariable UUID id,
            @Valid @RequestBody CreditReviewRequest request) {
        return ResponseEntity.ok(creditService.reviewCredit(id, request, auth.getName()));
    }

    // ── Admin only ────────────────────────────────────────

    @PutMapping("/{id}/disburse")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Décaisser un crédit approuvé (Admin uniquement)")
    public ResponseEntity<CreditResponse> disburseCredit(Authentication auth, @PathVariable UUID id) {
        return ResponseEntity.ok(creditService.disburseCredit(id, auth.getName()));
    }

    // ============================================================
    // GAP-6: Credit Document Endpoints (Pièces justificatives)
    // ============================================================

    @PostMapping(value = "/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Uploader un justificatif pour une demande de crédit",
            description = "Formats acceptés: PDF, JPEG, PNG, DOC, DOCX. Taille max: 10 MB. Max 10 documents par demande.")
    public ResponseEntity<CreditDocumentResponse> uploadDocument(
            Authentication auth,
            @PathVariable UUID id,
            @io.swagger.v3.oas.annotations.Parameter(description = "Fichier justificatif à uploader",
                    content = @io.swagger.v3.oas.annotations.media.Content(mediaType = MediaType.MULTIPART_FORM_DATA_VALUE))
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "documentType", required = false) CreditDocument.DocumentType documentType) {
        return ResponseEntity.ok(creditDocumentService.uploadDocument(auth.getName(), id, file, documentType));
    }

    @GetMapping("/{id}/documents")
    @Operation(summary = "Lister les justificatifs d'une demande de crédit")
    public ResponseEntity<List<CreditDocumentResponse>> getDocuments(
            Authentication auth,
            @PathVariable UUID id) {
        return ResponseEntity.ok(creditDocumentService.getDocuments(auth.getName(), id));
    }

    @GetMapping("/{id}/documents/{docId}")
    @Operation(summary = "Télécharger un justificatif")
    public ResponseEntity<Resource> downloadDocument(
            Authentication auth,
            @PathVariable UUID id,
            @PathVariable UUID docId) {
        Resource resource = creditDocumentService.downloadDocument(auth.getName(), id, docId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + resource.getFilename() + "\"")
                .body(resource);
    }

    @DeleteMapping("/{id}/documents/{docId}")
    @Operation(summary = "Supprimer un justificatif")
    public ResponseEntity<Void> deleteDocument(
            Authentication auth,
            @PathVariable UUID id,
            @PathVariable UUID docId) {
        creditDocumentService.deleteDocument(auth.getName(), id, docId);
        return ResponseEntity.noContent().build();
    }
}
