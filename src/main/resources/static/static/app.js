// Shared by every view: formatting, toasts, theme, the api() wrapper, navigation between views and the
// helpers several views use. Each view lives in views/*.js; start.js runs last. They are classic scripts
// sharing one global scope, so a view can call anything defined here or in another view.

const fmtMoney = (n) => new Intl.NumberFormat("es-ES", { style: "currency", currency: "EUR" }).format(n);

if (window.Chart) {
  Chart.defaults.font.family = "'Public Sans', -apple-system, sans-serif";
}

const fmtMoneyCompact = (n) =>
  new Intl.NumberFormat("es-ES", { style: "currency", currency: "EUR", notation: "compact", maximumFractionDigits: 1 }).format(n);
const monoFont = { family: "'IBM Plex Mono', monospace" };

// "2026-09" -> "sept 26"
function fmtMonthLabel(ym) {
  const [y, m] = ym.split("-").map(Number);
  return new Date(y, m - 1, 1).toLocaleDateString("es-ES", { month: "short", year: "2-digit" });
}

let toastTimer;
function toast(msg) {
  const el = document.getElementById("toast");
  el.textContent = msg;
  el.classList.add("show");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.remove("show"), 2600);
}

// A failed API call outside a handler's own try/catch (changing a category, deleting a
// row...) would otherwise fail silently and leave the UI looking as if it had worked.
window.addEventListener("unhandledrejection", (e) => {
  toast("Error: " + (e.reason && e.reason.message ? e.reason.message : "algo ha fallado"));
});

// ---------- Theme ----------
function currentTheme() {
  return document.documentElement.getAttribute("data-theme") === "dark" ? "dark" : "light";
}

// Chart colours come from the CSS variables so charts can't drift from the page palette.
function themeColors() {
  const styles = getComputedStyle(document.documentElement);
  const get = (name) => styles.getPropertyValue(name).trim();
  return {
    text: get("--text"),
    muted: get("--text-muted"),
    border: get("--border"),
    surface: get("--surface"),
    primary: get("--primary"),
    income: get("--income"),
    expense: get("--expense"),
  };
}

// "#6c5ce7" -> "rgba(108, 92, 231, 0.15)" (theme variables are always #rrggbb)
function withAlpha(hex, alpha) {
  const n = parseInt(hex.slice(1), 16);
  return `rgba(${n >> 16}, ${(n >> 8) & 255}, ${n & 255}, ${alpha})`;
}

const CATEGORY_PALETTE = {
  light: ["#6c5ce7", "#e17055", "#00b894", "#f0a500", "#0984e3", "#e84393", "#00a3a3", "#8e6c3a"],
  dark: ["#a996f8", "#ff8a75", "#3ddc84", "#ffd166", "#5aa9ff", "#ff7eb6", "#4de8e0", "#d2a679"],
};
const OTHERS_COLOR = { light: "#b2adc7", dark: "#6b6483" };
const MAX_CATEGORY_SLICES = 8;

// More slices than palette colours would repeat colours and be unreadable, so the tail
// of the (already sorted) list is folded into one "Otras" slice. A lone leftover stays as is.
function groupTopCategories(rows) {
  if (rows.length <= MAX_CATEGORY_SLICES + 1) return rows;
  const rest = rows.slice(MAX_CATEGORY_SLICES);
  return [
    ...rows.slice(0, MAX_CATEGORY_SLICES),
    { category: `Otras (${rest.length})`, amount: rest.reduce((sum, r) => sum + r.amount, 0), isOthers: true },
  ];
}

function updateThemeToggleLabel() {
  const dark = currentTheme() === "dark";
  document.getElementById("theme-toggle-icon").textContent = dark ? "☀️" : "🌙";
  document.getElementById("theme-toggle-label").textContent = dark ? "Modo claro" : "Modo oscuro";
}

document.getElementById("theme-toggle").addEventListener("click", () => {
  const next = currentTheme() === "dark" ? "light" : "dark";
  document.documentElement.setAttribute("data-theme", next);
  try { localStorage.setItem("theme", next); } catch (e) {}
  updateThemeToggleLabel();
  // charts read their colours when built; other views get fresh ones on next visit
  if (document.getElementById("view-dashboard").classList.contains("active")) renderDashboardData();
  if (document.getElementById("view-prices").classList.contains("active")) renderPrices();
});

updateThemeToggleLabel();

async function api(path, options = {}) {
  const res = await fetch(path, options);
  if (!res.ok) {
    let detail = res.statusText;
    try { detail = (await res.json()).detail || detail; } catch (e) {}
    throw new Error(detail);
  }
  const ct = res.headers.get("content-type") || "";
  return ct.includes("application/json") ? res.json() : null;
}

// ---------- Navigation ----------
// The current view lives in the URL hash so a reload (or back/forward) stays where you were.
const VIEWS = ["dashboard", "year", "recurring", "transactions", "import", "categories", "prices", "economy", "holidays"];

function viewFromHash() {
  // The bank-sync callback redirects to "#import?bank=linked" (or "=error"), so the view name is
  // everything before the "?".
  const name = location.hash.slice(1).split("?")[0];
  return VIEWS.includes(name) ? name : "dashboard";
}

function showView(name) {
  document.querySelectorAll(".nav-btn").forEach((b) => {
    const active = b.dataset.view === name;
    b.classList.toggle("active", active);
    if (active) b.setAttribute("aria-current", "page"); else b.removeAttribute("aria-current");
  });
  document.querySelectorAll(".view").forEach((v) => v.classList.toggle("active", v.id === `view-${name}`));
  if (name === "dashboard") loadDashboard();
  if (name === "year") loadYearView();
  if (name === "recurring") loadRecurringView();
  if (name === "transactions") loadTransactions();
  if (name === "categories") loadCategoriesView();
  if (name === "import") loadImportView();
  if (name === "prices") loadPricesView();
  if (name === "economy") loadEconomyView();
  if (name === "holidays") loadHolidaysView();
  showBankExpiryBanner();
  document.body.classList.remove("sidebar-open");
}

document.querySelectorAll(".nav-btn").forEach((btn) => {
  btn.addEventListener("click", () => {
    // changing the hash fires hashchange; clicking the current view just refreshes it
    if (location.hash === `#${btn.dataset.view}`) showView(btn.dataset.view);
    else location.hash = btn.dataset.view;
  });
});
window.addEventListener("hashchange", () => showView(viewFromHash()));

// ---------- Mobile menu ----------
document.getElementById("mobile-menu-btn").addEventListener("click", () => {
  document.body.classList.toggle("sidebar-open");
});
document.getElementById("sidebar-backdrop").addEventListener("click", () => {
  document.body.classList.remove("sidebar-open");
});

// ---------- Shared state ----------
let allCategories = [];

async function refreshCategories() {
  allCategories = await api("/api/categories");
  return allCategories;
}

function escapeHtml(str) {
  const div = document.createElement("div");
  div.textContent = str;
  return div.innerHTML;
}

function escapeAttr(str) {
  return escapeHtml(str).replace(/"/g, "&quot;").replace(/'/g, "&#39;");
}

// Every <option> built from data (category/account names, column headers from an
// imported file) goes through here so nothing user-controlled reaches innerHTML raw.
function optionHtml(value, label = value, selected = false) {
  return `<option value="${escapeAttr(value)}"${selected ? " selected" : ""}>${escapeHtml(label)}</option>`;
}

// ---------- Shared helpers ----------
const fmtInt = (n) => new Intl.NumberFormat("es-ES").format(n);

// The local calendar date as YYYY-MM-DD: toISOString() would give the UTC one, a day off around midnight.
function localIsoDate(date) {
  const pad = (n) => String(n).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

function addDaysIso(iso, days) {
  const [y, m, d] = iso.split("-").map(Number);
  return localIsoDate(new Date(y, m - 1, d + days));
}

function formatIsoDay(iso) {
  const [y, m, d] = iso.split("-");
  return `${d}/${m}/${y}`;
}

const UNCATEGORIZED = "Sin categorizar";

// Messages that must stay next to the form they belong to (a toast is gone before it's read).
function setFeedback(id, message, kind = "error") {
  const el = document.getElementById(id);
  el.textContent = message;
  el.className = `feedback ${kind}`;
  el.hidden = false;
}

function clearFeedback(id) {
  document.getElementById(id).hidden = true;
}

// "hace 3 h", "hace 2 días"
function timeAgo(iso, now = Date.now()) {
  const minutes = Math.round((now - new Date(iso).getTime()) / 60000);
  if (minutes < 1) return "ahora mismo";
  if (minutes < 60) return `hace ${minutes} min`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `hace ${hours} h`;
  const days = Math.round(hours / 24);
  if (days < 30) return days === 1 ? "hace 1 día" : `hace ${days} días`;
  return "el " + new Date(iso).toLocaleDateString("es-ES", { day: "numeric", month: "short", year: "numeric" });
}
