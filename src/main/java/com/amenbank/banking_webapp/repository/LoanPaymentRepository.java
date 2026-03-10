package com.amenbank.banking_webapp.repository;

import com.amenbank.banking_webapp.model.LoanPayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface LoanPaymentRepository extends JpaRepository<LoanPayment, UUID> {

    List<LoanPayment> findByLoanContractIdOrderByPaymentDateDesc(UUID loanContractId);
}

