# Demo

Covers `demo.sh`, `application-demo.yml`, `startup/DemoDataRunner` and
`startup/DemoTransactions`.

`./demo.sh` starts the app with invented data, to show it without real data (to try it
out, or for screenshots). It is the same jar with the `demo` profile (`application-demo.yml`):

- **Its own folder and port**: `target/demo-data`, `https://127.0.0.1:8001`, so it runs next to the
  real app (8000) and never reads or writes `data/`. The script empties the folder on each start
  (keeping `tls/`, so the browser warns only once) and runs its own copy of the jar,
  `target/demo-en-marcha.jar` (like `iniciar.sh`), refusing to start twice.
- **Account `demo` / `demo`** (`DataSeedRunner` with `app.demo=true`), printed on start.
- **Nothing reaches outside**: the price, economy and holiday schedulers are off, and
  `BankSyncController` refuses to link or download ("La demo no se conecta a ningún banco…"); the script
  also unsets `ENABLE_BANKING_APP_ID`. Adding a product or changing a holiday place still reads that
  shop / the Generalitat, as in the real app — only if someone does it by hand.

## What it is filled with (`DemoDataRunner`, only on an empty database)

- **Movements** (`DemoTransactions.build(today)`): 24 months up to today of one account, "Principal",
  with running balances. Each month comes from a `Random` seeded with that month, so a month is always
  the same whatever day the demo starts; only the window moves. A salary on the last working day (extra
  pays in June and December, +3 % a year), rent (+2 % a year), a monthly transfer to an index fund,
  electricity (dearer in winter and summer), phone, gym, Netflix (up from 12,99 to 13,99 € in April
  2026), Spotify, HBO Max (February 2025 to January 2026 only), a train pass, then a random
  number of supermarket, restaurant, Amazon, pharmacy, Cabify, cinema, cash and Bizum movements, plus
  what makes months differ: sales in January/July, home insurance in March, a tax refund in June, a
  holiday booked in July and taken in August, Black Friday, Christmas. Descriptions have the shape the
  bank download stores (`Mercadona sants — Pago con tarjeta`, `docs/bank-sync.md`) and are saved
  through `TransactionImportService.persist`, like an import. `DEMO_RULES` adds the rules a user would
  have made for the regular payees; Bizums, "Bonpreu" and "Bar el tomate" stay `Sin categorizar` on
  purpose, to try the rules and the rule suggestion on. `DemoTransactionsTest` pins all this, including
  which descriptions land in which category.
- **Prices**: five products with a few price changes each (one under its target, one that went up
  again, one out of stock, one back in stock). Their links look real but are invented; "Comprobar ahora" on one fails.
- **Economía and Festivos**: a saved copy of the real public data,
  `src/main/resources/demo/public-data.json.gz` (~64 KB), so the demo looks the same without internet.
  The work place is Barcelona (whose local holidays the Generalitat publishes) and home is Sevilla. The
  copy ages: its figures stop at the day it was taken and "Puentes" only reach the last year it has.

## Refreshing the saved copy of the public data

1. Start a scratch instance on an empty folder with the economy and holiday checks soon and often:
   ```bash
   ./mvnw spring-boot:run \
     -Dspring-boot.run.arguments="--server.port=8012 --app.data-dir=$PWD/target/snapshot-data --logging.file.name=$PWD/target/snapshot-data/app.log --app.prices.scheduled=false --app.economy.first-check-after=PT5S --app.holidays.first-check-after=PT5S --app.holidays.check-every=PT10S"
   ```
2. Set the two places with its printed admin password:
   `curl -k -u admin:PASS -X PUT -d "region=CT&town_code=08019&town_name=Barcelona" https://127.0.0.1:8012/api/holidays/places/work`
   and the same with `region=AN&town_name=Sevilla` for `home`.
3. Wait until `economy_sources` has every source and `holiday_years` has the BOE id of the years
   wanted (the BOE search reads 40 daily summaries per check), then stop it by PID
   (`pgrep -f "server.port=801[2]"`).
4. `tools/demo-snapshot.py target/snapshot-data/finanzas.db` writes the file (only public tables are read).
   Run `DemoModeTest` and commit it.
