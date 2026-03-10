package com.amenbank.banking_webapp.repository;

import com.amenbank.banking_webapp.model.InterestAccrual;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface InterestAccrualRepository extends JpaRepository<InterestAccrual, UUID> {

    List<InterestAccrual> findByLoanContractIdOrderByAccrualDateDesc(UUID loanContractId);

    /** Sum of accrued interest for a loan between two dates */
    @Query("SELECT COALESCE(SUM(a.interestAmount), 0) FROM InterestAccrual a " +
           "WHERE a.loanContract.id = :loanId AND a.accrualDate BETWEEN :from AND :to AND a.isPenalty = false")
    BigDecimal sumInterestBetween(@Param("loanId") UUID loanId,
                                  @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Check if accrual already exists for a date */
    boolean existsByLoanContractIdAndAccrualDate(UUID loanContractId, LocalDate accrualDate);
}

