"use strict";

const keyStates = {
  active: ["啟用中", "success", "check"], paused: ["已暫停", "quiet", "pause"],
  expired: ["已到期", "danger", "clock"], revoked: ["已撤銷", "quiet", "ban"]
};
const consoleState = {edit: null, usage: null, items: [], filter: null, read: null, mode: "requests"};

function badge(text, tone = "neutral", glyph) {
  const node = el("span", text, "badge");
  node.dataset.tone = tone;
  if (glyph) node.prepend(icon(glyph));
  return node;
}
function meter(value, maximum, label) {
  const node = el("div", undefined, "meter");
  node.setAttribute("role", "meter");
  node.setAttribute("aria-label", label);
  node.setAttribute("aria-valuemin", "0");
  node.setAttribute("aria-valuemax", String(maximum));
  node.setAttribute("aria-valuenow", String(Math.min(value, maximum)));
  const bar = el("span");
  bar.style.width = Math.min(100, 100 * value / maximum) + "%";
  node.append(bar);
  return node;
}
function keyState(key) {
  if (!key.enabled) return "revoked";
  if (key.expires_at !== null && key.expires_at <= Date.now()) return "expired";
  return key.paused ? "paused" : "active";
}
function renderKeys() {
  const term = $("key-search").value.trim().toLowerCase();
  const filter = $("key-status").value;
  const visible = state.keys.filter(k => (!filter || keyState(k) === filter) && (k.label + " " + k.prefix).toLowerCase().includes(term));
  $("key-count").textContent = visible.length + " 把 Key";
  const rows = visible.map(k => {
    const card = el("article", undefined, "key-record");
    const header = el("div", undefined, "section-inline");
    const name = el("div");
    name.append(el("h3", k.label), el("code", k.prefix + "…"));
    header.append(name, badge(...keyStates[keyState(k)]));
    const policy = el("div", undefined, "key-policy");
    const quota = metric("今日請求", number(k.used_today) + " / " + number(k.daily_limit));
    quota.append(meter(k.used_today, k.daily_limit, "今日請求量"));
    policy.append(quota, metric("到期時間", k.expires_at == null ? "持續有效" : localDate(k.expires_at)));
    card.append(header, policy, el("p", k.models.join("、"), "quiet"));
    const history = button("查看此 Key 用量", () => showPage("usage", () => showUsage(k.id)), {glyph: "usage", iconOnly: true});
    const actions = controls(history);
    if (k.enabled) {
      actions.prepend(button("編輯", () => openKeyEditor(k), {glyph: "edit", iconOnly: true}));
      const pause = button(k.paused ? "恢復" : "暫停", async () => {
        await api("/gateway/keys/pause", {id: k.id, paused: !k.paused});
        await loadShare();
        notice(k.paused ? "Key 已恢復。" : "Key 已暫停。");
      }, {glyph: k.paused ? "play" : "pause"});
      const revoke = button("撤銷", async () => {
        if (!window.confirm("要撤銷「" + k.label + "」嗎？這把 Key 會立即失效。")) return;
        await api("/gateway/keys/revoke", {id: k.id});
        await loadShare();
        notice("分發 Key 已撤銷。");
      }, {glyph: "ban"});
      revoke.classList.add("danger-button");
      actions.append(pause, revoke);
    }
    card.append(actions);
    return card;
  });
  $("key-list").replaceChildren(...(rows.length ? rows : [empty(state.keys.length ? "沒有符合條件的 Key。" : "還沒有分發 Key。")]));
}

function localDate(at, zone) {
  return new Intl.DateTimeFormat("zh-TW", {timeZone: zone, dateStyle: "short", timeStyle: "short"}).format(at);
}
function keySelection() {
  const list = $("key-models");
  const term = $("key-model-search").value.trim().toLowerCase();
  for (const label of list.querySelectorAll(".choice")) label.hidden = !label.textContent.toLowerCase().includes(term);
  const count = list.querySelectorAll("input:checked").length;
  $("key-selected").textContent = "已選 " + count + " 個模型";
  $("issue-key").disabled = count === 0;
}
function openKeyEditor(key) {
  consoleState.edit = key || null;
  const form = $("key-form");
  form.reset();
  $("key-model-search").value = "";
  $("key-editor-title").textContent = key ? "編輯分發 Key" : "建立分發 Key";
  $("issue-key-label").textContent = key ? "儲存設定" : "產生 Key";
  const selected = new Set(key?.models ?? []);
  const available = state.models.filter(v => v.enabled || selected.has(v.id));
  if (key) {
    form.elements.label.value = key.label;
    form.elements.daily_limit.value = key.daily_limit;
    if (key.expires_at != null) {
      const date = new Date(key.expires_at);
      form.elements.expires_at.value = new Date(key.expires_at - date.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
    }
  }
  const labels = available.map(v => {
    const label = el("label", undefined, "choice");
    const check = el("input");
    check.type = "checkbox";
    check.name = "model";
    check.value = v.id;
    check.checked = selected.has(v.id);
    label.append(check, el("span", v.id + (v.enabled ? "" : " · 已停用")));
    return label;
  });
  $("key-models").replaceChildren(...(labels.length ? labels : [empty("先發布至少一個模型，就能發出 Key。")]));
  keySelection();
  $("key-editor").showModal();
}

function syncUsageKeyFilter() {
  $("usage-key").disabled = $("usage-source").value === "tests";
}
async function showUsage(keyID) {
  if (!keyID) state.keys = await api("/gateway/keys");
  putOptions($("usage-key"), [["", "全部 Key"], ...state.keys.map(k => [k.id, k.label])]);
  if (keyID) {
    $("usage-source").value = "gateway";
    $("usage-key").value = keyID;
    $("usage-model").value = "";
    $("usage-status").value = "";
  }
  syncUsageKeyFilter();
  return loadUsage(true);
}
async function loadUsage(reset) {
  consoleState.read?.abort();
  const read = new AbortController();
  consoleState.read = read;
  const offset = reset ? 0 : consoleState.items.length;
  const filter = reset ? {
    range: $("usage-range").value,
    source: $("usage-source").value,
    key_id: $("usage-source").value === "tests" ? "" : $("usage-key").value,
    model: $("usage-model").value.trim(),
    status: $("usage-status").value
  } : {...consoleState.filter, as_of: consoleState.usage.as_of};
  const params = new URLSearchParams({...filter, limit: "30", offset: String(offset)});
  $("usage-more").disabled = true;
  $("usage-list").setAttribute("aria-busy", "true");
  try {
    const data = await api("/gateway/usage?" + params, undefined, read.signal);
    if (consoleState.read !== read) return;
    consoleState.usage = data;
    if (reset) consoleState.filter = filter;
    consoleState.items = reset ? data.items : consoleState.items.concat(data.items);
    $("usage-more").hidden = !data.more;
    renderUsage();
  } finally {
    if (consoleState.read === read) {
      $("usage-more").disabled = false;
      $("usage-list").removeAttribute("aria-busy");
    }
  }
}

function renderUsage() {
  const {summary, ranking} = consoleState.usage;
  const stat = (glyph, label, value, detail, decoration) => {
    const card = el("div", undefined, "card");
    card.append(decoration || itemIcon(glyph), metric(label, value, detail));
    return card;
  };
  let ring;
  if (summary.completed) {
    ring = svgNode("svg", {viewBox: "0 0 40 40", class: "summary-ring", "aria-hidden": "true"});
    ring.append(svgNode("circle", {cx: 20, cy: 20, r: 16.5, stroke: "var(--line)"}),
      svgNode("circle", {cx: 20, cy: 20, r: 16.5, stroke: "var(--success)", pathLength: 100,
        "stroke-linecap": "round", "stroke-dasharray": (100 * summary.successes / summary.completed) + " 100"}));
  }
  $("usage-summary").replaceChildren(
    stat("usage", "調用請求", number(summary.requests), "依目前篩選"),
    stat("check", "成功率", ratio(summary.successes, summary.completed), summary.completed ? summary.successes + " / " + summary.completed + " 筆有結果" : "尚無完成的請求", ring),
    stat("token", "輸入 / 輸出 Token", number(summary.input_tokens) + " / " + number(summary.output_tokens),
      "輸入 " + summary.input_reported + " 筆 · 輸出 " + summary.output_reported + " 筆回報"),
    stat("clock", "平均首字", summary.mean_ttft_ms == null ? "—" : number(summary.mean_ttft_ms) + " ms", "依已量到首字的請求")
  );
  const max = ranking[0]?.requests;
  $("usage-ranking").replaceChildren(...(ranking.length ? ranking.map(v => {
    const item = el("div", undefined, "rank-line");
    const head = el("div");
    head.append(el("strong", v.model), el("span", (v.source === "tests" ? "實測" : "分發") + " · " + number(v.requests) + " 次"));
    item.append(head, meter(v.requests, max, v.model + " 調用量"));
    return item;
  }) : [empty("目前沒有模型調用。")]));
  $("usage-count").textContent = "已顯示 " + number(consoleState.items.length) + " / " + number(summary.requests) + " 筆";
  renderTrend();
  renderUsageRows();
}

function renderTrend() {
  const {trend, timezone} = consoleState.usage;
  const tokens = consoleState.mode === "tokens";
  const series = tokens
    ? [["輸入", "var(--primary)", p => p.input_tokens], ["輸出", "var(--indigo)", p => p.output_tokens]]
    : [["成功", "var(--success)", p => p.successes], ["失敗", "var(--danger)", p => p.completed - p.successes],
       ["其餘", "var(--pending)", p => p.requests - p.completed]];
  $("chart-requests").setAttribute("aria-pressed", String(!tokens));
  $("chart-tokens").setAttribute("aria-pressed", String(tokens));
  const container = $("usage-chart");
  const info = $("usage-bucket");
  info.textContent = "";
  if (!trend.some(p => tokens ? p.input_tokens != null || p.output_tokens != null : p.requests > 0)) {
    container.replaceChildren(empty(tokens ? "這個期間沒有上游回報 Token。" : "這個期間沒有調用。"));
    return;
  }
  const plotWidth = 640, plotHeight = 144;
  const slot = plotWidth / trend.length;
  const width = Math.min(31, slot * .66);
  const max = Math.max(1, ...trend.map(p => series.reduce((total, v) => total + (v[2](p) ?? 0), 0)));
  const plot = svgNode("svg", {
    viewBox: "0 0 " + plotWidth + " " + plotHeight, class: "chart-svg", preserveAspectRatio: "none",
    tabindex: 0, role: "img", "aria-label": (tokens ? "Token" : "調用") + "趨勢，使用方向鍵查看時間段", "aria-describedby": "usage-bucket"
  });
  for (const portion of [0, .5, 1]) {
    plot.append(svgNode("line", {x1: 0, x2: plotWidth, y1: plotHeight * portion, y2: plotHeight * portion, class: "grid-line"}));
  }
  const groups = trend.map((p, index) => {
    const group = svgNode("g", {class: "bar"});
    let y = plotHeight;
    for (const [, color, value] of series) {
      const height = (value(p) ?? 0) / max * (plotHeight - 4);
      if (height <= 0) continue;
      y -= height;
      group.append(svgNode("rect", {x: index * slot + (slot - width) / 2, y, width, height, rx: 2.4, fill: color}));
    }
    plot.append(group);
    return group;
  });
  const guide = svgNode("line", {y1: 0, y2: plotHeight, class: "grid-line", "stroke-dasharray": "3 3", visibility: "hidden"});
  plot.append(guide);
  const display = new Intl.DateTimeFormat("zh-TW", {timeZone: timezone, month: "numeric", day: "numeric",
    ...(consoleState.filter.range === "24h" ? {hour: "2-digit", minute: "2-digit", hourCycle: "h23"} : {})});
  const labels = el("div", undefined, "chart-axis");
  for (const i of new Set([0, Math.floor((trend.length - 1) / 2), trend.length - 1])) {
    labels.append(el("span", display.format(trend[i].from)));
  }
  const legend = el("div", undefined, "chart-legend");
  for (const [label, color] of series) {
    const name = el("span");
    const dot = el("i", undefined, "legend-dot");
    dot.style.backgroundColor = color;
    name.append(dot, document.createTextNode(label));
    legend.append(name);
  }
  const frame = el("div", undefined, "chart-frame");
  const scale = el("div", undefined, "chart-scale");
  scale.append(el("span", number(max)), el("span", "0"));
  frame.append(scale, plot);
  container.replaceChildren(frame, labels, legend);
  let selected = -1;
  const inspect = index => {
    selected = Math.min(trend.length - 1, Math.max(0, index));
    const p = trend[selected];
    const x = selected * slot + slot / 2;
    guide.setAttribute("x1", x);
    guide.setAttribute("x2", x);
    guide.setAttribute("visibility", "visible");
    groups.forEach((bar, i) => bar.classList.toggle("dimmed", i !== selected));
    info.textContent = localDate(p.from, timezone) + " — " + localDate(p.until, timezone) + " · " +
      series.map(([label, , value]) => label + " " + number(value(p))).join(" · ");
  };
  const trackPointer = event => {
    const box = plot.getBoundingClientRect();
    if (box.width) inspect(Math.floor((event.clientX - box.left) / box.width * trend.length));
  };
  plot.addEventListener("pointermove", event => { if (event.pointerType === "mouse") trackPointer(event); });
  plot.addEventListener("click", trackPointer);
  plot.addEventListener("focus", () => inspect(selected < 0 ? trend.length - 1 : selected));
  plot.addEventListener("keydown", event => {
    if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) return;
    event.preventDefault();
    inspect(event.key === "Home" ? 0 : event.key === "End" ? trend.length - 1 : selected + (event.key === "ArrowLeft" ? -1 : 1));
  });
  info.textContent = "選取時間段查看數字。";
}

function renderUsageRows() {
  const list = $("usage-list");
  if (!consoleState.items.length) {
    list.replaceChildren(empty("這個範圍還沒有請求。"));
    return;
  }
  const labels = ["時間", "模型 / 供應商", "Key / 來源", "結果", "首字 / 總延遲", "輸入 / 輸出", "操作"];
  const table = el("table", undefined, "usage-table");
  table.setAttribute("aria-label", "調用明細");
  const head = table.createTHead().insertRow();
  for (const label of labels) {
    const cell = el("th", label);
    cell.scope = "col";
    head.append(cell);
  }
  const body = table.createTBody();
  for (const v of consoleState.items) {
    const row = body.insertRow();
    const add = (value, detail) => {
      const td = row.insertCell();
      td.dataset.label = labels[td.cellIndex];
      td.append(value);
      if (detail) td.append(el("small", detail));
    };
    add(localDate(v.at, consoleState.usage.timezone));
    add(v.model, v.provider ? v.provider + " / " + v.upstream_model : "");
    add(v.key || "模型實測", v.source === "gateway" ? "分發請求" : "");
    add(usageResult(v), v.attempts > 1 ? "含備援 · 共 " + v.attempts + " 次上游請求" : "");
    add(delay(v.ttft_ms) + " / " + delay(v.latency_ms));
    add(number(v.input_tokens) + " / " + number(v.output_tokens));
    add(button("詳情", () => openUsageDetail(v), {glyph: "info", iconOnly: true}));
  }
  list.replaceChildren(table);
}
function delay(ms) { return ms == null ? "—" : number(ms) + " ms"; }
function usageResult(v) {
  const code = v.http_status ?? v.upstream_status;
  const tone = v.outcome === "SUCCESS" ? "success" : ["RUNNING", "CANCELLED", "INTERRUPTED"].includes(v.outcome) ? "quiet" : "danger";
  return badge((statuses[v.outcome] || v.outcome) + (code ? " · " + code : ""), tone,
    tone === "success" ? "check" : tone === "quiet" ? "clock" : "alert");
}
function renderDetailFields(fields, values) {
  fields.replaceChildren();
  for (const [name, value] of values) {
    const item = el("dd");
    item.append(value);
    fields.append(el("dt", name), item);
  }
}
function openUsageDetail(v) {
  const values = [
    ["請求 ID", v.id], ["調用時間", localDate(v.at, consoleState.usage.timezone)],
    ["來源", v.source === "gateway" ? "分發請求" : "模型實測"], ["分發 Key", v.key || "—"],
    ["模型", v.model], ["實際連線", [v.provider, v.upstream_model].filter(Boolean).join(" / ") || "—"],
    ["結果", usageResult(v)], ["上游 HTTP", number(v.upstream_status)],
    ["上游嘗試次數", number(v.attempts)], ["首字等待", delay(v.ttft_ms)], ["總延遲", delay(v.latency_ms)],
    ["輸入 / 輸出 Token", number(v.input_tokens) + " / " + number(v.output_tokens)]
  ];
  renderDetailFields($("usage-detail-fields"), values);
  $("usage-detail").showModal();
}

$("new-key").addEventListener("click", () => openKeyEditor());
$("key-search").addEventListener("input", renderKeys);
$("key-status").addEventListener("change", renderKeys);
$("key-model-search").addEventListener("input", keySelection);
$("key-models").addEventListener("change", keySelection);
$("usage-source").addEventListener("change", syncUsageKeyFilter);
$("clear-usage-filters").addEventListener("click", event => action(event.currentTarget, () => {
  $("usage-filters").reset();
  syncUsageKeyFilter();
  return loadUsage(true);
}));
$("usage-filters").addEventListener("submit", event => {
  event.preventDefault();
  action(event.submitter, () => loadUsage(true));
});
$("usage-more").addEventListener("click", () => action(null, () => loadUsage(false)));
for (const mode of ["requests", "tokens"]) $("chart-" + mode).addEventListener("click", () => {
  consoleState.mode = mode;
  if (consoleState.usage) renderTrend();
});

submit("key-form", async form => {
  const data = new FormData(form);
  const models = data.getAll("model");
  if (!models.length) throw new Error("請選擇至少一個可用模型。");
  const date = data.get("expires_at");
  const expires = date ? new Date(date).getTime() : null;
  if (date && !Number.isFinite(expires)) throw new Error("請填寫有效的到期時間。");
  const edit = consoleState.edit;
  const result = await api(edit ? "/gateway/keys/edit" : "/gateway/keys", {
    id: edit?.id, label: data.get("label"), daily_limit: Number(data.get("daily_limit")), models, expires_at: expires
  });
  $("key-editor").close();
  if (edit) notice("已儲存 Key 設定。");
  else {
    $("issued-base").value = baseURL();
    $("issued-key").value = result.token;
    $("issued-dialog").showModal();
  }
  await loadShare();
});
