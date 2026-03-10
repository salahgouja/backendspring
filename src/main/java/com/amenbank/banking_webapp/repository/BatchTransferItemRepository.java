package com.amenbank.banking_webapp.repository;

import com.amenbank.banking_webapp.model.BatchTransferItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BatchTransferItemRepository extends JpaRepository<BatchTransferItem, UUID> {

    List<BatchTransferItem> findByBatchTransferId(UUID batchTransferId);
}

