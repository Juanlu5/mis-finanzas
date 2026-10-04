const { describe, test, afterEach } = require("node:test");
const assert = require("node:assert/strict");
const { loadApp, fakeBackend } = require("./helpers");

let app;
afterEach(() => app && app.close());

// Intl puts no-break spaces in "1200,00 €" and "40 %": compare with plain ones on both sides
const norm = (s) => s.replace(/\s+/g, " ").trim();
const text = (id) => norm(app.$(id).textContent);
const money = (n) => norm(new Intl.NumberFormat("es-ES", { style: "currency", currency: "EUR" }).format(n));

// 2025 has data from March to May; 2024 only in November.
const MONTHS = ["2025-05", "2025-04", "2025-03", "2024-11"];

const SUMMARIES = {
  "2025": { income: 3000, expense: 1800, balance: 1200, count: 30 },
  "2024": { income: 1000, expense: 800, balance: 200, count: 10 },
};

const MONTHLY = {
  "2025": [
    { month: "2025-01", income: 0, expense: 0 },
    { month: "2025-02", income: 0, expense: 0 },
    { month: "2025-03", income: 1000, expense: 400 },
    { month: "2025-04", income: 1000, expense: 900 },
    { month: "2025-05", income: 1000, expense: 500 },
    { month: "2025-06", income: 0, expense: 0 },
  ],
};

const BY_CATEGORY = {
  "2025": [{ category: "Vivienda", amount: 1200 }, { category: "Ocio", amount: 400 }, { category: "Salud", amount: 200 }],
  "2024": [{ category: "Vivienda", amount: 600 }, { category: "Salud", amount: 200 }],
};

const TRANSACTIONS = [
  { id: 1, date: "2025-03-01", description: "Alquiler marzo", amount: -600, category: "Vivienda", account: "Principal", accounting_month: "2025-03" },
  { id: 2, date: "2025-04-01", description: "Alquiler abril", amount: -650, category: "Vivienda", account: "Principal", accounting_month: "2025-04" },
  { id: 3, date: "2025-04-10", description: "Concierto <b>", amount: -120, category: "Ocio", account: "Principal", accounting_month: "2025-04" },
  { id: 4, date: "2025-05-10", description: "Cena", amount: -45, category: "Ocio", account: "Principal", accounting_month: "2025-05" },
  { id: 5, date: "2025-05-31", description: "Nómina", amount: 1000, category: "Nómina / Ingresos", account: "Principal", accounting_month: "2025-05" },
  { id: 6, date: "2024-11-05", description: "Farmacia", amount: -20, category: "Salud", account: "Principal", accounting_month: "2024-11" },
];

const yearOf = (params) => (params.get("from") || params.get("month") || "").slice(0, 4);

async function open({ hash = "year", months = MONTHS, summaries = SUMMARIES, monthly = MONTHLY, byCategory = BY_CATEGORY, overrides = {} } = {}) {
  const backend = fakeBackend({
    transactions: TRANSACTIONS,
    overrides: {
      "/api/summary/months-available": () => months,
      "/api/summary": ({ params }) => summaries[yearOf(params)] || { income: 0, expense: 0, balance: 0, count: 0 },
      "/api/summary/monthly": ({ params }) => monthly[yearOf(params)] || [],
      "/api/summary/by-category": ({ params }) => byCategory[yearOf(params)] || [],
      ...overrides,
    },
  });
  app = await loadApp({ hash, api: backend.handler });
}

describe("the yearly summary", () => {
  test("has its own menu entry and opens on the newest year with data", async () => {
    await open({ hash: "" });
    app.click(app.document.querySelector('.nav-btn[data-view="year"]'));
    await app.settle();

    assert.equal(app.window.location.hash, "#year");
    assert.equal(app.$("view-year").classList.contains("active"), true);
    assert.deepEqual(app.$$("#year-select option").map((o) => o.value), ["2025", "2024"]);
    assert.equal(app.$("year-select").value, "2025");
    assert.equal(text("year-coverage"), "Datos de mar 25 a may 25 (3 meses).");
  });

  test("shows the year's totals, the average per month with data and the saving rate", async () => {
    await open();

    assert.equal(text("year-income"), money(3000));
    assert.equal(text("year-expense"), money(1800));
    assert.equal(text("year-balance"), money(1200));
    // three months with data, not twelve
    assert.equal(text("year-avg-expense"), `${money(600)} al mes de media`);
    assert.equal(text("year-rate"), "40 %");
    assert.equal(text("year-rate-note"), "de lo que ingresaste");
  });

  test("compares with the year before when it has data", async () => {
    await open();

    assert.match(text("year-delta-expense"), new RegExp(`${money(1000)} más que 2024`));
    assert.match(text("year-delta-income"), new RegExp(`${money(2000)} más que 2024`));
    // 40 % against 20 %
    assert.equal(text("year-delta-rate"), "▲20 puntos más que 2024");
    assert.equal(app.$("year-delta-rate").querySelector(".arrow").getAttribute("aria-hidden"), "true");
  });

  test("says nothing about a year before that has no data", async () => {
    await open();
    app.change(app.$("year-select"), "2024");
    await app.settle();

    assert.equal(text("year-expense"), money(800));
    assert.equal(text("year-delta-expense"), "");
    assert.equal(text("year-delta-rate"), "");
    assert.equal(app.$$("#year-categories .year-cat-change").length, 0);
    assert.equal(text("year-coverage"), "Datos de nov 24 a nov 24 (1 mes).");
  });

  test("the months that stand out only count months with data", async () => {
    await open();

    const rows = app.$$("#year-highlights li").map((li) => norm(li.textContent));
    assert.deepEqual(rows, [
      `Mes con más gasto${money(900)}abr 25`,
      `Mes con menos gasto${money(400)}mar 25`,
      `Mes en que más ahorraste${money(600)}mar 25`,
      `Mes en que menos ahorraste${money(100)}abr 25`,
      `Donde más gastasteVivienda${money(1200)} · 66,7 % del gasto`,
    ]);
    assert.equal(text("year-highlights-note"), "");
  });

  test("lists the categories biggest first, with their share and the change against the year before", async () => {
    await open();

    const names = app.$$("#year-categories .year-cat-name").map((el) => el.textContent);
    assert.deepEqual(names, ["Vivienda", "Ocio", "Salud"]);
    const changes = app.$$("#year-categories .year-cat-change").map((el) => norm(el.textContent));
    assert.deepEqual(changes, [
      `▲${money(600)} más que 2024 (100 %)`,
      "No hubo gastos en 2024",
      "Igual que 2024",
    ]);
    // the biggest fills the bar, the others in proportion
    assert.deepEqual(app.$$("#year-categories .year-cat-bar span").map((el) => el.style.width), ["100%", "33.33333333333333%", "16.666666666666664%"]);
  });

  test("clicking a category opens its movements over that year", async () => {
    await open();

    app.click(app.$$("#year-categories .year-cat")[1]);
    await app.settle();

    assert.equal(app.$("view-transactions").classList.contains("active"), true);
    assert.equal(app.$("tx-month").value, "year-2025");
    assert.equal(app.$("tx-category").value, "Ocio");
  });

  test("lists the biggest expenses of the year, escaped", async () => {
    await open();

    const call = app.callsTo("/api/transactions").at(-1).params;
    assert.equal(call.get("kind"), "expense");
    assert.equal(call.get("sort"), "amount");
    assert.equal(call.get("dir"), "asc");
    assert.equal(call.get("month_from"), "2025-01");
    assert.equal(call.get("month_to"), "2025-12");

    const rows = app.$$("#year-top-expenses li");
    assert.deepEqual(rows.map((li) => li.querySelector(".year-top-desc").textContent),
      ["Alquiler abril", "Alquiler marzo", "Concierto <b>", "Cena"]);
    assert.equal(rows[2].querySelector("b"), null);
    assert.equal(rows[1].querySelector(".year-top-meta").textContent, "01/03/2025 · Vivienda");
  });

  test("with no movements at all it says so", async () => {
    await open({ months: [] });

    assert.equal(app.$("year-empty").hidden, false);
    assert.equal(app.$("year-content").hidden, true);
  });
});

describe("a year still in progress", () => {
  const pad = (n) => String(n).padStart(2, "0");
  const now = new Date();
  const year = String(now.getFullYear());
  const thisMonth = `${year}-${pad(now.getMonth() + 1)}`;
  const lastYear = String(now.getFullYear() - 1);

  test("leaves the current month out of the months that stand out, and compares only up to today", async () => {
    // the current month has spent almost nothing yet: it would be "the cheapest month"
    const months = [thisMonth, `${lastYear}-06`];
    await open({
      months,
      summaries: { [year]: { income: 1000, expense: 500, balance: 500, count: 5 }, [lastYear]: { income: 1000, expense: 400, balance: 600, count: 5 } },
      monthly: { [year]: [{ month: thisMonth, income: 0, expense: 5 }] },
      byCategory: { [year]: [{ category: "Ocio", amount: 500 }], [lastYear]: [{ category: "Ocio", amount: 400 }] },
    });

    assert.match(text("year-coverage"), /El año aún no ha terminado\.$/);
    assert.equal(app.$$("#year-highlights li").length, 1); // only "Donde más gastaste"
    assert.match(text("year-highlights-note"), /no cuenta para los meses destacados/);

    const previous = app.callsTo("/api/summary/by-category").map((c) => c.params).find((p) => yearOf(p) === lastYear);
    assert.equal(previous.get("until").slice(0, 4), lastYear);
    assert.match(text("year-delta-expense"), new RegExp(`más que ${lastYear} a estas alturas`));
  });
});

describe("the month ranking", () => {
  test("is empty without finished months and ignores months without data", async () => {
    await open();
    const rank = (monthly, withData, now) =>
      app.value(`yearMonthRanking(${JSON.stringify(monthly)}, ${JSON.stringify(withData)}, ${JSON.stringify(now)})`);

    assert.equal(rank([{ month: "2025-05", income: 0, expense: 3 }], ["2025-05"], "2025-05"), null);
    const r = rank([
      { month: "2025-01", income: 0, expense: 0 },
      { month: "2025-02", income: 500, expense: 700 },
    ], ["2025-02"], "2025-09");
    assert.equal(r.count, 1);
    assert.equal(r.leastExpense.month, "2025-02");
    assert.equal(r.worstSaving.saving, -200);
  });
});
