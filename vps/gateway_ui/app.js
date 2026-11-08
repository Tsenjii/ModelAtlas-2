"use strict";

const $ = id => document.getElementById(id);
const state = {
  page: "models", shareMode: "setup", upstreams: [], offers: [], models: [], keys: [], routes: [],
  modelGroup: null, modelProvider: null, providerID: "", routeGroup: null, routePicking: false, usage: [], usageOffset: 0
};
const statuses = {
  SUCCESS: "成功", RUNNING: "處理中", RATE_LIMITED: "上游限流", CANCELLED: "已取消",
  INTERRUPTED: "已中斷", MODEL_UNAVAILABLE: "上游不可用", UPSTREAM_5XX: "上游故障",
  TIMEOUT: "逾時", NETWORK: "連線失敗", PARSER: "回應格式錯誤", INVALID_REQUEST: "上游拒絕請求",
  AUTH_INVALID: "上游 Key 無效", AUTH: "上游驗證失敗", INFERENCE_DENIED: "上游拒絕存取",
  PAYMENT_REQUIRED: "額度不足", HTTP_ERROR: "上游拒絕請求", TELEMETRY_SAVE: "紀錄暫不可寫入",
  SECRET_IN_RESPONSE: "上游回應含私密資料"
};
const errors = {
  "valid name and HTTPS base URL required": "請填名稱和有效的 HTTPS Base URL。",
  "invalid upstream credential": "上游 Key 格式有誤。",
  "upstream connection unavailable": "這個連線目前無法使用，請檢查上游 Key。",
  "upstream changed during discovery; retry": "同步期間連線設定有變更，請重新同步。",
  "a matching Chat Completions endpoint is required": "這個連線需要同站的 Chat Completions 端點。",
  "route is not an enabled Chat Completions connection": "有路由目前停用或無法提供 Chat Completions。",
  "choose a model ID and 1–8 routes": "請填有效模型 ID，並選 1–8 條上游路由。",
  "select published models": "請選擇目前啟用的公開模型。",
  "anonymous connection cannot receive a secret": "匿名連線可另新增一個有 Key 的上游。",
  "invalid provider profile or check-in URL": "請檢查名稱與 HTTPS 網址。標記需要簽到時，請填簽到網址。",
  "valid non-negative amount and unit required": "請填寫非負數額度和單位。",
  "valid model grouping ID required": "請輸入有效的模型歸類 ID。"
};

function el(tag, text, className) {
  const node = document.createElement(tag);
  if (text !== undefined) node.textContent = text;
  if (className) node.className = className;
  return node;
}
function button(text, handler) {
  const node = el("button", text);
  node.type = "button";
  node.addEventListener("click", () => action(node, handler));
  return node;
}
function option(value, text) {
  const node = el("option", text);
  node.value = value;
  return node;
}
function putOptions(select, list) {
  const previous = select.value;
  select.replaceChildren(...list.map(([value, label]) => option(value, label)));
  if (list.some(([value]) => value === previous)) select.value = previous;
}
function empty(text) {
  return el("p", text, "empty");
}
function notice(text, error = false) {
  const dialog = document.querySelector("dialog[open]");
  const global = $("notice");
  let node = global;
  if (dialog && error) {
    node = dialog.querySelector(".dialog-message") || el("p", undefined, "dialog-message");
    node.setAttribute("role", "alert");
    if (!node.isConnected) dialog.append(node);
  }
  node.textContent = text;
  node.hidden = false;
  node.dataset.error = String(error);
  if (node === global) {
    clearTimeout(notice.timer);
    notice.timer = setTimeout(() => { global.hidden = true; }, error ? 6500 : 3300);
  }
}
async function action(control, work) {
  if (control?.disabled) return;
  if (control) control.disabled = true;
  try {
    await work();
  } catch (error) {
    if (error.name !== "AbortError") notice(error.message, true);
  } finally {
    if (control) control.disabled = false;
  }
}
async function api(path, data, signal) {
  const init = {signal, cache: "no-store"};
  if (data !== undefined) {
    init.method = "POST";
    init.headers = {"Content-Type": "application/json"};
    init.body = JSON.stringify(data);
  }
  const response = await fetch("/api/v1" + path, init);
  const text = await response.text();
  if (!response.ok) {
    let problem;
    try { problem = JSON.parse(text); } catch {}
    const info = problem?.error || problem;
    const status = info?.upstream_status;
    if (info?.code === "UNSUPPORTED_PRIVATE_VERSION") throw new Error("這個站點版本尚未驗證額度查詢。");
    if (info?.code === "CREDENTIAL_ROLE") throw new Error("請確認這把 Key 的用途與帳戶。");
    throw new Error(errors[text.trim()] || (status ? "上游回傳 HTTP " + status : "操作未完成（HTTP " + response.status + "）"));
  }
  return JSON.parse(text);
}
function connectionFields(v) {
  return {provider_id: v.provider_id, account_id: v.account_id, credential_id: v.credential_id};
}
function selection(v) {
  return {...connectionFields(v), model_id: v.model_id, group: v.group, endpoint: v.endpoint};
}
function connectionID(v) {
  return JSON.stringify([v.provider_id, v.account_id, v.credential_id]);
}
function scopeID(v) {
  return JSON.stringify([connectionID(v), v.model_id, v.group, v.endpoint]);
}
function connectionLabel(v) {
  return [v.name || v.provider, v.account, v.credential, v.group].filter(Boolean).join(" · ");
}
function routeLabel(v) {
  const upstream = state.upstreams.find(item => connectionID(item) === connectionID(v));
  return (upstream ? connectionLabel(upstream) : v.provider_id) + " / " + v.model_id;
}
function number(value, digits = 0) {
  return value == null ? "—" : new Intl.NumberFormat("zh-TW", {maximumFractionDigits: digits}).format(value);
}
function ratio(successes, total) {
  return total ? number(100 * successes / total, 1) + "%" : "—";
}
function metric(label, value, detail) {
  const node = el("div", undefined, "metric");
  node.append(el("small", label), el("strong", value));
  if (detail) node.append(el("em", detail));
  return node;
}
function identity(title, lines) {
  const node = el("div", undefined, "identity");
  node.append(el("h3", title));
  for (const line of lines.filter(Boolean)) node.append(el("small", line));
  return node;
}
function controls(...buttons) {
  const node = el("div", undefined, "row-actions");
  node.append(...buttons);
  return node;
}
function compact(title, lines, actions) {
  const node = el("article", undefined, "entry compact-entry");
  node.append(identity(title, lines), actions);
  return node;
}
function baseURL() {
  return location.origin + "/v1";
}
async function copy(value, field) {
  try {
    await navigator.clipboard.writeText(value);
    notice("已複製");
  } catch {
    if (field instanceof HTMLInputElement) {
      field.focus();
      field.select();
    } else if (field) {
      const range = document.createRange();
      range.selectNodeContents(field);
      window.getSelection()?.removeAllRanges();
      window.getSelection()?.addRange(range);
    }
    notice("已選取文字，可使用系統複製功能。");
  }
}
async function showPage(page) {
  state.page = page;
  for (const id of ["models", "providers", "share"]) $("page-" + id).hidden = id !== page;
  for (const nav of document.querySelectorAll("[data-page]")) {
    nav.classList.toggle("active", nav.dataset.page === page);
    if (nav.dataset.page === page) nav.setAttribute("aria-current", "page");
    else nav.removeAttribute("aria-current");
  }
  window.scrollTo(0, 0);
  await refresh();
}
async function refresh() {
  if (state.page === "models") return showModelView();
  if (state.page === "providers") return showProviderView();
  const setup = state.shareMode === "setup";
  $("share-settings").hidden = !setup;
  $("share-usage").hidden = setup;
  $("share-setup").setAttribute("aria-pressed", String(setup));
  $("share-history").setAttribute("aria-pressed", String(!setup));
  if (setup) return loadShare();
  state.keys = await api("/gateway/keys");
  putOptions($("usage-key"), [["", "全部 Key"], ...state.keys.map(k => [k.id, k.label])]);
  await loadUsage(true);
}
function renderRoutes() {
  $("route-tray").hidden = state.routes.length === 0;
  $("route-count").textContent = "已選 " + state.routes.length + " 個路由";
  $("selected-routes").replaceChildren(...(state.routes.length ? state.routes.map((v, i) => compact((i + 1) + ". " + routeLabel(v), [], controls(
    button("移除", () => {
      state.routes = state.routes.filter(row => scopeID(row) !== scopeID(v));
      if (!state.routes.length) state.routeGroup = null;
      renderRoutes();
    })
  ))) : [empty("從上游模型清單挑選同一模型的連線。")]));
}
function resetModelDraft() {
  state.routes = [];
  state.routeGroup = null;
  state.routePicking = false;
  $("public-model").value = "";
  $("public-model").readOnly = false;
  $("model-editor-title").textContent = "新增公開模型";
  $("model-editor").open = false;
  renderRoutes();
}
async function loadShare() {
  [state.models, state.keys, state.upstreams] = await Promise.all([api("/gateway/models"), api("/gateway/keys"), api("/gateway/upstreams")]);
  renderShare();
}
function renderShare() {
  $("gateway-url").textContent = baseURL();
  $("public-models").replaceChildren(...(state.models.length ? state.models.map(v => compact(v.id,
    [v.enabled ? "已啟用 · " + v.routes.length + " 個路由" : "已停用", v.routes.map(routeLabel).join(" ｜ ")],
    controls(button("編輯路由", () => {
      state.routes = v.routes.map(selection);
      state.routeGroup = null;
      $("public-model").value = v.id;
      $("public-model").readOnly = true;
      $("model-editor-title").textContent = "編輯 " + v.id;
      $("model-editor").open = true;
      renderRoutes();
      $("model-editor").scrollIntoView({block: "nearest"});
    }), button(v.enabled ? "停用" : "啟用", async () => {
      await api("/gateway/models", {id: v.id, routes: v.routes, enabled: !v.enabled});
      await loadShare();
      notice(v.enabled ? "公開模型已停用。" : "公開模型已啟用。");
    })))) : [empty("先從上游選一個模型，就能發布成對外 ID。")]));
  const selected = new Set(Array.from($("key-models").querySelectorAll("input:checked"), v => v.value));
  const available = state.models.filter(v => v.enabled);
  $("key-models").replaceChildren(...(available.length ? available.map(v => {
    const label = el("label", undefined, "choice");
    const check = el("input");
    check.type = "checkbox";
    check.name = "model";
    check.value = v.id;
    check.checked = selected.has(v.id);
    label.append(check, el("span", v.id));
    return label;
  }) : [empty("先發布至少一個模型。")]));
  $("issue-key").disabled = available.length === 0;
  $("key-list").replaceChildren(...(state.keys.length ? state.keys.map(k => {
    const revoke = button("撤銷", async () => {
      if (!window.confirm("要撤銷「" + k.label + "」嗎？這把 Key 會立即停用。")) return;
      await api("/gateway/keys/revoke", {id: k.id});
      await loadShare();
      notice("分發 Key 已撤銷。");
    });
    revoke.hidden = !k.enabled;
    return compact(k.label, [k.prefix + "…", k.models.join("、"), k.enabled ? "今日 " + k.used_today + " / " + k.daily_limit + " 次" : "已撤銷"], controls(revoke));
  }) : [empty("還沒有分發 Key。")]));
  renderRoutes();
}

let usageRead;
async function loadUsage(reset) {
  usageRead?.abort();
  const read = new AbortController();
  usageRead = read;
  const offset = reset ? 0 : state.usageOffset;
  const params = new URLSearchParams({limit: "30", offset: String(offset), key_id: $("usage-key").value, failures: String($("only-failures").checked)});
  $("usage-more").disabled = true;
  try {
    const data = await api("/gateway/usage?" + params, undefined, read.signal);
    if (usageRead !== read) return;
    state.usage = reset ? data.items : state.usage.concat(data.items);
    state.usageOffset = offset + data.items.length;
    $("usage-more").hidden = !data.more;
    renderUsage(data.summary);
  } finally {
    if (usageRead === read) $("usage-more").disabled = false;
  }
}
function renderUsage(summary) {
  const stat = (label, value, detail) => {
    const card = el("div", undefined, "card");
    card.append(metric(label, value, detail));
    return card;
  };
  $("usage-summary").replaceChildren(
    stat("近 24 小時請求", number(summary.requests)),
    stat("完成請求成功率", ratio(summary.successes, summary.completed), summary.completed ? summary.successes + " / " + summary.completed + " 次" : "尚無已完成請求"),
    stat("上游回報 Token", number(summary.input_tokens) + " / " + number(summary.output_tokens), "輸入 / 輸出")
  );
  const cards = state.usage.map(v => {
    const row = el("article", undefined, "entry usage-row");
    const date = new Date(v.at).toLocaleString("zh-TW");
    const values = el("div", undefined, "metrics");
    values.append(metric("總延遲", v.latency_ms == null ? "—" : number(v.latency_ms) + " ms"),
      metric("首字等待", v.ttft_ms == null ? "—" : number(v.ttft_ms) + " ms"),
      metric("輸入 / 輸出", number(v.input_tokens) + " / " + number(v.output_tokens)));
    row.append(identity(v.model, [v.key + " · " + date, v.provider ? v.provider + " / " + v.upstream_model : "",
      v.upstream_status && v.upstream_status !== v.http_status ? "上游 HTTP " + v.upstream_status : "",
      v.attempts > 1 ? "含備援 · 共 " + v.attempts + " 次上游請求" : ""]), values,
    el("span", (statuses[v.outcome] || v.outcome) + (v.http_status ? " · " + v.http_status : ""), "badge"));
    return row;
  });
  $("usage-list").replaceChildren(...(cards.length ? cards : [empty("這個範圍還沒有請求。")]));
}

function bindAppearance() {
  const set = (id, storage, fallback) => {
    const input = $(id);
    let value;
    try { value = localStorage.getItem(storage); } catch {}
    input.value = [...input.options].some(o => o.value === value) ? value : fallback;
    const apply = () => {
      if (id === "theme" && input.value === "system") delete document.documentElement.dataset.theme;
      else document.documentElement.dataset[id] = input.value;
    };
    input.addEventListener("change", () => {
      apply();
      try { localStorage.setItem(storage, input.value); } catch {}
    });
    apply();
  };
  set("theme", "modelatlas.appearance", "system");
  set("wallpaper", "modelatlas.wallpaper", "kotoha-chibi");
}
function submit(id, work) {
  $(id).addEventListener("submit", event => {
    event.preventDefault();
    const form = event.currentTarget;
    action(form.querySelector("button[type='submit']"), () => work(form));
  });
}
for (const nav of document.querySelectorAll("[data-page]")) nav.addEventListener("click", () => action(nav, () => {
  const page = nav.dataset.page;
  if (state.page === page) {
    if (page === "models") state.modelGroup = null;
    if (page === "providers") state.providerID = "";
  }
  return showPage(page);
}));
for (const close of document.querySelectorAll("[data-close]")) close.addEventListener("click", () => $(close.dataset.close).close());
for (const dialog of document.querySelectorAll("dialog")) dialog.addEventListener("close", () => {
  dialog.querySelector(".dialog-message")?.remove();
  for (const field of dialog.querySelectorAll("input[type='password'],#issued-key")) field.value = "";
});
$("appearance-open").addEventListener("click", () => $("appearance-dialog").showModal());
$("refresh").addEventListener("click", () => action($("refresh"), refresh));
$("share-setup").addEventListener("click", () => action(null, async () => {
  state.shareMode = "setup";
  await refresh();
}));
$("share-history").addEventListener("click", () => action(null, async () => {
  state.shareMode = "usage";
  await refresh();
}));
$("configure-route").addEventListener("click", () => action(null, async () => {
  state.shareMode = "setup";
  await showPage("share");
  $("model-editor").open = true;
  $("public-model").focus();
}));
$("select-routes").addEventListener("click", () => action(null, () => {
  state.routePicking = true;
  return showPage("models");
}));
$("cancel-model").addEventListener("click", resetModelDraft);
$("copy-base").addEventListener("click", () => copy(baseURL(), $("gateway-url")));
$("copy-key").addEventListener("click", () => copy($("issued-key").value, $("issued-key")));
$("usage-key").addEventListener("change", () => action(null, () => loadUsage(true)));
$("only-failures").addEventListener("change", () => action(null, () => loadUsage(true)));
$("usage-more").addEventListener("click", () => action(null, () => loadUsage(false)));

submit("model-form", async form => {
  if (!state.routes.length) throw new Error("請先選擇上游路由。");
  const id = new FormData(form).get("id").trim();
  await api("/gateway/models", {id, routes: state.routes, enabled: true});
  resetModelDraft();
  await loadShare();
  notice("已發布 " + id + "。");
});
submit("key-form", async form => {
  const data = new FormData(form);
  const models = data.getAll("model");
  if (!models.length) throw new Error("請選擇至少一個可用模型。");
  const result = await api("/gateway/keys", {label: data.get("label"), daily_limit: Number(data.get("daily_limit")), models});
  $("issued-base").value = baseURL();
  $("issued-key").value = result.token;
  form.reset();
  $("key-editor").open = false;
  $("issued-dialog").showModal();
  await loadShare();
});
bindAppearance();
renderRoutes();
