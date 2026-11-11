"use strict";

let offerTermEditor, imageTest;
let lastImagePrompt = {model: "", value: ""};

function renderOfferTerms(terms) {
  const fields = $("offer-term-values");
  const rows = [];
  const price = terms?.price;
  if (price) {
    const legs = price.legs;
    const label = {MANUAL: "手填價格", NOMINAL: "站方標價", EFFECTIVE: "Key 費率"}[price.basis];
    if (price.free || legs.unit === "USD/request") rows.push([label, gatewayPriceLabel(price)]);
    else {
      for (const [name, value] of [["輸入", legs.input], ["輸出", legs.output], ["快取", legs.cache]]) {
        if (value != null) rows.push([name + " · " + label, "US$" + value + "／千 Token"]);
      }
      if (legs.fixed != null) rows.push(["固定費 · " + label, "US$" + legs.fixed + "／次"]);
    }
  }
  if (terms?.daily) {
    rows.push([terms.daily.source === "USER" ? "每日額度 · 手填" : "每日額度 · 站方", gatewayDailyLabel(terms.daily)]);
  }
  renderDetailFields(fields, rows);
  fields.hidden = !rows.length;
}

function updateTermPriceFields() {
  const form = $("offer-terms-form");
  const {mode, fixed, fixed_absent} = form.elements;
  $("offer-token-prices").hidden = mode.value !== "tokens";
  $("offer-fixed-price").hidden = mode.value !== "request" && (mode.value !== "tokens" || fixed_absent.checked);
  fixed.disabled = $("offer-fixed-price").hidden;
}

async function openOfferTermEditor(offer) {
  offerTermEditor?.read.abort();
  const editor = {offer, read: new AbortController(), value: null, priceChanged: false};
  offerTermEditor = editor;
  const dialog = $("offer-terms-dialog");
  const form = $("offer-terms-form");
  const status = $("offer-terms-status");
  const save = form.querySelector("button[type='submit']");
  const inputs = form.querySelectorAll("input, select");
  const fields = form.elements;
  form.reset();
  updateTermPriceFields();
  for (const input of inputs) input.disabled = true;
  $("offer-terms-scope").textContent = [offer.provider, offer.model_id, offer.account, offer.credential, offer.group].filter(Boolean).join(" · ");
  status.textContent = "讀取設定中…";
  save.disabled = true;
  dialog.showModal();
  try {
    const scope = selection(offer);
    const value = await api("/gateway/offering-terms?" + new URLSearchParams(scope), undefined, editor.read.signal);
    if (offerTermEditor !== editor || !dialog.open) return;
    if (scopeID(value) !== scopeID(scope)) throw new Error("價格資料的連線範圍不符。");
    editor.value = value;
    const price = value.manual_price;
    fields.mode.value = price ? "tokens" : "";
    if (price?.unit === "USD/request") fields.mode.value = value.terms?.price?.free ? "free" : "request";
    for (const key of ["input", "output", "cache", "fixed"]) fields[key].value = price?.[key] ?? "";
    fields.fixed_absent.checked = price?.fixed_absent === true;
    fields.daily.value = value.manual_daily ?? "";
    for (const input of inputs) input.disabled = false;
    updateTermPriceFields();
    status.textContent = "";
    save.disabled = false;
  } catch (error) {
    if (offerTermEditor === editor && error.name !== "AbortError") status.textContent = error.message;
  }
}

function termPriceFromForm(fields) {
  const mode = fields.mode.value;
  if (!mode) return null;
  if (mode === "free") return {unit: "USD/request", fixed: "0"};
  if (mode === "request") return {unit: "USD/request", fixed: fields.fixed.value.trim() || null};
  return {
    unit: "USD/1K tokens", input: fields.input.value.trim() || null, output: fields.output.value.trim() || null,
    cache: fields.cache.value.trim() || null, fixed: fields.fixed_absent.checked ? null : fields.fixed.value.trim() || null,
    fixed_absent: fields.fixed_absent.checked
  };
}

$("offer-terms-form").addEventListener("input", event => {
  if (offerTermEditor && event.target.name !== "daily") offerTermEditor.priceChanged = true;
  updateTermPriceFields();
});
$("offer-terms-dialog").addEventListener("close", () => {
  if ($("offer-terms-dialog").open) return;
  offerTermEditor?.read.abort();
  offerTermEditor = null;
});
submit("offer-terms-form", async form => {
  const editor = offerTermEditor;
  if (!editor?.value) return;
  const scope = selection(editor.offer);
  const status = $("offer-terms-status");
  status.textContent = "";
  let saved;
  try {
    saved = await api("/gateway/offering-terms", {
      ...scope, revision: editor.value.revision,
      price: editor.priceChanged ? termPriceFromForm(form.elements) : editor.value.manual_price,
      daily: form.elements.daily.value.trim() || null
    });
  } catch (error) {
    if (offerTermEditor === editor) status.textContent = error.message;
    return;
  }
  editor.offer.terms = saved.terms;
  if (offerTermEditor === editor) {
    renderOfferTerms(saved.terms);
    $("offer-terms-dialog").close();
  }
  if (state.modelGroup?.id === editor.offer.canonical_id) {
    renderOffers();
    try { await loadOffers(true, Math.min(200, Math.max(50, state.offers.length))); }
    catch (error) {
      if (error.name !== "AbortError") notice("已儲存。清單更新失敗，請重新整理。", true);
    }
  }
});

function clearImageResult(test) {
  if (test?.url) URL.revokeObjectURL(test.url);
  if (test) test.url = "";
  $("offer-image-result").replaceChildren();
}

function openOfferImageTest(offer) {
  const dialog = $("offer-image-dialog");
  imageTest?.read?.abort();
  clearImageResult(imageTest);
  imageTest = {offer, read: null, url: ""};
  const form = $("offer-image-form");
  form.elements.prompt.value = lastImagePrompt.model === offer.canonical_id ? lastImagePrompt.value : "";
  form.querySelector("button[type='submit']").disabled = false;
  $("offer-image-scope").textContent = [offer.provider, offer.model_id, offer.credential].filter(Boolean).join(" · ");
  $("offer-image-status").textContent = "會使用該連線的額度。";
  dialog.showModal();
  form.elements.prompt.focus();
}

function renderImageResult(test, result) {
  const preview = $("offer-image-result");
  const status = $("offer-image-status");
  const image = result.data?.[0];
  const latency = result.details?.latency_ms;
  const elapsed = latency == null ? "" : " · " + number(latency) + " ms";
  if (result.outcome !== "SUCCESS" || !image) throw new Error(statuses[result.outcome] || "上游沒有回傳圖片。");
  if (image.b64_json && ["image/png", "image/jpeg", "image/webp"].includes(image.mime)) {
    const bytes = Uint8Array.from(atob(image.b64_json), c => c.charCodeAt(0));
    test.url = URL.createObjectURL(new Blob([bytes], {type: image.mime}));
    const link = el("a", undefined, "offer-image-preview");
    link.href = test.url;
    link.target = "_blank";
    link.rel = "noopener noreferrer";
    link.setAttribute("aria-label", "開啟原圖");
    const img = el("img");
    img.alt = "本次生成的圖片";
    img.addEventListener("load", () => {
      if (imageTest === test && img.isConnected) status.textContent = "完成 · " + img.naturalWidth + " × " + img.naturalHeight + elapsed;
    }, {once: true});
    img.addEventListener("error", () => {
      if (imageTest !== test || !img.isConnected) return;
      clearImageResult(test);
      status.textContent = "上游回傳的圖片無法預覽。";
    }, {once: true});
    link.append(img);
    preview.append(link);
    status.textContent = "載入圖片中…" + elapsed;
    img.src = test.url;
    return;
  }
  const original = httpsLink("開啟原圖", image.url);
  if (!original) throw new Error("上游未提供可開啟的圖片。");
  original.referrerPolicy = "no-referrer";
  status.textContent = "完成 · 上游回傳圖片連結" + elapsed;
  preview.append(original);
}

$("offer-image-form").elements.prompt.addEventListener("input", event => {
  if (imageTest) lastImagePrompt = {model: imageTest.offer.canonical_id, value: event.target.value};
});
$("offer-image-dialog").addEventListener("close", () => {
  if ($("offer-image-dialog").open) return;
  imageTest?.read?.abort();
  clearImageResult(imageTest);
  imageTest = null;
});
submit("offer-image-form", async form => {
  const test = imageTest;
  if (!test || test.read) return;
  const prompt = form.elements.prompt.value.trim();
  const status = $("offer-image-status");
  if (!prompt) {
    status.textContent = "請輸入提示詞。";
    return;
  }
  const read = new AbortController();
  test.read = read;
  clearImageResult(test);
  status.textContent = "出圖中…";
  form.elements.prompt.disabled = true;
  try {
    const scope = selection(test.offer);
    const result = await api("/gateway/test-image", {...scope, prompt, request_id: crypto.randomUUID()}, read.signal);
    if (imageTest !== test || !$("offer-image-dialog").open) return;
    if (scopeID(result.selection) !== scopeID(scope)) throw new Error("出圖結果的連線範圍不符。");
    renderImageResult(test, result);
  } catch (error) {
    if (imageTest === test && error.name !== "AbortError") status.textContent = error.message;
  } finally {
    test.read = null;
    if (imageTest === test) form.elements.prompt.disabled = false;
  }
});
