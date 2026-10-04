package com.misfinanzas.web;

import com.misfinanzas.service.RecurringService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The "Gastos fijos" view. A group's key goes as a form or query parameter, never in the path: it holds a
 * category name, which may contain "/" (see CategoryController).
 */
@RestController
@RequestMapping("/api/recurring")
public class RecurringController {

    private final RecurringService service;

    public RecurringController(RecurringService service) {
        this.service = service;
    }

    @GetMapping
    public RecurringService.Report report() {
        return service.report();
    }

    @PostMapping("/hidden")
    public Map<String, Object> hide(@RequestParam String key) {
        service.hide(key);
        return Map.of("ok", true);
    }

    @DeleteMapping("/hidden")
    public Map<String, Object> unhide(@RequestParam String key) {
        service.unhide(key);
        return Map.of("ok", true);
    }
}
