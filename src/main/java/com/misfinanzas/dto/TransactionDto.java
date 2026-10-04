package com.misfinanzas.dto;

import com.misfinanzas.entity.Transaction;

import java.math.BigDecimal;

public record TransactionDto(Long id, String date, String description, BigDecimal amount,
                              String category, String account, String accounting_month) {
    public static TransactionDto from(Transaction t) {
        return new TransactionDto(t.getId(), t.getDate().toString(), t.getDescription(), t.getAmount(),
                t.getCategory(), t.getAccount(), t.getAccountingMonth());
    }
}
