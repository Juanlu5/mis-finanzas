package com.misfinanzas.dto;

import java.util.List;

/** One page of transactions plus the size of the whole filtered result, so the UI can paginate. */
public record TransactionPage(List<TransactionDto> items, long total, int page, int size, int pages) {
}
