// Yearly summary (#year). Reuses the dashboard's cards and period comparison (dashboard.js).
// Loaded after app.js (shared helpers and navigation); see index.html for the order.

// ---------- Yearly summary ----------
// "Tu año": totals, the months and categories that stand out, the biggest expenses and, when the year
// before has data, how it compares. Built only from the dashboard's endpoints, over the same accounting
// months (so its numbers add up with the dashboard's "Año YYYY"). A year still in progress is compared
// with the one before only up to the same day, like the dashboard (previousPeriod).
let yearRun = 0;
const YEAR_TOP_EXPENSES = 5;

// Years with data, newest first, from /months-available (newest first).
function yearsWithData(months) {
  return [...new Set(months.map((m) => m.slice(0, 4)))];
}

// The months that can stand out: those with data, but not the current one — half a month would always
// be "the cheapest" (and the payroll, which lands on the last day, would make it the worst saving).
function yearMonthRanking(monthly, monthsWithData, now = currentMonth()) {
  const finished = monthly.filter((m) => monthsWithData.includes(m.month) && m.month !== now)
    .map((m) => ({ ...m, saving: Math.round((m.income - m.expense) * 100) / 100 }));
  if (!finished.length) return null;
  const pick = (key, better) => finished.reduce((best, m) => (better(m[key], best[key]) ? m : best));
  return {
    count: finished.length,
    mostExpense: pick("expense", (a, b) => a > b),
    leastExpense: pick("expense", (a, b) => a < b),
    bestSaving: pick("saving", (a, b) => a > b),
    worstSaving: pick("saving", (a, b) => a < b),
  };
}

// Share of what came in that was kept, or null when nothing came in.
function savingRate(summary) {
  return summary.income > 0 ? summary.balance / summary.income : null;
}

// `words`: the value is a name, not an amount, so it isn't set in the figures' font
function highlightHtml(label, value, detail = "", words = false) {
  return `<li><span class="year-hl-label">${escapeHtml(label)}</span>` +
    `<span class="year-hl-value${words ? " words" : ""}">${escapeHtml(value)}</span>` +
    (detail ? `<span class="year-hl-detail">${escapeHtml(detail)}</span>` : "") + "</li>";
}

async function loadYearView() {
  const months = await api("/api/summary/months-available");
  const select = document.getElementById("year-select");
  const years = yearsWithData(months);
  const picked = select.value; // coming back to the view keeps the year that was on screen
  select.innerHTML = years.map((y) => optionHtml(y, y)).join("");
  if (years.includes(picked)) select.value = picked;
  document.getElementById("year-empty").hidden = years.length > 0;
  document.getElementById("year-content").hidden = years.length === 0;
  document.getElementById("year-coverage").textContent = "";
  if (years.length) await renderYear(months);
}

async function renderYear(months) {
  const year = document.getElementById("year-select").value;
  const now = currentMonth();
  const choice = `year-${year}`;
  const range = periodRange(choice, { months, now, custom: {} });
  const inYear = months.filter((m) => m.startsWith(year)).sort();
  const hasPreviousYear = months.some((m) => m.startsWith(String(Number(year) - 1)));
  const compared = hasPreviousYear ? previousPeriod(choice, range) : null;
  const q = periodQuery(range);

  const run = ++yearRun;
  const [summary, monthly, byCategory, top, prevSummary, prevByCategory] = await Promise.all([
    api(`/api/summary${q}`),
    api("/api/summary/monthly?" + new URLSearchParams({ from: range.from, to: range.to })),
    api(`/api/summary/by-category${q}`),
    api("/api/transactions?" + new URLSearchParams({
      kind: "expense", month_from: range.from, month_to: range.to, sort: "amount", dir: "asc", size: YEAR_TOP_EXPENSES,
    })),
    compared ? api(`/api/summary${periodQuery(compared.range, compared.until)}`) : null,
    compared ? api("/api/summary/by-category?" + new URLSearchParams({
      from: compared.range.from, to: compared.range.to, ...(compared.until ? { until: compared.until } : {}),
    })) : null,
  ]);
  if (run !== yearRun) return;

  const inProgress = range.to === now;
  const first = inYear[0];
  const last = inYear[inYear.length - 1];
  document.getElementById("year-coverage").textContent =
    `Datos de ${fmtMonthLabel(first)} a ${fmtMonthLabel(last)} (${inYear.length} ${inYear.length === 1 ? "mes" : "meses"})` +
    (inProgress ? ". El año aún no ha terminado." : ".");

  document.getElementById("year-income").textContent = fmtMoney(summary.income);
  document.getElementById("year-expense").textContent = fmtMoney(summary.expense);
  document.getElementById("year-balance").textContent = fmtMoney(summary.balance);
  // per month with data, not per calendar month: a history that starts in March isn't spread over twelve
  renderAverages(summary, inYear.length, "year-avg");
  renderDeltas(summary, prevSummary, compared, "year-delta");
  renderSavingRate(summary, prevSummary, compared);
  renderYearHighlights(monthly, inYear, byCategory, summary, now);
  renderYearCategories(byCategory, summary, prevByCategory, compared, year);
  renderYearTopExpenses(top.items);
}

function renderSavingRate(summary, previous, compared) {
  const rate = savingRate(summary);
  document.getElementById("year-rate").textContent = rate === null ? "—" : fmtPercent(rate);
  document.getElementById("year-rate-note").textContent =
    rate === null ? "No hubo ingresos" : rate < 0 ? "Gastaste más de lo que ingresaste" : "de lo que ingresaste";
  const delta = document.getElementById("year-delta-rate");
  delta.replaceChildren();
  const before = previous && previous.count ? savingRate(previous) : null;
  if (rate === null || before === null) return;
  const points = Math.round((rate - before) * 1000) / 10;
  if (points === 0) {
    delta.textContent = `Igual que ${compared.label}`;
    return;
  }
  const arrow = document.createElement("span");
  arrow.className = `arrow ${points > 0 ? "good" : "bad"}`;
  arrow.setAttribute("aria-hidden", "true");
  arrow.textContent = points > 0 ? "▲" : "▼";
  const n = Math.abs(points).toLocaleString("es-ES");
  delta.append(arrow, `${n} ${Math.abs(points) === 1 ? "punto" : "puntos"} ${points > 0 ? "más" : "menos"} que ${compared.label}`);
}

function renderYearHighlights(monthly, inYear, byCategory, summary, now) {
  const items = [];
  const ranking = yearMonthRanking(monthly, inYear, now);
  if (ranking) {
    const month = (m) => fmtMonthLabel(m.month);
    items.push(highlightHtml("Mes con más gasto", fmtMoney(ranking.mostExpense.expense), month(ranking.mostExpense)));
    if (ranking.count > 1) {
      items.push(highlightHtml("Mes con menos gasto", fmtMoney(ranking.leastExpense.expense), month(ranking.leastExpense)));
      items.push(highlightHtml("Mes en que más ahorraste", fmtMoney(ranking.bestSaving.saving), month(ranking.bestSaving)));
      items.push(highlightHtml("Mes en que menos ahorraste", fmtMoney(ranking.worstSaving.saving), month(ranking.worstSaving)));
    }
  }
  if (byCategory.length && summary.expense > 0) {
    const top = byCategory[0];
    items.push(highlightHtml("Donde más gastaste", top.category,
      `${fmtMoney(top.amount)} · ${fmtPercent(top.amount / summary.expense)} del gasto`, true));
  }
  document.getElementById("year-highlights").innerHTML = items.join("");
  document.getElementById("year-highlights-note").textContent = inYear.includes(now)
    ? `${fmtMonthLabel(now)} no cuenta para los meses destacados: aún no ha terminado.`
    : "";
}

function renderYearCategories(byCategory, summary, prevByCategory, compared, year) {
  const list = document.getElementById("year-categories");
  const biggest = byCategory.length ? byCategory[0].amount : 0;
  const before = new Map((prevByCategory || []).map((c) => [c.category, c.amount]));
  const comparing = compared && prevByCategory && prevByCategory.length > 0;
  list.innerHTML = byCategory.map((c) => {
    let change = ""; // HTML, escaped
    if (comparing) {
      const was = before.get(c.category) || 0;
      if (was === 0) {
        change = escapeHtml(`No hubo gastos en ${compared.label}`);
      } else {
        const d = describeChange({ current: c.amount, previous: was, monthLabel: compared.label, goodWhenUp: false, withPercent: true });
        change = (d.arrow ? `<span class="arrow ${d.tone}" aria-hidden="true">${d.arrow}</span>` : "") + escapeHtml(d.text);
      }
    }
    const share = summary.expense > 0 ? fmtPercent(c.amount / summary.expense) : "";
    return `<li><button type="button" class="year-cat" data-category="${escapeAttr(c.category)}">` +
      `<span class="year-cat-name">${escapeHtml(c.category)}</span>` +
      `<span class="year-cat-amount">${escapeHtml(fmtMoney(c.amount))} <span class="muted">${escapeHtml(share)}</span></span>` +
      `<span class="year-cat-bar" aria-hidden="true"><span style="width:${biggest > 0 ? Math.max(1, (c.amount / biggest) * 100) : 0}%"></span></span>` +
      (change ? `<span class="year-cat-change delta">${change}</span>` : "") +
      "</button></li>";
  }).join("");
  document.getElementById("year-categories-empty").hidden = byCategory.length > 0;
  list.dataset.year = year;
}

document.getElementById("year-categories").addEventListener("click", (e) => {
  const button = e.target.closest(".year-cat");
  if (!button) return;
  const params = new URLSearchParams({ category: button.dataset.category, period: `year-${e.currentTarget.dataset.year}` });
  location.hash = `transactions?${params}`;
});

function renderYearTopExpenses(items) {
  document.getElementById("year-top-expenses").innerHTML = items.map((t) =>
    `<li><span class="year-top-desc">${escapeHtml(t.description)}</span>` +
    `<span class="year-top-amount amount-neg">${escapeHtml(fmtMoney(Math.abs(t.amount)))}</span>` +
    `<span class="year-top-meta">${escapeHtml(formatIsoDay(t.date))} · ${escapeHtml(t.category)}</span></li>`,
  ).join("");
  document.getElementById("year-top-empty").hidden = items.length > 0;
}

document.getElementById("year-select").addEventListener("change", async () => {
  renderYear(await api("/api/summary/months-available"));
});
