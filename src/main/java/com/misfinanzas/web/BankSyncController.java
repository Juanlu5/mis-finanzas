package com.misfinanzas.web;

import com.misfinanzas.service.EnableBankingClient;
import com.misfinanzas.service.TransactionImportService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * Lets the user pull real BBVA movements for a date range instead of downloading and uploading a file,
 * via Enable Banking (see EnableBankingClient). Reuses TransactionImportService (the same dedupe,
 * categorization and save logic ImportController uses) so a bank-synced row and a manually imported one
 * are handled identically once they're both an ImportRow.
 */
@RestController
@RequestMapping("/api/bank-sync")
public class BankSyncController {

    private static final Logger log = LoggerFactory.getLogger(BankSyncController.class);

    private final EnableBankingClient enableBankingClient;
    private final TransactionImportService transactionImportService;
    private final boolean demo;

    public BankSyncController(EnableBankingClient enableBankingClient, TransactionImportService transactionImportService,
                              @Value("${app.demo:false}") boolean demo) {
        this.enableBankingClient = enableBankingClient;
        this.transactionImportService = transactionImportService;
        this.demo = demo;
    }

    // The demo has no bank credentials; without this it would answer that ENABLE_BANKING_APP_ID is missing.
    private void refuseInDemo() {
        if (demo) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "La demo no se conecta a ningún banco: sus movimientos son inventados.");
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        EnableBankingClient.Status status = enableBankingClient.status();
        Map<String, Object> body = new HashMap<>();
        body.put("linked", status.linked());
        body.put("aspsp", status.aspspName());
        body.put("valid_until", status.validUntil());
        // A link that ran out is still stored (with its date), so the page can say it expired rather than
        // just offer to link as if it had never been done.
        body.put("expired", !status.linked() && status.validUntil() != null);
        return body;
    }

    @PostMapping("/link")
    public Map<String, Object> link(HttpServletRequest request) {
        refuseInDemo();
        return Map.of("url", enableBankingClient.startAuthorization(callbackUrl(request)));
    }

    @DeleteMapping("/link")
    public Map<String, Object> unlink() {
        enableBankingClient.unlink();
        return Map.of("linked", false);
    }

    // The bank redirects the user's browser here after they authenticate on its own site — this is a page
    // navigation, not an XHR call, so the response has to be a redirect back into the app, not JSON. That
    // includes a cancelled or failed authentication: the bank then comes back with ?error=... and no code,
    // so neither parameter can be required, or Spring would answer that with a bare 400 page.
    @GetMapping("/callback")
    public ResponseEntity<Void> callback(@RequestParam(value = "code", required = false) String code,
                                         @RequestParam(value = "state", required = false) String state) {
        String outcome = "error";
        if (code != null && !code.isBlank() && state != null) {
            try {
                enableBankingClient.completeAuthorization(code, state);
                outcome = "linked";
            } catch (ApiException e) {
                log.warn("La vinculación con el banco no se ha completado: {}", e.getMessage());
            } catch (RuntimeException e) {
                // Anything unexpected still has to land back in the app, not on a bare error page, and be
                // in the log, since the browser only ever sees "bank=error".
                log.error("Fallo inesperado al completar la vinculación con el banco", e);
            }
        }
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create("/#import?bank=" + outcome)).build();
    }

    @PostMapping("/import")
    public Map<String, Object> importRange(@RequestParam("date_from") LocalDate dateFrom,
                                            @RequestParam("date_to") LocalDate dateTo,
                                            @RequestParam("account") String account,
                                            HttpServletRequest request) {
        refuseInDemo();
        // Every download is a button press, so the account holder is present: say so (see EnableBankingClient.Psu).
        EnableBankingClient.Psu psu = new EnableBankingClient.Psu(request.getRemoteAddr(), request.getHeader("User-Agent"));
        EnableBankingClient.Fetched fetched = enableBankingClient.fetchTransactions(dateFrom, dateTo, account, psu);
        TransactionImportService.ImportResult result = transactionImportService.persist(fetched.rows());
        return Map.of(
                "added", result.added(),
                "duplicates", result.duplicates(),
                "skipped", 0,
                "total_rows", fetched.rows().size(),
                "balances_filled", result.balancesFilled(),
                "pending", fetched.pending());
    }

    private String callbackUrl(HttpServletRequest request) {
        return request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort() + "/api/bank-sync/callback";
    }
}
