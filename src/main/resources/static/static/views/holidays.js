// Holidays (#holidays): long weekends and the two calendars.
// Loaded after app.js (shared helpers and navigation); see index.html for the order.

// ---------- Holidays ----------
// The server keeps the holidays (BOE, Generalitat, and the ones typed in) for the user's two places: "work",
// which decides their days off, and "home", a second calendar to plan visits by. The long weekends are worked
// out here, counting Saturday and Sunday as days off (the user works Monday to Friday).
const HOLIDAY_WINDOW_DAYS = 365;
const MAX_BRIDGE_DAYS = 2; // more vacation days than this is a holiday, not a "puente"
let holidayData = null;
let holidayTowns = null;
let holidaysRun = 0;
let holidayTodayOverride = null; // tests pin "today"

function holidayToday() {
  return holidayTodayOverride || localIsoDate(new Date());
}

const isoDate = (iso) => new Date(`${iso}T00:00:00`);
const isWeekendIso = (iso) => [0, 6].includes(isoDate(iso).getDay());

// "mar 8 dic"
function holidayDayLabel(iso) {
  return isoDate(iso).toLocaleDateString("es-ES", { weekday: "short", day: "numeric", month: "short" });
}

// "martes 8 de diciembre" (the locale would put a comma after the weekday)
function holidayLongLabel(iso) {
  const d = isoDate(iso);
  return `${d.toLocaleDateString("es-ES", { weekday: "long" })} ${d.toLocaleDateString("es-ES", { day: "numeric", month: "long" })}`;
}

// The block of consecutive days off around `iso`, given which days are off.
function offRun(iso, isOff) {
  let from = iso;
  let to = iso;
  while (isOff(addDaysIso(from, -1))) from = addDaysIso(from, -1);
  while (isOff(addDaysIso(to, 1))) to = addDaysIso(to, 1);
  return { from, to, days: Math.round((isoDate(to) - isoDate(from)) / 86400000) + 1 };
}

// What a weekday holiday gives: the days off it already makes (`base`) and, when one or two working days
// between it and a weekend (or another holiday) join them up, the best such "puente" (`bridge`: the days to
// take and the block that makes). A holiday on a weekend gives nothing.
function bridgeFor(iso, holidays) {
  if (isWeekendIso(iso)) return { weekend: true };
  const isOff = (d) => isWeekendIso(d) || holidays.has(d);
  const base = offRun(iso, isOff);
  let bridge = null;
  for (const step of [-1, 1]) {
    const gap = [];
    let d = addDaysIso(step < 0 ? base.from : base.to, step);
    while (!isOff(d) && gap.length <= MAX_BRIDGE_DAYS) {
      gap.push(d);
      d = addDaysIso(d, step);
    }
    if (gap.length === 0 || gap.length > MAX_BRIDGE_DAYS) continue;
    const taken = new Set(gap);
    const run = offRun(iso, (x) => isOff(x) || taken.has(x));
    const option = { take: gap.sort(), run };
    if (!bridge || run.days > bridge.run.days || (run.days === bridge.run.days && gap.length < bridge.take.length)) bridge = option;
  }
  return { base, bridge };
}

function runLabel(run) {
  return `${holidayDayLabel(run.from)} – ${holidayDayLabel(run.to)}`;
}

// "el lunes 7 de diciembre", "el lunes 8 y el martes 9 de …"
function takeLabel(days) {
  return days.map((d) => `el ${holidayLongLabel(d)}`).join(" y ");
}

function holidayPlaceName(place) {
  return place.town_name || place.region_name;
}

function holidayKindLabel(day, place) {
  if (day.kind === "national") return "España";
  if (day.kind === "region") return place.region_name;
  return place.town_name || "Local";
}

// A Generalitat local holiday comes without a name.
function holidayName(day, place) {
  return day.source === "generalitat" ? `Fiesta local de ${holidayPlaceName(place)}` : day.name;
}

function upcoming(days, today) {
  const end = addDaysIso(today, HOLIDAY_WINDOW_DAYS);
  return days.filter((d) => d.date >= today && d.date <= end);
}

function nextHolidayHtml(work, today) {
  const next = upcoming(work.days, today)[0];
  if (!next) return economyCardHtml({ title: "Próximo festivo", value: "—", sub: "No hay festivos publicados en los próximos 12 meses" });
  const days = Math.round((isoDate(next.date) - isoDate(today)) / 86400000);
  const when = days === 0 ? "hoy" : days === 1 ? "mañana" : `dentro de ${days} días`;
  const set = new Set(work.days.map((d) => d.date));
  const b = bridgeFor(next.date, set);
  let tip = "Cae en fin de semana";
  if (!b.weekend) {
    tip = b.base.days >= 3 ? `${b.base.days} días libres seguidos (${runLabel(b.base)})` : "Un día suelto";
    if (b.bridge) tip += ` · si coges ${takeLabel(b.bridge.take)}: ${b.bridge.run.days} días`;
  }
  return economyCardHtml({
    title: "Próximo festivo",
    value: holidayName(next, work),
    sub: `${holidayLongLabel(next.date)} · ${when}`,
    change: tip,
  });
}

function bridgesHtml(work, today) {
  const set = new Set(work.days.map((d) => d.date));
  const items = upcoming(work.days, today).map((day) => {
    const b = bridgeFor(day.date, set);
    let tip;
    if (b.weekend) tip = "Cae en fin de semana";
    else if (b.bridge) tip = `Coge ${takeLabel(b.bridge.take)} → <strong>${b.bridge.run.days} días libres</strong> (${escapeHtml(runLabel(b.bridge.run))})`;
    else if (b.base.days >= 3) tip = `<strong>${b.base.days} días libres</strong> sin coger nada (${escapeHtml(runLabel(b.base))})`;
    else tip = "Un día suelto, sin puente posible";
    return `<li><span class="holiday-when">${escapeHtml(holidayDayLabel(day.date))}</span> · ${escapeHtml(holidayName(day, work))}` +
      `<span class="holiday-tip">${tip}</span></li>`;
  });
  return items.join("") || '<li class="muted">No hay festivos publicados en los próximos 12 meses.</li>';
}

// Holidays at home that are working days here: what taking one gives (with the weekends and holidays around it).
function homeOnlyHtml(work, home, today) {
  const workSet = new Set(work.days.map((d) => d.date));
  const items = upcoming(home.days, today)
    .filter((d) => !workSet.has(d.date) && !isWeekendIso(d.date))
    .map((day) => {
      const run = offRun(day.date, (x) => x === day.date || isWeekendIso(x) || workSet.has(x));
      const tip = run.days > 1
        ? `Si lo coges: <strong>${run.days} días</strong> allí (${escapeHtml(runLabel(run))})`
        : "Si lo coges: un día suelto";
      return `<li><span class="holiday-when">${escapeHtml(holidayDayLabel(day.date))}</span> · ${escapeHtml(holidayName(day, home))}` +
        `<span class="holiday-tip">${tip}</span></li>`;
    });
  return items.join("") || '<li class="muted">Ninguno en los próximos 12 meses.</li>';
}

function holidayCalendarHtml(work, home, today) {
  const byDate = new Map();
  for (const [place, role] of [[work, "work"], [home, "home"]]) {
    if (!place) continue;
    for (const day of place.days) {
      const entry = byDate.get(day.date) || { date: day.date, names: [], chips: [] };
      const name = holidayName(day, place);
      if (!entry.names.includes(name)) entry.names.push(name);
      entry.chips.push(`<span class="holiday-chip${role === "home" ? " home" : ""}">${escapeHtml(holidayPlaceName(place))}</span>`);
      byDate.set(day.date, entry);
    }
  }
  const months = new Map();
  [...byDate.values()].sort((a, b) => a.date.localeCompare(b.date)).forEach((e) => {
    const key = e.date.slice(0, 7);
    if (!months.has(key)) months.set(key, []);
    months.get(key).push(e);
  });
  return [...months.entries()].map(([month, entries]) =>
    `<div class="holiday-month"><h4>${escapeHtml(economyPeriodLabel(month))}</h4><ul>` +
    entries.map((e) => `<li class="${e.date < today ? "past" : ""}"><span class="holiday-day">${escapeHtml(holidayDayLabel(e.date))}</span>` +
      `<span>${escapeHtml(e.names.join(" / "))}</span>${e.chips.join("")}</li>`).join("") +
    "</ul></div>").join("");
}

function holidayNotes(data) {
  const notes = [];
  for (const y of data.years) {
    if (!y.published) notes.push(`Los festivos de ${y.year} aún no se han publicado en el BOE (suele ser en octubre o noviembre del año anterior).`);
    else if (y.locals_published === false) notes.push(`Las fiestas locales de ${y.year} de ${holidayPlaceName(data.work)} aún no están publicadas.`);
  }
  if (data.home && !data.home.days.some((d) => d.source === "manual")) {
    notes.push(`Apunta las fiestas locales de ${holidayPlaceName(data.home)} en «Tus calendarios».`);
  }
  if (data.last_error) notes.push(`Última actualización fallida: ${data.last_error}`);
  return notes.join(" ");
}

function renderHolidays() {
  const data = holidayData;
  const today = holidayToday();
  const content = document.getElementById("holidays-content");
  const settings = document.getElementById("holiday-settings");
  fillHolidaySettings(data);
  if (!data.work) {
    content.hidden = true;
    settings.open = true;
    return;
  }
  content.hidden = false;
  document.getElementById("holiday-next").innerHTML = nextHolidayHtml(data.work, today);
  document.getElementById("holiday-notes").textContent = holidayNotes(data);
  document.getElementById("holiday-work-name").textContent = holidayPlaceName(data.work);
  document.getElementById("holiday-bridges").innerHTML = bridgesHtml(data.work, today);
  document.getElementById("holiday-home-panel").hidden = !data.home;
  if (data.home) {
    document.getElementById("holiday-home-name").textContent = holidayPlaceName(data.home);
    document.getElementById("holiday-home-only").innerHTML = homeOnlyHtml(data.work, data.home, today);
  }
  document.getElementById("holiday-calendar").innerHTML = holidayCalendarHtml(data.work, data.home, today);
}

function fillHolidaySettings(data) {
  const regionOptions = data.regions.map((r) => optionHtml(r.code, r.name)).join("");
  const workRegion = document.getElementById("hol-work-region");
  const homeRegion = document.getElementById("hol-home-region");
  workRegion.innerHTML = regionOptions;
  homeRegion.innerHTML = optionHtml("", "Ninguno") + regionOptions;
  workRegion.value = data.work ? data.work.region : "CT";
  homeRegion.value = data.home ? data.home.region : "";
  document.getElementById("hol-work-town").value = data.work ? data.work.town_name || "" : "";
  document.getElementById("hol-home-town").value = data.home ? data.home.town_name || "" : "";
  updateWorkTownField();
  const roles = [["work", data.work], ["home", data.home]].filter(([, p]) => p);
  document.getElementById("hol-day-role").innerHTML = roles.map(([role, p]) => optionHtml(role, holidayPlaceName(p))).join("");
  document.getElementById("holiday-day-form").hidden = roles.length === 0;
  const manual = roles.flatMap(([role, p]) => p.days.filter((d) => d.source === "manual").map((d) => ({ ...d, place: holidayPlaceName(p) })));
  document.getElementById("holiday-manual").innerHTML = manual.map((d) =>
    `<li>${escapeHtml(holidayDayLabel(d.date))} ${escapeHtml(d.date.slice(0, 4))} · ${escapeHtml(d.name)} <span class="muted">(${escapeHtml(d.place)})</span>` +
    `<button type="button" class="small-btn holiday-manual-delete" data-id="${escapeAttr(String(d.id))}">Quitar</button></li>`).join("");
}

// Only Catalan towns have their local holidays published: the town field is a picker there, plain text elsewhere.
async function updateWorkTownField() {
  const catalan = document.getElementById("hol-work-region").value === "CT";
  document.getElementById("hol-work-town").setAttribute("list", catalan ? "hol-towns" : "");
  if (catalan && !holidayTowns) {
    try {
      holidayTowns = await api("/api/holidays/towns");
      document.getElementById("hol-towns").innerHTML = holidayTowns.map((t) => optionHtml(t.name)).join("");
    } catch (e) { /* the field still takes a name; saving says if it isn't known */ }
  }
}

document.getElementById("hol-work-region").addEventListener("change", updateWorkTownField);

document.getElementById("holiday-places-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  clearFeedback("holiday-places-feedback");
  const workRegion = document.getElementById("hol-work-region").value;
  const workTown = document.getElementById("hol-work-town").value.trim();
  const params = new URLSearchParams({ region: workRegion, town_name: workTown });
  if (workRegion === "CT" && workTown) {
    const town = (holidayTowns || []).find((t) => t.name.toLowerCase() === workTown.toLowerCase());
    if (!town) {
      setFeedback("holiday-places-feedback", `No encuentro «${workTown}» entre los municipios de Cataluña: elígelo de la lista.`);
      return;
    }
    params.set("town_code", town.code);
    params.set("town_name", town.name);
  }
  try {
    holidayData = await api("/api/holidays/places/work", { method: "PUT", body: params });
    const homeRegion = document.getElementById("hol-home-region").value;
    if (homeRegion) {
      holidayData = await api("/api/holidays/places/home", {
        method: "PUT", body: new URLSearchParams({ region: homeRegion, town_name: document.getElementById("hol-home-town").value.trim() }),
      });
    }
  } catch (err) {
    setFeedback("holiday-places-feedback", err.message);
    return;
  }
  renderHolidays();
  setFeedback("holiday-places-feedback", "Guardado.", "ok");
});

document.getElementById("holiday-day-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  clearFeedback("holiday-day-feedback");
  const body = new URLSearchParams({
    role: document.getElementById("hol-day-role").value,
    date: document.getElementById("hol-day-date").value,
    name: document.getElementById("hol-day-name").value.trim(),
  });
  try {
    await api("/api/holidays/days", { method: "POST", body });
  } catch (err) {
    setFeedback("holiday-day-feedback", err.message);
    return;
  }
  document.getElementById("hol-day-name").value = "";
  await loadHolidaysView();
  setFeedback("holiday-day-feedback", "Añadido.", "ok");
});

document.getElementById("holiday-manual").addEventListener("click", async (e) => {
  const button = e.target.closest(".holiday-manual-delete");
  if (!button) return;
  try {
    await api(`/api/holidays/days/${encodeURIComponent(button.dataset.id)}`, { method: "DELETE" });
  } catch (err) {
    setFeedback("holiday-day-feedback", err.message);
    return;
  }
  await loadHolidaysView();
});

async function loadHolidaysView() {
  const run = ++holidaysRun;
  const data = await api("/api/holidays");
  if (run !== holidaysRun) return;
  holidayData = data;
  renderHolidays();
}
