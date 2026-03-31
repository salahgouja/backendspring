package com.amenbank.banking_webapp.repository;

import com.amenbank.banking_webapp.model.LoanPayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LoanPaymentRepository extends JpaRepository<LoanPayment, UUID> {

    List<LoanPayment> findByLoanContractIdOrderByPaymentDateDesc(UUID loanContractId);

    @Query("""
            SELECT p
            FROM LoanPayment p
            JOIN FETCH p.loanContract lc
            JOIN FETCH lc.product
            JOIN FETCH lc.user u
            LEFT JOIN FETCH u.agency
            WHERE p.id = :paymentId
            """)
    Optional<LoanPayment> findDetailedById(@Param("paymentId") UUID paymentId);
}
