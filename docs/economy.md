# Economy view

Covers `service/economy/` and the Economía view.

## `service/economy/`

The **Economy view** ("📈 Economía", `web/EconomyController`, `GET /api/economy?region=ES|CT|…`):
public data, not the user's. Sources, all checked live on 2026-09-26: the **INE** JSON API (`servicios.ine.es/
wstempus`: IPC by community and ECOICOP group — table 76136 —, rent from the subgroups table 76137 found by name
then read per series, national subclasses 76128 for "what changes most", the IRAV rent cap series `IRAV1`, house
prices IPV 80270, quarterly), the **Ministry's fuel prices** (every station; averaged per community, public-sale
stations only; also has history by date) and **Red Eléctrica**'s PVPC (hourly; ~a month per request, `time_trunc=
day` is refused). `IneParser` picks series by their **name parts** ("Cataluña. Transporte. Variación anual."), never
by code: the INE renumbered everything when it rebased in January 2026; table ids may still change on the next
rebase. `Region` maps each source's naming — the Ministry numbers communities differently from the INE (07/08
swapped). Stored as `EconomyPoint` (indicator, region, text period "2026-08" / "2026-Q2" / "2026-09-26" /
"2026-09-26T13", value, `arrived_at`) + `EconomySource` (last attempt/success/error). `EconomyScheduler` ticks
1 min after start-up and hourly (`app.economy.*`; `app.economy.scheduled=false` in tests); `refreshDue` re-reads a
source after its interval (INE 12 h, fuel 6 h, power 3 h) and backfills **one month** of fuel (the 1st of each month,
~12 MB per national list) and power history per tick, 24 months back (a past month counts as done with at most
one day missing). The PC is off every night: the power read starts the day after the newest stored daily price
(up to 28 days back), so days off leave no gap; fuel only has the days the app ran plus each 1st. A failure keeps
the last data, shows the reason under "Fuentes" and is retried after `app.economy.retry-after` (55 min), not the
whole interval. **The fuel server only offers TLS_RSA ciphers**, which Java 21 disables globally
(`jdk.tls.disabledAlgorithms`): it hangs up on Java's handshake. That one source is read with
the system's `curl` (`EconomyFetcher.getWithCurl`) rather than re-enabling the ciphers for the whole JVM (bank
connection included). `EconomyReport` builds the view's JSON from the DB only; IRAV, subclasses and electricity
are Spain/peninsula-wide and come as `ES` whatever the region. Frontend (`loadEconomyView`, `economyRun`): region
select remembered in `localStorage` (`economyRegion`), cards with inline-SVG sparklines (`sparklineSvg`; power as
weekly averages), group bars, hour bars (3 cheapest marked; PVPC can be negative), and a menu badge
(`#economy-badge`, `economyNewsCount`) counting monthly/quarterly figures that arrived since the last visit
(`economySeen`; daily prices don't count; nothing counts before a first visit). Each section is a `<details
class="economy-block" data-block=…>` the user can fold; the folded ones are remembered per browser in
`economyFolded` (only folded ids are stored, so a new section starts open). Folding only hides: data is still
downloaded and rendered.
**Phase 2** (money, jobs, Europe; checked live 2026-09-26): INE home mortgages by community (table 76316, rows
starting "Viviendas": count, and the average amount = the INE's total in thousands of € ×1000 / count), mortgage
interest at signing (76315, Spain only: total/fixed/variable), EPA unemployment by community and under-25s
(65334, quarterly), ETCL gross wage cost per worker and month, all sectors (6061, quarterly) — in these tables the
region isn't always the first name part, so `IneParser.Series.region()` finds it anywhere ("Total Nacional" = ES);
the **Bank of Spain** API (`app.bde.es/bierest`, `rango=30M` — "12M" is refused) for the 12-month Euríbor
(`D_1NBAF472`, `BdeParser`); the **ECB** data API in CSV for the deposit/main refinancing rates (one observation per
decision; drawn as steps) and USD/GBP reference rates (`EcbParser`) — it must be asked with `Accept: text/csv`
(`EconomyFetcher.get(url, accept)`): with `Accept: application/json` it answers SDMX-JSON even to `format=csvdata`;
and **Eurostat** JSON-stat (`EurostatParser`) for Spain vs `EU27_2020`: HICP annual rate (`prc_hicp_minr`,
`coicop18=TOTAL&unit=RCH_A` — the old `prc_hicp_manr` stopped in 2025) and monthly unemployment (`une_rt_m`), stored
with region `EU` (a region code, not a `Region`). Unemployment and wages are compared with the same quarter a year
before (`yearBefore`), mortgages with the same month: all three are seasonal.
