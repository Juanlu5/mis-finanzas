const { describe, test, before, after } = require("node:test");
const assert = require("node:assert/strict");
const { loadApp, fakeBackend } = require("./helpers");

let app;
before(async () => { app = await loadApp({ hash: "import", api: fakeBackend().handler }); });
after(() => app.close());

// Helpers are called through the page so the code under test is the real app.js.
const call = (expression) => app.value(expression);

describe("escaping", () => {
  test("escapeHtml neutralises tags and ampersands but leaves quotes alone", () => {
    assert.equal(call(`escapeHtml('<b>&"x"</b>')`), '&lt;b&gt;&amp;"x"&lt;/b&gt;');
  });

  test("escapeAttr also escapes both kinds of quote, so it is safe inside an attribute", () => {
    assert.equal(call(`escapeAttr(\`a"b'c<\`)`), "a&quot;b&#39;c&lt;");
  });

  test("optionHtml escapes value and label separately and marks the selected one", () => {
    assert.equal(
      call(`optionHtml('a"b', '<i>', true)`),
      '<option value="a&quot;b" selected>&lt;i&gt;</option>');
    assert.equal(call(`optionHtml('x')`), '<option value="x">x</option>');
  });
});

describe("formatting", () => {
  test("fmtMoney uses Spanish euro format", () => {
    assert.equal(call(`fmtMoney(1234.5)`).replace(/\s/g, " "), "1234,50 €");
    assert.equal(call(`fmtMoney(-12000)`).replace(/\s/g, " "), "-12.000,00 €");
  });

  test("fmtMoneyCompact abbreviates axis ticks", () => {
    assert.match(call(`fmtMoneyCompact(2500)`), /2,5\s*mil\s*€/);
  });

  test("fmtMonthLabel turns YYYY-MM into a short Spanish label", () => {
    assert.equal(call(`fmtMonthLabel("2026-01")`), "ene 26");
    assert.equal(call(`fmtMonthLabel("2025-12")`), "dic 25");
  });

  test("withAlpha converts #rrggbb to rgba", () => {
    assert.equal(call(`withAlpha("#6c5ce7", 0.14)`), "rgba(108, 92, 231, 0.14)");
  });
});

describe("groupTopCategories", () => {
  const rows = (n) => Array.from({ length: n }, (_, i) => ({ category: `C${i + 1}`, amount: 100 - i }));
  const group = (n) => call(`groupTopCategories(${JSON.stringify(rows(n))})`);

  test("leaves 9 or fewer untouched (a lone leftover is not worth a slice of its own)", () => {
    assert.deepEqual(group(3), rows(3));
    assert.deepEqual(group(9), rows(9));
  });

  test("folds everything after the 8th into 'Otras (n)'", () => {
    const grouped = group(10);
    assert.equal(grouped.length, 9);
    assert.deepEqual(grouped.slice(0, 8), rows(8));
    assert.deepEqual(grouped[8], { category: "Otras (2)", amount: 92 + 91, isOthers: true });
  });
});

describe("viewFromHash", () => {
  test("accepts only known views", () => {
    assert.equal(call(`viewFromHash()`), "import"); // the page was opened on #import
    app.window.location.hash = "categories";
    assert.equal(call(`viewFromHash()`), "categories");
    app.window.location.hash = "../etc/passwd";
    assert.equal(call(`viewFromHash()`), "dashboard");
  });
});

describe("previousMonth", () => {
  test("goes back one month, across the year boundary too", () => {
    assert.equal(call(`previousMonth("2026-03")`), "2026-02");
    assert.equal(call(`previousMonth("2026-12")`), "2026-11");
    assert.equal(call(`previousMonth("2026-01")`), "2025-12");
    assert.equal(call(`previousMonth("2026-10")`), "2026-09", "keeps the leading zero");
  });
});

describe("describeChange", () => {
  const change = (fields) =>
    call(`describeChange(${JSON.stringify({ monthLabel: "feb 26", goodWhenUp: true, withPercent: true, ...fields })})`);

  test("an increase and a decrease read as más / menos than the previous month", () => {
    assert.match(change({ current: 150, previous: 100 }).text, /^50,00\s€ más que feb 26 \(50\s%\)$/);
    assert.match(change({ current: 50, previous: 100 }).text, /^50,00\s€ menos que feb 26 \(50\s%\)$/);
  });

  test("the tone follows whether a rise is good news", () => {
    assert.equal(change({ current: 2, previous: 1, goodWhenUp: true }).tone, "good");
    assert.equal(change({ current: 2, previous: 1, goodWhenUp: false }).tone, "bad");
    assert.equal(change({ current: 1, previous: 2, goodWhenUp: false }).tone, "good");
  });

  test("the percentage uses the size of a negative base, not its sign", () => {
    // from -100 to -50 is an improvement of 50 over a base of 100
    assert.match(change({ current: -50, previous: -100 }).text, /más que feb 26 \(50\s%\)$/);
  });

  test("withPercent: false and a zero base both leave the percentage out", () => {
    assert.equal(change({ current: 150, previous: 100, withPercent: false }).text.includes("%"), false);
    assert.equal(change({ current: 150, previous: 0 }).text.includes("%"), false);
  });
});

describe("chart period", () => {
  const range = (choice, extra = {}) =>
    call(`periodRange(${JSON.stringify(choice)}, ${JSON.stringify({ months: ["2026-09", "2026-03", "2025-11"], now: "2026-09", custom: {}, ...extra })})`);

  test("last N months end at the current month, across a year boundary", () => {
    assert.deepEqual(range("last-12"), { from: "2025-10", to: "2026-09" });
    assert.deepEqual(range("last-3", { now: "2026-02" }), { from: "2025-12", to: "2026-02" });
  });

  test("the current year stops at the current month; a past one is whole", () => {
    assert.deepEqual(range("year-2026"), { from: "2026-01", to: "2026-09" });
    assert.deepEqual(range("year-2025"), { from: "2025-01", to: "2025-12" });
  });

  test("all history runs from the oldest month with data to now", () => {
    assert.deepEqual(range("all"), { from: "2025-11", to: "2026-09" });
    assert.deepEqual(range("all", { months: [] }), { from: "2026-09", to: "2026-09" });
  });

  test("a single month is a range of one", () => {
    assert.deepEqual(range("2026-03"), { from: "2026-03", to: "2026-03" });
  });

  const previous = (choice, r, today) =>
    call(`previousPeriod(${JSON.stringify(choice)}, ${JSON.stringify(r)}, ${JSON.stringify(today)})`);

  test("a finished period is compared with the whole period before it", () => {
    const today = "2026-09-24";
    assert.deepEqual(previous("2026-01", { from: "2026-01", to: "2026-01" }, today),
      { range: { from: "2025-12", to: "2025-12" }, until: null, label: "dic 25", noData: "Sin datos en dic 25" });
    assert.deepEqual(previous("last-6", { from: "2026-02", to: "2026-07" }, today).range, { from: "2025-08", to: "2026-01" });
    assert.equal(previous("year-2025", { from: "2025-01", to: "2025-12" }, today).label, "2024");
    assert.deepEqual(previous("custom", { from: "2026-02", to: "2026-03" }, today),
      { range: { from: "2025-12", to: "2026-01" }, until: null, label: "el periodo anterior", noData: "Sin datos en el periodo anterior" });
    assert.equal(previous("", { from: "2025-01", to: "2026-09" }, today), null);
  });

  test("a period still in progress is compared only up to the same day", () => {
    const today = "2026-09-24";
    const month = previous("2026-09", { from: "2026-09", to: "2026-09" }, today);
    assert.deepEqual(month.range, { from: "2026-08", to: "2026-08" });
    assert.equal(month.until, "2026-08-24");
    assert.equal(month.label, "ago 26 a estas alturas");
    assert.equal(month.noData, "Sin datos en ago 26 hasta el día 24");
    assert.match(month.note, /aún no ha terminado/);

    const year = previous("year-2026", { from: "2026-01", to: "2026-09" }, today);
    assert.deepEqual(year.range, { from: "2025-01", to: "2025-09" });
    assert.equal(year.until, "2025-09-24");
    assert.equal(year.label, "2025 a estas alturas");

    const last3 = previous("last-3", { from: "2026-07", to: "2026-09" }, today);
    assert.deepEqual(last3.range, { from: "2026-04", to: "2026-06" });
    assert.equal(last3.until, "2026-06-24");
    assert.equal(last3.label, "los 3 meses anteriores a estas alturas");
  });

  test("the same day in a shorter month is its last day", () => {
    assert.equal(previous("2026-03", { from: "2026-03", to: "2026-03" }, "2026-03-31").until, "2026-02-28");
    assert.equal(previous("2024-03", { from: "2024-03", to: "2024-03" }, "2024-03-30").until, "2024-02-29");
  });

  test("a custom range given backwards is turned round", () => {
    assert.deepEqual(range("custom", { custom: { from: "2026-03", to: "2025-11" } }), { from: "2025-11", to: "2026-03" });
  });

  test("months between two months counts both ends", () => {
    assert.equal(call(`monthsBetween("2025-11", "2026-02")`), 4);
    assert.equal(call(`monthsBetween("2026-02", "2026-02")`), 1);
  });

  test("the balance axis goes by week, month or quarter with the length of the period", () => {
    assert.equal(call("balanceAxisUnit(3)"), "week");
    assert.equal(call("balanceAxisUnit(12)"), "month");
    assert.equal(call("balanceAxisUnit(60)"), "quarter");
  });

  test("quarters add up their months and round to cents", () => {
    // 25 months from January 2024: one more than fits as monthly bars
    const monthly = Array.from({ length: 25 }, (_, i) => ({
      month: `${2024 + Math.floor(i / 12)}-${String((i % 12) + 1).padStart(2, "0")}`,
      income: i < 2 ? [0.1, 0.2][i] : 0,
      expense: 1,
    }));
    const grouped = call(`groupMonthly(${JSON.stringify(monthly)})`);

    assert.equal(grouped.grouping, "por trimestres");
    assert.equal(grouped.rows.length, 9);
    assert.deepEqual(grouped.rows[0], { key: "T1 2024", label: "T1 2024", income: 0.3, expense: 3 });
    assert.deepEqual(grouped.rows.at(-1), { key: "T1 2026", label: "T1 2026", income: 0, expense: 1 });
  });
});

describe("keyword suggested from a description", () => {
  const cases = [
    ["Lidl centro — Pago con tarjeta", "lidl centro"],
    ["Www.amazon*q7x2k5m1z — Pago con tarjeta", "www.amazon"],
    ["Airbnb * kd83jt2wp5 — Pago con tarjeta", "airbnb"],
    ["Dropbox* plus sub — Pago con tarjeta", "dropbox"],
    ["Iryob2c — Pago con tarjeta", "iryob2c"],                           // one digit is part of the name
    ["Bizum — COMPRA: TEMU", "temu"],
    ["Bizum — ENVIADO: Sin concepto", ""],
    ["Ingreso por nómina o pensión — Construcciones lopez sl", "construcciones lopez sl"],
    ["Transferencia realizada — OBA1234567890ABCD 0123abcd4567ef89", "transferencia realizada"],
    ["Ret. efectivo a debito con tarj. en cajero. aut. — 01820002 999", "ret. efectivo a debito con tarj. en cajero. aut"],
    ["Ingreso en efectivo", "ingreso en efectivo"],
    ["COMPRA MERCADONA VALENCIA 4521", "compra mercadona valencia"],   // an Excel-style description
  ];
  for (const [description, expected] of cases) {
    test(`${description} → «${expected}»`, () => {
      assert.equal(call(`suggestKeyword(${JSON.stringify(description)})`), expected);
    });
  }
});
