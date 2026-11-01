const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const assert=require('node:assert/strict'),fs=require('node:fs/promises');
(async()=>{
 const browser=await chromium.launch({headless:true}),origin=process.argv[2];
 const context=await browser.newContext({httpCredentials:{username:'admin',password:process.env.MODELATLAS_TEST_TOKEN},viewport:{width:1280,height:1000},reducedMotion:'reduce'});
 const page=await context.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message));
 await fs.mkdir('e2e/screenshots',{recursive:true});
 try{
  await page.goto(origin);await page.waitForFunction(()=>modelPickerReady);await page.locator('#settings-toggle').click();await page.locator('#settings-data-tab').click();
  await page.locator('#system-status-summary').filter({hasText:'服務正常運作。'}).waitFor();
  assert.match(await page.locator('#system-status-details').innerText(),/已通過啟動檢查/);
  const last=await page.locator('#system-status-details').innerText();
  let arrived,release;const barrier=new Promise(r=>arrived=r),hold=new Promise(r=>release=r);let requests=0;
  await page.route('**/api/v1/system',async route=>{requests++;arrived();await hold;await route.fulfill({status:503,contentType:'text/plain',body:'暫時無法讀取服務狀態'});});
  await page.locator('#system-status-refresh').click();await barrier;
  assert(await page.locator('#system-status-refresh').isDisabled());assert.equal(await page.locator('#system-status-details').innerText(),last);
  await page.evaluate(()=>{loadSystemStatus();loadSystemStatus();});assert.equal(requests,1);release();
  await page.locator('#system-status-problem').filter({hasText:'保留上次檢查結果'}).waitFor();assert.equal(await page.locator('#system-status-details').innerText(),last);
  await page.unroute('**/api/v1/system');await page.locator('#system-status-refresh').click();await page.waitForFunction(()=>!systemStatusPending);assert(await page.locator('#system-status-problem').isHidden());
  for(const width of [320,375,1280])for(const theme of ['light','dark']){
   await page.setViewportSize({width,height:900});await page.evaluate(theme=>{document.documentElement.dataset.theme=theme;document.documentElement.style.fontSize='100%';},theme);
   await page.locator('#system-status-card').scrollIntoViewIfNeeded();
   assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),`${width}/${theme} overflow`);
   const box=await page.locator('#system-status-refresh').boundingBox();assert(box.height>=48,'48px update target');
   await page.screenshot({path:`e2e/screenshots/system-status-${width}-${theme}.png`,animations:'disabled'});
  }
  await page.setViewportSize({width:375,height:1000});await page.evaluate(()=>document.documentElement.style.fontSize='200%');await page.locator('#system-status-card').scrollIntoViewIfNeeded();
  assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'large text overflow');await page.screenshot({path:'e2e/screenshots/system-status-375-large.png',animations:'disabled'});
  assert(!/top-secret|modelatlas\.sqlite/.test(await page.locator('#system-status-card').innerText()));assert.deepEqual(errors,[]);
  console.log('PASS: actual status, authenticated vault readiness, pending dedupe, last-good/retry, 320/375/1280 light/dark and 200%, 7 screenshots');
 }finally{await context.close();await browser.close();}
})().catch(error=>{console.error(error);process.exit(1)});
