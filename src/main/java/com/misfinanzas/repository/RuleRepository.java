package com.misfinanzas.repository;

import com.misfinanzas.entity.Rule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RuleRepository extends JpaRepository<Rule, Long> {
    List<Rule> findAllByOrderByKeywordAsc();

    List<Rule> findByCategory(String category);
}
