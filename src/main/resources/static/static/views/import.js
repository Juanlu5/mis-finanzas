// Import (#import), file tab. The bank tab is bank.js.
// Loaded after app.js (shared helpers and navigation); see index.html for the order.

// ---------- Import ----------
const IMPORT_EXTENSIONS = [".csv", ".xlsx", ".xls"];
const IMPORT_MAX_BYTES = 25 * 1024 * 1024; // spring.servlet.multipart.max-file-size

// Each mapping field: its <select>, and the label its column gets in the preview header.
const IMPORT_FIELDS = [
  { key: "date", select: "map-date", role: "Fecha" },
  { key: "description", select: "map-description", role: "Descripción" },
  { key: "description_extra", select: "map-description-extra", role: "Detalle" },
  { key: "amount", select: "map-amount", role: "Importe" },
  { key: "debit", select: "map-debit", role: "Cargo" },
  { key: "credit", select: "map-credit", role: "Abono" },
  { key: "balance", select: "map-balance", role: "Saldo" },
];

let importFile = null;
let importColumns = [];
let importBusy = false;

const dropzone = document.getElementById("dropzone");
const fileInput = document.getElementById("file-input");
const confirmImportBtn = document.getElementById("btn-confirm-import");

function showImportStep(step) {
  // The result screen replaces the whole flow, whichever tab (file upload or bank download) produced it.
  document.getElementById("import-tabs-wrap").hidden = step === "result";
  for (const name of ["upload", "preview"]) {
    document.getElementById(`import-step-${name}`).hidden = name !== step;
  }
  document.getElementById("import-step-result").hidden = step !== "result";
}

// Errors stay on screen (a toast vanishes in 2 s, too little to read what went wrong).
function showImportError(message) {
  const el = document.getElementById("import-error");
  el.textContent = message;
  el.hidden = false;
}

function clearImportError() {
  document.getElementById("import-error").hidden = true;
}

function setImportBusy(busy) {
  importBusy = busy;
  dropzone.disabled = busy;
  dropzone.classList.toggle("busy", busy);
  dropzone.setAttribute("aria-busy", String(busy));
  confirmImportBtn.textContent = busy ? "Importando…" : "Importar transacciones";
  updateImportState();
}

// The bank-sync callback (a full page redirect, not a fetch) lands here as "#import?bank=linked/error";
// report it once, then strip the query so reloading doesn't show the same toast again.
function handleBankSyncReturn() {
  const query = location.hash.split("?")[1];
  if (!query) return null;
  const bank = new URLSearchParams(query).get("bank");
  if (bank === "linked") toast("Cuenta bancaria vinculada correctamente.");
  else if (bank === "error") toast("No se ha podido vincular la cuenta bancaria.");
  if (bank) history.replaceState(null, "", location.pathname + "#import");
  return bank;
}

async function loadImportView() {
  const accounts = await api("/api/accounts");
  document.getElementById("import-accounts").innerHTML = accounts.map((a) => optionHtml(a)).join("");
  // Pre-fill only when there's no doubt which account is meant: with several, guessing wrong would
  // silently mix two accounts' histories (or create a new one for a typo), so the user has to choose.
  const input = document.getElementById("import-account");
  if (!input.dataset.touched) input.value = accounts.length === 0 ? "Principal" : accounts.length === 1 ? accounts[0] : "";
  const bankAccountInput = document.getElementById("bank-account");
  if (!bankAccountInput.dataset.touched) bankAccountInput.value = input.value;
  // coming back after a finished import: start clean rather than show last time's result
  if (!document.getElementById("import-step-result").hidden) resetImport();
  updateImportState();

  // the bank's redirect, or the expiry banner's button, lands on the bank tab
  const toBankTab = handleBankSyncReturn() || openBankTabNext;
  openBankTabNext = false;
  if (toBankTab) selectImportTab("bank");
  // back on the bank tab (e.g. after an import): what "since last time" means may have changed
  else if (!document.getElementById("panel-import-bank").hidden) loadBankSyncStatus();
}

document.getElementById("import-account").addEventListener("input", (e) => {
  e.target.dataset.touched = "1";
  updateImportState();
});

dropzone.addEventListener("click", () => fileInput.click());
dropzone.addEventListener("dragover", (e) => { e.preventDefault(); dropzone.classList.add("drag"); });
dropzone.addEventListener("dragleave", () => dropzone.classList.remove("drag"));
dropzone.addEventListener("drop", (e) => {
  e.preventDefault();
  dropzone.classList.remove("drag");
  const files = e.dataTransfer.files;
  if (files.length > 1) { showImportError("Sube un solo archivo cada vez."); return; }
  if (files.length) handleFile(files[0]);
});
fileInput.addEventListener("change", () => {
  if (fileInput.files.length) handleFile(fileInput.files[0]);
});

// A file dropped outside the zone would make the browser navigate to it and leave the app.
for (const type of ["dragover", "drop"]) {
  window.addEventListener(type, (e) => {
    if (e.dataTransfer && [...e.dataTransfer.types].includes("Files")) e.preventDefault();
  });
}

async function handleFile(file) {
  clearImportError();
  if (!IMPORT_EXTENSIONS.some((ext) => file.name.toLowerCase().endsWith(ext))) {
    showImportError(`«${file.name}» no es un CSV ni un Excel (.csv, .xlsx, .xls).`);
    fileInput.value = "";
    return;
  }
  if (file.size > IMPORT_MAX_BYTES) {
    showImportError(`«${file.name}» pesa más de 25 MB, que es el máximo.`);
    fileInput.value = "";
    return;
  }

  const fd = new FormData();
  fd.append("file", file);
  let preview;
  setImportBusy(true);
  try {
    preview = await api("/api/import/preview", { method: "POST", body: fd });
  } catch (e) {
    showImportError("No se ha podido leer el archivo: " + e.message);
    fileInput.value = "";
    return;
  } finally {
    setImportBusy(false);
  }

  importFile = file;
  importColumns = preview.columns;
  document.getElementById("import-summary-text").textContent =
    `${file.name} — ${preview.rows} filas detectadas. Revisa que las columnas estén bien asignadas.`;
  document.getElementById("preview-count").textContent =
    preview.sample.length < preview.rows ? `(primeras ${preview.sample.length} de ${preview.rows} filas)` : "";

  for (const { key, select } of IMPORT_FIELDS) {
    document.getElementById(select).innerHTML = '<option value="">(ninguna)</option>' +
      importColumns.map((c) => optionHtml(c, c, c === preview.mapping[key])).join("");
  }

  // <th>/<td> carry their column index; updateImportState() marks which ones are mapped
  const table = document.getElementById("preview-table");
  table.querySelector("thead").innerHTML = "<tr>" + importColumns.map((c, i) =>
    `<th data-col="${i}">${escapeHtml(c)}<div class="col-role"><span class="pill"></span></div></th>`).join("") + "</tr>";
  table.querySelector("tbody").innerHTML = preview.sample.map((row) =>
    "<tr>" + importColumns.map((c, i) => `<td data-col="${i}">${escapeHtml(String(row[c] ?? ""))}</td>`).join("") + "</tr>"
  ).join("");

  showImportStep("preview");
  updateImportState();
}

function currentImportMapping() {
  const mapping = {};
  for (const { key, select } of IMPORT_FIELDS) mapping[key] = document.getElementById(select).value || null;
  return mapping;
}

// Keeps the form consistent with what the backend does and tells the user what's still missing:
// one amount column XOR debit/credit (the backend lets `amount` win, so offering both would
// silently ignore one), the required fields, and which preview column plays which role.
function updateImportState() {
  const amount = document.getElementById("map-amount");
  const debit = document.getElementById("map-debit");
  const credit = document.getElementById("map-credit");
  if (amount.value) { debit.value = ""; credit.value = ""; } // the amount wins, like in the backend
  debit.disabled = credit.disabled = Boolean(amount.value);
  amount.disabled = Boolean(debit.value || credit.value);

  const mapping = currentImportMapping();
  document.querySelectorAll("#preview-table th[data-col]").forEach((th) => {
    const index = Number(th.dataset.col);
    const roles = IMPORT_FIELDS.filter((f) => mapping[f.key] === importColumns[index]).map((f) => f.role);
    th.querySelector(".pill").textContent = roles.join(" · ");
    document.querySelectorAll(`#preview-table [data-col="${index}"]`)
      .forEach((cell) => cell.classList.toggle("unused", roles.length === 0));
  });

  const missing = [];
  if (!mapping.date) missing.push("la columna de fecha");
  if (!mapping.description) missing.push("la columna de descripción");
  if (!mapping.amount && !mapping.debit && !mapping.credit) missing.push("la columna de importe (o las de cargos y abonos)");
  if (!document.getElementById("import-account").value.trim()) missing.push("el nombre de la cuenta");
  document.getElementById("import-hint").textContent = missing.length ? `Para importar falta indicar ${missing.join(", ")}.` : "";
  confirmImportBtn.disabled = importBusy || missing.length > 0;
}

for (const { select } of IMPORT_FIELDS) document.getElementById(select).addEventListener("change", updateImportState);

document.getElementById("btn-cancel-import").addEventListener("click", resetImport);
document.getElementById("btn-import-another").addEventListener("click", resetImport);
document.getElementById("btn-view-transactions").addEventListener("click", () => { location.hash = "transactions"; });

function resetImport() {
  importFile = null;
  importColumns = [];
  fileInput.value = "";
  clearImportError();
  showImportStep("upload");
}

function showImportResult(result) {
  document.getElementById("result-added").textContent = result.added;
  document.getElementById("result-duplicates").textContent = result.duplicates;
  document.getElementById("result-errors").textContent = result.skipped;

  const notes = [];
  if (result.added === 0 && result.duplicates > 0 && result.duplicates === result.total_rows) {
    notes.push("Todo este archivo ya estaba importado, así que no se ha añadido nada.");
  }
  if (result.balances_filled) {
    notes.push(`Se ha rellenado el saldo en ${result.balances_filled} transacciones que ya existían.`);
  }
  if (result.skipped) {
    notes.push(`${result.skipped} filas no se han importado por no tener una fecha, un importe o una descripción legibles (por ejemplo, una fila de totales al final).`);
  }
  if (result.pending) {
    notes.push(result.pending === 1
      ? "1 movimiento todavía está pendiente en el banco y no se ha importado; se importará en una descarga posterior, cuando el banco lo contabilice."
      : `${result.pending} movimientos todavía están pendientes en el banco y no se han importado; se importarán en una descarga posterior, cuando el banco los contabilice.`);
  }
  const list = document.getElementById("result-notes");
  list.replaceChildren(...notes.map((text) => Object.assign(document.createElement("li"), { textContent: text })));
  showImportStep("result");
}

confirmImportBtn.addEventListener("click", async () => {
  if (importBusy || confirmImportBtn.disabled) return;
  const fd = new FormData();
  fd.append("file", importFile);
  fd.append("account", document.getElementById("import-account").value.trim());
  fd.append("mapping", JSON.stringify(currentImportMapping()));

  clearImportError();
  let result;
  setImportBusy(true);
  try {
    result = await api("/api/import/confirm", { method: "POST", body: fd });
  } catch (e) {
    showImportError("No se ha podido importar: " + e.message);
    return;
  } finally {
    setImportBusy(false);
  }

  // Nothing readable at all means the column assignment is wrong, not a bad file: stay on the form.
  if (result.added === 0 && result.duplicates === 0 && result.skipped > 0) {
    showImportError(`No se ha podido leer ninguna de las ${result.skipped} filas con esta asignación. ` +
      "Comprueba que las columnas de fecha, descripción e importe son las correctas.");
    return;
  }
  showImportResult(result);
});

// The file and the bank download are two tabs of the same view.
const IMPORT_TABS = ["file", "bank"];

function selectImportTab(name) {
  for (const tab of IMPORT_TABS) {
    const selected = tab === name;
    const button = document.getElementById(`tab-import-${tab}`);
    button.setAttribute("aria-selected", String(selected));
    button.tabIndex = selected ? 0 : -1;
    document.getElementById(`panel-import-${tab}`).hidden = !selected;
  }
  if (name === "bank") loadBankSyncStatus();
}

document.querySelectorAll("#view-import .tabs .tab").forEach((tab) => {
  tab.addEventListener("click", () => selectImportTab(tab.dataset.tab));
});
