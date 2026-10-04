package com.misfinanzas.web;

import com.misfinanzas.dto.ImportRow;
import com.misfinanzas.service.EnableBankingClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static com.misfinanzas.TestMoney.eur;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * EnableBankingClient is entirely mocked (never a real call, never a real JWT/private key needed) so these
 * tests only check that BankSyncController wires status/link/callback/import correctly, the same way
 * ImportApiTest checks ImportController without ever touching a real bank.
 */
class BankSyncApiTest extends ApiTestBase {

    @MockitoBean
    private EnableBankingClient enableBankingClient;

    @Test
    void statusReportsNotLinkedByDefault() throws Exception {
        when(enableBankingClient.status()).thenReturn(new EnableBankingClient.Status(false, null, null));

        mockMvc.perform(get("/api/bank-sync/status").with(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linked").value(false))
                .andExpect(jsonPath("$.expired").value(false));
    }

    @Test
    void statusReportsAnExpiredLinkWithItsDate() throws Exception {
        when(enableBankingClient.status())
                .thenReturn(new EnableBankingClient.Status(false, "BBVA", Instant.parse("2026-09-01T00:00:00Z")));

        mockMvc.perform(get("/api/bank-sync/status").with(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linked").value(false))
                .andExpect(jsonPath("$.expired").value(true))
                .andExpect(jsonPath("$.valid_until").value("2026-09-01T00:00:00Z"));
    }

    @Test
    void statusReportsLinkedWithTheAspspName() throws Exception {
        when(enableBankingClient.status())
                .thenReturn(new EnableBankingClient.Status(true, "BBVA", Instant.parse("2026-09-01T00:00:00Z")));

        mockMvc.perform(get("/api/bank-sync/status").with(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linked").value(true))
                .andExpect(jsonPath("$.aspsp").value("BBVA"))
                .andExpect(jsonPath("$.expired").value(false));
    }

    @Test
    void linkReturnsTheAuthorizationUrlAndPassesACallbackAddress() throws Exception {
        when(enableBankingClient.startAuthorization(anyString())).thenReturn("https://bbva.example/authorize");

        mockMvc.perform(post("/api/bank-sync/link").with(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://bbva.example/authorize"));

        verify(enableBankingClient).startAuthorization(argThat(url -> url.endsWith("/api/bank-sync/callback")));
    }

    @Test
    void unlinkRevokesTheLinkAndReportsNotLinked() throws Exception {
        mockMvc.perform(delete("/api/bank-sync/link").with(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linked").value(false));

        verify(enableBankingClient).unlink();
    }

    @Test
    void aFailedUnlinkSurfacesTheBanksError() throws Exception {
        doThrow(new ApiException(HttpStatus.BAD_GATEWAY, "No se ha podido desvincular la cuenta"))
                .when(enableBankingClient).unlink();

        mockMvc.perform(delete("/api/bank-sync/link").with(auth()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value("No se ha podido desvincular la cuenta"));
    }

    @Test
    void callbackRedirectsToTheImportViewOnSuccess() throws Exception {
        mockMvc.perform(get("/api/bank-sync/callback").param("code", "abc").param("state", "xyz").with(auth()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/#import?bank=linked"));

        verify(enableBankingClient).completeAuthorization("abc", "xyz");
    }

    @Test
    void callbackRedirectsWithAnErrorFlagWhenAuthorizationFails() throws Exception {
        doThrow(new ApiException(HttpStatus.BAD_REQUEST, "La vinculación ha caducado"))
                .when(enableBankingClient).completeAuthorization("bad", "xyz");

        mockMvc.perform(get("/api/bank-sync/callback").param("code", "bad").param("state", "xyz").with(auth()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/#import?bank=error"));
    }

    @Test
    void anUnexpectedFailureOnCallbackStillRedirectsBackIntoTheApp() throws Exception {
        doThrow(new IllegalStateException("insert into bank_links failed"))
                .when(enableBankingClient).completeAuthorization("abc", "xyz");

        mockMvc.perform(get("/api/bank-sync/callback").param("code", "abc").param("state", "xyz").with(auth()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/#import?bank=error"));
    }

    @Test
    void callbackWithoutACodeRedirectsWithAnErrorFlag() throws Exception {
        // What the bank sends back when the user cancels or authentication fails on its site.
        mockMvc.perform(get("/api/bank-sync/callback").param("error", "access_denied").param("state", "xyz").with(auth()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/#import?bank=error"));

        verify(enableBankingClient, never()).completeAuthorization(any(), any());
    }

    @Test
    void importRangeFetchesAndPersistsRows() throws Exception {
        when(enableBankingClient.fetchTransactions(eq(LocalDate.parse("2026-03-01")), eq(LocalDate.parse("2026-03-31")), eq("Principal"), any()))
                .thenReturn(new EnableBankingClient.Fetched(
                        List.of(new ImportRow(LocalDate.parse("2026-03-15"), "COMPRA MERCADONA", eur(-45.30), "Principal", null)), 2));

        mockMvc.perform(post("/api/bank-sync/import").with(auth())
                        .contentType(APPLICATION_FORM_URLENCODED)
                        .content("date_from=2026-03-01&date_to=2026-03-31&account=Principal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added").value(1))
                .andExpect(jsonPath("$.duplicates").value(0))
                .andExpect(jsonPath("$.pending").value(2));

        assertThat(transactionRepository.findAll()).hasSize(1);
    }

    @Test
    void theDownloadTellsTheBankTheAccountHolderIsPresent() throws Exception {
        when(enableBankingClient.fetchTransactions(any(), any(), anyString(), any()))
                .thenReturn(new EnableBankingClient.Fetched(List.of(), 0));

        mockMvc.perform(post("/api/bank-sync/import").with(auth())
                        .with(request -> { request.setRemoteAddr("192.168.1.50"); return request; })
                        .header("User-Agent", "Firefox de prueba")
                        .contentType(APPLICATION_FORM_URLENCODED)
                        .content("date_from=2026-03-01&date_to=2026-03-31&account=Principal"))
                .andExpect(status().isOk());

        verify(enableBankingClient).fetchTransactions(any(), any(), anyString(),
                eq(new EnableBankingClient.Psu("192.168.1.50", "Firefox de prueba")));
    }

    @Test
    void reimportingTheSameRangeIsADuplicateNotANewRow() throws Exception {
        when(enableBankingClient.fetchTransactions(any(), any(), anyString(), any()))
                .thenReturn(new EnableBankingClient.Fetched(
                        List.of(new ImportRow(LocalDate.parse("2026-03-15"), "NOMINA", eur(1500.0), "Principal", null)), 0));

        String request = "date_from=2026-03-01&date_to=2026-03-31&account=Principal";
        mockMvc.perform(post("/api/bank-sync/import").with(auth())
                .contentType(APPLICATION_FORM_URLENCODED).content(request));

        mockMvc.perform(post("/api/bank-sync/import").with(auth())
                        .contentType(APPLICATION_FORM_URLENCODED).content(request))
                .andExpect(jsonPath("$.added").value(0))
                .andExpect(jsonPath("$.duplicates").value(1));

        assertThat(transactionRepository.findAll()).hasSize(1);
    }

    @Test
    void importRangeSurfacesAnExpiredBankSessionAsUnauthorized() throws Exception {
        when(enableBankingClient.fetchTransactions(any(), any(), anyString(), any()))
                .thenThrow(new ApiException(HttpStatus.UNAUTHORIZED,
                        "La sesión con el banco ha caducado, vuelve a vincular la cuenta."));

        mockMvc.perform(post("/api/bank-sync/import").with(auth())
                        .contentType(APPLICATION_FORM_URLENCODED)
                        .content("date_from=2026-03-01&date_to=2026-03-31&account=Principal"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("La sesión con el banco ha caducado, vuelve a vincular la cuenta."));

        assertThat(transactionRepository.findAll()).isEmpty();
    }
}
