# Holidays view

Covers `service/holidays/` and the Festivos view.

## `service/holidays/`

The **Holidays view** ("📅 Festivos", `web/HolidayController`, `/api/holidays`): two places
(`HolidayPlace`, role `work` — decides the days off and the long weekends — and `home`, a second calendar to plan
visits), this year and the next. National/regional holidays come from the **BOE**'s yearly resolution "relación de
fiestas laborales para el año Y" (published Oct/Nov of Y-1; 2026's is BOE-A-2025-21667), found by reading the BOE's
daily summaries (`/datosabiertos/api/boe/sumario/yyyyMMdd`, `Accept: application/json`, no Sundays, a 404 day
skipped) up to 40 per refresh, resuming from `HolidayYear.scannedUntil`; its table (`HolidayParsers.boeTable`,
jsoup) has one column per region, marks `*`/`**` national and `***` regional; stored per region, "national" only if
every region has it. **Not the free holiday APIs** (Nager.Date, OpenHolidays): checked 2026-09-26, they miss the
Sunday holidays a region moves to Monday (Andalusia's 2 Nov and 7 Dec 2026, among others). Catalan towns' local holidays from the
Generalitat (`analisi.transparenciacatalunya.cat/resource/b4eh-r8up.json?codi_municipi_ine=`; dates only, no names;
2027's come out late in 2026); elsewhere the user types them in (`POST /days`, source `manual`, deletable).
`GET /towns` = the Generalitat's Catalan towns for the picker. `HolidayScheduler`: 90 s after start-up, then every 6 h
(`app.holidays.scheduled=false` in tests); status in `EconomySource` under key `holidays`. Frontend
(`loadHolidaysView`, `renderHolidays`): next holiday, long weekends (`bridgeFor`: the block of days off around a
weekday holiday, and the best one or two working days that join it to a weekend or another holiday — Mon-Fri work
week), home holidays that are working days here (`homeOnlyHtml`), a month-by-month calendar with a chip per place,
and the settings form. `holidayTodayOverride` lets tests pin today.
