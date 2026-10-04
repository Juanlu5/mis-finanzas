package com.misfinanzas.service;

import com.misfinanzas.dto.ImportRow;
import com.misfinanzas.entity.BankLink;
import com.misfinanzas.repository.BankLinkRepository;
import com.misfinanzas.web.ApiException;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static com.misfinanzas.TestMoney.eur;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Mostly a pure test of EnableBankingClient.toImportRow (the JSON-shape-to-ImportRow mapping), with no HTTP
 * involved — the same style as ImportServiceTest/CategorizationServiceTest. fetchTransactions is tested
 * against a MockRestServiceServer (Enable Banking's JSON as text, a throw-away RSA key to sign with), so
 * paging and the pending filter are checked on the real request/response path, never a real bank.
 */
class EnableBankingClientTest {

    @TempDir
    Path tempDir;

    private EnableBankingClient.BankTransaction tx(String bookingDate, String amount, String indicator,
                                                     List<String> remittance, String balance) {
        return new EnableBankingClient.BankTransaction(bookingDate, bookingDate, bookingDate,
                new EnableBankingClient.Amount("EUR", amount), indicator, remittance,
                balance == null ? null : new EnableBankingClient.Amount("EUR", balance), "BOOK");
    }

    private EnableBankingClient.BankTransaction withStatus(String status) {
        return new EnableBankingClient.BankTransaction("2026-03-15", null, null,
                new EnableBankingClient.Amount("EUR", "1.00"), "DBIT", List.of("algo"), null, status);
    }

    @Test
    void aCreditIsPositive() {
        ImportRow row = EnableBankingClient.toImportRow(
                tx("2026-03-16", "1500.00", "CRDT", List.of("NOMINA EMPRESA"), null), "Principal");

        assertThat(row.amount()).isEqualTo(eur(1500.0));
        assertThat(row.date().toString()).isEqualTo("2026-03-16");
        assertThat(row.description()).isEqualTo("NOMINA EMPRESA");
        assertThat(row.account()).isEqualTo("Principal");
        assertThat(row.balance()).isNull();
    }

    @ParameterizedTest
    @CsvSource({"DBIT", "DBTR", "OTRO"})
    void anythingOtherThanCrdtIsNegative(String indicator) {
        ImportRow row = EnableBankingClient.toImportRow(
                tx("2026-03-15", "45.30", indicator, List.of("COMPRA MERCADONA"), null), "Principal");

        assertThat(row.amount()).isEqualTo(eur(-45.30));
    }

    @Test
    void severalRemittanceLinesAreJoinedWithSpaces() {
        ImportRow row = EnableBankingClient.toImportRow(
                tx("2026-03-15", "20.00", "DBIT", List.of("Bizum", "a Pepe"), null), "Principal");

        assertThat(row.description()).isEqualTo("Bizum a Pepe");
    }

    @Test
    void noRemittanceInformationFallsBackToAPlaceholder() {
        ImportRow row = EnableBankingClient.toImportRow(
                tx("2026-03-15", "20.00", "DBIT", List.of(), null), "Principal");

        assertThat(row.description()).isEqualTo("(sin descripción)");
    }

    @Test
    void balanceIsReadWhenThePageIncludesIt() {
        ImportRow row = EnableBankingClient.toImportRow(
                tx("2026-03-15", "45.30", "DBIT", List.of("COMPRA MERCADONA"), "954.70"), "Principal");

        assertThat(row.balance()).isEqualTo(eur(954.70));
    }

    @Test
    void fallsBackToTransactionDateWhenThereIsNoBookingDate() {
        EnableBankingClient.BankTransaction noBookingDate = new EnableBankingClient.BankTransaction(
                null, null, "2026-03-15", new EnableBankingClient.Amount("EUR", "10.00"), "DBIT",
                List.of("algo"), null, "BOOK");

        ImportRow row = EnableBankingClient.toImportRow(noBookingDate, "Principal");

        assertThat(row.date().toString()).isEqualTo("2026-03-15");
    }

    @Test
    void bookedMovementsAndOnesWithoutAStatusCountAsBooked() {
        assertThat(EnableBankingClient.isBooked(withStatus("BOOK"))).isTrue();
        assertThat(EnableBankingClient.isBooked(withStatus(null))).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"PDNG", "HOLD", "SCHD", "CNCL", "RJCT", "OTHR"})
    void anythingNotBookedIsLeftOut(String status) {
        assertThat(EnableBankingClient.isBooked(withStatus(status))).isFalse();
    }

    private EnableBankingClient linkedClient(RestClient.Builder builder) throws Exception {
        BankLinkRepository repository = mock(BankLinkRepository.class);
        BankLink link = new BankLink();
        link.setAccountUid("acc-1");
        link.setValidUntil(Instant.now().plusSeconds(3600));
        when(repository.findTopByOrderByIdDesc()).thenReturn(Optional.of(link));
        return new EnableBankingClient(builder, repository, "app-id", writeThrowAwayKey().toString());
    }

    private static final EnableBankingClient.Psu PRESENT = new EnableBankingClient.Psu("127.0.0.1", "Firefox de prueba");

    private static final String MARCH_FIRST_PAGE = """
            {"transactions": [
              {"booking_date": "2026-04-02", "transaction_amount": {"currency": "EUR", "amount": "100.00"},
               "credit_debit_indicator": "DBIT", "remittance_information": ["ALQUILER"], "status": "BOOK"},
              {"booking_date": "2026-03-15", "transaction_amount": {"currency": "EUR", "amount": "45.30"},
               "credit_debit_indicator": "DBIT", "remittance_information": ["COMPRA MERCADONA"], "status": "BOOK"},
              {"transaction_date": "2026-03-31", "transaction_amount": {"currency": "EUR", "amount": "3.50"},
               "credit_debit_indicator": "DBIT", "remittance_information": ["CAFE"], "status": "PDNG"}
             ],
             "continuation_key": "page-2"}
            """;

    private static final String MARCH_SECOND_PAGE = """
            {"transactions": [
              {"booking_date": "2026-03-01", "transaction_amount": {"currency": "EUR", "amount": "1500.00"},
               "credit_debit_indicator": "CRDT", "remittance_information": ["NOMINA"], "status": "BOOK"}
             ]}
            """;

    @Test
    void fetchReadsEveryPageUpToTodayAndImportsOnlyTheBookedMovementsInTheRange() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        EnableBankingClient client = linkedClient(builder);

        // Up to today, not to the requested end: later movements are needed to work balances out backwards.
        server.expect(requestTo("https://api.enablebanking.com/accounts/acc-1/transactions?date_from=2026-03-01&date_to=" + LocalDate.now()))
                .andExpect(header("Authorization", startsWith("Bearer ")))
                .andExpect(header("Psu-Ip-Address", "127.0.0.1"))
                .andExpect(header("Psu-User-Agent", "Firefox de prueba"))
                .andRespond(withSuccess(MARCH_FIRST_PAGE, APPLICATION_JSON));
        server.expect(requestTo(containsString("continuation_key=page-2")))
                .andRespond(withSuccess(MARCH_SECOND_PAGE, APPLICATION_JSON));
        server.expect(requestTo("https://api.enablebanking.com/accounts/acc-1/balances"))
                .andExpect(header("Psu-Ip-Address", "127.0.0.1"))
                .andRespond(withSuccess("""
                        {"balances": [
                          {"name": "Interim available balance", "balance_type": "ITAV",
                           "balance_amount": {"currency": "EUR", "amount": "2500.00"}},
                          {"name": "Accounting balance", "balance_type": "CLBD",
                           "balance_amount": {"currency": "EUR", "amount": "2000.00"}, "reference_date": null}
                         ]}
                        """, APPLICATION_JSON));

        EnableBankingClient.Fetched fetched = client.fetchTransactions(
                LocalDate.parse("2026-03-01"), LocalDate.parse("2026-03-31"), "Principal", PRESENT);

        server.verify();
        // ALQUILER (after the range) isn't imported, but it's why MERCADONA ends at 2100 and not 2000.
        assertThat(fetched.rows()).extracting(ImportRow::description).containsExactly("COMPRA MERCADONA", "NOMINA");
        assertThat(fetched.rows()).extracting(ImportRow::balance).containsExactly(eur(2100.00), eur(2145.30));
        assertThat(fetched.pending()).isEqualTo(1);
    }

    @Test
    void theBanksDailyLimitGetsAClearMessage() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        EnableBankingClient client = linkedClient(builder);
        server.expect(requestTo(containsString("/transactions?")))
                .andRespond(MockRestResponseCreators.withStatus(HttpStatus.TOO_MANY_REQUESTS).contentType(APPLICATION_JSON)
                        .body("{\"code\":429,\"message\":\"[HUB046] Allowed number of accesses exceeded for consent.\"}"));

        assertThatThrownBy(() -> client.fetchTransactions(
                LocalDate.parse("2026-03-01"), LocalDate.parse("2026-03-31"), "Principal", PRESENT))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(e.getMessage()).contains("limitado las consultas");
                });
    }

    @Test
    void aFailedBalanceLookupStillImportsTheMovementsWithoutABalance() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        EnableBankingClient client = linkedClient(builder);

        server.expect(requestTo(containsString("/transactions?"))).andRespond(withSuccess(MARCH_FIRST_PAGE, APPLICATION_JSON));
        server.expect(requestTo(containsString("continuation_key=page-2"))).andRespond(withSuccess(MARCH_SECOND_PAGE, APPLICATION_JSON));
        server.expect(requestTo(containsString("/balances"))).andRespond(withServerError());

        EnableBankingClient.Fetched fetched = client.fetchTransactions(
                LocalDate.parse("2026-03-01"), LocalDate.parse("2026-03-31"), "Principal", PRESENT);

        assertThat(fetched.rows()).hasSize(2).allSatisfy(row -> assertThat(row.balance()).isNull());
    }

    private EnableBankingClient.BankTransaction booked(String date, String amount, String indicator, String balance) {
        return new EnableBankingClient.BankTransaction(date, date, date, new EnableBankingClient.Amount("EUR", amount),
                indicator, List.of("x"), balance == null ? null : new EnableBankingClient.Amount("EUR", balance), "BOOK");
    }

    @Test
    void balancesAreWorkedOutBackwardsFromTheCurrentOneKeepingTheBanksOrderWithinADay() {
        // Newest first, as BBVA lists them; two movements on the 22nd.
        List<EnableBankingClient.BankTransaction> newestFirst = List.of(
                booked("2026-09-22", "30.00", "DBIT", null),
                booked("2026-09-22", "5.00", "DBIT", null),
                booked("2026-09-21", "200.00", "CRDT", null),
                booked("2026-09-11", "400.00", "DBIT", null));

        assertThat(EnableBankingClient.runningBalances(newestFirst, new BigDecimal("1000.00")))
                .containsExactly(eur(1000.00), eur(1030.00), eur(1035.00), eur(835.00));
    }

    @Test
    void anOldestFirstListGivesTheSameBalances() {
        List<EnableBankingClient.BankTransaction> oldestFirst = List.of(
                booked("2026-09-11", "400.00", "DBIT", null),
                booked("2026-09-21", "200.00", "CRDT", null),
                booked("2026-09-22", "5.00", "DBIT", null),
                booked("2026-09-22", "30.00", "DBIT", null));

        assertThat(EnableBankingClient.runningBalances(oldestFirst, new BigDecimal("1000.00")))
                .containsExactly(eur(835.00), eur(1035.00), eur(1030.00), eur(1000.00));
    }

    @Test
    void aBalanceSentByTheBankIsKeptAndTheCountRestartsFromIt() {
        List<EnableBankingClient.BankTransaction> newestFirst = List.of(
                booked("2026-09-22", "30.00", "DBIT", null),
                booked("2026-09-21", "10.00", "DBIT", "500.00"),
                booked("2026-09-20", "20.00", "DBIT", null));

        assertThat(EnableBankingClient.runningBalances(newestFirst, new BigDecimal("1000.00")))
                .containsExactly(eur(1000.00), eur(500.00), eur(510.00));
    }

    @Test
    void withoutACurrentBalanceThereAreNoBalances() {
        assertThat(EnableBankingClient.runningBalances(
                List.of(booked("2026-09-22", "30.00", "DBIT", null)), null)).containsExactly((BigDecimal) null);
    }

    private EnableBankingClient.BankBalance balance(String type, String amount, String referenceDate) {
        return new EnableBankingClient.BankBalance(type, new EnableBankingClient.Amount("EUR", amount), referenceDate);
    }

    @Test
    void theBookedBalanceIsPreferredAndAnAvailableOneIsNeverUsed() {
        LocalDate newest = LocalDate.parse("2026-09-22");

        assertThat(EnableBankingClient.bookedBalance(List.of(balance("ITAV", "9.99", null), balance("CLBD", "100.00", null)), newest))
                .isEqualByComparingTo("100.00");
        assertThat(EnableBankingClient.bookedBalance(List.of(balance("ITBD", "50.00", null)), newest))
                .isEqualByComparingTo("50.00");
        // "Available" also reflects pending movements and credit lines: booked movements can't be subtracted from it.
        assertThat(EnableBankingClient.bookedBalance(List.of(balance("ITAV", "9.99", null)), newest)).isNull();
    }

    @Test
    void aBalanceDatedBeforeTheNewestMovementIsNotUsed() {
        assertThat(EnableBankingClient.bookedBalance(
                List.of(balance("CLBD", "100.00", "2026-09-21")), LocalDate.parse("2026-09-22"))).isNull();
        assertThat(EnableBankingClient.bookedBalance(
                List.of(balance("CLBD", "100.00", "2026-09-22")), LocalDate.parse("2026-09-22"))).isEqualByComparingTo("100.00");
    }

    @Test
    void linkingStoresTheSessionIdAccountAndExpiryTheBankGranted() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BankLinkRepository repository = mock(BankLinkRepository.class);
        when(repository.findTopByOrderByIdDesc()).thenReturn(Optional.empty());
        EnableBankingClient client = new EnableBankingClient(builder, repository, "app-id", writeThrowAwayKey().toString());

        AtomicReference<String> sentState = new AtomicReference<>();
        server.expect(requestTo("https://api.enablebanking.com/auth"))
                .andExpect(request -> sentState.set(JsonPath.read(
                        ((MockClientHttpRequest) request).getBodyAsString(), "$.state")))
                .andRespond(withSuccess("{\"url\": \"https://bbva.example/authorize\"}", APPLICATION_JSON));
        // Enable Banking's AuthorizeSessionResponse, field names as its API reference documents them.
        server.expect(requestTo("https://api.enablebanking.com/sessions"))
                .andRespond(withSuccess("""
                        {"session_id": "4a7c2b6e-0000-4000-8000-000000000001",
                         "accounts": [{"uid": "acc-uid-1", "currency": "EUR"}],
                         "aspsp": {"name": "BBVA", "country": "ES"},
                         "psu_type": "personal",
                         "access": {"valid_until": "2027-03-22T10:15:30.123456+00:00"}}
                        """, APPLICATION_JSON));

        assertThat(client.startAuthorization("https://127.0.0.1:8000/api/bank-sync/callback"))
                .isEqualTo("https://bbva.example/authorize");
        client.completeAuthorization("the-code", sentState.get());

        server.verify();
        ArgumentCaptor<BankLink> saved = ArgumentCaptor.forClass(BankLink.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getSessionId()).isEqualTo("4a7c2b6e-0000-4000-8000-000000000001");
        assertThat(saved.getValue().getAccountUid()).isEqualTo("acc-uid-1");
        assertThat(saved.getValue().getValidUntil()).isEqualTo(Instant.parse("2027-03-22T10:15:30.123456Z"));
    }

    private BankLinkRepository repositoryWithSession(String sessionId) {
        BankLinkRepository repository = mock(BankLinkRepository.class);
        BankLink link = new BankLink();
        link.setSessionId(sessionId);
        when(repository.findTopByOrderByIdDesc()).thenReturn(Optional.of(link));
        return repository;
    }

    @Test
    void unlinkingRevokesTheSessionAtEnableBankingAndForgetsTheLink() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BankLinkRepository repository = repositoryWithSession("sess-1");
        EnableBankingClient client = new EnableBankingClient(builder, repository, "app-id", writeThrowAwayKey().toString());

        server.expect(requestTo("https://api.enablebanking.com/sessions/sess-1"))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(header("Authorization", startsWith("Bearer ")))
                .andRespond(withSuccess("{\"message\": \"OK\"}", APPLICATION_JSON));

        client.unlink();

        server.verify();
        verify(repository).deleteAll();
    }

    @ParameterizedTest
    @CsvSource({"401", "403", "404"})
    void aSessionTheBankNoLongerKnowsIsForgottenAnyway(int status) throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BankLinkRepository repository = repositoryWithSession("sess-1");
        EnableBankingClient client = new EnableBankingClient(builder, repository, "app-id", writeThrowAwayKey().toString());

        server.expect(requestTo("https://api.enablebanking.com/sessions/sess-1"))
                .andRespond(MockRestResponseCreators.withStatus(HttpStatus.valueOf(status)));

        client.unlink();

        verify(repository).deleteAll();
    }

    @Test
    void aFailedRevocationKeepsTheLinkSoItCanBeRetried() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BankLinkRepository repository = repositoryWithSession("sess-1");
        EnableBankingClient client = new EnableBankingClient(builder, repository, "app-id", writeThrowAwayKey().toString());

        server.expect(requestTo("https://api.enablebanking.com/sessions/sess-1")).andRespond(withServerError());

        assertThatThrownBy(client::unlink)
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("No se ha podido desvincular");
        verify(repository, never()).deleteAll();
    }

    @Test
    void unlinkingWithNoLinkCallsNobody() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BankLinkRepository repository = mock(BankLinkRepository.class);
        when(repository.findTopByOrderByIdDesc()).thenReturn(Optional.empty());
        EnableBankingClient client = new EnableBankingClient(builder, repository, "app-id", writeThrowAwayKey().toString());

        client.unlink();

        server.verify();
        verify(repository, never()).deleteAll();
    }

    @Test
    void withoutAGrantedExpiryTheRequestedWindowIsAssumed() {
        Instant expiry = EnableBankingClient.grantedUntil(new EnableBankingClient.AccessWindow("no es una fecha"));

        assertThat(expiry).isBetween(Instant.now().plus(Duration.ofDays(179)), Instant.now().plus(Duration.ofDays(181)));
        assertThat(EnableBankingClient.grantedUntil(null)).isAfter(Instant.now().plus(Duration.ofDays(179)));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            // Real shapes BBVA sends (amounts and references made up).
            "PAGO CON TARJETA EN SUPERMERCADOS // PAGO CON TARJETA // LIDL CENTRO              VALENCIA     ES | Lidl centro — Pago con tarjeta",
            "PAGO CON TARJETA DE SERVICIOS VARIOS // PAGO CON TARJETA // WWW.AMAZON*AB12CD34 | Www.amazon*ab12cd34 — Pago con tarjeta",
            "PAGO CON TARJETA EN RESTAURANTES Y CAFETERIAS // PAGO CON TARJETA // EduPlan | EduPlan — Pago con tarjeta",
            "BIZUM // OTROS // ENVIADO: Sin concepto | Bizum — ENVIADO: Sin concepto",
            "TRANSFERENCIAS // TRANSFERENCIA REALIZADA // OBA1234567890ABCD 0123abcd4567ef89 | Transferencia realizada — OBA1234567890ABCD 0123abcd4567ef89",
            "ABONO DE NOMINA POR TRANSFERENCIA // INGRESO POR NÓMINA O PENSIÓN // EMPRESA EJEMPLO SL | Ingreso por nómina o pensión — Empresa ejemplo sl",
            "RET. EFECTIVO  A DEBITO CON TARJ. EN CAJERO. AUT. // OTROS // 01820002 999 | Ret. efectivo a debito con tarj. en cajero. aut. — 01820002 999",
            "INGRESO EN EFECTIVO // INGRESO DE EFECTIVO // INGRESO EN EFECTIVO | Ingreso en efectivo",
            "BIZUM // OTROS // COMPRA: VENTA BILLETES | Bizum — COMPRA: VENTA BILLETES",
            "TRANSFERENCIAS // TRANSFERENCIA REALIZADA // OBA3KQ9ZT2MXW8RLD | Transferencia realizada — OBA3KQ9ZT2MXW8RLD",
            "UN TEXTO   SIN EL FORMATO HABITUAL | UN TEXTO SIN EL FORMATO HABITUAL",
    })
    void descriptionsAreCleanedIntoTheExcelExportsShape(String raw, String expected) {
        assertThat(EnableBankingClient.cleanDescription(raw)).isEqualTo(expected);
    }

    // A card payment's detail as BBVA sends it: 25 characters of merchant, 13 of city, 2 of country.
    private static String cardPayment(String merchant, String city) {
        return "PAGO CON TARJETA DE SERVICIOS VARIOS // PAGO CON TARJETA // " + String.format("%-25s%-13s%s", merchant, city, "ES");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            // 24 characters: one space left before the city (the real case that came out as "... perez madrid").
            "TALLERES MECANICOS PEREZ | MADRID | Talleres mecanicos perez — Pago con tarjeta",
            // 25 characters: the city follows with no space at all.
            "ABCDEFGHIJKLMNOPQRSTUVWXY | BARCELONA | Abcdefghijklmnopqrstuvwxy — Pago con tarjeta",
            // Shorter names were already fine, and still are.
            "LIDL CENTRO | VALENCIA | Lidl centro — Pago con tarjeta",
            "FARMACIA DEL PILAR | BARCELONA | Farmacia del pilar — Pago con tarjeta",
    })
    void aCardMerchantIsReadFromItsFixedWidthField(String merchant, String city, String expected) {
        assertThat(EnableBankingClient.cleanDescription(cardPayment(merchant, city))).isEqualTo(expected);
    }

    @Test
    void aLongCardDetailWithoutCityAndCountryIsNotCut() {
        String raw = "PAGO CON TARJETA DE SERVICIOS VARIOS // PAGO CON TARJETA // PAYPAL *UNA TIENDA CON NOMBRE LARGO";

        assertThat(EnableBankingClient.cleanDescription(raw)).isEqualTo("Paypal *una tienda con nombre largo — Pago con tarjeta");
    }

    @Test
    void theFirstCleaningIsOfferedSoCardPaymentsStoredWithTheCityGluedOnAreRecognised() {
        String raw = cardPayment("TALLERES MECANICOS PEREZ", "MADRID");
        ImportRow row = EnableBankingClient.toImportRow(tx("2026-06-22", "30.00", "DBIT", List.of(raw), null), "Principal");

        assertThat(row.description()).isEqualTo("Talleres mecanicos perez — Pago con tarjeta");
        assertThat(row.alternativeDescriptions())
                .containsEntry("bank-raw", raw)
                .containsEntry("bank-clean-v1", "Talleres mecanicos perez madrid — Pago con tarjeta");
    }

    @Test
    void noFirstCleaningAlternativeWhenItCameOutTheSame() {
        ImportRow row = EnableBankingClient.toImportRow(
                tx("2026-06-22", "30.00", "DBIT", List.of(cardPayment("LIDL CENTRO", "VALENCIA")), null), "Principal");

        assertThat(row.alternativeDescriptions()).doesNotContainKey("bank-clean-v1");
    }

    @Test
    void theRawDescriptionIsOfferedSoMovementsStoredBeforeCleaningAreRecognised() {
        String raw = "BIZUM // OTROS // ENVIADO: Sin concepto";
        ImportRow row = EnableBankingClient.toImportRow(tx("2026-03-15", "20.00", "DBIT", List.of(raw), null), "Principal");

        assertThat(row.description()).isEqualTo("Bizum — ENVIADO: Sin concepto");
        assertThat(row.alternativeDescriptions()).containsValue(raw);
    }

    private Path writeThrowAwayKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        String base64 = Base64.getMimeEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
        Path pem = tempDir.resolve("private_key.pem");
        Files.writeString(pem, "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----\n");
        return pem;
    }
}
