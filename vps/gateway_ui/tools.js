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
  $("header-refresh").addEventListener("click", () => refresh().catch(() => {}));
  if (liteTool === "models") {
    show("models");
    $("models-title").textContent = "模型目錄與管理";
    if (toolQuery.get("tab") === "catalog") workspace("models", "models-catalog");
    const external = el("a", "LMSpeed 外部參考", "link-button");
    external.href = "/tools/data?tab=services";
    $("models").querySelector(".heading").append(external);
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
      $("lite-tool-back").href = "/tools/models";
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
  if (toolOffersMore) action(parent, "載入更多連線", async () => {
    const page = await readToolOffers(toolOffers.length);
    toolOffers.push(...page.offerings);
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

function renderVerificationTool() {
  prepareToolModelRows();
  const results = $("lite-tool-results");
  const old = $("verification-connection")?.value;
  const oldPair = [...results.querySelectorAll(".tool-compare-slot select")].map(select => select.value);
  const rows = myModels.filter(model => chatOperation(model.operation));
  results.replaceChildren();
  if (!rows.length) {
    const empty = el("div", "這個模型目前沒有文字對話連線。", "card");
    results.append(empty);
    moreToolOffers(results, renderVerificationTool);
    return;
  }
  const tools = el("section", undefined, "card tool-test");
  const label = el("label", "驗證連線"), picker = el("select");
  picker.id = "verification-connection";
  picker.setAttribute("aria-label", label.textContent);
  const connectionNames = rows.map(model => model.provider + " · " + connectionLabel(model));
  const nameCounts = new Map();
  for (const name of connectionNames) nameCounts.set(name, (nameCounts.get(name) || 0) + 1);
  for (const [index, model] of rows.entries()) {
    const operation = operationLabels[model.operation] || model.operation;
    const name = connectionNames[index], detail = nameCounts.get(name) > 1 ? model.model_id + " · " + operation : "";
    option(picker, key(model), [name, detail,
      model.enabled ? "" : "已停用"].filter(Boolean).join(" · "));
  }
  const previous = rows.find(model => key(model) === old);
  if (previous) picker.value = old;
  else if (!rows[0].enabled) {
    const firstEnabled = rows.find(model => model.enabled);
    if (firstEnabled) picker.value = key(firstEnabled);
  }
  label.append(picker);
  const connection = el("div");
  const selectedScope = el("small", undefined, "tool-scope");
  selectedScope.id = "verification-scope";
  picker.setAttribute("aria-describedby", selectedScope.id);
  connection.append(label, selectedScope);
  const status = el("p", undefined, "tool-run-status");
  status.setAttribute("role", "status");
  const actions = el("div", undefined, "tool-test-actions");
  const gallery = el("section", undefined, "card tool-gallery");
  const active = () => rows.find(row => key(row) === picker.value);
  let historyVersion = 0, previewVersion = 0, runs = [];
  const runPath = () => "/verification/runs?" + query(active());
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
  const timeOptions = {month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit", hourCycle: "h23"};
  const timeFormat = new Intl.DateTimeFormat("zh-TW", {...timeOptions, year: "numeric"});
  const optionTimeFormat = new Intl.DateTimeFormat("zh-TW", {month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hourCycle: "h23"});
  const runOutcome = run => ({SUCCESS: "作品已儲存", INVALID_SVG: "回應無法預覽", UNCONFIRMED: "結果待確認"})[run.outcome]
    || outcomeLabels[run.outcome] || run.outcome;
  const savedDrawings = new Map();

  async function showPair() {
    const version = ++previewVersion, path = runPath();
    const ids = [...new Set(slots.map(({select}) => select.value).filter(Boolean))];
    compare.setAttribute("aria-busy", "true");
    for (const {select, preview} of slots) {
      if (preview.dataset.run !== select.value) {
        delete preview.dataset.run;
        preview.replaceChildren(el("p", "讀取作品…", "tool-drawing-empty"));
      }
    }
    try {
      const pairs = await Promise.allSettled(ids.map(id => savedDrawings.get(id) || api(path + "&id=" + encodeURIComponent(id))));
      if (version !== previewVersion) return;
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
        const date = new Date(run.created_at), time = el("time", timeFormat.format(date));
        time.dateTime = date.toISOString();
        if (run.drawing) {
          const img = el("img", undefined, "tool-drawing");
          img.alt = "鵜鶘騎單車 · " + time.textContent;
          img.src = "data:image/svg+xml," + encodeURIComponent(run.drawing);
          const zoom = el("button", undefined, "tool-drawing-trigger"), glyph = el("span", undefined, "tool-drawing-zoom");
          zoom.type = "button";
          zoom.setAttribute("aria-label", "放大" + name);
          glyph.append(icon("search"));
          zoom.append(img, glyph);
          zoom.addEventListener("click", () => {
            const box = modal("鵜鶘騎單車 · " + name), expanded = img.cloneNode();
            expanded.className = "tool-drawing-expanded";
            box.append(expanded, el("p", time.textContent, "section-description"));
          });
          preview.append(zoom);
          preview.dataset.run = run.id;
        } else {
          preview.append(el("p", runOutcome(run), "tool-drawing-empty"));
        }
        const meta = el("div", undefined, "tool-run-meta");
        meta.append(time, el("span", (run.credential_version == null ? "" : "Key v" + run.credential_version + " · ") + runOutcome(run)));
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
    const page = await api(runPath() + "&limit=" + limit + "&offset=" + (append ? runs.length : 0)).catch(error => {
      if (version === historyVersion) throw error;
    });
    if (version !== historyVersion) return;
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
        option(select, run.id, "#" + (offset + 1) + " · " + optionTimeFormat.format(date));
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
  const runButton = action(actions, "執行測試", async () => {
    const model = active();
    if (verificationPending || !model?.enabled) return;
    verificationPending = picker.disabled = true;
    status.textContent = "正在等待 " + model.provider + " 的回覆…";
    let newest = "";
    try {
      const run = await submit("/verification/runs", {id: crypto.randomUUID(), selection: scope(model)});
      newest = run.id;
      status.textContent = runOutcome(run);
    } catch (error) {
      status.textContent = "這次結果待確認。";
      notice(error.message, true);
    } finally {
      try {
        if (tools.isConnected) await readHistory({prefer: newest});
      } finally {
        verificationPending = picker.disabled = false;
      }
      if (!tools.isConnected) await refresh();
    }
  }, false);
  runButton.prepend(icon("play"));
  const evidence = action(actions, "模型真實性證據", () => {
    const box = modal("模型真實性證據 · " + active().provider);
    modelAuthenticity(box, active(), detailVersion);
    box.querySelector("details[data-authenticity]").open = true;
  });
  evidence.classList.add("ghost");
  tools.append(connection, actions,
    el("p", "每次使用所選 Key 發送一個請求，最多輸出 4,096 tokens。", "section-description tool-test-note"), status);
  const emptyHistory = el("div", undefined, "tool-history-empty");
  const emptyTitle = el("strong"), emptyCopy = el("span", "執行上方測試後，作品會保存在這裡。");
  emptyHistory.append(emptyTitle, emptyCopy);
  gallery.append(el("h2", "作品對照"), emptyHistory, compare);
  const historyActions = el("div", undefined, "actions tool-history-actions");
  action(historyActions, "更新測試紀錄", () => readHistory()).prepend(icon("refresh"));
  const earlier = action(historyActions, "載入更早紀錄", () => readHistory({append: true}));
  gallery.append(historyActions);
  results.append(tools, gallery);
  moreToolOffers(results, renderVerificationTool);
  function switchConnection(previous = []) {
    const model = active();
    runButton.disabled = !model.enabled || verificationPending;
    selectedScope.textContent = model.model_id + " · " + (operationLabels[model.operation] || model.operation);
    previewVersion++;
    savedDrawings.clear();
    compare.removeAttribute("aria-busy");
    runs = [];
    for (const {preview} of slots) {
      delete preview.dataset.run;
      preview.replaceChildren();
    }
    compare.hidden = earlier.hidden = emptyCopy.hidden = true;
    emptyHistory.hidden = false;
    emptyTitle.textContent = "正在讀取測試紀錄…";
    readHistory({previous}).catch(error => {
      emptyTitle.textContent = "暫時無法讀取紀錄。";
      notice(error.message, true);
    });
  }
  picker.addEventListener("change", () => { status.textContent = ""; switchConnection(); });
  for (const {select} of slots) select.addEventListener("change", () => showPair().catch(error => notice(error.message, true)));
  switchConnection(picker.value === old ? oldPair : []);
}
