package com.amenbank.banking_webapp.repository;

import com.amenbank.banking_webapp.model.Transfer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    List<Transfer> findBySenderUserIdOrderByCreatedAtDesc(UUID userId);

    List<Transfer> findByIsRecurringTrueAndStatus(Transfer.TransferStatus status);
}
