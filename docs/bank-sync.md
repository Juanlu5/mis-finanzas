# Bank sync (BBVA through Enable Banking)

Covers `EnableBankingClient`, `BankSyncController`, `BankLink` and the "Descargar del banco" tab.

## `service/EnableBankingClient.java`

Talks to [Enable Banking](https://enablebanking.com), a
European Open Banking/PSD2 aggregator, so the "descargar del banco" tab in the Import view can
pull real BBVA movements for a date range instead of the user uploading a file. Every request
needs an RS256 JWT signed with the *application's own* private key (see "Bank sync setup"
at the end of this file) — never the user's bank password, which is only ever entered on BBVA's own
authentication page during the redirect flow (`POST /auth` → redirect to BBVA → BBVA redirects
back to `GET /api/bank-sync/callback?code=&state=` → `POST /sessions` exchanges the code for a
session, saved as the single `BankLink` row). Signing uses Nimbus JOSE+JWT, not `jjwt`: `jjwt`'s
default JSON binding pulls Jackson 2, and this project is on Jackson 3;
Nimbus needs neither. The private key is read fresh (parsed from PEM PKCS#8) on every signed
request rather than cached, since this is invoked at most a handful of times per human action —
never a hot path — and a key rotated on disk then takes effect on the next click, not after a
restart. A 401/403 from Enable Banking (expired session) becomes
`ApiException(UNAUTHORIZED, "La sesión con el banco ha caducado...")`; the "state" round-trip for
the redirect is checked against an in-memory field, not a table, since it's a single-user app
with one flow active at a time. `fetchTransactions` maps Enable Banking's JSON straight to
`ImportRow` (a `CRDT` indicator is the only positive sign; anything else, including a value this
code hasn't seen, is treated as a debit) — **booked movements only** (`isBooked`: status `BOOK`, or
no status at all): a pending one (`PDNG`, `HOLD`...) can change date/description once booked, which
would change its dedupe key and store it twice, so it's left out, counted in the response's `pending`,
and picked up by a later download. **Balances are computed**: BBVA sends no
`balance_after_transaction`, only the account's current booked balance (`GET /accounts/{uid}/balances`,
type `CLBD`, no `reference_date`), so `runningBalances` works backwards from it (newest movement ends
at that balance; each older one at the next newer one's minus its amount), keeping the bank's own
order within a day (BBVA lists newest first; an oldest-first list is detected and handled). That needs
every booked movement up to today, so the download always runs to today and imports only the
requested range. Only booked types count (`CLBD`, then `ITBD`, never `ITAV`/available); no usable
balance or a failed lookup imports the rows without one, logged. Checked against real data: worked
back from BBVA's `CLBD` it matched, to the cent, the balance the Excel export had for the same day.
Re-downloading a range fills in the balance of rows stored without one (the duplicate back-fill).
**Descriptions are cleaned** (`cleanDescription`): BBVA sends one remittance line shaped
`bank category // operation // detail` (card detail = merchant in a fixed-width field + city +
country); it's rewritten into the shape BBVA's Excel export uses, which the rules were written
against (`Lidl centro — Pago con tarjeta`, `Transferencia realizada — OBA…`,
`Bizum — ENVIADO: Sin concepto`, `Ingreso en efectivo`). Checked on the real history: 44 of 65
came out identical to the Excel's. The raw text goes in `alternativeDescriptions` (`bank-raw`) so
movements stored raw before cleaning existed are still recognised. A card payment's detail is the card
networks' fixed-width field (25 chars merchant + 13 city + 2 country, `cardMerchant`): a merchant name of
24-25 chars leaves at most one space before the city, so it's cut at the field width (only when the
detail ends in a country code). The first cleaning didn't, and stored `Talleres mecanicos perez madrid`;
its output is offered as a second alternative (`bank-clean-v1`, `cleanDescription(raw, false)`) so
movements stored that way still dedupe. Editing a description in the app doesn't change its dedupe key. **PSU headers**: every
download sends `Psu-Ip-Address`/`Psu-User-Agent` (the browser's, from the request) — PSD2 caps
background reads (BBVA: 429 "Allowed number of accesses exceeded for consent" after ~4/day), not
reads with the user present; verified live that a transactions request refused without them
worked with them, with a loopback or LAN IP. A 429 becomes a clear "limitado las consultas" message.
Then it hands the rows to `TransactionImportService` — same dedupe/categorization as a file import.
Stored movements carry the bank's descriptions, so importing a BBVA *Excel* export of the same
period would store them again (the Excel's descriptions differ).

## `web/BankSyncController.java`

`GET /status`, `POST /link`, `DELETE /link`, `GET /callback`,
`POST /import` under `/api/bank-sync`; thin, all the logic above lives in `EnableBankingClient`.
In the demo (`app.demo=true`, `docs/demo.md`) `POST /link` and `POST /import` answer 503 "La demo no se
conecta a ningún banco…" instead of the "no está configurada" they would otherwise give.
`DELETE /link` (the "Desvincular" button) revokes the session at Enable Banking
(`DELETE /sessions/{id}`, which ends the consent at BBVA) and deletes the `BankLink` row; a 401/403/404
there means the session is already gone, so the row is deleted anyway, while any other failure keeps
it so the unlink can be retried. `/callback` is
a redirect target (a real browser navigation back from BBVA), so it responds with a 302 to
`/#import?bank=linked` or `?bank=error`, never JSON — including when the user cancels on the bank's
site, which comes back with `?error=` and no `code` (so neither parameter is `required`), and any
unexpected exception (logged, since the browser only sees `bank=error`). The saved `BankLink`
takes `session_id` and `access.valid_until` (what the bank actually granted) from the `/sessions`
response; the JSON record field names must match Enable Banking's API reference exactly — the first
real link failed because the code read `id` instead of `session_id`, which only a test feeding the
documented JSON (`EnableBankingClientTest`, over `MockRestServiceServer`) catches.

## Bank tab and expiry warning (frontend)

The Import view has a second tab, **Descargar del banco** (`selectImportTab`, same ARIA
tablist pattern as Categories/Reglas/Seguridad): not-linked state shows a "Vincular con BBVA"
button (`POST /api/bank-sync/link`, then a real page navigation — `location.href`, not
`fetch` — since it has to load BBVA's own login page); linked state shows the same account
input/datalist as the file tab and two ways to download. The main one, **Actualizar movimientos**,
needs no dates: `refreshBankAutoRange` asks `GET /api/transactions?account=X&sort=date&dir=desc&size=1`
for the account's newest stored movement and downloads from that date minus `BANK_OVERLAP_DAYS` (7)
to today (`autoRangeFor`; the overlap picks up movements that were still pending last time, and
what's already stored dedupes). It is derived from the data on purpose rather than from a stored
"last sync" date, so it stays right after a manual range download of an old month, an Excel import
or a restored backup; an account with no movements opens the other option instead. Changing the
account drops the old range at once and looks it up again after a 300 ms debounce
(`bankRangeRun` discards stale answers). The other option, folded in a `<details>` ("Elegir fechas"),
is the original two `<input type="date">` form. Both go through `runBankImport` →
`POST /api/bank-sync/import`, which reuses `showImportResult` unchanged (the response shape matches
`/api/import/confirm`'s). `import-tabs-wrap` wraps the tabs and both panels so `showImportStep`
can hide the whole thing when the shared result screen takes over, regardless of which tab
produced it. `viewFromHash` strips a `?query` before matching a view name, because the bank's
redirect callback comes back as a real page load at `#import?bank=linked` (or `=error`) —
`handleBankSyncReturn` (called from `loadImportView`) reads that once, toasts it, switches to
the bank tab, and cleans the URL with `history.replaceState` so a reload doesn't repeat it. An
expired bank session (401) shows inline in `#bank-sync-error`, not a toast — same reasoning as
`showImportError`. When the response's `pending` is > 0 the result screen adds a note that those
movements are still pending at the bank and will come in a later download.
**Expiry warning** (`bankExpiryNotice`, `showBankExpiry`, `refreshBankExpiry` at start-up, silent on
failure): within `BANK_EXPIRY_WARN_DAYS` (30) calendar days of `valid_until`, or once expired, a banner
(`#bank-expiry-banner`) sits on top of every view but Import, with "Renovar ahora" opening the bank tab
(`openBankTabNext`); there `#bank-renew` (linked) or `#bank-sync-expired` (above "Vincular") say it next
to the button. Renewing is the same `/link` flow (`startBankLink`). Off 127.0.0.1/localhost the text adds
"renuévala desde el PC" (see "Bank sync setup", step 4). `GET /api/bank-sync/status` has `expired`: a
stored link past its date (`linked` false but `valid_until` kept).

## Bank sync setup

**One-time, manual, outside the code): the "Descargar del banco" tab needs an
Enable Banking application registered by the user themselves —
1. Sign up at enablebanking.com and register an application in their dashboard; it issues an
   `application-id` and generates an RSA keypair, downloaded as a `.pem`.
2. Activate their "Linked Accounts" (free, individual, restricted-production) access by linking
   the real BBVA account once from *their* dashboard — this is separate from this app's own
   `/auth` → `/sessions` flow, which is what's used afterwards for re-authorizing every ~180 days.
3. Place the `.pem` at `data/enablebanking/private_key.pem` (gitignored like the rest of `data/`,
   but **not** covered by the automatic DB backup — it's a static app credential, not user data;
   if it's lost, generate a new one from the Enable Banking dashboard), and the application id in
   `data/enablebanking/app-id` (one line), which `iniciar.sh` exports as `ENABLE_BANKING_APP_ID` —
   or export that variable yourself. The id is kept out of git: not a secret without the key, but
   it identifies the registration.
4. The application's whitelisted redirect URL is `https://127.0.0.1:8000/api/bank-sync/callback`.
   `BankSyncController` builds the redirect URL from the request that started the link, so **link
   from the PC at `https://127.0.0.1:8000`**: started from the phone (LAN IP) it would send a URL
   Enable Banking doesn't know, and on the phone `127.0.0.1` would be the phone itself anyway.
Without this, `EnableBankingClient` fails any bank-sync call with a clear
`ApiException(SERVICE_UNAVAILABLE, ...)` rather than blocking the rest of the app from starting —
the manual-import flow works exactly as before whether or not this is configured.
