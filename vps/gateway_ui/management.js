"use strict";

const management = {
  groups: [], providers: [], provider: null, accounts: [], credentials: [],
  checkins: null, account: null, connection: null, align: null
};
const operationNames = {
  CHAT_COMPLETIONS: "Chat Completions", RESPONSES: "Responses", ANTHROPIC_MESSAGES: "Messages",
  GEMINI_NATIVE: "Gemini", IMAGES_GENERATION: "圖片生成", IMAGES_EDIT: "圖片編輯",
  AUDIO: "語音轉寫", AUDIO_SPEECH: "語音合成", GEMINI_SPEECH: "Gemini 語音",
  EMBEDDINGS: "嵌入", RERANK: "重排", CUSTOM: "自訂端點"
};
let groupRead, offerRead, providerRead, detailRead;

async function showModelView() {
  const group = state.modelGroup;
  $("model-index").hidden = !!group;
  $("model-detail").hidden = !group;
  if (group) {
    $("model-title").textContent = group.name;
    $("model-canonical").textContent = group.id === group.name ? "" : group.id;
    $("route-pick").hidden = true;
    return loadOffers(true);
  }
  return loadGroups(true);
}

async function loadGroups(reset) {
  groupRead?.abort();
  const read = new AbortController();
  groupRead = read;
  const params = new URLSearchParams({limit: "40", offset: String(reset ? 0 : management.groups.length), q: $("model-search").value.trim()});
  if (state.modelProvider) params.set("provider_id", state.modelProvider.id);
  $("model-provider-filter").hidden = !state.modelProvider;
  $("model-provider-name").textContent = state.modelProvider ? state.modelProvider.name + " 提供的模型" : "";
  const data = await api("/gateway/model-groups?" + params, undefined, read.signal);
  if (groupRead !== read) return;
  management.groups = reset ? data.models : management.groups.concat(data.models);
  $("models-more").hidden = !data.more;
  $("model-count").textContent = "已載入 " + management.groups.length + " 個模型";
  const nodes = management.groups.map(group => {
    const tile = button("", async () => {
      state.modelGroup = group;
      state.offers = [];
      $("offer-sort").value = "ttft";
      await showPage("models");
      $("model-title").focus({preventScroll: true});
    });
    tile.className = "model-tile";
    const label = el("span", undefined, "model-tile-label");
    label.append(el("strong", group.name), el("small", group.providers + " 家供應商 · " + group.connections + " 個連線"));
    tile.append(label, el("span", "›", "tile-arrow"));
    tile.lastElementChild.setAttribute("aria-hidden", "true");
    return tile;
  });
  $("model-list").replaceChildren(...(nodes.length ? nodes : [empty("這裡會列出你的模型。可新增供應商、同步目錄或手動加入模型 ID。")]));
}

async function loadOffers(reset) {
  const group = state.modelGroup;
  if (!group) return;
  offerRead?.abort();
  const read = new AbortController();
  offerRead = read;
  const params = new URLSearchParams({
    limit: "50", offset: String(reset ? 0 : state.offers.length), canonical_id: group.id, sort: $("offer-sort").value
  });
  $("offer-list").setAttribute("aria-busy", "true");
  $("offers-more").disabled = true;
  try {
    const result = await api("/gateway/offerings?" + params, undefined, read.signal);
    if (offerRead !== read || state.modelGroup !== group) return;
    state.offers = reset ? result.offerings : state.offers.concat(result.offerings);
    $("offers-more").hidden = !result.more;
    renderOffers();
  } finally {
    if (offerRead === read) {
      $("offer-list").removeAttribute("aria-busy");
      $("offers-more").disabled = false;
    }
  }
}

function performanceCells(p) {
  const metrics = el("div", undefined, "metrics");
  metrics.append(metric("首字等待", p.ttft_ms == null ? "—" : number(p.ttft_ms) + " ms",
      p.latency_ms == null ? "" : "總延遲 " + number(p.latency_ms) + " ms"),
    metric("生成速度", p.tps == null ? "—" : number(p.tps, 1) + " tok/s"),
    metric("成功率", ratio(p.successes, p.samples), p.samples ? p.successes + " / " + p.samples + " 次" : "未實測"));
  return metrics;
}

function renderOffers() {
  const cards = state.offers.map(v => {
    const row = el("article", undefined, "entry");
    const relayable = v.operation === "CHAT_COMPLETIONS";
    const testable = ["CHAT_COMPLETIONS", "RESPONSES", "ANTHROPIC_MESSAGES", "GEMINI_NATIVE"].includes(v.operation);
    const probe = button("測試", async () => {
      const result = await api(relayable ? "/gateway/probe" : "/models/test", selection(v));
      await loadOffers(true);
      notice(v.provider + " · " + (statuses[result.outcome] || result.outcome || "測試完成"), result.outcome !== "SUCCESS");
    });
    probe.disabled = !v.enabled;
    probe.hidden = !testable;
    const picked = state.routes.some(route => scopeID(route) === scopeID(v));
    const choose = button(picked ? "移除路由" : "加入路由", () => {
      if (picked) {
        state.routes = state.routes.filter(route => scopeID(route) !== scopeID(v));
        if (!state.routes.length) state.routeGroup = null;
      } else {
        if (state.routes.length >= 8) throw new Error("一個公開模型最多 8 條上游路由。");
        if (state.routeGroup && state.routeGroup !== v.canonical_id) throw new Error("目前已有另一個模型的分發草稿，請先儲存或清除。");
        state.routes.push(selection(v));
        state.routeGroup = v.canonical_id;
        if (!$("public-model").value) $("public-model").value = v.model_id;
      }
      renderRoutes();
      renderOffers();
    });
    choose.hidden = !relayable || !state.routePicking;
    choose.disabled = !v.enabled && !picked;
    choose.setAttribute("aria-pressed", String(picked));
    const align = button("調整歸類", () => {
      management.align = v;
      $("group-scope").textContent = v.provider + " / " + v.model_id;
      $("group-form").elements.canonical_id.value = v.canonical_id;
      $("group-dialog").showModal();
    });
    const details = identity(v.provider, [[v.account, v.credential, v.group].filter(Boolean).join(" · "),
      v.model_id + " · " + (operationNames[v.operation] || v.operation), v.enabled ? "" : "連線停用或不可用"]);
    row.append(details, performanceCells(v.performance), controls(probe, choose, align));
    return row;
  });
  const group = state.modelGroup;
  $("route-pick").hidden = !state.offers.some(v => v.operation === "CHAT_COMPLETIONS");
  $("route-pick").setAttribute("aria-pressed", String(state.routePicking));
  $("offer-count").textContent = group.providers + " 家供應商 · " + group.connections + " 個連線";
  $("offer-list").replaceChildren(...(cards.length ? cards : [empty("這個模型目前沒有連線。")]));
}

function checkinSummary(p) {
  if (p.requires_checkin) return p.pending_checkins ? p.pending_checkins + " 個帳戶待簽到" : "今日簽到已記錄";
  if (p.checkin_offered === true) return "站方提供簽到";
  if (p.checkin_offered === false) return "站方未啟用簽到";
  return "簽到待確認";
}
function quotaValue(q) {
  if (q.unlimited) return "無上限";
  if (q.remaining !== "") return q.remaining + " " + q.unit;
  return "剩餘待確認";
}
function quotaSource(q) {
  if (q.role === "ACCOUNT") return q.account + " · 手動";
  if (q.role === "ACCOUNT_PAT") return q.account + " · 站方帳戶";
  return q.account + " / " + q.credential;
}
function httpsLink(label, url) {
  if (!url) return null;
  try { if (new URL(url).protocol !== "https:") return null; } catch { return null; }
  const link = el("a", label, "link-button");
  link.href = url;
  link.target = "_blank";
  link.rel = "noopener noreferrer";
  return link;
}
function providerFacts(p, summary) {
  const facts = el("div", undefined, "provider-facts");
  facts.append(performanceCells(p.performance));
  const limits = el("div", undefined, "quota-facts");
  limits.append(el("small", "剩餘額度", "quiet"));
  if (p.quotas.length) {
    for (const q of p.quotas.slice(0, summary ? 2 : 3)) {
      limits.append(el("div", quotaValue(q) + " · " + quotaSource(q), "quota-line"));
    }
  } else limits.append(el("div", "待查詢", "quota-line"));
  facts.append(limits, el("span", checkinSummary(p), "badge"));
  return facts;
}

async function showProviderView() {
  $("provider-index").hidden = !!state.providerID;
  $("provider-detail").hidden = !state.providerID;
  if (state.providerID) return loadProvider();
  return loadProviderList(true);
}

async function loadProviderList(reset) {
  providerRead?.abort();
  const read = new AbortController();
  providerRead = read;
  const params = new URLSearchParams({limit: "25", offset: String(reset ? 0 : management.providers.length), q: $("provider-search").value.trim()});
  const data = await api("/gateway/providers?" + params, undefined, read.signal);
  if (providerRead !== read) return;
  management.providers = reset ? data.providers : management.providers.concat(data.providers);
  $("providers-more").hidden = !data.more;
  $("provider-count").textContent = "已載入 " + management.providers.length + " 家 · 近 7 天本站實測";
  const cards = management.providers.map(p => {
    const row = el("article", undefined, "entry provider-tile");
    const intro = identity(p.name, [p.description || "尚未填寫簡介", p.base_url, p.accounts + " 個帳戶 · " + p.keys + " 把啟用的推論 Key"]);
    const view = button("管理 " + p.name, () => {
      state.providerID = p.id;
      $("provider-detail-content").replaceChildren();
      return showPage("providers");
    });
    row.append(intro, providerFacts(p, true), controls(view));
    return row;
  });
  $("provider-list").replaceChildren(...(cards.length ? cards : [empty("目前沒有符合的供應商。")]));
}

async function loadProvider() {
  const id = state.providerID;
  detailRead?.abort();
  const read = new AbortController();
  detailRead = read;
  const params = "?provider_id=" + encodeURIComponent(id);
  const [result, accounts, credentials] = await Promise.all([
    api("/gateway/providers" + params, undefined, read.signal),
    api("/accounts" + params, undefined, read.signal),
    api("/credentials" + params, undefined, read.signal)
  ]);
  if (detailRead !== read || state.providerID !== id) return;
  const provider = result.providers[0];
  if (!provider) throw new Error("找不到供應商。");
  const checkins = provider.requires_checkin ? await api("/checkins/accounts" + params + "&timezone=" + encodeURIComponent(result.timezone), undefined, read.signal) : null;
  if (detailRead !== read || state.providerID !== id) return;
  Object.assign(management, {provider, accounts, credentials, checkins});
  renderProvider();
}

function openProviderProfile(p) {
  const form = $("provider-form");
  for (const field of ["name", "description", "website", "checkin_url"]) form.elements[field].value = p[field] || "";
  form.elements.requires_checkin.checked = p.requires_checkin;
  $("provider-dialog").showModal();
}

function openConnectionDialog(v, operation) {
  management.connection = {upstream: v, operation};
  const field = $("connection-value");
  field.value = "";
  field.type = operation === "key" ? "password" : "text";
  field.maxLength = operation === "key" ? 8192 : 256;
  $("connection-title").textContent = operation === "key" ? "更新上游 Key" : "手動新增模型";
  $("connection-value-label").textContent = operation === "key" ? "新的 API Key" : "模型 ID";
  $("connection-dialog").showModal();
}
function openAccountDialog(account, kind) {
  management.account = account;
  $(kind + "-form").reset();
  $(kind + "-dialog").showModal();
}
function quotaDetail(q) {
  const extras = [q.used === "" ? "" : "已用 " + q.used, q.granted === "" ? "" : "總額 " + q.granted].filter(Boolean);
  return [quotaValue(q), ...extras, new Date(q.observed_at).toLocaleString("zh-TW")].join(" · ");
}
function renderCredential(p, c) {
  const v = {...c, credential_id: c.id};
  const row = el("article", undefined, "entry compact-entry");
  const quota = p.quotas.find(q => q.credential_id === c.id && q.account_id === c.account_id);
  const inference = c.role === "INFERENCE";
  const anonymous = inference && (c.auth_mode || p.auth_mode) === "NONE";
  row.append(identity(c.label || "Key", [
    [inference ? "推論 Key" : "帳戶 PAT", c.enabled ? "已啟用" : "已停用", c.group].filter(Boolean).join(" · "),
    c.base_url !== p.base_url ? c.base_url : "", c.note, quota ? quotaDetail(quota) : ""
  ]));
  const actions = controls();
  if (inference) {
    const sync = button("同步模型", async () => {
      const result = await api("/gateway/sync", connectionFields(v));
      await loadProvider();
      notice("同步完成 · " + result.models_seen + " 個模型");
    });
    const add = button("手動加模型", () => openConnectionDialog(v, "model"));
    sync.disabled = add.disabled = !c.enabled;
    actions.append(sync, add);
  }
  if (!anonymous) {
    const quotaButton = button("更新額度", async () => {
      await api("/gateway/quota/refresh", {...connectionFields(v), role: c.role});
      await loadProvider();
      notice("已讀取站方額度。");
    });
    quotaButton.disabled = !c.enabled;
    actions.append(quotaButton, button("換 Key", () => openConnectionDialog(v, "key")));
  }
  actions.append(button(c.enabled ? "停用" : "啟用", async () => {
    await api("/credentials/toggle", {provider_id: p.id, account_id: c.account_id, ids: [c.id], enabled: !c.enabled});
    await loadProvider();
    notice(c.enabled ? "Key 已停用。" : "Key 已啟用。");
  }));
  row.append(actions);
  return row;
}
function renderAccount(p, a) {
  const card = el("div", undefined, "card");
  const heading = el("div", undefined, "section-inline");
  heading.append(el("h2", a.label || "帳戶"), controls(button("新增 Key", () => openAccountDialog(a, "credential")),
    button("記錄額度", () => openAccountDialog(a, "quota"))));
  card.append(heading);
  for (const q of p.quotas.filter(q => q.account_id === a.id && q.role !== "INFERENCE")) {
    card.append(el("p", quotaSource(q) + " · " + quotaDetail(q), "quota-line"));
  }
  const check = management.checkins?.accounts.find(v => v.account_id === a.id);
  if (check?.required) {
    const line = el("div", undefined, "checkin-line");
    line.append(el("span", check.completed_at ? "今日簽到已記錄" : "今日尚未記錄簽到", "badge"));
    const link = httpsLink("前往站點簽到", check.checkin_url);
    if (link) line.append(link);
    if (!check.completed_at) line.append(button("我已簽到", async () => {
      const {date, timezone} = management.checkins;
      await api("/checkins/accounts", {provider_id: p.id, account_id: a.id, timezone, date});
      await loadProvider();
      notice("已記錄這個帳戶的簽到。");
    }));
    card.append(line);
  }
  const list = el("div", undefined, "rows");
  for (const c of management.credentials.filter(v => v.account_id === a.id)) list.append(renderCredential(p, c));
  if (!list.children.length) list.append(empty("這個帳戶還沒有 Key。"));
  card.append(list);
  return card;
}
function renderProvider() {
  const p = management.provider;
  const content = $("provider-detail-content");
  const heading = el("div", undefined, "page-heading");
  const title = el("div");
  title.append(el("p", "Provider overview", "eyebrow"), el("h1", p.name));
  heading.append(title, button("編輯資料", () => openProviderProfile(p)));
  const profile = el("div", undefined, "card stack");
  const base = el("code", p.base_url);
  profile.append(el("p", p.description || "尚未填寫簡介"), base, providerFacts(p, false),
    el("p", "近 7 天 · 最近 100 筆本站實測", "quiet"));
  const tools = controls(button("複製 Base URL", () => copy(p.base_url, base)), button("看這家的模型", async () => {
    state.modelGroup = null;
    state.modelProvider = p;
    $("model-search").value = "";
    await showPage("models");
  }), button("查詢站方簽到", async () => {
    await api("/newapi/status", {provider_id: p.id});
    await loadProvider();
    notice("已讀取站方狀態。");
  }));
  const link = httpsLink("開啟網站", p.website);
  if (link) tools.append(link);
  profile.append(tools);
  const accounts = el("div", undefined, "section-inline");
  accounts.append(el("h2", "帳戶與 Key"), button("新增帳戶", () => openAccountDialog(null, "account")));
  content.replaceChildren(heading, profile, accounts, ...management.accounts.map(a => renderAccount(p, a)));
}

for (const id of ["model-add-provider", "add-upstream"]) $(id).addEventListener("click", () => $("upstream-dialog").showModal());
$("models-back").addEventListener("click", () => action(null, () => {
  state.modelGroup = null;
  return showPage("models");
}));
$("providers-back").addEventListener("click", () => action(null, () => {
  state.providerID = "";
  return showPage("providers");
}));
$("clear-model-provider").addEventListener("click", () => action(null, () => {
  state.modelProvider = null;
  return loadGroups(true);
}));
for (const [id, load] of [["model", loadGroups], ["provider", loadProviderList]]) {
  let timer;
  $(id + "-filters").addEventListener("submit", event => { event.preventDefault(); action(null, () => load(true)); });
  $(id + "-search").addEventListener("input", () => {
    clearTimeout(timer);
    timer = setTimeout(() => action(null, () => load(true)), 180);
  });
  $(id + "s-more").addEventListener("click", () => action(null, () => load(false)));
}
$("offer-sort").addEventListener("change", () => action(null, () => loadOffers(true)));
$("offers-more").addEventListener("click", () => action(null, () => loadOffers(false)));
$("route-pick").addEventListener("click", () => {
  state.routePicking = !state.routePicking;
  renderOffers();
});

submit("upstream-form", async form => {
  const v = await api("/gateway/upstreams", Object.fromEntries(new FormData(form)));
  form.reset();
  $("upstream-dialog").close();
  state.providerID = v.provider_id;
  $("provider-detail-content").replaceChildren();
  let result;
  try { result = await api("/gateway/sync", connectionFields(v)); }
  catch (error) { notice("供應商已儲存，可重試同步或手動加入模型。 " + error.message, true); }
  await showPage("providers");
  if (result) notice("同步完成 · " + result.models_seen + " 個模型");
});
submit("connection-form", async form => {
  const value = new FormData(form).get("value").trim();
  const {upstream, operation} = management.connection;
  if (operation === "key") await api("/credentials/rotate", {...connectionFields(upstream), secret: value, confirm: true});
  else await api("/gateway/upstream-model", {...connectionFields(upstream), model_id: value});
  form.reset();
  $("connection-dialog").close();
  await loadProvider();
  notice(operation === "key" ? "上游 Key 已更新。" : "模型已加入。");
});
submit("group-form", async form => {
  const {provider_id, model_id} = management.align;
  await api("/gateway/model-group", {provider_id, model_id, canonical_id: form.elements.canonical_id.value});
  $("group-dialog").close();
  state.modelGroup = null;
  await showPage("models");
  notice("已更新模型歸類。");
});
submit("provider-form", async form => {
  await api("/gateway/provider", {
    ...Object.fromEntries(new FormData(form)), provider_id: management.provider.id,
    requires_checkin: form.elements.requires_checkin.checked
  });
  $("provider-dialog").close();
  await loadProvider();
});
submit("account-form", async form => {
  await api("/accounts", {provider_id: management.provider.id, id: crypto.randomUUID(), label: form.elements.label.value.trim()});
  $("account-dialog").close();
  await loadProvider();
});
submit("credential-form", async form => {
  const {id, auth_mode} = management.provider;
  const data = Object.fromEntries(new FormData(form));
  await api("/credentials", {...data, provider_id: id, account_id: management.account.id, auth_mode: auth_mode === "NONE" ? "BEARER" : ""});
  form.reset();
  $("credential-dialog").close();
  await loadProvider();
});
submit("quota-form", async form => {
  await api("/gateway/quota", {...Object.fromEntries(new FormData(form)), provider_id: management.provider.id, account_id: management.account.id});
  $("quota-dialog").close();
  await loadProvider();
});

action(null, refresh);
