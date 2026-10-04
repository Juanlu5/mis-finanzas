package com.misfinanzas.web;

import com.misfinanzas.entity.Category;
import com.misfinanzas.entity.Rule;
import com.misfinanzas.entity.Transaction;
import com.misfinanzas.repository.CategoryRepository;
import com.misfinanzas.repository.RuleRepository;
import com.misfinanzas.repository.TransactionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/categories")
public class CategoryController {

    private static final String UNCATEGORIZED = "Sin categorizar";

    private final CategoryRepository categoryRepository;
    private final TransactionRepository transactionRepository;
    private final RuleRepository ruleRepository;

    public CategoryController(CategoryRepository categoryRepository, TransactionRepository transactionRepository,
                               RuleRepository ruleRepository) {
        this.categoryRepository = categoryRepository;
        this.transactionRepository = transactionRepository;
        this.ruleRepository = ruleRepository;
    }

    public record CategoryDto(String name, String kind) {
        static CategoryDto from(Category c) {
            return new CategoryDto(c.getName(), c.getKind());
        }
    }

    @GetMapping
    public List<CategoryDto> list() {
        return categoryRepository.findAllByOrderByNameAsc().stream().map(CategoryDto::from).toList();
    }

    @PostMapping
    public Map<String, Object> add(@RequestParam String name, @RequestParam(defaultValue = "expense") String kind) {
        if (categoryRepository.findByName(name).isPresent()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Ya existe esa categoría");
        }
        Category category = new Category();
        category.setName(name);
        category.setKind(kind);
        categoryRepository.save(category);
        return Map.of("ok", true);
    }

    // Deleting also moves the category's transactions back to "Sin categorizar" and removes its rules
    // (otherwise imports would keep assigning a category that no longer exists).
    // The name goes in a query parameter because Tomcat rejects an encoded "/" (%2F) in a path, and a seeded
    // category is called "Nómina / Ingresos". The path form is kept for names without a slash.
    @DeleteMapping
    @Transactional
    public Map<String, Object> deleteByName(@RequestParam String name) {
        return deleteCategory(name);
    }

    @DeleteMapping("/{name}")
    @Transactional
    public Map<String, Object> delete(@PathVariable String name) {
        return deleteCategory(name);
    }

    private Map<String, Object> deleteCategory(String name) {
        if (UNCATEGORIZED.equals(name)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "No se puede eliminar la categoría «Sin categorizar»");
        }
        Optional<Category> category = categoryRepository.findByName(name);
        if (category.isEmpty()) {
            return Map.of("ok", true, "reassigned", 0, "rules_deleted", 0);
        }
        List<Transaction> transactions = transactionRepository.findByCategory(name);
        transactions.forEach(t -> t.setCategory(UNCATEGORIZED));
        transactionRepository.saveAll(transactions);
        List<Rule> rules = ruleRepository.findByCategory(name);
        ruleRepository.deleteAll(rules);
        categoryRepository.delete(category.get());
        return Map.of("ok", true, "reassigned", transactions.size(), "rules_deleted", rules.size());
    }
}
