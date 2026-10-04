package com.misfinanzas.web;

import com.misfinanzas.dto.BulkCategoryUpdate;
import com.misfinanzas.dto.BulkIds;
import com.misfinanzas.dto.TransactionDto;
import com.misfinanzas.dto.TransactionPage;
import com.misfinanzas.entity.Transaction;
import com.misfinanzas.repository.TransactionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static com.misfinanzas.repository.TransactionSpecs.*;

@RestController
@RequestMapping("/api")
public class TransactionController {

    private static final Pattern MONTH_PATTERN = Pattern.compile("\\d{4}-\\d{2}");

    private final TransactionRepository transactionRepository;

    public TransactionController(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    /** API sort key -> entity property. A whitelist: the key must never reach the query unchecked. */
    private static final Map<String, String> SORTABLE = Map.of(
            "date", "date",
            "accounting_month", "accountingMonth",
            "description", "description",
            "account", "account",
            "category", "category",
            "amount", "amount");
    /** Text columns sort case-insensitively (SQLite's default puts every capital before every lowercase). */
    private static final Set<String> TEXT_SORTS = Set.of("description", "account", "category");
    private static final int MAX_PAGE_SIZE = 200;

    @GetMapping("/transactions")
    public TransactionPage list(@RequestParam(required = false) String month,
                                @RequestParam(required = false) String month_from,
                                @RequestParam(required = false) String month_to,
                                @RequestParam(required = false) String category,
                                @RequestParam(required = false) String account,
                                @RequestParam(required = false) String search,
                                @RequestParam(required = false) String kind,
                                @RequestParam(required = false) BigDecimal amount_min,
                                @RequestParam(required = false) BigDecimal amount_max,
                                @RequestParam(defaultValue = "date") String sort,
                                @RequestParam(defaultValue = "desc") String dir,
                                @RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "50") int size) {
        String property = SORTABLE.get(sort);
        if (property == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Campo de ordenación inválido: " + sort);
        }
        if (!dir.equals("asc") && !dir.equals("desc")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Dirección de ordenación inválida, usa asc o desc");
        }
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Paginación inválida: page >= 0 y size entre 1 y " + MAX_PAGE_SIZE);
        }

        // a range of accounting months, as the dashboard's periods are (both ends or neither)
        if ((month_from == null) != (month_to == null)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Indica el mes inicial y el final del periodo");
        }
        if (month_from != null && !(MONTH_PATTERN.matcher(month_from).matches() && MONTH_PATTERN.matcher(month_to).matches())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Formato de mes inválido, usa AAAA-MM");
        }
        if (month_from != null && month_from.compareTo(month_to) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "El mes inicial no puede ser posterior al final");
        }

        if (kind != null && !kind.equals("expense") && !kind.equals("income")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Tipo inválido, usa expense o income");
        }
        // amounts are sizes in euros, without sign: the sign is what `kind` is for
        if (!isValidAmount(amount_min) || !isValidAmount(amount_max)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "El importe del filtro debe ser un número positivo");
        }
        if (amount_min != null && amount_max != null && amount_min.compareTo(amount_max) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "El importe mínimo no puede ser mayor que el máximo");
        }

        Sort.Order order = dir.equals("asc") ? Sort.Order.asc(property) : Sort.Order.desc(property);
        if (TEXT_SORTS.contains(sort)) order = order.ignoreCase();
        // id last: without a unique tie-breaker rows with equal values can swap between pages
        Sort ordering = Sort.by(order, Sort.Order.desc("id"));

        Specification<Transaction> spec = combine(
                monthEquals(month), accountingMonthBetween(month_from, month_to), categoryEquals(category),
                accountEquals(account), descriptionContains(search), kindEquals(kind),
                amountBetween(amount_min, amount_max));
        Page<Transaction> result = transactionRepository.findAll(spec, PageRequest.of(page, size, ordering));
        return new TransactionPage(result.map(TransactionDto::from).getContent(),
                result.getTotalElements(), page, size, result.getTotalPages());
    }

    /** No filter, or an amount of zero or more ("NaN" or "Infinity" never get here: they aren't BigDecimals). */
    private static boolean isValidAmount(BigDecimal amount) {
        return amount == null || amount.signum() >= 0;
    }

    @PatchMapping("/transactions/{id}")
    public Map<String, Object> update(@PathVariable Long id,
                                       @RequestParam(required = false) String category,
                                       @RequestParam(required = false) String description,
                                       @RequestParam(required = false) String accounting_month) {
        Transaction t = transactionRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No encontrado"));
        String accountingMonth = accounting_month;
        if (category != null) t.setCategory(category);
        if (description != null) t.setDescription(description);
        if (accountingMonth != null) {
            if (!MONTH_PATTERN.matcher(accountingMonth).matches()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Formato de mes inválido, usa AAAA-MM");
            }
            t.setAccountingMonth(accountingMonth);
        }
        transactionRepository.save(t);
        return Map.of("ok", true);
    }

    @DeleteMapping("/transactions/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        Transaction t = transactionRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No encontrado"));
        transactionRepository.delete(t);
        return Map.of("ok", true);
    }

    @PostMapping("/transactions/bulk-category")
    public Map<String, Object> bulkCategory(@RequestBody BulkCategoryUpdate payload) {
        if (payload.ids() == null || payload.ids().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "No se han indicado transacciones");
        }
        List<Transaction> txs = transactionRepository.findAllById(payload.ids());
        txs.forEach(t -> t.setCategory(payload.category()));
        transactionRepository.saveAll(txs);
        return Map.of("updated", txs.size());
    }

    @PostMapping("/transactions/bulk-delete")
    public Map<String, Object> bulkDelete(@RequestBody BulkIds payload) {
        if (payload.ids() == null || payload.ids().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "No se han indicado transacciones");
        }
        List<Transaction> txs = transactionRepository.findAllById(payload.ids());
        transactionRepository.deleteAll(txs);
        return Map.of("deleted", txs.size());
    }

    @GetMapping("/accounts")
    public List<String> accounts() {
        return transactionRepository.findDistinctAccounts();
    }
}
