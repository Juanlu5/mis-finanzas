const { describe, test, afterEach } = require("node:test");
const assert = require("node:assert/strict");
const { loadApp, fakeBackend, tick } = require("./helpers");

let app;
afterEach(() => app && app.close());

const chartOn = (canvasId) => app.charts.filter((c) => c.canvas.id === canvasId && !c.destroyed).at(-1);
// values built inside jsdom have another realm's Array.prototype, which deepStrictEqual rejects
const plain = (value) => JSON.parse(JSON.stringify(value));
const money = (id) => app.$(id).textContent.replace(/\s/g, " "); // es-ES puts a non-breaking space before €

async function open(options) {
  const backend = fakeBackend(options);
  app = await loadApp({
    hash: "dashboard",
    api: options && options.api ? options.api(backend) : backend.handler,
    storage: options && options.storage,
  });
}

describe("cards", () => {
  test("show the summary of the newest month, formatted in euros", async () => {
    await open({ summary: { income: 1850, expense: 631.33, balance: 1218.67, count: 7 } });

    assert.equal(app.$("dashboard-period").value, "2026-03");
    assert.equal(app.callsTo("/api/summary")[0].params.get("month"), "2026-03");
    assert.equal(money("card-income"), "1850,00 €");
    assert.equal(money("card-expense"), "631,33 €");
    assert.equal(money("card-balance"), "1218,67 €");
  });
});

describe("comparison with the previous month", () => {
  const MARCH = { income: 1200, expense: 500, balance: 700, count: 9 };
  const FEBRUARY = { income: 1000, expense: 400, balance: 600, count: 5 };
  const byMonth = (months) => (params) => months[params.get("month")];
  const text = (id) => app.$(id).textContent.replace(/\s/g, " ");
  const arrow = (id) => app.$(id).querySelector(".arrow");

  test("asks for the previous calendar month next to the selected one", async () => {
    await open({ summary: byMonth({ "2026-03": MARCH, "2026-02": FEBRUARY }) });

    assert.deepEqual(app.callsTo("/api/summary").map((c) => c.params.get("month")).sort(), ["2026-02", "2026-03"]);
  });

  test("says in words how much more or less than last month, with a percentage for income and expenses", async () => {
    await open({ summary: byMonth({ "2026-03": MARCH, "2026-02": FEBRUARY }) });

    assert.equal(text("delta-income"), "▲200,00 € más que feb 26 (20 %)");
    assert.equal(text("delta-expense"), "▲100,00 € más que feb 26 (25 %)");
    assert.equal(text("delta-balance"), "▲100,00 € más que feb 26", "no percentage on the balance");
  });

  test("a rise is good for income and balance but bad for expenses", async () => {
    await open({ summary: byMonth({ "2026-03": MARCH, "2026-02": FEBRUARY }) });

    assert.equal(arrow("delta-income").className, "arrow good");
    assert.equal(arrow("delta-expense").className, "arrow bad");
    assert.equal(arrow("delta-balance").className, "arrow good");
  });

  test("a fall is the other way round", async () => {
    await open({ summary: byMonth({ "2026-03": FEBRUARY, "2026-02": MARCH }) });

    assert.equal(text("delta-income"), "▼200,00 € menos que feb 26 (16,7 %)");
    assert.equal(arrow("delta-income").className, "arrow bad");
    assert.equal(arrow("delta-expense").className, "arrow good");
    assert.equal(arrow("delta-balance").className, "arrow bad");
  });

  test("the arrow is decoration: screen readers get the words", async () => {
    await open({ summary: byMonth({ "2026-03": MARCH, "2026-02": FEBRUARY }) });

    assert.equal(arrow("delta-income").getAttribute("aria-hidden"), "true");
  });

  test("identical figures say so, without an arrow", async () => {
    await open({ summary: byMonth({ "2026-03": MARCH, "2026-02": MARCH }) });

    assert.equal(text("delta-income"), "Igual que feb 26");
    assert.equal(arrow("delta-income"), null);
  });

  test("differences below one cent count as no difference", async () => {
    await open({ summary: byMonth({ "2026-03": { ...MARCH, income: 1000.004 }, "2026-02": FEBRUARY }) });

    assert.equal(text("delta-income"), "Igual que feb 26");
  });

  test("a previous month with no transactions says so instead of comparing with zero", async () => {
    await open({ summary: byMonth({ "2026-03": MARCH, "2026-02": { income: 0, expense: 0, balance: 0, count: 0 } }) });

    for (const kind of ["income", "expense", "balance"]) assert.equal(text(`delta-${kind}`), "Sin datos en feb 26");
  });

  test("no percentage when the previous figure was zero (it would be infinite)", async () => {
    await open({ summary: byMonth({ "2026-03": MARCH, "2026-02": { income: 0, expense: 400, balance: -400, count: 3 } }) });

    assert.equal(text("delta-income"), "▲1200,00 € más que feb 26");
  });

  test("'Todo el histórico' has nothing to compare with: cleared, and no extra request", async () => {
    await open({ summary: byMonth({ "2026-03": MARCH, "2026-02": FEBRUARY }) });
    const before = app.callsTo("/api/summary").length;

    app.change(app.$("dashboard-period"), "");
    await app.settle();

    const requests = app.callsTo("/api/summary").slice(before);
    assert.equal(requests.length, 1);
    assert.equal(requests[0].params.has("month"), false);
    for (const kind of ["income", "expense", "balance"]) assert.equal(app.$(`delta-${kind}`).textContent, "");
  });

  test("switching month compares with the month before that one", async () => {
    await open({ summary: byMonth({ "2026-03": MARCH, "2026-02": FEBRUARY, "2026-01": { ...FEBRUARY, income: 500 } }) });

    app.change(app.$("dashboard-period"), "2026-02");
    await app.settle();

    assert.equal(text("delta-income"), "▲500,00 € más que ene 26 (100 %)");
  });

  test("January compares with December of the year before", async () => {
    await open({
      summary: byMonth({ "2026-01": MARCH, "2025-12": FEBRUARY }),
      overrides: { "/api/summary/months-available": () => ["2026-01"] },
    });

    assert.deepEqual(app.callsTo("/api/summary").map((c) => c.params.get("month")).sort(), ["2025-12", "2026-01"]);
    assert.match(text("delta-income"), /más que dic 25/);
  });
});

describe("category doughnut", () => {
  const rows = (n) => Array.from({ length: n }, (_, i) => ({ category: `Cat ${i + 1}`, amount: (n - i) * 10 }));

  test("up to 9 categories are shown one by one", async () => {
    await open({ byCategory: rows(9) });

    const { data } = chartOn("chart-category").config;
    assert.equal(data.labels.length, 9);
    assert.equal(data.labels.some((l) => l.startsWith("Otras")), false);
  });

  test("beyond that, the smallest fold into one grey 'Otras (n)' slice with their sum", async () => {
    await open({ byCategory: rows(12) }); // 120, 110 ... 10

    const { data } = chartOn("chart-category").config;
    assert.equal(data.labels.length, 9);
    assert.deepEqual(plain(data.labels.slice(0, 8)), rows(8).map((r) => r.category));
    assert.equal(data.labels[8], "Otras (4)");
    assert.equal(data.datasets[0].data[8], 40 + 30 + 20 + 10);
    assert.equal(data.datasets[0].data.length, 9);
    assert.equal(new Set(data.datasets[0].backgroundColor).size, 9, "no colour is repeated");
  });

  test("the tooltip shows euros and the share of the total", async () => {
    await open({ byCategory: [{ category: "A", amount: 75 }, { category: "B", amount: 25 }] });

    const { callbacks } = chartOn("chart-category").config.options.plugins.tooltip;
    const text = callbacks.label({ label: "A", parsed: 75 }).replace(/\s/g, " ");
    assert.equal(text, " A: 75,00 € (75%)");
  });
});

describe("monthly bars", () => {
  test("label months in Spanish", async () => {
    await open({ overrides: { "/api/summary/monthly": () => [{ month: "2026-01", income: 1, expense: 2 }] } });

    assert.deepEqual(plain(chartOn("chart-monthly").config.data.labels), ["ene 26"]);
  });
});

describe("re-rendering", () => {
  test("an old, slow response for another month does not overwrite the current one", async () => {
    const held = [];
    await open({
      api: (backend) => (call) => {
        // income tells which month a reply belongs to: 300 = March, 200 = February, 999 = all history
        const income = { "2026-03": 300, "2026-02": 200 }[call.params.get("month")] ?? 999;
        if (call.path === "/api/summary" && held.armed) {
          return new Promise((resolve) => held.push(() => resolve({ income, expense: 0, balance: income, count: 1 })));
        }
        return backend.handler(call);
      },
    });
    held.armed = true;

    app.change(app.$("dashboard-period"), "2026-02"); // render A: February plus its comparison month (2 requests)
    app.change(app.$("dashboard-period"), "");        // render B: all history, nothing to compare (1 request)
    await tick();
    assert.equal(held.length, 3);

    held[2](); // B answers first
    await app.settle();
    assert.equal(money("card-income"), "999,00 €");

    held[0](); held[1](); // February arrives late
    await app.settle();
    assert.equal(money("card-income"), "999,00 €", "stale February was dropped");
    assert.equal(app.$("delta-income").textContent, "", "and so was its comparison");
  });

  test("toggling the theme rebuilds the charts", async () => {
    await open({});
    const before = app.charts.length;

    app.click(app.$("theme-toggle"));
    await app.settle();

    assert.ok(app.charts.length > before);
    assert.equal(app.charts.slice(0, before).every((c) => c.destroyed), true, "old charts were destroyed");
  });

  test("toggling the theme on another view does not refetch the dashboard", async () => {
    app = await loadApp({ hash: "transactions", api: fakeBackend().handler });
    const before = app.callsTo("/api/summary").length;

    app.click(app.$("theme-toggle"));
    await app.settle();

    assert.equal(app.callsTo("/api/summary").length, before);
  });
});

describe("dashboard period", () => {
  const lastCall = (path) => app.callsTo(path).at(-1).params;
  const plusMonths = (ym, n) => app.value(`addMonths(${JSON.stringify(ym)}, ${n})`);
  const now = () => app.value("currentMonth()");
  const lastDayOf = (ym) => {
    const [y, m] = ym.split("-").map(Number);
    return `${ym}-${String(new Date(y, m, 0).getDate()).padStart(2, "0")}`;
  };
  const choose = async (value) => { app.change(app.$("dashboard-period"), value); await app.settle(); };
  const optionValues = (id) => [...app.$(id).options].map((o) => o.value);
  const withAccount = { overrides: { "/api/accounts": () => ["Principal"] } };

  test("offers every month with data, then quick ranges, each year, all history and a custom range", async () => {
    await open({ overrides: { "/api/summary/months-available": () => ["2027-01", "2026-12"] } });

    assert.deepEqual(optionValues("dashboard-period"),
      ["2027-01", "2026-12", "last-3", "last-6", "last-12", "year-2027", "year-2026", "", "custom"]);
    assert.equal(app.$("dashboard-period").value, "2027-01", "the newest month is the default");
    assert.equal(app.$("dashboard-period-custom").hidden, true);
  });

  test("lists only the 12 newest months; an older one is a custom range of that month alone", async () => {
    const months = Array.from({ length: 40 }, (_, i) => plusMonths("2026-03", -i));
    await open({ ...withAccount, overrides: { ...withAccount.overrides, "/api/summary/months-available": () => months } });

    const listed = optionValues("dashboard-period").filter((v) => /^\d{4}-\d{2}$/.test(v));
    assert.deepEqual(listed, months.slice(0, 12));
    assert.ok(optionValues("dashboard-period-from").includes("2023-03"), "the custom pickers keep every month");
    assert.equal([...app.$("dashboard-period").options].at(-1).textContent, "Otro mes o periodo…");

    await choose("custom");
    app.change(app.$("dashboard-period-from"), "2023-03");
    app.change(app.$("dashboard-period-to"), "2023-03");
    await app.settle();

    assert.equal(app.$("dashboard-period-custom").hidden, false, "the pickers stay open");
    assert.deepEqual(app.callsTo("/api/summary").slice(-2).map((c) => c.params.get("month")), ["2023-03", "2023-02"],
      "that month, compared with the one before");
    assert.match(app.$("delta-income").textContent, /que feb 23/);
    assert.equal(lastCall("/api/summary/monthly").get("from"), "2022-04", "12 months up to it, as a listed month");
    assert.equal(app.$("monthly-grouping").textContent, "· 12 meses hasta mar 23");
  });

  test("with a single month, the charts show the 12 months up to it and say so", async () => {
    await open(withAccount);

    assert.equal(lastCall("/api/summary/monthly").get("from"), "2025-04");
    assert.equal(lastCall("/api/summary/monthly").get("to"), "2026-03");
    assert.equal(lastCall("/api/summary/balance-history").get("from"), "2025-04-01");
    assert.equal(lastCall("/api/summary/balance-history").get("to"), "2026-03-31");
    assert.equal(app.$("monthly-grouping").textContent, "· 12 meses hasta mar 26");
    assert.equal(app.$("balance-context").textContent, "· 12 meses hasta mar 26");
  });

  test("the chosen month's bars stand out and the rest are dimmed", async () => {
    await open({ overrides: { "/api/summary/monthly": () => [
      { month: "2026-02", income: 1, expense: 1 }, { month: "2026-03", income: 2, expense: 2 }] } });

    const colors = plain(chartOn("chart-monthly").config.data.datasets[0].backgroundColor);
    assert.equal(colors.length, 2);
    assert.notEqual(colors[0], colors[1]);
    assert.equal(colors[1], app.value("themeColors().income"));
  });

  test("a range drives the cards, the categories and the charts alike", async () => {
    await open(withAccount);

    await choose("last-3");

    for (const path of ["/api/summary/by-category", "/api/summary/monthly"]) {
      assert.equal(lastCall(path).get("from"), plusMonths(now(), -2), path);
      assert.equal(lastCall(path).get("to"), now(), path);
    }
    const cards = app.callsTo("/api/summary").slice(-2).map((c) => c.params);
    assert.equal(cards[0].get("from"), plusMonths(now(), -2));
    assert.equal(lastCall("/api/summary/balance-history").get("to"), lastDayOf(now()));
    assert.equal(app.$("monthly-grouping").textContent, "", "no note: the charts show the period itself");
  });

  test("a range is compared with the same length just before it", async () => {
    await open({});

    await choose("last-3");

    const compared = app.callsTo("/api/summary").at(-1).params;
    assert.equal(compared.get("from"), plusMonths(now(), -5));
    assert.equal(compared.get("to"), plusMonths(now(), -3));
    assert.match(app.$("delta-income").textContent, /que los 3 meses anteriores/);
  });

  test("a range shows the average per month on each card; a single month does not", async () => {
    // the last 3 calendar months, all with data
    const months = [0, 1, 2].map((back) => {
      const d = new Date();
      d.setDate(1);
      d.setMonth(d.getMonth() - back);
      return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}`;
    });
    await open({ summary: { income: 3000, expense: 900, balance: 2100, count: 30 }, overrides: { "/api/summary/months-available": () => months } });
    assert.equal(app.$("avg-income").textContent, "");

    await choose("last-3");

    assert.equal(money("avg-income"), "1000,00 € al mes de media");
    assert.equal(money("avg-expense"), "300,00 € al mes de media");
    assert.equal(money("avg-balance"), "700,00 € al mes de media");
  });

  test("the average only counts the months of the range that have data", async () => {
    // the history starts in March: January and February are unknown, not zero
    await open({
      summary: { income: 3000, expense: 900, balance: 2100, count: 30 },
      overrides: { "/api/summary/months-available": () => ["2025-05", "2025-04", "2025-03"] },
    });

    await choose("year-2025");

    assert.equal(money("avg-income"), "1000,00 € al mes de media");
    assert.equal(money("avg-expense"), "300,00 € al mes de media");
  });

  test("a year is compared with the same months of the year before", async () => {
    await open({ overrides: { "/api/summary/months-available": () => ["2025-11", "2025-03"] } });

    await choose("year-2025");

    assert.equal(lastCall("/api/summary/monthly").get("from"), "2025-01");
    assert.equal(lastCall("/api/summary/monthly").get("to"), "2025-12");
    const compared = app.callsTo("/api/summary").at(-1).params;
    assert.equal(compared.get("from"), "2024-01");
    assert.equal(compared.get("to"), "2024-12");
    assert.match(app.$("delta-income").textContent, /que 2024/);
  });

  test("a custom range shows two month pickers and uses them", async () => {
    await open({ overrides: { "/api/summary/months-available": () => ["2026-03", "2026-02", "2025-12"] } });

    await choose("custom");
    assert.equal(app.$("dashboard-period-custom").hidden, false);
    app.change(app.$("dashboard-period-from"), "2025-12");
    app.change(app.$("dashboard-period-to"), "2026-02");
    await app.settle();

    assert.equal(lastCall("/api/summary/by-category").get("from"), "2025-12");
    assert.equal(lastCall("/api/summary/by-category").get("to"), "2026-02");
    assert.match(app.$("delta-income").textContent, /que el periodo anterior/);
  });

  test("more than 24 months are grouped by quarter, and the title says so", async () => {
    const months = Array.from({ length: 30 }, (_, i) => {
      const d = new Date(2024, i, 1);
      return { month: `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}`, income: 100, expense: 10 };
    });
    await open({ overrides: { "/api/summary/monthly": () => months } });
    await choose("");

    const chart = chartOn("chart-monthly");
    assert.equal(chart.config.data.labels.length, 10);
    assert.equal(chart.config.data.labels[0], "T1 2024");
    assert.equal(chart.config.data.datasets[0].data[0], 300);
    assert.equal(app.$("monthly-grouping").textContent, "· por trimestres");
  });

  test("an empty period says so, without suggesting a re-import", async () => {
    await open(withAccount);
    await choose("last-3");

    assert.equal(app.$("balance-empty").textContent, "No hay movimientos con saldo en este periodo.");
  });
});

describe("the last period picked is remembered", () => {
  const MONTHS = ["2026-03", "2026-02", "2025-12"];
  const withMonths = (months, storage) => ({ overrides: { "/api/summary/months-available": () => months }, storage });
  const choose = async (value) => { app.change(app.$("dashboard-period"), value); await app.settle(); };
  const saved = () => JSON.parse(app.window.localStorage.getItem("dashboardPeriod"));
  const remembered = (value) => ({ dashboardPeriod: JSON.stringify(value) });

  test("a range is saved when picked and comes back on the next visit, cards and all", async () => {
    await open(withMonths(MONTHS));
    await choose("last-6");
    assert.deepEqual(plain(saved()), { choice: "last-6" });
    app.close();

    await open(withMonths(MONTHS, remembered({ choice: "last-6" })));
    assert.equal(app.$("dashboard-period").value, "last-6");
    const summary = app.callsTo("/api/summary")[0].params;
    assert.equal(summary.get("month"), null, "the first request already uses the remembered period");
    assert.notEqual(summary.get("from"), null);
  });

  test("the newest month is saved as 'the newest', so a new month takes over when it arrives", async () => {
    await open(withMonths(MONTHS));
    await choose("last-3");
    await choose("2026-03");
    assert.deepEqual(plain(saved()), { choice: "latest" });
    app.close();

    await open(withMonths(["2026-04", ...MONTHS], remembered({ choice: "latest" })));
    assert.equal(app.$("dashboard-period").value, "2026-04");
  });

  test("an older month stays as picked", async () => {
    await open(withMonths(MONTHS));
    await choose("2026-02");
    assert.deepEqual(plain(saved()), { choice: "2026-02" });
    app.close();

    await open(withMonths(["2026-04", ...MONTHS], remembered({ choice: "2026-02" })));
    assert.equal(app.$("dashboard-period").value, "2026-02");
    assert.equal(app.callsTo("/api/summary")[0].params.get("month"), "2026-02");
  });

  test("all history is remembered too", async () => {
    await open(withMonths(MONTHS, remembered({ choice: "" })));
    assert.equal(app.$("dashboard-period").value, "");
  });

  test("a custom range comes back with its two months and the pickers shown", async () => {
    await open(withMonths(MONTHS));
    await choose("custom");
    app.change(app.$("dashboard-period-from"), "2025-12");
    app.change(app.$("dashboard-period-to"), "2026-02");
    await app.settle();
    assert.deepEqual(plain(saved()), { choice: "custom", from: "2025-12", to: "2026-02" });
    app.close();

    await open(withMonths(MONTHS, remembered({ choice: "custom", from: "2025-12", to: "2026-02" })));
    assert.equal(app.$("dashboard-period").value, "custom");
    assert.equal(app.$("dashboard-period-custom").hidden, false);
    assert.equal(app.$("dashboard-period-from").value, "2025-12");
    assert.equal(app.$("dashboard-period-to").value, "2026-02");
  });

  test("a saved period that no longer exists, or garbage, falls back to the newest month", async () => {
    for (const storage of [
      remembered({ choice: "2024-01" }),
      remembered({ choice: "year-2024" }),
      remembered({ choice: "custom", from: "2024-01", to: "2026-02" }),
      remembered({ nothing: true }),
      { dashboardPeriod: "{not json" },
    ]) {
      await open(withMonths(MONTHS, storage));
      assert.equal(app.$("dashboard-period").value, "2026-03", storage.dashboardPeriod);
      assert.equal(app.$("dashboard-period-custom").hidden, true);
      app.close();
    }
    app = null;
  });
});

describe("from a category slice to its movements", () => {
  const BY_CATEGORY = [{ category: "Ocio", amount: 120 }, { category: "Salud", amount: 40 }];
  const clickSlice = async (index) => {
    chartOn("chart-category").config.options.onClick(null, [{ index }]);
    await app.settle();
  };
  const lastList = () => app.callsTo("/api/transactions").at(-1).params;
  const lastCall = (path) => app.callsTo(path).at(-1).params;
  const choose = async (value) => { app.change(app.$("dashboard-period"), value); await app.settle(); };

  test("a slice on a month opens Transactions filtered by that category and month", async () => {
    await open({ byCategory: BY_CATEGORY });

    await clickSlice(0);

    assert.equal(app.window.location.hash, "#transactions", "the link is consumed, a reload won't repeat it");
    assert.equal(app.$("view-transactions").classList.contains("active"), true);
    assert.equal(lastList().get("category"), "Ocio");
    assert.equal(lastList().get("month"), "2026-03");
    assert.equal(lastList().get("month_from"), null);
    assert.equal(app.$("tx-month").value, "2026-03");
    assert.equal(app.$("tx-category").value, "Ocio");
    assert.equal(app.$("tx-month-custom").hidden, true);
  });

  test("a slice on a quick range opens Transactions with that same range chosen", async () => {
    await open({ byCategory: BY_CATEGORY });
    await choose("last-3");
    const dashboardRange = { from: lastCall("/api/summary/by-category").get("from"), to: lastCall("/api/summary/by-category").get("to") };

    await clickSlice(1);

    assert.equal(app.$("tx-month").value, "last-3");
    assert.equal(lastList().get("category"), "Salud");
    assert.equal(lastList().get("month_from"), dashboardRange.from);
    assert.equal(lastList().get("month_to"), dashboardRange.to);
    assert.equal(lastList().get("month"), null);
  });

  test("a slice on a custom range brings its months, pickers shown", async () => {
    await open({ byCategory: BY_CATEGORY });
    await choose("custom");
    app.change(app.$("dashboard-period-from"), "2026-02");
    app.change(app.$("dashboard-period-to"), "2026-02");
    await app.settle();

    await clickSlice(1);

    assert.equal(app.$("tx-month").value, "custom");
    assert.equal(app.$("tx-month-custom").hidden, false);
    assert.equal(app.$("tx-month-from").value, "2026-02");
    assert.equal(app.$("tx-month-to").value, "2026-02");
    assert.equal(lastList().get("month_from"), "2026-02");
    assert.equal(lastList().get("month_to"), "2026-02");

    app.change(app.$("tx-month"), "2026-03");
    await app.settle();

    assert.equal(app.$("tx-month-custom").hidden, true);
    assert.equal(lastList().get("month"), "2026-03");
    assert.equal(lastList().get("month_from"), null);
    assert.equal(lastList().get("category"), "Salud", "the other filters stay");
  });

  test("with all history the category is the only filter", async () => {
    await open({ byCategory: BY_CATEGORY });
    await choose("");

    await clickSlice(0);

    assert.equal(lastList().get("category"), "Ocio");
    assert.equal(lastList().get("month"), null);
    assert.equal(lastList().get("month_from"), null);
  });

  test("the grey 'Otras' slice groups several categories, so it opens nothing", async () => {
    const many = Array.from({ length: 11 }, (_, i) => ({ category: `Cat ${i}`, amount: 100 - i }));
    await open({ byCategory: many });
    const before = app.callsTo("/api/transactions").length;

    await clickSlice(8); // the 9th slice: "Otras (3)"

    assert.equal(app.window.location.hash, "#dashboard");
    assert.equal(app.callsTo("/api/transactions").length, before);
  });

  test("a plain visit to Transactions doesn't keep a range from an earlier link", async () => {
    await open({ byCategory: BY_CATEGORY });
    await choose("last-3");
    await clickSlice(0);
    assert.equal(app.$("tx-month").value, "last-3");

    app.window.location.hash = "dashboard";
    await app.settle();
    app.window.location.hash = "transactions";
    await app.settle();

    assert.equal(app.$("tx-month").value, "");
    assert.equal(lastList().get("month_from"), null);
  });
});

describe("current balance card", () => {
  test("shows the newest known balance and how recent it is, whatever the period", async () => {
    await open({ currentBalance: { total: 2714.16, date: "2026-09-23", accounts: [{ account: "Principal", balance: 2714.16, date: "2026-09-23" }] } });

    assert.equal(money("card-now"), "2714,16 €");
    assert.equal(app.$("now-date").textContent, "A 23 de septiembre");
    const calls = app.callsTo("/api/summary/current-balance").length;

    app.change(app.$("dashboard-period"), "last-6");
    await app.settle();

    assert.equal(app.callsTo("/api/summary/current-balance").length, calls, "not refetched: it doesn't depend on the period");
  });

  test("a negative balance is shown in the expense colour; several accounts are counted", async () => {
    await open({ currentBalance: { total: -35.5, date: "2026-09-20", accounts: [
      { account: "Principal", balance: -85.5, date: "2026-09-20" }, { account: "Ahorro", balance: 50, date: "2026-09-22" }] } });

    assert.equal(app.$("card-now").classList.contains("negative"), true);
    assert.equal(app.$("now-date").textContent, "A 20 de septiembre · 2 cuentas");
  });

  test("without any balance yet it says so", async () => {
    await open({ currentBalance: { total: null, date: null, accounts: [] } });

    assert.equal(app.$("card-now").textContent, "—");
    assert.equal(app.$("now-date").textContent, "Sin datos de saldo todavía");
  });
});

describe("comparing a month still in progress", () => {
  test("asks for the previous month only up to the same day, and says so", async () => {
    // the current calendar month, worked out the way the page does (same process, same time zone)
    const now = new Date();
    const thisMonth = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, "0")}`;
    await open({ overrides: { "/api/summary/months-available": () => [thisMonth] } });

    const compared = app.callsTo("/api/summary").at(-1).params;
    assert.equal(compared.get("month"), app.value(`previousMonth("${thisMonth}")`));
    assert.equal(compared.get("until"), app.value(`sameDayIn(previousMonth("${thisMonth}"), ${now.getDate()})`));
    assert.match(app.$("delta-expense").title, /aún no ha terminado/);
    assert.match(app.$("delta-expense").textContent, /a estas alturas/);
  });
});
