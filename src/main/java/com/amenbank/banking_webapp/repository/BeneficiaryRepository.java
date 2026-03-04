package com.amenbank.banking_webapp.repository;

import com.amenbank.banking_webapp.model.Beneficiary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BeneficiaryRepository extends JpaRepository<Beneficiary, UUID> {

    List<Beneficiary> findByUserIdOrderByNameAsc(UUID userId);

    Optional<Beneficiary> findByUserIdAndAccountNumber(UUID userId, String accountNumber);

    boolean existsByUserIdAndAccountNumber(UUID userId, String accountNumber);

    long countByUserId(UUID userId);
}

