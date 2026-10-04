const { describe, test, afterEach } = require("node:test");
const assert = require("node:assert/strict");
const { loadApp, fakeBackend } = require("./helpers");

let app;
afterEach(() => app && app.close());

const LINK = "https://chollostyle.es/product/57335";
const DAY = 86400000;
const ago = (ms) => new Date(Date.now() - ms).toISOString();

// Intl writes "70,19 €" with a no-break space: compare the words, not the spacing
const text = (el) => el.textContent.replace(/\s+/g, " ").trim();
const cards = () => app.$$(".price-card");
const posts = () => app.callsTo("/api/prices").filter((c) => c.method === "POST");

function product(overrides = {}) {
  const now = new Date().toISOString();
  return {
    id: 1, url: LINK, name: "Club C 85", store: "chollostyle.es", currency: "EUR", price: 70.19, available: true,
    target_price: null, previous_price: null, changed_at: null, restocked_at: null, lowest_price: 70.19, highest_price: 70.19,
    created_at: ago(3 * DAY), checked_at: now, last_error: null, last_error_at: null, ...overrides,
  };
}

async function open(options = {}, hash = "prices") {
  const backend = fakeBackend(options);
  app = await loadApp({ hash, api: backend.handler });
  return backend;
}

async function add(url, target = "") {
  app.$("price-url").value = url;
  app.$("price-target").value = target;
  app.$("form-add-price").dispatchEvent(new app.window.Event("submit", { cancelable: true }));
  await app.settle();
}

function cardButton(card, action) {
  return card.querySelector(`button[data-action="${action}"]`);
}

describe("following a product's price", () => {
  test("with nothing followed yet it says how to start", async () => {
    await open();

    assert.equal(app.$("price-empty").hidden, false);
    assert.equal(cards().length, 0);
    assert.equal(app.$("prices-badge").hidden, true);
  });

  test("pasting a link reads the product and shows its card", async () => {
    await open();

    await add(`  ${LINK} `, "59,90");

    assert.deepEqual(posts()[0].form(), { url: LINK, target_price: "59.9" });
    assert.equal(cards().length, 1);
    const card = cards()[0];
    assert.equal(text(card.querySelector(".price-name")), "Club C 85");
    assert.equal(card.querySelector(".price-name").getAttribute("href"), LINK);
    assert.equal(card.querySelector(".price-name").getAttribute("rel"), "noopener noreferrer");
    assert.equal(text(card.querySelector(".price-store")), "chollostyle.es");
    assert.equal(text(card.querySelector(".price-now .value")), "70,19 €");
    assert.match(text(card.querySelector(".price-badges")), /Objetivo: 59,90 €/);
    assert.equal(app.$("price-empty").hidden, true);
    assert.match(text(app.$("price-feedback")), /Siguiendo «Club C 85»: ahora cuesta 70,19 €/);
    assert.equal(app.$("price-url").value, "");
    assert.equal(app.$("price-target").value, "");
  });

  test("a shop that can't be read says why and adds nothing", async () => {
    await open({ shop: () => ({ error: "Esta tienda no deja que un programa lea sus páginas" }) });

    await add("https://www.zara.com/es/es/sobrecamisa-p07484895.html");

    assert.equal(text(app.$("price-feedback")), "Esta tienda no deja que un programa lea sus páginas");
    assert.equal(app.$("price-feedback").className, "feedback error");
    assert.equal(cards().length, 0);
    assert.equal(app.$("price-add-btn").disabled, false);
    assert.equal(app.$("price-add-btn").textContent, "Seguir precio");
  });

  test("an empty link or a target that isn't a price is caught before asking the server", async () => {
    await open();

    await add("   ");
    assert.equal(text(app.$("price-feedback")), "Pega el enlace del producto.");

    await add(LINK, "barato");
    assert.equal(text(app.$("price-feedback")), "«barato» no es un precio válido (ej.: 45 o 45,30).");
    assert.equal(app.$("price-target").getAttribute("aria-invalid"), "true");

    assert.equal(posts().length, 0);
  });
});

describe("what the cards say", () => {
  test("a recent drop is highlighted, counted above the list and in the menu", async () => {
    await open({ products: [product({ price: 55, previous_price: 70.19, changed_at: ago(2 * DAY), lowest_price: 55 })] });

    const card = cards()[0];
    assert.ok(card.classList.contains("news"));
    assert.match(text(card.querySelector(".price-change")), /Ha bajado 15,19 € \(antes 70,19 €\) · hace 2 días/);
    assert.equal(text(card.querySelector(".price-meta")).startsWith("Mínimo 55,00 € · Máximo 70,19 €"), true);
    assert.equal(text(app.$("price-summary")), "1 producto con novedades: ha bajado de precio, está por debajo de tu objetivo o ha vuelto a haber stock.");
    assert.equal(app.$("prices-badge").hidden, false);
    assert.equal(app.$("prices-badge").textContent, "1");
  });

  test("an old drop is still shown but is no longer news", async () => {
    await open({ products: [product({ price: 55, previous_price: 70.19, changed_at: ago(20 * DAY), lowest_price: 55 })] });

    assert.match(text(cards()[0].querySelector(".price-change")), /Ha bajado/);
    assert.equal(cards()[0].classList.contains("news"), false);
    assert.equal(app.$("prices-badge").hidden, true);
    assert.equal(text(app.$("price-summary")), "");
  });

  test("a rise says so", async () => {
    await open({ products: [product({ price: 80, previous_price: 70.19, changed_at: ago(DAY), highest_price: 80 })] });

    assert.match(text(cards()[0].querySelector(".price-change")), /Ha subido 9,81 € \(antes 70,19 €\)/);
    assert.equal(cards()[0].classList.contains("news"), false);
  });

  test("at or below the target is news, unless it's sold out", async () => {
    await open({ products: [
      product({ id: 1, target_price: 75 }),
      product({ id: 2, url: LINK + "?x", target_price: 75, available: false }),
    ] });

    const [inStock, soldOut] = cards();
    assert.match(text(inStock.querySelector(".price-badges")), /Por debajo de tu objetivo de 75,00 €/);
    assert.ok(inStock.classList.contains("news"));
    assert.match(text(soldOut.querySelector(".price-badges")), /Agotado/);
    assert.equal(soldOut.classList.contains("news"), false);
    assert.equal(app.$("prices-badge").textContent, "1");
  });

  test("coming back in stock is news for a week", async () => {
    await open({ products: [
      product({ id: 1, restocked_at: ago(2 * DAY) }),
      product({ id: 2, url: LINK + "?x", restocked_at: ago(20 * DAY) }),
    ] });

    const [recent, old] = cards();
    assert.match(text(recent.querySelector(".price-badges")), /Ha vuelto a haber stock · hace 2 días/);
    assert.ok(recent.classList.contains("news"));
    assert.doesNotMatch(text(old.querySelector(".price-badges")), /stock/);
    assert.equal(old.classList.contains("news"), false);
    assert.equal(app.$("prices-badge").textContent, "1");
  });

  test("'Comprobar ahora' on a sold-out product that is back shows it", async () => {
    await open({
      products: [product({ available: false })],
      shop: () => ({ name: "Club C 85", price: 70.19, available: true }),
    });
    assert.equal(cards()[0].classList.contains("news"), false);

    cardButton(cards()[0], "check").click();
    await app.settle();

    assert.match(text(cards()[0].querySelector(".price-badges")), /Ha vuelto a haber stock/);
    assert.ok(cards()[0].classList.contains("news"));
  });

  test("a failed check is explained and the last good price stays", async () => {
    await open({ products: [product({ last_error: "No se ha podido conectar con la tienda.", last_error_at: ago(60000) })] });

    assert.match(text(cards()[0].querySelector(".price-error")),
      /La última comprobación falló \(hace 1 min\): No se ha podido conectar con la tienda\. El precio de arriba es el de ahora mismo\./);
    assert.equal(text(cards()[0].querySelector(".price-now .value")), "70,19 €");
  });

  test("names from shops are shown as text, never as markup", async () => {
    await open({ products: [product({ name: '<img src=x onerror="alert(1)">Zapatilla' })] });

    assert.equal(cards()[0].querySelector(".price-name img"), null);
    assert.equal(text(cards()[0].querySelector(".price-name")), '<img src=x onerror="alert(1)">Zapatilla');
  });

  test("the menu badge is filled on start-up, whatever view opens", async () => {
    await open({ products: [product({ target_price: 80 })] }, "dashboard");

    assert.equal(app.callsTo("/api/prices").length, 1);
    assert.equal(app.$("prices-badge").textContent, "1");
    assert.equal(app.$("prices-badge").hidden, false);
  });
});

describe("acting on a card", () => {
  test("'Comprobar ahora' reads the page again and shows the new price", async () => {
    let price = 70.19;
    await open({ products: [product()], shop: () => ({ name: "Club C 85", price, available: true }) });

    price = 49.95;
    app.click(cardButton(cards()[0], "check"));
    await app.settle();

    assert.equal(app.callsTo("/api/prices/1/check")[0].method, "POST");
    assert.equal(text(cards()[0].querySelector(".price-now .value")), "49,95 €");
    assert.match(text(cards()[0].querySelector(".price-change")), /Ha bajado 20,24 €/);
    assert.equal(app.$("toast").textContent, "Precio comprobado");
    assert.equal(app.$("prices-badge").textContent, "1");
  });

  test("a check that fails says so on the card", async () => {
    await open({ products: [product()], shop: () => ({ error: "La tienda ha respondido con un error (HTTP 500)." }) });

    app.click(cardButton(cards()[0], "check"));
    await app.settle();

    assert.match(text(cards()[0].querySelector(".price-error")), /HTTP 500/);
    assert.equal(app.$("toast").textContent, "No se ha podido comprobar: mira el aviso en la tarjeta");
  });

  test("the target price can be set, changed and removed", async () => {
    await open({ products: [product()] });

    app.click(cardButton(cards()[0], "target"));
    const form = cards()[0].querySelector(".price-target-form");
    assert.equal(form.hidden, false);
    form.querySelector("input").value = "65";
    form.dispatchEvent(new app.window.Event("submit", { cancelable: true, bubbles: true }));
    await app.settle();

    const patches = () => app.callsTo("/api/prices/1").filter((c) => c.method === "PATCH");
    assert.equal(patches()[0].form().target_price, "65");
    assert.match(text(cards()[0].querySelector(".price-badges")), /Objetivo: 65,00 €/);
    assert.equal(app.$("toast").textContent.replace(/\s/g, " "), "Te avisará si baja de 65,00 €");

    app.click(cardButton(cards()[0], "target"));
    assert.equal(cards()[0].querySelector(".price-target-form input").value, "65");
    app.click(cardButton(cards()[0], "target-clear"));
    await app.settle();

    assert.equal(patches()[1].form().target_price, "");
    assert.equal(text(cards()[0].querySelector(".price-badges")), "");
  });

  test("a target that isn't a price is not sent", async () => {
    await open({ products: [product()] });

    app.click(cardButton(cards()[0], "target"));
    const form = cards()[0].querySelector(".price-target-form");
    form.querySelector("input").value = "cincuenta";
    form.dispatchEvent(new app.window.Event("submit", { cancelable: true, bubbles: true }));
    await app.settle();

    assert.equal(text(form.querySelector(".price-target-msg")), "«cincuenta» no es un precio válido (ej.: 45 o 45,30).");
    assert.equal(app.callsTo("/api/prices/1").length, 0);
  });

  test("'Dejar de seguir' asks first and then removes it", async () => {
    const backend = await open({ products: [product()] });

    app.window.confirm = () => false;
    app.click(cardButton(cards()[0], "delete"));
    await app.settle();
    assert.equal(cards().length, 1);

    app.window.confirm = () => true;
    app.click(cardButton(cards()[0], "delete"));
    await app.settle();

    assert.equal(cards().length, 0);
    assert.equal(backend.state.products.length, 0);
    assert.equal(app.$("price-empty").hidden, false);
  });

  test("the chart shows the history as steps up to the last check, and hides again", async () => {
    const checked = new Date().toISOString();
    const backend = await open({ products: [product({ price: 55, previous_price: 70.19, changed_at: ago(DAY), checked_at: checked })] });
    backend.state.histories[1] = [{ date: ago(5 * DAY), price: 70.19 }, { date: ago(DAY), price: 55 }];

    const button = cardButton(cards()[0], "chart");
    app.click(button);
    await app.settle();

    assert.equal(button.getAttribute("aria-expanded"), "true");
    assert.equal(button.textContent, "Ocultar gráfico");
    assert.equal(cards()[0].querySelector(".price-chart").hidden, false);
    const chart = app.charts.at(-1);
    const dataset = chart.config.data.datasets[0];
    assert.equal(dataset.stepped, true);
    assert.deepEqual(JSON.parse(JSON.stringify(dataset.data.map((d) => d.y))), [70.19, 55, 55]);
    assert.equal(dataset.data.at(-1).x, checked);
    assert.equal(chart.config.options.scales.x.time.minUnit, "minute");
    assert.equal(cards()[0].querySelector(".chart-wrap-price").hidden, false);

    app.click(button);
    await app.settle();
    assert.equal(chart.destroyed, true);
    assert.equal(cards()[0].querySelector(".price-chart").hidden, true);
    assert.equal(button.getAttribute("aria-expanded"), "false");
  });

  test("an open chart stays open when the list is redrawn, and one price is no chart", async () => {
    await open({ products: [product()] });

    app.click(cardButton(cards()[0], "chart"));
    await app.settle();
    app.click(cardButton(cards()[0], "check"));
    await app.settle();

    assert.equal(cards()[0].querySelector(".price-chart").hidden, false);
    assert.equal(cardButton(cards()[0], "chart").textContent, "Ocultar gráfico");
    // a single price: no chart, just the sentence
    assert.match(text(cards()[0].querySelector(".price-chart .hint")), /^De momento solo hay un precio \(70,19 € desde hace/);
    assert.equal(cards()[0].querySelector(".chart-wrap-price").hidden, true);
    assert.equal(app.charts.filter((c) => !c.destroyed).length, 0);
  });
});

describe("timeAgo", () => {
  test("says how long ago in plain words", async () => {
    await open();
    const now = Date.parse("2026-09-25T12:00:00Z");
    const at = (ms) => app.window.timeAgo(new Date(now - ms).toISOString(), now);

    assert.equal(at(20 * 1000), "ahora mismo");
    assert.equal(at(5 * 60000), "hace 5 min");
    assert.equal(at(3 * 3600000), "hace 3 h");
    assert.equal(at(DAY), "hace 1 día");
    assert.equal(at(4 * DAY), "hace 4 días");
    assert.match(at(60 * DAY), /^el \d+ \w+\.? 2026$/);
  });
});
