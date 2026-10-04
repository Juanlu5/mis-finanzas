// Prices (#prices): product links whose price the server watches.
// Loaded after app.js (shared helpers and navigation); see index.html for the order.

// ---------- Prices ----------
// Products whose price the server watches (it reads each page every few hours). A card deserves attention
// when its price dropped or it came back in stock in the last PRICE_NEWS_DAYS, or it is at/below the user's
// target while it can be bought; the menu entry counts those.
const PRICE_NEWS_DAYS = 7;
let priceProducts = [];
let pricesRun = 0;
const openPriceCharts = new Set();
const priceCharts = new Map();

function fmtPrice(n, currency = "EUR") {
  try {
    return new Intl.NumberFormat("es-ES", { style: "currency", currency }).format(n);
  } catch (e) {
    return fmtMoney(n); // a currency code Intl doesn't know
  }
}

function priceNews(p, now = Date.now()) {
  const dropped = p.previous_price != null && p.price < p.previous_price;
  const recentDrop = dropped && now - new Date(p.changed_at).getTime() <= PRICE_NEWS_DAYS * 86400000;
  const atTarget = p.target_price != null && p.price <= p.target_price && p.available !== false;
  const restocked = p.restocked_at != null && now - new Date(p.restocked_at).getTime() <= PRICE_NEWS_DAYS * 86400000;
  return { dropped, atTarget, restocked, news: recentDrop || atTarget || restocked };
}

function updatePriceBadge(products) {
  const n = products.filter((p) => priceNews(p).news).length;
  const badge = document.getElementById("prices-badge");
  badge.textContent = n;
  badge.title = n === 1 ? "1 producto con novedades" : `${n} productos con novedades`;
  badge.hidden = n === 0;
}

// At start-up, for the menu badge. A failure here isn't worth an error toast on every page load.
async function refreshPriceBadge() {
  try {
    updatePriceBadge(await api("/api/prices"));
  } catch (e) { /* the Prices view says it when opened */ }
}

async function loadPricesView() {
  const run = ++pricesRun;
  const products = await api("/api/prices");
  if (run !== pricesRun) return;
  priceProducts = products;
  renderPrices();
}

function renderPrices() {
  priceCharts.forEach((chart) => chart.destroy());
  priceCharts.clear();
  const list = document.getElementById("price-list");
  list.innerHTML = priceProducts.map(priceCardHtml).join("");
  document.getElementById("price-empty").hidden = priceProducts.length > 0;
  const news = priceProducts.filter((p) => priceNews(p).news).length;
  document.getElementById("price-summary").textContent = news === 0 ? ""
    : news === 1 ? "1 producto con novedades: ha bajado de precio, está por debajo de tu objetivo o ha vuelto a haber stock."
    : `${news} productos con novedades: han bajado de precio, están por debajo de tu objetivo o ha vuelto a haber stock.`;
  updatePriceBadge(priceProducts);
  for (const id of openPriceCharts) {
    if (priceProducts.some((p) => p.id === id)) drawPriceChart(id);
    else openPriceCharts.delete(id);
  }
}

function priceCardHtml(p) {
  const cur = p.currency;
  const { dropped, atTarget, restocked, news } = priceNews(p);
  const change = p.previous_price == null ? "" : `
    <div class="price-change ${dropped ? "down" : "up"}">
      <span class="arrow" aria-hidden="true">${dropped ? "▼" : "▲"}</span>${dropped ? "Ha bajado" : "Ha subido"}
      ${fmtPrice(Math.abs(p.price - p.previous_price), cur)} (antes ${fmtPrice(p.previous_price, cur)}) · ${timeAgo(p.changed_at)}
    </div>`;
  const badges = [];
  if (restocked) badges.push(`<span class="badge good">Ha vuelto a haber stock · ${timeAgo(p.restocked_at)}</span>`);
  if (atTarget) badges.push(`<span class="badge good">Por debajo de tu objetivo de ${fmtPrice(p.target_price, cur)}</span>`);
  else if (p.target_price != null) badges.push(`<span class="badge">Objetivo: ${fmtPrice(p.target_price, cur)}</span>`);
  if (p.available === false) badges.push(`<span class="badge warn">Agotado</span>`);
  const range = p.lowest_price === p.highest_price
    ? `Mismo precio desde que lo sigues (${timeAgo(p.created_at)})`
    : `Mínimo ${fmtPrice(p.lowest_price, cur)} · Máximo ${fmtPrice(p.highest_price, cur)}`;
  const error = p.last_error ? `
    <p class="price-error">La última comprobación falló (${timeAgo(p.last_error_at)}): ${escapeHtml(p.last_error)}
      El precio de arriba es el de ${timeAgo(p.checked_at)}.</p>` : "";
  const chartOpen = openPriceCharts.has(p.id);
  return `
    <article class="price-card${news ? " news" : ""}" data-id="${p.id}">
      <div class="price-card-head">
        <div class="price-title">
          <a class="price-name" href="${escapeAttr(p.url)}" target="_blank" rel="noopener noreferrer">${escapeHtml(p.name)}</a>
          <div class="muted price-store">${escapeHtml(p.store)}</div>
        </div>
        <div class="price-now">
          <div class="value">${fmtPrice(p.price, cur)}</div>
          ${change}
        </div>
      </div>
      <div class="price-badges">${badges.join("")}</div>
      <div class="price-meta muted">${range} · Comprobado ${timeAgo(p.checked_at)}</div>
      ${error}
      <div class="price-actions">
        <button type="button" class="ghost" data-action="chart" aria-expanded="${chartOpen}">${chartOpen ? "Ocultar gráfico" : "Ver gráfico"}</button>
        <button type="button" class="ghost" data-action="target">${p.target_price == null ? "Poner precio objetivo" : "Cambiar objetivo"}</button>
        <button type="button" class="ghost" data-action="check">Comprobar ahora</button>
        <button type="button" class="ghost danger" data-action="delete">Dejar de seguir</button>
      </div>
      <form class="price-target-form" hidden>
        <label for="price-target-${p.id}">Avísame si baja de</label>
        <input type="text" id="price-target-${p.id}" inputmode="decimal" autocomplete="off" placeholder="ej.: 59,90" />
        <button class="primary" type="submit">Guardar</button>
        ${p.target_price == null ? "" : `<button type="button" class="ghost" data-action="target-clear">Quitar objetivo</button>`}
        <button type="button" class="ghost" data-action="target-cancel">Cancelar</button>
        <p class="price-target-msg" role="status"></p>
      </form>
      <div class="price-chart"${chartOpen ? "" : " hidden"}>
        <div class="chart-wrap chart-wrap-price"><canvas aria-label="Evolución del precio de ${escapeAttr(p.name)}"></canvas></div>
        <p class="hint"></p>
      </div>
    </article>`;
}

async function drawPriceChart(id) {
  const box = document.querySelector(`.price-card[data-id="${id}"] .price-chart`);
  const product = priceProducts.find((p) => p.id === id);
  if (!box || !product) return;
  const history = await api(`/api/prices/${id}/history`);
  // closed, or the list was re-rendered (which draws it again), while this was loading
  if (!openPriceCharts.has(id) || !box.isConnected) return;
  const hint = box.querySelector(".hint");
  const canvas = box.querySelector("canvas");
  if (priceCharts.has(id)) { priceCharts.get(id).destroy(); priceCharts.delete(id); }
  // one price is a dot, not a chart (and a time axis over a few milliseconds reads "11:26:13.292")
  if (history.length < 2) {
    canvas.parentElement.hidden = true;
    hint.textContent = `De momento solo hay un precio (${fmtPrice(product.price, product.currency)} desde ${timeAgo(history.length
      ? history[0].date : product.created_at)}): el gráfico aparecerá cuando cambie.`;
    return;
  }
  canvas.parentElement.hidden = false;
  hint.textContent = "";
  // a point is stored only when the price changes: the last one holds until the last check
  const data = history.map((pt) => ({ x: pt.date, y: pt.price }));
  data.push({ x: product.checked_at, y: product.price });
  const colors = themeColors();
  priceCharts.set(id, new Chart(canvas, {
    type: "line",
    data: {
      datasets: [{
        label: "Precio",
        data,
        stepped: true,
        borderColor: colors.primary,
        backgroundColor: withAlpha(colors.primary, 0.12),
        fill: true,
        // the last point only extends the line to the last check: no dot, no tooltip of its own
        pointRadius: data.map((_, i) => (i === data.length - 1 ? 0 : 3)),
        pointHitRadius: data.map((_, i) => (i === data.length - 1 ? 0 : 6)),
        pointHoverRadius: 5,
        borderWidth: 2,
      }],
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      interaction: { mode: "nearest", intersect: false },
      plugins: {
        legend: { display: false },
        tooltip: { callbacks: { label: (ctx) => ` ${fmtPrice(ctx.parsed.y, product.currency)}` } },
      },
      scales: {
        x: {
          type: "time",
          time: { tooltipFormat: "dd/MM/yyyy HH:mm", minUnit: "minute" },
          adapters: { date: { locale: window.dateFns && window.dateFns.locale && window.dateFns.locale.es } },
          ticks: { color: colors.muted, maxTicksLimit: 8, font: monoFont },
          grid: { display: false },
        },
        y: {
          ticks: { color: colors.muted, font: monoFont, callback: (v) => fmtPrice(v, product.currency) },
          grid: { color: colors.border },
        },
      },
    },
  }));
}

function replacePriceProduct(product) {
  priceProducts = priceProducts.map((p) => (p.id === product.id ? product : p));
  renderPrices();
}

async function savePriceTarget(id, value, form) {
  const message = form.querySelector(".price-target-msg");
  try {
    replacePriceProduct(await api(`/api/prices/${id}`, {
      method: "PATCH",
      body: new URLSearchParams({ target_price: value == null ? "" : String(value) }),
    }));
    toast(value == null ? "Objetivo quitado" : `Te avisará si baja de ${fmtPrice(value)}`);
  } catch (err) {
    message.textContent = err.message;
  }
}

document.getElementById("price-list").addEventListener("click", async (e) => {
  const button = e.target.closest("button[data-action]");
  if (!button) return;
  const card = button.closest(".price-card");
  const id = Number(card.dataset.id);
  const product = priceProducts.find((p) => p.id === id);
  const form = card.querySelector(".price-target-form");
  switch (button.dataset.action) {
    case "chart":
      if (openPriceCharts.has(id)) {
        openPriceCharts.delete(id);
        if (priceCharts.has(id)) { priceCharts.get(id).destroy(); priceCharts.delete(id); }
        card.querySelector(".price-chart").hidden = true;
        button.textContent = "Ver gráfico";
        button.setAttribute("aria-expanded", "false");
      } else {
        openPriceCharts.add(id);
        card.querySelector(".price-chart").hidden = false;
        button.textContent = "Ocultar gráfico";
        button.setAttribute("aria-expanded", "true");
        await drawPriceChart(id);
      }
      break;
    case "target": {
      form.hidden = false;
      const input = form.querySelector("input");
      input.value = product.target_price == null ? "" : String(product.target_price).replace(".", ",");
      input.focus();
      break;
    }
    case "target-cancel":
      form.hidden = true;
      break;
    case "target-clear":
      await savePriceTarget(id, null, form);
      break;
    case "check":
      button.disabled = true;
      button.textContent = "Comprobando…";
      try {
        const checked = await api(`/api/prices/${id}/check`, { method: "POST" });
        replacePriceProduct(checked);
        toast(checked.last_error ? "No se ha podido comprobar: mira el aviso en la tarjeta" : "Precio comprobado");
      } catch (err) {
        button.disabled = false;
        button.textContent = "Comprobar ahora";
        throw err;
      }
      break;
    case "delete":
      if (!confirm(`¿Dejar de seguir «${product.name}»?\n\nSe borra también su historial de precios.`)) return;
      await api(`/api/prices/${id}`, { method: "DELETE" });
      openPriceCharts.delete(id);
      priceProducts = priceProducts.filter((p) => p.id !== id);
      renderPrices();
      toast("Producto eliminado de la lista");
      break;
  }
});

document.getElementById("price-list").addEventListener("submit", async (e) => {
  e.preventDefault();
  const form = e.target;
  const id = Number(form.closest(".price-card").dataset.id);
  const input = form.querySelector("input");
  const value = parseAmountFilter(input.value);
  if (Number.isNaN(value)) {
    form.querySelector(".price-target-msg").textContent = `«${input.value.trim()}» no es un precio válido (ej.: 45 o 45,30).`;
    input.setAttribute("aria-invalid", "true");
    return;
  }
  await savePriceTarget(id, value, form);
});

document.getElementById("form-add-price").addEventListener("submit", async (e) => {
  e.preventDefault();
  const urlInput = document.getElementById("price-url");
  const targetInput = document.getElementById("price-target");
  const button = document.getElementById("price-add-btn");
  const url = urlInput.value.trim();
  const target = parseAmountFilter(targetInput.value);
  targetInput.removeAttribute("aria-invalid");
  if (!url) {
    setFeedback("price-feedback", "Pega el enlace del producto.");
    return;
  }
  if (Number.isNaN(target)) {
    targetInput.setAttribute("aria-invalid", "true");
    setFeedback("price-feedback", `«${targetInput.value.trim()}» no es un precio válido (ej.: 45 o 45,30).`);
    return;
  }
  button.disabled = true;
  button.textContent = "Leyendo la página…";
  clearFeedback("price-feedback");
  try {
    const product = await api("/api/prices", {
      method: "POST",
      body: new URLSearchParams({ url, target_price: target == null ? "" : String(target) }),
    });
    priceProducts = [product, ...priceProducts];
    renderPrices();
    urlInput.value = "";
    targetInput.value = "";
    setFeedback("price-feedback", `Siguiendo «${product.name}»: ahora cuesta ${fmtPrice(product.price, product.currency)}.`, "ok");
  } catch (err) {
    setFeedback("price-feedback", err.message);
  } finally {
    button.disabled = false;
    button.textContent = "Seguir precio";
  }
});
