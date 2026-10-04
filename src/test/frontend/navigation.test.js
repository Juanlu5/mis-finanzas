const { describe, test, afterEach } = require("node:test");
const assert = require("node:assert/strict");
const { loadApp, fakeBackend } = require("./helpers");

let app;
afterEach(() => app && app.close());

const activeViews = () => app.$$(".view.active").map((v) => v.id);
const navButton = (view) => app.document.querySelector(`.nav-btn[data-view="${view}"]`);

async function open(hash) {
  app = await loadApp({ hash, api: fakeBackend().handler });
}

describe("the current view lives in the URL hash", () => {
  test("opening #transactions lands on the transactions view, not the dashboard", async () => {
    await open("transactions");

    assert.deepEqual(activeViews(), ["view-transactions"]);
    assert.equal(navButton("transactions").getAttribute("aria-current"), "page");
    assert.equal(navButton("dashboard").hasAttribute("aria-current"), false);
    assert.equal(app.callsTo("/api/transactions").length, 1);
  });

  test("no hash or an unknown one falls back to the dashboard", async () => {
    await open("");
    assert.deepEqual(activeViews(), ["view-dashboard"]);
    app.close();

    await open("nope");
    assert.deepEqual(activeViews(), ["view-dashboard"]);
  });

  test("clicking a menu entry changes the hash and shows that view", async () => {
    await open("");

    app.click(navButton("categories"));
    await app.settle();

    assert.equal(app.window.location.hash, "#categories");
    assert.deepEqual(activeViews(), ["view-categories"]);
    assert.equal(navButton("categories").getAttribute("aria-current"), "page");
    assert.equal(app.callsTo("/api/rules").length, 1, "the view loads its data");
  });

  test("back/forward (a hash change) switches the view", async () => {
    await open("transactions");

    app.window.location.hash = "import";
    await app.settle();

    assert.deepEqual(activeViews(), ["view-import"]);
  });

  test("clicking the entry you are already on refreshes it", async () => {
    await open("transactions");
    const before = app.callsTo("/api/transactions").length;

    app.click(navButton("transactions"));
    await app.settle();

    assert.equal(app.callsTo("/api/transactions").length, before + 1);
  });

  test("opening a view on the way in closes the mobile menu", async () => {
    await open("");
    app.document.body.classList.add("sidebar-open");

    app.click(navButton("import"));
    await app.settle();

    assert.equal(app.document.body.classList.contains("sidebar-open"), false);
  });
});

describe("theme toggle", () => {
  test("flips the theme, remembers it, and updates the label", async () => {
    await open("transactions");
    const root = app.document.documentElement;
    const before = root.getAttribute("data-theme");

    app.click(app.$("theme-toggle"));

    const after = root.getAttribute("data-theme");
    assert.notEqual(after, before);
    assert.equal(app.window.localStorage.getItem("theme"), after);
    assert.equal(app.$("theme-toggle-label").textContent, after === "dark" ? "Modo claro" : "Modo oscuro");
  });
});

describe("failed requests", () => {
  // jsdom doesn't raise window "unhandledrejection" by itself, so this checks the handler is wired
  // up and what it shows; a real browser raises the event for any un-caught rejected promise.
  test("a rejection nobody caught shows an error toast", async () => {
    await open("");
    const event = Object.assign(new app.window.Event("unhandledrejection"), { reason: new Error("Boom") });

    app.window.dispatchEvent(event);

    assert.equal(app.$("toast").textContent, "Error: Boom");
    assert.equal(app.$("toast").classList.contains("show"), true);
  });

  test("a rejection without a message still says something", async () => {
    await open("");
    app.window.dispatchEvent(Object.assign(new app.window.Event("unhandledrejection"), { reason: undefined }));

    assert.equal(app.$("toast").textContent, "Error: algo ha fallado");
  });

  test("api() turns the server's {detail} into the error message", async () => {
    app = await loadApp({
      hash: "",
      api: fakeBackend({ overrides: { "/api/rules": () => ({ status: 400, body: { detail: "Palabra clave vacía" } }) } }).handler,
    });

    const message = await app.window.eval(`api("/api/rules").then(() => "no error", (e) => e.message)`);

    assert.equal(message, "Palabra clave vacía");
  });
});
