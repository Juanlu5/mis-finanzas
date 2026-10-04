const { describe, test, afterEach } = require("node:test");
const assert = require("node:assert/strict");
const { loadApp, fakeBackend } = require("./helpers");

let app;
afterEach(() => app && app.close());

// the text of every piece, with a space between pieces in separate elements
function text(el) {
  const parts = [];
  const walker = el.ownerDocument.createTreeWalker(el, 4 /* NodeFilter.SHOW_TEXT */);
  while (walker.nextNode()) parts.push(walker.currentNode.textContent);
  return parts.join(" ").replace(/\s+/g, " ").replace(/ ([,)])/g, "$1").trim();
}
const day = (date, name, kind, source = "boe", id = null) => ({ date, name, kind, source, id });

// 2026 as the BOE and the Generalitat publish it for Barcelona, and Extremadura with a local day typed in
const WORK = {
  role: "work", region: "CT", region_name: "Cataluña", town_code: "08019", town_name: "Barcelona",
  days: [
    day("2026-09-24", "Fiesta local", "local", "generalitat"),
    day("2026-10-12", "Fiesta Nacional de España", "national"),
    day("2026-12-08", "Inmaculada Concepción", "national"),
    day("2026-12-25", "Natividad del Señor", "national"),
    day("2026-12-26", "San Esteban", "region"),
  ],
};
const HOME = {
  role: "home", region: "EX", region_name: "Extremadura", town_code: null, town_name: "Mérida",
  days: [
    day("2026-10-12", "Fiesta Nacional de España", "national"),
    day("2026-10-14", "Fiesta del pueblo", "local", "manual", 7),
    day("2026-11-02", "Día siguiente a Todos los Santos", "region"),
    day("2026-12-07", "Lunes siguiente al Día de la Constitución Española", "region"),
    day("2026-12-08", "Inmaculada Concepción", "national"),
    day("2026-12-25", "Natividad del Señor", "national"),
  ],
};
const REGIONS = [{ code: "CT", name: "Cataluña" }, { code: "EX", name: "Extremadura" }, { code: "MD", name: "Madrid" }];

function holidays(overrides = {}) {
  return {
    work: WORK, home: HOME, regions: REGIONS, last_success_at: "2026-09-26T10:00:00Z", last_error: null,
    years: [{ year: 2026, published: true, locals_published: true }, { year: 2027, published: false, locals_published: false }],
    ...overrides,
  };
}

async function open({ data = holidays(), today = "2026-09-26", overrides = {} } = {}) {
  const backend = fakeBackend({
    overrides: {
      "/api/holidays": () => data,
      "/api/holidays/towns": () => [{ code: "08019", name: "Barcelona" }, { code: "08101", name: "L'Hospitalet de Llobregat" }],
      ...overrides,
    },
  });
  app = await loadApp({ hash: "", api: backend.handler });
  app.window.eval(`holidayTodayOverride = ${JSON.stringify(today)}`);
  app.window.location.hash = "holidays";
  await app.settle();
}

describe("long weekends", () => {
  const bridge = (iso, days) => app.value(`bridgeFor(${JSON.stringify(iso)}, new Set(${JSON.stringify(days)}))`);

  test("a Tuesday or a Thursday: one day off gives four", async () => {
    await open();
    assert.deepEqual(bridge("2026-12-08", ["2026-12-08"]).bridge,
      { take: ["2026-12-07"], run: { from: "2026-12-05", to: "2026-12-08", days: 4 } });
    assert.deepEqual(bridge("2026-10-01", ["2026-10-01"]).bridge.take, ["2026-10-02"]);
  });

  test("a Wednesday: two days off give five", async () => {
    await open();
    const b = bridge("2026-06-24", ["2026-06-24"]);
    assert.equal(b.bridge.take.length, 2);
    assert.equal(b.bridge.run.days, 5);
  });

  test("a Monday or a Friday is already a long weekend; a Saturday gives nothing", async () => {
    await open();
    const monday = bridge("2026-10-12", ["2026-10-12"]);
    assert.deepEqual(monday.base, { from: "2026-10-10", to: "2026-10-12", days: 3 });
    assert.equal(monday.bridge, null);
    assert.equal(bridge("2026-12-26", ["2026-12-26"]).weekend, true);
  });

  test("holidays next to each other count together", async () => {
    await open();
    // Extremadura: Monday 7 and Tuesday 8 of December
    const b = bridge("2026-12-08", ["2026-12-07", "2026-12-08"]);
    assert.deepEqual(b.base, { from: "2026-12-05", to: "2026-12-08", days: 4 });
    assert.equal(b.bridge, null);
  });
});

describe("the holidays view", () => {
  test("has its own menu entry", async () => {
    await open();
    assert.equal(app.$("view-holidays").classList.contains("active"), true);
    assert.ok(app.document.querySelector('.nav-btn[data-view="holidays"]'));
  });

  test("the next holiday, how far it is and what it gives", async () => {
    await open();
    const next = app.$("holiday-next");
    assert.equal(text(next.querySelector(".economy-value")), "Fiesta Nacional de España");
    assert.equal(text(next.querySelector(".economy-sub")), "lunes 12 de octubre · dentro de 16 días");
    assert.equal(text(next.querySelector(".economy-change")), "3 días libres seguidos (sáb, 10 oct – lun, 12 oct)");
  });

  test("lists the long weekends of the coming months, with the day to take", async () => {
    await open();
    const rows = app.$$("#holiday-bridges li").map(text);
    assert.equal(rows.length, 4); // 24 September is past
    assert.equal(rows[1], "mar, 8 dic · Inmaculada Concepción Coge el lunes 7 de diciembre → 4 días libres (sáb, 5 dic – mar, 8 dic)");
    assert.match(rows[2], /^vie, 25 dic · Natividad del Señor 3 días libres sin coger nada/);
    assert.match(rows[3], /Cae en fin de semana$/);
  });

  test("home holidays that are working days here, and what taking one gives", async () => {
    await open();
    assert.equal(text(app.$("holiday-home-name")), "Mérida");
    const rows = app.$$("#holiday-home-only li").map(text);
    assert.deepEqual(rows, [
      "mié, 14 oct · Fiesta del pueblo Si lo coges: un día suelto",
      "lun, 2 nov · Día siguiente a Todos los Santos Si lo coges: 3 días allí (sáb, 31 oct – lun, 2 nov)",
      "lun, 7 dic · Lunes siguiente al Día de la Constitución Española Si lo coges: 4 días allí (sáb, 5 dic – mar, 8 dic)",
    ]);
  });

  test("the calendar marks which place each holiday is for; a local one gets its town's name", async () => {
    await open();
    const rows = app.$$("#holiday-calendar li");
    const mercè = rows.find((li) => li.textContent.includes("24 sept"));
    assert.match(text(mercè), /Fiesta local de Barcelona Barcelona$/);
    assert.ok(mercè.classList.contains("past"));
    const oct12 = rows.find((li) => li.textContent.includes("12 oct"));
    assert.deepEqual([...oct12.querySelectorAll(".holiday-chip")].map((c) => [c.textContent, c.classList.contains("home")]),
      [["Barcelona", false], ["Mérida", true]]);
  });

  test("says what isn't published yet", async () => {
    await open();
    assert.match(text(app.$("holiday-notes")), /Los festivos de 2027 aún no se han publicado en el BOE/);
  });

  test("without a place yet, only the settings show", async () => {
    await open({ data: holidays({ work: null, home: null }) });
    assert.equal(app.$("holidays-content").hidden, true);
    assert.equal(app.$("holiday-settings").open, true);
    assert.equal(app.$("hol-work-region").value, "CT");
  });
});

describe("the settings", () => {
  test("saving sends the Catalan town's code, picked from the Generalitat's list", async () => {
    await open({ overrides: { "/api/holidays/places/work": () => holidays(), "/api/holidays/places/home": () => holidays() } });
    app.$("hol-work-town").value = "l'hospitalet de llobregat";
    app.$("hol-home-region").value = "EX";
    app.$("hol-home-town").value = "Mérida";
    app.$("holiday-places-form").dispatchEvent(new app.window.Event("submit", { cancelable: true }));
    await app.settle();

    const puts = app.calls.filter((c) => c.method === "PUT");
    assert.deepEqual(puts.map((c) => c.path), ["/api/holidays/places/work", "/api/holidays/places/home"]);
    assert.deepEqual(puts[0].form(), { region: "CT", town_name: "L'Hospitalet de Llobregat", town_code: "08101" });
    assert.deepEqual(puts[1].form(), { region: "EX", town_name: "Mérida" });
  });

  test("a Catalan town that isn't on the list is refused before saving", async () => {
    await open();
    app.$("hol-work-town").value = "Barcelonaa";
    app.$("holiday-places-form").dispatchEvent(new app.window.Event("submit", { cancelable: true }));
    await app.settle();

    assert.equal(app.calls.filter((c) => c.method === "PUT").length, 0);
    assert.match(text(app.$("holiday-places-feedback")), /No encuentro «Barcelonaa»/);
  });

  test("a local holiday typed in is sent for the chosen calendar, and can be removed", async () => {
    await open({ overrides: { "/api/holidays/days": () => ({ id: 8 }), "/api/holidays/days/7": () => ({ deleted: true }) } });
    app.$("hol-day-role").value = "home";
    app.$("hol-day-date").value = "2026-05-15";
    app.$("hol-day-name").value = "San Isidro";
    app.$("holiday-day-form").dispatchEvent(new app.window.Event("submit", { cancelable: true }));
    await app.settle();
    assert.deepEqual(app.callsTo("/api/holidays/days").at(-1).form(), { role: "home", date: "2026-05-15", name: "San Isidro" });

    // the one already typed in (id 7) has a remove button
    app.click(app.document.querySelector('.holiday-manual-delete[data-id="7"]'));
    await app.settle();
    assert.equal(app.callsTo("/api/holidays/days/7").at(-1).method, "DELETE");
  });
});
