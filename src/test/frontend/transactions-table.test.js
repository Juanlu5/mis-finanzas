const { describe, test, afterEach } = require("node:test");
const assert = require("node:assert/strict");
const { loadApp, fakeBackend, makeTransactions, tick, wait } = require("./helpers");

let app;
afterEach(() => app && app.close());

const lastTransactionsRequest = () => app.callsTo("/api/transactions").at(-1).params;
const descriptions = () => app.$$("#tx-table-body .desc-cell").map((td) => td.textContent);
const rowBoxes = () => app.$$(".row-select");
const sortButton = (column) => app.document.querySelector(`th[data-sort="${column}"] .sort-btn`);
const ariaSort = (column) => app.document.querySelector(`th[data-sort="${column}"]`).getAttribute("aria-sort");

async function open(options = {}) {
  const backend = fakeBackend(options);
  app = await loadApp({ hash: "transactions", api: options.api ? options.api(backend) : backend.handler });
  return backend;
}

describe("first load", () => {
  test("asks for the newest 50, renders them and shows the pager", async () => {
    await open({ total: 120 });

    const params = lastTransactionsRequest();
    assert.equal(params.get("sort"), "date");
    assert.equal(params.get("dir"), "desc");
    assert.equal(params.get("page"), "0");
    assert.equal(params.get("size"), "50");

    assert.equal(descriptions().length, 50);
    assert.equal(descriptions()[0], "Compra 120");
    assert.equal(app.$("tx-range").textContent, "1–50 de 120");
    assert.equal(app.$("tx-page-label").textContent, "Página 1 de 3");
    assert.equal(app.$("tx-prev").disabled, true);
    assert.equal(app.$("tx-next").disabled, false);
    assert.equal(ariaSort("date"), "descending");
    assert.equal(ariaSort("amount"), "none");
  });

  test("an empty result shows the message and hides the pager", async () => {
    await open({ total: 0 });

    assert.match(app.$("tx-table-body").textContent, /No hay transacciones que coincidan/);
    assert.equal(app.$("tx-pager").hidden, true);
  });
});

describe("sorting", () => {
  test("clicking a header sorts by it; clicking again flips the direction", async () => {
    await open();

    app.click(sortButton("description"));
    await app.settle();
    assert.equal(lastTransactionsRequest().get("sort"), "description");
    assert.equal(lastTransactionsRequest().get("dir"), "asc");
    assert.equal(descriptions()[0], "Compra 001");
    assert.equal(ariaSort("description"), "ascending");
    assert.equal(ariaSort("date"), "none");

    app.click(sortButton("description"));
    await app.settle();
    assert.equal(lastTransactionsRequest().get("dir"), "desc");
    assert.equal(descriptions()[0], "Compra 120");
    assert.equal(ariaSort("description"), "descending");
  });

  test("a newly chosen column starts A→Z for text and biggest-first for numbers and dates", async () => {
    await open();
    const firstDirOf = async (column) => {
      app.click(sortButton(column));
      await app.settle();
      return lastTransactionsRequest().get("dir");
    };

    assert.equal(await firstDirOf("category"), "asc");
    assert.equal(await firstDirOf("account"), "asc");
    assert.equal(await firstDirOf("amount"), "desc");
    assert.equal(await firstDirOf("accounting_month"), "desc");
    assert.equal(await firstDirOf("date"), "desc");
  });

  test("sorting goes back to the first page", async () => {
    await open();
    app.click(app.$("tx-next"));
    await app.settle();
    assert.equal(lastTransactionsRequest().get("page"), "1");

    app.click(sortButton("amount"));
    await app.settle();
    assert.equal(lastTransactionsRequest().get("page"), "0");
    assert.equal(app.$("tx-range").textContent, "1–50 de 120");
  });
});

describe("paging", () => {
  test("next and previous move one page and the buttons disable at the ends", async () => {
    await open({ total: 120 });

    app.click(app.$("tx-next"));
    await app.settle();
    assert.equal(lastTransactionsRequest().get("page"), "1");
    assert.equal(app.$("tx-range").textContent, "51–100 de 120");
    assert.equal(descriptions()[0], "Compra 070");

    app.click(app.$("tx-next"));
    await app.settle();
    assert.equal(app.$("tx-range").textContent, "101–120 de 120");
    assert.equal(app.$("tx-page-label").textContent, "Página 3 de 3");
    assert.equal(app.$("tx-next").disabled, true);
    assert.equal(app.$("tx-prev").disabled, false);

    app.click(app.$("tx-prev"));
    await app.settle();
    assert.equal(lastTransactionsRequest().get("page"), "1");
  });

  test("changing the page size refetches from the first page", async () => {
    await open({ total: 120 });
    app.click(app.$("tx-next"));
    await app.settle();

    app.change(app.$("tx-page-size"), "100");
    await app.settle();

    assert.equal(lastTransactionsRequest().get("size"), "100");
    assert.equal(lastTransactionsRequest().get("page"), "0");
    assert.equal(descriptions().length, 100);
    assert.equal(app.$("tx-page-label").textContent, "Página 1 de 2");
  });

  test("deleting the last row of the last page steps back to the new last page", async () => {
    await open({ total: 51 }); // page 2 holds a single row
    app.click(app.$("tx-next"));
    await app.settle();
    assert.equal(descriptions().length, 1);

    const before = app.callsTo("/api/transactions").length;
    app.click(app.document.querySelector("[data-del]"));
    await app.settle();

    const pages = app.callsTo("/api/transactions").slice(before).map((c) => c.params.get("page"));
    assert.deepEqual(pages, ["1", "0"], "asks for the now-empty page 1, then retreats to page 0");
    assert.equal(app.$("tx-range").textContent, "1–50 de 50");
    assert.equal(descriptions().length, 50);
  });
});

describe("filters", () => {
  test("a filter change goes back to the first page and applies the filter", async () => {
    await open({ total: 120 });
    app.click(app.$("tx-next"));
    await app.settle();

    app.change(app.$("tx-category"), "Ocio");
    await app.settle();

    assert.equal(lastTransactionsRequest().get("category"), "Ocio");
    assert.equal(lastTransactionsRequest().get("page"), "0");
    assert.equal(app.$("tx-range").textContent, "1–50 de 60");
  });

  test("the search box waits for a pause in typing before asking", async () => {
    await open();
    const before = app.callsTo("/api/transactions").length;

    for (const text of ["c", "co", "com"]) {
      app.$("tx-search").value = text;
      app.$("tx-search").dispatchEvent(new app.window.Event("input"));
      await wait(50);
    }
    assert.equal(app.callsTo("/api/transactions").length, before, "nothing sent while still typing");

    await wait(350);
    await app.settle();
    const requests = app.callsTo("/api/transactions").slice(before);
    assert.equal(requests.length, 1);
    assert.equal(requests[0].params.get("search"), "com");
  });
});

describe("period", () => {
  const MONTHS = ["2026-05", "2026-04", "2026-03", "2025-12"];
  const withMonths = { overrides: { "/api/summary/months-available": () => MONTHS } };
  const optionValues = (id) => [...app.$(id).options].map((o) => o.value);
  const now = () => app.value("currentMonth()");
  const plusMonths = (ym, n) => app.value(`addMonths(${JSON.stringify(ym)}, ${n})`);

  test("offers all months first, each month with data, the quick ranges, each year and a custom range", async () => {
    await open(withMonths);

    assert.deepEqual(optionValues("tx-month"),
      ["", "2026-05", "2026-04", "2026-03", "2025-12", "last-3", "last-6", "last-12", "year-2026", "year-2025", "custom"]);
    assert.equal(app.$("tx-month").value, "", "no limit by default");
    assert.equal(app.$("tx-month").options[1].textContent, "may 26", "months read as on the dashboard");
    assert.equal(app.$("tx-month-custom").hidden, true);
    assert.equal(lastTransactionsRequest().get("month"), null);
    assert.equal(lastTransactionsRequest().get("month_from"), null);
  });

  test("one month asks for that month", async () => {
    await open(withMonths);
    app.change(app.$("tx-month"), "2026-04");
    await app.settle();

    assert.equal(lastTransactionsRequest().get("month"), "2026-04");
    assert.equal(lastTransactionsRequest().get("month_from"), null);
  });

  test("the last 3 months end at the current month, as on the dashboard", async () => {
    await open(withMonths);
    app.change(app.$("tx-month"), "last-3");
    await app.settle();

    assert.equal(lastTransactionsRequest().get("month_from"), plusMonths(now(), -2));
    assert.equal(lastTransactionsRequest().get("month_to"), now());
    assert.equal(lastTransactionsRequest().get("month"), null);
  });

  test("a year asks for its months", async () => {
    await open(withMonths);
    app.change(app.$("tx-month"), "year-2025");
    await app.settle();

    assert.equal(lastTransactionsRequest().get("month_from"), "2025-01");
    assert.equal(lastTransactionsRequest().get("month_to"), "2025-12");
  });

  test("a custom range shows two month pickers, starts with every month and follows them", async () => {
    await open({ ...withMonths, total: 120 });
    app.click(app.$("tx-next"));
    await app.settle();

    app.change(app.$("tx-month"), "custom");
    await app.settle();
    assert.equal(app.$("tx-month-custom").hidden, false);
    assert.equal(lastTransactionsRequest().get("month_from"), "2025-12");
    assert.equal(lastTransactionsRequest().get("month_to"), "2026-05");
    assert.equal(lastTransactionsRequest().get("page"), "0");

    app.change(app.$("tx-month-to"), "2026-03");
    await app.settle();
    assert.equal(lastTransactionsRequest().get("month_to"), "2026-03");

    // picked the wrong way round: still the months between them
    app.change(app.$("tx-month-from"), "2026-04");
    await app.settle();
    assert.equal(lastTransactionsRequest().get("month_from"), "2026-03");
    assert.equal(lastTransactionsRequest().get("month_to"), "2026-04");

    app.change(app.$("tx-month"), "");
    await app.settle();
    assert.equal(app.$("tx-month-custom").hidden, true);
    assert.equal(lastTransactionsRequest().get("month_from"), null);
  });
});

describe("amount filter", () => {
  // makeTransactions: amounts -1 to -120 € ("Compra 001" to "Compra 120")
  const typeIn = async (id, text) => {
    app.$(id).value = text;
    app.$(id).dispatchEvent(new app.window.Event("input"));
    await wait(350);
    await app.settle();
  };

  test("'solo gastos de 100 € o más' sends the kind and the size of the amount, back to page 1", async () => {
    await open({ total: 120 });
    app.click(app.$("tx-next"));
    await app.settle();

    app.change(app.$("tx-kind"), "expense");
    await app.settle();
    await typeIn("tx-amount-min", "100");

    assert.equal(lastTransactionsRequest().get("kind"), "expense");
    assert.equal(lastTransactionsRequest().get("amount_min"), "100");
    assert.equal(lastTransactionsRequest().get("amount_max"), null);
    assert.equal(lastTransactionsRequest().get("page"), "0");
    assert.equal(app.$("tx-range").textContent, "1–21 de 21");
    assert.equal(app.$("tx-amount-msg").textContent, "");
  });

  test("only incomes: none among these purchases", async () => {
    await open();
    app.change(app.$("tx-kind"), "income");
    await app.settle();

    assert.equal(lastTransactionsRequest().get("kind"), "income");
    assert.match(app.$("tx-table-body").textContent, /No hay transacciones que coincidan/);
  });

  test("a range with a decimal comma finds exactly that amount", async () => {
    await open({ total: 120 });
    await typeIn("tx-amount-min", "45,00");
    await typeIn("tx-amount-max", "45");

    assert.equal(lastTransactionsRequest().get("amount_min"), "45");
    assert.equal(lastTransactionsRequest().get("amount_max"), "45");
    assert.deepEqual(descriptions(), ["Compra 045"]);
  });

  test("reads Spanish amounts: decimal comma, thousands dot, a euro sign", async () => {
    await open();
    // String(): NaN would turn into null on its way out of the page as JSON
    const parse = (text) => app.value(`String(parseAmountFilter(${JSON.stringify(text)}))`);
    assert.equal(parse(""), "null");
    assert.equal(parse("  "), "null");
    assert.equal(parse("45"), "45");
    assert.equal(parse("45,3"), "45.3");
    assert.equal(parse("45.30"), "45.3");
    assert.equal(parse("12 €"), "12");
    assert.equal(parse("1.000"), "1000");
    assert.equal(parse("1.000,50"), "1000.5");
    for (const bad of ["abc", "-5", "4,555", "1,000.50", "1.00.0"]) assert.equal(parse(bad), "NaN", bad);
  });

  test("something that isn't an amount is marked and left out; the other filters still apply", async () => {
    await open({ total: 120 });
    await typeIn("tx-amount-max", "10");
    await typeIn("tx-amount-min", "abc");

    assert.equal(lastTransactionsRequest().get("amount_min"), null);
    assert.equal(lastTransactionsRequest().get("amount_max"), "10");
    assert.equal(app.$("tx-amount-min").getAttribute("aria-invalid"), "true");
    assert.equal(app.$("tx-amount-max").hasAttribute("aria-invalid"), false);
    assert.match(app.$("tx-amount-msg").textContent, /«abc» no es un importe válido/);

    await typeIn("tx-amount-min", "");
    assert.equal(app.$("tx-amount-min").hasAttribute("aria-invalid"), false);
    assert.equal(app.$("tx-amount-msg").textContent, "");
  });

  test("'desde' above 'hasta' says so and applies neither", async () => {
    await open({ total: 120 });
    await typeIn("tx-amount-min", "100");
    await typeIn("tx-amount-max", "10");

    assert.equal(lastTransactionsRequest().get("amount_min"), null);
    assert.equal(lastTransactionsRequest().get("amount_max"), null);
    assert.match(app.$("tx-amount-msg").textContent, /mayor que «hasta»/);
    assert.equal(app.$("tx-range").textContent, "1–50 de 120");
  });

  test("a link from the dashboard starts from clean amount filters", async () => {
    await open({ total: 120 });
    app.change(app.$("tx-kind"), "income");
    await typeIn("tx-amount-min", "100");

    app.window.location.hash = "#transactions?category=Ocio";
    await wait(20);
    await app.settle();

    assert.equal(lastTransactionsRequest().get("category"), "Ocio");
    assert.equal(lastTransactionsRequest().get("kind"), null);
    assert.equal(lastTransactionsRequest().get("amount_min"), null);
    assert.equal(app.$("tx-kind").value, "");
    assert.equal(app.$("tx-amount-min").value, "");
  });
});

describe("selection", () => {
  const tickRow = (index) => app.click(rowBoxes()[index]);
  const selected = () => app.value("[...selectedIds].sort((a, b) => a - b)");

  test("ticking rows shows the bulk bar with the count", async () => {
    await open();
    assert.equal(app.$("bulk-bar").hidden, true);

    tickRow(0);
    assert.equal(app.$("bulk-count").textContent, "1 seleccionada");
    tickRow(1);
    assert.equal(app.$("bulk-count").textContent, "2 seleccionadas");
    assert.equal(app.$("bulk-bar").hidden, false);
  });

  test("survives paging, comes back ticked, and survives re-sorting", async () => {
    await open();
    tickRow(0);
    tickRow(1); // ids 120 and 119

    app.click(app.$("tx-next"));
    await app.settle();
    assert.equal(app.$("bulk-count").textContent, "2 seleccionadas", "still counted on another page");
    assert.equal(rowBoxes().filter((b) => b.checked).length, 0, "none of this page's rows are ticked");

    app.click(app.$("tx-prev"));
    await app.settle();
    assert.deepEqual(rowBoxes().slice(0, 3).map((b) => b.checked), [true, true, false]);

    app.click(sortButton("amount"));
    await app.settle();
    assert.deepEqual(selected(), [119, 120]);
  });

  test("a filter change drops it", async () => {
    await open();
    tickRow(0);

    app.change(app.$("tx-category"), "Salud");
    await app.settle();

    assert.deepEqual(selected(), []);
    assert.equal(app.$("bulk-bar").hidden, true);
  });

  test("select-all ticks only the visible page, and unticking it leaves the other pages alone", async () => {
    await open();
    tickRow(0); // id 120 on page 1
    app.click(app.$("tx-next"));
    await app.settle();

    app.click(app.$("tx-select-all"));
    assert.equal(rowBoxes().every((b) => b.checked), true);
    assert.equal(app.value("selectedIds.size"), 51);

    app.click(app.$("tx-select-all"));
    assert.deepEqual(selected(), [120]);
  });

  test("bulk delete sends the ticked ids from every page, then clears the selection", async () => {
    const backend = await open();
    tickRow(0);
    tickRow(1); // 120, 119
    app.click(app.$("tx-next"));
    await app.settle();
    tickRow(0); // 70

    app.click(app.$("bulk-delete"));
    await app.settle();

    const request = app.callsTo("/api/transactions/bulk-delete")[0];
    assert.deepEqual(request.json().ids.sort((a, b) => a - b), [70, 119, 120]);
    assert.deepEqual(selected(), []);
    assert.equal(app.$("bulk-bar").hidden, true);
    assert.equal(backend.state.transactions.length, 117);
  });

  test("bulk category sends the chosen category for the ticked ids", async () => {
    await open();
    tickRow(0);
    app.change(app.$("bulk-category"), "Salud");

    app.click(app.$("bulk-apply-category"));
    await app.settle();

    const body = app.callsTo("/api/transactions/bulk-category")[0].json();
    assert.deepEqual(body, { ids: [120], category: "Salud" });
  });

  test("deleting a single ticked row also unticks it", async () => {
    await open();
    tickRow(0); // id 120
    app.click(app.document.querySelector('[data-del="120"]'));
    await app.settle();

    assert.deepEqual(selected(), []);
    assert.equal(app.$("bulk-bar").hidden, true);
  });
});

describe("out-of-order responses", () => {
  test("a slow reply to an old request does not overwrite the newer one", async () => {
    const held = [];
    await open({
      api: (backend) => (call) => {
        const body = backend.handler(call);
        // once armed, transactions replies wait until the test releases them
        if (held.armed && call.path === "/api/transactions") return new Promise((resolve) => held.push(() => resolve(body)));
        return body;
      },
    });
    held.armed = true;

    app.click(sortButton("description")); // request A: description asc  -> "Compra 001" first
    app.click(sortButton("description")); // request B: description desc -> "Compra 120" first
    await tick();
    assert.equal(held.length, 2);

    held[1](); // B answers first
    await app.settle();
    assert.equal(descriptions()[0], "Compra 120");

    held[0](); // A answers late
    await app.settle();
    assert.equal(descriptions()[0], "Compra 120", "the stale answer was dropped");
  });
});

describe("data from the database is never treated as HTML", () => {
  test("descriptions and category names with markup and quotes render as plain text", async () => {
    const evil = { ...makeTransactions(1)[0], description: `<img src=x onerror="window.pwned=1"> a"b'c`, category: `Ocio "x" <b>` };
    await open({
      transactions: [evil],
      categories: [{ name: evil.category, kind: "expense" }],
    });

    assert.equal(app.$$("#tx-table-body img").length, 0, "no element was created from the description");
    assert.equal(app.$$("#tx-table-body .desc-cell")[0].textContent, evil.description);
    assert.equal(app.$$("#tx-table-body .desc-cell")[0].getAttribute("title"), evil.description);
    const option = app.$$(".cat-select option")[0];
    assert.equal(option.value, evil.category);
    assert.equal(option.textContent, evil.category);
    assert.equal(app.window.pwned, undefined);
  });
});
