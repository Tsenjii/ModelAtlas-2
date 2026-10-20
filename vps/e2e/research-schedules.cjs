const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const assert=require('node:assert/strict'),fs=require('node:fs/promises');
(async()=>{
 const browser=await chromium.launch({headless:true}),origin=process.argv[2];
 const context=await browser.newContext({httpCredentials:{username:'admin',password:process.env.MODELATLAS_TEST_TOKEN},viewport:{width:375,height:1000},reducedMotion:'reduce'});
 const page=await context.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message));await fs.mkdir('e2e/screenshots',{recursive:true});
 try{
  await page.goto(origin);await page.waitForFunction(()=>modelPickerReady);await page.getByRole('button',{name:'Models',exact:true}).click();await page.getByRole('tab',{name:'我的模型',exact:true}).click();await page.getByRole('tab',{name:'全球研究',exact:true}).click();
  await page.waitForFunction(()=>researchScheduleState!==null&&researchSources.length===2);
  const surface=page.locator('#research-schedule-card'),form=page.locator('#research-schedule-form');
  await surface.getByRole('button',{name:'新增排程',exact:true}).click();await form.getByLabel('排程名稱',{exact:true}).fill('每日公開探索');await form.getByLabel('間隔（分鐘）',{exact:true}).fill('15');await form.getByLabel('每輪 HTTP 上限',{exact:true}).fill('4');await form.getByLabel('每日 HTTP 上限',{exact:true}).fill('1');await form.getByLabel('排程公開來源',{exact:true}).selectOption(['browser-one','browser-two']);
  let entered,release;const arrived=new Promise(r=>entered=r),hold=new Promise(r=>release=r);let saves=0,failRead=false;
  await page.route('**/api/v1/research/schedules',async route=>{
   if(route.request().method()==='POST'){saves++;entered();await hold;const response=await route.fetch();assert.equal(response.status(),200);failRead=true;await route.fulfill({response});}
   else if(failRead){failRead=false;await route.fulfill({status:503,contentType:'text/plain',body:'暫時無法讀取排程'});}else await route.continue();
  });
  await form.getByRole('button',{name:'保存排程',exact:true}).click();await arrived;assert(await form.getByRole('button',{name:'保存排程',exact:true}).isDisabled());
  await page.evaluate(()=>document.querySelector('#research-schedule-form').dispatchEvent(new Event('submit',{cancelable:true,bubbles:true})));assert.equal(saves,1);release();
  await page.locator('#research-schedule-problem').filter({hasText:'保留上次排程資料'}).waitFor();assert.equal(await page.locator('#research-candidates .item-button').count(),0);assert(!await page.locator('#research-schedule-editor').evaluate(n=>n.open),'known commit did not close saved form');
  await surface.getByRole('button',{name:'更新排程',exact:true}).click();const card=page.locator('.research-schedule-row').filter({has:page.getByRole('heading',{name:'每日公開探索',exact:true})});await card.getByText('已停用',{exact:true}).waitFor();assert.equal(saves,1);await page.unroute('**/api/v1/research/schedules');
  await card.getByRole('button',{name:'啟用排程',exact:true}).click();await card.getByText('每日上限 · 等待重設',{exact:true}).waitFor();await page.waitForFunction(()=>researchCandidates.length===1);assert.match(await card.innerText(),/今日 1 \/ 1/);
  // A polling refresh never replaces an unfinished form or its source selection.
  await surface.getByRole('button',{name:'新增排程',exact:true}).click();await form.getByLabel('排程名稱',{exact:true}).fill('保留我的草稿');await form.getByLabel('間隔（分鐘）',{exact:true}).fill('35');await form.getByLabel('排程公開來源',{exact:true}).selectOption('browser-two');await surface.getByRole('button',{name:'更新排程',exact:true}).click();await page.waitForFunction(()=>!researchScheduleLoading);
  assert.equal(await form.getByLabel('排程名稱',{exact:true}).inputValue(),'保留我的草稿');assert.equal(await form.getByLabel('間隔（分鐘）',{exact:true}).inputValue(),'35');assert.deepEqual(await form.getByLabel('排程公開來源',{exact:true}).evaluate(n=>[...n.selectedOptions].map(o=>o.value)),['browser-two']);await form.getByRole('button',{name:'放棄編輯',exact:true}).click();
  await card.getByRole('button',{name:'排程紀錄',exact:true}).click();await page.locator('#detail').getByRole('button',{name:'查看本輪工作',exact:true}).click();await page.locator('#detail-content details').filter({hasText:'SOURCE · BUDGET_EXHAUSTED'}).waitFor();await page.locator('#detail-content').getByText('本輪結果',{exact:true}).click();assert.match(await page.locator('#detail-content').innerText(),/scanned_count/);await page.screenshot({path:'e2e/screenshots/research-schedule-history-375.png',animations:'disabled'});await page.locator('#detail-close').click();
  await card.getByRole('button',{name:'停用排程',exact:true}).click();await card.getByText('已停用',{exact:true}).waitFor();await card.getByRole('button',{name:'編輯排程',exact:true}).click();await form.getByLabel('每輪 HTTP 上限',{exact:true}).fill('6');await form.getByLabel('每日 HTTP 上限',{exact:true}).fill('2');await form.getByRole('button',{name:'保存排程修改',exact:true}).click();await page.waitForFunction(()=>!researchScheduleWriting);await card.getByRole('button',{name:'啟用排程',exact:true}).click();await card.getByText('每日上限 · 等待重設',{exact:true}).waitFor();assert.match(await card.innerText(),/今日 2 \/ 2/);await card.getByRole('button',{name:'停用排程',exact:true}).click();await card.getByText('已停用',{exact:true}).waitFor();
  for(const width of [320,375,1280])for(const theme of ['light','dark']){
   await page.setViewportSize({width,height:1000});await page.evaluate(theme=>{document.documentElement.dataset.theme=theme;document.documentElement.style.fontSize='100%';},theme);await surface.scrollIntoViewIfNeeded();
   assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),`${width}/${theme} page overflow`);assert(await surface.evaluate(n=>n.scrollWidth<=n.clientWidth),`${width}/${theme} schedule overflow`);
   for(const button of await surface.locator('button:visible').all()){const box=await button.boundingBox();assert(box.height>=48,'48px schedule target');}
   await surface.screenshot({path:`e2e/screenshots/research-schedules-${width}-${theme}.png`,animations:'disabled'});
  }
  await page.setViewportSize({width:375,height:1000});await page.evaluate(()=>document.documentElement.style.fontSize='200%');await surface.scrollIntoViewIfNeeded();assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'schedule 200% overflow');await surface.screenshot({path:'e2e/screenshots/research-schedules-375-large.png',animations:'disabled'});
  assert.deepEqual(errors,[]);console.log('PASS: actual UI → disabled save → deduped commit / failed refresh → explicit activation → real TLS worker / daily cap → history / draft retention → disable / edit preserving daily use. 320/375/1280 light/dark, 200%, 8 screenshots.');
 }catch(error){await page.screenshot({path:'e2e/screenshots/research-schedules-failure.png',fullPage:true}).catch(()=>{});throw error;}finally{await context.close();await browser.close();}
})().catch(error=>{console.error(error);process.exit(1)});
