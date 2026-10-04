// Import (#import), bank tab: the download through Enable Banking, and the banner before the consent expires.
// Loaded after app.js (shared helpers and navigation); see index.html for the order.

// ---------- Import: download from the bank (Enable Banking) ----------
const bankDateFrom = document.getElementById("bank-date-from");
const bankDateTo = document.getElementById("bank-date-to");
const bankAccountInput = document.getElementById("bank-account");
const bankImportBtn = document.getElementById("btn-bank-import");
const bankUpdateBtn = document.getElementById("btn-bank-update");
let bankSyncBusy = false;

// "Actualizar movimientos" downloads from the account's newest stored movement, minus this many days, to
// today. The overlap picks up movements that were still pending at the bank last time (they're only
// imported once booked, possibly a few days later); whatever was already stored is skipped as a duplicate.
const BANK_OVERLAP_DAYS = 7;
let bankAutoRange = null; // {from, to, newest} for the account in the input, or null while unknown
let bankRangeRun = 0;     // drops a stale lookup when the account changes mid-request
let bankRangeTimer = null;

// Actionable, so it stays on screen next to the button rather than in a toast (same reasoning as
// showImportError above): the user has to read it and either fix the range or re-link the account.
function showBankSyncError(message) {
  const el = document.getElementById("bank-sync-error");
  el.textContent = message;
  el.hidden = false;
}

function clearBankSyncError() {
  document.getElementById("bank-sync-error").hidden = true;
}

function autoRangeFor(newest, today) {
  const from = addDaysIso(newest, -BANK_OVERLAP_DAYS);
  return { from: from > today ? today : from, to: today, newest };
}

function updateBankSyncState() {
  const hasAccount = !!bankAccountInput.value.trim();
  const missing = [];
  if (!bankDateFrom.value) missing.push("la fecha de inicio");
  if (!bankDateTo.value) missing.push("la fecha de fin");
  if (!hasAccount) missing.push("el nombre de la cuenta");
  document.getElementById("bank-sync-hint").textContent =
    missing.length ? `Para importar falta indicar ${missing.join(", ")}.` : "";
  bankImportBtn.disabled = bankSyncBusy || missing.length > 0;
  bankUpdateBtn.disabled = bankSyncBusy || !hasAccount || !bankAutoRange;
}

async function refreshBankAutoRange() {
  clearTimeout(bankRangeTimer);
  const run = ++bankRangeRun;
  const account = bankAccountInput.value.trim();
  const text = document.getElementById("bank-auto-range");
  bankAutoRange = null;
  updateBankSyncState();
  if (!account) {
    text.textContent = "Indica la cuenta para ver desde qué fecha se descargará.";
    return;
  }
  text.textContent = "Buscando el último movimiento guardado…";
  let page;
  try {
    page = await api("/api/transactions?" + new URLSearchParams({ account, sort: "date", dir: "desc", size: "1" }));
  } catch (e) {
    if (run === bankRangeRun) text.textContent = "No se ha podido consultar el último movimiento guardado: " + e.message;
    return;
  }
  if (run !== bankRangeRun) return;
  const newest = page.items.length ? page.items[0].date : null;
  if (!newest) {
    text.textContent = `«${account}» no tiene movimientos todavía: elige desde qué fecha descargar en «Elegir fechas».`;
    document.getElementById("bank-custom-range").open = true;
  } else {
    bankAutoRange = autoRangeFor(newest, localIsoDate(new Date()));
    text.textContent = `Descargará del ${formatIsoDay(bankAutoRange.from)} hasta hoy. El último movimiento guardado ` +
      `en «${account}» es del ${formatIsoDay(newest)}; se repasan los ${BANK_OVERLAP_DAYS} días anteriores por si ` +
      "alguno seguía pendiente en el banco (los que ya tienes no se duplican).";
  }
  updateBankSyncState();
}

[bankDateFrom, bankDateTo].forEach((el) => el.addEventListener("change", updateBankSyncState));
bankAccountInput.addEventListener("input", (e) => {
  e.target.dataset.touched = "1";
  // Forget the old account's range at once, so the button can't use it while the new one is looked up.
  bankRangeRun++;
  bankAutoRange = null;
  updateBankSyncState();
  clearTimeout(bankRangeTimer);
  bankRangeTimer = setTimeout(refreshBankAutoRange, 300);
});

async function loadBankSyncStatus() {
  let status;
  try {
    status = await api("/api/bank-sync/status");
  } catch (e) {
    toast("Error: " + e.message);
    return;
  }
  showBankExpiry(status);
  document.getElementById("bank-sync-not-linked").hidden = status.linked;
  document.getElementById("bank-sync-linked").hidden = !status.linked;
  if (status.linked) {
    document.getElementById("bank-sync-aspsp").textContent = status.aspsp || "el banco";
    document.getElementById("bank-sync-valid-until").textContent =
      status.valid_until ? new Date(status.valid_until).toLocaleDateString("es-ES") : "—";
  }
  updateBankSyncState();
  if (status.linked) await refreshBankAutoRange();
}

// Linking for the first time and renewing are the same flow: the bank asks the user to log in again.
async function startBankLink(feedbackId) {
  clearFeedback(feedbackId);
  let data;
  try {
    data = await api("/api/bank-sync/link", { method: "POST" });
  } catch (e) {
    setFeedback(feedbackId, e.message);
    return;
  }
  // A real page navigation, not a fetch: it has to load BBVA's own login page.
  location.href = data.url;
}

document.getElementById("btn-bank-link").addEventListener("click", () => startBankLink("bank-sync-feedback"));
document.getElementById("btn-bank-renew").addEventListener("click", () => startBankLink("bank-renew-feedback"));

// ---------- Bank authorization about to expire ----------
// The bank's permission lasts ~180 days and renewing it means logging in at the bank again, so the app
// warns a month ahead, on every view, instead of letting a download fail one day with no warning.
const BANK_EXPIRY_WARN_DAYS = 30;
let bankExpiryNoticeShown = null;  // what the banner says, null = nothing to warn about
let openBankTabNext = false;       // the banner's button: open the Import view on the bank tab

// null when there's nothing to warn about; otherwise whether it already expired, the calendar days left
// (0 = today) and the date to show.
function bankExpiryNotice(status, now = new Date()) {
  if (!status || !status.valid_until || !(status.linked || status.expired)) return null;
  const until = new Date(status.valid_until);
  const date = until.toLocaleDateString("es-ES");
  if (status.expired || until <= now) return { expired: true, days: 0, date };
  const days = Math.round((Date.parse(localIsoDate(until)) - Date.parse(localIsoDate(now))) / 86400000);
  return days <= BANK_EXPIRY_WARN_DAYS ? { expired: false, days, date } : null;
}

function bankExpiryText(notice) {
  if (notice.expired) return `La autorización para descargar movimientos del banco caducó el ${notice.date}.`;
  const when = notice.days === 0 ? "hoy" : notice.days === 1 ? "mañana" : `dentro de ${notice.days} días`;
  return `La autorización para descargar movimientos del banco caduca ${when} (el ${notice.date}).`;
}

// The bank only sends the user back to the address registered at Enable Banking, 127.0.0.1 on the PC.
function bankRenewWhere() {
  return ["127.0.0.1", "localhost"].includes(location.hostname)
    ? ""
    : " Renuévala desde el PC, en https://127.0.0.1:8000: el banco solo acepta volver a esa dirección.";
}

function showBankExpiry(status) {
  const notice = bankExpiryNotice(status);
  bankExpiryNoticeShown = notice;
  if (notice) document.getElementById("bank-expiry-text").textContent = bankExpiryText(notice) + bankRenewWhere();
  showBankExpiryBanner();

  // the same warning inside the bank tab, next to the button that fixes it
  const renew = document.getElementById("bank-renew");
  renew.hidden = !(notice && status.linked);
  if (!renew.hidden) document.getElementById("bank-renew-text").textContent = bankExpiryText(notice) + bankRenewWhere();
  const expired = document.getElementById("bank-sync-expired");
  expired.hidden = !(notice && notice.expired && !status.linked);
  if (!expired.hidden) expired.textContent = bankExpiryText(notice) + " Vuelve a vincular la cuenta para seguir descargando." + bankRenewWhere();
}

// Not on the Import view: there the bank tab says it itself.
function showBankExpiryBanner() {
  document.getElementById("bank-expiry-banner").hidden = !bankExpiryNoticeShown || viewFromHash() === "import";
}

document.getElementById("btn-bank-expiry-renew").addEventListener("click", () => {
  openBankTabNext = true;
  location.hash = "import";
});

// At start-up, for the banner. Silent on failure like the price badge: the bank tab says it when opened.
async function refreshBankExpiry() {
  try {
    showBankExpiry(await api("/api/bank-sync/status"));
  } catch (e) { /* the bank tab says it when opened */ }
}

async function runBankImport(dateFrom, dateTo, button) {
  if (bankSyncBusy) return;
  clearBankSyncError();
  bankSyncBusy = true;
  const label = button.textContent;
  button.textContent = "Importando…";
  updateBankSyncState();
  let result;
  try {
    result = await api("/api/bank-sync/import", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ date_from: dateFrom, date_to: dateTo, account: bankAccountInput.value.trim() }),
    });
  } catch (e) {
    showBankSyncError("No se ha podido importar: " + e.message);
    return;
  } finally {
    bankSyncBusy = false;
    button.textContent = label;
    updateBankSyncState();
  }
  showImportResult(result);
}

bankUpdateBtn.addEventListener("click", () => {
  if (bankUpdateBtn.disabled || !bankAutoRange) return;
  runBankImport(bankAutoRange.from, bankAutoRange.to, bankUpdateBtn);
});

bankImportBtn.addEventListener("click", () => {
  if (bankImportBtn.disabled) return;
  runBankImport(bankDateFrom.value, bankDateTo.value, bankImportBtn);
});

// Revokes the consent at the bank too, not just here, so re-linking means going through BBVA again.
document.getElementById("btn-bank-unlink").addEventListener("click", async (e) => {
  const btn = e.currentTarget;
  if (btn.disabled) return;
  if (!confirm("¿Desvincular la cuenta del banco?\n\nSe revoca el permiso en BBVA y los movimientos ya importados se " +
               "conservan. Para volver a descargar tendrás que vincularla de nuevo.")) return;
  clearBankSyncError();
  btn.disabled = true;
  try {
    await api("/api/bank-sync/link", { method: "DELETE" });
  } catch (err) {
    showBankSyncError("No se ha podido desvincular: " + err.message);
    return;
  } finally {
    btn.disabled = false;
  }
  toast("Cuenta bancaria desvinculada.");
  await loadBankSyncStatus();
});
