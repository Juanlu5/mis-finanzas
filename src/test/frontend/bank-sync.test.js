const { describe, test, afterEach } = require("node:test");
const assert = require("node:assert/strict");
const { loadApp, fakeBackend } = require("./helpers");

let app;
afterEach(() => app && app.close());

const NOT_LINKED = { linked: false, aspsp: null, valid_until: null };
const inDays = (n) => new Date(Date.now() + n * 86400000).toISOString();
const LINKED = { linked: true, aspsp: "BBVA", valid_until: inDays(150), expired: false };
const RESULT = { added: 3, duplicates: 0, skipped: 0, total_rows: 3, balances_filled: 0 };

async function open({ status = NOT_LINKED, accounts = ["Principal"], overrides = {}, transactions } = {}) {
  const backend = fakeBackend({
    transactions,
    overrides: {
      "/api/accounts": () => accounts,
      "/api/bank-sync/status": () => status,
      ...overrides,
    },
  });
  app = await loadApp({ hash: "import", api: backend.handler });
}

async function openBankTab() {
  app.click(app.$("tab-import-bank"));
  await app.settle();
}

function setAccount(value) {
  app.$("bank-account").value = value;
  app.$("bank-account").dispatchEvent(new app.window.Event("input"));
}

function fillValidForm() {
  app.change(app.$("bank-date-from"), "2026-03-01");
  app.change(app.$("bank-date-to"), "2026-03-31");
  setAccount("Principal");
}

describe("the bank tab", () => {
  test("shows the link button when there is no bank connection yet", async () => {
    await open();
    await openBankTab();

    assert.equal(app.$("bank-sync-not-linked").hidden, false);
    assert.equal(app.$("bank-sync-linked").hidden, true);
  });

  test("shows the linked state with the bank name and expiry", async () => {
    await open({ status: LINKED });
    await openBankTab();

    assert.equal(app.$("bank-sync-linked").hidden, false);
    assert.equal(app.$("bank-sync-not-linked").hidden, true);
    assert.equal(app.$("bank-sync-aspsp").textContent, "BBVA");
  });

  test("clicking 'Vincular con BBVA' asks the backend and follows the returned URL", async () => {
    await open({ overrides: { "/api/bank-sync/link": () => ({ url: "https://bbva.example/authorize" }) } });
    await openBankTab();

    app.click(app.$("btn-bank-link"));
    await app.settle();

    // jsdom doesn't implement cross-origin navigation, so this only checks the app asked for it,
    // not that the browser actually followed location.href.
    assert.equal(app.callsTo("/api/bank-sync/link").length, 1);
  });

  test("a failure starting the link shows an inline message next to the button", async () => {
    await open({ overrides: { "/api/bank-sync/link": () => ({ status: 400, body: { detail: "No configurado" } }) } });
    await openBankTab();

    app.click(app.$("btn-bank-link"));
    await app.settle();

    assert.equal(app.$("bank-sync-feedback").hidden, false);
    assert.equal(app.$("bank-sync-feedback").textContent, "No configurado");
  });

  test("the import button stays disabled until the dates and the account are all filled", async () => {
    // Two existing accounts (ambiguous) means neither the file tab nor this one gets pre-filled.
    await open({ status: LINKED, accounts: ["Principal", "Ahorro"] });
    await openBankTab();

    assert.equal(app.$("btn-bank-import").disabled, true);
    app.change(app.$("bank-date-from"), "2026-03-01");
    assert.equal(app.$("btn-bank-import").disabled, true);
    app.change(app.$("bank-date-to"), "2026-03-31");
    assert.equal(app.$("btn-bank-import").disabled, true);
    setAccount("Principal");
    assert.equal(app.$("btn-bank-import").disabled, false);
  });

  test("importing a range sends form-encoded dates and shows the same result screen as a file import", async () => {
    await open({ status: LINKED, overrides: { "/api/bank-sync/import": () => RESULT } });
    await openBankTab();
    fillValidForm();

    app.click(app.$("btn-bank-import"));
    await app.settle();

    assert.equal(app.$("import-step-result").hidden, false);
    assert.equal(app.$("import-tabs-wrap").hidden, true);
    assert.equal(app.$("result-added").textContent, "3");
    const call = app.callsTo("/api/bank-sync/import")[0];
    assert.deepEqual(call.form(), { date_from: "2026-03-01", date_to: "2026-03-31", account: "Principal" });
  });

  test("movements still pending at the bank are explained on the result screen", async () => {
    await open({ status: LINKED, overrides: { "/api/bank-sync/import": () => ({ ...RESULT, pending: 2 }) } });
    await openBankTab();
    fillValidForm();

    app.click(app.$("btn-bank-import"));
    await app.settle();

    assert.match(app.$("result-notes").textContent, /2 movimientos todavía están pendientes/);
  });

  test("no pending note when the bank returned only booked movements", async () => {
    await open({ status: LINKED, overrides: { "/api/bank-sync/import": () => ({ ...RESULT, pending: 0 }) } });
    await openBankTab();
    fillValidForm();

    app.click(app.$("btn-bank-import"));
    await app.settle();

    assert.doesNotMatch(app.$("result-notes").textContent, /pendiente/);
  });

  test("an expired bank session shows an inline, actionable error rather than a toast", async () => {
    await open({
      status: LINKED,
      overrides: {
        "/api/bank-sync/import": () => ({
          status: 401,
          body: { detail: "La sesión con el banco ha caducado, vuelve a vincular la cuenta." },
        }),
      },
    });
    await openBankTab();
    fillValidForm();

    app.click(app.$("btn-bank-import"));
    await app.settle();

    assert.equal(app.$("bank-sync-error").hidden, false);
    assert.match(app.$("bank-sync-error").textContent, /caducado/);
    assert.equal(app.$("import-step-result").hidden, true);
  });
});

describe("returning from the bank's authorization redirect", () => {
  test("a successful link switches to the bank tab and shows a confirmation toast", async () => {
    const backend = fakeBackend({
      overrides: { "/api/accounts": () => ["Principal"], "/api/bank-sync/status": () => LINKED },
    });
    app = await loadApp({ hash: "import?bank=linked", api: backend.handler });

    assert.equal(app.$("tab-import-bank").getAttribute("aria-selected"), "true");
    assert.equal(app.$("toast").textContent, "Cuenta bancaria vinculada correctamente.");
    assert.equal(app.window.location.hash, "#import");
  });

  test("a failed link switches to the bank tab too, showing the not-linked state and a toast", async () => {
    const backend = fakeBackend({
      overrides: { "/api/accounts": () => ["Principal"], "/api/bank-sync/status": () => NOT_LINKED },
    });
    app = await loadApp({ hash: "import?bank=error", api: backend.handler });

    assert.equal(app.$("tab-import-bank").getAttribute("aria-selected"), "true");
    assert.equal(app.$("bank-sync-not-linked").hidden, false);
    assert.equal(app.$("toast").textContent, "No se ha podido vincular la cuenta bancaria.");
  });
});

describe("unlinking the bank", () => {
  // A stateful status, so the page's reload after unlinking sees what the DELETE did.
  function unlinkable(deleteResponse = () => ({ linked: false })) {
    let linked = true;
    return {
      "/api/bank-sync/status": () => (linked ? LINKED : NOT_LINKED),
      "/api/bank-sync/link": (call) => {
        if (call.method !== "DELETE") return { url: "https://bbva.example/authorize" };
        const response = deleteResponse();
        if (!response.status) linked = false;
        return response;
      },
    };
  }

  test("after confirming, asks the backend and goes back to the not-linked state", async () => {
    await open({ status: LINKED, overrides: unlinkable() });
    await openBankTab();

    app.click(app.$("btn-bank-unlink"));
    await app.settle();

    const deletes = app.callsTo("/api/bank-sync/link").filter((c) => c.method === "DELETE");
    assert.equal(deletes.length, 1);
    assert.equal(app.$("bank-sync-not-linked").hidden, false);
    assert.equal(app.$("bank-sync-linked").hidden, true);
    assert.equal(app.$("toast").textContent, "Cuenta bancaria desvinculada.");
  });

  test("cancelling the confirmation leaves the link alone", async () => {
    await open({ status: LINKED, overrides: unlinkable() });
    await openBankTab();
    app.window.confirm = () => false;

    app.click(app.$("btn-bank-unlink"));
    await app.settle();

    assert.equal(app.callsTo("/api/bank-sync/link").length, 0);
    assert.equal(app.$("bank-sync-linked").hidden, false);
  });

  test("a failure shows an inline error and keeps the linked state", async () => {
    await open({
      status: LINKED,
      overrides: unlinkable(() => ({ status: 502, body: { detail: "Enable Banking no responde" } })),
    });
    await openBankTab();

    app.click(app.$("btn-bank-unlink"));
    await app.settle();

    assert.equal(app.$("bank-sync-error").hidden, false);
    assert.equal(app.$("bank-sync-error").textContent, "No se ha podido desvincular: Enable Banking no responde");
    assert.equal(app.$("bank-sync-linked").hidden, false);
    assert.equal(app.$("btn-bank-unlink").disabled, false);
  });
});

describe("updating since the last stored movement", () => {
  const movement = (id, date, account) => ({
    id, date, account, description: `Mov ${id}`, amount: -1, category: "Ocio", accounting_month: date.slice(0, 7),
  });
  const TRANSACTIONS = [
    movement(1, "2026-09-02", "Principal"),
    movement(2, "2026-09-20", "Principal"),
    movement(3, "2026-09-10", "Principal"),
    movement(4, "2026-09-23", "Ahorro"),
  ];
  const today = () => app.value("localIsoDate(new Date())");
  const importCalls = () => app.callsTo("/api/bank-sync/import");

  test("explains the range: from the account's newest movement minus 7 days, to today", async () => {
    await open({ status: LINKED, transactions: TRANSACTIONS });
    await openBankTab();

    assert.match(app.$("bank-auto-range").textContent, /Descargará del 13\/09\/2026 hasta hoy/);
    assert.match(app.$("bank-auto-range").textContent, /es del 20\/09\/2026/);
    assert.equal(app.$("btn-bank-update").disabled, false);
  });

  test("the button downloads that range for that account", async () => {
    await open({ status: LINKED, transactions: TRANSACTIONS, overrides: { "/api/bank-sync/import": () => RESULT } });
    await openBankTab();

    app.click(app.$("btn-bank-update"));
    await app.settle();

    assert.equal(importCalls().length, 1);
    const body = new URLSearchParams(importCalls()[0].body);
    assert.equal(body.get("date_from"), "2026-09-13");
    assert.equal(body.get("date_to"), today());
    assert.equal(body.get("account"), "Principal");
    assert.equal(app.$("import-step-result").hidden, false);
  });

  test("changing the account looks the range up again for the new one", async () => {
    await open({ status: LINKED, accounts: ["Principal", "Ahorro"], transactions: TRANSACTIONS });
    await openBankTab();

    app.$("bank-account").value = "Ahorro";
    app.$("bank-account").dispatchEvent(new app.window.Event("input"));
    // the old account's range is dropped at once, not after the lookup
    assert.equal(app.$("btn-bank-update").disabled, true);
    await new Promise((resolve) => setTimeout(resolve, 350));
    await app.settle();

    assert.match(app.$("bank-auto-range").textContent, /Descargará del 16\/09\/2026/);
    assert.equal(app.$("btn-bank-update").disabled, false);
  });

  test("an account without movements asks for dates instead", async () => {
    await open({ status: LINKED, accounts: [], transactions: TRANSACTIONS });
    await openBankTab();
    setAccount("Nueva");
    await new Promise((resolve) => setTimeout(resolve, 350));
    await app.settle();

    assert.match(app.$("bank-auto-range").textContent, /no tiene movimientos todavía/);
    assert.equal(app.$("btn-bank-update").disabled, true);
    assert.equal(app.$("bank-custom-range").open, true);
  });

  test("without an account there is nothing to update", async () => {
    await open({ status: LINKED, accounts: ["Principal", "Ahorro"], transactions: TRANSACTIONS });
    await openBankTab();

    assert.equal(app.$("bank-account").value, "");
    assert.equal(app.$("btn-bank-update").disabled, true);
    assert.match(app.$("bank-auto-range").textContent, /Indica la cuenta/);
  });

  test("a failed download shows the error inline and the button can be pressed again", async () => {
    await open({
      status: LINKED,
      transactions: TRANSACTIONS,
      overrides: { "/api/bank-sync/import": () => ({ status: 429, body: { detail: "El banco ha limitado las consultas" } }) },
    });
    await openBankTab();

    app.click(app.$("btn-bank-update"));
    await app.settle();

    assert.equal(app.$("bank-sync-error").textContent, "No se ha podido importar: El banco ha limitado las consultas");
    assert.equal(app.$("btn-bank-update").disabled, false);
    assert.equal(app.$("btn-bank-update").textContent, "Actualizar movimientos");
  });
});

describe("the automatic range arithmetic", () => {
  const range = (newest, today) => app.value(`autoRangeFor(${JSON.stringify(newest)}, ${JSON.stringify(today)})`);

  test("goes back 7 days across month and year boundaries", async () => {
    await open();
    assert.deepEqual(range("2026-03-03", "2026-09-24"), { from: "2026-02-24", to: "2026-09-24", newest: "2026-03-03" });
    assert.deepEqual(range("2027-01-02", "2027-01-05").from, "2026-12-26");
  });

  test("never starts after today, even with a future-dated movement", async () => {
    await open();
    assert.equal(range("2026-10-30", "2026-09-24").from, "2026-09-24");
  });
});

describe("warning before the bank authorization expires", () => {
  const expiresIn = (days) => ({ linked: true, aspsp: "BBVA", valid_until: inDays(days), expired: false });
  const EXPIRED = { linked: false, aspsp: "BBVA", valid_until: inDays(-3), expired: true };

  async function openAt(hash, status, overrides = {}) {
    const backend = fakeBackend({
      overrides: { "/api/accounts": () => ["Principal"], "/api/bank-sync/status": () => status, ...overrides },
    });
    app = await loadApp({ hash, api: backend.handler });
  }

  test("no banner while there are more than 30 days left", async () => {
    await openAt("dashboard", expiresIn(40));
    assert.equal(app.$("bank-expiry-banner").hidden, true);
  });

  test("no banner when the bank was never linked", async () => {
    await openAt("dashboard", NOT_LINKED);
    assert.equal(app.$("bank-expiry-banner").hidden, true);
  });

  test("within 30 days, every view shows a banner with the days left", async () => {
    await openAt("dashboard", expiresIn(10));

    assert.equal(app.$("bank-expiry-banner").hidden, false);
    assert.match(app.$("bank-expiry-text").textContent, /caduca dentro de 10 días \(el \d+\/\d+\/\d{4}\)\./);

    app.window.location.hash = "transactions";
    await app.settle();
    assert.equal(app.$("bank-expiry-banner").hidden, false);
  });

  test("its button opens the bank tab, which offers to renew", async () => {
    await openAt("dashboard", expiresIn(10));

    app.click(app.$("btn-bank-expiry-renew"));
    await app.settle();

    assert.equal(app.window.location.hash, "#import");
    assert.equal(app.$("tab-import-bank").getAttribute("aria-selected"), "true");
    assert.equal(app.$("bank-renew").hidden, false);
    assert.match(app.$("bank-renew-text").textContent, /dentro de 10 días/);
    // the tab says it itself, so the banner steps aside there
    assert.equal(app.$("bank-expiry-banner").hidden, true);
  });

  test("renewing starts the same link flow as the first time", async () => {
    await openAt("import", expiresIn(5), { "/api/bank-sync/link": () => ({ status: 503, body: { detail: "No configurado" } }) });
    await openBankTab();

    app.click(app.$("btn-bank-renew"));
    await app.settle();

    assert.equal(app.callsTo("/api/bank-sync/link").length, 1);
    assert.equal(app.$("bank-renew-feedback").hidden, false);
    assert.equal(app.$("bank-renew-feedback").textContent, "No configurado");
  });

  test("no renew box in the bank tab while there is time left", async () => {
    await openAt("import", expiresIn(40));
    await openBankTab();

    assert.equal(app.$("bank-sync-linked").hidden, false);
    assert.equal(app.$("bank-renew").hidden, true);
  });

  test("an expired authorization says so, in the banner and above the link button", async () => {
    await openAt("dashboard", EXPIRED);
    assert.match(app.$("bank-expiry-text").textContent, /caducó el/);

    app.click(app.$("btn-bank-expiry-renew"));
    await app.settle();

    assert.equal(app.$("bank-sync-not-linked").hidden, false);
    assert.equal(app.$("bank-sync-expired").hidden, false);
    assert.match(app.$("bank-sync-expired").textContent, /caducó el .* Vuelve a vincular/);
  });

  test("says today and tomorrow in words", async () => {
    await openAt("dashboard", NOT_LINKED);
    const text = (days) => app.value(`bankExpiryText({ expired: false, days: ${days}, date: "22/3/2027" })`);
    assert.equal(text(0), "La autorización para descargar movimientos del banco caduca hoy (el 22/3/2027).");
    assert.equal(text(1), "La autorización para descargar movimientos del banco caduca mañana (el 22/3/2027).");
  });

  test("counts calendar days, and a past date counts as expired even if the server still said linked", async () => {
    await openAt("dashboard", NOT_LINKED);
    const notice = (until, now) =>
      app.value(`bankExpiryNotice({ linked: true, valid_until: ${JSON.stringify(until)} }, new Date(${JSON.stringify(now)}))`);
    assert.equal(notice("2027-03-22T12:00:00", "2027-03-21T23:00:00").days, 1);
    assert.equal(notice("2027-03-22T12:00:00", "2027-02-20T08:00:00").days, 30);
    assert.equal(notice("2027-03-22T12:00:00", "2027-02-19T08:00:00"), null);
    assert.equal(notice("2027-03-22T12:00:00", "2027-03-22T13:00:00").expired, true);
  });
});
