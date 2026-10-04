# Server: data, API, security and start-up

Covers entities, repositories, controllers, `SummaryService`, `CategorizationService`, `security/` and `startup/`.

## `entity/`

JPA entities: `Transaction`, `Rule`, `Category`, `AppUser`, `BankLink`; for price tracking
`TrackedProduct` + `PricePoint` (see `service/prices/`); for the Economy view `EconomyPoint` +
`EconomySource` (see `service/economy/`); for Holidays `Holiday`, `HolidayPlace`, `HolidayYear` (see
`service/holidays/`).
**Money is `BigDecimal` with two decimals** everywhere in Java (`Transaction.amount`/`balance`,
`ImportRow`, `SummaryService`'s totals, the JSON — `-421.00`), so sums are exact; `double` used to be
used and 0.29 is really 0.28999… in one. `entity/Money`: `Money.of` rounds to cents (half up),
`Money.cents`, and `Money.Column`, the JPA converter. SQLite has no exact decimal, so the columns **stay
REAL** (no data migration: `ddl-auto=update` sees the same `float` type) and the converter rounds on the
way in and out — a two-decimal amount read back from a REAL is exactly its cents. On Postgres they'd be
`numeric(19,2)` and the converter would go. `Transaction`'s setters round too. Pass `BigDecimal.ZERO`,
not `0`, when comparing the `amount` path in a `Specification` (the converter gets the literal). Tests
write amounts as `eur(-45.3)` (`TestMoney`); `BigDecimal.equals` looks at the scale (`45.3` ≠ `45.30`),
so compare what didn't go through `Money.of` (e.g. `normalizeAmount`'s result, which keeps the text's
decimals) with `isEqualByComparingTo`. Moved on 2026-09-24, checked on a copy of the real data: every
dashboard total identical to the cent and a full bank re-download added 0 of 65.
`AppUser` has a `role` column from day one even though there's a single
admin user today, so adding real multi-user/roles later is additive.
`BankLink` is the single Enable Banking authorization this (single-user) app
holds (which ASPSP, which account, the session and its expiry); re-linking
updates the one row in place rather than adding another. It lives in
`finanzas.db` like everything else, so it's covered by the existing
automatic backup for free — no separate secrets store was introduced for it.

## `repository/`

Spring Data JPA repositories. `TransactionSpecs` builds
dynamic filter `Specification`s; use `TransactionSpecs.combine(...)` to AND
them together, **not** `Specification.allOf(...)` directly — this Spring
Data version's `allOf` throws `IllegalArgumentException` ("Other
specification must not be null") when any argument is null, unlike its
Javadoc-implied null-safety. `combine` filters nulls itself.

## `service/SummaryService.java`

Dashboard aggregations (monthly
income/expense, balance history, by-category breakdown), in-memory over
`findAll()` — fine at this app's scale (a personal account's transaction
history), matches the original's simplicity. `GET /api/summary/monthly?from=&to=` (YYYY-MM, both or
neither; without them it's the old "last `months` months with data") returns **every** month in the
range, empty ones as zeros. `GET /api/summary/balance-history?account=&from=&to=` (dates) returns one
point per day and account, the balance at the end of that day: `lastOfTheDay` finds the last movement
from the balances (each movement starts from the previous one's balance, its own balance minus its
amount; the last is the only balance no other starts from), falling back to the highest id. Ids don't
say the order: a bank download stores a day's movements newest first — on the real data, the highest
id was the wrong one on all 12 days with several movements.

## `service/CategorizationService.java`

Keyword-substring matching, same
idea as the old `categorize.py`, but rules are tried **most specific first**
(longest keyword wins, then lower id) instead of "first row wins", so
priority doesn't depend on the order SQLite returns rows in and a rule
added later can override a generic one. The description is matched padded
with spaces (a keyword like `" dia "` matches the whole word, not `media
markt`); blank keywords are ignored.

## `security/`

`SecurityConfig` requires HTTP Basic auth on **every**
request (stateless, no session/cookies, CSRF disabled — same posture as
the old hand-rolled `BasicAuthMiddleware`, since this app is reachable from
the whole home WiFi). `AppUserDetailsService` reads users from the `users`
table via Spring Security's standard `UserDetailsService`. **Lockout**: `LoginThrottleFilter` (added
before `BasicAuthenticationFilter`, built with `new` so Boot doesn't also register it as a servlet
filter) asks `LoginAttemptLimiter`: after `app.login.max-failures` (5) wrong passwords in a row from one
address it answers 429 + `Retry-After` + a `{"detail"}` for `app.login.lock-minutes` (15); a request
that sent Basic credentials and got a 401 counts as a failure, anything else as a success (which
clears the count), no credentials at all doesn't count. Loopback is never locked (the owner can't
lock themselves out on the PC). In memory only. Tests run from 127.0.0.1, so lockout tests set
another `remoteAddr`, a different one per test (the limiter is shared by the Spring context).
**Content-Security-Policy** (`SecurityConfig.CONTENT_SECURITY_POLICY`), a second lock behind
escaping: scripts, fonts, images and `fetch` only from the app itself, no framing, no `<base>`; styles
allow `'unsafe-inline'` for the `style=""` attributes. So **no inline `<script>` and no `on*=""`
handlers**, in `index.html` or in markup the scripts build — the browser would silently block them
(`SecurityApiTest` checks `index.html`); that's why the pre-paint theme code lives in
`static/theme.js`, which the frontend tests inline like the other scripts. Setting `el.style.x` from JS is fine.

## `startup/DbBackupEnvironmentPostProcessor.java`

Snapshots
`data/finanzas.db` into `data/backups/` **before** Hibernate's schema
update touches it. Registered via `META-INF/spring.factories` so it runs
before the DataSource bean is even created (a plain `CommandLineRunner`
would run too late, after schema changes). Old copies are thinned out by
age (`toKeep`): the newest `app.backups-to-keep` (default 10, minimum 1) always, then the newest of
each day for 14 days, of each week for 8 weeks and of each month for 12 months — keeping just the
N newest (the old rule, N=30) covered only 6 days after a few days of restarts. The date is read from
the file name (`finanzas_yyyyMMdd_HHmmss.db`; the copy keeps the db's mtime); an unreadable one is
kept, and files with other names (`manual-*.db`) are never touched. A failed backup must never block startup
(better to run without one than not run at all), but it still has to be
visible: it takes a `DeferredLogFactory` constructor param (Spring Boot's
supported way to log from an `EnvironmentPostProcessor`) because this runs
before the logging system is configured — a plain SLF4J logger here would
only ever reach the console, never the log file the rest of the app writes
to, and a bare `System.err.println` (the original approach) is easy to miss
once nobody's watching the console anymore, e.g. on the second run onward,
after the password's already been noted down.

## `startup/TlsKeystoreEnvironmentPostProcessor.java`

Serves the app over **HTTPS** (port 8000,
no plain-http port: an `http://` request gets Tomcat's "requires TLS" 400). On the first run it
generates a self-signed PKCS12 keystore at `data/tls/keystore.p12` (owner-only permissions) by
running the JDK's own `keytool` — no crypto dependency, since the JDK has no public API to build an
X.509 certificate — valid for `localhost`, `127.0.0.1` and the machine's LAN IPv4 addresses at that
moment; it's reused afterwards (regenerating would make every device warn again). Delete `data/tls/`
to regenerate it, e.g. after the LAN IP changed. Why: the app is used from the phone over the home
WiFi with HTTP Basic, which over plain http sends the password readable on every request, and
Enable Banking only accepts `https` redirect URLs in production. Same rules as the backup
post-processor: registered in `spring.factories` (server.ssl.* must exist before the web server is
built), `DeferredLogFactory` for logging, and a failure logs a warning and starts on plain http
rather than not starting. `app.https.enabled=false` turns it off (the test `application.properties`
does, so tests never generate certificates). `SecurityConfig` disables HSTS on purpose: with a
self-signed certificate that may be regenerated, a cached HSTS entry would take away the browser's
"continue anyway" button and lock the user out of their own app.

## `startup/DataSeedRunner.java`

Seeds default categories/rules and the
initial admin user on first run (mirrors `seed_defaults()` +
`ensure_credentials()`). Prints the password through an explicit
UTF-8 `PrintStream` — Windows console's default charset otherwise mangles
the accented characters in that message. It only seeds *empty* tables, so
rules/categories edited in the running app live in `finanzas.db` only (not in
`DEFAULT_RULES`), and fixing a seed rule in code doesn't reach an existing
database — edit it in the app. `@Order(1)`; with `app.demo=true` the account is `demo`/`demo` (nothing
secret is printed) and `DemoDataRunner` (`@Order(2)`) fills the rest — `docs/demo.md`.

## `web/`

REST controllers. Errors are `{"detail": message}` (`GlobalExceptionHandler`), the shape the
frontend's `api()` reads. `GET /api/transactions` is paginated and sortable and returns
`{items, total, page, size, pages}` (`sort` = `date|accounting_month|description|account|category|amount`,
`dir` = `asc|desc`, `page` from 0, `size` 1..200, default 50; anything else is a 400).
`sort` is a whitelist mapped to entity properties — never pass the request value
to the query — text columns sort case-insensitively, and `id` is always the last
sort key so equal rows keep a stable order across pages. Past-the-end pages come
back empty with the real `total`, so the UI can step back. `month_from`/`month_to` (both or neither,
YYYY-MM) filter a range of accounting months (`TransactionSpecs.accountingMonthBetween`, also used by
`SummaryService`). `kind` = `expense|income` filters by sign, and `amount_min`/`amount_max` (either or both,
finite and >= 0, min <= max, else 400) by the **size** of the amount, sign ignored, with half a cent of
slack on each end (`TransactionSpecs.amountBetween`: the comparison runs in SQLite, on REALs). Other endpoints:
`PATCH /api/transactions/{id}` and `POST /api/categories` /
`POST /api/rules` take `@RequestParam` (not `@RequestBody`) because the
frontend sends `application/x-www-form-urlencoded` bodies — Spring's
`FormContentFilter` parses those into request parameters automatically for
PATCH/PUT, but only if the handler *doesn't* also try to read the raw body
itself (an earlier `@RequestBody MultiValueMap` approach silently returned
an empty body because the filter had already consumed the stream).
`DELETE /api/categories?name=` takes the name as a **query parameter** (the
path form `/{name}` is kept for plain names): Tomcat rejects an encoded `/`
(`%2F`) in a path, and the seeded category is called `Nómina / Ingresos`.
Deleting a category moves its transactions to `Sin categorizar` and deletes
its rules; `Sin categorizar` itself can't be deleted (imports depend on it).
`POST /api/rules` rejects a blank keyword (400); with `apply=true` it also moves the movements the new
rule now decides to its category (returns `updated`). `GET /api/rules/preview?keyword=&category=` says
what a rule would do without creating it (`matches`, `already`, `would_change` + `from` per category,
`other_rule` = kept by a more specific or older same-keyword rule, `existing_rule`); both use
`CategorizationService` itself on the full rule set plus the candidate (no id, so it loses ties like a
newly saved rule would), so the preview can't drift from real matching.
`POST /api/import/confirm` builds `ImportRow`s from the uploaded file, then hands them to
`TransactionImportService.persist` (dedupe/categorize/save, atomic — see that service's note)
and returns its counts alongside `built.errors()`/`table.rows().size()`.

**Data model notes**: `Transaction.accountingMonth` drives dashboard grouping and is user-editable
independent of `date`; `Transaction.balance` is nullable and only backfilled
on duplicate rows during re-import, never overwriting category or date.

**No Flyway**: schema changes go through `spring.jpa.hibernate.ddl-auto=update`,
which auto-adds new columns/tables from entity changes — deliberately kept
as simple as the original's hand-rolled `PRAGMA table_info` migration, since
this is still a single-user local SQLite app. If/when this moves to
Postgres or multi-user, introduce Flyway then rather than now.
