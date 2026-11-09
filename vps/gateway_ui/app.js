"use strict";

const $ = id => document.getElementById(id);
const state = {
  page: "models", upstreams: [], offers: [], models: [], keys: [], routes: [],
  modelGroup: null, modelProvider: null, providerID: "", routeGroup: null, routePicking: false
};
const statuses = {
  SUCCESS: "成功", RUNNING: "處理中", RATE_LIMITED: "上游限流", CANCELLED: "已取消",
  INTERRUPTED: "已中斷", MODEL_UNAVAILABLE: "上游不可用", UPSTREAM_5XX: "上游故障",
  TIMEOUT: "逾時", NETWORK: "連線失敗", PARSER: "回應格式錯誤", INVALID_REQUEST: "上游拒絕請求",
  AUTH_INVALID: "上游 Key 無效", AUTH: "上游驗證失敗", INFERENCE_DENIED: "上游拒絕存取",
  PAYMENT_REQUIRED: "額度不足", HTTP_ERROR: "上游拒絕請求", TELEMETRY_SAVE: "紀錄暫不可寫入",
  SECRET_IN_RESPONSE: "上游回應含私密資料", REQUEST_IN_PROGRESS: "已有請求執行中"
};
const errors = {
  "valid name and HTTPS base URL required": "請填名稱和有效的 HTTPS Base URL。",
  "public ID or upstream route removed": "這個公開 ID 或其中一條上游路由已刪除。",
  "management item missing": "找不到這個項目，請重新整理。",
  "no stored API key": "這條連線沒有儲存可複製的 API Key。",
  "invalid custom time window": "請選擇有效的開始與結束時間。",
  "upstream refresh already running or service busy": "目前正在更新或服務忙碌，請稍後再試。",
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
  "valid model grouping ID required": "請輸入有效的模型歸類 ID。",
  "expiration must be in the future": "請選擇未來的到期時間，或留白。",
  "key or selected models unavailable": "這把 Key 或選取的模型目前無法更新。"
};

function el(tag, text, className) {
  const node = document.createElement(tag);
  if (text !== undefined) node.textContent = text;
  if (className) node.className = className;
  return node;
}
function svgNode(tag, attributes = {}) {
  const node = document.createElementNS("http://www.w3.org/2000/svg", tag);
  for (const [name, value] of Object.entries(attributes)) node.setAttribute(name, value);
  return node;
}
function icon(name, sprite = "") {
  const node = svgNode("svg", {"aria-hidden": "true"});
  node.append(svgNode("use", {href: sprite + "#icon-" + name}));
  return node;
}
function itemIcon(name, sprite = "") {
  const mark = el("span", undefined, "item-icon");
  mark.append(icon(name, sprite));
  return mark;
}
const modelBrands = [
  ["openai", "gpt|chatgpt|o[134](?![a-z0-9])|dall[-_. ·]?e|whisper|sora|text[-_. ]embedding[-_. ](?:ada|3)|openai(?=$|[.])"],
  ["claude", "(?:anthropic[-_. ]+)?claude|anthropic(?=$|[.])"],
  ["gemini", "(?:google[-_. ]+)?gemini"],
  ["gemma", "(?:google[-_. ]+)?(?:gemma|paligemma|shieldgemma)"],
  ["google", "imagen|veo|lyria|learnlm|google(?=$|[.])"],
  ["deepseek", "deep[-_. ]?seek"],
  ["qwen", "qwen|qwq|qvq|tongyi"],
  ["meta", "llama|meta[-_. ]llama|muse[-_. ](?:spark|glimmer)|meta(?=$|[.])"],
  ["mistral", "mistral|mixtral|codestral|pixtral|ministral|magistral|devstral|voxtral|mathstral"],
  ["zhipu", "chatglm|glm|cogview|cogvideo|zhipu|z[-_. ]ai"],
  ["grok", "(?:x[-_. ]?ai[-_. ]+)?grok|xai(?=$|[.])"],
  ["kimi", "kimi|moonshot(?=$|[.])"],
  ["minimax", "mini[-_. ]?max|hailuo"],
  ["mimo", "(?:xiaomi[-_. ]?)?mimo|xiaomi(?=$|[.])"],
  ["stepfun", "stepfun|step(?=[-_. ]?[0-9])|step[-_. ]?(?:audio|video)"],
  ["antgroup", "inclusion[-_. ]?ai|ant[-_. ]?group|(?:ling|ring|ming)(?=[-_. ]?(?:[0-9]|(?:flash|mini|lite|omni|max)(?=$|[^a-z])))"],
  ["lg", "exaone|lg[-_. ]ai"],
  ["hunyuan", "hunyuan|tencent(?=$|[.])|hy(?=[-_. ]?[0-9])"],
  ["doubao", "doubao"],
  ["bytedance", "seed(?=[-_. ]?[0-9])|seed[-_. ](?:ance|ream|edit|tts)|seedance|seedream|bytedance(?=$|[.])"],
  ["baidu", "ernie|wenxin|baidu(?=$|[.])"],
  ["yi", "01[-_. ]?ai|yi(?=[-_. ](?:[0-9]|coder|large|lightning|vision))"],
  ["nvidia", "nemotron|nvidia(?=$|[.])"],
  ["cohere", "command(?=$|[-_. ](?:a|r|light|nightly)(?=$|[^a-z]))|aya|cohere(?=$|[.])"],
  ["perplexity", "sonar|perplexity(?=$|[.])"],
  ["aws", "amazon[-_. ]nova|nova(?=[-_. ](?:[0-9]|micro|lite|pro|premier|sonic|canvas|reel|act))|amazon(?=$|[.])|aws(?=$|[.])"],
  ["ai21", "ai21|jamba|jurassic"],
  ["microsoft", "phi(?=$|[-_. ]?[0-9])|microsoft(?=$|[.])"],
  ["ibm", "granite|ibm(?=$|[.])"],
  ["longcat", "long[-_. ]?cat|meituan(?=$|[.])"],
  ["internlm", "internlm"],
  ["baichuan", "bai[-_. ]?chuan"],
  ["spark", "iflytek|spark[-_. ](?:max|ultra|lite|desk|x1)"],
  ["sensenova", "sensenova|sensetime(?=$|[.])"],
  ["skywork", "skywork|tiangong"],
  ["huggingface", "hugging[-_. ]?face(?:h4)?|smollm|zephyr"],
  ["jina", "jina"],
  ["voyage", "voyage"],
  ["snowflake", "snowflake(?=$|[.])|arctic"],
  ["bfl", "flux|bfl(?=$|[.])|black[-_. ]forest[-_. ]labs"],
  ["stability", "stability(?:ai)?|stable[-_. ]?(?:diffusion|image|audio|video|lm)|sdxl|sd3"],
  ["recraft", "recraft"],
  ["runway", "runway"],
  ["luma", "luma(?=$|[-_. ]?(?:ai|labs|[0-9]))|dream[-_. ]machine|ray(?=[-_. ]?[23])"],
  ["elevenlabs", "eleven[-_. ]?(?:labs|multilingual|turbo|flash|monolingual|v[0-9])|scribe[-_. ]v"],
  ["suno", "suno"],
  ["udio", "udio"]
].map(([brand, family]) => [brand, new RegExp("^(?:" + family + ")(?=$|[^a-z]|v[0-9])")]);
const modelBrandNamespaces = new Map([
  ...modelBrands.map(([brand]) => [brand, brand]),
  ["01-ai", "yi"], ["anthropic", "claude"], ["black-forest-labs", "bfl"], ["deepseek-ai", "deepseek"],
  ["inclusionai", "antgroup"], ["lgai-exaone", "lg"], ["meta-llama", "meta"], ["mistralai", "mistral"],
  ["moonshotai", "kimi"], ["xiaomi", "mimo"], ["x-ai", "grok"], ["xai", "grok"], ["zai-org", "zhipu"]
]);

function modelIcon(...names) {
  for (const name of names) {
    if (!name) continue;
    // Prefer the model family. Only an exact known namespace can be a fallback.
    const path = name.toLowerCase().split("/").map(part => part.trim());
    const id = path.pop().split(":", 1)[0].replace(/^(?:us|eu|apac|global|au|ca|jp|sa)\.(?=[^.]+\.)/, "");
    const brand = modelBrands.find(([, family]) => family.test(id))?.[0] ??
      path.reverse().map(part => modelBrandNamespaces.get(part)).find(Boolean);
    if (!brand) continue;
    const mark = itemIcon("brand-" + brand, "/gateway-model-brands.svg");
    mark.classList.add("brand-icon");
    return mark;
  }
  return itemIcon("model");
}
function button(text, handler, {glyph, iconOnly} = {}) {
  const node = el("button", iconOnly ? undefined : text);
  node.type = "button";
  if (glyph) node.prepend(icon(glyph));
  if (iconOnly) {
    node.classList.add("icon-button");
    node.setAttribute("aria-label", text);
    node.title = text;
  }
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
  const dialog = [...document.querySelectorAll("dialog[open]")].at(-1);
  const global = $("notice");
  let node = global;
  if (dialog) {
    node = dialog.querySelector(".dialog-message") || el("p", undefined, "dialog-message");
    node.setAttribute("role", error ? "alert" : "status");
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
  const response = await fetch("/ui/api" + path, init);
  const text = await response.text();
  if (!response.ok) {
    let problem;
    try { problem = JSON.parse(text); } catch {}
    const info = problem?.error || problem;
    if (response.status === 401 && info?.code === "LOGIN_REQUIRED") {
      location.replace("/login");
      throw new DOMException("Browser session expired", "AbortError");
    }
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
function metric(label, value, detail, glyph) {
  const node = el("div", undefined, "metric");
  const caption = el("small", label, glyph ? "icon-label" : undefined);
  if (glyph) caption.prepend(icon(glyph));
  node.append(caption, el("strong", value));
  if (detail) node.append(el("em", detail));
  return node;
}
function identity(title, lines, glyph) {
  const content = el("div", undefined, glyph ? "identity-text" : "identity");
  content.append(el("h3", title));
  for (const line of lines.filter(Boolean)) content.append(el("small", line));
  if (!glyph) return content;
  const node = el("div", undefined, "identity marked-identity");
  node.append(itemIcon(glyph), content);
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
async function showPage(page, load = refresh) {
  state.page = page;
  for (const id of ["models", "providers", "share", "usage"]) $("page-" + id).hidden = id !== page;
  for (const nav of document.querySelectorAll("[data-page]")) {
    nav.classList.toggle("active", nav.dataset.page === page);
    if (nav.dataset.page === page) nav.setAttribute("aria-current", "page");
    else nav.removeAttribute("aria-current");
  }
  window.scrollTo(0, 0);
  await load();
}
async function refresh() {
  if (state.page === "models") return showModelView();
  if (state.page === "providers") return showProviderView();
  if (state.page === "share") return loadShare();
  return showUsage();
}
function renderRoutes() {
  $("route-tray").hidden = state.routes.length === 0;
  $("route-count").textContent = "已選 " + state.routes.length + " 個路由";
  $("selected-routes").replaceChildren(...(state.routes.length ? state.routes.map((v, i) => compact((i + 1) + ". " + routeLabel(v), [], controls(
    button("移除", () => {
      state.routes = state.routes.filter(row => scopeID(row) !== scopeID(v));
      if (!state.routes.length) state.routeGroup = null;
      renderRoutes();
    }, {glyph: "minus", iconOnly: true})
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
    }, {glyph: "route"}), button(v.enabled ? "停用" : "啟用", async () => {
      await api("/gateway/models", {id: v.id, routes: v.routes, enabled: !v.enabled});
      await loadShare();
      notice(v.enabled ? "公開模型已停用。" : "公開模型已啟用。");
    }, {glyph: v.enabled ? "pause" : "play"}), deleteButton("刪除公開模型", () => confirmRemoval(
      "刪除公開模型", v.id, "這個公開 ID 會停止提供服務並保留供歷史辨識。請求紀錄仍可查詢。",
      {kind: "PUBLIC_MODEL", id: v.id}, async () => {
        if ($("public-model").readOnly && $("public-model").value === v.id) resetModelDraft();
        await loadShare();
      }
    ))))) : [empty("先從上游選一個模型，就能發布成對外 ID。")]));
  renderKeys();
  renderRoutes();
}
function bindSettings() {
  const bind = (id, storage, fallback, apply) => {
    const input = $(id);
    let value;
    try { value = localStorage.getItem(storage); } catch {}
    input.value = [...input.options].some(option => option.value === value) ? value : fallback;
    input.addEventListener("change", () => {
      apply?.(input.value);
      try { localStorage.setItem(storage, input.value); } catch {}
    });
    apply?.(input.value);
  };
  const root = document.documentElement;
  bind("theme", "modelatlas.appearance", "system", value => {
    if (value === "system") delete root.dataset.theme;
    else root.dataset.theme = value;
  });
  bind("text-size", "modelatlas.textSize", "normal", value => { root.dataset.textSize = value; });
  bind("motion", "modelatlas.motion", "system", value => { root.dataset.motion = value; });
  bind("default-performance-source", "modelatlas.performanceSource", "local");
  bind("default-performance-window", "modelatlas.performanceWindow", "7d");
  bind("wallpaper", "modelatlas.wallpaper", "elaina", value => { root.dataset.wallpaper = value; });
  bind("default-offer-sort", "modelatlas.offerSort", "ttft");
  bind("default-usage-range", "modelatlas.usageRange", "24h", value => {
    const range = $("usage-range");
    for (const option of range.options) option.defaultSelected = option.value === value;
    range.value = value;
  });
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
$("settings-open").addEventListener("click", () => $("settings-dialog").showModal());
$("refresh").addEventListener("click", () => action($("refresh"), refresh));
$("share-history").addEventListener("click", () => action(null, () => showPage("usage")));
$("configure-route").addEventListener("click", () => action(null, async () => {
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
$("copy-issued-base").addEventListener("click", () => copy($("issued-base").value, $("issued-base")));
$("copy-key").addEventListener("click", () => copy($("issued-key").value, $("issued-key")));
submit("model-form", async form => {
  if (!state.routes.length) throw new Error("請先選擇上游路由。");
  const id = new FormData(form).get("id").trim();
  await api("/gateway/models", {id, routes: state.routes, enabled: true});
  resetModelDraft();
  await loadShare();
  notice("已發布 " + id + "。");
});
bindSettings();
renderRoutes();
window.addEventListener("pageshow", event => {
  // Revalidate a console page restored from the browser's back/forward cache.
  if (event.persisted) location.reload();
});
