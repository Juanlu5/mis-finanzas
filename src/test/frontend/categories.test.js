const { describe, test, afterEach } = require("node:test");
const assert = require("node:assert/strict");
const { loadApp, fakeBackend, makeTransactions } = require("./helpers");

let app;
let backend;
afterEach(() => app && app.close());

const CATEGORIES = [
  { name: "Compras", kind: "expense" },
  { name: "Nómina / Ingresos", kind: "income" },
  { name: "Ocio", kind: "expense" },
  { name: "Sin categorizar", kind: "expense" },
  { name: "Supermercado", kind: "expense" },
];
const RULES = [
  { keyword: "amazon", category: "Compras" },
  { keyword: "nomina", category: "Nómina / Ingresos" },
  { keyword: "airbnb", category: "Ocio" },
  { keyword: "cine", category: "Ocio" },
  { keyword: "lidl", category: "Supermercado" },
  { keyword: " dia ", category: "Supermercado" },
];

async function open(options = {}) {
  backend = fakeBackend({ categories: CATEGORIES, rules: RULES, transactions: [], ...options });
  app = await loadApp({ hash: "categories", api: backend.handler });
  return backend;
}

const tx = (id, category, description = `Movimiento ${id}`) =>
  ({ ...makeTransactions(1)[0], id, category, description });

const tabSelected = () => ["categories", "rules", "security"].filter((t) => app.$(`tab-${t}`).getAttribute("aria-selected") === "true");
const visiblePanels = () => ["categories", "rules", "security"].filter((t) => !app.$(`panel-${t}`).hidden);
const card = (name) => app.$$(".category-card").find((c) => c.querySelector(".name").textContent === name);
const feedback = (id) => (app.$(id).hidden ? null : { text: app.$(id).textContent, kind: app.$(id).className });
const groups = () => app.$$(".rule-group").map((g) => ({
  category: g.querySelector("h4").firstChild.textContent.trim(),
  count: g.querySelector("h4 .muted").textContent,
  keywords: [...g.querySelectorAll(".rule-chip .kw")].map((k) => k.textContent),
}));
const submit = (formId) => app.$(formId).requestSubmit();
const captureConfirm = (answer = true) => {
  const messages = [];
  app.window.confirm = (message) => { messages.push(message); return answer; };
  return messages;
};

describe("tabs", () => {
  test("open on Categorías, showing only that panel", async () => {
    await open();

    assert.deepEqual(tabSelected(), ["categories"]);
    assert.deepEqual(visiblePanels(), ["categories"]);
    assert.equal(app.$("tab-rules").tabIndex, -1, "only the selected tab is in the tab order");
  });

  test("clicking a tab shows its panel and hides the others", async () => {
    await open();

    app.click(app.$("tab-rules"));
    assert.deepEqual(tabSelected(), ["rules"]);
    assert.deepEqual(visiblePanels(), ["rules"]);

    app.click(app.$("tab-security"));
    assert.deepEqual(visiblePanels(), ["security"]);
  });

  test("arrow keys move between tabs, wrapping around, and move the focus too", async () => {
    await open();
    const press = (el, key) => el.dispatchEvent(new app.window.KeyboardEvent("keydown", { key, bubbles: true }));

    press(app.$("tab-categories"), "ArrowRight");
    assert.deepEqual(tabSelected(), ["rules"]);
    assert.equal(app.document.activeElement, app.$("tab-rules"));

    press(app.$("tab-rules"), "ArrowLeft");
    press(app.$("tab-categories"), "ArrowLeft");
    assert.deepEqual(tabSelected(), ["security"], "wraps from the first to the last");
  });

  test("the Reglas tab shows how many rules there are, and nothing when there are none", async () => {
    await open();
    assert.equal(app.$("rules-count").textContent, "6");
    app.close();

    await open({ rules: [] });
    assert.equal(app.$("rules-count").textContent, "");
  });
});

describe("categories", () => {
  test("one card per category with its kind and how many rules it has", async () => {
    await open();

    assert.equal(app.$$(".category-card").length, 5);
    assert.equal(card("Nómina / Ingresos").querySelector(".pill").textContent, "Ingreso");
    assert.equal(card("Compras").querySelector(".pill").textContent, "Gasto");
    assert.equal(card("Ocio").querySelector(".meta").textContent.replace(/\s+/g, " ").trim(), "2 reglas Eliminar");
    assert.match(card("Compras").querySelector(".meta").textContent, /1 regla\b(?!s)/);
    assert.match(card("Sin categorizar").querySelector(".meta").textContent, /Sin reglas/);
  });

  test("'Sin categorizar' is protected: no delete button, since the server refuses it", async () => {
    await open();

    assert.equal(card("Sin categorizar").querySelector("[data-del-cat]"), null);
    assert.match(card("Sin categorizar").textContent, /Protegida/);
    assert.notEqual(card("Ocio").querySelector("[data-del-cat]"), null);
  });

  test("clicking a card's rule count opens the rules filtered by that category", async () => {
    await open();

    app.click(card("Ocio").querySelector("[data-show-rules]"));

    assert.deepEqual(visiblePanels(), ["rules"]);
    assert.equal(app.$("rules-filter").value, "Ocio");
    assert.deepEqual(groups().map((g) => g.category), ["Ocio"]);
  });

  test("category names are escaped", async () => {
    await open({ categories: [...CATEGORIES, { name: `<img src=x onerror="window.pwned=1"> "q"`, kind: "expense" }], rules: [] });

    assert.equal(app.$$("#categories-list img").length, 0);
    assert.equal(app.window.pwned, undefined);
    assert.equal(app.$$(".category-card .name").at(-1).textContent, `<img src=x onerror="window.pwned=1"> "q"`);
  });
});

describe("deleting a category", () => {
  const remove = async (name) => { app.click(card(name).querySelector("[data-del-cat]")); await app.settle(); };

  test("the confirmation says how many movements and rules will be affected", async () => {
    await open({ transactions: [tx(1, "Ocio"), tx(2, "Ocio"), tx(3, "Ocio")] });
    const messages = captureConfirm(false);

    await remove("Ocio");

    assert.equal(messages.length, 1);
    assert.match(messages[0], /¿Eliminar la categoría «Ocio»\?/);
    assert.match(messages[0], /3 movimientos pasarán a «Sin categorizar» y se borrarán 2 reglas\./);
  });

  test("singular, rules only, and nothing at all are worded properly", async () => {
    await open({ transactions: [tx(1, "Compras")] });
    let messages = captureConfirm(false);
    await remove("Compras");
    assert.match(messages[0], /1 movimiento pasará a «Sin categorizar» y se borrará 1 regla\./);
    app.close();

    await open();
    messages = captureConfirm(false);
    await remove("Ocio");
    assert.match(messages[0], /\n\nSe borrarán 2 reglas\.$/);
    app.close();

    await open({ rules: [] });
    messages = captureConfirm(false);
    await remove("Ocio");
    assert.match(messages[0], /No tiene movimientos ni reglas\./);
  });

  test("cancelling deletes nothing", async () => {
    await open();
    captureConfirm(false);

    await remove("Ocio");

    assert.equal(app.callsTo("/api/categories").filter((c) => c.method === "DELETE").length, 0);
    assert.notEqual(card("Ocio"), undefined);
  });

  test("confirming deletes it by query parameter (names may contain a slash), moves its movements and refreshes", async () => {
    await open({ transactions: [tx(1, "Nómina / Ingresos")] });
    captureConfirm(true);

    await remove("Nómina / Ingresos");

    const request = app.callsTo("/api/categories").find((c) => c.method === "DELETE");
    assert.equal(request.params.get("name"), "Nómina / Ingresos");
    assert.equal(card("Nómina / Ingresos"), undefined);
    assert.equal(app.$("toast").textContent, "Categoría «Nómina / Ingresos» eliminada");
    assert.equal(backend.state.transactions[0].category, "Sin categorizar");
    assert.equal(app.$("rules-count").textContent, "5", "its rule went too");
  });

  test("a server error is reported and the list stays as the server has it", async () => {
    await open({ overrides: { "/api/categories": (call) => (call.method === "DELETE" ? { status: 400, body: { detail: "en uso" } } : CATEGORIES) } });
    captureConfirm(true);

    await remove("Ocio");

    assert.equal(app.$("toast").textContent, "Error: en uso");
  });
});

describe("adding a category", () => {
  const add = async (name, kind = "expense") => {
    app.$("new-category-name").value = name;
    app.$("new-category-kind").value = kind;
    submit("form-add-category");
    await app.settle();
  };

  test("sends the trimmed name and kind, clears the field and confirms", async () => {
    await open();

    await add("  Mascotas  ", "expense");

    const body = app.callsTo("/api/categories").find((c) => c.method === "POST").form();
    assert.deepEqual(body, { name: "Mascotas", kind: "expense" });
    assert.notEqual(card("Mascotas"), undefined);
    assert.equal(app.$("new-category-name").value, "");
    assert.deepEqual(feedback("category-feedback"), { text: "Categoría «Mascotas» añadida.", kind: "feedback ok" });
  });

  test("an income category can be created", async () => {
    await open();

    await add("Alquiler cobrado", "income");

    assert.equal(card("Alquiler cobrado").querySelector(".pill").textContent, "Ingreso");
  });

  test("a name that differs only in case is refused before asking the server", async () => {
    await open();
    const before = app.callsTo("/api/categories").length;

    await add("compras");

    assert.deepEqual(feedback("category-feedback"), { text: "Ya existe una categoría llamada «Compras».", kind: "feedback error" });
    assert.equal(app.callsTo("/api/categories").length, before);
  });

  test("a server refusal is shown next to the form", async () => {
    await open({ overrides: { "/api/categories": (call) => (call.method === "POST" ? { status: 400, body: { detail: "nombre no válido" } } : CATEGORIES) } });

    await add("Nueva");

    assert.deepEqual(feedback("category-feedback"), { text: "nombre no válido", kind: "feedback error" });
  });
});

describe("the rules list", () => {
  test("groups rules by category in the categories' order, with counts", async () => {
    await open();

    assert.deepEqual(groups(), [
      { category: "Compras", count: "1", keywords: ["amazon"] },
      { category: "Nómina / Ingresos", count: "1", keywords: ["nomina"] },
      { category: "Ocio", count: "2", keywords: ["airbnb", "cine"] },
      { category: "Supermercado", count: "2", keywords: ["dia", "lidl"] },
    ]);
    assert.equal(app.$("rules-summary").textContent, "6 reglas");
  });

  test("a whole-word rule is shown without its padding and tagged, with the exact keyword on hover", async () => {
    await open();

    const chip = app.$$(".rule-chip").find((c) => c.querySelector(".kw").textContent === "dia");

    assert.equal(chip.querySelector(".tag").textContent, "palabra");
    assert.equal(chip.title, '" dia "');
    assert.equal(app.$$(".rule-chip .tag").length, 1);
  });

  test("a keyword padded on one side only shows the space as ␣", async () => {
    await open({ rules: [{ keyword: "uber ", category: "Ocio" }, { keyword: " bar", category: "Ocio" }] });

    assert.deepEqual(groups()[0].keywords, ["␣bar", "uber␣"]);
    assert.equal(app.$$(".rule-chip .tag").length, 0);
  });

  test("keywords and category names are escaped, in the text and in the attributes", async () => {
    const evil = `<img src=x onerror="window.pwned=1">"'`;
    await open({ categories: [...CATEGORIES, { name: evil, kind: "expense" }], rules: [{ keyword: evil, category: evil }] });

    assert.equal(app.$$("#rules-list img").length, 0);
    assert.equal(app.window.pwned, undefined);
    assert.equal(app.$$(".rule-chip .kw")[0].textContent, evil);
    assert.equal(app.$$(".chip-x")[0].getAttribute("aria-label"), `Eliminar la regla «${evil}»`);
  });

  test("with no rules it says so", async () => {
    await open({ rules: [] });

    assert.match(app.$("rules-list").textContent, /Aún no has añadido reglas/);
  });

  test("the filter matches keywords and category names, ignoring case, and says how many", async () => {
    await open();
    const filterBy = (text) => { app.$("rules-filter").value = text; app.$("rules-filter").dispatchEvent(new app.window.Event("input")); };

    filterBy("AMA");
    assert.deepEqual(groups().map((g) => g.keywords), [["amazon"]]);
    assert.equal(app.$("rules-summary").textContent, "1 de 6 reglas");

    filterBy("ocio");
    assert.deepEqual(groups().map((g) => g.category), ["Ocio"]);

    filterBy("");
    assert.equal(groups().length, 4);
    assert.equal(app.$("rules-summary").textContent, "6 reglas");
  });

  test("a filter that matches nothing says so, showing the text safely", async () => {
    await open();

    app.$("rules-filter").value = "<b>zzz";
    app.$("rules-filter").dispatchEvent(new app.window.Event("input"));

    assert.equal(app.$("rules-list").textContent.trim(), "Ninguna regla coincide con «<b>zzz».");
    assert.equal(app.$$("#rules-list b").length, 0);
  });

  test("the × removes a rule and everything that counts rules follows", async () => {
    await open();

    app.click(app.document.querySelector('.chip-x[data-kw="airbnb"]'));
    await app.settle();

    assert.equal(app.calls.filter((c) => c.method === "DELETE" && /^\/api\/rules\/\d+$/.test(c.path)).length, 1);
    assert.deepEqual(groups().find((g) => g.category === "Ocio").keywords, ["cine"]);
    assert.equal(app.$("rules-count").textContent, "5");
    assert.equal(app.$("toast").textContent, "Regla «airbnb» eliminada");
    assert.match(card("Ocio").querySelector(".meta").textContent, /1 regla\b(?!s)/);
  });
});

describe("adding a rule", () => {
  const add = async (keyword, category = "Compras", { whole = false } = {}) => {
    app.$("new-rule-keyword").value = keyword;
    app.$("new-rule-category").value = category;
    app.$("new-rule-whole").checked = whole;
    submit("form-add-rule");
    await app.settle();
  };
  const posted = () => app.callsTo("/api/rules").filter((c) => c.method === "POST").map((c) => c.form());

  test("sends the trimmed keyword and the category, then shows it and explains what happens next", async () => {
    await open();

    await add("  ikea ", "Compras");

    assert.deepEqual(posted(), [{ keyword: "ikea", category: "Compras" }]);
    assert.deepEqual(groups().find((g) => g.category === "Compras").keywords, ["amazon", "ikea"]);
    assert.equal(app.$("rules-count").textContent, "7");
    assert.match(feedback("rule-feedback").text, /Regla añadida: «ikea» → Compras\. .*Aplicar reglas/);
    assert.equal(feedback("rule-feedback").kind, "feedback ok");
  });

  test("clears the form after adding, but the category stays as chosen", async () => {
    await open();

    await add("ikea", "Ocio");

    assert.equal(app.$("new-rule-keyword").value, "");
    assert.equal(app.$("new-rule-whole").checked, false);
    assert.equal(app.$("new-rule-category").value, "Ocio", "adding several rules for one category in a row is common");
  });

  test("'Solo palabra completa' pads the keyword with spaces, which is what makes it match whole words", async () => {
    await open();

    await add("ikea", "Compras", { whole: true });

    assert.deepEqual(posted(), [{ keyword: " ikea ", category: "Compras" }]);
    assert.equal(app.$$(".rule-chip").find((c) => c.querySelector(".kw").textContent === "ikea").querySelector(".tag").textContent, "palabra");
  });

  test("the same keyword for the same category is refused, whatever the case", async () => {
    await open();

    await add("AMAZON", "Compras");

    assert.equal(feedback("rule-feedback").text, "Ya existe esa regla («AMAZON» → Compras).");
    assert.equal(posted().length, 0);
  });

  test("the same keyword for another category is refused too, naming where it goes", async () => {
    await open();

    await add("lidl", "Ocio");

    assert.match(feedback("rule-feedback").text, /Ya existe una regla para «lidl» y la manda a «Supermercado»/);
    assert.equal(feedback("rule-feedback").kind, "feedback error");
    assert.equal(posted().length, 0);
  });

  test("a whole-word rule and a plain one with the same word are different rules", async () => {
    await open();

    await add("lidl", "Supermercado", { whole: true });

    assert.deepEqual(posted(), [{ keyword: " lidl ", category: "Supermercado" }]);
  });

  test("an empty keyword asks for one", async () => {
    await open();

    await add("   ");

    assert.equal(feedback("rule-feedback").text, "Escribe una palabra clave.");
    assert.equal(posted().length, 0);
  });

  test("without any category there is nothing to assign it to", async () => {
    await open({ categories: [], rules: [] });

    await add("ikea", "");

    assert.equal(feedback("rule-feedback").text, "Crea antes una categoría a la que asignarla.");
  });

  test("a server refusal is shown next to the form and the keyword is kept", async () => {
    await open({ overrides: { "/api/rules": (call) => (call.method === "POST" ? { status: 400, body: { detail: "no válida" } } : []) } });

    await add("ikea");

    assert.equal(feedback("rule-feedback").text, "no válida");
    assert.equal(app.$("new-rule-keyword").value, "ikea");
  });

  test("an old message goes away when trying again", async () => {
    await open();
    await add("   ");
    assert.notEqual(feedback("rule-feedback"), null);

    await add("ikea");

    assert.equal(feedback("rule-feedback").kind, "feedback ok");
  });
});

describe("applying the rules to what's already imported", () => {
  const button = () => app.$("btn-apply-rules");

  test("says how many movements are waiting, and enables the button", async () => {
    await open({ transactions: [tx(1, "Sin categorizar"), tx(2, "Sin categorizar"), tx(3, "Ocio")] });

    assert.equal(app.$("pending-info").textContent, "2 movimientos sin categorizar.");
    assert.equal(button().disabled, false);
  });

  test("singular, and disabled with nothing pending", async () => {
    await open({ transactions: [tx(1, "Sin categorizar")] });
    assert.equal(app.$("pending-info").textContent, "1 movimiento sin categorizar.");
    app.close();

    await open({ transactions: [tx(1, "Ocio")] });
    assert.equal(app.$("pending-info").textContent, "No hay movimientos sin categorizar.");
    assert.equal(button().disabled, true);
  });

  test("applies the rules, reports how many changed and refreshes the count", async () => {
    await open({ transactions: [tx(1, "Sin categorizar", "COMPRA AMAZON 123"), tx(2, "Sin categorizar", "CINE YELMO"), tx(3, "Sin categorizar", "PANADERIA")] });

    app.click(button());
    await app.settle();

    assert.equal(app.callsTo("/api/rules/apply-all").length, 1);
    assert.equal(app.$("toast").textContent, "2 movimientos recategorizados");
    assert.equal(app.$("pending-info").textContent, "1 movimiento sin categorizar.");
    assert.equal(button().disabled, false, "the unmatched one still waits");
  });

  test("says so when no rule recognises the pending movements", async () => {
    await open({ transactions: [tx(1, "Sin categorizar", "PANADERIA")] });

    app.click(button());
    await app.settle();

    assert.equal(app.$("toast").textContent, "Ninguna regla reconoce esos movimientos");
  });

  test("whole-word rules apply to whole words only", async () => {
    await open({ transactions: [tx(1, "Sin categorizar", "MEDIA MARKT"), tx(2, "Sin categorizar", "DIA SUPERMERCADOS")] });

    app.click(button());
    await app.settle();

    assert.deepEqual(backend.state.transactions.map((t) => t.category), ["Sin categorizar", "Supermercado"]);
  });

  test("the button can't be pressed twice while it works", async () => {
    const held = [];
    await open({
      transactions: [tx(1, "Sin categorizar", "CINE")],
      overrides: { "/api/rules/apply-all": () => new Promise((resolve) => held.push(() => resolve({ updated: 1 }))) },
    });

    app.click(button());
    await new Promise((r) => setImmediate(r));
    assert.equal(button().disabled, true);
    held[0]();
    await app.settle();
    assert.equal(app.callsTo("/api/rules/apply-all").length, 1);
  });
});

describe("security", () => {
  const fill = (current, next, repeat) => {
    app.$("current-password").value = current;
    app.$("new-password").value = next;
    app.$("repeat-password").value = repeat;
  };
  const change = async (...values) => { fill(...values); submit("form-change-password"); await app.settle(); };

  test("shows who is logged in", async () => {
    await open();

    assert.equal(app.$("auth-username").textContent, "admin");
  });

  test("password managers are told which field is which", async () => {
    await open();

    assert.equal(app.$("current-password").autocomplete, "current-password");
    assert.equal(app.$("new-password").autocomplete, "new-password");
    assert.equal(app.$("repeat-password").autocomplete, "new-password");
  });

  test("two different new passwords are refused before sending: a typo would lock the only user out", async () => {
    await open();

    await change("secreta", "nueva-clave", "nueva-clabe");

    assert.equal(feedback("password-feedback").text, "Las contraseñas nuevas no coinciden.");
    assert.equal(app.callsTo("/api/auth/change-password").length, 0);
  });

  test("a short new password is refused before sending", async () => {
    await open();

    await change("secreta", "abc", "abc");

    assert.equal(feedback("password-feedback").text, "La contraseña nueva debe tener al menos 6 caracteres.");
    assert.equal(app.callsTo("/api/auth/change-password").length, 0);
  });

  test("a wrong current password shows the server's message and keeps what was typed", async () => {
    await open();

    await change("equivocada", "nueva-clave", "nueva-clave");

    assert.deepEqual(feedback("password-feedback"), { text: "La contraseña actual no es correcta", kind: "feedback error" });
    assert.equal(app.$("new-password").value, "nueva-clave");
    assert.deepEqual(backend.state.passwordChanges, []);
  });

  test("success sends both passwords, empties the form and says what to do next", async () => {
    await open();

    await change("secreta", "nueva-clave", "nueva-clave");

    assert.deepEqual(app.callsTo("/api/auth/change-password")[0].json(), { current_password: "secreta", new_password: "nueva-clave" });
    assert.deepEqual(backend.state.passwordChanges, ["nueva-clave"]);
    assert.equal(app.$("current-password").value, "");
    assert.equal(app.$("repeat-password").value, "");
    assert.deepEqual(feedback("password-feedback"), { text: "Contraseña actualizada. Úsala la próxima vez que inicies sesión.", kind: "feedback ok" });
  });
});
