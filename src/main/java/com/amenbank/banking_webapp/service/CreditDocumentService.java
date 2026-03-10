package com.amenbank.banking_webapp.service;

import com.amenbank.banking_webapp.dto.response.CreditDocumentResponse;
import com.amenbank.banking_webapp.exception.BankingException;
import com.amenbank.banking_webapp.exception.BankingException.ForbiddenException;
import com.amenbank.banking_webapp.exception.BankingException.NotFoundException;
import com.amenbank.banking_webapp.model.*;
import com.amenbank.banking_webapp.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * GAP-6: Service for managing credit request document uploads.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CreditDocumentService {

    private static final int MAX_DOCUMENTS_PER_CREDIT = 10;
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10 MB
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "application/pdf",
            "image/jpeg",
            "image/png",
            "image/jpg",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    );

    private final CreditDocumentRepository creditDocumentRepository;
    private final CreditRequestRepository creditRequestRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    @Value("${app.upload.credit-documents-dir:./uploads/credit-documents}")
    private String uploadDir;

    // ── Upload Document ────────────────────────────────────
    @Transactional
    public CreditDocumentResponse uploadDocument(String userEmail, UUID creditId,
                                                  MultipartFile file,
                                                  CreditDocument.DocumentType documentType) {
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new NotFoundException("Utilisateur introuvable"));

        CreditRequest credit = creditRequestRepository.findById(creditId)
                .orElseThrow(() -> new NotFoundException("Demande de crédit introuvable"));

        // Verify ownership
        if (!credit.getUser().getId().equals(user.getId())) {
            throw new ForbiddenException("Cette demande de crédit ne vous appartient pas");
        }

        // Only allow uploads for SUBMITTED or SIMULATION status
        if (credit.getStatus() != CreditRequest.CreditStatus.SUBMITTED
                && credit.getStatus() != CreditRequest.CreditStatus.SIMULATION
                && credit.getStatus() != CreditRequest.CreditStatus.IN_REVIEW) {
            throw new BankingException("Les documents ne peuvent être ajoutés que pour les demandes en cours");
        }

        // Validate file
        if (file.isEmpty()) {
            throw new BankingException("Le fichier est vide");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BankingException("Le fichier dépasse la taille maximale de 10 MB");
        }
        if (file.getContentType() == null || !ALLOWED_TYPES.contains(file.getContentType())) {
            throw new BankingException("Type de fichier non autorisé. Formats acceptés: PDF, JPEG, PNG, DOC, DOCX");
        }

        // Check max documents per credit
        long docCount = creditDocumentRepository.countByCreditRequestId(creditId);
        if (docCount >= MAX_DOCUMENTS_PER_CREDIT) {
            throw new BankingException("Nombre maximum de documents atteint (" + MAX_DOCUMENTS_PER_CREDIT + ")");
        }

        // Store file
        String storagePath = storeFile(file, creditId);

        // Create record
        CreditDocument doc = CreditDocument.builder()
                .creditRequest(credit)
                .fileName(sanitize(file.getOriginalFilename()))
                .fileType(file.getContentType())
                .fileSize(file.getSize())
                .storagePath(storagePath)
                .documentType(documentType != null ? documentType : CreditDocument.DocumentType.AUTRE)
                .build();
        creditDocumentRepository.save(doc);

        auditService.log(AuditLog.AuditAction.CREDIT_SUBMITTED, userEmail,
                "CreditDocument", doc.getId().toString(),
                "Document uploaded: " + doc.getFileName() + " for credit " + creditId);

        log.info("Document uploaded for credit {}: {}", creditId, doc.getFileName());

        return toResponse(doc);
    }

    // ── List Documents ─────────────────────────────────────
    @Transactional(readOnly = true)
    public List<CreditDocumentResponse> getDocuments(String userEmail, UUID creditId) {
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new NotFoundException("Utilisateur introuvable"));

        CreditRequest credit = creditRequestRepository.findById(creditId)
                .orElseThrow(() -> new NotFoundException("Demande de crédit introuvable"));

        // Allow owner, agents, and admins to view documents
        boolean isOwner = credit.getUser().getId().equals(user.getId());
        boolean isStaff = user.getUserType() == User.UserType.AGENT || user.getUserType() == User.UserType.ADMIN;
        if (!isOwner && !isStaff) {
            throw new ForbiddenException("Accès refusé");
        }

        return creditDocumentRepository.findByCreditRequestIdOrderByUploadedAtDesc(creditId)
                .stream().map(this::toResponse).toList();
    }

    // ── Download Document ──────────────────────────────────
    @Transactional(readOnly = true)
    public Resource downloadDocument(String userEmail, UUID creditId, UUID docId) {
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new NotFoundException("Utilisateur introuvable"));

        CreditRequest credit = creditRequestRepository.findById(creditId)
                .orElseThrow(() -> new NotFoundException("Demande de crédit introuvable"));

        boolean isOwner = credit.getUser().getId().equals(user.getId());
        boolean isStaff = user.getUserType() == User.UserType.AGENT || user.getUserType() == User.UserType.ADMIN;
        if (!isOwner && !isStaff) {
            throw new ForbiddenException("Accès refusé");
        }

        CreditDocument doc = creditDocumentRepository.findById(docId)
                .orElseThrow(() -> new NotFoundException("Document introuvable"));

        if (!doc.getCreditRequest().getId().equals(creditId)) {
            throw new BankingException("Ce document n'appartient pas à cette demande de crédit");
        }

        try {
            Path filePath = Paths.get(doc.getStoragePath());
            Resource resource = new UrlResource(filePath.toUri());
            if (!resource.exists()) {
                throw new NotFoundException("Fichier introuvable sur le serveur");
            }
            return resource;
        } catch (MalformedURLException e) {
            throw new BankingException("Erreur lors du téléchargement du fichier");
        }
    }

    // ── Delete Document ────────────────────────────────────
    @Transactional
    public void deleteDocument(String userEmail, UUID creditId, UUID docId) {
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new NotFoundException("Utilisateur introuvable"));

        CreditRequest credit = creditRequestRepository.findById(creditId)
                .orElseThrow(() -> new NotFoundException("Demande de crédit introuvable"));

        if (!credit.getUser().getId().equals(user.getId())) {
            throw new ForbiddenException("Cette demande de crédit ne vous appartient pas");
        }

        CreditDocument doc = creditDocumentRepository.findById(docId)
                .orElseThrow(() -> new NotFoundException("Document introuvable"));

        if (!doc.getCreditRequest().getId().equals(creditId)) {
            throw new BankingException("Ce document n'appartient pas à cette demande de crédit");
        }

        // Delete file from disk
        try {
            Files.deleteIfExists(Paths.get(doc.getStoragePath()));
        } catch (IOException e) {
            log.warn("Could not delete file {}: {}", doc.getStoragePath(), e.getMessage());
        }

        creditDocumentRepository.delete(doc);

        log.info("Document {} deleted for credit {}", docId, creditId);
    }

    // ── Private Helpers ────────────────────────────────────
    private String storeFile(MultipartFile file, UUID creditId) {
        try {
            Path uploadPath = Paths.get(uploadDir, creditId.toString()).normalize().toAbsolutePath();
            Files.createDirectories(uploadPath);

            // Sanitize: only keep a safe extension, ignore user-controlled path components
            String originalFilename = file.getOriginalFilename();
            String extension = "";
            if (originalFilename != null && originalFilename.contains(".")) {
                extension = originalFilename.substring(originalFilename.lastIndexOf("."))
                        .replaceAll("[^a-zA-Z0-9.]", ""); // strip anything dangerous
            }
            String storedName = UUID.randomUUID() + extension;

            Path filePath = uploadPath.resolve(storedName).normalize();
            // Guard against path traversal
            if (!filePath.startsWith(uploadPath)) {
                throw new BankingException("Chemin de fichier invalide");
            }
            Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);

            return filePath.toString();
        } catch (IOException e) {
            throw new BankingException("Erreur lors de l'enregistrement du fichier: " + e.getMessage());
        }
    }

    private CreditDocumentResponse toResponse(CreditDocument doc) {
        return CreditDocumentResponse.builder()
                .id(doc.getId())
                .creditRequestId(doc.getCreditRequest().getId())
                .fileName(doc.getFileName())
                .fileType(doc.getFileType())
                .fileSize(doc.getFileSize())
                .documentType(doc.getDocumentType().name())
                .uploadedAt(doc.getUploadedAt())
                .downloadUrl("/api/credits/" + doc.getCreditRequest().getId() + "/documents/" + doc.getId())
                .build();
    }

    private String sanitize(String input) {
        if (input == null) return "document";
        return input.replaceAll("[<>\"'&]", "");
    }
}

