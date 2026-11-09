"use strict";

const performanceHours = {"1h": 1, "24h": 24, "7d": 168, "30d": 720};
const connectionTime = new Intl.DateTimeFormat("zh-TW", {dateStyle: "short", timeStyle: "medium"});
let offerDetailRead, keyCopyTimer;

function credentialCopyButton(scope, owner) {
  const control = button("複製 API Key", async () => {
    const isCurrent = () => control.isConnected && (!owner || owner.open);
    const read = api("/gateway/credential/copy", connectionFields(scope)).then(({api_key}) => {
      if (!isCurrent()) throw new DOMException("Connection closed", "AbortError");
      return api_key;
    });
    // Request clipboard access during the click, before waiting for the
    // server response. Safari ties access to the original user gesture.
    if (navigator.clipboard?.write && window.ClipboardItem) {
      const clipboardValue = read.then(value => new Blob([value], {type: "text/plain"}));
      // The read may fail after an immediate clipboard denial. The original
      // request's error is handled below when offering the manual fallback.
      void clipboardValue.catch(() => {});
      try {
        await navigator.clipboard.write([new ClipboardItem({"text/plain": clipboardValue})]);
        notice("已複製上游 Key");
        return;
      } catch {
        // Offer a transient, manually selectable field when access is denied.
      }
    }
    const value = await read;
    if (!isCurrent()) return;
    const dialog = $("upstream-copy-dialog");
    const field = $("upstream-copy-value");
    clearTimeout(keyCopyTimer);
    field.value = value;
    if (!dialog.open) dialog.showModal();
    field.focus();
    field.select();
    keyCopyTimer = setTimeout(() => { field.value = ""; dialog.close(); }, 30000);
  }, {glyph: "copy"});
  return control;
}

$("upstream-copy-dialog").addEventListener("close", () => {
  if ($("upstream-copy-dialog").open) return;
  clearTimeout(keyCopyTimer);
  $("upstream-copy-value").value = "";
});
$("upstream-copy-retry").addEventListener("click", () => {
  const field = $("upstream-copy-value");
  if (field.value) void copy(field.value, field);
});
window.addEventListener("pagehide", () => { $("upstream-copy-value").value = ""; });

function connectionPlot(chart, options) {
  chart.replaceChildren();
  metricPlot(chart, {
    ...options,
    allowExport: false,
    appendAction(parent, text, handler) {
      const node = button(text, handler);
      parent.append(node);
      return node;
    }
  });
}

function renderOfferTrend(local) {
  const trend = $("offer-trend");
  const label = el("label", "趨勢指標");
  const measure = el("select");
  const chart = el("div");
  measure.append(option("ttft_ms", "首字等待"), option("tps", "生成速度"),
    option("latency_ms", "總延遲"), option("success", "成功／失敗"));
  label.append(measure);
  trend.replaceChildren(el("h3", "實測趨勢"), label, chart);
  const draw = () => {
    const binary = measure.value === "success";
    connectionPlot(chart, {
      title: measure.selectedOptions[0].textContent,
      unit: binary ? "" : measure.value === "tps" ? "tok/s" : "ms",
      binary,
      points: local.history.map(sample => ({
        at: sample.at,
        value: binary ? Number(sample.outcome === "SUCCESS") : sample[measure.value],
        note: (statuses[sample.outcome] || sample.outcome) + (sample.http_status ? " · HTTP " + sample.http_status : "")
      })),
      description: "繪出此期間最近 " + local.history.length + " / " + local.history_total + " 筆完成請求。選取圖點可查看時間和數值。"
    });
  };
  measure.addEventListener("change", draw);
  draw();
  trend.hidden = false;
}

function renderLocalPerformance(local) {
  const metrics = el("div", undefined, "metrics");
  const stat = (label, v, fmt, glyph) => metric(label, fmt(v?.mean),
    v?.p95 == null ? "" : "P50 " + fmt(v.p50) + " · P95 " + fmt(v.p95), glyph);
  const speed = v => v == null ? "—" : number(v, 1) + " tok/s";
  metrics.append(stat("平均首字等待", local.ttft, delay, "clock"),
    stat("平均生成速度", local.tps, speed, "bolt"),
    stat("平均總延遲", local.latency, delay, "gauge"),
    metric("成功率", local.success_rate == null ? "—" : number(local.success_rate * 100, 1) + "%",
      local.samples + " 筆完成 · " + local.cancelled + " 筆取消 · " + local.interrupted + " 筆中斷", "check"));
  const details = el("details", undefined, "details-card");
  const failures = Object.entries(local.failures || {});
  details.append(el("summary", "近 60 秒用量與失敗原因"), el("p",
    "觀測 RPM " + number(local.observed_rpm) + " · 觀測 TPM " + number(local.observed_total_tpm), "quiet"));
  if (failures.length) {
    const list = el("ul");
    for (const [outcome, count] of failures) list.append(el("li", (statuses[outcome] || outcome) + " · " + count + " 筆"));
    details.append(list);
  }
  $("offer-local-summary").replaceChildren(metrics, details);
  renderOfferTrend(local);
}

function renderSitePerformance(v, value) {
  const data = $("offer-site-data");
  const facts = value.facts;
  const groupLabel = el("label", "站方群組");
  const group = el("select");
  const metricLabel = el("label", "站方趨勢指標");
  const measure = el("select");
  const body = el("div", undefined, "stack");
  group.append(option("", "站方整體"));
  if (v.group && !facts.groups.some(row => row.group === v.group)) {
    group.append(option(v.group, v.group + "（站方未提供）"));
  }
  for (const row of facts.groups) group.append(option(row.group, row.group));
  group.value = v.group || "";
  groupLabel.append(group);
  for (const [value, title] of [["avg_ttft_ms", "首字等待"], ["avg_tps", "生成速度"], ["avg_latency_ms", "總延遲"], ["success_rate", "成功率"]]) {
    measure.append(option(value, title));
  }
  metricLabel.append(measure);
  const provenance = el("p", "站方公開資料 · " + connectionTime.format(value.observed_at) + " · " + facts.origin, "quiet");
  const filters = el("div", undefined, "connection-grid");
  filters.append(groupLabel, metricLabel);
  data.replaceChildren(filters, body, provenance);
  const draw = () => {
    const row = group.value ? facts.groups.find(row => row.group === group.value) : facts.summary;
    const points = group.value ? row?.series : facts.series;
    body.replaceChildren();
    if (row?.request_count === 0 && !points?.length) {
      body.append(empty("站方這段期間沒有回報樣本。"));
      return;
    }
    if (row) {
      const metrics = el("div", undefined, "metrics");
      metrics.append(metric("平均首字等待", delay(row.avg_ttft_ms), "", "clock"),
        metric("平均生成速度", row.avg_tps == null ? "—" : number(row.avg_tps, 1) + " tok/s", "", "bolt"),
        metric("平均總延遲", delay(row.avg_latency_ms), "", "gauge"),
        metric("成功率", row.success_rate == null ? "—" : number(row.success_rate, 1) + "%",
          row.request_count == null ? "" : number(row.request_count) + " 筆請求", "check"));
      body.append(metrics);
    }
    if (!row && !points?.length) {
      body.append(empty(group.value ? "站方沒有提供這個群組的統計。" :
        facts.groups.length ? "站方沒有提供整體統計。可選擇有資料的群組。" : "站方這段期間沒有回報樣本。"));
      return;
    }
    if (points?.length) {
      const chart = el("div");
      body.append(chart);
      connectionPlot(chart, {
        title: measure.selectedOptions[0].textContent,
        unit: measure.value === "success_rate" ? "%" : measure.value === "avg_tps" ? "tok/s" : "ms",
        points: points.map(point => ({at: point.ts * 1000, value: point[measure.value], note: group.value || "站方整體"}))
      });
    }
  };
  group.addEventListener("change", draw);
  measure.addEventListener("change", draw);
  draw();
}

function validSitePerformance(value, scope, hours, origin) {
  return value?.provider_id === scope.provider_id && value.source === "NEW_API_OFFICIAL" &&
    value.credential_role === "ANONYMOUS" && !value.account_id && !value.credential_id &&
    value.endpoint === "/api/perf-metrics" && value.facts?.model_name === scope.model_id &&
    value.facts.hours === hours && value.facts.origin === origin && value.facts.timestamp_unit === "UNIX_SECONDS" &&
    value.facts.success_rate_unit === "PERCENT" && Array.isArray(value.facts.groups);
}

function renderRecentConnections(samples) {
  const sources = {GATEWAY: "分發請求", GATEWAY_TEST: "模型實測", MANUAL_TEST: "模型實測", CHAT: "工作區對話"};
  const rows = samples.map(sample => {
    const row = el("article", undefined, "history-sample");
    const heading = el("header", undefined, "section-inline");
    heading.append(el("strong", connectionTime.format(sample.at)), usageResult(sample));
    const fields = el("dl", undefined, "detail-fields");
    renderDetailFields(fields, [["來源", sources[sample.source] || sample.source], ["首位元組", delay(sample.ttfb_ms)],
      ["首字等待", delay(sample.ttft_ms)], ["總延遲", delay(sample.latency_ms)],
      ["生成速度", sample.tps == null ? "—" : number(sample.tps, 1) + " tok/s"],
      ["輸入 / 輸出 Token", number(sample.input_tokens) + " / " + number(sample.output_tokens)]]);
    row.append(heading, fields);
    return row;
  });
  $("offer-history").replaceChildren(...(rows.length ? rows : [empty("這條連線還沒有紀錄。")]));
}

function openOfferDetail(v) {
  offerDetailRead?.abort();
  const read = new AbortController();
  offerDetailRead = read;
  const dialog = $("offer-detail");
  const current = () => offerDetailRead === read && dialog.open;
  const scope = selection(v);
  const fields = $("offer-detail-fields");
  $("offer-detail-title").replaceChildren(modelIcon(v.canonical_id, v.model_id), el("span", "連線詳情"));
  renderDetailFields(fields, [["供應商", v.provider], ["上游模型 ID", v.model_id], ["Base URL", v.base_url]]);
  $("offer-scope").open = false;
  renderDetailFields($("offer-scope-fields"), [
    ["帳戶", v.account], ["Key 標籤", v.credential], ["群組", v.group || "未指定"],
    ["模型歸類", v.canonical_id], ["端點", v.endpoint], ["連線狀態", v.enabled ? "可用" : "停用或不可用"]
  ]);
  const [, idField, urlField] = fields.querySelectorAll("dd");
  const key = v.has_api_key ? [credentialCopyButton(scope, dialog)] : [];
  $("offer-actions").replaceChildren(
    button("複製模型 ID", () => copy(v.model_id, idField), {glyph: "copy"}),
    button("複製 Base URL", () => copy(v.base_url, urlField), {glyph: "copy"}),
    ...key, deleteButton("刪除連線", () => removeOffer(v), false)
  );
  const localStatus = $("offer-local-status");
  const siteStatus = $("offer-site-status");
  const windowSelect = $("offer-window");
  const siteSelect = $("site-hours");
  windowSelect.value = $("default-performance-window").value;
  siteSelect.value = String(performanceHours[windowSelect.value]);
  $("offer-custom-range").hidden = true;
  $("offer-from").value = $("offer-to").value = "";
  $("offer-local-summary").replaceChildren();
  $("offer-trend").hidden = true;
  $("offer-trend").replaceChildren();
  $("offer-site-data").replaceChildren();
  $("offer-history").replaceChildren(empty("讀取連線紀錄中…"));
  localStatus.textContent = siteStatus.textContent = "";
  let localRequest = 0, siteRequest = 0, siteStarted = false, localStarted = false;
  const siteValues = new Map();

  const loadLocal = async () => {
    const request = ++localRequest;
    const params = new URLSearchParams({...scope, window: windowSelect.value});
    if (windowSelect.value === "custom") {
      const start = new Date($("offer-from").value).getTime(), end = new Date($("offer-to").value).getTime();
      if (!Number.isFinite(start) || !Number.isFinite(end) || end <= start || end > Date.now()) {
        localStatus.textContent = "請選擇有效的開始與結束時間。";
        return;
      }
      params.set("start", start);
      params.set("end", end);
    }
    localStatus.textContent = "讀取實測效能中…";
    try {
      const value = await api("/gateway/offering-performance?" + params, undefined, read.signal);
      if (!current() || localRequest !== request) return;
      if (scopeID(value.selection) !== scopeID(scope) || value.local?.source !== "MY_KEY_LOCAL" || !Array.isArray(value.local.history)) {
        throw new Error("實測來源或連線範圍不符。");
      }
      renderLocalPerformance(value.local);
      localStatus.textContent = value.local.samples ? "" : "這段期間沒有完成的實測。";
    } catch (error) {
      if (current() && localRequest === request && error.name !== "AbortError") localStatus.textContent = error.message;
    }
  };
  const loadSite = async (force = false) => {
    const request = ++siteRequest;
    const hours = Number(siteSelect.value);
    const params = {...scope, hours};
    const active = () => current() && siteRequest === request;
    const previous = siteValues.get(hours);
    if (previous) renderSitePerformance(v, previous);
    else $("offer-site-data").replaceChildren();
    siteStatus.textContent = "讀取站方效能中…";
    try {
      let saved = previous;
      if (!saved) {
        saved = await api("/gateway/offering-site?" + new URLSearchParams(params), undefined, read.signal);
        if (!active()) return;
        if (validSitePerformance(saved, scope, hours, v.base_url)) {
          siteValues.set(hours, saved);
          renderSitePerformance(v, saved);
        } else {
          saved = null;
        }
      }
      if (force || !saved || Date.now() - saved.observed_at > 5 * 60000) {
        const fresh = await api("/gateway/offering-site", params, read.signal);
        if (!active()) return;
        if (!validSitePerformance(fresh, scope, hours, v.base_url)) throw new Error("站方資料的模型或時間範圍不符。");
        siteValues.set(hours, fresh);
        renderSitePerformance(v, fresh);
      }
      siteStatus.textContent = "";
    } catch (error) {
      if (!active() || error.name === "AbortError") return;
      siteStatus.textContent = "無法讀取站方效能。這個站點可能未開放公開統計。" +
        (siteValues.has(hours) ? "目前顯示上次成功讀取的資料。" : " " + error.message);
    }
  };
  const activate = source => {
    const site = source === "site";
    $("offer-local").hidden = site;
    $("offer-site").hidden = !site;
    $("offer-source-local").setAttribute("aria-pressed", String(!site));
    $("offer-source-site").setAttribute("aria-pressed", String(site));
    if (site && !siteStarted) {
      siteStarted = true;
      void loadSite();
    } else if (!site && !localStarted) {
      localStarted = true;
      void loadLocal();
      api("/gateway/offering-history?" + new URLSearchParams(scope), undefined, read.signal).then(samples => {
        if (current()) renderRecentConnections(samples);
      }).catch(error => {
        if (current() && error.name !== "AbortError") $("offer-history").replaceChildren(empty(error.message));
      });
    }
  };
  windowSelect.onchange = () => {
    $("offer-custom-range").hidden = windowSelect.value !== "custom";
    if (windowSelect.value === "custom") {
      localRequest++;
      localStatus.textContent = "選擇起訖時間後套用。";
    } else void loadLocal();
  };
  $("offer-window-form").onsubmit = event => { event.preventDefault(); void loadLocal(); };
  $("offer-source-local").onclick = () => activate("local");
  $("offer-source-site").onclick = () => activate("site");
  siteSelect.onchange = () => { void loadSite(); };
  $("offer-site-refresh").onclick = () => action($("offer-site-refresh"), () => loadSite(true));
  dialog.showModal();
  activate($("default-performance-source").value);
}

$("offer-detail").addEventListener("close", () => {
  if ($("offer-detail").open) return;
  offerDetailRead?.abort();
  for (const id of ["offer-trend", "offer-history", "offer-local-summary", "offer-site-data", "offer-actions"]) $(id).replaceChildren();
});
