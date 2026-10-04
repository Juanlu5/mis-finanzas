package com.misfinanzas.repository;

import com.misfinanzas.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface TransactionRepository
        extends JpaRepository<Transaction, Long>, JpaSpecificationExecutor<Transaction> {

    java.util.Optional<Transaction> findByDedupeKey(String dedupeKey);

    java.util.List<Transaction> findByDedupeKeyIn(java.util.Collection<String> dedupeKeys);

    @org.springframework.data.jpa.repository.Query(
            "select distinct t.account from Transaction t order by t.account")
    java.util.List<String> findDistinctAccounts();

    @org.springframework.data.jpa.repository.Query(
            "select distinct t.accountingMonth from Transaction t where t.accountingMonth is not null " +
            "order by t.accountingMonth desc")
    java.util.List<String> findDistinctAccountingMonths();

    long countByCategory(String category);

    java.util.List<Transaction> findByCategory(String category);

    java.util.List<Transaction> findByDateGreaterThanEqual(java.time.LocalDate from);
}
