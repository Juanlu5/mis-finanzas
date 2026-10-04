// Dashboard (#dashboard). Its period helpers (periodRange, periodOptionsHtml, previousPeriod, renderDeltas...)
// are reused by year.js and transactions.js.
// Loaded after app.js (shared helpers and navigation); see index.html for the order.

// ---------- Dashboard ----------
let monthlyChart, categoryChart, balanceChart;
let balanceAccounts = [];

// ---------- Dashboard period ----------
// One selector drives the whole dashboard: a single month (the default, the newest with data) or a range
// of whole months, {from, to} as "YYYY-MM". Values: "YYYY-MM", "last-N", "year-YYYY", "custom", "" (all).
let availableMonths = []; // newest first, as /months-available returns them

function currentMonth() {
  return localIsoDate(new Date()).slice(0, 7);
}

// "2026-03" plus n months (n may be negative)
function addMonths(ym, n) {
  const [y, m] = ym.split("-").map(Number);
  const d = new Date(y, m - 1 + n, 1);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}`;
}

function monthsBetween(from, to) {
  const [fy, fm] = from.split("-").map(Number);
  const [ty, tm] = to.split("-").map(Number);
  return (ty - fy) * 12 + (tm - fm) + 1;
}

const isSingleMonth = (choice) => /^\d{4}-\d{2}$/.test(choice);

// `choice` is the selector's value; `custom` the two custom selects; `months` newest first; `now` "YYYY-MM".
function periodRange(choice, { months, now, custom }) {
  if (isSingleMonth(choice)) return { from: choice, to: choice };
  const last = choice.match(/^last-(\d+)$/);
  if (last) return { from: addMonths(now, 1 - Number(last[1])), to: now };
  const year = choice.match(/^year-(\d{4})$/);
  if (year) {
    const to = `${year[1]}-12`;
    // the current year stops at this month: the rest would only be empty bars
    return { from: `${year[1]}-01`, to: to > now ? now : to };
  }
  if (choice === "custom" && custom.from && custom.to) {
    return custom.from <= custom.to ? { from: custom.from, to: custom.to } : { from: custom.to, to: custom.from };
  }
  // all history (and anything unexpected): from the oldest month with data up to now
  const oldest = months.length ? months[months.length - 1] : now;
  const newest = months.length ? months[0] : now;
  return { from: oldest < now ? oldest : now, to: newest > now ? newest : now };
}

// What the cards compare with: the month before a month, the same months a year earlier for a year, the
// same length just before for any other range, and nothing for all history. `label` reads after "que",
// `noData` is the whole "no data" sentence. A period still in progress (it ends in the current month) is
// compared with the previous one only up to the same day (`until`): half a month against a whole one
// would always look like a drop — and a payroll that arrives on the last day would make it look huge.
function previousPeriod(choice, range, today = localIsoDate(new Date())) {
  if (choice === "") return null;
  let prev;
  let label;
  if (isSingleMonth(choice)) {
    const month = previousMonth(choice);
    prev = { from: month, to: month };
    label = fmtMonthLabel(month);
  } else if (/^year-/.test(choice)) {
    prev = { from: addMonths(range.from, -12), to: addMonths(range.to, -12) };
    const whole = prev.from.endsWith("-01") && prev.to.endsWith("-12");
    label = whole || range.to === today.slice(0, 7) ? prev.from.slice(0, 4) : `${fmtMonthLabel(prev.from)} – ${fmtMonthLabel(prev.to)}`;
  } else {
    const length = monthsBetween(range.from, range.to);
    prev = { from: addMonths(range.from, -length), to: addMonths(range.from, -1) };
    const last = choice.match(/^last-(\d+)$/);
    label = last ? `los ${last[1]} meses anteriores` : "el periodo anterior";
  }
  if (range.to !== today.slice(0, 7)) {
    return { range: prev, until: null, label, noData: `Sin datos en ${label}` };
  }
  const day = Number(today.slice(8, 10));
  const until = sameDayIn(prev.to, day);
  return {
    range: prev,
    until,
    label: `${label} a estas alturas`,
    noData: `Sin datos en ${label} hasta el día ${Number(until.slice(8, 10))}`,
    note: `Este periodo aún no ha terminado: se compara con ${label} solo hasta el día ${Number(until.slice(8, 10))} de ${fmtMonthLabel(prev.to)}.`,
  };
}

// Day `day` of month "YYYY-MM" as "YYYY-MM-DD", or its last day if it's shorter (31 March -> 28/29 February).
function sameDayIn(ym, day) {
  const [y, m] = ym.split("-").map(Number);
  const last = new Date(y, m, 0).getDate();
  return `${ym}-${String(Math.min(day, last)).padStart(2, "0")}`;
}

// The period choices, shared by the dashboard and Transactions: the newest months with data, then the ranges.
// Only the newest MONTH_OPTIONS months are listed (years of history would make a list of dozens); an older
// one is picked as a custom range from that month to itself, which selectedChoice reads as that month.
// `allLabel` names the "" (no limit) option; `allFirst` puts it on top instead of among the ranges.
const MONTH_OPTIONS = 12;

function periodOptionsHtml(months, { allLabel, allFirst = false }) {
  const years = [...new Set(months.map((m) => m.slice(0, 4)))];
  const all = optionHtml("", allLabel);
  return (allFirst ? all : "") +
    '<optgroup label="Mes">' + months.slice(0, MONTH_OPTIONS).map((m) => optionHtml(m, fmtMonthLabel(m))).join("") + "</optgroup>" +
    '<optgroup label="Periodo">' +
    optionHtml("last-3", "Últimos 3 meses") +
    optionHtml("last-6", "Últimos 6 meses") +
    optionHtml("last-12", "Últimos 12 meses") +
    years.map((y) => optionHtml(`year-${y}`, `Año ${y}`)).join("") +
    (allFirst ? "" : all) +
    optionHtml("custom", "Otro mes o periodo…") +
    "</optgroup>";
}

// The two month pickers of a custom range, oldest to newest, preset to all the months with data.
function fillCustomRange(fromId, toId, months) {
  const ascending = [...months].reverse();
  for (const id of [fromId, toId]) {
    document.getElementById(id).innerHTML = ascending.map((m) => optionHtml(m, fmtMonthLabel(m))).join("");
  }
  document.getElementById(fromId).value = ascending[0] || "";
  document.getElementById(toId).value = months[0] || "";
}

async function loadPeriodSelector() {
  const months = await api("/api/summary/months-available");
  availableMonths = months;
  const sel = document.getElementById("dashboard-period");
  sel.innerHTML = periodOptionsHtml(months, { allLabel: "Todo el histórico" });
  sel.value = months.length ? months[0] : "";
  fillCustomRange("dashboard-period-from", "dashboard-period-to", months);
  restorePeriod(months);
  document.getElementById("dashboard-period-custom").hidden = periodSelectValue() !== "custom";
  return months;
}

// The last period picked is remembered in this browser (localStorage: per device, just a convenience).
// The newest month is saved as "latest", not as that month, so the default keeps moving on to each new
// month as it arrives; any other month stays as picked.
const PERIOD_KEY = "dashboardPeriod";

function savePeriod() {
  const choice = periodSelectValue();
  const saved = { choice: choice !== "" && choice === availableMonths[0] ? "latest" : choice };
  if (choice === "custom") {
    saved.from = document.getElementById("dashboard-period-from").value;
    saved.to = document.getElementById("dashboard-period-to").value;
  }
  try { localStorage.setItem(PERIOD_KEY, JSON.stringify(saved)); } catch (e) {}
}

// Puts back the saved period if it still exists (a month or a year can disappear if its movements are
// deleted); otherwise the default set by loadPeriodSelector stays.
function restorePeriod(months) {
  let saved;
  try { saved = JSON.parse(localStorage.getItem(PERIOD_KEY)); } catch (e) { return; }
  if (!saved || typeof saved.choice !== "string" || saved.choice === "latest") return;
  const sel = document.getElementById("dashboard-period");
  if (![...sel.options].some((o) => o.value === saved.choice)) return;
  if (saved.choice === "custom") {
    if (!months.includes(saved.from) || !months.includes(saved.to)) return;
    document.getElementById("dashboard-period-from").value = saved.from;
    document.getElementById("dashboard-period-to").value = saved.to;
  }
  sel.value = saved.choice;
}

// What the selector shows, "custom" included (for the pickers, saving and links)...
function periodSelectValue() {
  return document.getElementById("dashboard-period").value;
}

// ...and what it means: a custom range of one month is that month, so an old month not in the list
// behaves exactly like a listed one (compared with the month before, 12-month charts).
function selectedChoice() {
  const value = periodSelectValue();
  if (value !== "custom") return value;
  const from = document.getElementById("dashboard-period-from").value;
  return from && from === document.getElementById("dashboard-period-to").value ? from : value;
}

function selectedPeriod() {
  return periodRange(selectedChoice(), {
    months: availableMonths,
    now: currentMonth(),
    custom: {
      from: document.getElementById("dashboard-period-from").value,
      to: document.getElementById("dashboard-period-to").value,
    },
  });
}

// The trend charts show the period itself, except for a single month, where one pair of bars or thirty
// days of balance would say little: then they show the 12 months up to it, with that month highlighted.
function chartPeriod() {
  const choice = selectedChoice();
  if (!isSingleMonth(choice)) return { ...selectedPeriod(), highlight: null };
  return { from: addMonths(choice, -11), to: choice, highlight: choice };
}

function onPeriodChange() {
  document.getElementById("dashboard-period-custom").hidden = periodSelectValue() !== "custom";
  savePeriod();
  renderDashboardData();
}
["dashboard-period", "dashboard-period-from", "dashboard-period-to"].forEach((id) =>
  document.getElementById(id).addEventListener("change", onPeriodChange));

// More than two years of monthly bars would be too thin to read: beyond that, one pair per quarter.
const MAX_MONTHLY_BARS = 24;

function groupMonthly(monthly) {
  if (monthly.length <= MAX_MONTHLY_BARS) {
    return { grouping: "", rows: monthly.map((m) => ({ key: m.month, label: fmtMonthLabel(m.month), income: m.income, expense: m.expense })) };
  }
  const quarters = new Map();
  for (const m of monthly) {
    const [y, mo] = m.month.split("-").map(Number);
    const label = `T${Math.ceil(mo / 3)} ${y}`;
    const q = quarters.get(label) || { key: label, label, income: 0, expense: 0 };
    q.income += m.income;
    q.expense += m.expense;
    quarters.set(label, q);
  }
  const round = (n) => Math.round(n * 100) / 100;
  return {
    grouping: "por trimestres",
    rows: [...quarters.values()].map((q) => ({ key: q.key, label: q.label, income: round(q.income), expense: round(q.expense) })),
  };
}

async function loadBalanceAccountSelector() {
  balanceAccounts = await api("/api/accounts");
  const sel = document.getElementById("balance-account");
  if (balanceAccounts.length > 1) {
    sel.style.display = "";
    sel.innerHTML = balanceAccounts.map((a) => optionHtml(a)).join("");
  } else {
    sel.style.display = "none";
  }
}
document.getElementById("balance-account").addEventListener("change", renderBalanceChart);

// The money there is now, whatever period is chosen: the newest balance the movements carry.
async function renderCurrentBalance() {
  const now = await api("/api/summary/current-balance");
  const value = document.getElementById("card-now");
  const date = document.getElementById("now-date");
  if (now.total === null || now.total === undefined) {
    value.textContent = "—";
    value.classList.remove("negative");
    date.textContent = "Sin datos de saldo todavía";
    return;
  }
  value.textContent = fmtMoney(now.total);
  value.classList.toggle("negative", now.total < 0);
  const [y, m, d] = now.date.split("-").map(Number);
  date.textContent = `A ${new Date(y, m - 1, d).toLocaleDateString("es-ES", { day: "numeric", month: "long" })}` +
    (now.accounts.length > 1 ? ` · ${now.accounts.length} cuentas` : "");
}

async function loadDashboard() {
  renderCurrentBalance();
  await loadPeriodSelector();
  await loadBalanceAccountSelector();
  await renderDashboardData();
}

// Each render bumps its counter; a response that arrives after a newer render started
// (period switched quickly, theme toggled mid-load) is dropped instead of overwriting it.
let balanceRun = 0;
let monthlyRun = 0;
let dashboardRun = 0;

async function renderBalanceChart() {
  const run = ++balanceRun;
  const sel = document.getElementById("balance-account");
  const account = sel.value || balanceAccounts[0] || "";
  const canvas = document.getElementById("chart-balance");
  const emptyMsg = document.getElementById("balance-empty");
  const period = chartPeriod();
  document.getElementById("balance-context").textContent = contextNote(period);
  emptyMsg.textContent = selectedChoice() === "" || !availableMonths.length
    ? "Todavía no hay datos de saldo. Descarga los movimientos del banco o reimporta tu extracto (si incluye una " +
      "columna de \"disponible\" o \"saldo\") para rellenarlo: no duplicará las transacciones que ya tienes."
    : "No hay movimientos con saldo en este periodo.";
  if (!account) {
    canvas.style.display = "none";
    emptyMsg.style.display = "block";
    return;
  }

  const [y, m] = period.to.split("-").map(Number);
  const lastDay = `${period.to}-${String(new Date(y, m, 0).getDate()).padStart(2, "0")}`;
  const data = await api("/api/summary/balance-history?" +
    new URLSearchParams({ account, from: `${period.from}-01`, to: lastDay }));
  if (run !== balanceRun) return;

  if (!data.length) {
    canvas.style.display = "none";
    emptyMsg.style.display = "block";
    if (balanceChart) { balanceChart.destroy(); balanceChart = null; }
    return;
  }
  canvas.style.display = "";
  emptyMsg.style.display = "none";

  const colors = themeColors();

  if (balanceChart) balanceChart.destroy();
  balanceChart = new Chart(canvas, {
    type: "line",
    data: {
      datasets: [{
        label: "Saldo",
        data: data.map((d) => ({ x: d.date, y: d.balance })),
        borderColor: colors.primary,
        backgroundColor: withAlpha(colors.primary, 0.14),
        fill: true,
        tension: 0.15,
        pointRadius: 0,
        pointHoverRadius: 4,
        borderWidth: 2,
      }],
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      interaction: { mode: "index", intersect: false },
      plugins: {
        legend: { display: false },
        tooltip: { callbacks: { label: (ctx) => ` ${fmtMoney(ctx.parsed.y)}` } },
      },
      scales: {
        x: {
          type: "time",
          // a few months read better by week; years of them, by quarter
          time: { unit: balanceAxisUnit(monthsBetween(period.from, period.to)), tooltipFormat: "dd/MM/yyyy" },
          // the date-fns bundle ships its locales; without this the axis reads "Aug 2025"
          adapters: { date: { locale: window.dateFns && window.dateFns.locale && window.dateFns.locale.es } },
          ticks: { color: colors.muted, maxTicksLimit: 10, font: monoFont },
          grid: { display: false },
        },
        y: { ticks: { color: colors.muted, font: monoFont, callback: (v) => fmtMoneyCompact(v) }, grid: { color: colors.border } },
      },
    },
  });
}

function balanceAxisUnit(months) {
  return months <= 3 ? "week" : months <= 36 ? "month" : "quarter";
}

// Said next to a chart's title when it isn't showing exactly the chosen period.
function contextNote(period) {
  return period.highlight ? `· 12 meses hasta ${fmtMonthLabel(period.highlight)}` : "";
}

// "2026-01" -> "2025-12"
function previousMonth(ym) {
  const [y, m] = ym.split("-").map(Number);
  return m === 1 ? `${y - 1}-12` : `${y}-${String(m - 1).padStart(2, "0")}`;
}

const fmtPercent = (n) => new Intl.NumberFormat("es-ES", { style: "percent", maximumFractionDigits: 1 }).format(n);

// How `current` compares with `previous`, in words (colour alone would not say it). `goodWhenUp`
// says whether a rise is good news: income yes, expenses no. `withPercent` is off for the balance,
// where a percentage over a near-zero or negative base means nothing.
function describeChange({ current, previous, monthLabel, goodWhenUp, withPercent }) {
  const diff = Math.round((current - previous) * 100) / 100;
  if (diff === 0) return { arrow: "", tone: "", text: `Igual que ${monthLabel}` };
  const up = diff > 0;
  const percent = withPercent && previous !== 0 ? ` (${fmtPercent(Math.abs(diff) / Math.abs(previous))})` : "";
  return {
    arrow: up ? "▲" : "▼",
    tone: up === goodWhenUp ? "good" : "bad",
    text: `${fmtMoney(Math.abs(diff))} ${up ? "más" : "menos"} que ${monthLabel}${percent}`,
  };
}

// `previous` is the summary of the period compared with (see previousPeriod), or null when there is none.
// `prefix` picks the cards: the dashboard's (`delta-income`...) or the yearly summary's.
function renderDeltas(summary, previous, compared, prefix = "delta") {
  const kinds = [
    ["income", true, true],
    ["expense", false, true],
    ["balance", true, false],
  ];
  for (const [kind, goodWhenUp, withPercent] of kinds) {
    const el = document.getElementById(`${prefix}-${kind}`);
    el.replaceChildren();
    el.title = compared && compared.note ? compared.note : "";
    if (!previous) continue;
    if (previous.count === 0) {
      el.textContent = compared.noData;
      continue;
    }
    const change = describeChange({ current: summary[kind], previous: previous[kind], monthLabel: compared.label, goodWhenUp, withPercent });
    if (change.arrow) {
      const arrow = document.createElement("span");
      arrow.className = `arrow ${change.tone}`;
      arrow.setAttribute("aria-hidden", "true");
      arrow.textContent = change.arrow;
      el.append(arrow);
    }
    el.append(change.text);
  }
}

// How many months of `range` have movements: the average is over those, so a range reaching back before the
// history starts (the year the data begins in) isn't spread over months the app knows nothing about.
function monthsWithDataIn(range, months = availableMonths) {
  return months.filter((m) => m >= range.from && m <= range.to).length;
}

// Over several months, each card also says what that is per month.
function renderAverages(summary, months, prefix = "avg") {
  for (const kind of ["income", "expense", "balance"]) {
    document.getElementById(`${prefix}-${kind}`).textContent =
      months > 1 ? `${fmtMoney(Math.round((summary[kind] / months) * 100) / 100)} al mes de media` : "";
  }
}

async function renderMonthlyChart() {
  const run = ++monthlyRun;
  const period = chartPeriod();
  const monthly = await api("/api/summary/monthly?" + new URLSearchParams({ from: period.from, to: period.to }));
  if (run !== monthlyRun) return;

  const { grouping, rows } = groupMonthly(monthly);
  document.getElementById("monthly-grouping").textContent = grouping ? `· ${grouping}` : contextNote(period);
  const colors = themeColors();
  // with a single month chosen, the other months are dimmed so it stands out
  const shade = (color) => (period.highlight
    ? rows.map((r) => (r.key === period.highlight ? color : withAlpha(color, 0.35)))
    : color);
  const ctx1 = document.getElementById("chart-monthly");
  if (monthlyChart) monthlyChart.destroy();
  monthlyChart = new Chart(ctx1, {
    type: "bar",
    data: {
      labels: rows.map((r) => r.label),
      datasets: [
        { label: "Ingresos", data: rows.map((r) => r.income), backgroundColor: shade(colors.income), borderRadius: 4 },
        { label: "Gastos", data: rows.map((r) => r.expense), backgroundColor: shade(colors.expense), borderRadius: 4 },
      ],
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      interaction: { mode: "index", intersect: false },
      plugins: {
        legend: { position: "bottom", labels: { color: colors.text, boxWidth: 12 } },
        tooltip: { callbacks: { label: (ctx) => ` ${ctx.dataset.label}: ${fmtMoney(ctx.parsed.y)}` } },
      },
      scales: {
        x: { ticks: { color: colors.muted, font: { ...monoFont, size: 11 }, maxRotation: 0 }, grid: { display: false } },
        y: { ticks: { color: colors.muted, font: monoFont, callback: (v) => fmtMoneyCompact(v) }, grid: { color: colors.border } },
      },
    },
  });
}

// The query for a range of months: one month as ?month= (as before ranges existed), null (all) as no filter;
// `until` (a date) leaves out movements after it.
function periodQuery(range, until = null) {
  if (!range) return "";
  const params = new URLSearchParams(range.from === range.to ? { month: range.from } : { from: range.from, to: range.to });
  if (until) params.set("until", until);
  return `?${params}`;
}

async function renderDashboardData() {
  const choice = selectedChoice();
  const period = selectedPeriod();
  const q = periodQuery(choice === "" ? null : period);
  const compared = previousPeriod(choice, period);

  const run = ++dashboardRun;
  const balanceDone = renderBalanceChart();
  const monthlyDone = renderMonthlyChart();
  const [summary, byCatAll, previous] = await Promise.all([
    api(`/api/summary${q}`),
    api(`/api/summary/by-category${q}`),
    // no comparison for "Todo el histórico": there is no previous period
    compared ? api(`/api/summary${periodQuery(compared.range, compared.until)}`) : null,
  ]);
  await Promise.all([balanceDone, monthlyDone]);
  if (run !== dashboardRun) return;

  document.getElementById("card-income").textContent = fmtMoney(summary.income);
  document.getElementById("card-expense").textContent = fmtMoney(summary.expense);
  document.getElementById("card-balance").textContent = fmtMoney(summary.balance);
  renderAverages(summary, isSingleMonth(choice) ? 1 : monthsWithDataIn(period));
  renderDeltas(summary, previous, compared);

  const colors = themeColors();
  const theme = currentTheme();

  const byCat = groupTopCategories(byCatAll);
  const ctx2 = document.getElementById("chart-category");
  if (categoryChart) categoryChart.destroy();
  const total = byCat.reduce((sum, c) => sum + c.amount, 0);
  categoryChart = new Chart(ctx2, {
    type: "doughnut",
    data: {
      labels: byCat.map((c) => c.category),
      datasets: [{
        data: byCat.map((c) => c.amount),
        backgroundColor: byCat.map((c, i) => (c.isOthers ? OTHERS_COLOR[theme] : CATEGORY_PALETTE[theme][i])),
        borderColor: colors.surface,
        borderWidth: 2,
      }],
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      cutout: "62%",
      // a slice opens its movements (not "Otras", which is several categories)
      onClick: (event, elements) => {
        const slice = elements.length ? byCat[elements[0].index] : null;
        if (slice && !slice.isOthers) openCategoryTransactions(slice.category);
      },
      onHover: (event, elements) => {
        const clickable = elements.length && !byCat[elements[0].index].isOthers;
        if (event.native && event.native.target) event.native.target.style.cursor = clickable ? "pointer" : "default";
      },
      plugins: {
        legend: { position: "bottom", labels: { boxWidth: 12, color: colors.text } },
        tooltip: {
          callbacks: {
            label: (ctx) => ` ${ctx.label}: ${fmtMoney(ctx.parsed)} (${Math.round((ctx.parsed / total) * 100)}%)`,
          },
        },
      },
    },
  });
}

// The movements behind a slice: Transactions filtered by that category over the dashboard's period, passed
// in the hash so it's a real navigation (Back returns to the dashboard).
function openCategoryTransactions(category) {
  const params = new URLSearchParams({ category });
  const choice = periodSelectValue();
  if (choice !== "") {
    // Transactions offers the same choices, so it can show the very same one; a custom range also needs its months
    params.set("period", choice);
    if (choice === "custom") {
      const period = selectedPeriod();
      params.set("from", period.from);
      params.set("to", period.to);
    }
  }
  location.hash = `transactions?${params}`;
}
