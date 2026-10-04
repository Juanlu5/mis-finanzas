const { describe, test, afterEach } = require("node:test");
const assert = require("node:assert/strict");
const { loadApp, fakeBackend } = require("./helpers");

let app;
afterEach(() => app && app.close());

// Intl puts no-break spaces in "1,921 €/l" and "+4,0 %": compare with plain ones
const norm = (s) => s.replace(/\s+/g, " ").trim();
const text = (el) => norm(el.textContent);
const card = (containerId, i) => app.$$(`#${containerId} .economy-card`)[i];

const REGIONS = [{ code: "ES", name: "España" }, { code: "CT", name: "Cataluña" }, { code: "MD", name: "Madrid" }];
const series = (region, points, arrived = "2026-09-15T09:00:00Z") => ({
  region, arrived_at: arrived, points: points.map(([period, value]) => ({ period, value })),
});
const hours = (prices) => prices.map((price, i) => ({ hour: String(i).padStart(2, "0"), price }));

function economy(region = "ES", overrides = {}) {
  return {
    region,
    regions: REGIONS,
    inflation: series(region, [["2026-06", 3.4], ["2026-07", 3.3], ["2026-08", 4.0]]),
    groups: [
      { name: "Transporte", series: series(region, [["2026-07", 6.2], ["2026-08", 7.9]]) },
      { name: "Vestido y calzado", series: series(region, [["2026-07", -2.2], ["2026-08", -2.1]]) },
    ],
    items_period: "2026-08",
    items_up: [{ name: "Gasóleo", value: 30.3 }, { name: "Huevos", value: 12.5 }],
    items_down: [{ name: "Vuelos nacionales", value: -16.4 }],
    rent: series(region, [["2026-07", 2.0], ["2026-08", 1.9]]),
    irav: series("ES", [["2026-07", 2.49], ["2026-08", 2.47]]),
    buy: series(region, [["2026-Q1", 12.9], ["2026-Q2", 10.1]]),
    buy_new: series(region, [["2026-Q2", 6.4]]),
    buy_used: series(region, [["2026-Q2", 10.6]]),
    petrol: series(region, [["2026-08-01", 1.774], ["2026-09-01", 1.790], ["2026-09-26", 1.921]], "2026-09-26T15:00:00Z"),
    diesel: series(region, [["2026-09-26", 1.919]], "2026-09-26T15:00:00Z"),
    power: series("ES", [["2026-09-24", 120], ["2026-09-25", 150], ["2026-09-26", 144.61]], "2026-09-26T15:00:00Z"),
    power_today: { date: "2026-09-26", average: 144.61, hours: hours([214.52, 198.57, 190, 180, 170, 160, 150, 140, 120, 100, 80, 60, 50, 40, 45, 55, 90, 130, 170, 200, 250, 240, 230, 220]) },
    power_tomorrow: null,
    euribor: series("ES", [["2026-07", 2.855], ["2026-08", 2.954]]),
    ecb_deposit: series("ES", [["2025-06-11", 2.0], ["2026-06-17", 2.25], ["2026-09-16", 2.5]]),
    ecb_main: series("ES", [["2026-09-16", 2.65]]),
    currencies: [
      { code: "USD", series: series("ES", [["2026-08-28", 1.1643], ["2026-09-25", 1.1403]]) },
      { code: "GBP", series: series("ES", [["2026-09-25", 0.86045]]) },
    ],
    mortgages: series(region, [["2025-06", 7057], ["2026-05", 7537], ["2026-06", 8045]]),
    mortgage_amount: series(region, [["2026-06", 194892]]),
    mortgage_rate: series("ES", [["2026-05", 2.98], ["2026-06", 2.96]]),
    mortgage_fixed: series("ES", [["2026-06", 2.89]]),
    mortgage_variable: series("ES", [["2026-06", 3.07]]),
    unemployment: series(region, [["2025-Q2", 8.11], ["2026-Q1", 10.12], ["2026-Q2", 7.9]]),
    unemployment_young: series(region, [["2026-Q2", 16.05]]),
    salary: series(region, [["2025-Q2", 2635.0], ["2026-Q2", 2770.19]]),
    eu_inflation: { spain: series("ES", [["2026-07", 3.9], ["2026-08", 4.6]]), eu: series("EU", [["2026-07", 3.0], ["2026-08", 3.2]]) },
    eu_unemployment: { spain: series("ES", [["2026-06", 10.1], ["2026-07", 10.0]]), eu: series("EU", [["2026-06", 6.1]]) },
    sources: [
      { key: "ine", name: "INE", last_success_at: "2026-09-26T15:38:47Z", last_error: null },
      { key: "fuel", name: "Ministerio", last_success_at: null, last_error: "No se ha podido conectar <b>" },
    ],
    ...overrides,
  };
}

async function open({ hash = "economy", storage, respond = (params) => economy(params.get("region") || "ES") } = {}) {
  const backend = fakeBackend({ overrides: { "/api/economy": ({ params }) => respond(params) } });
  app = await loadApp({ hash, api: backend.handler, storage });
}

describe("the economy view", () => {
  test("has its own menu entry and asks for Spain the first time", async () => {
    await open({ hash: "" });
    app.click(app.document.querySelector('.nav-btn[data-view="economy"]'));
    await app.settle();

    assert.equal(app.$("view-economy").classList.contains("active"), true);
    assert.equal(app.callsTo("/api/economy").at(-1).params.get("region"), "ES");
    assert.deepEqual(app.$$("#economy-region option").map((o) => o.value), ["ES", "CT", "MD"]);
    assert.equal(app.$("economy-region").value, "ES");
  });

  test("shows inflation with its month, the change in points and a sparkline", async () => {
    await open();
    const c = card("economy-prices", 0);

    assert.equal(text(c.querySelector(".economy-value")), "+4,0 %");
    assert.equal(text(c.querySelector(".economy-sub")), "Subida de los precios en un año · agosto de 2026");
    assert.equal(text(c.querySelector(".economy-change")), "0,7 puntos más que en julio de 2026");
    assert.equal(c.querySelectorAll(".spark polyline").length, 1);
  });

  test("lists the groups with a bar each way, and what changes most in Spain", async () => {
    await open();

    const rows = app.$$("#economy-groups li");
    assert.deepEqual(rows.map((li) => text(li.querySelector(".economy-group-name"))), ["Transporte", "Vestido y calzado"]);
    assert.deepEqual(rows.map((li) => text(li.querySelector(".economy-group-value"))), ["+7,9 %", "−2,1 %"]);
    assert.deepEqual(rows.map((li) => li.querySelector(".economy-bar span").className), ["up", "down"]);
    assert.equal(rows[0].querySelector(".economy-bar span").style.width, "50%");
    assert.equal(text(app.$("economy-groups-when")), "· agosto de 2026");
    assert.deepEqual(app.$$("#economy-items-up li").map(text), ["Gasóleo+30,3 %", "Huevos+12,5 %"]);
    assert.deepEqual(app.$$("#economy-items-down li").map(text), ["Vuelos nacionales−16,4 %"]);
  });

  test("housing: rent, the rent cap and house prices by quarter", async () => {
    await open();

    assert.equal(text(card("economy-housing", 0).querySelector(".economy-value")), "+1,9 %");
    const irav = card("economy-housing", 1);
    assert.equal(text(irav.querySelector(".economy-value")), "+2,47 %");
    assert.equal(text(irav.querySelector(".economy-change")), "0,02 puntos menos que en julio de 2026");
    const buy = card("economy-housing", 2);
    assert.match(text(buy.querySelector(".economy-sub")), /2\.º trimestre de 2026$/);
    assert.equal(text(buy.querySelector(".economy-change")),
      "2,8 puntos menos que el 1.º trimestre de 2026 · Nueva +6,4 % · segunda mano +10,6 %");
  });

  test("fuel per litre, compared with four weeks before", async () => {
    await open();

    const petrol = card("economy-daily", 0);
    assert.equal(text(petrol.querySelector(".economy-value")), "1,921 €/l");
    assert.equal(text(petrol.querySelector(".economy-change")), "0,147 € más que el 1 ago 2026");
    // a single day has nothing to compare with, nor a line to draw
    const diesel = card("economy-daily", 1);
    assert.equal(diesel.querySelector(".economy-change"), null);
    assert.equal(diesel.querySelector(".spark"), null);
  });

  test("electricity: today's average per kWh, its cheapest and dearest hours, and the three cheapest marked", async () => {
    await open();

    const power = card("economy-daily", 2);
    assert.equal(text(power.querySelector(".economy-value")), "0,145 €/kWh");
    assert.match(text(power.querySelector(".economy-change")), /^Más barata: 13:00 \(0,040 €\/kWh\) · Más cara: 20:00 \(0,250 €\/kWh\)/);
    assert.match(text(power.querySelector(".economy-change")), /Media de los últimos 30 días: 0,138 €\/kWh/);
    assert.equal(power.querySelectorAll(".hour-bars rect").length, 24);
    assert.equal(power.querySelectorAll(".hour-bars rect.cheap").length, 3);
    assert.equal(text(power.querySelector(".economy-scope")), "Península");
  });

  test("a price below zero is written with a minus and still gets a (faint) bar", async () => {
    const prices = Array(24).fill(100);
    prices[14] = -13;
    await open({ respond: () => economy("ES", { power_today: { date: "2026-09-26", average: 90, hours: hours(prices) } }) });

    const power = card("economy-daily", 2);
    assert.match(text(power.querySelector(".economy-change")), /^Más barata: 14:00 \(−0,013 €\/kWh\)/);
    const bar = power.querySelectorAll(".hour-bars rect")[14];
    assert.equal(bar.getAttribute("class"), "cheap negative");
    assert.ok(Number(bar.getAttribute("height")) > 0);
  });

  test("tomorrow's electricity price shows once it is out", async () => {
    await open({ respond: () => economy("ES", { power_tomorrow: { date: "2026-09-27", average: 99.5, hours: hours(Array(24).fill(99.5)) } }) });
    assert.match(text(card("economy-daily", 2).querySelector(".economy-change")), /Mañana: media de 0,100 €\/kWh/);
  });

  test("choosing a community asks for it, marks Spain-only figures and is remembered", async () => {
    await open();
    app.change(app.$("economy-region"), "CT");
    await app.settle();

    assert.equal(app.callsTo("/api/economy").at(-1).params.get("region"), "CT");
    assert.equal(app.$("economy-region").value, "CT");
    assert.equal(text(card("economy-housing", 1).querySelector(".economy-scope")), "España");
    assert.equal(card("economy-housing", 0).querySelector(".economy-scope"), null);
    assert.equal(app.window.localStorage.getItem("economyRegion"), "CT");
  });

  test("opens on the remembered community", async () => {
    await open({ storage: { economyRegion: "MD" } });
    assert.equal(app.callsTo("/api/economy").at(-1).params.get("region"), "MD");
    assert.equal(app.$("economy-region").value, "MD");
  });

  test("a figure a community has no data for says so", async () => {
    await open({ respond: () => economy("ES", { buy: null, buy_new: null, buy_used: null }) });
    const buy = card("economy-housing", 2);
    assert.equal(text(buy.querySelector(".economy-value")), "—");
    assert.equal(text(buy.querySelector(".economy-sub")), "Sin datos todavía para esta comunidad");
  });

  test("before the first download it says the data is on its way", async () => {
    const empty = { inflation: null, groups: [], items_period: null, items_up: [], items_down: [], rent: null, irav: null,
      buy: null, buy_new: null, buy_used: null, petrol: null, diesel: null, power: null, power_today: null,
      euribor: null, ecb_deposit: null, ecb_main: null, currencies: [], mortgages: null, mortgage_amount: null,
      mortgage_rate: null, mortgage_fixed: null, mortgage_variable: null, unemployment: null, unemployment_young: null,
      salary: null, eu_inflation: null, eu_unemployment: null };
    await open({ respond: () => economy("ES", empty) });

    assert.equal(app.$("economy-empty").hidden, false);
    assert.equal(app.$("economy-content").hidden, true);
  });

  test("lists the sources, with a failed one's reason escaped", async () => {
    await open();
    const rows = app.$$("#economy-sources li");
    assert.match(text(rows[0]), /^INE — actualizado /);
    assert.equal(text(rows[1]), "Ministerio — aún sin datos · Último intento fallido: No se ha podido conectar <b>");
    assert.equal(rows[1].querySelector("b"), null);
  });

  test("names from the INE never become markup", async () => {
    await open({ respond: () => economy("ES", { groups: [{ name: "<img src=x>", series: series("ES", [["2026-08", 1]]) }] }) });
    assert.equal(app.$$("#economy-groups img").length, 0);
    assert.equal(text(app.$("economy-groups").querySelector(".economy-group-name")), "<img src=x>");
  });
});

describe("money, jobs and Europe", () => {
  test("the Euríbor with its three decimals and the ECB's rate with the day it last changed", async () => {
    await open();

    const euribor = card("economy-money", 0);
    assert.equal(text(euribor.querySelector(".economy-value")), "2,954 %");
    assert.equal(text(euribor.querySelector(".economy-change")), "0,099 puntos más que en julio de 2026");
    const ecb = card("economy-money", 1);
    assert.equal(text(ecb.querySelector(".economy-value")), "2,50 %");
    assert.equal(text(ecb.querySelector(".economy-sub")), "Tipo de depósito · desde el 16 sept 2026");
    assert.equal(text(ecb.querySelector(".economy-change")), "Subió 0,25 puntos · operaciones principales 2,65 %");
    // a rate holds until the next decision: the line goes in steps (each change adds a corner)
    assert.equal(ecb.querySelector(".spark polyline").getAttribute("points").split(" ").length, 5);
  });

  test("home mortgages against the same month a year before, and the average interest for Spain", async () => {
    await open({ respond: () => economy("CT") });

    const mortgages = card("economy-money", 2);
    assert.equal(text(mortgages.querySelector(".economy-value")), "8.045");
    assert.equal(text(mortgages.querySelector(".economy-sub")), "Firmadas en junio de 2026 · importe medio 194.892 €");
    assert.equal(text(mortgages.querySelector(".economy-change")), "14,0 % más que en junio de 2025");
    const rate = card("economy-money", 3);
    assert.equal(text(rate.querySelector(".economy-scope")), "España");
    assert.equal(text(rate.querySelector(".economy-change")), "0,02 puntos menos que en mayo de 2026 · Fijo 2,89 % · variable 3,07 %");
  });

  test("what a euro buys, with the pound beside the dollar", async () => {
    await open();
    const fx = card("economy-money", 4);
    assert.equal(text(fx.querySelector(".economy-value")), "1 € = 1,1403 $");
    assert.equal(text(fx.querySelector(".economy-sub")), "25 sept 2026 · 0,8605 £");
    assert.equal(text(fx.querySelector(".economy-change")), "0,0240 $ menos que el 28 ago 2026");
  });

  test("unemployment and wages compared with a year before, not with the quarter before (both are seasonal)", async () => {
    await open();

    const jobs = card("economy-jobs", 0);
    assert.equal(text(jobs.querySelector(".economy-value")), "7,9 %");
    assert.equal(text(jobs.querySelector(".economy-change")), "0,21 puntos menos que el 2.º trimestre de 2025 · menores de 25 años: 16,05 %");
    const salary = card("economy-jobs", 1);
    assert.equal(text(salary.querySelector(".economy-value")), "2.770 €/mes");
    assert.equal(text(salary.querySelector(".economy-change")), "5,1 % más que el 2.º trimestre de 2025");
  });

  test("Spain against the EU: the gap in words and both lines on one chart", async () => {
    await open();

    const inflation = card("economy-europe", 0);
    assert.equal(text(inflation.querySelector(".economy-value")), "4,6 %");
    assert.equal(text(inflation.querySelector(".economy-change")), "UE: 3,2 % · España 1,4 puntos por encima");
    assert.equal(inflation.querySelectorAll(".spark polyline").length, 2);
    assert.equal(inflation.querySelector(".spark polyline.spark-second").getAttribute("points").split(" ").length, 2);
    // the EU hasn't published July yet: no comparison, and its line only where it has data
    const unemployment = card("economy-europe", 1);
    assert.equal(unemployment.querySelector(".economy-change"), null);
    assert.equal(unemployment.querySelector(".spark polyline.spark-second"), null);
  });

  test("a figure not yet downloaded shows a dash, not an error", async () => {
    await open({ respond: () => economy("ES", { euribor: null, currencies: [], salary: null, eu_inflation: null }) });

    assert.equal(text(card("economy-money", 0).querySelector(".economy-value")), "—");
    assert.equal(text(card("economy-money", 4).querySelector(".economy-value")), "—");
    assert.equal(text(card("economy-jobs", 1).querySelector(".economy-value")), "—");
    assert.equal(text(card("economy-europe", 0).querySelector(".economy-value")), "—");
  });
});

describe("folding sections", () => {
  const block = (name) => app.document.querySelector(`#view-economy .economy-block[data-block="${name}"]`);

  test("every section starts open", async () => {
    await open();
    assert.deepEqual(app.$$("#view-economy .economy-block").map((b) => b.open),
      [true, true, true, true, true, true, true]);
  });

  test("a folded section is remembered, and comes back folded next time", async () => {
    await open();
    block("money").open = false;
    block("europe").open = false;
    await app.settle();

    const saved = app.window.localStorage.getItem("economyFolded");
    assert.deepEqual(JSON.parse(saved), ["money", "europe"]);
    app.close();

    await open({ storage: { economyFolded: saved } });
    assert.equal(block("money").open, false);
    assert.equal(block("europe").open, false);
    assert.equal(block("housing").open, true);
    // folded, not gone: its cards are still filled in for when it's opened
    assert.equal(app.$$("#economy-money .economy-card").length, 5);
  });

  test("opening it again forgets it", async () => {
    await open({ storage: { economyFolded: '["money"]' } });
    block("money").open = true;
    await app.settle();
    assert.equal(app.window.localStorage.getItem("economyFolded"), "[]");
  });

  test("a damaged saved value just leaves everything open", async () => {
    await open({ storage: { economyFolded: "{no es json" } });
    assert.ok(app.$$("#view-economy .economy-block").every((b) => b.open));
  });
});

describe("the economy badge", () => {
  test("counts the monthly figures that arrived since the last visit, not the daily prices", async () => {
    // inflation, rent, IRAV and house prices arrived on the 15th; fuel and electricity today
    await open({ hash: "dashboard", storage: { economySeen: "2026-09-20T00:00:00Z" },
      respond: () => economy("ES", { rent: series("ES", [["2026-08", 1.9]], "2026-09-25T00:00:00Z"), irav: series("ES", [["2026-08", 2.47]], "2026-09-25T00:00:00Z") }) });

    assert.equal(app.$("economy-badge").hidden, false);
    assert.equal(app.$("economy-badge").textContent, "2");
  });

  test("the first time ever it starts counting from now", async () => {
    await open({ hash: "dashboard" });
    assert.equal(app.$("economy-badge").hidden, true);
    assert.equal(app.callsTo("/api/economy").length, 0);
    assert.ok(app.window.localStorage.getItem("economySeen"));
  });

  test("opening the view clears it", async () => {
    await open({ hash: "dashboard", storage: { economySeen: "2026-01-01T00:00:00Z" } });
    assert.equal(app.$("economy-badge").textContent, "8");

    app.click(app.document.querySelector('.nav-btn[data-view="economy"]'));
    await app.settle();

    assert.equal(app.$("economy-badge").hidden, true);
    assert.ok(Date.parse(app.window.localStorage.getItem("economySeen")) > Date.parse("2026-09-01"));
  });
});
