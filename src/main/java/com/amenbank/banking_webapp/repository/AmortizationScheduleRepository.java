package com.amenbank.banking_webapp.repository;

import com.amenbank.banking_webapp.model.AmortizationSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AmortizationScheduleRepository extends JpaRepository<AmortizationSchedule, UUID> {

    List<AmortizationSchedule> findByLoanContractIdOrderByInstallmentNumberAsc(UUID loanContractId);

    /** Next unpaid installment for a loan */
    @Query("SELECT a FROM AmortizationSchedule a WHERE a.loanContract.id = :loanId " +
           "AND a.status IN ('PENDING', 'DUE', 'OVERDUE', 'GRACE') ORDER BY a.installmentNumber ASC LIMIT 1")
    Optional<AmortizationSchedule> findNextDue(@Param("loanId") UUID loanId);

    /** All overdue installments */
    @Query("SELECT a FROM AmortizationSchedule a WHERE a.loanContract.id = :loanId " +
           "AND a.status = 'OVERDUE' ORDER BY a.installmentNumber ASC")
    List<AmortizationSchedule> findOverdueByLoanId(@Param("loanId") UUID loanId);

    /** All installments due on or before a specific date */
    @Query("SELECT a FROM AmortizationSchedule a WHERE a.dueDate <= :date AND a.status = 'PENDING'")
    List<AmortizationSchedule> findDueOnOrBefore(@Param("date") LocalDate date);

    /** All installments due on a specific date (for reminders) — GAP-I */
    @Query("SELECT a FROM AmortizationSchedule a WHERE a.dueDate = :date AND a.status = 'PENDING'")
    List<AmortizationSchedule> findDueOnDate(@Param("date") LocalDate date);

    /** Remaining unpaid schedule lines for recalculation */
    @Query("SELECT a FROM AmortizationSchedule a WHERE a.loanContract.id = :loanId " +
           "AND a.status IN ('PENDING', 'DUE', 'GRACE') ORDER BY a.installmentNumber ASC")
    List<AmortizationSchedule> findRemainingByLoanId(@Param("loanId") UUID loanId);
}

