# Fixed expenses and subscriptions

Covers `service/RecurringService`, `web/RecurringController` and
`static/views/recurring.js`.

The "🔁 Gastos fijos" view (`#recurring`) shows what gets charged by itself every month, two months,
three months or year. Nothing is typed in: it is found in the movements of the last 24 months each time
the view opens (`GET /api/recurring`). The only thing stored is which groups the user hid
(`recurring_hidden`, `RecurringHidden`).

## Detection (`RecurringService.detect`)

Only expenses (amount < 0) count. Each one goes into a group:

- **By payee** (`comercio:<payee>`), read the way the rule suggestion reads a description
  (`suggestKeyword` in `views/transactions.js`, ported as `RecurringService.Payee`): a card payment's
  merchant, a Bizum's concept, what a transfer or a direct debit names; cut at `*`, words with two
  digits or more dropped.
- **By category and amount** (`importe:<category>:<euros>`) when the description names nobody: a Bizum
  "Sin concepto", a transfer carrying only a reference, a cash withdrawal. For example, rent
  paid some months by Bizum and others in cash: no description ties it together, but "Vivienda,
  650 €" does. Amounts within 10 % of the smallest of their cluster go together (650 and 680).

A group is regular when the median gap between charges fits a cadence (monthly 20–40 days, every two
months 50–70, three 80–100, yearly 340–390) and at least 60 % of the gaps do. Its price is **fixed**
when at least 60 % of consecutive charges are the same price (±1 %): a price rise or a one-off extra
(an August train ticket on top of the monthly pass) doesn't break it. Then it needs 3 charges (2 if
yearly). A **variable** one (electricity) needs 6 charges, 75 % regular gaps, the last 12 within
0.25–1.75 × their median, and is never yearly: a holiday booked every July is not a fixed expense.
Shopping several times a month never fits a cadence, which keeps supermarkets and pharmacies out.

For each group: `amount` (the last price if fixed, else the median of the last 12, with `min`/`max`),
`monthly` (amount ÷ months of the cadence), `next_date` (last + cadence), `active` (last charge no older
than 1.5 × the cadence + 5 days; otherwise "Ya no se cobran"), `price_change` (fixed only: the newest
change that has held since, from a price paid at least twice before it, within a year — so a single
odd amount isn't one) and the last 12 `charges`. The report adds `monthly_total`/`yearly_total` (active,
not hidden) and `income_monthly` (what came in over the last three whole months, ÷ 3) for the "De lo
que entra" card.

Hiding (`POST /api/recurring/hidden`, `DELETE /api/recurring/hidden?key=`, form/query parameter because
a key holds a category name, which may contain `/`) keeps the group listed under "Los que no cuentas" but
out of the totals. A key built from an amount moves if that amount changes by more than rounding, and the
group then shows again.

The demo (`docs/demo.md`) has a Netflix
price rise and a cancelled HBO Max to show those cases. Tests: `RecurringServiceTest` (the cases above),
`RecurringApiTest`, `recurring.test.js`.

## View (`views/recurring.js`)

Three cards (per month, per year, share of income — hidden without income), then "Lo que pagas ahora"
(active, dearest first), "Ya no se cobran" and a folded "Los que no cuentas". Each row: name (plus the
category when grouped by payee), price with its cadence (and per month when not monthly; "≈" when
variable), next date or "Se esperaba hacia el …" when late, the price change, a `<details>` with the
charges, and "No es un gasto fijo" / "Volver a contarlo". `recurringTodayOverride` lets tests pin today.
