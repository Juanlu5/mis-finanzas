# Importing files and avoiding duplicates

Covers `ImportService`, `TransactionImportService`, the dedupe keys and the Import view (file tab).

## `service/ImportService.java`

The heaviest piece, ported from the
original `importer.py`: `HINT_GROUPS` keyword-based column guessing,
header-row detection (skips title/metadata rows above the real table),
locale-tolerant `normalizeAmount`/`normalizeDate` (`1.234,56` vs
`1,234.56`, several date formats), and `makeDedupeKey` (SHA-256 of
account+date+description+amount, used to skip re-importing the same
transaction; **the amount goes in written as the old `double` code wrote it** — `Math.round(x * 100.0) /
100.0` on `amount.doubleValue()`, so `-45.3`, `1700.0`, even `1.2E7` — because every stored key was hashed
that way: writing `-45.30` would make the next download store everything again.
`ImportServiceTest.keysAreExactlyTheOnesStoredWhenAmountsWereDoubles` pins real hashes computed by the old
code; the other key tests only compare keys with each other and would not notice). Identical rows *within one file* (two coffees, same day, same
amount) get an occurrence number appended to the key; occurrence 0 hashes
exactly like the original key, so rows already stored keep matching.
**The key hashes the stored description, which is the description column plus the
optional "detail" column** (`desc — detail`), so it used to change whenever the user
picked another detail column (or the column guessing changed) and a re-import re-added
every row. Fixed *without* touching stored keys: `buildRows` gives each `ImportRow`
`alternativeDescriptions` (`alone` = description column only, `with:<column>` = with each
other text column as the detail; columns mapped to date/amount/debit/credit/balance/
description are never details), and `TransactionImportService.persist` looks every row up under
its own key AND all the alternatives' keys, in one batched `findByDedupeKeyIn` (chunks of
500, SQLite's parameter limit) taken *before* inserting anything. The occurrence number is
counted **per kind** of description (`keyFor`), because what makes two rows "identical"
depends on the description and it must be counted the way the import that stored them
counted it. New rows are stored under the canonical (chosen-mapping) key only. Residual
ambiguity: a row whose description column equals an already-stored description is
treated as that row, even if its detail differs; and an earlier import that used a
*different description column* is not recognised.
Excel via Apache POI, CSV via Apache Commons CSV. CSV: decoded as UTF-8 only
if the bytes are valid UTF-8, otherwise Windows-1252 (the lenient UTF-8
decoder never throws, so a plain "try UTF-8 then Latin-1" never falls back);
the delimiter (`; , tab`) is the one giving the most rows of equal width, not
the first that yields >1 column (a TSV with decimal commas). `guessMapping`
never maps a non-date field to a column matching a date hint (`Fecha valor`
contains the amount hint `valor`). `normalizeAmount`: a lone dot + 3 digits
is a thousands separator (`1.234` = 1234) unless the integer part is 0;
U+00A0/U+202F are stripped. Excel **date
cells are read as real dates** (rendered ISO), never through `DataFormatter`
text: that gives `3/15/26` (US order) for Excel's default short date whatever
the locale, which wasn't parseable and would swap day/month if accepted.
`normalizeDate` is STRICT (31/02 is rejected, not clamped to 28/02).
Commons-csv 1.12.0 needs commons-io 2.17.0+ (`UnsynchronizedBufferedReader`)
— pinned explicitly in `pom.xml` because `poi-ooxml` otherwise drags in an
older commons-io that wins Maven's nearest-dependency-wins resolution and
breaks CSV parsing at runtime with a `NoClassDefFoundError`.

## `service/TransactionImportService.java`

The dedupe-lookup/categorize/save logic that both a
manual file import and an automatic bank sync need, extracted out of `ImportController` (which
used to have it inline) so neither reimplements it. `persist(List<ImportRow>)` is
`@Transactional` here (moved from the controller when this was extracted): all inserts and
balance back-fills of one batch commit together or not at all — before this lived anywhere,
`transactionRepository.save` per row meant a commit per row, **~18 s to import 3,000 new rows**
on a scratch instance, down to **~1.85 s** in one transaction, and a failure halfway used to leave
the batch half-imported with no way to tell short of noticing missing rows.
`ImportApiTest.aFailureMidImportLeavesNothingStored` proves atomicity by spying on
`TransactionRepository.save` to fail one row and asserting nothing persisted; because
`ApiTestBase` wraps every test in a rolled-back transaction, that one test opts out with
`@Transactional(propagation = Propagation.NOT_SUPPORTED)` so the service's own transaction is the
real, physically-committed/rolled-back one instead of just joining (and marking rollback-only on)
the test's.

## Import view (frontend)

Import view (`IMPORT_FIELDS`, `updateImportState`): three steps toggled with `hidden`
(upload -> preview/column assignment -> result). The form mirrors what the backend does:
`amount` wins over `debit`/`credit`, so choosing one locks the other; date, description, an
amount and the account name are required and the button stays disabled with a hint saying
what's missing (a mapping without them makes the server skip *every* row, returning
`added: 0, skipped: N`, which the UI turns into an inline error instead of a "done"
screen). Errors go in `#import-error` (persistent, `role="alert"`), not a toast. The account
is pre-filled only when unambiguous (none -> "Principal", one -> that one, several -> empty)
so a wrong one is never picked silently. The dropzone is a real `<button>`; extension and the
25 MB limit are checked before uploading. A global `[hidden] { display: none !important }`
rule exists so `hidden` beats any `display` set by a class.
The result screen reads `added`/`duplicates`/`skipped` from `/api/import/confirm`;
"duplicates" also counts rows recognised through another detail-column choice (see the
dedupe note under `service/ImportService.java`).
