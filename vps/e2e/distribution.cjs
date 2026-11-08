"use strict";

const { chromium, request } = require(process.env.PLAYWRIGHT_MODULE || "playwright");
const assert = require("node:assert/strict");
const fs = require("node:fs/promises");

(async () => {
  const browser = await chromium.launch({ headless: true });
  const [origin, upstream] = process.argv.slice(2);
  const context = await browser.newContext({
    viewport: { width: 402, height: 874 }, isMobile: true, hasTouch: true, reducedMotion: "reduce",
    httpCredentials: { username: "admin", password: process.env.MODELATLAS_TEST_TOKEN }
  });
  const client = await request.newContext();
  const page = await context.newPage();
  page.setDefaultTimeout(12000);
  const errors = [];
  const external = [];
  page.on("pageerror", error => errors.push(error.message));
  page.on("request", req => { if (!req.url().startsWith(origin + "/")) external.push(req.url()); });
  await fs.mkdir("e2e/screenshots", { recursive: true });
  const go = name => page.getByRole("navigation").getByRole("button", { name, exact: true }).click();
  const assertLayout = async () => {
    const layout = await page.evaluate(() => ({
      width: innerWidth,
      scroll: document.documentElement.scrollWidth,
      headerOverlaps: scrollY === 0 && document.querySelector(".gateway-bar").getBoundingClientRect().bottom >
        [...document.querySelectorAll("main h1")].find(node => node.checkVisibility()).getBoundingClientRect().top,
      controls: [...document.querySelectorAll('button,summary,input:not([type=checkbox]),select,textarea,.check,.choice,.link-button')]
        .filter(node => node.checkVisibility() && node.getBoundingClientRect().height < 47.9)
        .map(node => node.id || node.textContent)
    }));
    assert(layout.scroll <= layout.width, "horizontal overflow " + JSON.stringify(layout));
    assert(!layout.headerOverlaps, "header overlays the page title");
    assert.deepEqual(layout.controls, [], "tap targets shorter than 48px");
  };
  const shot = async name => {
    await assertLayout();
    await page.screenshot({ path: "e2e/screenshots/distribution-" + name + ".png", animations: "disabled" });
  };
  const add = async (name, side, key, description) => {
    await page.getByRole("button", { name: "＋ 新增供應商" }).click();
    const form = page.locator("#upstream-form");
    await form.getByLabel("名稱", { exact: true }).fill(name);
    await form.getByLabel("簡介", { exact: true }).fill(description);
    await form.getByLabel("Base URL", { exact: true }).fill(upstream + "/" + side + "/v1");
    await form.getByLabel("上游 API Key", { exact: true }).fill(key);
    await form.getByRole("button", { name: "儲存並同步模型" }).click();
    await page.locator("#provider-detail-content").getByRole("heading", { name, exact: true }).waitFor();
  };
  const offering = name => page.locator("#offer-list article").filter({ hasText: name });

  try {
    await page.goto(origin);
    await add("潮汐 API", "a", process.env.MODELATLAS_TEST_UPSTREAM_A, "日常主力模型站");
    const provider = page.locator("#provider-detail-content");
    await provider.getByRole("button", { name: "編輯資料" }).click();
    await page.locator("#provider-form").getByLabel("需要每日簽到").check();
    await page.locator("#provider-form").getByLabel("簽到網址").fill(upstream + "/checkin");
    await page.getByRole("button", { name: "儲存供應商資料" }).click();
    await provider.getByRole("link", { name: "前往站點簽到" }).waitFor();
    await provider.getByRole("button", { name: "我已簽到" }).click();
    await provider.locator(".checkin-line").getByText("今日簽到已記錄", { exact: true }).waitFor();
    await provider.getByRole("button", { name: "記錄額度", exact: true }).click();
    await page.getByLabel("剩餘額度").fill("2.50");
    await page.getByRole("button", { name: "儲存額度" }).click();
    await provider.getByText("2.50 USD", { exact: false }).first().waitFor();
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
    await provider.getByRole("button", { name: "查詢站方簽到", exact: true }).click();
    await provider.getByText("站方提供簽到", { exact: true }).waitFor();

    await go("模型");
    const tile = page.locator("#model-list").getByRole("button", { name: /DeepSeek 4\.1 Flash/ });
    await tile.waitFor();
    assert.equal(await tile.count(), 1, "same model appeared more than once");
    assert((await tile.innerText()).includes("2 家供應商 · 2 個連線"));
    await tile.click();
    await page.waitForFunction(() => document.querySelectorAll("#offer-list article").length === 2);
    for (const name of ["潮汐 API", "曙光 API"]) {
      const row = offering(name);
      await row.getByRole("button", { name: "測試", exact: true }).click();
      await row.getByText("1 / 1 次", { exact: true }).waitFor();
      assert((await row.locator(".metrics").innerText()).includes("100%"));
    }
    await page.locator("#offer-sort").selectOption("ttft");
    await page.waitForFunction(() => document.querySelector("#offer-list article")?.textContent.includes("潮汐 API"));
    await page.locator("#notice").waitFor({ state: "hidden" });
    await shot("model-402-light");
    await page.setViewportSize({ width: 1344, height: 860 });
    await shot("model-1344-light");

    await page.setViewportSize({ width: 402, height: 874 });
    await go("供應商");
    await page.locator("#providers-back").click();
    await page.locator("#provider-count").getByText("已載入 2 家", { exact: false }).waitFor();
    await page.locator("#provider-list article").filter({ hasText: "潮汐 API" }).getByText("100%", { exact: true }).waitFor();
    await shot("providers-402-light");
    await go("模型");
    await page.getByRole("button", { name: "選取分發連線" }).click();
    for (const name of ["潮汐 API", "曙光 API"]) await offering(name).getByRole("button", { name: "加入路由" }).click();
    await page.getByRole("button", { name: "前往分發" }).click();
    await page.getByLabel("對外模型 ID").fill("atlas-chat");
    await page.getByRole("button", { name: "發布模型" }).click();
    await page.locator("#public-models").getByRole("heading", { name: "atlas-chat", exact: true }).waitFor();
    await page.getByText("發出新 Key", { exact: true }).click();
    await page.locator("#key-form").getByLabel("Key 名稱").fill("手機");
    await page.locator("#key-form").getByLabel("每日請求上限").fill("2");
    await page.locator("#key-models").getByLabel("atlas-chat").check();
    await page.getByRole("button", { name: "產生 Key" }).click();
    await page.locator("#issued-dialog").waitFor();
    const token = await page.locator("#issued-key").inputValue();
    assert(token.startsWith("sk-ma-"), "no distribution key issued");
    await page.locator("#issued-dialog").getByRole("button", { name: "完成" }).click();
    await page.waitForFunction(() => document.querySelector("#issued-key").value === "");
    const headers = { Authorization: "Bearer " + token };
    const models = await client.get(origin + "/v1/models", { headers });
    assert.equal(models.status(), 200);
    assert.deepEqual((await models.json()).data.map(item => item.id), ["atlas-chat"]);
    const result = await client.post(origin + "/v1/chat/completions", {
      headers, data: { model: "atlas-chat", stream: true, messages: [{ role: "user", content: "fallback" }] }
    });
    assert.equal(result.status(), 200);
    assert((await result.text()).includes("data: [DONE]"), "missing forwarded SSE completion");

    await page.getByRole("button", { name: "用量紀錄", exact: true }).click();
    await page.locator("#usage-list").getByText("曙光 API / DeepSeek-V4_1-Flash", { exact: true }).waitFor();
    assert(await page.locator("#usage-list").getByText("含備援 · 共 2 次上游請求", { exact: true }).isVisible());
    await page.locator("#usage-key").selectOption({ label: "手機" });
    await page.getByLabel("只看失敗").check();
    await page.locator("#usage-list").getByText("這個範圍還沒有請求。").waitFor();
    await page.getByLabel("只看失敗").uncheck();
    await page.locator("#usage-list").getByText("曙光 API / DeepSeek-V4_1-Flash", { exact: true }).waitFor();

    await page.setViewportSize({ width: 320, height: 680 });
    await page.emulateMedia({ colorScheme: "dark" });
    await page.evaluate(() => { document.documentElement.style.fontSize = "200%"; });
    await assertLayout();
    await page.getByRole("button", { name: "外觀設定" }).click();
    await page.getByLabel("背景").selectOption("elaina-chibi");
    await page.getByLabel("色彩").selectOption("dark");
    await assertLayout();
    await page.getByRole("dialog").getByRole("button", { name: "完成" }).click();
    await page.getByRole("button", { name: "模型與 Key", exact: true }).click();
    await page.locator("#key-list").getByText("今日 1 / 2 次", { exact: true }).waitFor();
    await assertLayout();
    const revoke = page.getByRole("button", { name: "撤銷", exact: true });
    await revoke.evaluate(button => button.scrollIntoView({ block: "center" }));
    assert((await revoke.boundingBox()).y + (await revoke.boundingBox()).height < (await page.getByRole("navigation").boundingBox()).y);
    page.once("dialog", dialog => dialog.accept());
    await revoke.click();
    await page.locator("#key-list").getByText("已撤銷", { exact: true }).waitFor();
    assert.equal((await client.get(origin + "/v1/models", { headers })).status(), 401);
    await page.reload();
    assert.equal(await page.locator("html").getAttribute("data-wallpaper"), "elaina-chibi");
    assert.equal(await page.locator("html").getAttribute("data-theme"), "dark");
    assert.deepEqual(errors, []);
    assert.deepEqual(external, []);
    console.log(JSON.stringify({ result: "PASS", screenshots: 3, realTLSUpstreams: 2, groupedModel: true, scopedQuota: true,
      accountCheckin: true, streamedFailover: true, narrowDarkLargeText: true }));
  } finally {
    await client.dispose();
    await browser.close();
  }
})().catch(error => { console.error(error.message); process.exitCode = 1; });
