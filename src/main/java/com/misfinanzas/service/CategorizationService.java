package com.misfinanzas.service;

import com.misfinanzas.entity.Rule;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Service
public class CategorizationService {

    /**
     * Rules are tried most specific first: the longest keyword wins ("uber eats" beats "uber"), and
     * between equally long keywords the older rule (lower id) does. Priority never depends on the order
     * the database happens to return rows in, and a rule added later can still override a generic one.
     */
    private static final Comparator<Rule> BY_SPECIFICITY = Comparator
            .comparingInt((Rule r) -> r.getKeyword().strip().length()).reversed()
            .thenComparing(Rule::getId, Comparator.nullsLast(Comparator.naturalOrder()));

    /**
     * Returns the category of the most specific rule whose keyword is found in the description, or null.
     * The description is padded with spaces so a keyword like " dia " matches the whole word
     * (also at the start or end) without matching inside "media markt". Blank keywords are
     * ignored: an empty string is contained in every description and would swallow everything.
     * The caller's list is not modified.
     */
    public String applyRules(String description, List<Rule> rules) {
        String descLower = " " + description.toLowerCase() + " ";
        return rules.stream()
                .filter(rule -> rule.getKeyword() != null && !rule.getKeyword().isBlank())
                .sorted(BY_SPECIFICITY)
                .filter(rule -> descLower.contains(rule.getKeyword().toLowerCase()))
                .map(Rule::getCategory)
                .findFirst()
                .orElse(null);
    }
}
