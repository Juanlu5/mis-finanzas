# Frontend

Covers `index.html`, the scripts and `style.css`.

## `src/main/resources/static/`

The frontend: `index.html`, `style.css` and plain scripts loaded in this order (no modules, no build
step; they share one global scope, so any file can call what another defines):

| File | What |
|---|---|
| `static/app.js` | Formatting, toast, theme and chart colours, `api()`, navigation (`showView`), and helpers several views use (`escapeHtml`/`escapeAttr`/`optionHtml`, `fmtInt`, dates `localIsoDate`/`addDaysIso`/`formatIsoDay`, `setFeedback`, `timeAgo`, `UNCATEGORIZED`) |
| `static/views/dashboard.js` | Dashboard, and the period helpers (`periodRange`, `periodOptionsHtml`, `previousPeriod`, `renderDeltas`, `renderAverages`) that `year.js` and `transactions.js` reuse |
| `static/views/year.js` | Yearly summary |
| `static/views/recurring.js` | Gastos fijos (`docs/recurring.md`) |
| `static/views/transactions.js` | Transactions: filters, table, bulk actions, the rule suggested after a correction |
| `static/views/import.js` | Import view, file tab, and the switch between its two tabs |
| `static/views/bank.js` | Import view, bank tab, and the banner before the bank consent expires |
| `static/views/categories.js` | Categories & rules, password |
| `static/views/prices.js` | Prices |
| `static/views/economy.js` | Economy (`economyCardHtml` is reused by `holidays.js`) |
| `static/views/holidays.js` | Holidays |
| `static/start.js` | Runs last: opens the view in the URL, fills the menu badges and the bank banner |

A top-level name must be unique across the files, and code that runs at load time (outside a
function) can only use what earlier files defined. A new view gets its own file, added to
`index.html` before `start.js`. Anything built
from data (category
and account names, column headers of an imported file) must go through
`escapeHtml`/`escapeAttr`/`optionHtml` before reaching `innerHTML`; note that
`escapeHtml` does *not* escape quotes, so use `escapeAttr` inside attributes.
Note the nested `static/static/` folder: `index.html` references `/static/app.js` and
`/static/style.css`, and Spring Boot serves `src/main/resources/static/**` at the root path, so the
JS/CSS live one level deeper.
Frontend conventions: the current view lives in the URL hash
(`#transactions`; `showView`/`viewFromHash`) so reload and back/forward keep
the place; a global `unhandledrejection` handler toasts any failed API call
that a handler didn't catch itself; chart colours are read from the CSS
variables via `themeColors()` (never hard-code hexes in chart code — the
category palette in `CATEGORY_PALETTE` is the one exception, for lack of enough
theme variables), and `groupTopCategories` folds all but the 8 biggest
categories into one grey "Otras (n)" slice. Dashboard renders bump
`dashboardRun`/`balanceRun` and drop stale responses, so keep that guard when
adding an `await` to them (`txRun` does the same for the transactions table).

## Categories, dashboard, transactions and yearly summary

Categories & rules view: three tabs (`selectCategoryTab`, ARIA `tablist` with arrow keys):
Categorías (cards with kind and rule count; `Sin categorizar` shows "Protegida" instead of
Eliminar because the server refuses it), Reglas (grouped by category as chips with a filter)
and Seguridad. The delete-category confirmation shows real numbers: movements come from
`GET /api/transactions?category=X&size=1` (`countTransactionsIn` — the paginated list's `total`
is the cheap count), rules from `allRules`. **Whole-word rules**: a keyword padded with a space
on both sides (`" dia "`) matches only the whole word, and the old UI trimmed the spaces so
it could not create them; the "Solo palabra completa" checkbox now pads it, and
`keywordLabel` shows padded rules without the padding plus a "palabra" tag (one-sided
padding shows as `␣`). Adding a rule/category is checked client-side for duplicates
ignoring case (the server only rejects identical names and lets duplicate rules through).
Form messages live next to the form (`setFeedback`, `role="status"`) rather than in toasts. The
password form asks twice and rejects < 6 chars locally because there is no way to recover a
forgotten password. The apply-rules button shows and refreshes the count of `Sin categorizar`
movements (`refreshPending`) and only touches those.
Dashboard **period** (`#dashboard-period`, `periodRange`, `selectedPeriod`): ONE selector for the whole
dashboard — the 12 newest months with data (`MONTH_OPTIONS`; default: the newest), last 3/6/12 months (ending at the current
calendar month), one entry per year with data (the current year stops at this month), all history
(value `""`), or a custom from/to built from every month in `months-available` ("Otro mes o periodo…").
An older month is picked there as from = to: `selectedChoice` reads such a range as that month (compared
with the month before, 12-month charts), while `periodSelectValue` (the raw `"custom"`) drives the pickers,
saving and the drill-down link. Cards and doughnut use exactly that
period (`periodQuery`: one month as `?month=`, a range as `?from=&to=`, all as no filter); over several
months each card also shows the average per month (`renderAverages`), over the months of the range that
have data (`monthsWithDataIn`), so a year the history starts in isn't spread over months before it. The trend charts use it too,
except for a single month (`chartPeriod`): then they show the 12 months up to it, that month's bars
highlighted (the rest dimmed) and "· 12 meses hasta …" next to the titles. The last choice is
**remembered** in `localStorage` (`dashboardPeriod`, `savePeriod`/`restorePeriod`, per browser): the
newest month is saved as `"latest"` so the default still moves on to each new month; an older month, a
range, all history or a custom from/to comes back as picked, and one that no longer exists (or junk)
falls back to the newest month. `loadApp({ storage })` in the frontend tests seeds `localStorage`. `renderMonthlyChart` has its
own `monthlyRun` guard. Over 24 months the bars are grouped by quarter (`groupMonthly`, "por
trimestres"); the balance axis unit follows the period length (`balanceAxisUnit`: week/month/quarter).
Cards compare with `previousPeriod` (`describeChange`, `renderDeltas`, one more `GET /api/summary` in the
same `Promise.all`): a month with the previous *calendar* month, a year with the same months a year
earlier ("2024", or "ene 25 – sept 25" for the current year so far), last N months with the N before
("los 3 meses anteriores"), a custom range with the same length before it ("el periodo anterior"). A
period still in progress (it ends in the current calendar month) is compared only up to the same day
(`until`, `sameDayIn` — clamped to shorter months; `GET /api/summary?until=` drops movements dated
after it): the payroll lands on the last day, so a whole previous month made every month in progress
look ~1,700 € worse. Its wording says "… a estas alturas" and the deltas carry a `title` explaining it.
The balance card is labelled **Ahorro** (income − expense of the period), not "Balance", which was
confused with the account balance. **Saldo actual** (`renderCurrentBalance`, first card, not tied to
the period): `GET /api/summary/current-balance` = per account, the balance after its newest movement
with one (`lastOfTheDay` for that day), summed; `date` is the stalest account's. It
reads `count === 0` as "no data" and
shows nothing for "Todo el histórico". Wording says más/menos in text and the arrow is
`aria-hidden` colour only (a rise is good for income/balance, bad for expenses); no
percentage on the balance or over a zero base.
**Learning from corrections** (`offerRule`, `suggestKeyword`): changing a row's category by hand (not
to `Sin categorizar`, not via bulk) shows `#rule-suggest` above the table — an editable keyword guessed
from the description (card merchant; Bizum concept, none for "Sin concepto"; whoever a transfer/payroll
names, else the operation; cut at `*`, words with 2+ digits dropped) and the impact from
`/api/rules/preview` (debounced, `ruleImpactRun` guard; buttons disabled while it's stale). "Crear regla
y pasar N a X" posts `apply=true`, "Crear regla sin cambiar esos N" doesn't; with nothing to move only a
primary "Crear regla" shows (`setRuleButtons`); a same-keyword rule is pointed at
instead of duplicated. The fake backend mirrors the matching/ranking for preview and apply.
**Category drill-down**: clicking a doughnut slice (not the grey "Otras", several categories) calls
`openCategoryTransactions`, a real navigation to `#transactions?category=X&period=<choice>` (the dashboard's
period choice, `&from=&to=` added for a custom range; no period for all history), so Back returns to the
dashboard. `applyTransactionsLink` (in `loadTransactions`, after the filters are populated) applies it once
and puts the hash back to plain `#transactions`: it picks the same choice in `tx-month` (both selectors are
built from the same `/months-available`, so it exists). A plain visit starts from "Todos los meses".
**Transactions period** (`tx-month`, `txPeriodParams`): the dashboard's choices (`periodOptionsHtml`,
`fillCustomRange`, `periodRange` are shared), with "Todos los meses" (`""`, no filter) on top instead of
"Todo el histórico" among the ranges. One month is sent as `month`, anything else as
`month_from`/`month_to` (accounting months, so it adds up with the dashboard); "Otro mes o periodo…" shows the
`tx-month-from`/`-to` pickers (`showTxCustomRange`).
**Yearly summary** (`#year`, "🗓️ Resumen del año", `loadYearView`/`renderYear`, `yearRun` guard): a year
picker (years with data), four cards (income/expense/Ahorro + saving rate, averages per month *with data*,
deltas and the rate's change in points against the year before, only when that year has data — reusing
`renderDeltas`/`renderAverages` with an id prefix and `previousPeriod`, so a year in progress is compared up
to the same day), "Lo más destacado" (`yearMonthRanking`: months with data only, never the current month,
which is unfinished), the 5 biggest expenses (`/api/transactions?kind=expense&sort=amount&dir=asc`) and the
categories as bars with share and change; clicking one opens `#transactions?category=X&period=year-YYYY`.
Built only from existing endpoints; `GET /api/summary/by-category` takes `until` (with `from`/`to` only) for
that same-day comparison.
Transactions table layout: every column but the description has a fixed `<col>` width and
`.tx-table` a `min-width` (1000px) that leaves the description ~240px; narrower (medium windows, browser
zoom) it scrolls inside `.table-scroll` — which only works because `#main` has `min-width: 0` (a flex item
otherwise grows to its content and pushes the whole page sideways). Headers end in "…" rather than
overlapping. The cards are 4 per row, 2 below 1250px, 1 on mobile.
**Amount filter** (`tx-kind`, `tx-amount-min`/`-max`, `parseAmountFilter`, `amountFilter`): a
gastos/ingresos select plus two text inputs (`inputmode="decimal"`, not `type="number"`, whose comma
handling depends on the browser's locale) in euros without sign, read the Spanish way (`45,30`, `1.000`,
`12 €`; a dot + 3 digits is thousands, like `normalizeAmount`). An input that isn't an amount, or both
when "desde" > "hasta", is left out of the request, marked `aria-invalid` and explained in
`#tx-amount-msg`, and the list still reloads with the rest — it never shows results for a filter that
isn't applied. Typing is debounced like the search box. A dashboard link clears these (and the search)
so leftovers can't narrow it silently.
Transactions table: `txState` holds page/size/sort/dir; `selectedIds` survives
paging and re-sorting but is cleared by any filter change (`restartTransactionList`),
so bulk actions can act on rows that aren't on screen. Behaviour is covered by the
frontend tests (see Commands), but not how it looks: check visual changes on a
scratch instance (see `docs/testing.md`) in a real browser —
`firefox --headless --no-remote --profile <tmpdir> --screenshot` works, but
captures at the `load` event, before the API fetches finish. To see a view *with data*, build it in
jsdom with the test helpers (`loadApp` + `fakeBackend`, drive it, `await app.settle()`), clone the
document, drop the `<script>`s, put back the two `<link>` stylesheets `helpers.js` strips (as `file://`
paths), copy input/select values into attributes (a clone loses them), write it to a file and screenshot
that. Charts don't draw (Chart.js is stubbed), layout does. Keep the window **taller than 500px**:
below that the mobile layout kicks in (`max-height: 500px` media query).
