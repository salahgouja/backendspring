package com.amenbank.banking_webapp.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * GAP-6: Justificatif / document attached to a credit request.
 */
@Entity
@Table(name = "credit_documents")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = {"creditRequest"})
public class CreditDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "credit_request_id", nullable = false)
    private CreditRequest creditRequest;

    @Column(nullable = false)
    private String fileName;

    @Column(nullable = false)
    private String fileType; // MIME type: application/pdf, image/jpeg, etc.

    @Column(nullable = false)
    private Long fileSize; // Size in bytes

    @Column(nullable = false)
    private String storagePath; // Path on disk or S3 key

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private DocumentType documentType = DocumentType.AUTRE;

    @CreationTimestamp
    private LocalDateTime uploadedAt;

    public enum DocumentType {
        FICHE_PAIE,       // Pay slip
        ATTESTATION,      // Employment certificate
        RELEVE_BANCAIRE,  // Bank statement
        PIECE_IDENTITE,   // ID document
        DEVIS,            // Quote / estimate
        TITRE_PROPRIETE,  // Property title
        AUTRE             // Other
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CreditDocument that = (CreditDocument) o;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}

