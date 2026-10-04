package com.misfinanzas.web;

import com.misfinanzas.entity.Rule;
import com.misfinanzas.entity.Transaction;
import com.misfinanzas.repository.RuleRepository;
import com.misfinanzas.repository.TransactionRepository;
import com.misfinanzas.service.CategorizationService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@RestController
@RequestMapping("/api/rules")
public class RuleController {

    private final RuleRepository ruleRepository;
    private final TransactionRepository transactionRepository;
    private final CategorizationService categorizationService;

    public RuleController(RuleRepository ruleRepository, TransactionRepository transactionRepository,
                           CategorizationService categorizationService) {
        this.ruleRepository = ruleRepository;
        this.transactionRepository = transactionRepository;
        this.categorizationService = categorizationService;
    }

    public record RuleDto(Long id, String keyword, String category) {
        static RuleDto from(Rule r) {
            return new RuleDto(r.getId(), r.getKeyword(), r.getCategory());
        }
    }

    @GetMapping
    public List<RuleDto> list() {
        return ruleRepository.findAllByOrderByKeywordAsc().stream().map(RuleDto::from).toList();
    }

    /**
     * Creates the rule. With {@code apply}, the movements it now decides (see {@link #preview}'s
     * {@code would_change}) are moved to its category too, whatever category they had; without it, only future
     * imports and the "apply rules" button (which touches just "Sin categorizar") use it.
     */
    @PostMapping
    public Map<String, Object> add(@RequestParam String keyword, @RequestParam String category,
                                   @RequestParam(defaultValue = "false") boolean apply) {
        requireKeyword(keyword);
        // worked out before saving: the rule being created is the candidate in it
        Impact impact = apply ? impact(keyword, category) : null;
        Rule rule = new Rule();
        rule.setKeyword(keyword);
        rule.setCategory(category);
        ruleRepository.save(rule);
        if (impact == null) return Map.of("ok", true, "updated", 0);
        impact.moving().forEach(t -> t.setCategory(category));
        transactionRepository.saveAll(impact.moving());
        return Map.of("ok", true, "updated", impact.moving().size());
    }

    /**
     * What creating the rule {@code keyword} → {@code category} would do, without creating it: how many
     * movements contain the keyword ({@code matches}), how many of those are already in that category
     * ({@code already}), how many it would move there ({@code would_change}, counted per current category in
     * {@code from}) and how many would stay put because a more specific rule wins them ({@code other_rule}).
     * {@code existing_rule} is the category of a rule with this very keyword, if there is one: the new rule
     * could never beat it (between equally long keywords the older rule wins).
     */
    @GetMapping("/preview")
    public Map<String, Object> preview(@RequestParam String keyword, @RequestParam String category) {
        requireKeyword(keyword);
        Impact impact = impact(keyword, category);
        Map<String, Integer> from = new TreeMap<>();
        impact.moving().forEach(t -> from.merge(t.getCategory(), 1, Integer::sum));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("matches", impact.matches());
        body.put("already", impact.already());
        body.put("would_change", impact.moving().size());
        body.put("from", from);
        body.put("other_rule", impact.otherRule());
        body.put("existing_rule", ruleRepository.findAll().stream()
                .filter(r -> r.getKeyword().equalsIgnoreCase(keyword))
                .map(Rule::getCategory)
                .findFirst().orElse(null));
        return body;
    }

    private record Impact(int matches, int already, List<Transaction> moving, int otherRule) {
    }

    // Uses CategorizationService itself, so the preview can't drift from how rules really match and rank.
    private Impact impact(String keyword, String category) {
        Rule candidate = new Rule();
        candidate.setKeyword(keyword);
        candidate.setCategory(category);
        List<Rule> rules = new ArrayList<>(ruleRepository.findAll());
        rules.add(candidate); // no id yet: it loses ties with existing rules, as it will once saved
        int matches = 0, already = 0, otherRule = 0;
        List<Transaction> moving = new ArrayList<>();
        for (Transaction t : transactionRepository.findAll()) {
            if (categorizationService.applyRules(t.getDescription(), List.of(candidate)) == null) continue;
            matches++;
            if (category.equals(t.getCategory())) already++;
            else if (category.equals(categorizationService.applyRules(t.getDescription(), rules))) moving.add(t);
            else otherRule++;
        }
        return new Impact(matches, already, moving, otherRule);
    }

    private static void requireKeyword(String keyword) {
        if (keyword.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "La palabra clave no puede estar vacía");
        }
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        ruleRepository.findById(id).ifPresent(ruleRepository::delete);
        return Map.of("ok", true);
    }

    @PostMapping("/apply-all")
    public Map<String, Object> applyAll() {
        List<Rule> rules = ruleRepository.findAll();
        List<Transaction> pending = transactionRepository.findByCategory("Sin categorizar");
        int updated = 0;
        for (Transaction t : pending) {
            String category = categorizationService.applyRules(t.getDescription(), rules);
            if (category != null) {
                t.setCategory(category);
                updated++;
            }
        }
        transactionRepository.saveAll(pending);
        return Map.of("updated", updated);
    }
}
