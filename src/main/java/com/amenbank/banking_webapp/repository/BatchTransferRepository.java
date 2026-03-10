package com.amenbank.banking_webapp.repository;

import com.amenbank.banking_webapp.model.BatchTransfer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface BatchTransferRepository extends JpaRepository<BatchTransfer, UUID> {

    Page<BatchTransfer> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);
}

