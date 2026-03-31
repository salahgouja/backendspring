package com.amenbank.banking_webapp.repository;

import com.amenbank.banking_webapp.model.LoanContract;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LoanContractRepository extends JpaRepository<LoanContract, UUID> {

    Optional<LoanContract> findByContractNumber(String contractNumber);

    List<LoanContract> findByUserIdOrderByCreatedAtDesc(UUID userId);

    List<LoanContract> findByStatusOrderByCreatedAtDesc(LoanContract.LoanStatus status);

    /** All active variable-rate loans for a given reference index */
    @Query("SELECT lc FROM LoanContract lc JOIN lc.product p " +
           "WHERE p.rateType = 'VARIABLE' AND p.referenceIndex = :indexName " +
           "AND lc.status IN ('ACTIVE', 'OVERDUE') ORDER BY lc.createdAt")
    List<LoanContract> findActiveVariableRateLoans(@Param("indexName") String indexName);

    /** All active loans that need daily accrual */
    @Query("SELECT lc FROM LoanContract lc WHERE lc.status IN ('ACTIVE', 'OVERDUE')")
    List<LoanContract> findAllActiveLoans();

    boolean existsByContractNumber(String contractNumber);

    /** GAP-C: Find loan contract linked to a credit request */
    Optional<LoanContract> findByCreditRequestId(UUID creditRequestId);
}

