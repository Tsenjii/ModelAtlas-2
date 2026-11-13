"use strict";

const modelGroupPageSize = 40;
const unclassifiedBrand = "unclassified";
const modelBrandLabels = new Map([
  ["", "全部品牌"],
  ...modelBrands.map(([brand, , name]) => [brand, name]),
  [unclassifiedBrand, "未歸類"]
]);
let modelFacets = {};
let modelBrandsAvailable = new Set([""]);
let brandProviderID = "";
let modelOffset = null;
const management = {
  modelBrand: "", providers: [], provider: null, accounts: [], credentials: [],
  checkins: null, account: null, connection: null, align: null, quotaScope: null, proxy: null
};
const operationNames = {
  CHAT_COMPLETIONS: "Chat Completions", RESPONSES: "Responses", ANTHROPIC_MESSAGES: "Messages",
  GEMINI_NATIVE: "Gemini", IMAGES_GENERATION: "圖片生成", IMAGES_EDIT: "圖片編輯",
  AUDIO: "語音轉寫", AUDIO_SPEECH: "語音合成", GEMINI_SPEECH: "Gemini 語音",
  EMBEDDINGS: "嵌入", RERANK: "重排", CUSTOM: "自訂端點"
};
let groupRead, offerRead, providerRead, detailRead;
function cancelManagementReads(page) {
  if (page !== "models") {
    groupRead?.abort();
    offerRead?.abort();
  }
  if (page !== "providers") {
    providerRead?.abort();
    detailRead?.abort();
  }
}
let providerReturn = null;
let offerBatch = null;
const offerTests = new Set();
const providerCatalogIssues = new Map();

const removeConnectionImpact = "相關分發路由也會移除。沒有路由的公開模型會停用。紀錄保留。";

function deleteButton(label, handler, iconOnly = true) {
  const node = button(label, handler, {glyph: "trash", iconOnly});
  node.classList.add("danger-button");
  return node;
}

function confirmChange(title, item, impact, change, after, label = "確認刪除", done = "已刪除。") {
  const dialog = $("remove-dialog");
  const form = $("remove-form");
  const submitButton = form.querySelector("button[type='submit']");
  const cancel = $("cancel-removal");
  $("remove-title").textContent = title;
  $("remove-item").textContent = item;
  $("remove-description").textContent = impact;
  $("confirm-action-label").textContent = label;
  form.onsubmit = event => {
    event.preventDefault();
    void action(submitButton, async () => {
      cancel.disabled = true;
      try {
        await change();
      } finally {
        cancel.disabled = false;
      }
      dialog.close();
      try {
        await after();
        notice(done);
      } catch (error) {
        notice(done + " 清單更新失敗，請重新整理。", true);
      }
    });
  };
  dialog.showModal();
}
function confirmRemoval(title, item, impact, data, after) {
  confirmChange(title, item, impact, () => api("/gateway/remove", {...data, confirm: true}), after);
}
$("remove-dialog").addEventListener("cancel", event => {
  if ($("cancel-removal").disabled) event.preventDefault();
});

function pruneDraft(predicate) {
  state.routes = state.routes.filter(route => !predicate(route));
  if (!state.routes.length) state.routeGroup = null;
  renderRoutes();
}
function removeOffer(v) {
  const group = state.modelGroup;
  confirmRemoval("刪除連線", v.provider + " / " + v.account + " / " + v.credential + " / " + v.model_id,
    removeConnectionImpact,
    {kind: "CONNECTION", ...selection(v)}, async () => {
      $("offer-detail").close();
      pruneDraft(route => scopeID(route) === scopeID(v));
      if (state.modelGroup !== group) return;
      const next = await api("/gateway/model-groups?" + new URLSearchParams({
        id: group.id, limit: 1, provider_id: state.modelProvider?.id ?? ""
      }));
      if (state.modelGroup !== group) return;
      state.modelGroup = next.models[0] ?? null;
      state.offers = [];
      await showModelView();
    });
}
$("remove-model-group").addEventListener("click", () => {
  const group = state.modelGroup;
  if (!group || state.modelProvider || offerBatch?.running || offerTests.size) return;
  const known = new Set(state.offers.map(scopeID));
  confirmRemoval("刪除整組模型", group.name,
    "會移除整組連線，含已封存的連線。 " + removeConnectionImpact,
    {kind: "MODEL_GROUP", canonical_id: group.id}, async () => {
      const onlyThisGroup = state.routeGroup === group.id && !$("public-model").readOnly;
      pruneDraft(route => onlyThisGroup || known.has(scopeID(route)));
      state.modelGroup = null;
      state.offers = [];
      await showModelView();
    });
});
function removeProviderItem(kind, label, scope) {
  const title = {PROVIDER: "刪除供應商", ACCOUNT: "刪除帳戶", CREDENTIAL: "刪除上游 Key"}[kind];
  const impact = kind === "CREDENTIAL" ? "這把 Key、已儲存的舊版本和相關模型連線會被刪除。"
    : "會刪除這裡的所有上游 Key 和相關模型連線。";
  confirmRemoval(title, label, impact + " " + removeConnectionImpact, {kind, ...scope}, async () => {
    pruneDraft(route => route.provider_id === scope.provider_id &&
      (!scope.account_id || route.account_id === scope.account_id) &&
      (!scope.credential_id || route.credential_id === scope.credential_id));
    if (kind === "PROVIDER" && state.providerID === scope.provider_id) {
      state.providerID = "";
      if (state.modelProvider?.id === scope.provider_id) {
        state.modelProvider = null;
        state.modelGroup = null;
        state.offers = [];
      }
    }
    await showProviderView();
  });
}

function offerTestable(v) {
  return ["CHAT_COMPLETIONS", "RESPONSES", "ANTHROPIC_MESSAGES", "GEMINI_NATIVE"].includes(v.operation);
}

function testOffer(v) {
  return api(v.operation === "CHAT_COMPLETIONS" ? "/gateway/probe" : "/models/test", selection(v));
}

function renderOfferBatch() {
  $("offer-batch").disabled = !!offerBatch?.running || !!offerTests.size || !state.offers.some(v => v.enabled && offerTestable(v));
}

function updateBatchSelection() {
  const count = offerBatch.items.filter(item => item.input.checked).length;
  const all = $("offer-batch-all");
  all.checked = count === offerBatch.items.length;
  all.indeterminate = count > 0 && !all.checked;
  $("offer-batch-start").disabled = !count;
  $("offer-batch-progress").textContent = "已選 " + count + " / " + offerBatch.items.length + " 條連線";
}

function openOfferBatch() {
  const candidates = new Map();
  for (const v of state.offers) {
    if (v.enabled && offerTestable(v)) candidates.set(scopeID(v), v);
    if (candidates.size === 100) break;
  }
  const entries = [...candidates.values()];
  if (!entries.length) return;
  const items = entries.map(v => {
    const label = el("label", undefined, "choice batch-choice");
    const input = el("input");
    input.type = "checkbox";
    input.checked = true;
    const details = el("span", undefined, "identity");
    details.append(el("strong", v.provider), el("small", [v.account, v.credential, v.group].filter(Boolean).join(" · ")),
      el("small", v.model_id + " · " + (operationNames[v.operation] || v.operation)));
    const result = el("small", "", "batch-result quiet");
    label.append(input, details, result);
    return {v, input, result, label};
  });
  offerBatch = {group: state.modelGroup, items, running: false, stop: false};
  $("offer-batch-list").replaceChildren(...items.map(item => item.label));
  $("offer-batch-all").disabled = false;
  $("offer-batch-start").hidden = false;
  $("offer-batch-stop").disabled = false;
  updateBatchSelection();
  $("offer-batch-dialog").showModal();
}

async function runOfferBatch() {
  const batch = offerBatch;
  if (!batch || batch.running || offerTests.size) return;
  const entries = batch.items.filter(item => item.input.checked);
  if (!entries.length) return;
  batch.running = true;
  renderOfferBatch();
  $("offer-batch-all").disabled = true;
  for (const item of batch.items) item.input.disabled = true;
  for (const item of entries) item.result.textContent = "待測";
  $("offer-batch-start").hidden = true;
  $("offer-batch-close").disabled = true;
  $("offer-batch-stop").hidden = false;
  $("offer-batch-stop").focus();
  let completed = 0, succeeded = 0, reason = "";
  const progress = $("offer-batch-progress");
  try {
    for (const entry of entries) {
      if (batch.stop || state.page !== "models" || state.modelGroup !== batch.group) {
        reason = "已停止";
        break;
      }
      progress.textContent = "測試 " + (completed + 1) + " / " + entries.length + " · " + entry.v.provider;
      entry.result.textContent = "測試中";
      let result;
      try {
        result = await testOffer(entry.v);
      } catch (error) {
        entry.result.textContent = "結果未確認";
        reason = "本筆結果未確認 · " + error.message;
        break;
      }
      completed++;
      if (result.outcome === "SUCCESS") succeeded++;
      const status = result.http_status ?? result.details?.http_status;
      const ttft = result.ttft_ms ?? result.details?.ttft_ms;
      entry.result.textContent = (statuses[result.outcome] || result.outcome) + (status ? " · HTTP " + status : "") +
        (ttft == null ? "" : " · 首字 " + delay(ttft));
      if (status === 429 || status === 402 || ["RATE_LIMITED", "PAYMENT_REQUIRED", "REQUEST_IN_PROGRESS", "CANCELLED", "TELEMETRY_SAVE"].includes(result.outcome)) {
        reason = statuses[result.outcome] || "上游額度或服務限制";
        break;
      }
    }
  } finally {
    for (const entry of entries) {
      if (entry.result.textContent === "待測") entry.result.textContent = "未執行";
    }
    let summary = "已完成 " + completed + " / " + entries.length + "，成功 " + succeeded + (reason ? " · " + reason : "");
    if (state.page === "models" && state.modelGroup === batch.group) {
      try { await loadOffers(true, Math.min(200, Math.max(50, state.offers.length))); }
      catch { summary += " · 清單更新失敗，請重新整理。"; }
    }
    progress.textContent = summary;
    $("offer-batch-summary").textContent = summary;
    $("offer-batch-summary").dataset.group = batch.group.id;
    batch.running = false;
    renderOfferBatch();
    $("offer-batch-stop").hidden = true;
    $("offer-batch-close").disabled = false;
    $("offer-batch-close").focus();
  }
}

async function showModelView() {
  const group = state.modelGroup;
  if ($("offer-batch-summary").dataset.group !== group?.id) {
    $("offer-batch-summary").textContent = "";
  }
  $("model-index").hidden = !!group;
  $("model-detail").hidden = !group;
  if (group) {
    groupRead?.abort();
    $("model-title").replaceChildren(group.brand ? modelBrandIcon(group.brand) : modelIcon(group.id, group.name), el("span", group.name));
    const links = $("model-tool-links");
    if (links.dataset.model !== group.id) {
      links.dataset.model = group.id;
      $("model-tool-menu").open = false;
      links.replaceChildren(toolLink("鵜鶘測試", "verification", {model: group.id}, "bicycle"),
        toolLink("價格比較", "prices", {model: group.id}, "tag"));
    }
    $("route-pick").hidden = true;
    $("remove-model-group").hidden = true;
    if (!state.offers.length) {
      $("model-tool-menu").hidden = true;
      $("model-tool-menu").open = false;
      $("offer-count").textContent = "";
      $("offer-list").replaceChildren(empty("讀取連線中…"));
      $("offers-more").hidden = true;
      renderOfferBatch();
    }
    return loadOffers(true, Math.min(200, Math.max(50, state.offers.length)));
  }
  offerRead?.abort();
  return loadGroups(true);
}

async function loadGroups(reset) {
  if (state.page !== "models" || state.modelGroup) return;
  const list = $("model-list");
  const more = $("models-more");
  const search = $("model-search").value.trim();
  const providerID = state.modelProvider?.id ?? "";
  const type = $("model-type").value;
  const brand = management.modelBrand;
  const offset = reset ? 0 : modelOffset;
  if (offset === null) return;
  groupRead?.abort();
  const read = new AbortController();
  groupRead = read;
  const params = new URLSearchParams({
    facets: "1", limit: String(modelGroupPageSize), offset: String(offset), q: search, type, brand, provider_id: providerID
  });
  $("model-provider-filter").hidden = !state.modelProvider;
  $("model-provider-name").textContent = state.modelProvider ? state.modelProvider.name + " 提供的模型" : "";
  if (reset) {
    more.hidden = true;
    modelOffset = null;
    $("model-count").textContent = "";
    list.replaceChildren(empty("讀取模型中…"));
  }
  list.setAttribute("aria-busy", "true");
  const isCurrent = () => groupRead === read && !read.signal.aborted && state.page === "models" && !state.modelGroup;
  try {
    const page = await api("/gateway/model-groups?" + params, undefined, read.signal);
    if (!isCurrent()) return;
    const available = new Set(Object.keys(page.facets.all));
    if (search && providerID === brandProviderID) {
      for (const key of modelBrandsAvailable) available.add(key);
    }
    if (reset && !available.has(brand)) {
      management.modelBrand = "";
      return await loadGroups(true);
    }
    management.modelBrand = brand;
    modelFacets = page.facets;
    modelBrandsAvailable = available;
    brandProviderID = providerID;
    modelOffset = offset + page.models.length;
    renderModelGroups(page.models, reset, type, brand, providerID || search);
    more.hidden = !page.more;
    if ($("model-filter-dialog").open) renderBrandChoices();
  } catch (error) {
    if (isCurrent() && reset && error.name !== "AbortError") list.replaceChildren(empty("讀取模型失敗，請重新整理。"));
    throw error;
  } finally {
    if (groupRead === read) list.removeAttribute("aria-busy");
  }
}

function brandPickerIcon(brand) {
  if (!brand || brand === unclassifiedBrand) return icon("model");
  const mark = icon("brand-" + brand, "/gateway-model-brands.svg");
  mark.classList.add("brand-picker-logo");
  return mark;
}

function renderBrandChoices() {
  const grid = $("brand-picker-choices");
  const focused = grid.contains(document.activeElement) ? document.activeElement.dataset.brand : undefined;
  const choices = [];
  const counts = modelFacets[$("model-type").value] ?? {};
  for (const [brand, name] of modelBrandLabels) {
    if (!modelBrandsAvailable.has(brand)) continue;
    const choice = button("", () => {
      management.modelBrand = brand;
      return applyModelFilters();
    });
    choice.className = "brand-picker-choice";
    choice.dataset.brand = brand;
    choice.setAttribute("aria-label", name);
    const selected = brand === management.modelBrand;
    choice.setAttribute("aria-pressed", String(selected));
    const mark = el("span", undefined, "item-icon");
    mark.append(brandPickerIcon(brand));
    const label = el("span", undefined, "brand-picker-choice-label");
    label.append(el("strong", name), el("small", (counts[brand] || 0) + " 個模型"));
    if (selected) {
      const check = icon("check");
      check.classList.add("brand-picker-check");
      mark.append(check);
    }
    choice.append(mark, label);
    choices.push(choice);
  }
  grid.replaceChildren(...choices);
  if (focused !== undefined) {
    (choices.find(choice => choice.dataset.brand === focused) || choices[0])?.focus({preventScroll: true});
  }
}

function applyModelFilters() {
  renderBrandChoices();
  return loadGroups(true);
}

function renderModelGroups(groups, reset, type, brand, filtered) {
  const trigger = $("model-filter-trigger");
  const active = type !== "all" || !!brand;
  trigger.toggleAttribute("data-active", active);
  trigger.setAttribute("aria-label", active ? "篩選模型，已套用篩選" : "篩選模型");
  $("model-count").textContent = (modelFacets[type]?.[brand] || 0) + " 個模型";
  const nodes = groups.map(group => {
    const tile = button("", async () => {
      state.modelGroup = group;
      state.offers = [];
      $("offer-sort").value = $("default-offer-sort").value;
      await showPage("models");
      $("model-title").focus({preventScroll: true});
    });
    tile.className = "model-tile";
    const label = el("span", undefined, "model-tile-label");
    label.append(el("strong", group.name), el("small", group.providers + " 家供應商 · " + group.connections + " 個連線"));
    const terms = gatewayTermsLabel(group.terms?.[type]);
    if (terms) label.append(el("small", terms, "model-terms-line"));
    tile.append(modelBrandIcon(group.brand), label, icon("chevron"));
    return tile;
  });
  const list = $("model-list");
  if (reset) list.replaceChildren(...(nodes.length ? nodes : [empty(filtered || active ? "找不到符合的模型。" : "新增供應商後，模型會出現在這裡。")]));
  else list.append(...nodes);
}

async function loadOffers(reset, limit = 50) {
  const group = state.modelGroup;
  if (state.page !== "models" || !group) return;
  offerRead?.abort();
  const read = new AbortController();
  offerRead = read;
  const params = new URLSearchParams({
    limit: String(limit), offset: String(reset ? 0 : state.offers.length), canonical_id: group.id,
    sort: $("offer-sort").value, provider_id: state.modelProvider?.id ?? ""
  });
  $("offer-list").setAttribute("aria-busy", "true");
  $("offers-more").disabled = true;
  try {
    const result = await api("/gateway/offerings?" + params, undefined, read.signal);
    if (offerRead !== read || read.signal.aborted || state.page !== "models" || state.modelGroup !== group) return;
    state.offers = reset ? result.offerings : state.offers.concat(result.offerings);
    $("offers-more").hidden = !result.more;
    renderOffers(reset ? null : result.offerings);
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
      p.latency_ms == null ? "" : "總延遲 " + number(p.latency_ms) + " ms", "clock"),
    metric("生成速度", p.tps == null ? "—" : number(p.tps, 1) + " tok/s", "", "bolt"),
    metric("成功率", ratio(p.successes, p.samples), p.samples ? p.successes + " / " + p.samples + " 次" : "未實測", "check"));
  return metrics;
}

function renderOffers(appendRows = null) {
  const pickedScopes = new Set(state.routes.map(scopeID));
  const cards = (appendRows ?? state.offers).map(v => {
    const row = el("article", undefined, "entry offering-entry");
    const relayable = v.operation === "CHAT_COMPLETIONS";
    const scope = scopeID(v);
    const probe = button(offerTests.has(scope) ? "測速中…" : "測速", async () => {
      if (offerBatch?.running || offerTests.has(scope)) return;
      const group = state.modelGroup;
      offerTests.add(scope);
      probe.lastChild.textContent = "測速中…";
      renderOfferBatch();
      let result;
      try {
        result = await testOffer(v);
      } finally {
        offerTests.delete(scope);
        const current = [...$("offer-list").querySelectorAll("[data-test-scope]")].find(node => node.dataset.testScope === scope);
        if (current) {
          current.lastChild.textContent = "測速";
          current.disabled = !state.offers.find(row => scopeID(row) === scope)?.enabled;
        }
        renderOfferBatch();
      }
      if (state.modelGroup === group) await loadOffers(true, Math.min(200, Math.max(50, state.offers.length)));
      notice(v.provider + " · " + (statuses[result.outcome] || result.outcome || "測速完成"), result.outcome !== "SUCCESS");
    }, {glyph: "gauge"});
    probe.dataset.testScope = scope;
    probe.disabled = !v.enabled || offerTests.has(scope);
    probe.hidden = !offerTestable(v);
    const picked = pickedScopes.has(scope);
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
      renderOffers();
    }, {glyph: picked ? "minus" : "plus"});
    choose.hidden = !relayable || !state.routePicking;
    choose.disabled = !v.enabled && !picked;
    choose.setAttribute("aria-pressed", String(picked));
    const details = identity(v.provider, [[v.account, v.credential, v.group].filter(Boolean).join(" · "),
      v.model_id + (relayable ? "" : " · " + (operationNames[v.operation] || v.operation)), gatewayTermsLabel(v.terms),
      v.enabled ? "" : "連線不可用"]);
    const view = button(v.provider, () => openProvider(v.provider_id, v));
    view.classList.add("quiet-button", "provider-name");
    view.dataset.offerScope = scope;
    view.append(icon("chevron"));
    details.querySelector("h3").replaceChildren(view);
    const history = button("連線詳情", () => openOfferDetail(v), {glyph: "info"});
    const actions = controls(probe, history, choose);
    if (v.operation === "IMAGES_GENERATION") {
      const image = button("測試出圖", () => openOfferImageTest(v), {glyph: "model"});
      image.disabled = !v.enabled;
      actions.prepend(image);
    }
    row.append(details, performanceCells(v.performance), actions);
    return row;
  });
  const group = state.modelGroup;
  const provider = state.modelProvider;
  $("remove-model-group").hidden = !!provider || !state.offers.length;
  $("route-pick").hidden = !state.offers.some(v => v.operation === "CHAT_COMPLETIONS");
  $("model-tool-menu").hidden = $("remove-model-group").hidden && $("route-pick").hidden;
  $("route-pick").setAttribute("aria-pressed", String(state.routePicking));
  const scopeLabel = provider ? provider.name : group.providers + " 家供應商";
  $("offer-count").textContent = scopeLabel + " · " + group.connections + " 個連線";
  const list = $("offer-list");
  if (appendRows) list.append(...cards);
  else list.replaceChildren(...(cards.length ? cards : [empty("這個模型目前沒有連線。")]));
  renderRoutes();
  renderOfferBatch();
}

function openModelGrouping(v) {
  management.align = v;
  $("group-scope").textContent = v.provider + " / " + v.model_id;
  $("group-form").elements.canonical_id.value = v.canonical_id;
  $("group-dialog").showModal();
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
  if (q.source === "MANUAL") return [q.account, q.credential, "手動"].filter(Boolean).join(" · ");
  if (q.role === "ACCOUNT_PAT") return q.account + " · 站方帳戶";
  return q.account + " / " + q.credential;
}
function httpsLink(label, url) {
  if (!url) return null;
  try { if (new URL(url).protocol !== "https:") return null; } catch { return null; }
  const link = el("a", label, "link-button");
  link.append(icon("external"));
  link.href = url;
  link.target = "_blank";
  link.rel = "noopener noreferrer";
  return link;
}
function providerFacts(p, summary) {
  const facts = el("div", undefined, "provider-facts");
  facts.append(performanceCells(p.performance));
  if (summary) return facts;
  const meta = el("div", undefined, "provider-meta");
  if (p.quotas.length) {
    const limits = el("div");
    const caption = el("small", "剩餘額度", "quiet icon-label");
    caption.prepend(icon("wallet"));
    limits.append(caption);
    for (const q of p.quotas.slice(0, 3)) {
      limits.append(el("div", quotaValue(q) + " · " + quotaSource(q), "quota-line"));
    }
    meta.append(limits);
  }
  if (p.requires_checkin || p.checkin_offered != null) meta.append(badge(checkinSummary(p), undefined, "calendar"));
  if (meta.children.length) facts.append(meta);
  return facts;
}

async function showProviderView() {
  $("provider-index").hidden = !!state.providerID;
  $("provider-detail").hidden = !state.providerID;
  if (state.providerID) {
    const current = management.provider?.id === state.providerID;
    $("provider-title").textContent = current ? management.provider.name : "供應商";
    $("provider-edit").disabled = !current;
    const label = providerReturn?.id === state.providerID && providerReturn.group ? "返回模型比較" : "返回供應商列表";
    $("providers-back").setAttribute("aria-label", label);
    $("providers-back").title = label;
    return loadProvider();
  }
  detailRead?.abort();
  return loadProviderList(true);
}

async function openProvider(id, offer) {
  providerReturn = {id, scroll: window.scrollY, group: offer && state.modelGroup?.id, scope: offer && scopeID(offer)};
  state.providerID = id;
  $("provider-detail-content").replaceChildren(empty("讀取供應商…"));
  await showPage("providers");
  if (state.page === "providers" && state.providerID === id) $("provider-title").focus({preventScroll: true});
}

async function loadProviderList(reset) {
  if (state.page !== "providers" || state.providerID) return;
  providerRead?.abort();
  const read = new AbortController();
  providerRead = read;
  const params = new URLSearchParams({limit: "25", offset: String(reset ? 0 : management.providers.length), q: $("provider-search").value.trim()});
  const data = await api("/gateway/providers?" + params, undefined, read.signal);
  if (providerRead !== read || read.signal.aborted || state.page !== "providers" || state.providerID) return;
  management.providers = reset ? data.providers : management.providers.concat(data.providers);
  $("providers-more").hidden = !data.more;
  $("provider-count").textContent = (data.more ? "已載入 " : "") + management.providers.length + " 家 · 近 7 天實測";
  const cards = data.providers.map(p => {
    const row = el("article", undefined, "entry provider-tile");
    const intro = identity(p.name, [p.base_url], "provider");
    if (p.description) intro.querySelector("h3").after(el("p", p.description, "provider-preview"));
    const view = button("管理 " + p.name, () => openProvider(p.id), {glyph: "chevron", iconOnly: true});
    view.classList.add("provider-open");
    view.dataset.provider = p.id;
    row.append(intro, providerFacts(p, true), controls(view));
    return row;
  });
  const list = $("provider-list");
  if (reset) list.replaceChildren(...(cards.length ? cards : [empty("目前沒有符合的供應商。")]));
  else list.append(...cards);
}

async function loadProvider() {
  if (state.page !== "providers" || !state.providerID) return;
  providerRead?.abort();
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
  if (detailRead !== read || read.signal.aborted || state.page !== "providers" || state.providerID !== id) return;
  const provider = result.providers[0];
  if (!provider) throw new Error("找不到供應商。");
  const checkins = provider.requires_checkin ? await api("/checkins/accounts" + params + "&timezone=" + encodeURIComponent(result.timezone), undefined, read.signal) : null;
  if (detailRead !== read || read.signal.aborted || state.page !== "providers" || state.providerID !== id) return;
  Object.assign(management, {provider, accounts, credentials, checkins});
  renderProvider();
}

function openProviderProfile(p) {
  const form = $("provider-form");
  for (const field of ["name", "description", "website", "checkin_url"]) form.elements[field].value = p[field] || "";
  form.elements.requires_checkin.checked = p.requires_checkin;
  form.elements.checkin_url.required = p.requires_checkin;
  $("provider-dialog").showModal();
}

function openConnectionDialog(v, operation, imageModel = "") {
  management.connection = {upstream: v, operation};
  const field = $("connection-value");
  field.value = imageModel;
  field.type = operation === "key" ? "password" : "text";
  field.maxLength = operation === "key" ? 8192 : 256;
  $("connection-title").textContent = operation === "key" ? "更新上游 Key" : "手動新增模型";
  $("connection-value-label").textContent = operation === "key" ? "新的 API Key" : "模型 ID";
  $("connection-model-type").hidden = operation !== "model";
  field.form.elements.endpoint.value = imageModel ? "IMAGES_GENERATION" : "CHAT_COMPLETIONS";
  $("connection-dialog").showModal();
}
function updateProviderProxyFields() {
  const form = $("provider-proxy-form");
  const usingProxy = form.elements.mode.value === "PROXY";
  $("provider-proxy-fields").hidden = !usingProxy;
  form.elements.proxy_url.disabled = !usingProxy;
  form.elements.proxy_url.required = usingProxy && !management.proxy?.configured;
}

function openProviderProxy(p) {
  const proxy = p.proxy || {mode: "DIRECT", revision: 0, configured: false};
  management.proxy = {provider_id: p.id, revision: proxy.revision, configured: proxy.configured};
  const form = $("provider-proxy-form");
  form.reset();
  form.elements.mode.value = proxy.mode;
  $("provider-proxy-scope").textContent = p.name;
  $("provider-proxy-current").textContent = proxy.url ? "目前代理 · " + proxy.url : "";
  $("provider-proxy-current").hidden = !proxy.url;
  updateProviderProxyFields();
  $("provider-proxy-dialog").showModal();
}

function openAccountDialog(account, kind) {
  management.account = account;
  $(kind + "-form").reset();
  $(kind + "-dialog").showModal();
}
function openQuota(scope, quota, label) {
  management.quotaScope = scope;
  const form = $("quota-form");
  form.reset();
  $("quota-dialog-title").textContent = scope.credential_id ? "設定 Key 額度" : "記錄帳戶額度";
  $("quota-scope").textContent = label + " · 手動填寫，可隨時調整。";
  $("clear-manual-quota").hidden = !quota;
  if (quota) {
    form.elements.remaining.value = quota.remaining;
    form.elements.unit.value = quota.unit;
  }
  $("quota-dialog").showModal();
}
function quotaDetail(q) {
  const extras = [q.used === "" ? "" : "已用 " + q.used, q.granted === "" ? "" : "總額 " + q.granted].filter(Boolean);
  return [quotaValue(q), ...extras, new Date(q.observed_at).toLocaleString("zh-TW")].join(" · ");
}
async function syncProviderCatalog(v) {
  const id = v.credential_id;
  try {
    const result = await api("/gateway/sync", connectionFields(v));
    if (result.models_seen) providerCatalogIssues.delete(id);
    else providerCatalogIssues.set(id, "站方沒有列出模型。");
    return result;
  } catch (error) {
    if (error.name === "AbortError") throw error;
    providerCatalogIssues.set(id, "讀取站方模型清單失敗。 " + error.message);
  }
}

function renderCredential(p, c, expanded) {
  const v = {...c, credential_id: c.id};
  const row = el("article", undefined, "entry compact-entry credential-row");
  const quotas = p.quotas.filter(q => q.credential_id === c.id && q.account_id === c.account_id);
  const quota = quotas.find(q => q.source !== "MANUAL");
  const manual = quotas.find(q => q.source === "MANUAL");
  const inference = c.role === "INFERENCE";
  const anonymous = inference && (c.auth_mode || p.auth_mode) === "NONE";
  row.append(identity(c.label || "Key", [
    [inference ? "推論 Key" : "帳戶 PAT", c.enabled ? "已啟用" : "已停用", c.group].filter(Boolean).join(" · "),
    c.base_url !== p.base_url ? c.base_url : "", c.note,
    manual ? "手動額度 · " + quotaDetail(manual) : "",
    quota ? "站方額度 · " + quotaDetail(quota) : ""
  ]));
  const actions = controls();
  const more = el("details", undefined, "details-card credential-tools");
  const summary = el("summary", "Key 設定");
  summary.prepend(icon("chevron"));
  const extra = controls();
  more.dataset.credential = c.id;
  more.open = expanded.has(c.id);
  more.append(summary, extra);
  if (!anonymous) actions.append(credentialCopyButton(v));
  let catalogHelp;
  if (inference) {
    const sync = button("同步模型", async () => {
      const result = await syncProviderCatalog(v);
      if (state.page !== "providers" || state.providerID !== p.id) return;
      await loadProvider();
      if (result?.models_seen) notice("同步完成 · " + result.models_seen + " 個模型");
    }, {glyph: "refresh"});
    const add = button("手動加模型", () => openConnectionDialog(v, "model"), {glyph: "plus"});
    sync.disabled = add.disabled = !c.enabled;
    actions.append(sync);
    const issue = c.enabled && providerCatalogIssues.get(c.id);
    if (issue) {
      catalogHelp = el("div", undefined, "section-inline catalog-recovery");
      catalogHelp.append(el("p", issue, "quiet"), add);
    } else {
      extra.append(add);
    }
    extra.append(button("設定 Key 額度", () => openQuota(connectionFields(v), manual, c.label), {glyph: "wallet"}));
  }
  if (!anonymous) {
    const quotaButton = button("更新額度", async () => {
      await api("/gateway/quota/refresh", {...connectionFields(v), role: c.role});
      await loadProvider();
      notice("已讀取站方額度。");
    }, {glyph: "wallet"});
    quotaButton.disabled = !c.enabled;
    (inference ? extra : actions).append(quotaButton);
    extra.append(button("換 Key", () => openConnectionDialog(v, "key"), {glyph: "key"}));
  }
  extra.append(button(c.enabled ? "停用" : "啟用", async () => {
    await api("/credentials/toggle", {provider_id: p.id, account_id: c.account_id, ids: [c.id], enabled: !c.enabled});
    await loadProvider();
    notice(c.enabled ? "Key 已停用。" : "Key 已啟用。");
  }, {glyph: c.enabled ? "pause" : "play"}));
  extra.append(deleteButton("刪除上游 Key", () => removeProviderItem("CREDENTIAL", c.label, connectionFields(v)), false));
  row.append(actions, more);
  if (catalogHelp) row.append(catalogHelp);
  return row;
}
function renderAccount(p, a, expanded) {
  const card = el("div", undefined, "card");
  const heading = el("div", undefined, "section-inline");
  const manual = p.quotas.find(q => q.role === "ACCOUNT" && q.account_id === a.id && q.source === "MANUAL");
  const scope = {provider_id: p.id, account_id: a.id};
  heading.append(el("h2", a.label || "帳戶"), controls(button("新增 Key", () => openAccountDialog(a, "credential"), {glyph: "plus"}),
    button("記錄額度", () => openQuota(scope, manual, a.label), {glyph: "wallet"}),
    deleteButton("刪除帳戶", () => removeProviderItem("ACCOUNT", a.label, scope))));
  card.append(heading);
  for (const q of p.quotas.filter(q => q.account_id === a.id && q.role !== "INFERENCE")) {
    card.append(el("p", quotaSource(q) + " · " + quotaDetail(q), "quota-line"));
  }
  const check = management.checkins?.accounts.find(v => v.account_id === a.id);
  if (check?.required) {
    const line = el("div", undefined, "checkin-line");
    line.append(badge(check.completed_at ? "今日簽到已記錄" : "今日尚未記錄簽到", check.completed_at ? "success" : "quiet", "calendar"));
    if (!check.completed_at) line.append(button("我已簽到", async () => {
      const {date, timezone} = management.checkins;
      await api("/checkins/accounts", {provider_id: p.id, account_id: a.id, timezone, date});
      await loadProvider();
      notice("已記錄這個帳戶的簽到。");
    }, {glyph: "check"}));
    card.append(line);
  }
  const list = el("div", undefined, "rows");
  for (const c of management.credentials.filter(v => v.account_id === a.id)) list.append(renderCredential(p, c, expanded));
  if (!list.children.length) list.append(empty("這個帳戶還沒有 Key。"));
  card.append(list);
  return card;
}
function renderProvider() {
  const p = management.provider;
  const content = $("provider-detail-content");
  const expanded = new Set([...content.querySelectorAll(".credential-tools[open]")].map(node => node.dataset.credential));
  $("provider-title").textContent = p.name;
  $("provider-edit").disabled = false;
  const profile = el("div", undefined, "card stack provider-profile");
  const base = el("code", p.base_url);
  const copyURL = button("複製 Base URL", () => copy(p.base_url, base), {glyph: "copy", iconOnly: true});
  const endpoint = el("div", undefined, "provider-endpoint");
  endpoint.append(base, copyURL);
  if (p.description) {
    if (p.description.length > 140) {
      const intro = el("details", undefined, "details-card provider-about");
      intro.open = content.querySelector(".provider-about")?.open ?? false;
      const summary = el("summary", "供應商介紹");
      summary.prepend(icon("chevron"));
      const preview = p.description.slice(0, 140).trimEnd() + "…";
      intro.append(summary, el("p", p.description, "provider-description"));
      profile.append(el("p", preview, "provider-description-preview"), intro);
    } else {
      profile.append(el("p", p.description, "provider-description"));
    }
  }
  profile.append(endpoint);
  if (p.proxy) {
    const network = p.proxy.mode === "PROXY" ? (p.proxy.configured ? "代理 · " + p.proxy.url : "此裝置尚未設定代理") : "直連";
    profile.append(el("p", network, "quiet provider-network"));
  }
  profile.append(providerFacts(p, false), el("p", "近 7 天實測", "quiet"));
  const tools = controls(button("查看模型", async () => {
    state.modelGroup = null;
    state.modelProvider = p;
    management.modelBrand = "";
    $("model-search").value = "";
    await showPage("models");
  }, {glyph: "model"}));
  if (p.requires_checkin) {
    const checkin = httpsLink("前往簽到", p.checkin_url);
    tools.prepend(checkin || button("設定簽到網址", () => {
      openProviderProfile(p);
      $("provider-form").elements.checkin_url.focus();
    }, {glyph: "calendar"}));
  }
  const link = httpsLink("開啟網站", p.website);
  if (link) tools.append(link);
  tools.append(toolLink("站方資料", "providers", {provider: p.id, tab: "site"}, "provider"));
  profile.append(tools);
  const accounts = el("div", undefined, "section-inline");
  accounts.append(el("h2", "帳戶與 Key"), button("新增帳戶", () => openAccountDialog(null, "account"), {glyph: "plus"}));
  const advanced = el("details", undefined, "card details-card provider-advanced");
  advanced.open = content.querySelector(".provider-advanced")?.open ?? false;
  advanced.append(el("summary", "進階管理"), controls(
    button("連線設定", () => openProviderProxy(p), {glyph: "settings"}),
    toolLink("Key 版本與端點", "providers", {provider: p.id}),
    deleteButton("刪除供應商", () => removeProviderItem("PROVIDER", p.name, {provider_id: p.id}), false)
  ));
  content.replaceChildren(profile, accounts, ...management.accounts.map(a => renderAccount(p, a, expanded)), advanced);
}

for (const id of ["model-add-provider", "add-upstream"]) $(id).addEventListener("click", () => $("upstream-dialog").showModal());
$("provider-edit").addEventListener("click", () => openProviderProfile(management.provider));
$("provider-form").elements.requires_checkin.addEventListener("change", event => {
  const required = event.currentTarget;
  required.form.elements.checkin_url.required = required.checked;
});
$("models-back").addEventListener("click", () => action(null, () => {
  state.modelGroup = null;
  return showPage("models");
}));
$("providers-back").addEventListener("click", () => action(null, async () => {
  const from = providerReturn;
  providerReturn = null;
  state.providerID = "";
  const toModel = from?.group && state.modelGroup?.id === from.group;
  const page = toModel ? "models" : "providers";
  await showPage(page);
  if (!from || state.page !== page) return;
  window.scrollTo(0, from.scroll);
  const nodes = toModel ? $("offer-list").querySelectorAll("[data-offer-scope]") : $("provider-list").querySelectorAll("[data-provider]");
  [...nodes].find(node => toModel ? node.dataset.offerScope === from.scope : node.dataset.provider === from.id)
    ?.focus({preventScroll: true});
}));
$("clear-model-provider").addEventListener("click", () => action(null, () => {
  state.modelProvider = null;
  return loadGroups(true);
}));
$("model-filter-trigger").addEventListener("click", () => {
  renderBrandChoices();
  $("model-filter-dialog").showModal();
});
$("model-type").addEventListener("change", () => action(null, applyModelFilters));
for (const [id, load, abort] of [
  ["model", loadGroups, () => groupRead?.abort()], ["provider", loadProviderList, () => providerRead?.abort()]
]) {
  const more = $(id + "s-more");
  let timer;
  const search = () => {
    clearTimeout(timer);
    more.hidden = true;
    return action(null, () => load(true));
  };
  $(id + "-filters").addEventListener("submit", event => { event.preventDefault(); void search(); });
  $(id + "-search").addEventListener("input", () => {
    abort();
    clearTimeout(timer);
    more.hidden = true;
    timer = setTimeout(() => {
      if (state.page === id + "s" && (id === "model" ? !state.modelGroup : !state.providerID)) void search();
    }, 180);
  });
  more.addEventListener("click", () => action(more, () => load(false)));
}
$("offer-sort").addEventListener("change", () => action(null, () => loadOffers(true)));
$("offers-more").addEventListener("click", () => action(null, () => loadOffers(false)));
$("offer-batch").addEventListener("click", openOfferBatch);
$("offer-batch-list").addEventListener("change", updateBatchSelection);
$("offer-batch-all").addEventListener("change", event => {
  for (const item of offerBatch.items) item.input.checked = event.target.checked;
  updateBatchSelection();
});
submit("offer-batch-form", runOfferBatch);
$("offer-batch-stop").addEventListener("click", () => {
  offerBatch.stop = true;
  $("offer-batch-stop").disabled = true;
  $("offer-batch-progress").textContent = "本筆完成後停止…";
});
$("offer-batch-dialog").addEventListener("cancel", event => {
  if (offerBatch?.running) event.preventDefault();
});
$("offer-batch-dialog").addEventListener("close", () => {
  if ($("offer-batch-dialog").open) return;
  offerBatch = null;
  $("offer-batch-list").replaceChildren();
});
function setRoutePicking(picking) {
  state.routePicking = picking;
  $("model-tool-menu").open = false;
  renderOffers();
}
$("route-pick").addEventListener("click", () => setRoutePicking(!state.routePicking));
$("exit-route-pick").addEventListener("click", () => setRoutePicking(false));

submit("upstream-form", async form => {
  const v = await api("/gateway/upstreams", Object.fromEntries(new FormData(form)));
  form.reset();
  $("upstream-dialog").close();
  providerReturn = null;
  state.providerID = v.provider_id;
  $("provider-detail-content").replaceChildren();
  const result = await syncProviderCatalog(v);
  await showPage("providers");
  notice(result?.models_seen ? "同步完成 · " + result.models_seen + " 個模型" : "供應商已儲存。");
});
submit("connection-form", async form => {
  const value = new FormData(form).get("value").trim();
  const {upstream, operation} = management.connection;
  if (operation === "key") await api("/credentials/rotate", {...connectionFields(upstream), secret: value, confirm: true});
  else await api("/gateway/upstream-model", {...connectionFields(upstream), model_id: value, endpoint: form.elements.endpoint.value});
  form.reset();
  $("connection-dialog").close();
  if (state.page === "models" && state.modelGroup) {
    $("offer-detail").close();
    await loadOffers(true, Math.min(200, Math.max(50, state.offers.length)));
  } else await loadProvider();
  notice(operation === "key" ? "上游 Key 已更新。" : "模型已加入。");
});
submit("group-form", async form => {
  const {provider_id, model_id} = management.align;
  await api("/gateway/model-group", {provider_id, model_id, canonical_id: form.elements.canonical_id.value});
  $("group-dialog").close();
  $("offer-detail").close();
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
$("provider-proxy-form").elements.mode.addEventListener("change", updateProviderProxyFields);
$("provider-proxy-dialog").addEventListener("close", () => { management.proxy = null; });
submit("provider-proxy-form", async form => {
  const scope = management.proxy;
  await api("/providers/proxy", {provider_id: scope.provider_id, revision: scope.revision, mode: form.elements.mode.value, proxy_url: form.elements.mode.value === "PROXY" ? form.elements.proxy_url.value : ""});
  form.reset();
  $("provider-proxy-dialog").close();
  if (state.page === "providers" && state.providerID === scope.provider_id) await loadProvider();
  notice("連線設定已儲存。");
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
async function saveManualQuota(data) {
  const scope = management.quotaScope;
  await api("/gateway/quota", {...scope, ...data});
  if (management.quotaScope === scope) $("quota-dialog").close();
  if (state.providerID === scope.provider_id) await loadProvider();
  notice(data.clear ? "已清除手動額度。" : "已儲存手動額度。");
}
submit("quota-form", form => saveManualQuota(Object.fromEntries(new FormData(form))));
$("clear-manual-quota").addEventListener("click", event => action(event.currentTarget, () => {
  if (window.confirm("要清除這筆手動額度嗎？站方回報仍會保留。")) return saveManualQuota({clear: true});
}));

action(null, async () => {
  const params = new URLSearchParams(location.search);
  const model = params.get("model"), provider = params.get("provider");
  if (model) {
    const result = await api("/gateway/model-groups?" + new URLSearchParams({id: model, limit: "1"}));
    if (result.models.length) state.modelGroup = result.models[0];
  }
  if (provider) return openProvider(provider);
  if (params.get("settings") === "open") $("settings-dialog").showModal();
  if (["providers", "usage"].includes(params.get("view"))) return showPage(params.get("view"));
  await refresh();
});
