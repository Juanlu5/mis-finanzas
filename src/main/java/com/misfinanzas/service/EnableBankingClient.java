package com.misfinanzas.service;

import com.misfinanzas.dto.ImportRow;
import com.misfinanzas.entity.BankLink;
import com.misfinanzas.repository.BankLinkRepository;
import com.misfinanzas.web.ApiException;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Talks to Enable Banking (an Open Banking/PSD2 aggregator) so the app can pull real BBVA movements
 * for a date range instead of the user downloading and uploading a file. Every request needs an RS256
 * JWT signed with the application's own private key (registered on enablebanking.com, downloaded once
 * by the user, see docs/bank-sync.md) — never the user's bank password, which is entered only on BBVA's own
 * authentication page during the redirect flow.
 */
@Service
public class EnableBankingClient {

    private static final Logger log = LoggerFactory.getLogger(EnableBankingClient.class);

    private static final String BASE_URL = "https://api.enablebanking.com";
    private static final String ASPSP_NAME = "BBVA";
    private static final String ASPSP_COUNTRY = "ES";
    // How long an authorization is requested for; the bank may grant less. Re-authorizing is a couple of
    // clicks (redirect + push notification approval), not a re-entry of credentials, so a long window is
    // just fewer interruptions, not a bigger risk.
    private static final Duration ACCESS_VALIDITY = Duration.ofDays(180);
    private static final Duration JWT_VALIDITY = Duration.ofMinutes(5);
    // Single user, one flow at a time: no need for a persisted table just to check the "state" round-trips.
    private static final Duration PENDING_AUTH_TTL = Duration.ofMinutes(15);

    private final RestClient restClient;
    private final BankLinkRepository bankLinkRepository;
    private final String applicationId;
    private final String privateKeyPath;

    private volatile PendingAuthorization pending;

    private record PendingAuthorization(String state, Instant expiresAt) {
    }

    public EnableBankingClient(RestClient.Builder restClientBuilder, BankLinkRepository bankLinkRepository,
                                @Value("${app.enable-banking.application-id}") String applicationId,
                                @Value("${app.enable-banking.private-key-path}") String privateKeyPath) {
        this.restClient = restClientBuilder.baseUrl(BASE_URL).build();
        this.bankLinkRepository = bankLinkRepository;
        this.applicationId = applicationId;
        this.privateKeyPath = privateKeyPath;
    }

    public record Status(boolean linked, String aspspName, Instant validUntil) {
    }

    public Status status() {
        return bankLinkRepository.findTopByOrderByIdDesc()
                .map(link -> new Status(Instant.now().isBefore(link.getValidUntil()), link.getAspspName(), link.getValidUntil()))
                .orElse(new Status(false, null, null));
    }

    /** Starts the redirect flow. {@code redirectUrl} must point back at {@code GET /api/bank-sync/callback}. */
    public String startAuthorization(String redirectUrl) {
        String state = UUID.randomUUID().toString();
        pending = new PendingAuthorization(state, Instant.now().plus(PENDING_AUTH_TTL));

        AuthRequest body = new AuthRequest(
                new AccessWindow(Instant.now().plus(ACCESS_VALIDITY).toString()),
                new AspspRef(ASPSP_NAME, ASPSP_COUNTRY),
                state, redirectUrl, "personal");
        AuthResponse response = call(() -> restClient.post()
                .uri("/auth")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + signApplicationJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(AuthResponse.class), "iniciar la vinculación");
        if (response == null || response.url() == null) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Enable Banking no ha devuelto una URL de autorización.");
        }
        return response.url();
    }

    /** Completes the redirect flow (the bank sent the browser back to our callback with this code+state). */
    public void completeAuthorization(String code, String state) {
        PendingAuthorization current = pending;
        if (current == null || !current.state().equals(state) || Instant.now().isAfter(current.expiresAt())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "La vinculación ha caducado o no es válida, vuelve a intentarlo.");
        }
        pending = null;

        SessionResponse response = call(() -> restClient.post()
                .uri("/sessions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + signApplicationJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new SessionRequest(code))
                .retrieve()
                .body(SessionResponse.class), "completar la vinculación");
        if (response == null || response.accounts() == null || response.accounts().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "El banco no ha devuelto ninguna cuenta accesible.");
        }

        BankLink link = bankLinkRepository.findTopByOrderByIdDesc().orElseGet(BankLink::new);
        link.setAspspName(ASPSP_NAME);
        link.setAspspCountry(ASPSP_COUNTRY);
        link.setAccountUid(response.accounts().get(0).uid());
        link.setSessionId(response.session_id());
        link.setValidUntil(grantedUntil(response.access()));
        link.setCreatedAt(Instant.now());
        bankLinkRepository.save(link);
    }

    /**
     * Revokes the session at Enable Banking (which ends the consent at the bank) and forgets the link here.
     * A session the bank no longer knows (401/403/404: already expired or revoked elsewhere) has nothing
     * left to revoke, so the link is forgotten anyway; any other failure keeps it, so unlinking can be
     * retried rather than leaving a consent alive at the bank that this app no longer knows about.
     */
    public void unlink() {
        BankLink link = bankLinkRepository.findTopByOrderByIdDesc().orElse(null);
        if (link == null) return;
        try {
            restClient.delete()
                    .uri("/sessions/{id}", link.getSessionId())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + signApplicationJwt())
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status != 401 && status != 403 && status != 404) {
                throw new ApiException(HttpStatus.BAD_GATEWAY,
                        "No se ha podido desvincular la cuenta con Enable Banking (" + e.getStatusCode() + "). Vuelve a intentarlo.");
            }
            log.info("La sesión con el banco ya no existía ({}), se desvincula solo aquí.", e.getStatusCode());
        }
        bankLinkRepository.deleteAll();
    }

    /**
     * The person asking for a download, as their browser reached this app. Sent to Enable Banking as the PSU
     * headers, which tell the bank the account holder is present: PSD2 caps reads made in the background
     * (typically 4 per day per consent; BBVA answers the 5th with a 429 "Allowed number of accesses exceeded
     * for consent"), but not reads made with the person there, and every download here is a button press.
     * Checked live: with the cap spent, the same transactions request failed without these headers and worked
     * with them, and BBVA took a loopback or LAN address as the IP. Either field may be null (then omitted).
     */
    public record Psu(String ipAddress, String userAgent) {
        static final Psu ABSENT = new Psu(null, null);
    }

    /**
     * What a download produced: the booked movements, ready to import, and how many pending ones were left
     * out (see {@link #isBooked}).
     */
    public record Fetched(List<ImportRow> rows, int pending) {
    }

    /**
     * What the bank actually granted, which can be less than the {@link #ACCESS_VALIDITY} asked for; the
     * requested window only if the response doesn't say (or says it in a shape this can't read), since the
     * worst case then is a status page showing a date later than the real one, and an expired session is
     * still caught by the 401 handling in {@link #call}.
     */
    static Instant grantedUntil(AccessWindow access) {
        if (access != null && access.valid_until() != null) {
            try {
                return OffsetDateTime.parse(access.valid_until()).toInstant();
            } catch (DateTimeParseException ignored) {
                // fall through to the requested window
            }
        }
        return Instant.now().plus(ACCESS_VALIDITY);
    }

    /**
     * Fetches every booked movement of the linked account between the two dates (inclusive), across all pages,
     * each with the account balance after it (see {@link #runningBalances}).
     *
     * <p>BBVA doesn't send a balance per movement, only the account's current booked balance, so balances are
     * worked out backwards from it — which needs every booked movement from the requested start up to today,
     * not just up to the requested end. The download therefore always runs to today, and movements after
     * {@code to} are only used for that arithmetic, never imported.
     */
    public Fetched fetchTransactions(LocalDate from, LocalDate to, String account, Psu psu) {
        BankLink link = bankLinkRepository.findTopByOrderByIdDesc()
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Todavía no has vinculado ninguna cuenta bancaria."));
        if (Instant.now().isAfter(link.getValidUntil())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "La sesión con el banco ha caducado, vuelve a vincular la cuenta.");
        }
        LocalDate today = LocalDate.now();
        LocalDate fetchTo = to.isBefore(today) ? today : to;

        List<BankTransaction> booked = new ArrayList<>();
        int pending = 0;
        String continuationKey = null;
        do {
            String continuationKeyForThisPage = continuationKey;
            TransactionsPage page = call(() -> restClient.get()
                    .uri(uriBuilder -> {
                        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString("/accounts/{uid}/transactions")
                                .queryParam("date_from", from)
                                .queryParam("date_to", fetchTo);
                        if (continuationKeyForThisPage != null) builder.queryParam("continuation_key", continuationKeyForThisPage);
                        return builder.buildAndExpand(link.getAccountUid()).toUri();
                    })
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + signApplicationJwt())
                    .headers(headers -> addPsuHeaders(headers, psu))
                    .retrieve()
                    .body(TransactionsPage.class), "descargar los movimientos");
            if (page == null || page.transactions() == null) break;
            for (BankTransaction tx : page.transactions()) {
                if (isBooked(tx)) booked.add(tx);
                else if (inRange(dateOf(tx), from, to)) pending++;
            }
            continuationKey = page.continuation_key();
        } while (continuationKey != null && !continuationKey.isBlank());

        List<BigDecimal> balances = runningBalances(booked, currentBookedBalance(link, booked, psu));
        List<ImportRow> rows = new ArrayList<>();
        for (int i = 0; i < booked.size(); i++) {
            BankTransaction tx = booked.get(i);
            if (inRange(dateOf(tx), from, to)) rows.add(toImportRow(tx, account, balances.get(i)));
        }
        return new Fetched(rows, pending);
    }

    // A movement with neither booking nor transaction date can't be placed in any range (nor stored: the
    // date column is NOT NULL), so it's left out rather than failing the whole download.
    private static void addPsuHeaders(HttpHeaders headers, Psu psu) {
        if (psu == null) return;
        if (psu.ipAddress() != null && !psu.ipAddress().isBlank()) headers.set("Psu-Ip-Address", psu.ipAddress());
        if (psu.userAgent() != null && !psu.userAgent().isBlank()) headers.set("Psu-User-Agent", psu.userAgent());
    }

    private static boolean inRange(LocalDate date, LocalDate from, LocalDate to) {
        return date != null && !date.isBefore(from) && !date.isAfter(to);
    }

    /**
     * The account's booked balance right now, i.e. after every booked movement, or null if it can't be had:
     * then movements are imported without a balance (as a bank that sends none would leave them) rather than
     * failing a download that did work. Only booked types count (CLBD, then ITBD): an "available" balance also
     * reflects pending movements and credit lines, which the booked movements can't be subtracted from. A
     * balance dated before the newest booked movement doesn't include it, so it isn't used either.
     */
    private BigDecimal currentBookedBalance(BankLink link, List<BankTransaction> booked, Psu psu) {
        if (booked.isEmpty()) return null;
        BalancesResponse response;
        try {
            response = call(() -> restClient.get()
                    .uri("/accounts/{uid}/balances", link.getAccountUid())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + signApplicationJwt())
                    .headers(headers -> addPsuHeaders(headers, psu))
                    .retrieve()
                    .body(BalancesResponse.class), "consultar el saldo");
        } catch (ApiException e) {
            log.warn("No se ha podido consultar el saldo de la cuenta, los movimientos se importan sin saldo: {}", e.getMessage());
            return null;
        }
        BigDecimal balance = bookedBalance(response == null ? null : response.balances(), newestDate(booked));
        if (balance == null) {
            log.warn("El banco no ha devuelto un saldo contable utilizable, los movimientos se importan sin saldo.");
        }
        return balance;
    }

    static BigDecimal bookedBalance(List<BankBalance> balances, LocalDate newestMovement) {
        if (balances == null) return null;
        for (String type : List.of("CLBD", "ITBD")) {
            for (BankBalance balance : balances) {
                if (!type.equals(balance.balance_type()) || balance.balance_amount() == null) continue;
                if (balance.reference_date() != null && newestMovement != null
                        && LocalDate.parse(balance.reference_date()).isBefore(newestMovement)) continue;
                return new BigDecimal(balance.balance_amount().amount());
            }
        }
        return null;
    }

    private static LocalDate newestDate(List<BankTransaction> booked) {
        return booked.stream().map(EnableBankingClient::dateOf).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
    }

    /**
     * The balance after each movement (same positions as {@code booked}), worked out backwards from
     * {@code current}, the balance after all of them: the newest one ends at {@code current}, and each older one
     * at the next newer one's balance minus that newer movement's amount. A movement that carries its own
     * balance keeps it and restarts the count from there. All null when {@code current} is null and no movement
     * has a balance of its own.
     *
     * <p>Order within a day matters (two movements on the same day end at different balances) and only the
     * bank knows it: its own list order is kept, whichever direction it lists in (BBVA: newest first).
     */
    static List<BigDecimal> runningBalances(List<BankTransaction> booked, BigDecimal current) {
        List<Integer> newestFirst = new ArrayList<>();
        for (int i = 0; i < booked.size(); i++) newestFirst.add(i);
        LocalDate first = booked.isEmpty() ? null : dateOf(booked.get(0));
        LocalDate last = booked.isEmpty() ? null : dateOf(booked.get(booked.size() - 1));
        if (first != null && last != null && first.isBefore(last)) Collections.reverse(newestFirst);
        // Stable sort: equal dates keep the bank's order from above.
        newestFirst.sort(Comparator.comparing((Integer i) -> dateOf(booked.get(i)),
                Comparator.nullsFirst(Comparator.<LocalDate>naturalOrder())).reversed());

        BigDecimal[] balances = new BigDecimal[booked.size()];
        BigDecimal running = current;
        for (int i : newestFirst) {
            BankTransaction tx = booked.get(i);
            if (tx.balance_after_transaction() != null) {
                running = new BigDecimal(tx.balance_after_transaction().amount());
            }
            if (running == null) continue;
            balances[i] = running;
            running = running.subtract(signedAmount(tx));
        }
        return Arrays.asList(balances);
    }

    /**
     * Only booked movements are imported. A pending one (PDNG, HOLD...) can change date or description once
     * the bank books it, and then its dedupe key no longer matches, so it would be stored twice; it gets
     * imported on a later download, once booked. A movement with no status at all is taken as booked: the
     * bank didn't say otherwise, and dropping it would lose it for good.
     */
    static boolean isBooked(BankTransaction tx) {
        return tx.status() == null || "BOOK".equalsIgnoreCase(tx.status());
    }

    static LocalDate dateOf(BankTransaction tx) {
        String date = tx.booking_date() != null ? tx.booking_date() : tx.transaction_date();
        return date == null ? null : LocalDate.parse(date);
    }

    private static BigDecimal signedAmount(BankTransaction tx) {
        BigDecimal magnitude = new BigDecimal(tx.transaction_amount().amount()).abs();
        return "CRDT".equalsIgnoreCase(tx.credit_debit_indicator()) ? magnitude : magnitude.negate();
    }

    static ImportRow toImportRow(BankTransaction tx, String account) {
        return toImportRow(tx, account, null);
    }

    /** {@code computedBalance} is used only when the movement doesn't carry a balance of its own. */
    static ImportRow toImportRow(BankTransaction tx, String account, BigDecimal computedBalance) {
        LocalDate date = dateOf(tx);
        BigDecimal amount = signedAmount(tx);
        String raw = (tx.remittance_information() == null || tx.remittance_information().isEmpty())
                ? "(sin descripción)"
                : String.join(" ", tx.remittance_information());
        String description = cleanDescription(raw);
        BigDecimal balance = tx.balance_after_transaction() != null
                ? new BigDecimal(tx.balance_after_transaction().amount())
                : computedBalance;
        // Movements downloaded before descriptions were cleaned are stored under the raw text, and card payments
        // with a long merchant name under the city-glued text the first cleaning produced: offering both as
        // alternatives lets the dedupe lookup recognise them instead of adding them again.
        Map<String, String> alternatives = new LinkedHashMap<>();
        if (!raw.equals(description)) alternatives.put("bank-raw", raw);
        String firstCleaning = cleanDescription(raw, false);
        if (!firstCleaning.equals(description)) alternatives.put("bank-clean-v1", firstCleaning);
        return new ImportRow(date, description, amount, account, balance, alternatives);
    }

    private static final Pattern PADDING = Pattern.compile("\\s{2,}");
    // A card payment's detail is the card networks' fixed-width "acceptor name/location": 25 characters of
    // merchant, 13 of city and a 2-letter country code (checked on BBVA's text).
    private static final int CARD_MERCHANT_WIDTH = 25;
    private static final Pattern ENDS_WITH_COUNTRY = Pattern.compile(".*\\s[A-Z]{2}");
    private static final Locale SPANISH = Locale.forLanguageTag("es");

    /**
     * BBVA's remittance text is "bank's category // operation // detail", e.g.
     * {@code PAGO CON TARJETA EN SUPERMERCADOS // PAGO CON TARJETA // LIDL CENTRO      VALENCIA     ES}, the detail
     * of a card payment being the merchant in a fixed-width field followed by city and country. This turns it
     * into the shape BBVA's Excel export uses, which the existing rules were written against:
     * <ul>
     *   <li>card payment: {@code Lidl centro — Pago con tarjeta} (merchant first, city/country dropped);</li>
     *   <li>anything else: {@code Transferencia realizada — OBA3KQ9...}, {@code Bizum — ENVIADO: Sin concepto}
     *       (the operation, or the bank's category when the operation is just "OTROS", then the detail);</li>
     *   <li>a detail that only repeats the category leaves just the category ({@code Ingreso en efectivo},
     *       which is also what the Excel export and the seeded rule say).</li>
     * </ul>
     * All-caps text is put in sentence case, except a Bizum concept or a transfer reference, kept as sent. Anything not in the three-part shape only gets its padding collapsed.
     */
    static String cleanDescription(String raw) {
        return cleanDescription(raw, true);
    }

    /**
     * {@code fixedWidthMerchant} false reproduces the first version of this cleaning, which took the merchant as
     * everything up to the first run of spaces: a merchant name of 24-25 characters leaves at most one space
     * before the city, so the city stayed glued on ({@code Talleres mecanicos perez madrid}). Kept only to
     * recognise movements stored that way (see {@link #toImportRow}).
     */
    static String cleanDescription(String raw, boolean fixedWidthMerchant) {
        String[] parts = raw.split("//");
        if (parts.length != 3) return PADDING.matcher(raw.trim()).replaceAll(" ");
        String category = PADDING.matcher(parts[0].trim()).replaceAll(" ");
        String operation = PADDING.matcher(parts[1].trim()).replaceAll(" ");
        String detail = PADDING.split(parts[2].trim())[0];

        if (operation.equalsIgnoreCase("PAGO CON TARJETA") && !detail.isEmpty()) {
            String merchant = fixedWidthMerchant ? cardMerchant(parts[2].trim(), detail) : detail;
            return sentenceCase(merchant) + " — " + sentenceCase(operation);
        }
        if (detail.isEmpty() || detail.equalsIgnoreCase(category)) {
            return sentenceCase(category);
        }
        String title = operation.isEmpty() || operation.equalsIgnoreCase("OTROS") ? category : operation;
        if (detail.equalsIgnoreCase(title)) {
            return sentenceCase(title);
        }
        // A Bizum concept or a transfer reference is shown exactly as sent, like the Excel export does:
        // lower-casing "OBA3KQ9ZT2MXW8RLD" would only make a reference harder to read.
        boolean verbatim = category.equalsIgnoreCase("BIZUM") || title.toUpperCase(SPANISH).contains("TRANSFERENCIA");
        return sentenceCase(title) + " — " + (verbatim ? detail : sentenceCase(detail));
    }

    /**
     * The merchant out of a card payment's detail. Usually the first run of spaces already ends it, but a name
     * that (nearly) fills its 25-character field is followed by the city after one space or none, so a detail
     * laid out as name + city + country is cut at the field's width instead. One that isn't (just a name, as
     * online payments often are) is left as it is.
     */
    private static String cardMerchant(String detailField, String beforeFirstPadding) {
        if (beforeFirstPadding.length() <= CARD_MERCHANT_WIDTH
                || !ENDS_WITH_COUNTRY.matcher(detailField).matches()) {
            return beforeFirstPadding;
        }
        return detailField.substring(0, CARD_MERCHANT_WIDTH).trim();
    }

    private static String sentenceCase(String text) {
        if (text.isEmpty() || !text.equals(text.toUpperCase(SPANISH))) return text;
        String lower = text.toLowerCase(SPANISH);
        return lower.substring(0, 1).toUpperCase(SPANISH) + lower.substring(1);
    }

    // ---------- JWT signing ----------

    private String signApplicationJwt() {
        try {
            RSAPrivateKey key = loadPrivateKey();
            Instant now = Instant.now();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer("enablebanking.com")
                    .audience("api.enablebanking.com")
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plus(JWT_VALIDITY)))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(applicationId).build(), claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "No se ha podido firmar la petición a Enable Banking: " + e.getMessage());
        }
    }

    /**
     * Reads and parses the PEM PKCS#8 private key on every call rather than caching it: this is invoked at
     * most a handful of times per human action (never a hot path), and staying stateless means a key
     * rotated on disk takes effect on the next click, not after a restart.
     */
    private RSAPrivateKey loadPrivateKey() {
        if (applicationId == null || applicationId.isBlank() || privateKeyPath == null || privateKeyPath.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "La sincronización con el banco no está configurada (falta ENABLE_BANKING_APP_ID o la clave privada).");
        }
        try {
            String pem = Files.readString(Path.of(privateKeyPath), StandardCharsets.UTF_8);
            String base64 = pem.replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "").replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(base64);
            return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (IOException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "No se ha podido leer la clave privada de Enable Banking en " + privateKeyPath + ".");
        } catch (InvalidKeySpecException | NoSuchAlgorithmException | IllegalArgumentException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "La clave privada de Enable Banking no tiene el formato esperado (PEM PKCS#8). " +
                            "Si la descargaste como PKCS#1 (\"BEGIN RSA PRIVATE KEY\"), conviértela con " +
                            "`openssl pkcs8 -topk8 -nocrypt -in clave.pem -out private_key.pem`.");
        }
    }

    // ---------- Error translation ----------

    private interface RemoteCall<T> {
        T run();
    }

    private <T> T call(RemoteCall<T> call, String action) {
        try {
            return call.run();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "La sesión con el banco ha caducado, vuelve a vincular la cuenta.");
            }
            if (e.getStatusCode().value() == 429) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                        "El banco ha limitado las consultas por hoy. Vuelve a intentarlo más tarde o mañana.");
            }
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "No se ha podido " + action + " con Enable Banking (" + e.getStatusCode() + ").");
        }
    }

    // ---------- Enable Banking's JSON shapes (internal, only what we use) ----------

    record AccessWindow(String valid_until) {
    }

    private record AspspRef(String name, String country) {
    }

    private record AuthRequest(AccessWindow access, AspspRef aspsp, String state, String redirect_url, String psu_type) {
    }

    private record AuthResponse(String url) {
    }

    private record SessionRequest(String code) {
    }

    private record SessionAccount(String uid) {
    }

    // Field names are Enable Banking's own ("session_id", not "id": reading "id" left it null and the insert
    // into bank_links failed on the first real link).
    private record SessionResponse(String session_id, List<SessionAccount> accounts, AccessWindow access) {
    }

    // Package-private (not private): EnableBankingClientTest builds these directly to test toImportRow
    // without any HTTP involved.
    record Amount(String currency, String amount) {
    }

    record BankTransaction(String booking_date, String value_date, String transaction_date,
                            Amount transaction_amount, String credit_debit_indicator,
                            List<String> remittance_information, Amount balance_after_transaction,
                            String status) {
    }

    record BankBalance(String balance_type, Amount balance_amount, String reference_date) {
    }

    private record BalancesResponse(List<BankBalance> balances) {
    }

    private record TransactionsPage(List<BankTransaction> transactions, String continuation_key) {
    }
}
