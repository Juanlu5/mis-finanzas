package com.misfinanzas.service;

import com.misfinanzas.entity.Rule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CategorizationServiceTest {

    private final CategorizationService service = new CategorizationService();

    private static Rule rule(String keyword, String category) {
        Rule rule = new Rule();
        rule.setKeyword(keyword);
        rule.setCategory(category);
        return rule;
    }

    @Test
    void returnsCategoryOfMatchingRule() {
        List<Rule> rules = List.of(rule("mercadona", "Supermercado"));

        assertThat(service.applyRules("COMPRA TARJ. MERCADONA 0123", rules)).isEqualTo("Supermercado");
    }

    @Test
    void matchingIsCaseInsensitiveOnBothSides() {
        List<Rule> rules = List.of(rule("NETFLIX", "Suscripciones"));

        assertThat(service.applyRules("netflix.com", rules)).isEqualTo("Suscripciones");
    }

    @Test
    void returnsNullWhenNothingMatchesOrThereAreNoRules() {
        assertThat(service.applyRules("Transferencia a Pepe", List.of(rule("lidl", "Supermercado")))).isNull();
        assertThat(service.applyRules("Transferencia a Pepe", List.of())).isNull();
    }

    private static Rule rule(long id, String keyword, String category) {
        Rule rule = rule(keyword, category);
        rule.setId(id);
        return rule;
    }

    // "Most specific wins": the longest matching keyword decides, whatever the order of the list
    // (the database returns rows in no guaranteed order, and a rule added later must still be able to win).
    @Test
    void longestMatchingKeywordWinsRegardlessOfListOrder() {
        List<Rule> specificFirst = List.of(rule("uber eats", "Restaurantes"), rule("uber", "Transporte"));
        List<Rule> genericFirst = List.of(rule("uber", "Transporte"), rule("uber eats", "Restaurantes"));

        assertThat(service.applyRules("UBER EATS MADRID", specificFirst)).isEqualTo("Restaurantes");
        assertThat(service.applyRules("UBER EATS MADRID", genericFirst)).isEqualTo("Restaurantes");
        assertThat(service.applyRules("UBER *TRIP", genericFirst)).isEqualTo("Transporte");
    }

    @Test
    void equalLengthKeywordsFallBackToTheOlderRuleById() {
        List<Rule> newerListedFirst = List.of(rule(9, "metro", "Transporte"), rule(2, "cafes", "Restaurantes"));

        // "metro" and "cafes" are both 5 chars and both match: the lower id (older rule) wins
        assertThat(service.applyRules("cafes metro", newerListedFirst)).isEqualTo("Restaurantes");
    }

    @Test
    void ruleWithoutIdKeepsListOrderOnTies() {
        List<Rule> rules = List.of(rule("metro", "Transporte"), rule("cafes", "Restaurantes"));

        assertThat(service.applyRules("cafes metro", rules)).isEqualTo("Transporte");
    }

    @Test
    void doesNotReorderOrModifyTheCallersList() {
        List<Rule> rules = new java.util.ArrayList<>(List.of(rule("uber", "Transporte"), rule("uber eats", "Restaurantes")));

        service.applyRules("UBER EATS", rules);

        assertThat(rules).extracting(Rule::getKeyword).containsExactly("uber", "uber eats");
    }

    @Test
    void accentsAreNotNormalized() {
        // "nomina" and "nómina" are different keywords; that's why the seed has both.
        List<Rule> rules = List.of(rule("nomina", "Nómina / Ingresos"));

        assertThat(service.applyRules("Nómina marzo", rules)).isNull();
        assertThat(service.applyRules("Nomina marzo", rules)).isEqualTo("Nómina / Ingresos");
    }

    @Test
    void blankKeywordsAreIgnoredInsteadOfMatchingEverything() {
        List<Rule> rules = List.of(rule("", "Ocio"), rule("   ", "Ocio"), rule("lidl", "Supermercado"));

        assertThat(service.applyRules("LIDL 123", rules)).isEqualTo("Supermercado");
        assertThat(service.applyRules("Algo cualquiera", rules)).isNull();
    }

    @Test
    void keywordWithSpacesMatchesWholeWordsAtTheEdgesOfTheDescription() {
        List<Rule> rules = List.of(rule(" dia ", "Supermercado"));

        assertThat(service.applyRules("DIA", rules)).isEqualTo("Supermercado");
        assertThat(service.applyRules("DIA SUPERMERCADOS", rules)).isEqualTo("Supermercado");
        assertThat(service.applyRules("COMPRA TARJ. DIA 1234", rules)).isEqualTo("Supermercado");
        assertThat(service.applyRules("MEDIA MARKT VALENCIA", rules)).isNull();
    }
}
