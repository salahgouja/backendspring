package com.amenbank.banking_webapp.repository;

import com.amenbank.banking_webapp.model.RateRevision;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface RateRevisionRepository extends JpaRepository<RateRevision, UUID> {

    List<RateRevision> findByLoanContractIdOrderByEffectiveDateDesc(UUID loanContractId);
}

