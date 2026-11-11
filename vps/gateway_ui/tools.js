const toolQuery = new URLSearchParams(location.search);
const toolModel = toolQuery.get("model") || "";
const toolProvider = toolQuery.get("provider") || "";
const toolHeadings = {
  prices: ["價格比較", "各站標價與已確認的 Key 費率"],
  verification: ["鵜鶘騎單車"],
  requests: ["完整請求紀錄"]
};
let toolOffers = [];
let toolOffersMore = false;
let verificationPending = false;
let verificationOverviewRead;

function liteModelTitle(panel) {
  return panel === "models-hidden" ? "已隱藏與封存" : panel === "models-owned" ? "批次測試與整理" : "模型目錄";
}

async function startLiteTool() {
  const appearance = document.documentElement.dataset;
  try {
    const wallpaper = localStorage.getItem("modelatlas.wallpaper");
    const textSize = localStorage.getItem("modelatlas.textSize");
    if (Object.hasOwn(aquaWallpapers, wallpaper) || wallpaper === "plain") appearance.wallpaper = wallpaper;
    if (["compact", "normal", "large"].includes(textSize)) appearance.textSize = textSize;
    if (localStorage.getItem("modelatlas.motion") === "reduced") appearance.reduceMotion = "true";
  } catch {}
  const back = new URLSearchParams();
  if (toolModel) back.set("model", toolModel);
  else if (toolProvider) back.set("provider", toolProvider);
  else if (liteTool === "requests") back.set("view", "usage");
  else if (liteTool === "providers") back.set("view", "providers");
  else if (liteTool === "data") back.set("settings", "open");
  $("lite-tool-back").href = back.size ? "/?" + back : "/";
  if (toolModel) $("lite-tool-back").replaceChildren(icon("back"), document.createTextNode("返回模型"));
  else if (liteTool === "models") $("lite-tool-back").replaceChildren(icon("back"), document.createTextNode("返回我的模型"));
  $("header-refresh").addEventListener("click", () => refresh().catch(() => {}));
  if (liteTool === "models") {
    show("models");
    const tab = toolQuery.get("tab");
    const panel = tab === "batch" ? "models-owned" : tab === "retained" ? "models-hidden" : "models-catalog";
    $("models-title").nextElementSibling.hidden = true;
    $("models").querySelector(".workspace-tabs").hidden = true;
    for (const id of ["models-owned", "models-hidden", "models-catalog"]) {
      $(id).removeAttribute("role");
      $(id).setAttribute("aria-labelledby", "models-title");
    }
    const catalog = $("models-catalog");
    catalog.querySelector(":scope > summary").hidden = true;
    catalog.querySelector(".section-heading").hidden = true;
    catalog.querySelector(".catalog-filters > summary").textContent = "更多篩選";
    const search = $("catalog-search");
    search.parentElement.replaceChildren(document.createTextNode("搜尋模型"), search);
    const membership = $("catalog-membership");
    membership.value = catalogDefault("catalog-membership");
    const searchbar = catalog.querySelector(".catalog-searchbar");
    searchbar.append(membership.parentElement);
    const modelTools = el("nav", undefined, "catalog-model-tools");
    modelTools.setAttribute("aria-label", "整理已加入的模型");
    for (const [key, id] of [["batch", "models-owned"], ["retained", "models-hidden"]]) {
      const link = el("a", liteModelTitle(id));
      link.href = location.pathname + "?tab=" + key;
      modelTools.append(link);
    }
    searchbar.after(modelTools);
    if (panel !== "models-catalog") {
      $("lite-tool-back").href = location.pathname;
      $("lite-tool-back").replaceChildren(icon("back"), document.createTextNode("返回模型目錄"));
    }
    $("catalog-more").textContent = "載入更多模型";
    $("catalog-reset").textContent = "清除篩選";
    $("manual-add").textContent = "加入我的模型";
    workspace("models", panel);
  } else if (liteTool === "providers") {
    show("providers");
    if (toolQuery.get("tab") === "import") {
      workspace("providers", "providers-new");
      const panel = $("import-form").closest("details"), single = $("onboarding-form").closest("details");
      single.open = false;
      single.before(panel);
      panel.open = true;
    }
  } else if (liteTool === "data") {
    show("settings");
    const external = toolQuery.get("tab") === "services";
    $("settings-title").textContent = external ? "外部效能資料" : "備份與同步";
    $("settings-title").nextElementSibling.textContent = external ? "查看外部模型資料及同步紀錄。" : "管理本機設定、加密備份與裝置配對。";
    if (external) {
      $("service-history").classList.add("card");
    }
    workspace("settings", external ? "settings-services" : "settings-data");
  } else {
    const [title, summary] = toolHeadings[liteTool];
    $("lite-tool-title").textContent = title;
    if (summary) $("lite-tool-title").parentElement.append(el("p", summary, "tool-heading-note"));
    show("lite-tool-content");
  }
  try {
    await refresh();
    if (liteTool === "providers" && toolProvider) {
      const provider = providers.find(row => row.id === toolProvider);
      if (provider) await providerDetail(provider, toolQuery.get("tab") === "site" ? "site" : "keys");
    }
    if (liteTool === "providers" && toolQuery.get("tab") === "import") {
      const pending = await api("/onboarding");
      for (const row of pending.filter(review => review.status === "REVIEW")) {
        item($("onboarding-list"), row.provider.name, "待確認", () => openReview(row));
      }
    }
  } catch {
    // The existing workspace recovery card exposes a retry.
  }
}

async function refreshLiteToolSnapshot(requested) {
  if (liteTool === "data") {
    if (toolQuery.get("tab") === "services") await loadServiceReferences();
    else await Promise.all([loadWorkspaceSync(), loadSystemStatus(), loadCloudBackups()]);
  } else if (liteTool === "requests") {
    const values = await Promise.all([api("/providers"), api("/accounts"), api("/credentials")]);
    if (requested !== workspaceRefreshRequested) return;
    [providers, accounts, credentials] = values;
    const panel = el("div", undefined, "card tool-request-panel");
    $("lite-tool-results").replaceChildren(panel);
    const selected = scope(Object.fromEntries(toolQuery));
    const selection = selected.provider_id && selected.account_id && selected.credential_id && selected.model_id && selected.endpoint ? selected : null;
    await renderRequestHistory(panel, {selection}, () => requested === workspaceRefreshRequested);
  } else {
    if (liteTool === "verification" && verificationPending) return;
    const page = await readToolOffers(0);
    if (requested !== workspaceRefreshRequested || (liteTool === "verification" && verificationPending)) return;
    toolOffers = page.offerings;
    toolOffersMore = page.more;
    if (liteTool === "prices") renderPriceTool();
    else renderVerificationTool();
  }
  if (requested === workspaceRefreshRequested) workspaceHasData = true;
}

function readToolOffers(offset) {
  if (!toolModel) return Promise.resolve({offerings: [], more: false});
  const params = new URLSearchParams({canonical_id: toolModel, limit: "25", offset: String(offset)});
  if (liteTool === "prices") params.set("prices", "1");
  return api("/offerings?" + params);
}

function prepareToolModelRows() {
  providers = toolOffers.map(row => ({id: row.provider_id, name: row.provider, base_url: row.base_url, config: {}}));
  accounts = toolOffers.map(row => ({id: row.account_id, provider_id: row.provider_id, label: row.account}));
  credentials = toolOffers.map(row => ({id: row.credential_id, provider_id: row.provider_id, account_id: row.account_id, label: row.credential}));
  myModels = toolOffers.map(row => ({...row, state: "ACTIVE", metadata: {canonical_id: row.canonical_id}, offer: row.prices}));
  const caption = $("lite-tool-model");
  caption.textContent = toolOffers[0]?.display_name || toolModel;
  caption.hidden = !caption.textContent;
}

function moreToolOffers(parent, render) {
  if (toolOffersMore) return action(parent, "載入更多連線", async () => {
    const current = toolOffers;
    const page = await readToolOffers(current.length);
    if (current !== toolOffers || (liteTool === "verification" && verificationPending)) return;
    toolOffers = current.concat(page.offerings);
    toolOffersMore = page.more;
    render();
  });
}

function renderPriceTool() {
  prepareToolModelRows();
  const results = $("lite-tool-results");
  const list = el("div", undefined, "tool-row-list");
  results.replaceChildren(list);
  for (const model of myModels) {
    const card = el("article", undefined, "card");
    card.append(el("h2", model.provider), el("p", connectionLabel(model) + " · " + model.model_id, "section-description"));
    detailQuote(card, "站方標價", model.offer?.nominal_price || model.offer?.price);
    detailQuote(card, "已確認的 Key 費率", model.offer?.effective_price);
    if (model.terms?.price?.basis === "MANUAL") detailQuote(card, "手填價格", model.terms.price);
    if (model.terms?.daily) card.append(el("p", "每日額度 · " + gatewayDailyLabel(model.terms.daily) +
      (model.terms.daily.source === "USER" ? " · 手填" : " · 站方"), "section-description"));
    if (model.offer?.published_groups?.length) {
      const prices = el("details");
      prices.append(el("summary", "站方公布的各群組標價"));
      for (const quote of model.offer.published_groups) {
        detailQuote(prices, quote.group || "站方未命名群組", quote,
          Date.now() - quote.observed_at > 86400000 ? "可手動更新標價。" : undefined);
        action(prices, "群組價格歷史", () => openPublishedPriceHistory(model, quote.group));
      }
      card.append(prices);
    }
    const actions = el("div", undefined, "actions");
    const sync = action(actions, "同步站方價格", async () => {
      const result = await submit("/offerings/prices", scope(model));
      await refresh();
      notice(result.mapping_available ? "已讀取站方價目表。" : "已保存站方計費規則，這個版本的金額換算尚待確認。");
    });
    sync.disabled = !model.enabled;
    action(actions, "價格與歷史", () => {
      const box = modal("價格與歷史 · " + model.provider);
      detailQuote(box, "站方標價", model.offer?.nominal_price || model.offer?.price);
      detailQuote(box, "已確認的 Key 費率", model.offer?.effective_price);
      observationArchive(box, model, detailVersion, {price: true})();
    });
    card.append(actions);
    list.append(card);
  }
  if (!myModels.length) list.append(el("p", "找不到這個模型的連線。"));
  moreToolOffers(results, renderPriceTool);
}

async function openPublishedPriceHistory(model, group) {
  const box = modal(model.provider + " · " + (group || "未命名群組") + " · 標價歷史"), ticket = detailVersion;
  const rows = await api("/offerings/price-history?" + query(model) + "&price_group=" + encodeURIComponent(group));
  if (!detailCurrent(ticket)) return;
  box.append(el("p", "同一站點、模型和群組最近 100 筆公開標價。", "section-description"));
  const tracks = new Set(rows.map(row => row.facts?.price?.unit).filter(Boolean));
  for (const unit of tracks) {
    const series = rows.filter(row => row.facts?.price?.unit === unit);
    for (const [leg, title] of unit === "USD/request" ? [["fixed", "每次請求費"]] : [["input", "輸入價格"], ["output", "輸出價格"]]) {
      metricPlot(box, {title, unit, points: series.map(row => ({id: row.id, at: row.observed_at, value: row.facts.price[leg]}))});
    }
  }
  if (!tracks.size) box.append(el("p", "此範圍尚無價格紀錄。"));
}

const verificationTime = new Intl.DateTimeFormat("zh-TW", {
  year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit", hourCycle: "h23"
});
const verificationShortTime = new Intl.DateTimeFormat("zh-TW", {
  month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hourCycle: "h23"
});
const verificationOutcome = run => ({SUCCESS: "作品已儲存", INVALID_SVG: "回應無法預覽", UNCONFIRMED: "結果待確認"})[run.outcome]
  || outcomeLabels[run.outcome] || run.outcome;
const verificationPath = model => "/verification/runs?" + query(model);

function verificationArtwork(run, name) {
  const timestamp = verificationTime.format(new Date(run.created_at));
  const image = el("img", undefined, "tool-drawing");
  image.alt = "鵜鶘騎單車 · " + timestamp;
  image.src = "data:image/svg+xml," + encodeURIComponent(run.drawing);
  const zoom = el("button", undefined, "tool-drawing-trigger");
  const glyph = el("span", undefined, "tool-drawing-zoom");
  zoom.type = "button";
  zoom.setAttribute("aria-label", "放大" + name);
  glyph.append(icon("search"));
  zoom.append(image, glyph);
  zoom.addEventListener("click", () => {
    const box = modal("鵜鶘騎單車 · " + name), expanded = image.cloneNode();
    expanded.className = "tool-drawing-expanded";
    box.append(expanded, el("p", timestamp, "section-description"));
  });
  return zoom;
}

function renderVerificationTool() {
  prepareToolModelRows();
  const results = $("lite-tool-results");
  const previousStrip = $("verification-providers");
  const previousSelections = new Map([...results.querySelectorAll(".tool-provider-card")].map(card => [card.dataset.provider, card.dataset.scope]));
  const previousScope = $("verification-history")?.dataset.scope;
  const previousPair = [...results.querySelectorAll(".tool-compare-slot select")].map(select => select.value);
  verificationOverviewRead?.abort();
  const read = verificationOverviewRead = new AbortController();
  const rows = myModels.filter(model => chatOperation(model.operation));
  results.replaceChildren();
  if (!rows.length) {
    const empty = el("div", "這個模型目前沒有文字對話連線。", "card");
    results.append(empty);
    moreToolOffers(results, renderVerificationTool);
    return;
  }
  const groups = new Map();
  for (const model of rows) {
    if (!groups.has(model.provider_id)) groups.set(model.provider_id, []);
    groups.get(model.provider_id).push(model);
  }
  const overview = el("section", undefined, "card tool-verification-overview");
  const title = el("h2", "各家最新結果");
  title.id = "verification-overview-title";
  const heading = el("div", undefined, "tool-overview-heading");
  heading.append(title, el("small", groups.size + " 家供應商", "tool-strip-count"));
  const strip = el("div", undefined, "tool-provider-strip");
  strip.id = "verification-providers";
  strip.tabIndex = 0;
  strip.setAttribute("role", "region");
  strip.setAttribute("aria-labelledby", title.id);
  strip.style.setProperty("--provider-columns", String(Math.min(groups.size, 4)));
  overview.append(heading, strip, el("p", "每次測試使用所選 Key 發送一個請求。", "section-description tool-verification-note"));
  const history = el("section", undefined, "card tool-gallery");
  history.id = "verification-history";
  history.hidden = true;
  results.append(overview, history);
  let reloadHistory;
  const updateControls = [];
  const openHistory = (model, trigger, pair, focus) => {
    history.hidden = false;
    history.dataset.scope = key(model);
    strip.querySelectorAll("[aria-controls]").forEach(button => button.setAttribute("aria-expanded", String(button === trigger)));
    reloadHistory = renderVerificationHistory(history, model, pair, () => {
      history.hidden = true;
      delete history.dataset.scope;
      history.replaceChildren();
      trigger.setAttribute("aria-expanded", "false");
      trigger.focus();
    });
    if (focus) history.querySelector("h2").focus();
  };

  for (const [providerID, connections] of groups) {
    const byScope = new Map(connections.map(model => [key(model), model]));
    let model = byScope.get(previousSelections.get(providerID)) ?? connections.find(item => item.enabled) ?? connections[0];
    const card = el("article", undefined, "tool-provider-card");
    card.dataset.provider = providerID;
    const cardTitle = el("h3", model.provider);
    const connection = el("small", undefined, "tool-scope");
    const picker = el("select");
    picker.hidden = connections.length === 1;
    picker.setAttribute("aria-label", model.provider + " 連線");
    for (const [id, item] of byScope) {
      const name = [connectionLabel(item), item.model_id, operationLabels[item.operation] || item.operation];
      if (!item.enabled) name.push("已停用");
      option(picker, id, name.join(" · "));
    }
    picker.value = key(model);
    const header = el("div", undefined, "tool-provider-heading");
    header.append(cardTitle, picker, connection);
    const preview = el("div", undefined, "tool-provider-preview");
    const badge = el("span", undefined, "tool-provider-badge");
    badge.setAttribute("role", "status");
    const time = el("time");
    const meta = el("div", undefined, "tool-provider-meta");
    meta.append(badge, time);
    const artwork = el("div", undefined, "tool-provider-result");
    artwork.append(preview, meta);
    const actions = el("div", undefined, "tool-provider-actions");
    const run = el("button", "執行測試");
    run.type = "button";
    const past = action(actions, "歷次作品", () => openHistory(model, past, [], true));
    past.setAttribute("aria-controls", history.id);
    past.setAttribute("aria-expanded", "false");
    actions.prepend(run);
    card.append(header, artwork, actions);
    strip.append(card);

    let readVersion = 0;
    function showLatest(result, status = "尚未測試", kind = "") {
      badge.textContent = result ? verificationOutcome(result) : status;
      badge.dataset.outcome = result?.outcome || kind;
      time.hidden = !result;
      if (result) {
        const date = new Date(result.created_at);
        time.dateTime = date.toISOString();
        time.textContent = verificationShortTime.format(date);
        time.title = verificationTime.format(date);
      }
      const placeholder = el("div", undefined, "tool-drawing-empty");
      placeholder.append(icon("bicycle"));
      preview.replaceChildren(result?.drawing ? verificationArtwork(result, model.provider + "作品") : placeholder);
    }
    async function loadLatest() {
      const version = ++readVersion;
      showLatest(null, "讀取中…");
      try {
        const result = await api(verificationPath(model) + "&latest=1", {signal: read.signal});
        if (version === readVersion && card.isConnected) showLatest(result);
      } catch {
        if (!read.signal.aborted && version === readVersion && card.isConnected) showLatest(null, "紀錄讀取失敗", "READ_FAILED");
      }
    }
    function syncControls() {
      run.disabled = verificationPending || !model.enabled;
      picker.disabled = verificationPending;
    }
    function applySelection() {
      card.dataset.scope = key(model);
      connection.textContent = (connections.length === 1 ? connectionLabel(model) + " · " : "") +
        model.model_id + (model.enabled ? "" : " · 已停用");
      syncControls();
      void loadLatest();
    }
    picker.addEventListener("change", () => {
      const historyWasOpen = history.dataset.scope === key(model);
      model = byScope.get(picker.value);
      applySelection();
      if (historyWasOpen) openHistory(model, past, [], false);
    });
    run.addEventListener("click", async () => {
      if (verificationPending || !model.enabled) return;
      verificationPending = true;
      updateControls.forEach(update => update());
      readVersion++;
      run.textContent = "測試中…";
      showLatest(null, "正在等待回覆…");
      const id = crypto.randomUUID();
      let newest;
      try {
        newest = await submit("/verification/runs", {id, selection: scope(model)});
        showLatest(newest);
      } catch (error) {
        const recovered = await api(verificationPath(model) + "&id=" + encodeURIComponent(id), {signal: read.signal}).catch(() => null);
        if (recovered) {
          newest = recovered;
          showLatest(recovered);
        } else {
          showLatest(null, "結果待確認", "UNCONFIRMED");
        }
        notice(error.message, true);
      } finally {
        verificationPending = false;
        run.textContent = "執行測試";
        updateControls.forEach(update => update());
      }
      if (history.dataset.scope === key(model)) {
        await reloadHistory({prefer: newest?.id}).catch(error => notice(error.message, true));
      }
    });
    updateControls.push(syncControls);
    applySelection();
    if (key(model) === previousScope) openHistory(model, past, previousPair, false);
  }
  if (previousStrip) strip.scrollLeft = previousStrip.scrollLeft;
  moreToolOffers(overview, renderVerificationTool);
}

function renderVerificationHistory(gallery, model, previousPair = [], close) {
  let historyVersion = 0, previewVersion = 0, runs = [];
  const runPath = verificationPath(model);
  const compare = el("div", undefined, "tool-compare");
  const slots = ["作品 A", "作品 B"].map(name => {
    const column = el("div", undefined, "tool-compare-slot"), field = el("label", name), select = el("select");
    const preview = el("div", undefined, "tool-preview");
    select.setAttribute("aria-label", name);
    field.append(select);
    column.append(field, preview);
    compare.append(column);
    return {name, column, select, preview};
  });
  const savedDrawings = new Map();

  async function showPair() {
    const version = ++previewVersion;
    const ids = [...new Set(slots.map(({select}) => select.value).filter(Boolean))];
    compare.setAttribute("aria-busy", "true");
    for (const {select, preview} of slots) {
      if (preview.dataset.run !== select.value) {
        delete preview.dataset.run;
        preview.replaceChildren(el("p", "讀取作品…", "tool-drawing-empty"));
      }
    }
    try {
      const pairs = await Promise.allSettled(ids.map(id => savedDrawings.get(id) || api(runPath + "&id=" + encodeURIComponent(id))));
      if (!compare.isConnected || version !== previewVersion) return;
      const loaded = new Map();
      pairs.forEach((result, index) => {
        if (result.status !== "fulfilled" || result.value.id !== ids[index]) return;
        const run = result.value;
        loaded.set(run.id, run);
        if (run.drawing) {
          savedDrawings.delete(run.id);
          savedDrawings.set(run.id, run);
          if (savedDrawings.size > 4) savedDrawings.delete(savedDrawings.keys().next().value);
        }
      });
      for (const {name, select, preview} of slots) {
        const run = loaded.get(select.value);
        if (run?.drawing && preview.dataset.run === run.id) continue;
        preview.replaceChildren();
        if (!run) {
          preview.append(el("p", select.value ? "讀取失敗，可更新紀錄重試。" : "選一筆紀錄來比較", "tool-drawing-empty"));
          continue;
        }
        const date = new Date(run.created_at), time = el("time", verificationTime.format(date));
        time.dateTime = date.toISOString();
        if (run.drawing) {
          preview.append(verificationArtwork(run, name));
          preview.dataset.run = run.id;
        } else {
          preview.append(el("p", verificationOutcome(run), "tool-drawing-empty"));
        }
        const meta = el("div", undefined, "tool-run-meta");
        meta.append(time, el("span", (run.credential_version == null ? "" : "Key v" + run.credential_version + " · ") + verificationOutcome(run)));
        preview.append(meta);
        requestLink(preview, run.sample_id);
      }
    } finally {
      if (version === previewVersion) compare.removeAttribute("aria-busy");
    }
  }
  async function readHistory({append = false, prefer = "", previous = slots.map(({select}) => select.value)} = {}) {
    const version = ++historyVersion;
    const limit = append ? 20 : Math.min(200, Math.max(20, runs.length + Number(Boolean(prefer))));
    const page = await api(runPath + "&limit=" + limit + "&offset=" + (append ? runs.length : 0)).catch(error => {
      if (compare.isConnected && version === historyVersion) throw error;
    });
    if (!compare.isConnected || version !== historyVersion) return;
    runs = append ? runs.concat(page.runs) : page.runs;
    const exists = id => runs.some(run => run.id === id);
    const first = [prefer, previous[0], runs[0]?.id].find(exists) || "";
    const second = exists(previous[1]) ? previous[1] : runs.find(run => run.id !== first)?.id || "";
    const chosen = [first, second];
    slots.forEach(({column, select}, index) => {
      select.replaceChildren();
      option(select, "", "選擇紀錄");
      runs.forEach((run, offset) => {
        const date = new Date(run.created_at);
        option(select, run.id, "#" + (offset + 1) + " · " + verificationShortTime.format(date));
      });
      select.value = chosen[index];
      column.hidden = index >= runs.length;
    });
    earlier.hidden = !page.more;
    compare.hidden = runs.length === 0;
    emptyTitle.textContent = "尚無這條連線的測試紀錄。";
    emptyCopy.hidden = false;
    emptyHistory.hidden = runs.length > 0;
    if (runs.length) await showPair();
  }
  const emptyHistory = el("div", undefined, "tool-history-empty");
  const emptyTitle = el("strong", "正在讀取測試紀錄…");
  const emptyCopy = el("span", "執行上方測試後，作品會保存在這裡。");
  emptyCopy.hidden = true;
  emptyHistory.append(emptyTitle, emptyCopy);
  compare.hidden = true;
  const title = el("h2", model.provider + " · 歷次作品");
  title.tabIndex = -1;
  const heading = el("div", undefined, "tool-overview-heading");
  heading.append(title);
  action(heading, "收起歷次作品", close);
  const historyActions = el("div", undefined, "actions tool-history-actions");
  action(historyActions, "更新測試紀錄", () => readHistory()).prepend(icon("refresh"));
  const earlier = action(historyActions, "載入更早紀錄", () => readHistory({append: true}));
  earlier.hidden = true;
  action(historyActions, "模型真實性證據", () => {
    const box = modal("模型真實性證據 · " + model.provider);
    modelAuthenticity(box, model, detailVersion);
    box.querySelector("details[data-authenticity]").open = true;
  });
  gallery.replaceChildren(heading, el("small", connectionLabel(model) + " · " + model.model_id, "tool-scope"),
    emptyHistory, compare, historyActions);
  for (const {select} of slots) select.addEventListener("change", () => showPair().catch(error => notice(error.message, true)));
  readHistory({previous: previousPair}).catch(error => {
    emptyTitle.textContent = "暫時無法讀取紀錄。";
    notice(error.message, true);
  });
  return readHistory;
}
