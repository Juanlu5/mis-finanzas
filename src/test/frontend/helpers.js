"use strict";
// Test harness: loads the REAL index.html + the app's scripts (app.js, views/*.js, start.js) into
// jsdom, with a fake in-memory API in place of fetch, and a stub for Chart.js. They are classic
// scripts with globals and top-level DOM code, so each is run as an inline <script>, in the page's
// order, exactly like a browser would: their top-level let/const (txState, selectedIds...) end up
// in the global lexical scope and tests can read them.

const fs = require("node:fs");
const path = require("node:path");
const { JSDOM } = require("jsdom");

const STATIC_DIR = path.resolve(__dirname, "../../main/resources/static");
const INDEX_HTML = fs.readFileSync(path.join(STATIC_DIR, "index.html"), "utf8");

// External scripts/stylesheets are not loaded (Chart.js is stubbed, CSS doesn't matter here);
// the app's own scripts (everything under /static/ but the vendored libraries) are inlined where
// the page references them. Spring serves /static/x from STATIC_DIR/static/x.
const isOwnScript = (src) => src.startsWith("/static/") && !src.startsWith("/static/vendor/");
const PAGE_HTML = INDEX_HTML
  .replace(/<link[^>]*>/g, "")
  .replace(/<script[^>]*\bsrc="([^"]*)"[^>]*><\/script>/g, (tag, src) =>
    isOwnScript(src) ? `<script>${fs.readFileSync(path.join(STATIC_DIR, src), "utf8")}</script>` : "");

const tick = () => new Promise((resolve) => setImmediate(resolve));
const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * @param {object} options
 * @param {string} [options.hash] location hash to open the page with, without "#"
 * @param {(call: object) => any} options.api  fake server: receives {method, path, params, body, json()}
 *        and returns a body, or {status, body}, or a promise of either. undefined = 404.
 * @param {object} [options.storage] localStorage contents to start with, {key: string value}
 */
async function loadApp({ hash = "", api, storage = {} }) {
  const calls = [];
  const charts = [];
  let pending = 0;

  const dom = new JSDOM(PAGE_HTML, {
    url: `http://localhost/${hash ? "#" + hash : ""}`,
    runScripts: "dangerously",
    beforeParse(window) {
      // what an earlier visit left in localStorage
      for (const [key, value] of Object.entries(storage)) window.localStorage.setItem(key, value);
      window.confirm = () => true;
      window.matchMedia = () => ({ matches: false, addEventListener() {}, removeEventListener() {} });
      window.Chart = class {
        constructor(canvas, config) {
          this.canvas = canvas;
          this.config = config;
          charts.push(this);
        }
        destroy() { this.destroyed = true; }
      };
      window.Chart.defaults = { font: {} };

      window.fetch = (url, options = {}) => {
        const u = new URL(url, "http://localhost");
        const call = {
          method: (options.method || "GET").toUpperCase(),
          path: u.pathname,
          params: u.searchParams,
          body: options.body,
          json: () => JSON.parse(options.body),
          form: () => Object.fromEntries(new URLSearchParams(String(options.body))),
        };
        calls.push(call);
        pending++;
        return Promise.resolve(api(call)).then((result) => {
          pending--;
          const hasStatus = result && typeof result === "object" && "status" in result;
          const status = result === undefined ? 404 : hasStatus ? result.status : 200;
          const body = hasStatus ? result.body : result === undefined ? { detail: "No encontrado" } : result;
          return {
            ok: status < 400,
            status,
            statusText: String(status),
            headers: { get: (name) => (name.toLowerCase() === "content-type" ? "application/json" : null) },
            json: async () => body,
          };
        }, (error) => { pending--; throw error; });
      };
    },
  });

  const { window } = dom;
  const { document } = window;

  const app = {
    window,
    document,
    calls,
    charts,
    $: (id) => document.getElementById(id),
    $$: (selector) => [...document.querySelectorAll(selector)],
    /** requests to one path, oldest first */
    callsTo: (p) => calls.filter((c) => c.path === p),
    /** waits until every request made so far has been answered and the UI has re-rendered */
    async settle() {
      for (let i = 0; i < 30; i++) {
        await tick();
        if (pending === 0) { await tick(); if (pending === 0) return; }
      }
    },
    click(el) { el.click(); },
    change(el, value) {
      el.value = value;
      el.dispatchEvent(new window.Event("change", { bubbles: true }));
    },
    /** evaluates an expression in the page and returns it as a plain (same-realm) value */
    value(expression) {
      return JSON.parse(window.eval(`JSON.stringify(${expression})`));
    },
    close() { window.close(); },
  };
  await app.settle();
  return app;
}

// ---------- fake backend ----------

const CATEGORIES = [
  { name: "Ocio", kind: "expense" },
  { name: "Salud", kind: "expense" },
  { name: "Sin categorizar", kind: "expense" },
  { name: "Nómina / Ingresos", kind: "income" },
];

/** Rows 1..total, oldest first: date grows with id, so the default order (date desc) is id desc. */
function makeTransactions(total) {
  return Array.from({ length: total }, (_, index) => {
    const id = index + 1;
    const date = new Date(Date.UTC(2026, 0, 1) + id * 86400000).toISOString().slice(0, 10);
    return {
      id,
      date,
      description: `Compra ${String(id).padStart(3, "0")}`,
      amount: -id,
      category: id % 2 ? "Ocio" : "Salud",
      account: "Principal",
      accounting_month: date.slice(0, 7),
    };
  });
}

const SORT_KEYS = {
  date: (t) => t.date,
  accounting_month: (t) => t.accounting_month,
  description: (t) => t.description.toLowerCase(),
  account: (t) => t.account.toLowerCase(),
  category: (t) => t.category.toLowerCase(),
  amount: (t) => t.amount,
};

/**
 * Stateful fake of the whole API the frontend uses. `state.transactions` is mutated by the
 * delete/bulk endpoints, so a re-fetch after a change returns what a real server would.
 * Override any endpoint with `overrides[path] = (call) => body`.
 */
function fakeBackend({
  total = 120, transactions, categories = CATEGORIES, rules = [], summary, byCategory = [], overrides = {}, password = "secreta",
  currentBalance, products = [], shop, recurring = [], recurringIncome = null,
} = {}) {
  const state = {
    transactions: transactions || makeTransactions(total),
    categories: categories.map((c) => ({ ...c })),
    rules: rules.map((r, i) => ({ id: i + 1, ...r })),
    nextRuleId: rules.length + 1,
    password,
    passwordChanges: [],
    products: products.map((p) => ({ ...p })),
    histories: {},
    nextProductId: products.reduce((max, p) => Math.max(max, p.id), 0) + 1,
    // what the "shop" answers for a link: {name, price, currency?, available?}, or {error} if it can't be read
    shop: shop || (() => ({ name: "Club C 85", price: 70.19, available: true })),
    // what GET /api/recurring detects; the hidden flags and the totals are worked out like RecurringService does
    recurring: recurring.map((item) => ({ ...item })),
    recurringHidden: new Set(recurring.filter((item) => item.hidden).map((item) => item.key)),
  };
  function recurringReport() {
    const items = state.recurring.map((item) => ({ ...item, hidden: state.recurringHidden.has(item.key) }));
    const monthly = Math.round(items.filter((i) => i.active && !i.hidden).reduce((sum, i) => sum + i.monthly, 0) * 100) / 100;
    return { monthly_total: monthly, yearly_total: Math.round(monthly * 1200) / 100, income_monthly: recurringIncome, items };
  }
  const bad = (detail) => ({ status: 400, body: { detail } });

  function list(params) {
    let rows = state.transactions.filter((t) =>
      (!params.get("category") || t.category === params.get("category")) &&
      (!params.get("month") || t.accounting_month === params.get("month")) &&
      (!params.get("month_from") || (t.accounting_month >= params.get("month_from") && t.accounting_month <= params.get("month_to"))) &&
      (!params.get("account") || t.account === params.get("account")) &&
      (!params.get("search") || t.description.toLowerCase().includes(params.get("search").toLowerCase())) &&
      (!params.get("kind") || (params.get("kind") === "income" ? t.amount > 0 : t.amount < 0)) &&
      (!params.has("amount_min") || Math.abs(t.amount) >= Number(params.get("amount_min")) - 0.005) &&
      (!params.has("amount_max") || Math.abs(t.amount) <= Number(params.get("amount_max")) + 0.005));
    const key = SORT_KEYS[params.get("sort") || "date"];
    const dir = params.get("dir") === "asc" ? 1 : -1;
    rows = rows.sort((a, b) => (key(a) < key(b) ? -dir : key(a) > key(b) ? dir : b.id - a.id));
    const size = Number(params.get("size") || 50);
    const page = Number(params.get("page") || 0);
    return {
      items: rows.slice(page * size, page * size + size),
      total: rows.length,
      page,
      size,
      pages: Math.ceil(rows.length / size),
    };
  }

  // Same matching and ranking as CategorizationService: padded, case-insensitive substring; longest keyword
  // first, then the older rule (a rule not saved yet has no id and loses ties).
  const matchesRule = (t, rule) => ` ${t.description.toLowerCase()} `.includes(rule.keyword.toLowerCase());
  function winner(t, rules) {
    return [...rules]
      .filter((r) => r.keyword.trim())
      .sort((a, b) => b.keyword.trim().length - a.keyword.trim().length || (a.id ?? Infinity) - (b.id ?? Infinity))
      .find((r) => matchesRule(t, r));
  }
  function ruleImpact(keyword, category) {
    const candidate = { keyword, category };
    const rules = [...state.rules, candidate];
    let matches = 0, already = 0, otherRule = 0;
    const moving = [];
    for (const t of state.transactions) {
      if (!matchesRule(t, candidate)) continue;
      matches++;
      if (t.category === category) already++;
      else if (winner(t, rules).category === category) moving.push(t);
      else otherRule++;
    }
    return { matches, already, moving, otherRule };
  }

  function handler(call) {
    if (overrides[call.path]) return overrides[call.path](call);
    const { method, path: p, params } = call;
    if (p === "/api/transactions" && method === "GET") return list(params);
    const one = p.match(/^\/api\/transactions\/(\d+)$/);
    if (one && method === "PATCH") {
      const t = state.transactions.find((tx) => tx.id === Number(one[1]));
      const { category, description, accounting_month } = call.form();
      if (category !== undefined) t.category = category;
      if (description !== undefined) t.description = description;
      if (accounting_month !== undefined) t.accounting_month = accounting_month;
      return { ok: true };
    }
    if (one && method === "DELETE") {
      state.transactions = state.transactions.filter((t) => t.id !== Number(one[1]));
      return { ok: true };
    }
    if (p === "/api/transactions/bulk-delete") {
      const ids = new Set(call.json().ids);
      state.transactions = state.transactions.filter((t) => !ids.has(t.id));
      return { deleted: ids.size };
    }
    if (p === "/api/transactions/bulk-category") {
      const { ids, category } = call.json();
      state.transactions.filter((t) => ids.includes(t.id)).forEach((t) => { t.category = category; });
      return { updated: ids.length };
    }
    if (p === "/api/categories" && method === "GET") return state.categories;
    if (p === "/api/categories" && method === "POST") {
      const { name, kind = "expense" } = call.form();
      if (state.categories.some((c) => c.name === name)) return bad("Ya existe esa categoría");
      state.categories.push({ name, kind });
      return { ok: true };
    }
    if (p === "/api/categories" && method === "DELETE") {
      const name = params.get("name");
      if (name === "Sin categorizar") return bad("No se puede eliminar la categoría «Sin categorizar»");
      const reassigned = state.transactions.filter((t) => t.category === name);
      reassigned.forEach((t) => { t.category = "Sin categorizar"; });
      const before = state.rules.length;
      state.rules = state.rules.filter((r) => r.category !== name);
      state.categories = state.categories.filter((c) => c.name !== name);
      return { ok: true, reassigned: reassigned.length, rules_deleted: before - state.rules.length };
    }
    if (p === "/api/accounts") return ["Principal"];
    if (p === "/api/rules" && method === "GET") return [...state.rules].sort((a, b) => a.keyword.localeCompare(b.keyword));
    if (p === "/api/rules" && method === "POST") {
      const { keyword, category, apply } = call.form();
      if (!keyword.trim()) return bad("La palabra clave no puede estar vacía");
      const { moving } = ruleImpact(keyword, category);
      state.rules.push({ id: state.nextRuleId++, keyword, category });
      if (apply !== "true") return { ok: true, updated: 0 };
      moving.forEach((t) => { t.category = category; });
      return { ok: true, updated: moving.length };
    }
    if (p === "/api/rules/preview") {
      const keyword = params.get("keyword");
      const category = params.get("category");
      if (!keyword.trim()) return bad("La palabra clave no puede estar vacía");
      const { matches, already, moving, otherRule } = ruleImpact(keyword, category);
      const from = {};
      moving.forEach((t) => { from[t.category] = (from[t.category] || 0) + 1; });
      const existing = state.rules.find((r) => r.keyword.toLowerCase() === keyword.toLowerCase());
      return { matches, already, would_change: moving.length, from, other_rule: otherRule, existing_rule: existing ? existing.category : null };
    }
    const rule = p.match(/^\/api\/rules\/(\d+)$/);
    if (rule && method === "DELETE") {
      state.rules = state.rules.filter((r) => r.id !== Number(rule[1]));
      return { ok: true };
    }
    if (p === "/api/rules/apply-all") {
      let updated = 0;
      for (const t of state.transactions.filter((tx) => tx.category === "Sin categorizar")) {
        const match = state.rules.find((r) => ` ${t.description.toLowerCase()} `.includes(r.keyword.toLowerCase()));
        if (match) { t.category = match.category; updated++; }
      }
      return { updated };
    }
    // copies, like JSON from a real server: the page must not share objects with the fake's state
    const copy = (value) => JSON.parse(JSON.stringify(value));
    if (p === "/api/prices" && method === "GET") return copy(state.products);
    if (p === "/api/prices" && method === "POST") {
      const { url, target_price } = call.form();
      if (!url.trim()) return bad("Pega el enlace del producto.");
      if (state.products.some((x) => x.url === url)) return bad("Ya estás siguiendo ese producto.");
      const read = state.shop(url);
      if (read.error) return { status: 422, body: { detail: read.error } };
      const now = new Date().toISOString();
      const product = {
        id: state.nextProductId++, url, name: read.name, store: new URL(url).hostname.replace(/^www\./, ""),
        currency: read.currency || "EUR", price: read.price, available: read.available ?? null,
        target_price: target_price ? Number(target_price) : null, previous_price: null, changed_at: null,
        restocked_at: null, lowest_price: read.price, highest_price: read.price, created_at: now, checked_at: now,
        last_error: null, last_error_at: null,
      };
      state.products.unshift(product);
      state.histories[product.id] = [{ date: now, price: read.price, available: product.available }];
      return copy(product);
    }
    const priced = p.match(/^\/api\/prices\/(\d+)(\/check|\/history)?$/);
    if (priced) {
      const product = state.products.find((x) => x.id === Number(priced[1]));
      if (!product) return { status: 404, body: { detail: "Ese producto ya no está en la lista." } };
      if (method === "PATCH") {
        const { target_price } = call.form();
        if (target_price && Number(target_price) <= 0) return bad("El precio objetivo tiene que ser mayor que 0.");
        product.target_price = target_price ? Number(target_price) : null;
        return copy(product);
      }
      if (method === "DELETE") {
        state.products = state.products.filter((x) => x !== product);
        return { ok: true };
      }
      if (priced[2] === "/history") return copy(state.histories[product.id] || [{ date: product.created_at, price: product.price }]);
      if (priced[2] === "/check") {
        const read = state.shop(product.url);
        const now = new Date().toISOString();
        if (read.error) return copy(Object.assign(product, { last_error: read.error, last_error_at: now }));
        const available = read.available ?? null;
        if (available === true && product.available === false) product.restocked_at = now;
        else if (available !== true) product.restocked_at = null;
        if (read.price !== product.price) {
          Object.assign(product, { previous_price: product.price, changed_at: now });
          (state.histories[product.id] = state.histories[product.id] || []).push({ date: now, price: read.price });
        }
        return copy(Object.assign(product, {
          price: read.price, available, checked_at: now, last_error: null, last_error_at: null,
          lowest_price: Math.min(product.lowest_price, read.price), highest_price: Math.max(product.highest_price, read.price),
        }));
      }
    }
    if (p === "/api/recurring" && method === "GET") return recurringReport();
    if (p === "/api/recurring/hidden" && method === "POST") { state.recurringHidden.add(call.form().key); return { ok: true }; }
    if (p === "/api/recurring/hidden" && method === "DELETE") { state.recurringHidden.delete(params.get("key")); return { ok: true }; }
    if (p === "/api/auth/whoami") return { username: "admin" };
    if (p === "/api/auth/change-password") {
      const { current_password, new_password } = call.json();
      if (current_password !== state.password) return bad("La contraseña actual no es correcta");
      state.password = new_password;
      state.passwordChanges.push(new_password);
      return { ok: true };
    }
    if (p === "/api/summary/months-available") return ["2026-03", "2026-02"];
    if (p === "/api/summary") {
      // `summary` may be a function of the query (to answer differently per month) or a fixed object
      return (typeof summary === "function" ? summary(params) : summary) || { income: 1850, expense: 600.5, balance: 1249.5, count: 12 };
    }
    if (p === "/api/summary/monthly") return [];
    if (p === "/api/summary/by-category") return byCategory;
    if (p === "/api/summary/balance-history") return [];
    if (p === "/api/summary/current-balance") {
      return currentBalance || { total: 2714.16, date: "2026-03-31", accounts: [{ account: "Principal", balance: 2714.16, date: "2026-03-31" }] };
    }
    return undefined;
  }

  return { state, handler };
}

module.exports = { loadApp, fakeBackend, makeTransactions, tick, wait };
