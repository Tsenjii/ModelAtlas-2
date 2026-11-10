const toolQuery = new URLSearchParams(location.search);
const toolModel = toolQuery.get("model") || "";
const toolProvider = toolQuery.get("provider") || "";
const toolTitles = {prices: "價格比較", verification: "模型驗證", requests: "完整請求紀錄"};
let toolOffers = [];
let toolOffersMore = false;
let verificationPending = false;

async function startLiteTool() {
  const back = new URLSearchParams();
  if (toolModel) back.set("model", toolModel);
  else if (toolProvider) back.set("provider", toolProvider);
  else if (liteTool === "requests") back.set("view", "usage");
  else if (liteTool === "providers") back.set("view", "providers");
  else if (liteTool === "data") back.set("settings", "open");
  $("lite-tool-back").href = back.size ? "/?" + back : "/";
  $("header-refresh").addEventListener("click", () => refresh().catch(() => {}));
  if (liteTool === "models") {
    show("models");
    if (toolQuery.get("tab") === "catalog") workspace("models", "models-catalog");
    const external = el("a", "LMSpeed 外部參考", "link-button");
    external.href = "/tools/data?tab=services";
    $("models").querySelector(".heading").append(external);
  } else if (liteTool === "providers") {
    show("providers");
    if (toolQuery.get("tab") === "import") {
      workspace("providers", "providers-new");
      const panel = $("import-form").closest("details");
      if (panel) panel.open = true;
    }
  } else if (liteTool === "data") {
    show("settings");
    const external = toolQuery.get("tab") === "services";
    $("settings-title").textContent = external ? "外部效能資料" : "備份與同步";
    if (external) $("lite-tool-back").href = "/tools/models";
    workspace("settings", external ? "settings-services" : "settings-data");
  } else {
    $("lite-tool-title").textContent = toolTitles[liteTool];
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
    const results = $("lite-tool-results");
    results.replaceChildren();
    const selected = scope(Object.fromEntries(toolQuery));
    const selection = selected.provider_id && selected.account_id && selected.credential_id && selected.model_id && selected.endpoint ? selected : null;
    await renderRequestHistory(results, {selection}, () => requested === workspaceRefreshRequested);
  } else {
    const page = await readToolOffers(0);
    if (requested !== workspaceRefreshRequested) return;
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
  $("lite-tool-title").textContent = toolTitles[liteTool] + (toolOffers.length ? " · " + toolOffers[0].display_name : "");
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
  results.replaceChildren(el("p", "每條連線各自顯示站方標價和已確認的 Key 費率。同步價格會讀取該站的價目表。", "section-description"));
  const list = el("div", undefined, "tool-row-list");
  results.append(list);
  for (const model of myModels) {
    const card = el("article", undefined, "card");
    card.append(el("h2", model.provider), el("p", connectionLabel(model) + " · " + model.model_id, "section-description"));
    detailQuote(card, "站方標價", model.offer?.nominal_price || model.offer?.price, "依站點與群組顯示。");
    detailQuote(card, "已確認的 Key 費率", model.offer?.effective_price, "依目前使用的 Key 顯示。");
    if (model.offer?.published_groups?.length) {
      const prices = el("details");
      prices.append(el("summary", "站方公布的各群組標價"));
      for (const quote of model.offer.published_groups) {
        detailQuote(prices, quote.group || "站方未命名群組", quote,
          Date.now() - quote.observed_at > 86400000 ? "上次成功同步的標價，可手動更新。" : "公開價目表，實際扣款依使用的群組。");
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
  const rows = myModels.filter(model => chatOperation(model.operation));
  results.replaceChildren(el("p", "選一條連線後手動測試。題目與輸出上限固定，可用同一條連線的歷次作品觀察變化。", "section-description"));
  if (!rows.length) {
    results.append(el("p", "這個模型目前沒有文字對話連線。"));
    moreToolOffers(results, renderVerificationTool);
    return;
  }
  const tools = el("section", undefined, "card"), label = el("label", "驗證連線"), picker = el("select");
  picker.id = "verification-connection";
  picker.setAttribute("aria-label", "驗證連線");
  for (const model of rows) option(picker, key(model), model.provider + " · " + connectionLabel(model) + " · " + model.model_id);
  if (rows.some(model => key(model) === old)) picker.value = old;
  label.append(picker);
  tools.append(label);
  const status = el("p", undefined, "section-description");
  status.setAttribute("role", "status");
  const actions = el("div", undefined, "actions");
  const gallery = el("section", undefined, "card");
  const active = () => rows.find(row => key(row) === picker.value);
  let historyVersion = 0, previewVersion = 0, runs = [];
  const runPath = () => "/verification/runs?" + query(active());
  const left = el("select"), right = el("select"), previews = el("div", undefined, "tool-compare");
  const selectors = el("div", undefined, "tool-compare");
  for (const [labelText, select] of [["第一筆作品", left], ["第二筆作品", right]]) {
    const field = el("label", labelText);
    field.append(select);
    selectors.append(field);
  }
  const describeRun = run => {
    const status = {SUCCESS: "已保存", INVALID_SVG: "回應無法預覽", UNCONFIRMED: "結果待確認"};
    return [new Date(run.created_at).toLocaleString(), run.credential_version == null ? "" : "Key v" + run.credential_version,
      status[run.outcome] || outcomeLabels[run.outcome] || run.outcome].filter(Boolean).join(" · ");
  };
  async function showPair() {
    const version = ++previewVersion, path = runPath();
    previews.setAttribute("aria-busy", "true");
    try {
      const ids = [...new Set([left.value, right.value].filter(Boolean))];
      const pairs = await Promise.all(ids.map(id => api(path + "&id=" + encodeURIComponent(id))));
      if (version !== previewVersion) return;
      previews.replaceChildren();
      for (const run of pairs) {
        const card = el("article", undefined, "card");
        card.append(el("h3", describeRun(run)));
        if (run.drawing) {
          const img = el("img", undefined, "tool-drawing");
          img.alt = "鵜鶘騎單車 · " + new Date(run.created_at).toLocaleString();
          img.src = "data:image/svg+xml," + encodeURIComponent(run.drawing);
          card.append(img);
        }
        requestLink(card, run.sample_id);
        previews.append(card);
      }
    } finally {
      if (version === previewVersion) previews.removeAttribute("aria-busy");
    }
  }
  async function readHistory(append = false) {
    const version = ++historyVersion;
    previewVersion++;
    const page = await api(runPath() + "&limit=20&offset=" + (append ? runs.length : 0));
    if (version !== historyVersion) return;
    runs = append ? runs.concat(page.runs) : page.runs;
    for (const select of [left, right]) {
      const previous = select.value;
      select.replaceChildren();
      option(select, "", "選擇測試紀錄");
      for (const run of runs) option(select, run.id, describeRun(run));
      if (append && runs.some(run => run.id === previous)) select.value = previous;
    }
    if (!append) {
      left.value = runs[0]?.id || "";
      right.value = runs[1]?.id || "";
    }
    earlier.hidden = !page.more;
    selectors.hidden = runs.length === 0;
    emptyHistory.hidden = runs.length > 0;
    await showPair();
  }
  const runButton = action(actions, "執行鵜鶘騎單車測試", async () => {
    if (verificationPending) return;
    const model = active();
    verificationPending = picker.disabled = true;
    status.textContent = "正在等待這家供應商回覆…";
    try {
      const run = await submit("/verification/runs", {id: crypto.randomUUID(), selection: scope(model)});
      status.textContent = describeRun(run);
    } catch (error) {
      status.textContent = "這次結果待確認。";
      notice(error.message, true);
    } finally {
      verificationPending = picker.disabled = false;
      if (tools.isConnected) await readHistory();
      else await refresh();
    }
  }, false);
  action(actions, "模型真實性證據", () => {
    const box = modal("模型真實性證據 · " + active().provider);
    modelAuthenticity(box, active(), detailVersion);
    box.querySelector("details[data-authenticity]").open = true;
  });
  tools.append(el("p", "測試 v1 · 鵜鶘騎單車 SVG · 使用所選連線的額度，輸出上限 4,096 tokens", "section-description"), actions, status);
  const emptyHistory = el("p", "尚無這條連線的測試紀錄。");
  gallery.append(el("h2", "作品對照"), emptyHistory, selectors, previews);
  selectors.hidden = true;
  const historyActions = el("div", undefined, "actions");
  action(historyActions, "更新測試紀錄", () => readHistory());
  const earlier = action(historyActions, "載入更早紀錄", () => readHistory(true));
  earlier.hidden = true;
  gallery.append(historyActions);
  results.append(tools, gallery, el("p", "同一模型的表現會有波動。作品適合用來觀察能力變化，身份證據可另外檢查。", "section-description"));
  moreToolOffers(results, renderVerificationTool);
  function switchConnection() {
    runButton.disabled = !active().enabled || verificationPending;
    previews.replaceChildren();
    selectors.hidden = true;
    readHistory().catch(error => notice(error.message, true));
  }
  picker.addEventListener("change", () => { status.textContent = ""; switchConnection(); });
  for (const select of [left, right]) select.addEventListener("change", () => showPair().catch(error => notice(error.message, true)));
  switchConnection();
}
