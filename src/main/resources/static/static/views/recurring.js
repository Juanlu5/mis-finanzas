// Fixed expenses and subscriptions (#recurring): what the server finds repeating in the movements
// (GET /api/recurring, RecurringService). A group can be left out of the totals ("No es un gasto fijo").
// Loaded after app.js (shared helpers and navigation); see index.html for the order.

// ---------- Fixed expenses ----------
let recurringRun = 0;
let recurringTodayOverride = null; // tests pin "today"

const RECURRING_CADENCES = { monthly: "al mes", bimonthly: "cada 2 meses", quarterly: "cada 3 meses", yearly: "al año" };

function recurringToday() {
  return recurringTodayOverride || localIsoDate(new Date());
}

// "12 oct", with the year when it isn't this one: "5 mar 2027"
function recurringDayLabel(iso, today) {
  const options = { day: "numeric", month: "short" };
  if (iso.slice(0, 4) !== today.slice(0, 4)) options.year = "numeric";
  return new Date(`${iso}T00:00:00`).toLocaleDateString("es-ES", options);
}

// "abril", with the year when it isn't this one: "diciembre de 2025"
function recurringMonthLabel(iso, today) {
  const options = { month: "long" };
  if (iso.slice(0, 4) !== today.slice(0, 4)) options.year = "numeric";
  return new Date(`${iso}T00:00:00`).toLocaleDateString("es-ES", options);
}

function recurringPriceHtml(item) {
  const amount = item.fixed ? fmtMoney(item.amount) : `≈ ${fmtMoney(item.amount)}`;
  const perMonth = item.cadence === "monthly" ? "" : ` <span class="muted">(${escapeHtml(fmtMoney(item.monthly))} al mes)</span>`;
  return `<span class="recurring-amount">${escapeHtml(amount)}</span> <span class="muted">${RECURRING_CADENCES[item.cadence]}</span>${perMonth}`;
}

// When it comes next (or came last), how much it moves, and whether its price changed.
function recurringNotesHtml(item, today) {
  const notes = [];
  if (!item.active) notes.push(`Último cobro el ${escapeHtml(recurringDayLabel(item.last_date, today))}`);
  else if (item.next_date < today) notes.push(`Se esperaba hacia el ${escapeHtml(recurringDayLabel(item.next_date, today))}`);
  else notes.push(`Próximo hacia el ${escapeHtml(recurringDayLabel(item.next_date, today))}`);
  if (!item.fixed) notes.push(`Entre ${escapeHtml(fmtMoney(item.min))} y ${escapeHtml(fmtMoney(item.max))}`);
  const change = item.price_change;
  if (change) {
    const up = change.to > change.from;
    notes.push(`<span class="recurring-change ${up ? "up" : "down"}"><span aria-hidden="true">${up ? "▲" : "▼"}</span> ` +
      `${up ? "Subió" : "Bajó"} de ${escapeHtml(fmtMoney(change.from))} a ${escapeHtml(fmtMoney(change.to))} ` +
      `en ${escapeHtml(recurringMonthLabel(change.date, today))}</span>`);
  }
  if (item.grouped_by === "amount") notes.push("Mismo importe en esta categoría, aunque la descripción cambie");
  return notes.join(" · ");
}

function recurringChargesHtml(item, today) {
  const rows = item.charges.map((c) =>
    `<li><span class="recurring-charge-date">${escapeHtml(recurringDayLabel(c.date, today))}</span>` +
    `<span class="recurring-charge-desc">${escapeHtml(c.description)}</span>` +
    `<span class="recurring-charge-amount">${escapeHtml(fmtMoney(c.amount))}</span></li>`).join("");
  return `<details class="recurring-charges"><summary>Ver los cobros (${item.charges.length})</summary><ul>${rows}</ul></details>`;
}

function recurringItemHtml(item, today) {
  const category = item.grouped_by === "payee" ? ` <span class="muted">· ${escapeHtml(item.category)}</span>` : "";
  const action = item.hidden
    ? `<button type="button" class="link-btn" data-unhide="${escapeAttr(item.key)}">Volver a contarlo</button>`
    : `<button type="button" class="link-btn" data-hide="${escapeAttr(item.key)}">No es un gasto fijo</button>`;
  return `<li class="recurring-item${item.active ? "" : " ended"}">` +
    `<div class="recurring-head"><span class="recurring-name">${escapeHtml(item.name)}</span>${category}</div>` +
    `<div class="recurring-price">${recurringPriceHtml(item)}</div>` +
    `<div class="recurring-meta">${recurringNotesHtml(item, today)}</div>` +
    `<div class="recurring-actions">${recurringChargesHtml(item, today)}${action}</div></li>`;
}

function renderRecurring(report) {
  const today = recurringToday();
  const shown = report.items.filter((i) => !i.hidden);
  const active = shown.filter((i) => i.active);
  const ended = shown.filter((i) => !i.active);
  const hidden = report.items.filter((i) => i.hidden);

  document.getElementById("recurring-empty").hidden = report.items.length > 0;
  document.getElementById("recurring-content").hidden = report.items.length === 0;

  document.getElementById("recurring-monthly").textContent = fmtMoney(report.monthly_total);
  document.getElementById("recurring-yearly").textContent = fmtMoney(report.yearly_total);
  document.getElementById("recurring-count").textContent =
    active.length === 1 ? "1 pago que se repite" : `${fmtInt(active.length)} pagos que se repiten`;
  const income = report.income_monthly;
  document.getElementById("recurring-share-card").hidden = !(income > 0);
  if (income > 0) {
    document.getElementById("recurring-share").textContent = fmtPercent(report.monthly_total / income);
    document.getElementById("recurring-share-note").textContent =
      `De ${fmtMoney(income)} al mes que entraron de media en los 3 últimos meses`;
  }

  document.getElementById("recurring-active").innerHTML = active.map((i) => recurringItemHtml(i, today)).join("") ||
    '<li class="muted">Ningún pago fijo activo ahora mismo.</li>';
  document.getElementById("recurring-ended-panel").hidden = ended.length === 0;
  document.getElementById("recurring-ended").innerHTML = ended.map((i) => recurringItemHtml(i, today)).join("");
  document.getElementById("recurring-hidden-panel").hidden = hidden.length === 0;
  document.getElementById("recurring-hidden-count").textContent = fmtInt(hidden.length);
  document.getElementById("recurring-hidden").innerHTML = hidden.map((i) => recurringItemHtml(i, today)).join("");
}

async function loadRecurringView() {
  const run = ++recurringRun;
  const report = await api("/api/recurring");
  if (run !== recurringRun) return;
  renderRecurring(report);
}

document.getElementById("view-recurring").addEventListener("click", async (e) => {
  const button = e.target.closest("[data-hide], [data-unhide]");
  if (!button) return;
  button.disabled = true;
  if (button.dataset.hide !== undefined) {
    await api("/api/recurring/hidden", { method: "POST", body: new URLSearchParams({ key: button.dataset.hide }) });
    toast("Ya no cuenta en los gastos fijos");
  } else {
    await api(`/api/recurring/hidden?key=${encodeURIComponent(button.dataset.unhide)}`, { method: "DELETE" });
    toast("Vuelve a contar en los gastos fijos");
  }
  await loadRecurringView();
});
