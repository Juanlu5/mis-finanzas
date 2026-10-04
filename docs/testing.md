# Testing

Details behind the test commands in the README.

## Java tests

Unit tests (`ImportService`, `CategorizationService`, seed rules, the two
`EnvironmentPostProcessor`s, and `EnableBankingClient`: JSON mapping, description cleaning,
running balances, and its HTTP calls — link, paged download, balances, PSU headers, 429 — against
a `MockRestServiceServer` with Enable Banking's documented JSON and a throw-away RSA key; the economy and
holiday parsers, fed JSON/XML cut from what each source really sent),
`@DataJpaTest` tests against in-memory SQLite (specs, `SummaryService`,
`TransactionImportService`, `DataSeedRunner`, `EconomyService`, `HolidayService` — these two with the
fetcher mocked, so tests never reach the internet), and
`@SpringBootTest` + MockMvc API tests (`web/*ApiTest`, all extend `ApiTestBase`,
which boots the app on a throw-away temp `data/` dir and resets the admin
password to a known one). `src/test/resources/application.properties` points
`app.data-dir` at `target/test-data` so tests never touch the real `data/`:
`DbBackupEnvironmentPostProcessor` runs before `@DynamicPropertySource` applies,
so without it every test context would back up (and eventually prune) the real
`data/backups/`. Boot 4 split the
test starters by module: the `@DataJpaTest`/MockMvc annotations live in
`spring-boot-starter-data-jpa-test` / `spring-boot-starter-webmvc-test`
(`org.springframework.boot.data.jpa.test.autoconfigure`, `...webmvc.test.autoconfigure`).

`./mvnw test` also writes a JaCoCo report to `target/site/jacoco/` (`index.html`,
`jacoco.csv`, `jacoco.xml`); `lombok.config` keeps Lombok-generated getters out
of the numbers. Coverage is Java only (the frontend has its own tests, next section).

## Frontend tests

```
Needs Node 22+ (jsdom 30 requires `^22.22.2 || ^24.15.0 || >=26`); some
distros' packaged Node (e.g. 18) is too old. `helpers.js` loads the **real** `index.html` and
its scripts into jsdom — every `/static/` script but the vendored ones (`app.js`, `views/*.js`,
`start.js`, `theme.js`) inlined as a `<script>`, in the page's order, so their top-level `let`/`const`
(`txState`, `selectedIds`...) live in the global scope like in a browser — with
`window.fetch` replaced by `fakeBackend()` (a stateful in-memory API that pages, sorts,
filters and handles deletes; also stateful categories, rules, apply-all and change-password) and Chart.js stubbed to record its configs. Test files:
`transactions-table`, `rule-suggestion`, `dashboard`, `year`, `recurring`, `import`, `bank-sync`, `categories`, `navigation`, `pure-functions`, `prices`, `economy`, `holidays`
(`*.test.js`). Things to
know: values created inside the page have another realm's `Array.prototype`, so wrap them
in `JSON.parse(JSON.stringify(...))` (or `app.value(expr)`) before `assert.deepEqual`;
jsdom doesn't raise `unhandledrejection` itself, so that test dispatches it by hand;
CSS is not loaded, so layout and colours are **not** covered (check those in a real
browser). To confirm a test can fail, break the view's script on purpose and re-run — that is how
these were validated. When the API's response shape changes, update `fakeBackend()` too:
it is a copy of the contract, not the real thing.

## The USB backup (`copia-usb.sh`)

`./copia-usb.sh [folder]` (run by the user, it asks for a passphrase) puts `data/finanzas.db` (via
`sqlite3 .backup`) plus `data/enablebanking/` and `notas/` (local notes, kept out of git) in one gpg-symmetric (AES-256) `MisFinanzas_<stamp>.tar.gpg`
under `<folder>/MisFinanzas-copias/`, default the USB at `/media/$USER/PENDRIVE` (refused if that isn't a
mount point). It only ever creates new files there — never deletes or overwrites, the USB holds the user's
other files — and re-decrypts the written file to check it. Restore steps are in its header. To test it
without a passphrase prompt, put a `gpg` wrapper adding `--batch --pinentry-mode loopback --passphrase x`
first in `PATH`, with a throw-away `GNUPGHOME`.

## Testing changes manually

On top of `./mvnw test`, a scratch instance is the
safe way to try a change against a real server before touching the instance
holding real data:
```bash
./mvnw spring-boot:run \
  -Dspring-boot.run.arguments="--server.port=8011 --app.data-dir=$PWD/target/scratch-data --logging.file.name=$PWD/target/scratch-data/app.log"
```
(`--app.data-dir` gives it its own SQLite file and certificate, seeded from scratch — it's on
`https://127.0.0.1:8011`, so use `curl -k` — and the
log override keeps it out of the real `logs/app.log`; the admin password is
printed to that console once.) Some things only show up on a real
Tomcat, not in MockMvc — e.g. an encoded `%2F` in a path. `data/` (SQLite DB,
backups, auth) is gitignored and must never be committed.

To see what a change does **to the real data** without touching it: copy the live DB with `sqlite3 -readonly data/finanzas.db
".backup target/sim-data/finanzas.db"`, change the copy (e.g. `delete from transactions; delete from
users;` — an empty `users` table makes the instance print a fresh admin password), run the jar on it
(`--server.port=8012 --app.data-dir=$PWD/target/sim-data`, plus
`--app.enable-banking.private-key-path=$PWD/data/enablebanking/private_key.pem` and
`ENABLE_BANKING_APP_ID` in the environment to reach the bank with the real link), drive it with
`curl -k -u admin:<pwd>`, then diff the two databases opened read-only. The password is printed a
moment *after* "Started", so wait before grepping for it. Delete `target/sim-data` afterwards:
it holds real movements. Two traps: stop such an instance by PID (`pgrep -f 'java.*8012'`), not
`pkill -f <pattern>`, which also matches — and kills — the shell running the command; and don't
probe the bank API by hand (curl + a JWT signed with the key) without the `Psu-*` headers: every
header-less request counts against BBVA's ~4-a-day background cap for the consent, and exhausting it
makes the app's own downloads fail until the next day for any code that doesn't send them.
