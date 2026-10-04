package com.misfinanzas.web;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.misfinanzas.dto.ParsedTable;
import com.misfinanzas.service.ImportService;
import com.misfinanzas.service.TransactionImportService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/import")
public class ImportController {

    private final ImportService importService;
    private final TransactionImportService transactionImportService;
    private final ObjectMapper objectMapper;

    public ImportController(ImportService importService, TransactionImportService transactionImportService,
                             ObjectMapper objectMapper) {
        this.importService = importService;
        this.transactionImportService = transactionImportService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/preview")
    public Map<String, Object> preview(@RequestParam("file") MultipartFile file) throws IOException {
        ParsedTable table = readFile(file);
        Map<String, String> mapping = importService.guessMapping(table.columns());
        List<Map<String, String>> sample = table.rows().stream().limit(8).toList();
        return Map.of(
                "columns", table.columns(),
                "mapping", mapping,
                "sample", sample,
                "rows", table.rows().size());
    }

    @PostMapping("/confirm")
    public Map<String, Object> confirm(@RequestParam("file") MultipartFile file,
                                        @RequestParam("account") String account,
                                        @RequestParam("mapping") String mappingJson) throws IOException {
        ParsedTable table = readFile(file);
        Map<String, String> mapping;
        try {
            mapping = objectMapper.readValue(mappingJson, new TypeReference<Map<String, String>>() {
            });
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Mapeo de columnas inválido");
        }

        ImportService.BuildResult built = importService.buildRows(table, mapping, account);
        TransactionImportService.ImportResult result = transactionImportService.persist(built.rows());

        return Map.of(
                "added", result.added(),
                "duplicates", result.duplicates(),
                "skipped", built.errors(),
                "total_rows", table.rows().size(),
                "balances_filled", result.balancesFilled());
    }

    private ParsedTable readFile(MultipartFile file) throws IOException {
        try {
            return importService.readAny(file.getOriginalFilename(), file.getBytes());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
