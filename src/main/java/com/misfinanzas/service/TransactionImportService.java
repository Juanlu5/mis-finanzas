package com.misfinanzas.service;

import com.misfinanzas.dto.ImportRow;
import com.misfinanzas.entity.Rule;
import com.misfinanzas.entity.Transaction;
import com.misfinanzas.repository.RuleRepository;
import com.misfinanzas.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Turns a batch of {@link ImportRow}s into stored transactions: batched dedupe lookup, categorization,
 * and save, all in one transaction so a failure partway through leaves nothing behind (see
 * ImportController's history for why {@code @Transactional} is here). Shared by a manual file import
 * (ImportController) and an automatic bank sync (BankSyncController) — both build rows their own way,
 * then hand them here so duplicate detection and categorization aren't reimplemented twice.
 */
@Service
public class TransactionImportService {

    private final ImportService importService;
    private final RuleRepository ruleRepository;
    private final TransactionRepository transactionRepository;
    private final CategorizationService categorizationService;

    public TransactionImportService(ImportService importService, RuleRepository ruleRepository,
                                     TransactionRepository transactionRepository,
                                     CategorizationService categorizationService) {
        this.importService = importService;
        this.ruleRepository = ruleRepository;
        this.transactionRepository = transactionRepository;
        this.categorizationService = categorizationService;
    }

    public record ImportResult(int added, int duplicates, int balancesFilled) {
    }

    /** Name of the description a row is stored under, as opposed to the alternatives it's also matched by. */
    private static final String CANONICAL = "canonical";
    /** SQLite caps the number of bound parameters per statement (999 on older builds). */
    private static final int LOOKUP_CHUNK = 500;

    @Transactional
    public ImportResult persist(List<ImportRow> rows) {
        List<Rule> rules = ruleRepository.findAll();

        // Every row is looked up under several keys (its own description, then the alternatives that an
        // import with another choice of detail column would have used), so all of them are computed and
        // fetched up front. Looking only at what was stored BEFORE this import also means two rows of the
        // same batch can never be mistaken for each other's duplicates.
        Map<String, Map<String, Integer>> seenByKind = new HashMap<>();
        List<List<String>> candidateKeys = new ArrayList<>(rows.size());
        Set<String> everyKey = new HashSet<>();
        for (ImportRow row : rows) {
            List<String> keys = new ArrayList<>();
            keys.add(keyFor(seenByKind, CANONICAL, row, row.description()));
            row.alternativeDescriptions().forEach((kind, description) -> keys.add(keyFor(seenByKind, kind, row, description)));
            candidateKeys.add(keys);
            everyKey.addAll(keys);
        }
        Map<String, Transaction> stored = findStored(everyKey);

        int added = 0, duplicates = 0, balancesFilled = 0;
        for (int i = 0; i < rows.size(); i++) {
            ImportRow row = rows.get(i);
            List<String> keys = candidateKeys.get(i);
            Transaction existing = keys.stream().map(stored::get).filter(Objects::nonNull).findFirst().orElse(null);
            if (existing != null) {
                duplicates++;
                if (existing.getBalance() == null && row.balance() != null) {
                    existing.setBalance(row.balance());
                    transactionRepository.save(existing);
                    balancesFilled++;
                }
                continue;
            }
            String category = categorizationService.applyRules(row.description(), rules);
            Transaction t = new Transaction();
            t.setDate(row.date());
            t.setDescription(row.description());
            t.setAmount(row.amount());
            t.setAccount(row.account());
            t.setCategory(category != null ? category : "Sin categorizar");
            t.setDedupeKey(keys.get(0));
            t.setAccountingMonth(Transaction.monthKey(row.date()));
            t.setBalance(row.balance());
            transactionRepository.save(t);
            added++;
        }

        return new ImportResult(added, duplicates, balancesFilled);
    }

    /**
     * The key of this row under one description. The occurrence number counts identical rows within the
     * batch PER kind of description: what makes two rows "identical" depends on the description, and it
     * has to be counted the way the earlier import that stored them counted it.
     */
    private String keyFor(Map<String, Map<String, Integer>> seenByKind, String kind, ImportRow row, String description) {
        String base = importService.makeDedupeKey(row.account(), row.date(), description, row.amount());
        int occurrence = seenByKind.computeIfAbsent(kind, k -> new HashMap<>()).merge(base, 1, Integer::sum) - 1;
        return occurrence == 0 ? base
                : importService.makeDedupeKey(row.account(), row.date(), description, row.amount(), occurrence);
    }

    private Map<String, Transaction> findStored(Set<String> keys) {
        Map<String, Transaction> stored = new HashMap<>();
        List<String> all = new ArrayList<>(keys);
        for (int from = 0; from < all.size(); from += LOOKUP_CHUNK) {
            List<String> chunk = all.subList(from, Math.min(from + LOOKUP_CHUNK, all.size()));
            for (Transaction t : transactionRepository.findByDedupeKeyIn(chunk)) stored.put(t.getDedupeKey(), t);
        }
        return stored;
    }
}
