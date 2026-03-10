package com.amenbank.banking_webapp.repository;

import com.amenbank.banking_webapp.model.LoanProduct;
import com.amenbank.banking_webapp.model.CreditRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LoanProductRepository extends JpaRepository<LoanProduct, UUID> {

    Optional<LoanProduct> findByCode(String code);

    List<LoanProduct> findByIsActiveTrueOrderByName();

    List<LoanProduct> findByCreditTypeAndIsActiveTrue(CreditRequest.CreditType creditType);

    boolean existsByCode(String code);
}

