// Economy (#economy): public data about Spain. economyCardHtml is reused by holidays.js.
// Loaded after app.js (shared helpers and navigation); see index.html for the order.

// ---------- Economy ----------
// Public data about Spain's economy — the INE, the Ministry's fuel prices, Red Eléctrica's electricity price —
// that the server downloads and stores on its own (EconomyService); this only reads GET /api/economy?region=.
// The chosen community is remembered per browser. The menu badge counts the monthly and quarterly figures that
// arrived since the view was last opened (the daily prices would light it up every day, so they don't count).
const ECONOMY_REGION_KEY = "economyRegion";
const ECONOMY_SEEN_KEY = "economySeen";
const ECONOMY_NEWS = ["inflation", "rent", "irav", "buy", "euribor", "mortgages", "unemployment", "salary"];
const ECONOMY_CHEAP_HOURS = 3;
let economyRun = 0;

function readStorage(key) {
  try { return localStorage.getItem(key); } catch (e) { return null; }
}

function writeStorage(key, value) {
  try { localStorage.setItem(key, value); } catch (e) { /* per-browser convenience only */ }
}

const fmtDecimal = (n, digits) => n.toLocaleString("es-ES", { minimumFractionDigits: digits, maximumFractionDigits: digits });

// One decimal, or two when the figure has them (the IRAV comes as 2,47).
const rateDecimals = (v) => (Math.abs(v * 10 - Math.round(v * 10)) > 1e-9 ? 2 : 1);

// A yearly rate of change as the INE gives it (already in %): "+4,0 %", "−2,1 %", "+2,47 %".
function fmtRate(v) {
  return `${v > 0 ? "+" : v < 0 ? "−" : ""}${fmtDecimal(Math.abs(v), rateDecimals(v))} %`;
}

// "2026-08" -> "agosto de 2026", "2026-Q2" -> "2.º trimestre de 2026", "2026-09-26" -> "26 sept 2026"
function economyPeriodLabel(period) {
  let m = period.match(/^(\d{4})-(\d{2})$/);
  if (m) return new Date(Number(m[1]), Number(m[2]) - 1, 1).toLocaleDateString("es-ES", { month: "long", year: "numeric" });
  m = period.match(/^(\d{4})-Q(\d)$/);
  if (m) return `${m[2]}.º trimestre de ${m[1]}`;
  m = period.match(/^(\d{4})-(\d{2})-(\d{2})$/);
  if (m) return new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3])).toLocaleDateString("es-ES", { day: "numeric", month: "short", year: "numeric" });
  return period;
}

// What a rate did since the period before, in points: "0,7 puntos más que en julio de 2026". `digits`: for
// figures published with more decimals (the Euríbor has three).
function rateChange(last, previous, digits = 2) {
  const diff = Math.round((last.value - previous.value) * 10 ** digits) / 10 ** digits;
  const when = /-Q\d$/.test(previous.period) ? `el ${economyPeriodLabel(previous.period)}` : `en ${economyPeriodLabel(previous.period)}`;
  if (diff === 0) return `Igual que ${when}`;
  const n = Math.abs(diff);
  const shown = digits > 2 && Math.abs(n * 100 - Math.round(n * 100)) > 1e-9 ? digits : rateDecimals(n);
  return `${fmtDecimal(n, shown)} ${n === 1 ? "punto" : "puntos"} ${diff > 0 ? "más" : "menos"} que ${when}`;
}

// A daily price against the newest one at least four weeks older: "0,147 € más que el 1 de septiembre de 2026".
function priceChange(points, digits, unit) {
  const last = points.at(-1);
  const before = points.filter((p) => p.period <= addDaysIso(last.period, -28)).at(-1);
  if (!before) return "";
  const diff = Math.round((last.value - before.value) * 10 ** digits) / 10 ** digits;
  if (diff === 0) return `Igual que el ${economyPeriodLabel(before.period)}`;
  return `${fmtDecimal(Math.abs(diff), digits)} ${unit} ${diff > 0 ? "más" : "menos"} que el ${economyPeriodLabel(before.period)}`;
}

// A tiny line of the series, drawn as inline SVG (Chart.js would be a lot for a dozen of these); a dashed
// line marks zero when the series crosses it. `second` (same length, nulls allowed) is drawn dashed behind it,
// on the same scale: Spain against the EU.
function sparklineSvg(values, label, second = null, { step = false } = {}) {
  if (values.length < 2) return "";
  const w = 160, h = 36;
  const all = second ? values.concat(second.filter((v) => v !== null)) : values;
  const min = Math.min(...all), max = Math.max(...all), span = max - min || 1;
  const y = (v) => (h - 3 - ((v - min) / span) * (h - 6)).toFixed(1);
  const x = (i) => ((i / (values.length - 1)) * w).toFixed(1);
  // step: the value holds until the next point (a rate that only changes on a decision day)
  const points = values.map((v, i) => (step && i > 0 ? `${x(i)},${y(values[i - 1])} ` : "") + `${x(i)},${y(v)}`).join(" ");
  const secondList = second ? second.map((v, i) => (v === null ? null : `${x(i)},${y(v)}`)).filter(Boolean) : [];
  const secondPoints = secondList.length > 1 ? secondList.join(" ") : ""; // one point is no line
  const zero = min < 0 && max > 0 ? `<line class="spark-zero" x1="0" x2="${w}" y1="${y(0)}" y2="${y(0)}" />` : "";
  return `<svg class="spark" viewBox="0 0 ${w} ${h}" preserveAspectRatio="none" role="img" aria-label="${escapeAttr(label)}">` +
    `${zero}${secondPoints ? `<polyline class="spark-second" points="${secondPoints}" />` : ""}<polyline points="${points}" /></svg>`;
}

// All text goes in escaped; `html` (a sparkline, the hour bars) is markup built here.
function economyCardHtml({ title, scope = "", value, sub, change = "", html = "" }) {
  return `<div class="economy-card"><div class="label">${escapeHtml(title)}` +
    (scope ? ` <span class="economy-scope">${escapeHtml(scope)}</span>` : "") + "</div>" +
    `<div class="economy-value">${escapeHtml(value)}</div>` +
    `<div class="economy-sub">${escapeHtml(sub)}</div>` +
    (change ? `<div class="economy-change">${escapeHtml(change)}</div>` : "") + html + "</div>";
}

// Says "España" on a figure that isn't the chosen community's (the INE doesn't publish it per community).
function economyScope(series, region) {
  return series && region !== "ES" && series.region === "ES" ? "España" : "";
}

function rateCardHtml(title, series, region, what, extra = "") {
  if (!series) return economyCardHtml({ title, value: "—", sub: "Sin datos todavía para esta comunidad" });
  const points = series.points;
  const last = points.at(-1);
  return economyCardHtml({
    title,
    scope: economyScope(series, region),
    value: fmtRate(last.value),
    sub: `${what} · ${economyPeriodLabel(last.period)}`,
    change: [points.length > 1 ? rateChange(last, points.at(-2)) : "", extra].filter(Boolean).join(" · "),
    html: sparklineSvg(points.map((p) => p.value), `${title}: evolución de los últimos dos años`),
  });
}

function fuelCardHtml(title, series) {
  if (!series) return economyCardHtml({ title, value: "—", sub: "Sin datos todavía" });
  const last = series.points.at(-1);
  return economyCardHtml({
    title,
    value: `${fmtDecimal(last.value, 3)} €/l`,
    sub: `Media de las gasolineras · ${economyPeriodLabel(last.period)}`,
    change: priceChange(series.points, 3, "€"),
    html: sparklineSvg(series.points.map((p) => p.value), `${title}: evolución del precio`),
  });
}

const perKwh = (eurPerMwh) => `${eurPerMwh < 0 ? "−" : ""}${fmtDecimal(Math.abs(eurPerMwh) / 1000, 3)} €/kWh`;

// Daily electricity prices jump a lot from one day to the next: the line shows weekly averages.
function weeklyAverages(values) {
  const weeks = [];
  for (let i = 0; i < values.length; i += 7) {
    const week = values.slice(i, i + 7);
    weeks.push(week.reduce((a, b) => a + b, 0) / week.length);
  }
  return weeks;
}

// The day's 24 prices as bars; the cheapest hours stand out. A price below zero (it happens on sunny, windy
// days) gets a thin faint bar rather than none.
function hourBarsSvg(day) {
  const prices = day.hours.map((h) => h.price);
  const max = Math.max(...prices, 1);
  const cheap = [...day.hours].sort((a, b) => a.price - b.price).slice(0, ECONOMY_CHEAP_HOURS).map((h) => h.hour);
  const w = 240, h = 48, bar = w / day.hours.length;
  const bars = day.hours.map((hour, i) => {
    const height = Math.max(2, (hour.price / max) * (h - 2));
    const kind = [cheap.includes(hour.hour) ? "cheap" : "", hour.price < 0 ? "negative" : ""].filter(Boolean).join(" ");
    return `<rect class="${kind}" x="${(i * bar + 0.5).toFixed(1)}" y="${(h - height).toFixed(1)}" ` +
      `width="${(bar - 1).toFixed(1)}" height="${height.toFixed(1)}"><title>${escapeHtml(`${hour.hour}:00 · ${perKwh(hour.price)}`)}</title></rect>`;
  }).join("");
  return `<svg class="hour-bars" viewBox="0 0 ${w} ${h}" preserveAspectRatio="none" role="img" ` +
    `aria-label="${escapeAttr(`Precio de la luz hora a hora; las más baratas: ${cheap.map((x) => `${Number(x)}:00`).join(", ")}`)}">${bars}</svg>`;
}

function cheapestAndDearest(day) {
  const sorted = [...day.hours].sort((a, b) => a.price - b.price);
  const at = (hour) => `${Number(hour.hour)}:00 (${perKwh(hour.price)})`;
  return `Más barata: ${at(sorted[0])} · Más cara: ${at(sorted.at(-1))}`;
}

function powerCardHtml(data) {
  const title = "Luz (PVPC)";
  const scope = "Península";
  const today = data.power_today;
  const history = data.power;
  if (!today && !history) return economyCardHtml({ title, scope, value: "—", sub: "Sin datos todavía" });
  const month = history ? history.points.slice(-30).map((p) => p.value) : [];
  const monthAverage = month.length ? month.reduce((a, b) => a + b, 0) / month.length : null;
  const tomorrow = data.power_tomorrow;
  const lines = [
    today ? cheapestAndDearest(today) : "",
    monthAverage !== null ? `Media de los últimos 30 días: ${perKwh(monthAverage)}` : "",
    tomorrow ? `Mañana: media de ${perKwh(tomorrow.average)}` : "",
  ].filter(Boolean);
  return economyCardHtml({
    title,
    scope,
    value: today ? perKwh(today.average) : perKwh(history.points.at(-1).value),
    sub: today ? "Media de hoy, tarifa regulada" : `Media del ${economyPeriodLabel(history.points.at(-1).period)}`,
    change: lines.join(" · "),
    html: (today ? hourBarsSvg(today) : "") +
      (history ? sparklineSvg(weeklyAverages(history.points.map((p) => p.value)), "Precio medio de la luz por semanas: evolución") : ""),
  });
}

// One row per ECOICOP group: a bar from the middle, right for a rise and left for a fall.
function economyGroupsHtml(groups) {
  const biggest = Math.max(...groups.map((g) => Math.abs(g.series.points.at(-1).value)), 1);
  return groups.map((g) => {
    const v = g.series.points.at(-1).value;
    const width = ((Math.abs(v) / biggest) * 50).toFixed(1);
    return `<li><span class="economy-group-name">${escapeHtml(g.name)}</span>` +
      `<span class="economy-group-value">${escapeHtml(fmtRate(v))}</span>` +
      `<span class="economy-bar" aria-hidden="true"><span class="${v < 0 ? "down" : "up"}" style="width:${width}%"></span></span></li>`;
  }).join("");
}

function economyItemsHtml(items) {
  return items.map((i) => `<li><span>${escapeHtml(i.name)}</span><span class="economy-item-value">${escapeHtml(fmtRate(i.value))}</span></li>`).join("");
}

function economySourcesHtml(sources) {
  return sources.map((s) => {
    const when = s.last_success_at ? `actualizado ${timeAgo(s.last_success_at)}` : "aún sin datos";
    const error = s.last_error ? ` · <span class="economy-source-error">Último intento fallido: ${escapeHtml(s.last_error)}</span>` : "";
    return `<li><strong>${escapeHtml(s.name)}</strong> — ${escapeHtml(when)}${error}</li>`;
  }).join("");
}

function economyHasData(data) {
  return Boolean(data.inflation || data.rent || data.irav || data.buy || data.petrol || data.diesel || data.power ||
    data.euribor || data.mortgages || data.unemployment || data.salary);
}

// ---- Dinero e hipotecas, empleo, Europa ----

// A level, not a change: "2,954 %", "7,9 %".
const fmtLevel = (v, digits = rateDecimals(v)) => `${v < 0 ? "−" : ""}${fmtDecimal(Math.abs(v), digits)} %`;
// "8.045": Spanish style leaves four-digit numbers ungrouped, which reads badly next to "194.892"
const fmtWhole = (n) => Math.round(n).toLocaleString("es-ES", { useGrouping: "always" });

// The point a year before the newest one (same month or quarter), for figures with a seasonal pattern.
function yearBefore(points) {
  const last = points.at(-1);
  const period = last.period.replace(/^(\d{4})/, (y) => String(Number(y) - 1));
  return points.find((p) => p.period === period) || null;
}

// "12,3 % más que en junio de 2025"
function percentChange(last, before) {
  const pct = Math.round(((last.value - before.value) / Math.abs(before.value)) * 1000) / 10;
  const when = /-Q\d$/.test(before.period) ? `el ${economyPeriodLabel(before.period)}` : `en ${economyPeriodLabel(before.period)}`;
  if (pct === 0) return `Igual que ${when}`;
  return `${fmtDecimal(Math.abs(pct), 1)} % ${pct > 0 ? "más" : "menos"} que ${when}`;
}

function missingCard(title, scope = "") {
  return economyCardHtml({ title, scope, value: "—", sub: "Sin datos todavía para esta comunidad" });
}

function euriborCardHtml(series) {
  const title = "Euríbor a un año";
  if (!series) return missingCard(title);
  const points = series.points;
  const last = points.at(-1);
  return economyCardHtml({
    title,
    value: fmtLevel(last.value, 3),
    sub: `Media de ${economyPeriodLabel(last.period)} · la referencia de las hipotecas variables`,
    change: points.length > 1 ? rateChange(last, points.at(-2), 3) : "",
    html: sparklineSvg(points.map((p) => p.value), "Euríbor: evolución de los últimos dos años"),
  });
}

// The ECB's rates are one point per decision: the newest is the rate now.
function ecbCardHtml(deposit, main) {
  const title = "Tipos del BCE";
  if (!deposit) return missingCard(title);
  const points = deposit.points;
  const last = points.at(-1);
  const before = points.at(-2);
  const moved = before
    ? `${last.value > before.value ? "Subió" : last.value < before.value ? "Bajó" : "Sin cambios"}` +
      (last.value !== before.value ? ` ${fmtDecimal(Math.abs(last.value - before.value), 2)} puntos` : "")
    : "";
  return economyCardHtml({
    title,
    value: fmtLevel(last.value, 2),
    sub: `Tipo de depósito · desde el ${economyPeriodLabel(last.period)}`,
    change: [moved, main ? `operaciones principales ${fmtLevel(main.points.at(-1).value, 2)}` : ""].filter(Boolean).join(" · "),
    html: sparklineSvg(points.map((p) => p.value), "Tipo de depósito del BCE: sus últimos cambios", null, { step: true }),
  });
}

function mortgagesCardHtml(count, amount) {
  const title = "Hipotecas sobre viviendas";
  if (!count) return missingCard(title);
  const last = count.points.at(-1);
  const before = yearBefore(count.points);
  const average = amount && amount.points.at(-1).period === last.period ? ` · importe medio ${fmtWhole(amount.points.at(-1).value)} €` : "";
  return economyCardHtml({
    title,
    value: fmtWhole(last.value),
    sub: `Firmadas en ${economyPeriodLabel(last.period)}${average}`,
    change: before ? percentChange(last, before) : "",
    html: sparklineSvg(count.points.map((p) => p.value), "Hipotecas firmadas cada mes"),
  });
}

function mortgageRateCardHtml(rate, fixed, variable) {
  const title = "Interés medio de las hipotecas";
  if (!rate) return missingCard(title, "España");
  const last = rate.points.at(-1);
  const kinds = fixed && variable ? `Fijo ${fmtLevel(fixed.points.at(-1).value, 2)} · variable ${fmtLevel(variable.points.at(-1).value, 2)}` : "";
  return economyCardHtml({
    title,
    scope: "España",
    value: fmtLevel(last.value, 2),
    sub: `Al firmar, sobre viviendas · ${economyPeriodLabel(last.period)}`,
    change: [rate.points.length > 1 ? rateChange(last, rate.points.at(-2)) : "", kinds].filter(Boolean).join(" · "),
    html: sparklineSvg(rate.points.map((p) => p.value), "Interés medio de las hipotecas: evolución"),
  });
}

function currencyCardHtml(currencies) {
  const title = "Cambio del euro";
  const usd = currencies.find((c) => c.code === "USD");
  if (!usd) return missingCard(title);
  const symbols = { USD: "$", GBP: "£" };
  const last = usd.series.points.at(-1);
  const others = currencies.filter((c) => c.code !== "USD")
    .map((c) => `${fmtDecimal(c.series.points.at(-1).value, 4)} ${symbols[c.code] || c.code}`);
  return economyCardHtml({
    title,
    value: `1 € = ${fmtDecimal(last.value, 4)} $`,
    sub: [`${economyPeriodLabel(last.period)}`, ...others].join(" · "),
    change: priceChange(usd.series.points, 4, "$"),
    html: sparklineSvg(usd.series.points.map((p) => p.value), "Dólares por euro: evolución"),
  });
}

function unemploymentCardHtml(total, young) {
  const title = "Paro";
  if (!total) return missingCard(title);
  const last = total.points.at(-1);
  const before = yearBefore(total.points);
  const diff = before ? Math.round((last.value - before.value) * 100) / 100 : null;
  const change = diff === null ? "" : diff === 0 ? `Igual que el ${economyPeriodLabel(before.period)}`
    : `${fmtDecimal(Math.abs(diff), rateDecimals(diff))} puntos ${diff > 0 ? "más" : "menos"} que el ${economyPeriodLabel(before.period)}`;
  const youth = young && young.points.at(-1).period === last.period ? `menores de 25 años: ${fmtLevel(young.points.at(-1).value)}` : "";
  return economyCardHtml({
    title,
    value: fmtLevel(last.value),
    sub: `Tasa de paro · ${economyPeriodLabel(last.period)}`,
    change: [change, youth].filter(Boolean).join(" · "),
    html: sparklineSvg(total.points.map((p) => p.value), "Tasa de paro: evolución de los últimos dos años"),
  });
}

function salaryCardHtml(series) {
  const title = "Sueldo bruto medio";
  if (!series) return missingCard(title);
  const last = series.points.at(-1);
  const before = yearBefore(series.points);
  return economyCardHtml({
    title,
    value: `${fmtWhole(last.value)} €/mes`,
    sub: `Por trabajador, antes de impuestos · ${economyPeriodLabel(last.period)}`,
    change: before ? percentChange(last, before) : "",
    html: sparklineSvg(series.points.map((p) => p.value), "Sueldo medio: evolución de los últimos dos años"),
  });
}

// Spain's figure with the EU's next to it, and both lines on one chart.
function europeCardHtml(title, comparison, what) {
  if (!comparison || !comparison.spain) return missingCard(title);
  const spain = comparison.spain.points;
  const last = spain.at(-1);
  const euAt = new Map((comparison.eu ? comparison.eu.points : []).map((p) => [p.period, p.value]));
  const eu = euAt.get(last.period);
  const gap = eu === undefined ? "" : Math.round((last.value - eu) * 10) / 10;
  const versus = eu === undefined ? "" : `UE: ${fmtLevel(eu)} · España ${gap === 0 ? "igual" : `${fmtDecimal(Math.abs(gap), 1)} puntos ${gap > 0 ? "por encima" : "por debajo"}`}`;
  return economyCardHtml({
    title,
    value: fmtLevel(last.value),
    sub: `${what} · España, ${economyPeriodLabel(last.period)}`,
    change: versus,
    html: sparklineSvg(spain.map((p) => p.value), `${title}: España (línea continua) y la UE (discontinua)`,
      spain.map((p) => (euAt.has(p.period) ? euAt.get(p.period) : null))) +
      '<div class="spark-legend"><span class="first">España</span> · <span class="second">UE</span></div>',
  });
}

// How many of the monthly/quarterly figures arrived after `seen` (an ISO instant); none before a first visit.
function economyNewsCount(data, seen) {
  if (!seen) return 0;
  const since = Date.parse(seen);
  return ECONOMY_NEWS.filter((key) => data[key] && Date.parse(data[key].arrived_at) > since).length;
}

function updateEconomyBadge(count) {
  const badge = document.getElementById("economy-badge");
  badge.textContent = count;
  badge.title = count === 1 ? "1 dato nuevo" : `${count} datos nuevos`;
  badge.hidden = count === 0;
}

function economyRegion() {
  return readStorage(ECONOMY_REGION_KEY) || "ES";
}

async function loadEconomyView() {
  const run = ++economyRun;
  const region = economyRegion();
  const data = await api("/api/economy?" + new URLSearchParams({ region }));
  if (run !== economyRun) return;

  const select = document.getElementById("economy-region");
  select.innerHTML = data.regions.map((r) => optionHtml(r.code, r.name)).join("");
  select.value = data.region;

  const hasData = economyHasData(data);
  document.getElementById("economy-empty").hidden = hasData;
  document.getElementById("economy-content").hidden = !hasData;
  document.getElementById("economy-sources").innerHTML = economySourcesHtml(data.sources);
  if (!hasData) return;

  document.getElementById("economy-prices").innerHTML =
    rateCardHtml("Inflación", data.inflation, data.region, "Subida de los precios en un año");
  document.getElementById("economy-groups").innerHTML = economyGroupsHtml(data.groups);
  document.getElementById("economy-groups-when").textContent =
    data.inflation ? `· ${economyPeriodLabel(data.inflation.points.at(-1).period)}` : "";
  document.getElementById("economy-items-up").innerHTML = economyItemsHtml(data.items_up);
  document.getElementById("economy-items-down").innerHTML = economyItemsHtml(data.items_down);
  document.getElementById("economy-items-when").textContent = data.items_period ? `· ${economyPeriodLabel(data.items_period)}` : "";

  const newAndUsed = data.buy_new && data.buy_used
    ? `Nueva ${fmtRate(data.buy_new.points.at(-1).value)} · segunda mano ${fmtRate(data.buy_used.points.at(-1).value)}`
    : "";
  document.getElementById("economy-housing").innerHTML =
    rateCardHtml("Alquiler", data.rent, data.region, "Subida de los alquileres en un año") +
    rateCardHtml("Tope de subida del alquiler (IRAV)", data.irav, data.region,
      "Lo máximo que puede subir al año un contrato firmado desde el 26-05-2023") +
    rateCardHtml("Precio de compra de vivienda", data.buy, data.region, "Subida en un año", newAndUsed);

  document.getElementById("economy-daily").innerHTML =
    fuelCardHtml("Gasolina 95", data.petrol) + fuelCardHtml("Gasóleo", data.diesel) + powerCardHtml(data);
  document.getElementById("economy-money").innerHTML =
    euriborCardHtml(data.euribor) + ecbCardHtml(data.ecb_deposit, data.ecb_main) +
    mortgagesCardHtml(data.mortgages, data.mortgage_amount) +
    mortgageRateCardHtml(data.mortgage_rate, data.mortgage_fixed, data.mortgage_variable) +
    currencyCardHtml(data.currencies || []);
  document.getElementById("economy-jobs").innerHTML =
    unemploymentCardHtml(data.unemployment, data.unemployment_young) + salaryCardHtml(data.salary);
  document.getElementById("economy-europe").innerHTML =
    europeCardHtml("Inflación armonizada", data.eu_inflation, "Subida de los precios en un año") +
    europeCardHtml("Paro (mensual)", data.eu_unemployment, "Tasa de paro, corregida de estacionalidad");

  // seen: the badge only counts what comes after this visit
  writeStorage(ECONOMY_SEEN_KEY, new Date().toISOString());
  updateEconomyBadge(0);
}

// Sections the user folded away stay folded, per browser. Only the folded ones are stored, so a section added
// later shows up open.
const ECONOMY_FOLDED_KEY = "economyFolded";

function foldedEconomyBlocks() {
  try {
    const saved = JSON.parse(readStorage(ECONOMY_FOLDED_KEY) || "[]");
    return Array.isArray(saved) ? saved : [];
  } catch (e) {
    return [];
  }
}

const economyBlocks = () => [...document.querySelectorAll("#view-economy .economy-block")];

function restoreEconomyFolds() {
  const folded = foldedEconomyBlocks();
  for (const block of economyBlocks()) block.open = !folded.includes(block.dataset.block);
}

for (const block of economyBlocks()) {
  block.addEventListener("toggle", () => {
    const folded = economyBlocks().filter((b) => !b.open).map((b) => b.dataset.block);
    writeStorage(ECONOMY_FOLDED_KEY, JSON.stringify(folded));
  });
}
restoreEconomyFolds();

document.getElementById("economy-region").addEventListener("change", (e) => {
  writeStorage(ECONOMY_REGION_KEY, e.target.value);
  loadEconomyView();
});

// At start-up, for the menu badge; silent on failure like the price badge. The first time ever there is
// nothing "new" yet: it only starts counting from now.
async function refreshEconomyBadge() {
  const seen = readStorage(ECONOMY_SEEN_KEY);
  if (!seen) {
    writeStorage(ECONOMY_SEEN_KEY, new Date().toISOString());
    return;
  }
  if (viewFromHash() === "economy") return;
  try {
    updateEconomyBadge(economyNewsCount(await api("/api/economy?" + new URLSearchParams({ region: economyRegion() })), seen));
  } catch (e) { /* the Economy view says it when opened */ }
}
