// Transactions (#transactions): filters, the table, bulk actions and the rule suggested after a correction.
// Loaded after app.js (shared helpers and navigation); see index.html for the order.

// ---------- Transactions ----------
async function populateTransactionFilters() {
  const months = await api("/api/summary/months-available");
  txMonths = months;
  document.getElementById("tx-month").innerHTML =
    periodOptionsHtml(months, { allLabel: "Todos los meses", allFirst: true });
  fillCustomRange("tx-month-from", "tx-month-to", months);
  showTxCustomRange();

  await refreshCategories();
  const catSel = document.getElementById("tx-category");
  catSel.innerHTML = '<option value="">Todas las categorías</option>' +
    allCategories.map((c) => optionHtml(c.name)).join("");

  const bulkCatSel = document.getElementById("bulk-category");
  bulkCatSel.innerHTML = '<option value="">Elegir categoría...</option>' +
    allCategories.map((c) => optionHtml(c.name)).join("");

  const accounts = await api("/api/accounts");
  const accSel = document.getElementById("tx-account");
  accSel.innerHTML = '<option value="">Todas las cuentas</option>' +
    accounts.map((a) => optionHtml(a)).join("");
}

function categoryOptionsHtml(selected) {
  return allCategories.map((c) => optionHtml(c.name, c.name, c.name === selected)).join("");
}

const txState = { page: 0, size: 50, sort: "date", dir: "desc" };
// text columns start A→Z; date/month/amount start with the biggest/newest first
const TEXT_SORTS = ["description", "account", "category"];
// Ids ticked by the user. They survive paging and re-sorting (the same rows just move), but
// not a filter change, which swaps the whole result set.
let selectedIds = new Set();

// Period of Transactions: the same choices as the dashboard's (see periodRange), for accounting months.
let txMonths = []; // newest first, as /months-available returns them

function showTxCustomRange() {
  document.getElementById("tx-month-custom").hidden = document.getElementById("tx-month").value !== "custom";
}

// The request parameters for the chosen period: none for all months, `month` for one, a range otherwise.
function txPeriodParams() {
  const choice = document.getElementById("tx-month").value;
  if (choice === "") return {};
  if (isSingleMonth(choice)) return { month: choice };
  const range = periodRange(choice, {
    months: txMonths,
    now: currentMonth(),
    custom: {
      from: document.getElementById("tx-month-from").value,
      to: document.getElementById("tx-month-to").value,
    },
  });
  return { month_from: range.from, month_to: range.to };
}

// "#transactions?category=Ocio&period=last-3" (see openCategoryTransactions; a custom range adds &from=&to=)
// sets the filters once, then the hash is put back to plain "#transactions" so a reload doesn't bring them back.
function applyTransactionsLink() {
  const query = location.hash.split("?")[1];
  if (!query) return;
  history.replaceState(null, "", location.pathname + "#transactions");
  const link = new URLSearchParams(query);
  // the link says exactly what to show: leftovers from the last visit would silently narrow it
  for (const id of ["tx-search", "tx-kind", "tx-amount-min", "tx-amount-max"]) document.getElementById(id).value = "";
  document.getElementById("tx-category").value = link.get("category") || "";
  const period = link.get("period");
  const monthSelect = document.getElementById("tx-month");
  if (period === "custom") {
    const from = link.get("from");
    const to = link.get("to");
    if (!txMonths.includes(from) || !txMonths.includes(to)) return;
    document.getElementById("tx-month-from").value = from;
    document.getElementById("tx-month-to").value = to;
  } else if (!period || ![...monthSelect.options].some((o) => o.value === period)) {
    return;
  }
  monthSelect.value = period;
  showTxCustomRange();
}

async function loadTransactions() {
  selectedIds.clear();
  txState.page = 0;
  await populateTransactionFilters();
  applyTransactionsLink();
  await renderTransactions();
}

function restartTransactionList() {
  selectedIds.clear();
  txState.page = 0;
  renderTransactions();
}

document.getElementById("tx-month").addEventListener("change", showTxCustomRange);
["tx-month", "tx-month-from", "tx-month-to", "tx-category", "tx-account", "tx-kind"].forEach((id) =>
  document.getElementById(id).addEventListener("change", restartTransactionList)
);
let searchTimer;
for (const id of ["tx-search", "tx-amount-min", "tx-amount-max"]) {
  document.getElementById(id).addEventListener("input", () => {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(restartTransactionList, 300);
  });
}

// An amount typed in the filter, in euros and without sign: "45", "45,30", "45.3", "1.000", "1.000,50", "12 €".
// null when empty, NaN when it isn't an amount. A dot followed by three digits is a thousands separator.
function parseAmountFilter(text) {
  const t = text.replace(/[\s€]/g, "");
  if (!t) return null;
  if (/^\d+([.,]\d{1,2})?$/.test(t)) return Number(t.replace(",", "."));
  if (/^\d{1,3}(\.\d{3})+(,\d{1,2})?$/.test(t)) return Number(t.replace(/\./g, "").replace(",", "."));
  return NaN;
}

// The amount bounds to send. One that isn't an amount is left out (and marked), and so are both when
// "desde" is above "hasta": the list still reloads with the other filters rather than going stale.
function amountFilter() {
  const minInput = document.getElementById("tx-amount-min");
  const maxInput = document.getElementById("tx-amount-max");
  let min = parseAmountFilter(minInput.value);
  let max = parseAmountFilter(maxInput.value);
  let message = "";
  const bad = [];
  if (Number.isNaN(min)) { bad.push(minInput); min = null; }
  if (Number.isNaN(max)) { bad.push(maxInput); max = null; }
  if (bad.length) {
    message = bad.length === 2 ? "Los importes no son válidos (ej.: 45 o 45,30): no se aplican."
      : `«${bad[0].value.trim()}» no es un importe válido (ej.: 45 o 45,30): no se aplica.`;
  } else if (min !== null && max !== null && min > max) {
    bad.push(minInput, maxInput);
    message = "El importe «desde» es mayor que «hasta»: no se aplica.";
    min = max = null;
  }
  for (const input of [minInput, maxInput]) {
    if (bad.includes(input)) input.setAttribute("aria-invalid", "true");
    else input.removeAttribute("aria-invalid");
  }
  document.getElementById("tx-amount-msg").textContent = message;
  return { min, max };
}

document.querySelectorAll("#view-transactions th[data-sort]").forEach((th) => {
  th.querySelector(".sort-btn").addEventListener("click", () => {
    const column = th.dataset.sort;
    if (txState.sort === column) {
      txState.dir = txState.dir === "asc" ? "desc" : "asc";
    } else {
      txState.sort = column;
      txState.dir = TEXT_SORTS.includes(column) ? "asc" : "desc";
    }
    txState.page = 0;
    renderTransactions();
  });
});

document.getElementById("tx-prev").addEventListener("click", () => { txState.page--; renderTransactions(); });
document.getElementById("tx-next").addEventListener("click", () => { txState.page++; renderTransactions(); });
document.getElementById("tx-page-size").addEventListener("change", (e) => {
  txState.size = Number(e.target.value);
  txState.page = 0;
  renderTransactions();
});

function updateSortHeaders() {
  document.querySelectorAll("#view-transactions th[data-sort]").forEach((th) => {
    const active = th.dataset.sort === txState.sort;
    th.setAttribute("aria-sort", active ? (txState.dir === "asc" ? "ascending" : "descending") : "none");
  });
}

function renderPager(data) {
  const pager = document.getElementById("tx-pager");
  pager.hidden = data.total === 0;
  if (data.total === 0) return;
  const from = data.page * data.size + 1;
  const to = from + data.items.length - 1;
  document.getElementById("tx-range").textContent = `${fmtInt(from)}–${fmtInt(to)} de ${fmtInt(data.total)}`;
  document.getElementById("tx-page-label").textContent = `Página ${fmtInt(data.page + 1)} de ${fmtInt(data.pages)}`;
  document.getElementById("tx-prev").disabled = data.page === 0;
  document.getElementById("tx-next").disabled = data.page >= data.pages - 1;
  document.getElementById("tx-page-size").value = String(data.size);
}

// Same guard as the dashboard: a slow response must not overwrite a newer one.
let txRun = 0;

async function renderTransactions() {
  const run = ++txRun;
  updateSortHeaders();
  const params = new URLSearchParams({
    sort: txState.sort, dir: txState.dir, page: txState.page, size: txState.size,
  });
  const category = document.getElementById("tx-category").value;
  const account = document.getElementById("tx-account").value;
  const search = document.getElementById("tx-search").value;
  for (const [key, value] of Object.entries(txPeriodParams())) params.set(key, value);
  if (category) params.set("category", category);
  if (account) params.set("account", account);
  if (search) params.set("search", search);
  const kind = document.getElementById("tx-kind").value;
  if (kind) params.set("kind", kind);
  const amount = amountFilter();
  if (amount.min !== null) params.set("amount_min", String(amount.min));
  if (amount.max !== null) params.set("amount_max", String(amount.max));

  const data = await api(`/api/transactions?${params.toString()}`);
  if (run !== txRun) return;

  // deleting the last rows of the last page leaves it past the end: step back to the new last page
  if (!data.items.length && data.total > 0 && txState.page > 0) {
    txState.page = data.pages - 1;
    return renderTransactions();
  }

  const txs = data.items;
  const tbody = document.getElementById("tx-table-body");
  renderPager(data);

  if (!txs.length) {
    tbody.innerHTML = `<tr><td colspan="8" class="empty">No hay transacciones que coincidan.</td></tr>`;
    updateBulkBar();
    return;
  }

  tbody.innerHTML = txs.map((t) => `
    <tr data-id="${t.id}">
      <td><input type="checkbox" class="row-select" data-id="${t.id}" ${selectedIds.has(t.id) ? "checked" : ""} /></td>
      <td class="nowrap">${t.date}</td>
      <td class="month-cell"><input type="month" class="month-input" data-id="${t.id}" value="${t.accounting_month || ""}" /></td>
      <td class="desc-cell" title="${escapeAttr(t.description)}">${escapeHtml(t.description)}</td>
      <td class="nowrap">${escapeHtml(t.account)}</td>
      <td>
        <select class="cat-select" data-id="${t.id}">${categoryOptionsHtml(t.category)}</select>
      </td>
      <td class="nowrap ${t.amount >= 0 ? "amount-pos" : "amount-neg"}" style="text-align:right">${fmtMoney(t.amount)}</td>
      <td><button class="small-btn" data-del="${t.id}">Eliminar</button></td>
    </tr>
  `).join("");

  tbody.querySelectorAll(".row-select").forEach((cb) => {
    cb.addEventListener("change", () => {
      const id = Number(cb.dataset.id);
      if (cb.checked) selectedIds.add(id); else selectedIds.delete(id);
      updateBulkBar();
    });
  });

  tbody.querySelectorAll(".cat-select").forEach((sel) => {
    sel.addEventListener("change", async () => {
      await api(`/api/transactions/${sel.dataset.id}`, {
        method: "PATCH",
        body: new URLSearchParams({ category: sel.value }),
      });
      toast("Categoría actualizada");
      offerRule(sel.closest("tr").querySelector(".desc-cell").title, sel.value);
    });
  });

  tbody.querySelectorAll(".month-input").forEach((inp) => {
    inp.addEventListener("change", async () => {
      if (!inp.value) return;
      await api(`/api/transactions/${inp.dataset.id}`, {
        method: "PATCH",
        body: new URLSearchParams({ accounting_month: inp.value }),
      });
      toast("Mes contable actualizado");
      renderTransactions();
    });
  });

  tbody.querySelectorAll("[data-del]").forEach((btn) => {
    btn.addEventListener("click", async () => {
      if (!confirm("¿Eliminar esta transacción?")) return;
      await api(`/api/transactions/${btn.dataset.del}`, { method: "DELETE" });
      selectedIds.delete(Number(btn.dataset.del));
      renderTransactions();
    });
  });

  updateBulkBar();
}

// ---------- Learning from a correction: suggest a rule ----------
// Changing a movement's category by hand offers to turn it into a rule, with a keyword guessed from the
// description (editable) and what the rule would do, worked out by the server with the real matching.
let ruleSuggestCategory = null;
let ruleImpactRun = 0;
let ruleImpactTimer = null;

// The part of a description worth matching on, in the shapes descriptions come in (see cleanDescription
// in EnableBankingClient): the merchant of a card payment, the concept of a Bizum, whoever a transfer or
// payroll names; "" when there's nothing specific (a Bizum with no concept).
function suggestKeyword(description) {
  const [head, ...rest] = description.split(" — ");
  const tail = rest.join(" — ");
  let text;
  if (!tail || /^pago con tarjeta$/i.test(tail)) {
    text = head;
  } else if (/^bizum$/i.test(head)) {
    const concept = tail.replace(/^[^:]*:\s*/, ""); // "COMPRA: TEMU" -> "TEMU"
    text = /^sin concepto$/i.test(concept) ? "" : concept;
  } else {
    // a detail that is only a reference number says nothing: fall back to the operation
    text = keywordFrom(tail) ? tail : head;
  }
  return keywordFrom(text);
}

// Drops what changes from one movement to the next: whatever follows a "*" (order ids such as
// "Airbnb * kd83jt2wp5") and words with two digits or more (references, card or terminal numbers).
function keywordFrom(text) {
  return text.split("*")[0]
    .split(/\s+/)
    .filter((word) => (word.match(/\d/g) || []).length < 2)
    .join(" ")
    .replace(/[\s.,;:—-]+$/, "")
    .trim()
    .toLowerCase();
}

const ruleSuggest = document.getElementById("rule-suggest");
const ruleKeywordInput = document.getElementById("rule-suggest-keyword");
const ruleApplyBtn = document.getElementById("rule-suggest-apply");
const ruleCreateBtn = document.getElementById("rule-suggest-create");

function offerRule(description, category) {
  if (category === UNCATEGORIZED) {
    hideRuleSuggestion();
    return;
  }
  ruleSuggestCategory = category;
  ruleKeywordInput.value = suggestKeyword(description);
  document.getElementById("rule-suggest-category").textContent = category;
  ruleSuggest.hidden = false;
  refreshRuleImpact();
}

function hideRuleSuggestion() {
  ruleSuggest.hidden = true;
  ruleSuggestCategory = null;
  ruleImpactRun++;
}

// With stored movements to move there are two ways to create the rule, and each label says what
// it does to them; with none, "Crear regla" is the only one (a lone "solo la regla" suggested a
// missing option).
function setRuleButtons({ create, apply, applyCount = 0 }) {
  ruleCreateBtn.disabled = !create;
  ruleApplyBtn.hidden = !apply;
  ruleApplyBtn.disabled = !apply;
  ruleApplyBtn.textContent = `Crear regla y pasar ${applyCount} a ${ruleSuggestCategory}`;
  ruleCreateBtn.textContent = !apply ? "Crear regla"
    : applyCount === 1 ? "Crear regla sin cambiar ese" : `Crear regla sin cambiar esos ${applyCount}`;
  ruleCreateBtn.className = apply ? "ghost" : "primary";
}

// "3 de Ocio y 2 de Sin categorizar"
function describeOrigins(from) {
  const parts = Object.entries(from).map(([category, n]) => `${n} de ${category}`);
  return parts.length > 1 ? `${parts.slice(0, -1).join(", ")} y ${parts.at(-1)}` : parts.join("");
}

async function refreshRuleImpact() {
  clearTimeout(ruleImpactTimer);
  const run = ++ruleImpactRun;
  const keyword = ruleKeywordInput.value;
  const category = ruleSuggestCategory;
  const impact = document.getElementById("rule-suggest-impact");
  setRuleButtons({ create: false, apply: false });
  if (!keyword.trim()) {
    impact.textContent = "Escribe la palabra o el trozo de texto que identifica estos movimientos.";
    return;
  }
  impact.textContent = "Calculando a qué movimientos afectaría…";
  let preview;
  try {
    preview = await api("/api/rules/preview?" + new URLSearchParams({ keyword, category }));
  } catch (e) {
    if (run === ruleImpactRun) impact.textContent = "No se ha podido calcular: " + e.message;
    return;
  }
  if (run !== ruleImpactRun) return;

  if (preview.existing_rule) {
    impact.textContent = preview.existing_rule === category
      ? `Ya existe la regla «${keyword.trim()}» → ${category}.`
      : `Ya hay una regla «${keyword.trim()}» que los manda a ${preview.existing_rule}: cámbiala en Categorías y reglas.`;
    return;
  }
  const lines = [preview.matches === 1 ? "Coincide con 1 movimiento" : `Coincide con ${preview.matches} movimientos`];
  if (preview.already) lines.push(`${preview.already} ya en ${category}`);
  if (preview.would_change) lines.push(`${preview.would_change} pasarían a ${category} (${describeOrigins(preview.from)})`);
  if (preview.other_rule) lines.push(`${preview.other_rule} se quedan como están porque otra regla más concreta decide`);
  impact.textContent = `${lines.join(" · ")}. A partir de ahora, los que se importen irán a ${category}.`;
  setRuleButtons({ create: true, apply: preview.would_change > 0, applyCount: preview.would_change });
}

ruleKeywordInput.addEventListener("input", () => {
  ruleImpactRun++; // what is on screen no longer matches the keyword: don't let it be created
  setRuleButtons({ create: false, apply: false });
  clearTimeout(ruleImpactTimer);
  ruleImpactTimer = setTimeout(refreshRuleImpact, 300);
});

async function createSuggestedRule(apply) {
  const keyword = ruleKeywordInput.value;
  const category = ruleSuggestCategory;
  setRuleButtons({ create: false, apply: false });
  let result;
  try {
    result = await api("/api/rules", {
      method: "POST",
      body: new URLSearchParams({ keyword, category, apply: String(apply) }),
    });
  } catch (e) {
    document.getElementById("rule-suggest-impact").textContent = "No se ha podido crear la regla: " + e.message;
    return;
  }
  hideRuleSuggestion();
  const moved = result.updated === 1 ? " · 1 movimiento actualizado"
    : result.updated ? ` · ${result.updated} movimientos actualizados` : "";
  toast(`Regla creada: «${keyword.trim()}» → ${category}${moved}`);
  if (result.updated) renderTransactions();
}

ruleApplyBtn.addEventListener("click", () => createSuggestedRule(true));
ruleCreateBtn.addEventListener("click", () => createSuggestedRule(false));
document.getElementById("rule-suggest-dismiss").addEventListener("click", hideRuleSuggestion);

// ---------- Bulk selection ----------
function updateBulkBar() {
  const bar = document.getElementById("bulk-bar");
  const count = selectedIds.size;
  bar.hidden = count === 0;
  document.getElementById("bulk-count").textContent =
    count === 1 ? "1 seleccionada" : `${count} seleccionadas`;

  const selectAll = document.getElementById("tx-select-all");
  const rowBoxes = document.querySelectorAll(".row-select");
  const visibleChecked = document.querySelectorAll(".row-select:checked").length;
  selectAll.checked = rowBoxes.length > 0 && visibleChecked === rowBoxes.length;
  selectAll.indeterminate = visibleChecked > 0 && visibleChecked < rowBoxes.length;
}

document.getElementById("tx-select-all").addEventListener("change", (e) => {
  document.querySelectorAll(".row-select").forEach((cb) => {
    cb.checked = e.target.checked;
    const id = Number(cb.dataset.id);
    if (e.target.checked) selectedIds.add(id); else selectedIds.delete(id);
  });
  updateBulkBar();
});

document.getElementById("bulk-clear").addEventListener("click", () => {
  selectedIds.clear();
  renderTransactions();
});

document.getElementById("bulk-delete").addEventListener("click", async () => {
  const n = selectedIds.size;
  if (!n) return;
  if (!confirm(`¿Eliminar ${n} transacción(es)? Esta acción no se puede deshacer.`)) return;
  await api("/api/transactions/bulk-delete", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ ids: [...selectedIds] }),
  });
  toast(`${n} transacción(es) eliminada(s)`);
  selectedIds.clear();
  renderTransactions();
});

document.getElementById("bulk-apply-category").addEventListener("click", async () => {
  const n = selectedIds.size;
  if (!n) return;
  const category = document.getElementById("bulk-category").value;
  if (!category) { toast("Elige una categoría primero"); return; }
  await api("/api/transactions/bulk-category", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ ids: [...selectedIds], category }),
  });
  toast(`Categoría aplicada a ${n} transacción(es)`);
  selectedIds.clear();
  renderTransactions();
});
