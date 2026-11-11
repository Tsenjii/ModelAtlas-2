"use strict";

const { chromium, request } = require(process.env.PLAYWRIGHT_MODULE || "playwright");
const assert = require("node:assert/strict");
const fs = require("node:fs/promises");

(async () => {
  const browser = await chromium.launch({ headless: true });
  const [origin, upstream, workspace] = process.argv.slice(2);
  const context = await browser.newContext({
    viewport: { width: 402, height: 874 }, isMobile: true, hasTouch: true, reducedMotion: "reduce",
    ignoreHTTPSErrors: true
  });
  const client = await request.newContext({ ignoreHTTPSErrors: true });
  const page = await context.newPage();
  page.setDefaultTimeout(12000);
  const errors = [];
  const external = [];
  page.on("pageerror", error => errors.push(error.message));
  page.on("request", req => {
    if (req.url().startsWith("blob:" + origin + "/")) return;
    if (!req.url().startsWith(origin + "/") && !req.url().startsWith(workspace + "/")) external.push(req.url());
  });
  await fs.mkdir("e2e/screenshots", { recursive: true });
  const go = name => page.getByRole("navigation").getByRole("button", { name, exact: true }).click();
  const assertLayout = async () => {
    const layout = await page.evaluate(() => ({
      width: innerWidth,
      scroll: document.documentElement.scrollWidth,
      headerOverlaps: scrollY === 0 && document.querySelector(".gateway-bar")?.getBoundingClientRect().bottom >
        [...document.querySelectorAll("main h1")].find(node => node.checkVisibility()).getBoundingClientRect().top,
      clippedHeaderActions: [...document.querySelectorAll(".gateway-bar .header-actions .icon-button")].filter(node => {
        const rect = node.getBoundingClientRect();
        return rect.left < -0.1 || rect.right > innerWidth + 0.1;
      }).map(node => node.getAttribute("aria-label")),
      controls: [...document.querySelectorAll('button,summary,input:not([type=checkbox]),select,textarea,.check,.choice,.link-button')]
        .filter(node => node.checkVisibility() && node.getBoundingClientRect().height < 47.9)
        .map(node => node.id || node.textContent),
      iconControls: [...document.querySelectorAll('button.icon-button')].filter(node => node.checkVisibility() &&
        (!node.getAttribute('aria-label') || node.getBoundingClientRect().width < 47.9)).map(node => node.id || node.title)
    }));
    assert(layout.scroll <= layout.width, "horizontal overflow " + JSON.stringify(layout));
    assert(!layout.headerOverlaps, "header overlays the page title");
    assert.deepEqual(layout.clippedHeaderActions, [], "header actions outside the viewport");
    assert.deepEqual(layout.controls, [], "tap targets shorter than 48px");
    assert.deepEqual(layout.iconControls, [], "icon buttons without names or 48px width");
  };
  const assertConnectionGrid = async history => {
    const {actions, source, toolbar, filters} = await history.evaluate(dialog => {
      const boxes = selector => Array.from(dialog.querySelectorAll(selector), element => {
        const rect = element.getBoundingClientRect();
        return {x: rect.x, y: rect.y, bottom: rect.bottom, w: rect.width, h: rect.height,
          overflow: element.scrollWidth > element.clientWidth + 1};
      });
      return {
        actions: boxes("#offer-actions > button"),
        source: boxes('[aria-label="效能資料來源"] > button'),
        toolbar: boxes("#site-hours, #offer-site-refresh"),
        filters: boxes("#offer-site-data select")
      };
    });
    const same = values => values.every(value => Math.abs(value - values[0]) <= 1);
    assert.equal(actions.length, 4);
    assert(same(actions.map(box => box.w)) && same(actions.map(box => box.h)), "connection actions have unequal sizes");
    assert(same([actions[0].x, actions[2].x]) && same([actions[1].x, actions[3].x]) &&
      same([actions[0].y, actions[1].y]) && same([actions[2].y, actions[3].y]), "connection actions are not on a two-column grid");
    assert.equal(source.length, 2);
    assert(same(source.map(box => box.y)) && same(source.map(box => box.w)), "performance buttons are not aligned");
    assert.equal(toolbar.length, 2);
    assert(same(toolbar.map(box => box.bottom)) && same(toolbar.map(box => box.w)), "site time and refresh controls are not aligned");
    assert.equal(filters.length, 2);
    assert(same(filters.map(box => box.y)) && same(filters.map(box => box.w)), "site filters are not aligned");
    assert([...actions, ...source, ...toolbar, ...filters].every(box => box.w > 0 && box.h >= 47.9 && !box.overflow),
      "a connection control clips its content or has a small touch target");
  };
  const shot = async name => {
    await assertLayout();
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    await page.screenshot({ path: "e2e/screenshots/distribution-" + name + ".png", animations: "disabled" });
  };
  const add = async (name, side, key, description) => {
    await page.getByRole("button", { name: "新增供應商", exact: true }).click();
    const form = page.locator("#upstream-form");
    await form.getByLabel("名稱", { exact: true }).fill(name);
    await form.getByLabel("簡介", { exact: true }).fill(description);
    await form.getByLabel("Base URL", { exact: true }).fill(upstream + "/" + side + "/v1");
    await form.getByLabel("上游 API Key", { exact: true }).fill(key);
    await form.getByRole("button", { name: "儲存並同步模型" }).click();
    await page.locator("#provider-detail").getByRole("heading", { name, exact: true }).waitFor();
  };
  const offering = name => page.locator("#offer-list article").filter({ hasText: name });

  try {
    await page.goto(origin);
    await page.waitForURL(origin + "/login");
    await page.getByRole("heading", { name: "管理員登入", exact: true }).waitFor();
    assert.equal(await page.getByText("註冊", { exact: false }).count(), 0);
    await assertLayout();
    const blocked = await context.request.get(origin + "/ui/api/status");
    assert.equal(blocked.status(), 401);
    assert.equal(blocked.headers()["www-authenticate"], undefined);
    assert.equal((await context.request.get(origin + "/tools/api/providers")).status(), 401);
    const signIn = page.getByRole("button", { name: "登入", exact: true });
    await page.getByLabel("密碼", { exact: true }).fill("fixture-invalid-password");
    await signIn.click();
    await page.getByRole("alert").getByText("帳號或密碼不正確。", { exact: true }).waitFor();
    await page.getByLabel("密碼", { exact: true }).fill(process.env.MODELATLAS_TEST_TOKEN);
    await signIn.click();
    await page.waitForURL(origin + "/");
    const session = (await context.cookies(origin)).find(cookie => cookie.name === "__Host-modelatlas-session");
    assert(session?.httpOnly && session.secure && session.sameSite === "Lax", "missing protected web session");
    assert.equal((await context.request.get(origin + "/v1/models")).status(), 401, "web cookie grants distribution access");
    const description = "日常主力模型站\n\n" + "各模型額度與適用場景依帳戶而異。".repeat(16);
    await add("潮汐 API", "a", process.env.MODELATLAS_TEST_UPSTREAM_A, description);
    const provider = page.locator("#provider-detail");
    await provider.locator(".provider-advanced > summary").click();
    await provider.getByRole("button", {name: "連線設定", exact: true}).click();
    const proxyForm = page.getByRole("dialog", {name: "連線設定", exact: true});
    await proxyForm.getByLabel("連線方式", {exact: true}).selectOption("PROXY");
    await proxyForm.getByLabel("代理網址", {exact: true}).fill(process.env.MODELATLAS_TEST_PROXY_URL);
    await assertLayout();
    await proxyForm.getByRole("button", {name: "儲存連線設定", exact: true}).click();
    await proxyForm.waitFor({state: "hidden"});
    await provider.locator(".provider-network").getByText(/^代理 · /).waitFor();
    await provider.getByRole("button", {name: "同步模型", exact: true}).click();
    await provider.getByRole("button", {name: "同步模型", exact: true}).waitFor({state: "visible"});
    const about = provider.locator(".provider-about");
    assert.equal(await about.getAttribute("open"), null);
    await provider.locator(".provider-description-preview").waitFor();
    await about.getByText("供應商介紹", { exact: true }).click();
    assert.equal(await about.locator(".provider-description").textContent(), description);
    assert(!(await provider.locator(".provider-description-preview").isVisible()));
    await provider.getByRole("button", { name: "編輯資料" }).click();
    const checkinForm = page.locator("#provider-form");
    await checkinForm.getByLabel("需要每日簽到").check();
    const checkinField = checkinForm.getByLabel("簽到網址");
    assert.equal(await checkinField.getAttribute("required"), "");
    const checkinURL = upstream + "/checkin";
    await checkinField.fill(checkinURL);
    await page.getByRole("button", { name: "儲存供應商資料" }).click();
    const checkin = provider.getByRole("link", { name: "前往簽到", exact: true });
    await checkin.waitFor();
    assert.equal(await checkin.count(), 1);
    assert.equal(await checkin.getAttribute("href"), checkinURL);
    assert.equal(await checkin.getAttribute("target"), "_blank");
    assert.equal(await checkin.getAttribute("rel"), "noopener noreferrer");
    await provider.getByRole("button", { name: "我已簽到" }).click();
    await provider.locator(".checkin-line").getByText("今日簽到已記錄", { exact: true }).waitFor();
    await provider.getByRole("button", { name: "記錄額度", exact: true }).click();
    await page.getByLabel("剩餘額度").fill("2.50");
    await page.getByRole("button", { name: "儲存額度" }).click();
    await provider.getByText("2.50 USD", { exact: false }).first().waitFor();
    await provider.locator(".credential-tools summary").click();
    await provider.getByRole("button", { name: "更新額度", exact: true }).click();
    await provider.getByText("0 站方額度", { exact: false }).first().waitFor();
    await provider.getByRole("button", { name: "新增帳戶", exact: true }).click();
    await page.getByLabel("帳戶名稱").fill("額度查詢帳戶");
    await page.getByRole("button", { name: "儲存帳戶" }).click();
    const account = provider.locator(".card").filter({ has: page.getByRole("heading", { name: "額度查詢帳戶", exact: true }) });
    await account.getByRole("button", { name: "新增 Key", exact: true }).click();
    await page.locator("#credential-form").getByLabel("Key 名稱").fill("站方額度 PAT");
    await page.locator("#credential-form").getByLabel("用途").selectOption("ACCOUNT_PAT");
    await page.locator("#credential-form").getByLabel("Key", { exact: true }).fill(process.env.MODELATLAS_TEST_UPSTREAM_PAT);
    await page.getByRole("button", { name: "儲存 Key", exact: true }).click();
    await account.getByRole("button", { name: "更新額度", exact: true }).click();
    await account.getByText("45600 站方額度", { exact: false }).first().waitFor();
    await page.locator("#providers-back").click();
    await add("曙光 API", "b", process.env.MODELATLAS_TEST_UPSTREAM_B, "備援模型站");
    const providerList = await context.request.get(origin + "/ui/api/gateway/providers");
    assert.equal(providerList.status(), 200);
    const standby = (await providerList.json()).providers.find(row => row.name === "曙光 API");
    assert(standby, "standby provider is missing");
    const status = await client.post(origin + "/api/v1/newapi/status", {
      headers: { Authorization: "Bearer " + process.env.MODELATLAS_TEST_TOKEN },
      data: { provider_id: standby.id }
    });
    assert.equal(status.status(), 200);
    await page.getByRole("button", { name: "重新整理", exact: true }).click();
    await provider.getByText("站方提供簽到", { exact: true }).waitFor();
    assert.equal(await provider.getByRole("button", { name: "查詢站方簽到", exact: true }).count(), 0);
    assert.equal(await checkin.count(), 0, "station support must not enable manual check-in");

    const tile = page.locator("#model-list").getByRole("button", { name: /DeepSeek 4\.1 Flash/ });
    await provider.getByRole("button", {name: "查看模型", exact: true}).click();
    await tile.getByText("1 家供應商 · 1 個連線", {exact: true}).waitFor();
    await tile.click();
    await offering("曙光 API").waitFor();
    await page.getByText("曙光 API · 1 個連線", {exact: true}).waitFor();
    assert.equal(await offering("潮汐 API").count(), 0, "provider model detail showed another provider");
    assert.equal(await page.locator("#remove-model-group").getAttribute("hidden"), "", "provider detail exposes deletion of the global model");
    await page.getByRole("button", {name: "返回我的模型", exact: true}).click();
    await page.getByText("曙光 API 提供的模型", {exact: true}).waitFor();
    await page.getByRole("button", {name: "顯示所有供應商", exact: true}).click();
    await tile.getByText("2 家供應商 · 2 個連線", {exact: true}).waitFor();
    await tile.waitFor();
    assert.equal(await tile.count(), 1, "same model appeared more than once");
    assert((await tile.innerText()).includes("2 家供應商 · 2 個連線"));
    assert.equal(await tile.locator(".brand-icon use").getAttribute("href"), "/gateway-model-brands.svg#icon-brand-deepseek");
    const brandCases = [
      ["openrouter/gpt-oss-120b:free","openai"],
      ["us.anthropic.claude-sonnet-4-6:0","claude"],
      ["google/gemini3.8-flash","gemini"],
      ["google/gemma-3-27b","gemma"],
      ["imagen-4","google"],
      ["deepseek-ai/DeepSeek-R1-Distill-Qwen-32B","deepseek"],
      ["Qwen/Qwen3.5-Flash","qwen"],
      ["Muse Spark 1.3","meta"],
      ["mistralai/Mistral-Large-4","mistral"],
      ["zai-org/GLM-5.1","zhipu"],
      ["xai/grok-4","grok"],
      ["moonshotai/Kimi-K2.5","kimi"],
      ["MiniMax-M3.1","minimax"],
      ["Xiaomi/MiMo2.6-Flash","mimo"],
      ["stepfun/step5-flash","stepfun"],
      ["Ling-3.2","antgroup"],
      ["LGAI-EXAONE/EXAONE-4.0-32B","lg"],
      ["tencent/HY4","hunyuan"],
      ["Doubao-1.8-Pro","doubao"],
      ["seedance-2.0","bytedance"],
      ["ERNIE-5.5-Flash","baidu"],
      ["01-ai/Yi-1.5-34B","yi"],
      ["nvidia/Nemotron-3.5-Lightning","nvidia"],
      ["cohere/command-a","cohere"],
      ["sonar-pro","perplexity"],
      ["global.amazon.nova-2-lite-v1:0","aws"],
      ["ai21/Jamba-1.5-Large","ai21"],
      ["microsoft/Phi-4-reasoning","microsoft"],
      ["ibm-granite/granite-4.0","ibm"],
      ["meituan/LongCat-Flash-Chat","longcat"],
      ["internlm/internlm3-8b","internlm"],
      ["Baichuan-M3","baichuan"],
      ["Spark-X1","spark"],
      ["SenseNova-V6","sensenova"],
      ["Skywork-o1","skywork"],
      ["HuggingFaceTB/SmolLM3-3B","huggingface"],
      ["jinaai/jina-reranker-v3","jina"],
      ["voyage-4-lite","voyage"],
      ["snowflake/arctic-embed-l","snowflake"],
      ["black-forest-labs/FLUX.2-pro","bfl"],
      ["stabilityai/stable-diffusion-3.5-large","stability"],
      ["recraft-v4","recraft"],
      ["runway/gen-4.5","runway"],
      ["luma/ray-3","luma"],
      ["eleven_multilingual_v2","elevenlabs"],
      ["suno-v5","suno"],
      ["udio-32","udio"],
      ["sapiens-ai/agnes-3.0-flash", "agnesai"],
      ["Agne 3.0 Flash", "agnesai"],
      ["relay/AgnesAI-3.0-Qwen:free", "agnesai"],
      ["allenai/OLMo-3.1-32B-Instruct", "ai2"],
      ["Trinity-Large-Thinking", "arcee"],
      ["bge-m3", "baai"],
      ["dbrx-instruct", "dbrx"],
      ["essentialai/rnj-1-instruct", "essentialai"],
      ["LFM2.5-1.2B-Instruct", "liquid"],
      ["Hermes-4-70B", "nousresearch"],
      ["reka-flash", "reka"],
      ["SakanaAI/TinySwallow-1.5B", "sakana"],
      ["falcon-3-10b-instruct", "tii"],
      ["solar-pro2", "upstage"],
      ["kling-v3", "kling"],
      ["ideogram-v3", "ideogram"],
      ["midjourney-v7", "midjourney"],
      ["Adobe Firefly Image 4", "adobefirefly"],
      ["ViduQ2", "vidu"],
      ["pikaframes", "pika"],
      ["happy-horse-1.0", "happyhorse"],
      ["fish-audio/s2-pro", "fishaudio"],
      ["venice-uncensored", "venice"],
      ["Google Gemma 3", "gemma"],
      ["Anthropic Claude Sonnet 4", "claude"],
      ["anthropic/gpt-oss-120b", "openai"],
      ["DALL·E 3", "openai"],
      ["openai-compatible/custom-model", null],
      ["deepseek-bridge/novel-model", null],
      ["agnesai-bridge/novel-model", null],
      ["private-model:gemini", null],
      ["unknown-o34", null]
    ];
    const resolved = await page.evaluate(cases => {
      const icons = document.createElement("div");
      icons.id = "test-brand-icons";
      Object.assign(icons.style, {position: "absolute", left: "-10000px", top: "0", visibility: "hidden"});
      document.body.append(icons);
      return cases.map(([name]) => {
        const mark = modelIcon(name);
        icons.append(mark);
        return mark.querySelector("use").getAttribute("href");
      });
    }, brandCases);
    assert.deepEqual(resolved, brandCases.map(([, brand]) => brand ? "/gateway-model-brands.svg#icon-brand-" + brand : "#icon-model"));
    await page.waitForFunction(() => {
      const icons = Array.from(document.querySelectorAll("#test-brand-icons use"));
      return icons.length > 0 && icons.every(icon => icon.getBBox().width > 0 && icon.getBBox().height > 0);
    });
    await page.locator("#test-brand-icons").evaluate(icons => icons.remove());
    await tile.click();
    await page.waitForFunction(() => document.querySelectorAll("#offer-list article").length === 2);
    let finishSingle;
    const singleGate = new Promise(resolve => { finishSingle = resolve; });
    await page.route("**/ui/api/gateway/probe", async route => {
      await singleGate;
      await route.continue();
    }, { times: 1 });
    const tidal = offering("潮汐 API");
    try {
      await Promise.all([
        page.waitForRequest(origin + "/ui/api/gateway/probe"),
        tidal.getByRole("button", {name: "測速", exact: true}).click()
      ]);
      await page.locator("#offer-sort").selectOption("latency");
      await page.waitForFunction(() => !document.querySelector("#offer-list").hasAttribute("aria-busy"));
      assert(await tidal.getByRole("button", {name: "測速中…", exact: true}).isDisabled(), "sorting unlocked a pending single test");
      assert(await page.locator("#offer-batch").isDisabled());
    } finally {
      finishSingle();
    }
    for (const name of ["潮汐 API", "曙光 API"]) {
      const row = offering(name);
      if (name === "曙光 API") await row.getByRole("button", { name: "測速", exact: true }).click();
      await row.getByText("1 / 1 次", { exact: true }).waitFor();
      assert((await row.locator(".metrics").innerText()).includes("100%"));
    }
    await page.locator("#offer-sort").selectOption("ttft");
    await page.waitForFunction(() => document.querySelector("#offer-list article")?.textContent.includes("潮汐 API"));
    await page.locator("#notice").waitFor({ state: "hidden" });
    await page.evaluate(() => window.scrollTo(0, 0));
    await shot("model-402-light");
    await page.setViewportSize({ width: 1344, height: 860 });
    await shot("model-1344-light");

    await page.setViewportSize({ width: 402, height: 874 });
    await offering("曙光 API").getByRole("button", { name: "曙光 API", exact: true }).tap();
    await provider.getByRole("heading", { name: "曙光 API", exact: true }).waitFor();
    await provider.getByText("備援模型站", { exact: true }).waitFor();
    await provider.getByRole("button", { name: "返回模型比較", exact: true }).click();
    await page.waitForFunction(() => document.activeElement?.matches(".provider-name") && document.activeElement.textContent === "曙光 API");
    await offering("潮汐 API").getByRole("button", { name: "潮汐 API", exact: true }).press("Enter");
    await provider.getByRole("heading", { name: "潮汐 API", exact: true }).waitFor();
    await provider.getByText("45600 站方額度", { exact: false }).first().waitFor();
    await go("供應商");
    await page.locator("#provider-count").getByText("2 家 · 近 7 天實測", { exact: true }).waitFor();
    await page.locator("#provider-list article").filter({ hasText: "潮汐 API" }).getByText("100%", { exact: true }).waitFor();
    await shot("providers-402-light");
    const providerTile = page.locator("#provider-list article").filter({ hasText: "潮汐 API" });
    await providerTile.waitFor();
    assert.equal(await providerTile.locator(".provider-meta").count(), 0, "provider list exposes quota or check-in detail");
    await providerTile.click({ position: { x: 18, y: 18 } });
    await provider.getByRole("heading", { name: "潮汐 API", exact: true }).waitFor();
    await go("分發");
    await page.getByRole("button", { name: "新增公開模型", exact: true }).click();
    assert(await page.locator("#public-model").isVisible(), "empty distribution opens a dead-end Key form");
    await page.locator("#cancel-model").click();
    await go("模型");
    await page.locator("#model-tool-menu summary").click();
    await page.getByRole("button", { name: "選取分發連線" }).click();
    assert(await page.locator("#configure-route").isDisabled());
    for (const name of ["潮汐 API", "曙光 API"]) await offering(name).getByRole("button", { name: "加入路由" }).click();
    await page.getByRole("button", { name: "前往分發" }).click();
    await page.getByLabel("對外模型 ID").fill("atlas-chat");
    await page.getByRole("button", { name: "發布模型" }).click();
    const publicModel = page.locator("#public-models article").filter({ has: page.getByRole("heading", { name: "atlas-chat", exact: true }) });
    await publicModel.getByRole("button", { name: "停用", exact: true }).click();
    await publicModel.getByText("已停用", { exact: true }).waitFor();
    await publicModel.getByRole("button", { name: "編輯路由", exact: true }).click();
    await page.getByRole("button", { name: "儲存路由", exact: true }).click();
    await page.locator("#notice").getByText("路由已儲存。", { exact: true }).waitFor();
    assert(await publicModel.getByText("已停用", { exact: true }).isVisible(), "saving routes enabled a disabled model");
    await publicModel.getByRole("button", { name: "啟用", exact: true }).click();
    await publicModel.getByText("已啟用 · 2 個路由", { exact: true }).waitFor();
    await page.locator("#model-editor summary").click();
    await page.locator("#select-routes").click();
    await offering("潮汐 API").getByRole("button", { name: "加入路由" }).click();
    await page.locator("#configure-route").click();
    await page.getByLabel("對外模型 ID").fill("atlas-chat");
    await page.getByRole("button", { name: "發布模型" }).click();
    await page.locator("#notice").getByText("這個公開模型 ID 已被使用或保留。請改用其他 ID。", { exact: true }).waitFor();
    await page.locator("#cancel-model").click();
    assert(await publicModel.getByText("已啟用 · 2 個路由", { exact: true }).isVisible(), "duplicate create changed an existing model");
    await page.getByRole("button", { name: "建立 Key", exact: true }).click();
    await page.locator("#key-form").getByLabel("Key 名稱").fill("手機");
    await page.locator("#key-form").getByLabel("每日請求上限").fill("2");
    await page.locator("#key-form").getByLabel("到期時間").fill("2037-01-01T00:00");
    assert(await page.locator("#key-models").getByLabel("atlas-chat").isChecked(), "the only active public model was not selected");
    assert(!(await page.locator("#key-model-search-field").isVisible()));
    await shot("key-402-light");
    await page.getByRole("button", { name: "產生 Key" }).click();
    await page.locator("#issued-dialog").waitFor();
    const token = await page.locator("#issued-key").inputValue();
    assert(token.startsWith("sk-ma-"), "no distribution key issued");
    await page.locator("#issued-dialog").getByRole("button", { name: "複製 Base URL", exact: true }).click();
    await page.locator("#issued-dialog").getByText(/已複製|已選取文字/).waitFor();
    await page.locator("#issued-dialog").getByRole("button", { name: "完成" }).click();
    await page.waitForFunction(() => document.querySelector("#issued-key").value === "");
    const headers = { Authorization: "Bearer " + token };
    const models = await client.get(origin + "/v1/models", { headers });
    assert.equal(models.status(), 200);
    assert.deepEqual((await models.json()).data.map(item => item.id), ["atlas-chat"]);
    const keyRow = page.locator("#key-list article").filter({ hasText: "手機" });
    await keyRow.getByRole("button", { name: "暫停", exact: true }).click();
    await keyRow.getByText("已暫停", { exact: true }).waitFor();
    assert.equal((await client.get(origin + "/v1/models", { headers })).status(), 401);
    await keyRow.getByRole("button", { name: "恢復", exact: true }).click();
    await keyRow.getByText("啟用中", { exact: true }).waitFor();
    await keyRow.getByRole("button", { name: "編輯", exact: true }).click();
    assert.equal(await page.locator("#key-form").getByLabel("到期時間").inputValue(), "2037-01-01T00:00");
    await page.locator("#key-form").getByLabel("每日請求上限").fill("3");
    await page.getByRole("button", { name: "儲存設定", exact: true }).click();
    await keyRow.getByText("0 / 3", { exact: true }).waitFor();
    const result = await client.post(origin + "/v1/chat/completions", {
      headers, data: { model: "atlas-chat", stream: true, messages: [{ role: "user", content: "fallback" }] }
    });
    assert.equal(result.status(), 200);
    assert((await result.text()).includes("data: [DONE]"), "missing forwarded SSE completion");

    const usage = page.locator("#usage-filters");
    await keyRow.getByRole("button", { name: "查看此 Key 用量", exact: true }).click();
    await page.locator("#usage-count").getByText("已顯示 1 / 1 筆", { exact: true }).waitFor();
    assert.equal(await usage.locator("#usage-source").inputValue(), "gateway");
    assert.equal(await usage.locator("#usage-key option:checked").innerText(), "手機");
    await usage.getByText("Key 手機", { exact: true }).waitFor();
    assert(!(await usage.locator("#usage-filter-fields").isVisible()));
    await usage.getByRole("button", { name: "清除篩選", exact: true }).click();
    await page.locator("#usage-list").getByText("模型實測", { exact: true }).first().waitFor();
    assert.equal(await usage.locator("#usage-key").inputValue(), "");
    await go("分發");
    await page.getByRole("button", { name: "查看用量", exact: true }).click();
    const forwarded = page.locator("#usage-list tbody tr").filter({ hasText: "曙光 API / DeepSeek-V4_1-Flash" }).filter({ hasText: "atlas-chat" });
    await forwarded.getByText("曙光 API / DeepSeek-V4_1-Flash", { exact: true }).waitFor();
    assert(await forwarded.getByText("含備援 · 共 2 次上游請求", { exact: true }).isVisible());
    const apply = () => usage.getByRole("button", { name: "套用篩選", exact: true }).click();
    const toggleUsageFilters = () => usage.getByRole("button", { name: "篩選", exact: true }).click();
    await usage.locator("#usage-range").selectOption("7d");
    await page.locator("#usage-count").getByText("41 筆", { exact: false }).waitFor();
    assert(await page.locator("#usage-chart rect").count() > 6, "dated history did not draw the chart");
    const ranking = page.locator("#usage-ranking");
    await ranking.getByText("qwen-3-8-max", { exact: true }).waitFor();
    await ranking.locator(".rank-line").filter({ hasText: "deepseek-v4-1-flash" }).getByText("實測 · 17 次", { exact: true }).waitFor();
    await page.locator("#notice").waitFor({ state: "hidden" });
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.setViewportSize({ width: 1344, height: 1000 });
    await shot("usage-1344-light");
    await page.setViewportSize({ width: 402, height: 874 });
    await page.evaluate(() => window.scrollTo(0, 0));
    await shot("usage-402-light");
    await Promise.all([
      page.waitForRequest(request => {
        const url = new URL(request.url());
        return url.pathname.endsWith("/gateway/usage") && url.searchParams.get("items_only") === "1";
      }),
      page.locator("#usage-more").click()
    ]);
    await page.locator("#usage-count").getByText("已顯示 41 / 41 筆", { exact: true }).waitFor();
    assert.equal(await page.locator("#usage-list tbody tr").count(), 41);
    assert.equal(await ranking.getByText("qwen-3-8-max", { exact: true }).count(), 1);
    await page.getByRole("button", { name: "Token", exact: true }).click();
    const plot = page.locator("#usage-chart .chart-svg");
    await plot.focus();
    await plot.press("End");
    await page.locator("#usage-bucket").getByText("輸入", { exact: false }).waitFor();
    await forwarded.getByRole("button", { name: "詳情" }).click();
    await page.locator("#usage-detail").getByText("上游嘗試次數", { exact: true }).waitFor();
    await page.locator("#usage-detail").getByRole("button", { name: "關閉", exact: true }).click();
    await toggleUsageFilters();
    await page.locator("#usage-key").selectOption({ label: "手機" });
    assert.equal(await usage.locator("#usage-source").inputValue(), "gateway");
    await usage.locator("#usage-status").selectOption("failed");
    await apply();
    await page.locator("#usage-list").getByText("這個範圍還沒有請求。").waitFor();
    await toggleUsageFilters();
    await usage.locator("#usage-source").selectOption("tests");
    assert.equal(await usage.locator("#usage-key").inputValue(), "");
    assert(await usage.locator("#usage-key").isDisabled());
    await usage.locator("#usage-source").selectOption("gateway");
    await usage.locator("#usage-key").selectOption({ label: "手機" });
    await usage.locator("#usage-status").selectOption("");
    await usage.getByLabel("模型", { exact: true }).fill("atlas-chat");
    await apply();
    await page.locator("#usage-list").getByText("曙光 API / DeepSeek-V4_1-Flash", { exact: true }).waitFor();
    assert.equal(await page.locator("#usage-list tbody tr").count(), 1);

    await page.setViewportSize({ width: 320, height: 680 });
    await page.emulateMedia({ colorScheme: "dark" });
    await page.evaluate(() => { document.documentElement.style.fontSize = "200%"; });
    await assertLayout();
    await toggleUsageFilters();
    await assertLayout();
    await toggleUsageFilters();
    await page.getByRole("button", { name: "設定" }).click();
    await page.getByLabel("背景").selectOption("elaina-chibi");
    await page.getByLabel("色彩").selectOption("dark");
    await page.getByLabel("字級").selectOption("compact");
    await page.getByLabel("動態效果").selectOption("reduced");
    await page.getByLabel("模型比較排序").selectOption("success");
    await page.getByLabel("用量期間").selectOption("30d");
    await assertLayout();
    await page.getByRole("dialog").getByRole("button", { name: "完成" }).click();
    await go("分發");
    await page.locator("#key-list").getByText("1 / 3", { exact: true }).waitFor();
    await assertLayout();
    const revoke = page.getByRole("button", { name: "撤銷", exact: true });
    await revoke.evaluate(button => button.scrollIntoView({ block: "center" }));
    assert((await revoke.boundingBox()).y + (await revoke.boundingBox()).height < (await page.getByRole("navigation").boundingBox()).y);
    await revoke.click();
    const revokeDialog = page.locator("#remove-dialog");
    await revokeDialog.getByRole("button", { name: "取消", exact: true }).click();
    assert(await keyRow.getByText("啟用中", { exact: true }).isVisible());
    await revoke.click();
    await revokeDialog.getByRole("button", { name: "確認撤銷", exact: true }).click();
    await page.locator("#key-list").getByText("已撤銷", { exact: true }).waitFor();
    assert.equal((await client.get(origin + "/v1/models", { headers })).status(), 401);

    await go("模型");
    const history = page.locator("#offer-detail");
    await offering("潮汐 API").getByRole("button", { name: "連線詳情" }).click();
    const headerButton = history.getByRole("button", {name: "請求標頭", exact: true});
    assert(!(await headerButton.isVisible()), "rare request headers are exposed in the default detail view");
    await history.locator("#offer-scope > summary").click();
    await headerButton.click();
    const headerEditor = page.getByRole("dialog", {name: "請求標頭", exact: true});
    const addHeader = headerEditor.getByRole("button", {name: "新增標頭", exact: true});
    await addHeader.click();
    await headerEditor.getByLabel("標頭名稱", {exact: true}).first().fill("User-Agent");
    await headerEditor.getByLabel("標頭值", {exact: true}).first().fill("Atlas-Allowed-Fixture/1.0");
    await addHeader.click();
    await headerEditor.getByLabel("標頭名稱", {exact: true}).nth(1).fill("X-Client-Proof");
    await headerEditor.getByLabel("私密值", {exact: true}).nth(1).check();
    await headerEditor.getByLabel("標頭值", {exact: true}).nth(1).fill("synthetic-browser-client-proof");
    await assertLayout();
    await headerEditor.getByRole("button", {name: "儲存標頭", exact: true}).click();
    await headerEditor.waitFor({state: "hidden"});
    await headerButton.click();
    await headerEditor.getByPlaceholder("已儲存，留空沿用").waitFor();
    assert.equal(await headerEditor.getByLabel("標頭值", {exact: true}).first().inputValue(), "Atlas-Allowed-Fixture/1.0");
    assert.equal(await headerEditor.getByPlaceholder("已儲存，留空沿用").inputValue(), "");
    await headerEditor.getByRole("button", {name: "關閉", exact: true}).click();
    await history.locator("#offer-scope > summary").click();
    await history.getByRole("button", {name: "手填", exact: true}).click();
    const terms = page.getByRole("dialog", {name: "手填價格與額度", exact: true});
    await terms.getByLabel("計費方式", {exact: true}).selectOption("request");
    await terms.getByLabel("每次請求 · USD", {exact: true}).fill("0.02");
    await terms.getByLabel("每日請求次數", {exact: true}).fill("20");
    await terms.getByRole("button", {name: "儲存價格與額度", exact: true}).click();
    await terms.waitFor({state: "hidden"});
    await history.getByText("US$0.02／次", {exact: true}).waitFor();
    await history.getByText("20 次／日", {exact: true}).waitFor();
    await history.getByText("模型實測", { exact: true }).waitFor();
    await history.getByText("分發請求", { exact: true }).waitFor();
    assert.equal(await history.locator(".history-sample").count(), 2);
    await history.getByText("上游限流 · 429", { exact: true }).waitFor();
    const recent = history.locator(".history-sample").first();
    assert(!(await recent.locator("dl").isVisible()), "a recent request expands by default");
    await recent.locator("summary").click();
    await recent.getByText("首位元組", {exact: true}).waitFor();
    const archive = history.getByRole("link", {name: "完整請求紀錄", exact: true});
    assert((await archive.getAttribute("href")).includes("credential_id="), "request archive lost its connection scope");
    await context.grantPermissions(["clipboard-read", "clipboard-write"], {origin});
    await history.getByRole("button", {name: "複製模型 ID", exact: true}).click();
    await history.getByText("已複製", {exact: true}).waitFor();
    assert(await page.evaluate(async () => await navigator.clipboard.readText() === "deepseek-v4.1-flash"), "copied the wrong model ID");
    const keyCopied = page.waitForResponse(response => response.url().endsWith("/gateway/credential/copy") && response.ok());
    await history.getByRole("button", {name: "複製 API Key", exact: true}).click();
    await keyCopied;
    await history.getByText("已複製上游 Key", {exact: true}).waitFor();
    assert(await page.evaluate(async expected => await navigator.clipboard.readText() === expected, process.env.MODELATLAS_TEST_UPSTREAM_A),
      "copied an API Key from the wrong connection");
    assert.equal(await page.locator("#upstream-copy-value").inputValue(), "", "plaintext persisted in the UI after clipboard success");
    await page.evaluate(() => {
      Object.defineProperty(navigator, "clipboard", {configurable: true, value: undefined});
    });
    await history.getByRole("button", {name: "複製 API Key", exact: true}).click();
    const copyFallback = page.locator("#upstream-copy-dialog");
    await copyFallback.getByRole("heading", {name: "複製上游 Key"}).waitFor();
    assert(await copyFallback.getByLabel("上游 Key", {exact: true}).inputValue() === process.env.MODELATLAS_TEST_UPSTREAM_A,
      "manual clipboard fallback selected the wrong value");
    await copyFallback.getByRole("button", {name: "關閉", exact: true}).click();
    await page.waitForFunction(() => document.querySelector("#upstream-copy-value").value === "");
    await page.evaluate(() => { delete navigator.clipboard; });

    await history.getByRole("button", {name: "站方效能", exact: true}).click();
    const official = history.getByRole("region", {name: "站方效能", exact: true});
    await official.locator(".metrics").getByText("81 ms", {exact: true}).waitFor();
    await official.locator(".metrics").getByText("98.7%", {exact: true}).waitFor();
    await assertConnectionGrid(history);
    const narrowSize = page.viewportSize();
    await page.evaluate(() => { document.documentElement.style.fontSize = ""; });
    await page.setViewportSize({width: 1344, height: 900});
    await assertConnectionGrid(history);
    await page.setViewportSize(narrowSize);
    await page.evaluate(() => { document.documentElement.style.fontSize = "200%"; });
    await official.getByLabel("站方群組").selectOption("standard");
    await official.locator(".metrics").getByText("82 ms", {exact: true}).waitFor();
    await official.getByLabel("站方趨勢指標").selectOption("success_rate");
    await official.getByRole("group", {name: /^成功率/}).waitFor();
    await official.getByLabel("站方時間範圍").selectOption("1");
    await official.locator(".metrics").getByText("81 ms", {exact: true}).waitFor();
    await assertLayout();
    assert.equal(await history.locator("#offer-local").isVisible(), false);
    await history.getByRole("button", {name: "我的實測", exact: true}).click();
    const trend = history.getByRole("region", {name: "實測趨勢"});
    const trendMetric = trend.getByLabel("趨勢指標");
    const selectedPoint = trend.locator(".plot-selected");
    assert.equal(await trendMetric.inputValue(), "ttft_ms");
    await trend.getByRole("group", {name: /^首字等待/}).waitFor();
    await trendMetric.selectOption("success");
    await selectedPoint.getByText(/HTTP 429/).waitFor();
    await selectedPoint.getByText("失敗", {exact: true}).waitFor();
    await trend.getByRole("button", {name: "上一筆", exact: true}).click();
    await selectedPoint.getByText("成功", {exact: true}).waitFor();
    assert(await trend.evaluate(node => node.scrollWidth <= node.clientWidth + 1), "connection chart overflow");
    await assertLayout();
    await history.getByRole("button", { name: "關閉" }).click();
    await offering("曙光 API").getByRole("button", { name: "連線詳情" }).click();
    await history.getByText("分發請求", { exact: true }).waitFor();
    assert.equal(await history.locator(".history-sample").count(), 2);
    assert.equal(await history.getByText("上游限流 · 429", { exact: true }).count(), 0);
    await history.getByRole("button", {name: "站方效能", exact: true}).click();
    await official.locator(".metrics").getByText("197 ms", {exact: true}).waitFor();
    await history.getByRole("button", {name: "我的實測", exact: true}).click();
    await history.getByLabel("實測時間範圍").selectOption("24h");
    await history.locator("#offer-local-status").getByText("讀取實測效能中…", {exact: true}).waitFor({state: "hidden"});
    assert.equal(await trendMetric.inputValue(), "ttft_ms");
    await trend.getByRole("group", {name: /^首字等待/}).waitFor();
    await trendMetric.selectOption("tps");
    await trend.getByRole("heading", {name: "生成速度", exact: true}).waitFor();
    await trendMetric.selectOption("latency_ms");
    await trend.getByRole("group", {name: /^總延遲/}).waitFor();
    await trendMetric.selectOption("success");
    await selectedPoint.getByText("成功", {exact: true}).waitFor();
    assert.equal(await selectedPoint.getByText(/HTTP 429/).count(), 0);
    await history.getByRole("button", { name: "關閉" }).click();

    const batch = page.locator("#offer-batch-dialog");
    const openBatch = () => page.locator("#offer-batch").click();
    const start = batch.getByRole("button", { name: "開始測速" });
    const close = batch.getByRole("button", { name: "關閉" });
    await openBatch();
    const choices = batch.locator("#offer-batch-list").getByRole("checkbox");
    const selectAll = batch.getByRole("checkbox", { name: "全選連線", exact: true });
    assert.equal(await choices.count(), 2);
    for (const choice of await choices.all()) await choice.uncheck();
    assert(await start.isDisabled());
    await selectAll.check();
    for (const choice of await choices.all()) assert(await choice.isChecked());
    await choices.first().uncheck();
    assert(await selectAll.evaluate(input => input.indeterminate));
    await selectAll.check();
    await assertLayout();
    await start.click();
    await batch.getByText("已完成 2 / 2，成功 2", { exact: true }).waitFor();
    assert.equal(await batch.locator(".batch-result").filter({ hasText: "成功" }).count(), 2);
    await close.click();
    await offering("潮汐 API").getByText("2 / 3 次", { exact: true }).waitFor();
    await offering("曙光 API").getByText("3 / 3 次", { exact: true }).waitFor();
    await page.locator("#offer-sort").selectOption("success");
    await page.waitForFunction(() => {
      const offers = document.querySelector("#offer-list");
      return !offers.hasAttribute("aria-busy") && offers.querySelector("article")?.textContent.includes("曙光 API");
    });

    // Hold the first request until the stop click, then let Chromium send it
    // through the original TLS/auth path with no API-context replay.
    let releaseProbe;
    const probeRelease = new Promise(resolve => { releaseProbe = resolve; });
    await page.route("**/ui/api/gateway/probe", async route => {
      await probeRelease;
      await route.continue();
    }, { times: 1 });
    await openBatch();
    try {
      const [pendingProbe] = await Promise.all([
        page.waitForRequest(origin + "/ui/api/gateway/probe"), start.click()
      ]);
      assert.equal(pendingProbe.postDataJSON().model_id, "DeepSeek-V4_1-Flash");
      await batch.getByRole("button", { name: "本筆完成後停止" }).click();
      await batch.getByText("本筆完成後停止…", { exact: true }).waitFor();
    } finally {
      releaseProbe();
    }
    try {
      await batch.getByText("已完成 1 / 2，成功 1 · 已停止", { exact: true }).waitFor();
    } catch (error) {
      const progress = await batch.locator("#offer-batch-progress").textContent();
      const results = await batch.locator(".batch-result").allTextContents();
      const refreshing = await page.locator("#offer-list").getAttribute("aria-busy");
      throw new Error("Stopped probe progress: " + progress + " / " + JSON.stringify(results) + " / refreshing=" + refreshing, {cause: error});
    }
    assert.equal(await batch.getByText("未執行", { exact: true }).count(), 1);
    await close.click();

    await openBatch();
    await start.click();
    await batch.getByText("已完成 1 / 2，成功 0 · 上游限流", { exact: true }).waitFor();
    await batch.getByText("上游限流 · HTTP 429", { exact: true }).waitFor();
    assert.equal(await batch.getByText("未執行", { exact: true }).count(), 1);
    await close.click();

    await page.reload();
    assert.equal(await page.locator("html").getAttribute("data-wallpaper"), "elaina-chibi");
    assert.equal(await page.locator("html").getAttribute("data-theme"), "dark");
    await page.locator("#model-list").getByRole("button", { name: /DeepSeek 4\.1 Flash/ }).click();
    assert.equal(await page.locator("#offer-sort").inputValue(), "success");
    const appearance = () => page.evaluate(() => {
      const root = document.documentElement, css = getComputedStyle(root);
      return [root.dataset.theme, root.dataset.wallpaper, root.dataset.textSize, css.fontSize,
        css.getPropertyValue("--primary"), css.getPropertyValue("--glass")];
    });
    const liteAppearance = await appearance();
    const assertToolAppearance = async () => {
      assert.deepEqual(await appearance(), liteAppearance, "tools did not inherit Lite appearance");
      assert.equal(await page.locator("html").getAttribute("data-reduce-motion"), "true");
      await page.locator(".atlas-scene").waitFor();
      await assertLayout();
    };
    const modelActions = page.getByRole("group", {name: "模型操作", exact: true});
    const pelicanLink = modelActions.getByRole("link", {name: "鵜鶘測試", exact: true});
    await pelicanLink.waitFor();
    assert.equal(await page.locator("#model-tool-menu").getAttribute("open"), null, "daily tools should not need the management menu");
    assert.equal(await pelicanLink.evaluate(link => getComputedStyle(link).textDecorationLine), "none");
    await modelActions.getByRole("link", {name: "價格比較", exact: true}).click();
    await page.getByRole("heading", {name: "價格比較", exact: true}).waitFor();
    const priceCard = page.locator(".tool-row-list .card").filter({has: page.getByRole("heading", {name: "潮汐 API", exact: true})});
    await priceCard.waitFor();
    await priceCard.getByRole("heading", {name: "手填價格", exact: true}).waitFor();
    await priceCard.getByText("每日額度 · 20 次／日 · 手填", {exact: true}).waitFor();
    assert.equal(await page.locator("#lite-tool-model").textContent(), "DeepSeek 4.1 Flash");
    await assertToolAppearance();
    await priceCard.getByRole("button", {name: "同步站方價格", exact: true}).click();
    await priceCard.getByText("站方公布的各群組標價", {exact: true}).click();
    await priceCard.getByText("0.002 USD / 1K tokens", {exact: true}).waitFor();
    await priceCard.getByRole("button", {name: "群組價格歷史", exact: true}).click();
    await page.locator("#detail").getByRole("heading", {name: "輸入價格", exact: true}).waitFor();
    await page.locator("#detail-close").click();
    await priceCard.getByRole("button", {name: "價格與歷史", exact: true}).click();
    await page.locator("#detail").getByRole("heading", {name: "站方標價", exact: true}).waitFor();
    await page.locator("#detail-close").click();
    await page.getByRole("link", {name: "返回模型", exact: true}).click();
    await pelicanLink.click();
    await page.getByRole("heading", {name: "鵜鶘騎單車", exact: true}).waitFor();
    const verificationCard = name => page.locator(".tool-provider-card")
      .filter({has: page.getByRole("heading", {name, exact: true})});
    const firstCard = verificationCard("潮汐 API"), secondCard = verificationCard("曙光 API");
    const firstPast = firstCard.getByRole("button", {name: "歷次作品", exact: true});
    const secondPast = secondCard.getByRole("button", {name: "歷次作品", exact: true});
    const gallery = page.locator("#verification-history");
    await firstCard.getByText("尚未測試", {exact: true}).waitFor();
    await secondCard.getByText("尚未測試", {exact: true}).waitFor();
    assert.equal(await page.locator("#lite-tool-model").textContent(), "DeepSeek 4.1 Flash");
    assert(!(await gallery.isVisible()), "history crowds the provider comparison");
    await firstPast.click();
    await gallery.getByText("尚無這條連線的測試紀錄。", {exact: true}).waitFor();
    await assertToolAppearance();
    const pelican = firstCard.getByRole("button", {name: "執行測試", exact: true});
    const firstPost = page.waitForRequest(req => req.method() === "POST" && req.url().endsWith("/tools/api/verification/runs"));
    await pelican.click();
    const requestBody = (await firstPost).postDataJSON();
    assert.equal(requestBody.selection.model_id, "deepseek-v4.1-flash");
    const drawings = gallery.locator(".tool-drawing");
    const firstLatest = firstCard.locator(".tool-drawing"), secondLatest = secondCard.locator(".tool-drawing");
    await drawings.first().waitFor();
    await firstLatest.waitFor();
    assert(!(await page.getByLabel("作品 B", {exact: true}).isVisible()), "second slot is visible before a comparison exists");
    await pelican.click();
    await drawings.nth(1).waitFor();
    assert(await drawings.first().evaluate(img => img.complete && img.naturalWidth > 0), "the saved SVG did not render");
    assert.notEqual(await drawings.first().getAttribute("src"), await drawings.nth(1).getAttribute("src"));
    await page.setViewportSize({width: 402, height: 874});
    await assertToolAppearance();
    const [a, b] = await Promise.all((await drawings.all()).map(img => img.boundingBox()));
    assert(Math.abs(a.y - b.y) <= 1 && Math.abs(a.width - b.width) <= 1 && b.x > a.x,
      "the two works are not aligned on a phone");
    await page.getByRole("button", {name: "放大作品 A", exact: true}).click();
    const expanded = page.locator("#detail .tool-drawing-expanded");
    await expanded.waitFor();
    assert.equal(await expanded.getAttribute("src"), await drawings.first().getAttribute("src"));
    assert((await expanded.boundingBox()).width > a.width, "the detail does not enlarge the drawing");
    await page.locator("#detail-close").click();
    const artworkA = page.getByLabel("作品 A", {exact: true}), artworkB = page.getByLabel("作品 B", {exact: true});
    const latest = await artworkA.inputValue(), baseline = await artworkB.inputValue();
    await artworkA.selectOption(baseline);
    await artworkB.selectOption(latest);
    const refreshWorks = page.getByRole("button", {name: "更新測試紀錄", exact: true});
    await refreshWorks.click();
    await page.waitForFunction(() => !document.querySelector(".tool-history-actions button").disabled);
    assert.deepEqual([await artworkA.inputValue(), await artworkB.inputValue()], [baseline, latest], "refresh changed the selected works");
    await page.setViewportSize({width: 320, height: 680});
    await page.evaluate(() => { document.documentElement.style.fontSize = "200%"; });
    await assertLayout();
    await page.evaluate(() => { document.documentElement.style.removeProperty("font-size"); });
    await secondPast.click();
    await gallery.getByText("尚無這條連線的測試紀錄。", {exact: true}).waitFor();
    assert.equal(await drawings.count(), 0, "another connection's drawings are still visible");
    await firstPast.click();
    await drawings.nth(1).waitFor();
    const duplicate = await page.evaluate(async body => (await fetch("/tools/api/verification/runs", {
      method: "POST", headers: {"Content-Type": "application/json"}, body: JSON.stringify(body)
    })).status, requestBody);
    assert.equal(duplicate, 409, "a repeated verification request sent a second inference");
    await page.getByRole("button", {name: "模型真實性證據", exact: true}).click();
    await page.getByRole("button", {name: "分析既有請求紀錄", exact: true}).click();
    await page.getByRole("heading", {name: "分析紀錄", exact: true}).waitFor();
    await page.locator("#detail-close").click();
    const failedDrawing = url => url.pathname === "/tools/api/verification/runs" && url.searchParams.get("id") === baseline;
    await page.route(failedDrawing, route => route.fulfill({status: 503, body: "artwork unavailable"}));
    await page.reload();
    await firstPast.click();
    await page.getByText("讀取失敗，可更新紀錄重試。", {exact: true}).waitFor();
    await drawings.first().waitFor();
    assert.equal(await drawings.count(), 1, "one failed drawing hid a valid drawing");
    await page.unroute(failedDrawing);
    await refreshWorks.click();
    await drawings.nth(1).waitFor();
    await gallery.getByRole("button", {name: "收起歷次作品", exact: true}).click();
    const secondTest = secondCard.getByRole("button", {name: "執行測試", exact: true});
    const secondPost = page.waitForRequest(req => req.method() === "POST" && req.url().endsWith("/tools/api/verification/runs"));
    await secondTest.click();
    const secondSelection = (await secondPost).postDataJSON().selection;
    assert.notEqual(secondSelection.provider_id, requestBody.selection.provider_id);
    assert.equal(secondSelection.model_id, "DeepSeek-V4_1-Flash");
    await secondCard.getByText("回應無法預覽", {exact: true}).waitFor();
    assert(await firstLatest.isVisible(), "an invalid result hides another provider's work");
    await secondTest.click();
    await secondLatest.waitFor();
    const [firstTile, secondTile] = await Promise.all([firstLatest.boundingBox(), secondLatest.boundingBox()]);
    assert(Math.abs(firstTile.y - secondTile.y) <= 1 && Math.abs(secondTile.x - firstTile.x) >= Math.min(firstTile.width, secondTile.width),
      "providers' latest works are not side by side");
    assert.notEqual(await firstLatest.getAttribute("src"), await secondLatest.getAttribute("src"));
    const unreadableProvider = url => url.pathname === "/tools/api/verification/runs" &&
      url.searchParams.get("latest") === "1" && url.searchParams.get("provider_id") === secondSelection.provider_id;
    await page.route(unreadableProvider, route => route.fulfill({status: 503, body: "history unavailable"}));
    await page.getByRole("button", {name: "重新整理", exact: true}).click();
    await secondCard.getByText("紀錄讀取失敗", {exact: true}).waitFor();
    await firstLatest.waitFor();
    await page.unroute(unreadableProvider);
    await page.getByRole("button", {name: "重新整理", exact: true}).click();
    await secondLatest.waitFor();
    const backToLite = page.getByRole("link", {name: "返回模型", exact: true});
    await page.setViewportSize({width: 1280, height: 900});
    await page.evaluate(() => window.scrollTo(0, 0));
    await assertToolAppearance();
    await backToLite.waitFor();
    const backBox = await backToLite.boundingBox();
    assert(backBox.y >= 0 && backBox.x >= 0 && backBox.x + backBox.width <= 1280, "desktop return link is outside the viewport");
    await page.setViewportSize({width: 402, height: 874});
    await backToLite.click();
    await offering("潮汐 API").waitFor();
    await page.getByRole("button", {name: "返回我的模型", exact: true}).click();
    const catalogModel = "fixture-catalog-model";
    assert.equal(await page.evaluate(async ({provider_id, id}) => (await fetch("/tools/api/models", {
      method: "POST", headers: {"Content-Type": "application/json"}, body: JSON.stringify({provider_id, id, display_name: "Catalog Fixture Model"})
    })).status, {provider_id: requestBody.selection.provider_id, id: catalogModel}), 201);
    await page.getByRole("link", {name: "模型目錄", exact: true}).click();
    await page.getByRole("heading", {name: "模型目錄", exact: true}).waitFor();
    assert(await page.locator("#models-catalog").isVisible());
    assert(!(await page.locator("#models-owned").isVisible()), "catalog duplicates the main model list");
    assert.equal(await page.getByRole("tablist").count(), 0);
    assert.equal(await page.locator("#catalog-membership").inputValue(), "new");
    await page.locator("#catalog").getByRole("button", {name: /Catalog Fixture Model/}).waitFor();
    assert.equal(await page.locator("#catalog .state-chip").count(), 0, "joined entries crowd the catalog by default");
    await page.locator("#catalog-search").fill(catalogModel);
    await page.locator("#catalog").getByRole("button", {name: /Catalog Fixture Model/}).click();
    await page.locator("#detail").getByRole("button", {name: "加入我的模型", exact: true}).click();
    await page.locator("#catalog").getByRole("button", {name: /Catalog Fixture Model/}).waitFor({state: "hidden"});
    await page.locator("#catalog-membership").selectOption("");
    await page.locator("#catalog").getByText("已加入 1 個連線", {exact: true}).waitFor();
    await assertToolAppearance();
    await page.getByRole("link", {name: "返回我的模型", exact: true}).click();
    const catalogTile = page.locator("#model-list").getByRole("button", {name: /Catalog Fixture Model/});
    await catalogTile.waitFor();
    const filterTrigger = page.getByRole("button", {name: /^篩選模型/});
    const modelFilter = page.getByRole("dialog", {name: "篩選模型", exact: true});
    const finishFilter = modelFilter.getByRole("button", {name: "完成", exact: true});
    await page.setViewportSize({width: 320, height: 680});
    const toolbar = await page.locator(".model-directory-actions").evaluate(node => [...node.children].map(control => {
      const {x, y, width, height} = control.getBoundingClientRect();
      return {x, y, width, height};
    }));
    assert.equal(toolbar.length, 2);
    assert.equal(toolbar[0].height, 48);
    assert.equal(toolbar[0].height, toolbar[1].height);
    assert.equal(toolbar[0].y, toolbar[1].y, "filter and catalog controls are on different rows");
    assert(toolbar[0].x + toolbar[0].width <= toolbar[1].x);
    await filterTrigger.click();
    const deepseekBrand = modelFilter.getByRole("button", {name: "DeepSeek", exact: true});
    assert.equal(await deepseekBrand.locator("use").first().getAttribute("href"), "/gateway-model-brands.svg#icon-brand-deepseek");
    await deepseekBrand.click();
    await finishFilter.click();
    await modelFilter.waitFor({state: "hidden"});
    assert(await tile.isVisible());
    assert(!(await catalogTile.isVisible()), "brand filter retained an unrelated model");
    await filterTrigger.click();
    assert.equal(await deepseekBrand.getAttribute("aria-pressed"), "true");
    await page.keyboard.press("Escape");
    await modelFilter.waitFor({state: "hidden"});
    assert(await filterTrigger.evaluate(node => node === document.activeElement), "model filter lost the trigger focus");
    await page.locator("#model-search").fill(catalogModel);
    await page.locator("#model-list").getByText("找不到符合的模型。", {exact: true}).waitFor();
    await filterTrigger.click();
    assert.equal(await deepseekBrand.getAttribute("aria-pressed"), "true", "search cleared the selected brand");
    await modelFilter.getByRole("button", {name: "未歸類", exact: true}).click();
    await finishFilter.click();
    await catalogTile.waitFor();
    assert(!(await tile.isVisible()), "unclassified filter retained a branded model");
    assert.equal(await page.locator("#model-search").inputValue(), catalogModel);
    await filterTrigger.click();
    await modelFilter.getByRole("button", {name: "全部品牌", exact: true}).click();
    await finishFilter.click();
    await page.locator("#model-search").fill("");
    await tile.waitFor();
    await catalogTile.waitFor();
    await page.setViewportSize({width: 402, height: 874});
    await page.getByRole("link", {name: "模型目錄", exact: true}).click();
    await page.getByRole("link", {name: "批次測試與整理", exact: true}).click();
    await page.getByRole("heading", {name: "批次測試與整理", exact: true}).waitFor();
    assert(await page.locator("#models-owned").isVisible());
    await page.getByRole("link", {name: "返回模型目錄", exact: true}).click();
    await page.getByRole("link", {name: "已隱藏與封存", exact: true}).click();
    await page.getByRole("heading", {name: "已隱藏與封存", exact: true}).waitFor();
    await page.getByRole("link", {name: "返回模型目錄", exact: true}).click();
    await page.getByRole("link", {name: "返回我的模型", exact: true}).click();
    await page.getByRole("button", {name: "設定", exact: true}).click();
    await page.getByRole("link", {name: "外部效能資料", exact: true}).click();
    await page.getByRole("heading", {name: "外部效能資料", exact: true}).waitFor();
    await page.getByRole("heading", {name: "LMSpeed 外部參考", exact: true}).waitFor();
    await assertToolAppearance();
    await page.getByRole("link", {name: "返回 Lite", exact: true}).click();
    await page.getByRole("button", {name: "完成", exact: true}).click();
    await go("供應商");
    await page.getByRole("link", {name: "批次匯入", exact: true}).click();
    await page.getByRole("button", {name: "建立待確認清單", exact: true}).waitFor();
    assert(await page.locator("#providers-new > details:first-child #import-form").isVisible());
    assert(!(await page.locator("#onboarding-form").isVisible()));
    await assertToolAppearance();
    await page.getByRole("link", {name: "返回 Lite", exact: true}).click();
    await page.locator("#provider-list").getByRole("button", {name: "管理 潮汐 API", exact: true}).click();
    await provider.getByText("進階管理", {exact: true}).click();
    await provider.getByRole("link", {name: "Key 版本與端點", exact: true}).click();
    await page.locator("#detail").getByRole("tab", {name: "帳戶與 Key", exact: true}).waitFor();
    const aboutInTools = page.locator(".tool-provider-about");
    assert(!(await aboutInTools.isVisible()), "provider introduction crowds the Key tab");
    await page.locator("#detail").getByRole("tab", {name: "概覽", exact: true}).click();
    await aboutInTools.getByText("供應商簡介", {exact: true}).click();
    assert.equal(await aboutInTools.locator("p").textContent(), description);
    await page.goto(origin);
    await tile.waitFor();
    await add("手動模型測試站", "a/no-catalog", process.env.MODELATLAS_TEST_UPSTREAM_A, "");
    const recovery = provider.locator(".catalog-recovery");
    await recovery.getByText(/讀取站方模型清單失敗/).waitFor();
    await recovery.getByRole("button", {name: "手動加模型", exact: true}).click();
    const manualModel = page.getByRole("dialog", {name: "手動新增模型", exact: true});
    await manualModel.getByLabel("模型 ID", {exact: true}).fill("fixture-manual-catalog");
    await manualModel.getByRole("button", {name: "儲存", exact: true}).click();
    await manualModel.waitFor({state: "hidden"});
    await provider.getByRole("button", {name: "查看模型", exact: true}).click();
    await page.locator("#model-list").getByRole("button", {name: /fixture-manual-catalog/}).waitFor();
    assert(!(await tile.isVisible()), "manual model was added outside the selected provider");
    await page.goto(origin + "/?model=deepseek-v4-1-flash");
    await offering("潮汐 API").getByRole("button", {name: "連線詳情", exact: true}).click();
    await history.locator("#offer-scope > summary").click();
    await history.getByRole("button", {name: "新增圖片生成連線", exact: true}).click();
    assert.equal(await manualModel.getByLabel("連線模型類型", {exact: true}).inputValue(), "IMAGES_GENERATION");
    await manualModel.getByLabel("模型 ID", {exact: true}).fill("gpt-image-fixture");
    await manualModel.getByRole("button", {name: "儲存", exact: true}).click();
    await manualModel.waitFor({state: "hidden"});
    await page.getByRole("button", {name: "返回我的模型", exact: true}).click();
    await tile.getByText(/最低 US\$0\.02／次 · 最高 20 次／日/).waitFor();
    await tile.click();
    await offering("潮汐 API").getByRole("button", {name: "連線詳情", exact: true}).click();
    await history.getByRole("button", {name: "手填", exact: true}).click();
    await terms.getByLabel("計費方式", {exact: true}).selectOption("free");
    await terms.getByRole("button", {name: "儲存價格與額度", exact: true}).click();
    await terms.waitFor({state: "hidden"});
    await history.getByText("免費", {exact: true}).waitFor();
    await history.getByRole("button", {name: "關閉", exact: true}).click();
    await page.getByRole("button", {name: "返回我的模型", exact: true}).click();
    await tile.getByText("最高 20 次／日", {exact: true}).waitFor();

    const modelType = modelFilter.getByRole("combobox", {name: "模型類型", exact: true});
    const imageTile = page.locator("#model-list").getByRole("button", {name: /gpt-image-fixture/});
    await filterTrigger.click();
    await modelType.selectOption("text");
    await finishFilter.click();
    assert.equal(await imageTile.count(), 0);
    await filterTrigger.click();
    await modelType.selectOption("image");
    const gptBrand = modelFilter.getByRole("button", {name: "GPT", exact: true});
    await gptBrand.click();
    assert.equal(await gptBrand.getAttribute("aria-pressed"), "true");
    assert.equal(await modelType.inputValue(), "image");
    await finishFilter.click();
    await imageTile.waitFor();
    assert.equal(await tile.count(), 0);
    assert.equal(await imageTile.locator(".model-terms-line").count(), 0, "unknown image terms should be blank");
    await imageTile.click();
    await offering("潮汐 API").getByRole("button", {name: "測試出圖", exact: true}).click();
    const imageTest = page.getByRole("dialog", {name: "測試出圖", exact: true});
    assert.equal(await imageTest.getByLabel("提示詞", {exact: true}).inputValue(), "");
    await imageTest.getByLabel("提示詞", {exact: true}).fill("fixture image preview");
    await imageTest.getByRole("button", {name: "生成一張", exact: true}).click();
    await imageTest.getByText(/完成 · 256 × 192 · \d+ ms/).waitFor();
    const original = imageTest.getByRole("link", {name: "開啟原圖", exact: true});
    assert((await original.getAttribute("href")).startsWith("blob:" + origin + "/"));
    assert.equal(await original.getByRole("img").evaluate(img => img.naturalWidth), 256);
    await imageTest.getByRole("button", {name: "關閉", exact: true}).click();
    await page.goto(origin + "/?view=usage");
    await page.getByRole("link", {name: "完整請求紀錄", exact: true}).click();
    await page.getByRole("heading", {name: "完整請求紀錄", exact: true}).waitFor();
    const exportLink = page.getByRole("link", {name: "下載篩選後的全部紀錄", exact: true});
    await exportLink.waitFor();
    await assertToolAppearance();
    const exported = await context.request.get(origin + await exportLink.getAttribute("href"));
    assert.equal(exported.status(), 200);
    assert.match(exported.headers()["content-type"], /ndjson/);
    await page.getByRole("link", {name: "返回 Lite", exact: true}).click();
    await page.getByRole("button", {name: "設定", exact: true}).click();
    await page.getByRole("link", {name: "備份與同步", exact: true}).click();
    const backup = page.getByRole("region", {name: "加密雲端備份", exact: true});
    await backup.getByText("設定備份目的地", {exact: true}).click();
    await backup.getByLabel("雲端儲存", {exact: true}).selectOption("b2");
    await backup.getByLabel("S3 端點", {exact: true}).fill("https://s3.us-west-004.backblazeb2.com");
    await backup.getByLabel("Bucket 名稱", {exact: true}).fill("atlas-test-bucket");
    await backup.getByLabel("Access Key ID", {exact: true}).fill("synthetic-b2-access-id");
    await backup.getByLabel("Secret Access Key", {exact: true}).fill("synthetic-b2-secret");
    await backup.getByLabel("我已在其他地方安全保存 ModelAtlas 主金鑰", {exact: true}).check();
    await assertToolAppearance();
    await backup.getByRole("button", {name: "儲存目的地", exact: true}).click();
    await backup.getByText("Backblaze B2 · atlas-test-bucket", {exact: true}).waitFor();
    assert(await backup.getByRole("button", {name: "立即備份目前資料", exact: true}).isEnabled());
    assert.equal(await backup.getByLabel("定時備份", {exact: true}).isChecked(), false);
    assert.equal(await backup.locator("input[name=secret_access_key]").inputValue(), "");
    await page.getByRole("heading", {name: "跨裝置同步", exact: true}).waitFor();
    await page.getByRole("link", {name: "匯出設定（不含 Key）", exact: true}).waitFor();
    await page.getByText("此裝置可作同步中心", {exact: false}).waitFor();
    await assertToolAppearance();
    await page.getByRole("link", {name: "返回 Lite", exact: true}).click();
    await page.getByRole("button", {name: "完成", exact: true}).click();
    await go("用量");
    assert.equal(await page.locator("#usage-range").inputValue(), "30d");
    await page.getByRole("button", { name: "設定", exact: true }).click();
    await page.getByText("此部署使用外部更新工具。", { exact: false }).waitFor();
    assert(await page.getByRole("button", {name: "檢查更新", exact: true}).isDisabled());
    assert.equal(await page.getByRole("link", {name: "進階工作區", exact: true}).count(), 0);
    await page.getByRole("button", { name: "登出", exact: true }).click();
    await page.waitForURL(origin + "/login");
    await page.getByRole("heading", { name: "管理員登入", exact: true }).waitFor();
    await page.evaluate(() => { document.documentElement.style.fontSize = "200%"; });
    await assertLayout();
    assert(!(await context.cookies(origin)).some(cookie => cookie.name === session.name), "logout did not clear the session");
    assert.equal((await context.request.get(origin + "/ui/api/status")).status(), 401);
    await page.goBack();
    await page.waitForURL(origin + "/login");
    await page.getByRole("heading", { name: "管理員登入", exact: true }).waitFor();
    const basic = Buffer.from("admin:" + process.env.MODELATLAS_TEST_TOKEN).toString("base64");
    assert.equal((await client.get(origin + "/api/v1/status", { headers: { Authorization: "Basic " + basic } })).status(), 200);
    await page.goto(workspace + "/workspace");
    await page.waitForURL(workspace + "/login");
    await page.getByLabel("密碼", { exact: true }).fill(process.env.MODELATLAS_TEST_TOKEN);
    await page.getByRole("button", { name: "登入", exact: true }).click();
    await page.waitForURL(workspace + "/");
    await page.locator("#model-list").getByRole("button", { name: /DeepSeek 4\.1 Flash/ }).waitFor();
    await page.setViewportSize({width: 320, height: 750});
    await page.emulateMedia({colorScheme: "dark"});
    await page.evaluate(() => { document.documentElement.style.fontSize = "200%"; });
    await assertLayout();
    await page.getByRole("button", { name: "設定", exact: true }).click();
    await page.getByRole("button", { name: "檢查更新", exact: true }).click();
    await page.getByText("可更新 v0.8.6-preview.1", { exact: true }).waitFor();
    assert.equal(await page.getByRole("link", { name: "版本說明", exact: true }).getAttribute("href"),
      "https://github.com/Tsenjii/ModelAtlas-2/releases/tag/v0.8.6-preview.1");
    assert(await page.getByRole("button", { name: "安裝新版", exact: true }).isEnabled());
    await page.locator("#update-settings").scrollIntoViewIfNeeded();
    await assertLayout();
    await page.getByRole("button", { name: "完成", exact: true }).click();
    await page.getByRole("link", { name: "進階工作區", exact: true }).click();
    await page.waitForURL(workspace + "/workspace");
    await page.waitForFunction(() => document.documentElement.dataset.workspaceReady === "true");
    assert.equal((await context.request.get(workspace + "/workspace/api/providers")).status(), 200);
    assert.equal((await context.request.get(workspace + "/api/v1/providers")).status(), 401);
    const rejectedInference = await page.evaluate(async () => {
      const response = await fetch(workspaceAPI + "/infer", {method: "POST", headers: {"Content-Type": "application/json"}, body: "{}"});
      return {status: response.status, body: (await response.text()).trim()};
    });
    assert.deepEqual(rejectedInference, {status: 404, body: "manual conversation missing"});
    await page.getByRole("link", { name: "回管理首頁", exact: true }).click();
    await page.waitForURL(workspace + "/");
    await page.locator("#model-list").getByRole("button", { name: /DeepSeek 4\.1 Flash/ }).click();
    await offering("潮汐 API").getByRole("button", {name: "連線詳情", exact: true}).click();
    await history.getByRole("button", {name: "刪除連線", exact: true}).click();
    const remove = page.locator("#remove-dialog");
    await remove.getByText(/潮汐 API/).waitFor();
    await remove.getByRole("button", {name: "取消", exact: true}).click();
    await offering("潮汐 API").waitFor();
    await history.getByRole("button", {name: "刪除連線", exact: true}).click();
    await assertLayout();
    await remove.getByRole("button", {name: "確認刪除", exact: true}).click();
    await offering("潮汐 API").waitFor({state: "detached"});
    await offering("曙光 API").waitFor();
    await page.locator("#offer-count").getByText("1 家供應商 · 1 個連線", {exact: true}).waitFor();

    await go("供應商");
    await page.locator("#provider-list").getByRole("button", {name: "管理 潮汐 API", exact: true}).click();
    await provider.locator(".credential-row").filter({has: page.getByRole("heading", {name: "主要 Key", exact: true})}).locator("summary").click();
    await provider.getByRole("button", {name: "設定 Key 額度", exact: true}).click();
    const quota = page.locator("#quota-form");
    await quota.getByLabel("剩餘額度", {exact: true}).fill("123.45");
    await quota.getByLabel("單位", {exact: true}).selectOption("TWD");
    await quota.getByRole("button", {name: "儲存額度", exact: true}).click();
    await provider.getByText("手動額度 · 123.45 TWD", {exact: false}).waitFor();
    await provider.getByRole("button", {name: "設定 Key 額度", exact: true}).click();
    assert.equal(await quota.getByLabel("剩餘額度", {exact: true}).inputValue(), "123.45");
    await quota.getByLabel("剩餘額度", {exact: true}).fill("0");
    await quota.getByRole("button", {name: "儲存額度", exact: true}).click();
    await provider.getByText("手動額度 · 0 TWD", {exact: false}).waitFor();
    await provider.getByText("站方額度 · 0 站方額度", {exact: false}).waitFor();
    await assertLayout();
    await page.getByRole("button", { name: "設定", exact: true }).click();
    await page.getByRole("button", { name: "安裝新版", exact: true }).click();
    await page.getByText("正在備份、試跑新版並重啟。服務回來後請重新登入。", { exact: true }).waitFor();
    assert.deepEqual(errors, []);
    assert.deepEqual(external, []);
    console.log(JSON.stringify({ result: "PASS", screenshots: 6, realTLSUpstreams: 2, groupedModel: true, scopedQuota: true,
      accountCheckin: true, streamedFailover: true, narrowDarkLargeText: true, activityCharts: true, keyPolicy: true,
      scopedOfferHistory: true, batchProbes: true, batchStop: true, batchRateLimit: true,
      providerCardHitArea: true, scopedKeyUsage: true, batchSelectAll: true, dialogCopyFeedback: true, browserSignIn: true,
      sharedWorkspace: true, settingsUpdate: true, connectionTrend: true, scopedSitePerformance: true,
      copiedUpstreamIDAndKey: true, scopedRemoval: true, manualKeyQuota: true, onDemandTools: true, providerProxy: true,
      brandPicker: true, catalogRecovery: true, manualVerification: true, manualModelTerms: true, modelType: true, imagePreview: true,
      cloudBackupSetup: true, modelRequestHeaders: true }));
  } finally {
    await client.dispose();
    await browser.close();
  }
})().catch(error => { console.error(error.message); process.exitCode = 1; });
