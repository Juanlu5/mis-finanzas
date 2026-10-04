const { describe, test, afterEach } = require("node:test");
const assert = require("node:assert/strict");
const { loadApp, fakeBackend } = require("./helpers");

let app;
afterEach(() => app && app.close());

// the text of every piece, with a space between pieces in separate elements (and plain spaces for the
// non-breaking one Intl puts before "€")
function text(el) {
  const parts = [];
  const walker = el.ownerDocument.createTreeWalker(el, 4 /* NodeFilter.SHOW_TEXT */);
  while (walker.nextNode()) parts.push(walker.currentNode.textContent);
  return parts.join(" ").replace(/\s+/g, " ").replace(/ ([,)])/g, "$1").replace(/\( /g, "(").trim();
}

function item(overrides) {
  return {
    key: "comercio:netflix.com", name: "Netflix.com", category: "Suscripciones", grouped_by: "payee",
    cadence: "monthly", fixed: true, amount: 13.99, min: 12.99, max: 13.99, monthly: 13.99,
    last_date: "2026-09-12", next_date: "2026-10-12", active: true, hidden: false, price_change: null,
    charges: [{ date: "2026-09-12", amount: 13.99, description: "Netflix.com — Pago con tarjeta" },
      { date: "2026-08-12", amount: 13.99, description: "Netflix.com — Pago con tarjeta" }],
    ...overrides,
  };
}

const NETFLIX = item({ price_change: { from: 12.99, to: 13.99, date: "2026-04-12" } });
const RENT = item({
  key: "importe:Ahorro / Inversión:200", name: "Ahorro / Inversión", category: "Ahorro / Inversión", grouped_by: "amount",
  amount: 200, min: 200, max: 200, monthly: 200, last_date: "2026-09-02", next_date: "2026-10-02",
});
const POWER = item({
  key: "comercio:endesa energia sau", name: "Endesa energia sau", category: "Vivienda", fixed: false,
  amount: 57.92, min: 41.75, max: 81.4, monthly: 57.92, last_date: "2026-08-07", next_date: "2026-09-07",
});
const INSURANCE = item({
  key: "comercio:mapfre seguros hogar", name: "Mapfre seguros hogar", category: "Vivienda", cadence: "yearly",
  amount: 186.4, min: 186.4, max: 186.4, monthly: 15.53, last_date: "2026-03-05", next_date: "2027-03-05",
});
const HBO = item({
  key: "comercio:hbo max", name: "Hbo max", amount: 9.99, min: 9.99, max: 9.99, monthly: 9.99,
  last_date: "2026-01-16", next_date: "2026-02-16", active: false,
});

async function open(options = {}) {
  const backend = fakeBackend({ recurring: [RENT, POWER, INSURANCE, NETFLIX, HBO], recurringIncome: 3102.53, ...options });
  app = await loadApp({ hash: "", api: backend.handler });
  app.window.eval(`recurringTodayOverride = "2026-09-26"`);
  app.window.location.hash = "recurring";
  await app.settle();
  return backend;
}

const names = (id) => app.$$(`#${id} .recurring-name`).map((el) => el.textContent);
const row = (name) => app.$$(".recurring-item").find((li) => li.querySelector(".recurring-name").textContent === name);

describe("Gastos fijos", () => {
  test("the menu opens it and the cards add up the active ones", async () => {
    await open();

    assert.ok(app.$$(".nav-btn").some((b) => b.dataset.view === "recurring" && b.classList.contains("active")));
    assert.equal(text(app.$("recurring-monthly")), "287,44 €");
    assert.equal(text(app.$("recurring-yearly")), "3449,28 €");
    assert.equal(text(app.$("recurring-count")), "4 pagos que se repiten");
    assert.equal(text(app.$("recurring-share")), "9,3 %");
    assert.match(text(app.$("recurring-share-note")), /3102,53 € al mes/);
  });

  test("what is paid now goes first and what stopped in its own list", async () => {
    await open();

    assert.deepEqual(names("recurring-active"), ["Ahorro / Inversión", "Endesa energia sau", "Mapfre seguros hogar", "Netflix.com"]);
    assert.deepEqual(names("recurring-ended"), ["Hbo max"]);
    assert.match(text(row("Hbo max")), /Último cobro el 16 ene/);
    assert.equal(app.$("recurring-hidden-panel").hidden, true);
  });

  test("a fixed price, a bill that varies and a yearly payment each say it their way", async () => {
    await open();

    assert.match(text(row("Netflix.com").querySelector(".recurring-price")), /^13,99 € al mes$/);
    assert.match(text(row("Netflix.com")), /Próximo hacia el 12 oct/);
    assert.match(text(row("Endesa energia sau").querySelector(".recurring-price")), /^≈ 57,92 € al mes$/);
    assert.match(text(row("Endesa energia sau")), /Se esperaba hacia el 7 sept/); // late
    assert.match(text(row("Endesa energia sau")), /Entre 41,75 € y 81,40 €/);
    assert.match(text(row("Mapfre seguros hogar").querySelector(".recurring-price")), /186,40 € al año \(15,53 € al mes\)/);
    assert.match(text(row("Mapfre seguros hogar")), /Próximo hacia el 5 mar 2027/);
  });

  test("a price that went up says so, and the category shows only when grouped by payee", async () => {
    await open();

    assert.match(text(row("Netflix.com")), /Subió de 12,99 € a 13,99 € en abril/);
    assert.ok(row("Netflix.com").querySelector(".recurring-change.up"));
    assert.match(text(row("Netflix.com").querySelector(".recurring-head")), /· Suscripciones/);
    assert.doesNotMatch(text(row("Ahorro / Inversión").querySelector(".recurring-head")), /·/);
    assert.match(text(row("Ahorro / Inversión")), /aunque la descripción cambie/);
  });

  test("each one lists its charges", async () => {
    await open();

    const charges = row("Netflix.com").querySelector(".recurring-charges");
    assert.match(text(charges.querySelector("summary")), /Ver los cobros \(2\)/);
    assert.equal(charges.querySelectorAll("li").length, 2);
    assert.match(text(charges.querySelector("li")), /12 sept Netflix.com — Pago con tarjeta 13,99 €/);
  });

  test("'No es un gasto fijo' takes it out of the totals, and it can come back", async () => {
    await open();

    app.click(row("Ahorro / Inversión").querySelector("[data-hide]"));
    await app.settle();

    const post = app.callsTo("/api/recurring/hidden").find((c) => c.method === "POST");
    assert.deepEqual(post.form(), { key: "importe:Ahorro / Inversión:200" });
    assert.equal(text(app.$("recurring-monthly")), "87,44 €");
    assert.equal(app.$("recurring-hidden-panel").hidden, false);
    assert.equal(text(app.$("recurring-hidden-count")), "1");
    assert.deepEqual(names("recurring-hidden"), ["Ahorro / Inversión"]);
    assert.ok(!names("recurring-active").includes("Ahorro / Inversión"));

    app.click(app.$("recurring-hidden").querySelector("[data-unhide]"));
    await app.settle();

    const del = app.callsTo("/api/recurring/hidden").find((c) => c.method === "DELETE");
    assert.equal(del.params.get("key"), "importe:Ahorro / Inversión:200");
    assert.equal(text(app.$("recurring-monthly")), "287,44 €");
    assert.equal(app.$("recurring-hidden-panel").hidden, true);
  });

  test("without income to compare with, the share card goes away", async () => {
    await open({ recurringIncome: null });

    assert.equal(app.$("recurring-share-card").hidden, true);
  });

  test("with nothing repeating it says so", async () => {
    await open({ recurring: [] });

    assert.equal(app.$("recurring-empty").hidden, false);
    assert.equal(app.$("recurring-content").hidden, true);
  });

  test("names from the movements are escaped", async () => {
    await open({ recurring: [item({ name: '<img src=x onerror="alert(1)">', key: 'comercio:"x"' })] });

    assert.equal(app.$$("#recurring-active img").length, 0);
    assert.equal(app.$$("#recurring-active .recurring-name")[0].textContent, '<img src=x onerror="alert(1)">');
    assert.equal(app.$("recurring-active").querySelector("[data-hide]").dataset.hide, 'comercio:"x"');
  });
});
