const { describe, test, afterEach } = require("node:test");
const assert = require("node:assert/strict");
const { loadApp, fakeBackend, tick } = require("./helpers");

let app;
afterEach(() => app && app.close());

const PREVIEW = {
  columns: ["Fecha", "Fecha valor", "Concepto", "Importe", "Disponible"],
  mapping: { date: "Fecha", description: "Concepto", description_extra: null, amount: "Importe", debit: null, credit: null, balance: "Disponible" },
  sample: [
    { Fecha: "01/08/2026", "Fecha valor": "01/08/2026", Concepto: "NOMINA EMPRESA", Importe: "1850,0", Disponible: "3350,0" },
    { Fecha: "02/08/2026", "Fecha valor": "02/08/2026", Concepto: "AMAZON 233", Importe: "-37,67", Disponible: "3312,33" },
  ],
  rows: 44,
};
const RESULT = { added: 44, duplicates: 0, skipped: 0, total_rows: 44, balances_filled: 0 };

async function open({ preview = PREVIEW, result = RESULT, accounts = ["Principal"], overrides = {} } = {}) {
  const backend = fakeBackend({
    overrides: {
      "/api/accounts": () => accounts,
      "/api/import/preview": () => preview,
      "/api/import/confirm": () => result,
      ...overrides,
    },
  });
  app = await loadApp({ hash: "import", api: backend.handler });
}

const step = () => ["upload", "preview", "result"].find((name) => !app.$(`import-step-${name}`).hidden);
const errorText = () => (app.$("import-error").hidden ? null : app.$("import-error").textContent);
const roleOf = (column) => app.document.querySelector(`th[data-col="${PREVIEW.columns.indexOf(column)}"] .pill`).textContent;
const cellsOf = (column) => app.$$(`#preview-table [data-col="${PREVIEW.columns.indexOf(column)}"]`);
const ok = (name = "extracto.csv") => new app.window.File(["Fecha;Concepto\n"], name);

function chooseFile(file) {
  Object.defineProperty(app.$("file-input"), "files", { value: [file], configurable: true });
  app.$("file-input").dispatchEvent(new app.window.Event("change"));
}

function dropFiles(files) {
  const event = new app.window.Event("drop", { bubbles: true, cancelable: true });
  event.dataTransfer = { files, types: ["Files"] };
  app.$("dropzone").dispatchEvent(event);
  return event;
}

async function toPreview(options) {
  await open(options);
  chooseFile(ok());
  await app.settle();
}

describe("the account", () => {
  test("is pre-filled when there is no doubt: none yet -> 'Principal', exactly one -> that one", async () => {
    await open({ accounts: [] });
    assert.equal(app.$("import-account").value, "Principal");
    app.close();

    await open({ accounts: ["BBVA"] });
    assert.equal(app.$("import-account").value, "BBVA");
  });

  test("with several accounts it stays empty, so a wrong one is never picked silently", async () => {
    await open({ accounts: ["BBVA", "Ahorro"] });

    assert.equal(app.$("import-account").value, "");
    assert.deepEqual(app.$$("#import-accounts option").map((o) => o.value), ["BBVA", "Ahorro"]);
  });

  test("what the user typed is not overwritten when coming back to the view", async () => {
    await open({ accounts: ["BBVA"] });
    app.$("import-account").value = "Nueva";
    app.$("import-account").dispatchEvent(new app.window.Event("input"));

    app.window.location.hash = "dashboard";
    await app.settle();
    app.window.location.hash = "import";
    await app.settle();

    assert.equal(app.$("import-account").value, "Nueva");
  });
});

describe("choosing the file", () => {
  test("uploads it for preview and moves on to the column assignment", async () => {
    await open();
    assert.equal(step(), "upload");

    chooseFile(ok("extracto agosto.csv"));
    await app.settle();

    const request = app.callsTo("/api/import/preview")[0];
    assert.equal(request.body.get("file").name, "extracto agosto.csv");
    assert.equal(step(), "preview");
    assert.match(app.$("import-summary-text").textContent, /extracto agosto\.csv — 44 filas/);
  });

  test("accepts .csv, .xlsx and .xls in any letter case", async () => {
    await open();
    for (const name of ["a.CSV", "b.Xlsx", "c.xls"]) {
      const before = app.callsTo("/api/import/preview").length;
      chooseFile(ok(name));
      await app.settle();
      assert.equal(app.callsTo("/api/import/preview").length, before + 1, name);
      app.click(app.$("btn-cancel-import"));
    }
  });

  test("rejects other file types on the spot, with a message, without asking the server", async () => {
    await open();

    chooseFile(ok("notas.txt"));
    await app.settle();

    assert.match(errorText(), /«notas\.txt» no es un CSV ni un Excel/);
    assert.equal(app.callsTo("/api/import/preview").length, 0);
    assert.equal(step(), "upload");
  });

  test("rejects files over the 25 MB the server accepts", async () => {
    await open();
    const big = ok("enorme.csv");
    Object.defineProperty(big, "size", { value: 26 * 1024 * 1024 });

    chooseFile(big);
    await app.settle();

    assert.match(errorText(), /pesa más de 25 MB/);
    assert.equal(app.callsTo("/api/import/preview").length, 0);
  });

  test("a file the server can't read shows its reason and stays on the upload step", async () => {
    await open({ overrides: { "/api/import/preview": () => ({ status: 400, body: { detail: "formato no reconocido" } }) } });

    chooseFile(ok());
    await app.settle();

    assert.equal(errorText(), "No se ha podido leer el archivo: formato no reconocido");
    assert.equal(step(), "upload");
  });

  test("a file name is shown as text, never parsed as HTML", async () => {
    await open();

    chooseFile(ok(`<img src=x onerror="window.pwned=1">.txt`));
    await app.settle();

    assert.equal(app.$$("#import-error img").length, 0);
    assert.equal(app.window.pwned, undefined);
    assert.match(errorText(), /<img src=x/);
  });

  test("shows a busy state while the file is being read, then clears it", async () => {
    const held = [];
    await open({ overrides: { "/api/import/preview": () => new Promise((resolve) => held.push(() => resolve(PREVIEW))) } });

    chooseFile(ok());
    await tick();
    assert.equal(app.$("dropzone").disabled, true);
    assert.equal(app.$("dropzone").getAttribute("aria-busy"), "true");
    assert.equal(app.$("dropzone").classList.contains("busy"), true);

    held[0]();
    await app.settle();
    assert.equal(app.$("dropzone").disabled, false);
    assert.equal(app.$("dropzone").getAttribute("aria-busy"), "false");
  });

  test("the drop zone is a real button, so the keyboard can reach it", async () => {
    await open();

    assert.equal(app.$("dropzone").tagName, "BUTTON");
  });
});

describe("dragging files onto the page", () => {
  test("dropping one file on the zone previews it", async () => {
    await open();

    dropFiles([ok("arrastrado.csv")]);
    await app.settle();

    assert.equal(app.callsTo("/api/import/preview")[0].body.get("file").name, "arrastrado.csv");
  });

  test("dropping several asks for one at a time", async () => {
    await open();

    dropFiles([ok("a.csv"), ok("b.csv")]);
    await app.settle();

    assert.equal(errorText(), "Sube un solo archivo cada vez.");
    assert.equal(app.callsTo("/api/import/preview").length, 0);
  });

  test("a file dropped outside the zone doesn't make the browser navigate away", async () => {
    await open();
    const drag = (types) => {
      const event = new app.window.Event("dragover", { bubbles: true, cancelable: true });
      event.dataTransfer = { types };
      app.document.body.dispatchEvent(event);
      return event.defaultPrevented;
    };

    assert.equal(drag(["Files"]), true);
    assert.equal(drag(["text/plain"]), false, "dragging text or links is left alone");
  });
});

describe("assigning columns", () => {
  test("offers the file's columns and selects the ones the server guessed", async () => {
    await toPreview();

    assert.deepEqual(app.$$("#map-date option").map((o) => o.value), ["", ...PREVIEW.columns]);
    assert.equal(app.$("map-date").value, "Fecha");
    assert.equal(app.$("map-description").value, "Concepto");
    assert.equal(app.$("map-amount").value, "Importe");
    assert.equal(app.$("map-balance").value, "Disponible");
    assert.equal(app.$("map-description-extra").value, "");
  });

  test("says when the preview is only part of the file", async () => {
    await toPreview();
    assert.equal(app.$("preview-count").textContent, "(primeras 2 de 44 filas)");
    app.close();

    await toPreview({ preview: { ...PREVIEW, rows: 2 } });
    assert.equal(app.$("preview-count").textContent, "");
  });

  test("labels each preview column with its role and dims the ones that aren't used", async () => {
    await toPreview();

    assert.equal(roleOf("Fecha"), "Fecha");
    assert.equal(roleOf("Concepto"), "Descripción");
    assert.equal(roleOf("Importe"), "Importe");
    assert.equal(roleOf("Disponible"), "Saldo");
    assert.equal(roleOf("Fecha valor"), "");
    assert.equal(cellsOf("Fecha valor").every((c) => c.classList.contains("unused")), true);
    assert.equal(cellsOf("Concepto").some((c) => c.classList.contains("unused")), false);
  });

  test("the labels follow the selects as they change", async () => {
    await toPreview();

    app.change(app.$("map-date"), "Fecha valor");

    assert.equal(roleOf("Fecha valor"), "Fecha");
    assert.equal(roleOf("Fecha"), "");
    assert.equal(cellsOf("Fecha").every((c) => c.classList.contains("unused")), true);
  });

  test("a column with two roles shows both", async () => {
    await toPreview();

    app.change(app.$("map-description-extra"), "Concepto");

    assert.equal(roleOf("Concepto"), "Descripción · Detalle");
  });

  test("column names and cell values from the file are escaped", async () => {
    const evil = { ...PREVIEW, columns: ["<b>Fecha</b>", "Concepto", "Importe"],
      mapping: { ...PREVIEW.mapping, date: "<b>Fecha</b>", balance: null },
      sample: [{ "<b>Fecha</b>": "<img src=x onerror=\"window.pwned=1\">", Concepto: "a\"b", Importe: "1" }] };
    await toPreview({ preview: evil });

    assert.equal(app.$$("#preview-table b, #preview-table img").length, 0);
    assert.equal(app.$$("#preview-table th")[0].firstChild.textContent, "<b>Fecha</b>");
    assert.equal(app.window.pwned, undefined);
  });
});

describe("amount: one column, or debit and credit", () => {
  test("with an amount column chosen, debit and credit are locked", async () => {
    await toPreview();

    assert.equal(app.$("map-debit").disabled, true);
    assert.equal(app.$("map-credit").disabled, true);
    assert.equal(app.$("map-amount").disabled, false);
  });

  test("choosing a debit column clears and locks the amount, and undoing it unlocks it", async () => {
    await toPreview();

    app.change(app.$("map-amount"), "");
    app.change(app.$("map-debit"), "Importe");
    assert.equal(app.$("map-amount").value, "");
    assert.equal(app.$("map-amount").disabled, true);
    assert.equal(app.$("map-credit").disabled, false, "debit and credit go together");

    app.change(app.$("map-debit"), "");
    assert.equal(app.$("map-amount").disabled, false);
  });

  test("if the server guessed both, the amount wins, as it does in the backend", async () => {
    await toPreview({ preview: { ...PREVIEW, mapping: { ...PREVIEW.mapping, debit: "Fecha valor", credit: "Disponible" } } });

    assert.equal(app.$("map-debit").value, "");
    assert.equal(app.$("map-credit").value, "");
    assert.equal(app.value("currentImportMapping()").amount, "Importe");
  });
});

describe("what is still missing", () => {
  test("nothing missing: no hint and the button is enabled", async () => {
    await toPreview();

    assert.equal(app.$("import-hint").textContent, "");
    assert.equal(app.$("btn-confirm-import").disabled, false);
  });

  test("a required field left empty disables the button and says which", async () => {
    await toPreview();

    app.change(app.$("map-date"), "");

    assert.equal(app.$("import-hint").textContent, "Para importar falta indicar la columna de fecha.");
    assert.equal(app.$("btn-confirm-import").disabled, true);
  });

  test("lists everything that's missing, including the account", async () => {
    await toPreview();

    app.change(app.$("map-description"), "");
    app.change(app.$("map-amount"), "");
    app.$("import-account").value = "  ";
    app.$("import-account").dispatchEvent(new app.window.Event("input"));

    assert.equal(app.$("import-hint").textContent,
      "Para importar falta indicar la columna de descripción, la columna de importe (o las de cargos y abonos), el nombre de la cuenta.");
  });

  test("debit alone or credit alone is enough for the amount", async () => {
    await toPreview();

    app.change(app.$("map-amount"), "");
    app.change(app.$("map-credit"), "Importe");

    assert.equal(app.$("btn-confirm-import").disabled, false);
  });

  test("clicking a disabled button sends nothing", async () => {
    await toPreview();
    app.change(app.$("map-date"), "");

    app.click(app.$("btn-confirm-import"));
    await app.settle();

    assert.equal(app.callsTo("/api/import/confirm").length, 0);
  });
});

describe("importing", () => {
  const send = async () => { app.click(app.$("btn-confirm-import")); await app.settle(); };

  test("sends the file, the trimmed account and the mapping (empty selects as null)", async () => {
    await toPreview();
    app.$("import-account").value = "  BBVA  ";
    app.$("import-account").dispatchEvent(new app.window.Event("input"));

    await send();

    const body = app.callsTo("/api/import/confirm")[0].body;
    assert.equal(body.get("file").name, "extracto.csv");
    assert.equal(body.get("account"), "BBVA");
    assert.deepEqual(JSON.parse(body.get("mapping")), {
      date: "Fecha", description: "Concepto", description_extra: null, amount: "Importe", debit: null, credit: null, balance: "Disponible",
    });
  });

  test("shows the counts, and no notes when everything went cleanly", async () => {
    await toPreview();
    await send();

    assert.equal(step(), "result");
    assert.equal(app.$("result-added").textContent, "44");
    assert.equal(app.$("result-duplicates").textContent, "0");
    assert.equal(app.$("result-errors").textContent, "0");
    assert.equal(app.$$("#result-notes li").length, 0);
  });

  test("explains rows with errors and back-filled balances", async () => {
    await toPreview({ result: { added: 40, duplicates: 2, skipped: 2, total_rows: 44, balances_filled: 2 } });
    await send();

    const notes = app.$$("#result-notes li").map((li) => li.textContent);
    assert.equal(notes.length, 2);
    assert.match(notes[0], /saldo en 2 transacciones que ya existían/);
    assert.match(notes[1], /2 filas no se han importado.*fecha, un importe o una descripción/);
  });

  test("re-importing a file already imported says so", async () => {
    await toPreview({ result: { added: 0, duplicates: 44, skipped: 0, total_rows: 44, balances_filled: 0 } });
    await send();

    assert.equal(step(), "result");
    assert.match(app.$("result-notes").textContent, /Todo este archivo ya estaba importado/);
  });

  test("when no row at all can be read, the assignment is wrong: stay on the form and say so", async () => {
    await toPreview({ result: { added: 0, duplicates: 0, skipped: 44, total_rows: 44, balances_filled: 0 } });
    await send();

    assert.equal(step(), "preview");
    assert.match(errorText(), /ninguna de las 44 filas/);
    assert.match(errorText(), /fecha, descripción e importe/);
  });

  test("a server error is shown, the form stays and the button works again", async () => {
    await toPreview({ overrides: { "/api/import/confirm": () => ({ status: 500, body: { detail: "disco lleno" } }) } });
    await send();

    assert.equal(errorText(), "No se ha podido importar: disco lleno");
    assert.equal(step(), "preview");
    assert.equal(app.$("btn-confirm-import").disabled, false);
    assert.equal(app.$("btn-confirm-import").textContent, "Importar transacciones");
  });

  test("while it runs the button is locked, so a double click can't import twice", async () => {
    const held = [];
    await toPreview({ overrides: { "/api/import/confirm": () => new Promise((resolve) => held.push(() => resolve(RESULT))) } });

    app.click(app.$("btn-confirm-import"));
    await tick();
    assert.equal(app.$("btn-confirm-import").disabled, true);
    assert.equal(app.$("btn-confirm-import").textContent, "Importando…");
    app.click(app.$("btn-confirm-import"));
    await tick();
    assert.equal(app.callsTo("/api/import/confirm").length, 1);

    held[0]();
    await app.settle();
    assert.equal(step(), "result");
  });

  test("a previous error is cleared when trying again", async () => {
    await toPreview({ result: { added: 0, duplicates: 0, skipped: 44, total_rows: 44, balances_filled: 0 } });
    await send();
    assert.notEqual(errorText(), null);

    app.click(app.$("btn-cancel-import"));
    assert.equal(errorText(), null);
  });
});

describe("after importing", () => {
  test("'Ver transacciones' opens the transactions view", async () => {
    await toPreview();
    app.click(app.$("btn-confirm-import"));
    await app.settle();

    app.click(app.$("btn-view-transactions"));
    await app.settle();

    assert.equal(app.window.location.hash, "#transactions");
    assert.equal(app.$("view-transactions").classList.contains("active"), true);
  });

  test("'Importar otro archivo' and 'Cancelar' go back to the upload step and forget the file", async () => {
    await toPreview();
    app.click(app.$("btn-confirm-import"));
    await app.settle();
    app.click(app.$("btn-import-another"));
    assert.equal(step(), "upload");
    assert.equal(app.value("importFile"), null);

    chooseFile(ok());
    await app.settle();
    assert.equal(step(), "preview");
    app.click(app.$("btn-cancel-import"));
    assert.equal(step(), "upload");
  });

  test("coming back to the view later starts clean instead of showing last time's result", async () => {
    await toPreview();
    app.click(app.$("btn-confirm-import"));
    await app.settle();

    app.window.location.hash = "dashboard";
    await app.settle();
    app.window.location.hash = "import";
    await app.settle();

    assert.equal(step(), "upload");
  });

  test("leaving in the middle of an import and coming back keeps the preview", async () => {
    await toPreview();

    app.window.location.hash = "dashboard";
    await app.settle();
    app.window.location.hash = "import";
    await app.settle();

    assert.equal(step(), "preview");
  });
});
