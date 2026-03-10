package com.amenbank.banking_webapp.repository;

import com.amenbank.banking_webapp.model.CreditDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CreditDocumentRepository extends JpaRepository<CreditDocument, UUID> {

    List<CreditDocument> findByCreditRequestIdOrderByUploadedAtDesc(UUID creditRequestId);

    long countByCreditRequestId(UUID creditRequestId);
}

