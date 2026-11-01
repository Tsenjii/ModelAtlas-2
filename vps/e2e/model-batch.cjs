const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const {readDownload}=require('./download-helper.cjs');
const {waitForAsync}=require('./async-wait-helper.cjs');
const assert=require('node:assert/strict'),fs=require('node:fs/promises');
(async()=>{
 const browser=await chromium.launch({headless:true});
 const context=await browser.newContext({viewport:{width:1280,height:1000},reducedMotion:'reduce',httpCredentials:{username:'admin',password:process.env.MODELATLAS_TEST_TOKEN}});
 const page=await context.newPage(),origin=process.argv[2],errors=[];
 page.on('pageerror',e=>errors.push(e.message));
 const fixture=async name=>(await page.request.get(origin+'/__batch_fixture/'+name)).json();
 const dialog=()=>page.locator('#detail'),close=async()=>{await page.locator('#detail-close').click();};
 const state=()=>dialog().getByRole('status');
 const screenshot=async name=>{
  assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'page overflow');
  assert(await dialog().evaluate(n=>n.scrollWidth<=n.clientWidth+1),'dialog overflow');
  await page.screenshot({path:'e2e/screenshots/model-batch-'+name+'.png',animations:'disabled'});
 };
 await fs.mkdir('e2e/screenshots',{recursive:true});
 try{
  await page.goto(origin);await page.getByRole('button',{name:'Models',exact:true}).click();
  await page.locator('#my-models .model-row').nth(2).waitFor();await page.locator('#model-select').click();
  for(const checkbox of await page.locator('#my-models .model-row input[type=checkbox]').all())await checkbox.check();
  await page.locator('#bulk-test').click();await state().filter({hasText:'準備測試 · 0 / 3'}).waitFor();
  assert.equal((await fixture('state')).calls,0,'preview spent inference quota');
  for(const [width,font,scheme]of [[1280,'','light'],[375,'','dark'],[320,'200%','dark']]){
   await page.setViewportSize({width,height:1000});await page.emulateMedia({colorScheme:scheme});await page.evaluate(font=>document.documentElement.style.fontSize=font,font);await screenshot(width+(font?'-large':'')+'-'+scheme);
  }
  await page.setViewportSize({width:1280,height:1000});await page.evaluate(()=>document.documentElement.style.fontSize='');
  await dialog().getByRole('button',{name:'開始測試 3 個模型',exact:true}).click();
  await state().filter({hasText:'測試完成 · 3 / 3'}).waitFor();assert.equal((await fixture('state')).calls,3);
  assert.equal(await page.locator('[data-batch-model-scope]').count(),3);
  const download=page.waitForEvent('download');await dialog().getByRole('button',{name:'匯出測試結果',exact:true}).click();
  const report=JSON.parse(await readDownload(download,'utf8'));
  assert.equal(report.status,'COMPLETED');assert.equal(report.results.length,3);assert(report.results.every(r=>r.outcome==='SUCCESS'&&r.details.sample_id&&r.selection.account_id==='a'&&r.selection.credential_id==='c'));assert(!JSON.stringify(report).includes('top-secret'));
  await screenshot('completed');await close();await page.locator('#model-batch-progress').click();await state().filter({hasText:'測試完成 · 3 / 3'}).waitFor();assert.equal((await fixture('state')).calls,3,'reopening replayed tests');await close();
  await fixture('limited');await page.locator('#bulk-test').click();await dialog().getByRole('button',{name:'開始測試 3 個模型',exact:true}).click();
  await state().filter({hasText:'已暫停：遇到額度、限流或忙碌 · 1 / 3'}).waitFor();assert.equal((await fixture('state')).calls,4);assert((await state().textContent()).includes('2 個尚未測試'));await screenshot('limited');await close();
  await fixture('blocked');await page.locator('#bulk-test').click();await dialog().getByRole('button',{name:'開始測試 3 個模型',exact:true}).click();
  await waitForAsync(page,async()=>(await(await fetch('/__batch_fixture/state')).json()).calls===5);
  await close();await page.locator('#model-batch-progress').click();assert.equal((await fixture('state')).calls,5,'progress view duplicated active request');
  await dialog().getByRole('button',{name:'停止測試',exact:true}).click();await state().filter({hasText:'已停止測試 · 1 / 3'}).waitFor();assert.equal((await fixture('state')).calls,5);
  await page.setViewportSize({width:320,height:1000});await page.evaluate(()=>document.documentElement.style.fontSize='200%');await screenshot('cancelled-320-large');
  assert.deepEqual(errors,[]);console.log('Batch testing: explicit three-scope HTTP, receipts/export, no preview/reopen requests, quota stop, active socket cancellation, retained progress, 320/375/1280 and 200% passed.');
 }catch(e){await page.screenshot({path:'e2e/screenshots/model-batch-failure.png'}).catch(()=>{});throw e;}finally{await browser.close();}
})().catch(e=>{console.error(e);process.exit(1);});
