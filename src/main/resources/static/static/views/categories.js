// Categories & rules (#categories), and the password form.
// Loaded after app.js (shared helpers and navigation); see index.html for the order.

// ---------- Categories & Rules ----------
const CATEGORY_TABS = ["categories", "rules", "security"];
let allRules = [];
let pendingTotal = 0;

const plural = (n, one, many) => `${fmtInt(n)} ${n === 1 ? one : many}`;

function selectCategoryTab(name) {
  for (const tab of CATEGORY_TABS) {
    const selected = tab === name;
    const button = document.getElementById(`tab-${tab}`);
    button.setAttribute("aria-selected", String(selected));
    button.tabIndex = selected ? 0 : -1;
    document.getElementById(`panel-${tab}`).hidden = !selected;
  }
}

document.querySelectorAll("#view-categories .tab").forEach((tab) => {
  tab.addEventListener("click", () => selectCategoryTab(tab.dataset.tab));
  tab.addEventListener("keydown", (e) => {
    const step = { ArrowRight: 1, ArrowLeft: -1 }[e.key];
    if (!step) return;
    const next = CATEGORY_TABS[(CATEGORY_TABS.indexOf(tab.dataset.tab) + step + CATEGORY_TABS.length) % CATEGORY_TABS.length];
    selectCategoryTab(next);
    document.getElementById(`tab-${next}`).focus();
  });
});

async function loadCategoriesView() {
  const [, rules, who] = await Promise.all([refreshCategories(), api("/api/rules"), api("/api/auth/whoami")]);
  allRules = rules;
  document.getElementById("auth-username").textContent = who.username;
  renderCategoriesList();
  renderRules();
  await refreshPending();
}

async function reloadRules() {
  allRules = await api("/api/rules");
  renderRules();
  renderCategoriesList(); // each card shows how many rules it has
}

// The list endpoint returns the total, so asking for one row is a cheap way to count.
async function countTransactionsIn(category) {
  const page = await api(`/api/transactions?category=${encodeURIComponent(category)}&size=1`);
  return page.total;
}

async function refreshPending() {
  pendingTotal = await countTransactionsIn(UNCATEGORIZED);
  document.getElementById("btn-apply-rules").disabled = pendingTotal === 0;
  document.getElementById("pending-info").textContent = pendingTotal === 0
    ? "No hay movimientos sin categorizar."
    : `${plural(pendingTotal, "movimiento sin categorizar", "movimientos sin categorizar")}.`;
}

document.getElementById("form-change-password").addEventListener("submit", async (e) => {
  e.preventDefault();
  clearFeedback("password-feedback");
  const current_password = document.getElementById("current-password").value;
  const new_password = document.getElementById("new-password").value;
  // No recovery exists for a forgotten password, so a typo here would lock the only user out.
  if (new_password.length < 6) {
    setFeedback("password-feedback", "La contraseña nueva debe tener al menos 6 caracteres.");
    return;
  }
  if (new_password !== document.getElementById("repeat-password").value) {
    setFeedback("password-feedback", "Las contraseñas nuevas no coinciden.");
    return;
  }
  try {
    await api("/api/auth/change-password", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ current_password, new_password }),
    });
  } catch (err) {
    setFeedback("password-feedback", err.message);
    return;
  }
  document.getElementById("form-change-password").reset();
  setFeedback("password-feedback", "Contraseña actualizada. Úsala la próxima vez que inicies sesión.", "ok");
});

function renderCategoriesList() {
  const list = document.getElementById("categories-list");
  const rulesPerCategory = {};
  for (const rule of allRules) rulesPerCategory[rule.category] = (rulesPerCategory[rule.category] || 0) + 1;

  list.innerHTML = allCategories.map((c) => {
    const rules = rulesPerCategory[c.name] || 0;
    const name = escapeAttr(c.name);
    return `
    <div class="category-card">
      <div class="top">
        <span class="name">${escapeHtml(c.name)}</span>
        <span class="pill">${c.kind === "income" ? "Ingreso" : "Gasto"}</span>
      </div>
      <div class="meta">
        ${rules
          ? `<button type="button" class="link-btn" data-show-rules="${name}">${plural(rules, "regla", "reglas")}</button>`
          : "<span>Sin reglas</span>"}
        ${c.name === UNCATEGORIZED
          ? '<span title="Los importes sin regla van a esta categoría, por eso no se puede eliminar">Protegida</span>'
          : `<button type="button" class="small-btn" data-del-cat="${name}" aria-label="Eliminar la categoría ${name}">Eliminar</button>`}
      </div>
    </div>`;
  }).join("");

  list.querySelectorAll("[data-show-rules]").forEach((btn) => {
    btn.addEventListener("click", () => {
      document.getElementById("rules-filter").value = btn.dataset.showRules;
      renderRules();
      selectCategoryTab("rules");
    });
  });

  list.querySelectorAll("[data-del-cat]").forEach((btn) => {
    btn.addEventListener("click", async () => {
      const name = btn.dataset.delCat;
      // say what will actually happen, with numbers, before the user confirms
      const transactions = await countTransactionsIn(name);
      const rules = allRules.filter((r) => r.category === name).length;
      const effects = [];
      if (transactions) effects.push(`${plural(transactions, "movimiento pasará", "movimientos pasarán")} a «${UNCATEGORIZED}»`);
      if (rules) effects.push(`${rules === 1 ? "se borrará" : "se borrarán"} ${plural(rules, "regla", "reglas")}`);
      const consequence = effects.length
        ? (effects.join(" y ").charAt(0).toUpperCase() + effects.join(" y ").slice(1) + ".")
        : "No tiene movimientos ni reglas.";
      if (!confirm(`¿Eliminar la categoría «${name}»?\n\n${consequence}`)) return;
      try {
        // query param, not a path segment: names like "Nómina / Ingresos" contain a "/"
        await api(`/api/categories?name=${encodeURIComponent(name)}`, { method: "DELETE" });
        toast(`Categoría «${name}» eliminada`);
      } catch (e) {
        toast("Error: " + e.message);
      }
      await loadCategoriesView();
    });
  });
}

// A keyword padded with a space on both sides is a whole-word rule (" dia " matches "dia" but not
// "media markt"): show it without the padding and tag it, since the spaces themselves are invisible.
function keywordLabel(keyword) {
  const whole = keyword.length > 2 && keyword.startsWith(" ") && keyword.endsWith(" ");
  return whole
    ? { text: keyword.trim(), whole: true }
    : { text: keyword.replace(/^ /, "␣").replace(/ $/, "␣"), whole: false };
}

function renderRules() {
  const filter = document.getElementById("rules-filter").value.trim().toLowerCase();
  document.getElementById("rules-count").textContent = allRules.length || "";

  const select = document.getElementById("new-rule-category");
  const previous = select.value;
  select.innerHTML = allCategories.map((c) => optionHtml(c.name)).join("");
  if (previous) select.value = previous;

  const visible = allRules.filter((r) =>
    !filter || r.keyword.toLowerCase().includes(filter) || r.category.toLowerCase().includes(filter));
  document.getElementById("rules-summary").textContent =
    filter ? `${visible.length} de ${plural(allRules.length, "regla", "reglas")}` : plural(allRules.length, "regla", "reglas");

  const list = document.getElementById("rules-list");
  if (!allRules.length) {
    list.innerHTML = '<p class="empty">Aún no has añadido reglas.</p>';
    return;
  }
  if (!visible.length) {
    list.innerHTML = `<p class="empty">Ninguna regla coincide con «${escapeHtml(filter)}».</p>`;
    return;
  }

  const groups = new Map();
  for (const rule of visible) {
    if (!groups.has(rule.category)) groups.set(rule.category, []);
    groups.get(rule.category).push(rule);
  }
  const order = allCategories.map((c) => c.name);
  const rank = (name) => (order.includes(name) ? order.indexOf(name) : order.length);
  const names = [...groups.keys()].sort((a, b) => rank(a) - rank(b) || a.localeCompare(b, "es"));

  list.innerHTML = names.map((name) => `
    <div class="rule-group">
      <h4>${escapeHtml(name)} <span class="muted">${groups.get(name).length}</span></h4>
      <ul class="rule-chips">
        ${groups.get(name).map((r) => {
          const { text, whole } = keywordLabel(r.keyword);
          return `<li class="rule-chip" title="${escapeAttr(`"${r.keyword}"`)}">
            <span class="kw">${escapeHtml(text)}</span>${whole ? '<span class="tag">palabra</span>' : ""}
            <button type="button" class="chip-x" data-del-rule="${r.id}" data-kw="${escapeAttr(text)}"
                    aria-label="Eliminar la regla «${escapeAttr(text)}»">×</button>
          </li>`;
        }).join("")}
      </ul>
    </div>`).join("");

  list.querySelectorAll("[data-del-rule]").forEach((btn) => {
    btn.addEventListener("click", async () => {
      await api(`/api/rules/${btn.dataset.delRule}`, { method: "DELETE" });
      toast(`Regla «${btn.dataset.kw}» eliminada`);
      await reloadRules();
    });
  });
}

document.getElementById("rules-filter").addEventListener("input", renderRules);

document.getElementById("form-add-category").addEventListener("submit", async (e) => {
  e.preventDefault();
  clearFeedback("category-feedback");
  const input = document.getElementById("new-category-name");
  const name = input.value.trim();
  const kind = document.getElementById("new-category-kind").value;
  if (!name) return;
  // the server only rejects an identical name; "compras" next to "Compras" would just be confusing
  const clash = allCategories.find((c) => c.name.toLowerCase() === name.toLowerCase());
  if (clash) {
    setFeedback("category-feedback", `Ya existe una categoría llamada «${clash.name}».`);
    return;
  }
  try {
    await api("/api/categories", { method: "POST", body: new URLSearchParams({ name, kind }) });
  } catch (err) {
    setFeedback("category-feedback", err.message);
    return;
  }
  input.value = "";
  await loadCategoriesView();
  setFeedback("category-feedback", `Categoría «${name}» añadida.`, "ok");
});

document.getElementById("form-add-rule").addEventListener("submit", async (e) => {
  e.preventDefault();
  clearFeedback("rule-feedback");
  const input = document.getElementById("new-rule-keyword");
  const wholeWord = document.getElementById("new-rule-whole");
  const typed = input.value.trim();
  const category = document.getElementById("new-rule-category").value;
  if (!typed) { setFeedback("rule-feedback", "Escribe una palabra clave."); return; }
  if (!category) { setFeedback("rule-feedback", "Crea antes una categoría a la que asignarla."); return; }

  // the padding must survive: it is what makes the rule match the whole word only
  const keyword = wholeWord.checked ? ` ${typed} ` : typed;
  const existing = allRules.find((r) => r.keyword.toLowerCase() === keyword.toLowerCase());
  if (existing) {
    setFeedback("rule-feedback", existing.category === category
      ? `Ya existe esa regla («${typed}» → ${category}).`
      : `Ya existe una regla para «${typed}» y la manda a «${existing.category}». Elimínala antes si quieres cambiarla.`);
    return;
  }
  try {
    await api("/api/rules", { method: "POST", body: new URLSearchParams({ keyword, category }) });
  } catch (err) {
    setFeedback("rule-feedback", err.message);
    return;
  }
  input.value = "";
  wholeWord.checked = false;
  input.focus();
  await reloadRules();
  setFeedback("rule-feedback",
    `Regla añadida: «${typed}» → ${category}. Se aplicará al importar; para lo ya importado usa «Aplicar reglas».`, "ok");
});

document.getElementById("btn-apply-rules").addEventListener("click", async (e) => {
  const button = e.currentTarget;
  button.disabled = true;
  try {
    const result = await api("/api/rules/apply-all", { method: "POST" });
    toast(result.updated === 0
      ? "Ninguna regla reconoce esos movimientos"
      : `${plural(result.updated, "movimiento recategorizado", "movimientos recategorizados")}`);
  } finally {
    await refreshPending();
  }
});
