const { describe, test, afterEach } = require("node:test");
const assert = require("node:assert/strict");
const { loadApp, fakeBackend, wait } = require("./helpers");

let app;
afterEach(() => app && app.close());

const tx = (id, description, category) => ({
  id, date: `2026-09-${String(10 + id).padStart(2, "0")}`, description, amount: -10, category,
  account: "Principal", accounting_month: "2026-09",
});
const LIDL = "Lidl centro — Pago con tarjeta";
const TRANSACTIONS = [
  tx(1, LIDL, "Ocio"),
  tx(2, LIDL, "Sin categorizar"),
  tx(3, LIDL, "Supermercado"),
  tx(4, "Farmacia del pilar — Pago con tarjeta", "Salud"),
  tx(5, "Bizum — ENVIADO: Sin concepto", "Ocio"),
];
const CATEGORIES = ["Ocio", "Salud", "Supermercado", "Sin categorizar"].map((name) => ({ name, kind: "expense" }));

async function open({ rules = [] } = {}) {
  const backend = fakeBackend({ transactions: TRANSACTIONS.map((t) => ({ ...t })), categories: CATEGORIES, rules });
  app = await loadApp({ hash: "transactions", api: backend.handler });
  return backend;
}

async function recategorize(id, category) {
  app.change(app.document.querySelector(`.cat-select[data-id="${id}"]`), category);
  await app.settle();
}

const suggestion = () => app.$("rule-suggest");
const impact = () => app.$("rule-suggest-impact").textContent;
const rulePosts = () => app.callsTo("/api/rules").filter((c) => c.method === "POST");

describe("suggesting a rule after a manual change", () => {
  test("offers a rule with a keyword taken from the description and says what it would do", async () => {
    await open();

    await recategorize(1, "Supermercado");

    assert.equal(suggestion().hidden, false);
    assert.equal(app.$("rule-suggest-keyword").value, "lidl centro");
    assert.equal(app.$("rule-suggest-category").textContent, "Supermercado");
    assert.match(impact(), /^Coincide con 3 movimientos · 2 ya en Supermercado · 1 pasarían a Supermercado \(1 de Sin categorizar\)/);
    assert.equal(app.$("rule-suggest-apply").hidden, false);
    assert.equal(app.$("rule-suggest-apply").textContent, "Crear regla y pasar 1 a Supermercado");
    assert.equal(app.$("rule-suggest-create").textContent, "Crear regla sin cambiar ese");
    assert.equal(app.$("rule-suggest-create").className, "ghost");
  });

  test("'create and apply' creates the rule, moves the matching movements and reloads the table", async () => {
    const backend = await open();
    await recategorize(1, "Supermercado");

    app.click(app.$("rule-suggest-apply"));
    await app.settle();

    assert.equal(rulePosts().length, 1);
    assert.equal(rulePosts()[0].form().apply, "true");
    assert.deepEqual(JSON.parse(JSON.stringify(backend.state.rules.map((r) => [r.keyword, r.category]))), [["lidl centro", "Supermercado"]]);
    assert.equal(backend.state.transactions.find((t) => t.id === 2).category, "Supermercado");
    assert.equal(suggestion().hidden, true);
    assert.equal(app.$("toast").textContent, "Regla creada: «lidl centro» → Supermercado · 1 movimiento actualizado");
  });

  test("'create without changing them' leaves the other movements as they are", async () => {
    const backend = await open();
    await recategorize(1, "Supermercado");

    app.click(app.$("rule-suggest-create"));
    await app.settle();

    assert.equal(rulePosts()[0].form().apply, "false");
    assert.equal(backend.state.transactions.find((t) => t.id === 2).category, "Sin categorizar");
    assert.equal(app.$("toast").textContent, "Regla creada: «lidl centro» → Supermercado");
  });

  test("'not now' closes it without creating anything", async () => {
    await open();
    await recategorize(1, "Supermercado");

    app.click(app.$("rule-suggest-dismiss"));
    await app.settle();

    assert.equal(suggestion().hidden, true);
    assert.equal(rulePosts().length, 0);
  });

  test("moving a movement back to 'Sin categorizar' suggests nothing", async () => {
    await open();

    await recategorize(1, "Sin categorizar");

    assert.equal(suggestion().hidden, true);
    assert.equal(app.callsTo("/api/rules/preview").length, 0);
  });

  test("a rule with the same keyword already sending them elsewhere is pointed out, not duplicated", async () => {
    await open({ rules: [{ keyword: "lidl centro", category: "Ocio" }] });

    await recategorize(3, "Salud");

    assert.equal(impact(), "Ya hay una regla «lidl centro» que los manda a Ocio: cámbiala en Categorías y reglas.");
    assert.equal(app.$("rule-suggest-create").disabled, true);
    assert.equal(app.$("rule-suggest-apply").hidden, true);
  });

  test("movements a more specific rule decides are said to stay as they are", async () => {
    await open({ rules: [{ keyword: "farmacia del", category: "Salud" }] });

    await recategorize(1, "Ocio"); // no-op for row 1, but offers "lidl centro" -> Ocio
    app.$("rule-suggest-keyword").value = "farmacia";
    app.$("rule-suggest-keyword").dispatchEvent(new app.window.Event("input"));
    await wait(350);
    await app.settle();

    assert.match(impact(), /1 se quedan como están porque otra regla más concreta decide/);
    assert.equal(app.$("rule-suggest-apply").hidden, true, "nothing to apply to");
    assert.equal(app.$("rule-suggest-create").disabled, false);
    assert.equal(app.$("rule-suggest-create").textContent, "Crear regla", "no 'solo' when it is the only way");
    assert.equal(app.$("rule-suggest-create").className, "primary");
  });

  test("editing the keyword disables the buttons until the new impact is known", async () => {
    await open();
    await recategorize(1, "Supermercado");

    app.$("rule-suggest-keyword").value = "lidl";
    app.$("rule-suggest-keyword").dispatchEvent(new app.window.Event("input"));
    assert.equal(app.$("rule-suggest-create").disabled, true);
    await wait(350);
    await app.settle();

    assert.equal(app.callsTo("/api/rules/preview").at(-1).params.get("keyword"), "lidl");
    assert.equal(app.$("rule-suggest-create").disabled, false);
  });

  test("a description with nothing specific leaves the keyword for the user to write", async () => {
    await open();

    await recategorize(5, "Salud");

    assert.equal(app.$("rule-suggest-keyword").value, "");
    assert.match(impact(), /^Escribe la palabra/);
    assert.equal(app.$("rule-suggest-create").disabled, true);
  });
});
