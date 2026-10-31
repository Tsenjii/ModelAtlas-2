const {waitForAsync}=require('./async-wait-helper.cjs');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const assert=require('node:assert/strict'),fs=require('node:fs/promises');
(async()=>{
 const browser=await chromium.launch({headless:true}),origin=process.argv[2];
 const context=await browser.newContext({httpCredentials:{username:'admin',password:process.env.MODELATLAS_TEST_TOKEN},viewport:{width:375,height:940},isMobile:true,hasTouch:true,reducedMotion:'reduce'});
 const page=await context.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message));
 const state=async()=>(await page.request.get(origin+'/__chunks/state')).json();
 const load=async()=>{await page.goto(origin);await page.waitForFunction(()=>modelPickerReady);await page.locator('#settings-toggle').click();await page.locator('#settings-data-tab').click();await page.waitForFunction(()=>workspaceSyncState?.transfer&&!workspaceSyncLoading);};
 const shot=async name=>{assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),name+' overflow');await page.screenshot({path:'e2e/screenshots/workspace-chunks-'+name+'.png',fullPage:false,animations:'disabled'});};
 await fs.mkdir('e2e/screenshots',{recursive:true});
 try {
  await load();const initial=(await state()).id;assert.equal(await page.locator('#workspace-sync-now').textContent(),'恢復同步');
  await page.locator('#workspace-transfer-progress').scrollIntoViewIfNeeded();await shot('ready-375-light');
  await page.locator('#workspace-sync-now').click();
  await waitForAsync(page,async()=>(await(await fetch('/__chunks/state')).json()).held);
  await page.waitForFunction(()=>workspaceSyncRunning&&workspaceSyncLoading);assert(await page.locator('#workspace-sync-now').isDisabled());
  await page.evaluate(()=>document.documentElement.dataset.theme='dark');await page.locator('#workspace-sync-pause').scrollIntoViewIfNeeded();await shot('uploading-375-dark');
  await page.getByRole('button',{name:'暫停同步',exact:true}).click();
  await page.waitForFunction(()=>workspaceSyncState?.peer?.enabled===false);
  await page.request.post(origin+'/__chunks/release');
  await page.waitForFunction(()=>!workspaceSyncRunning&&!workspaceSyncLoading);
  assert.equal((await state()).events,0);assert.equal((await state()).models,1);assert.equal((await state()).samples,0);
  await load();assert.equal(await page.evaluate(()=>workspaceSyncState.transfer.id),initial);assert.equal(await page.evaluate(()=>workspaceSyncState.transfer.progress_bytes),256*1024);
  assert.match(await page.locator('#workspace-transfer-progress').textContent(),/傳輸進度已保存/);
  assert.doesNotMatch(await page.locator('#workspace-transfer-progress').textContent(),/正在上傳|正在接收/);
  for(const width of [320,375])for(const theme of ['light','dark']){
   await page.setViewportSize({width,height:940});await page.evaluate(theme=>{document.documentElement.dataset.theme=theme;document.documentElement.style.fontSize='200%';},theme);
   await page.locator('#workspace-transfer-progress').scrollIntoViewIfNeeded();await shot('paused-'+width+'-'+theme+'-large');
   assert(await page.locator('#workspace-sync .actions button:visible').evaluateAll(nodes=>nodes.every(n=>n.getBoundingClientRect().height>=48)));
  }
  await page.setViewportSize({width:375,height:940});await page.evaluate(()=>document.documentElement.style.fontSize='');
  await page.getByRole('button',{name:'恢復同步',exact:true}).click();
  await page.waitForFunction(()=>!workspaceSyncRunning&&!workspaceSyncLoading&&!workspaceSyncState?.transfer);
  const final=await state();assert.equal(final.id,initial);assert.equal(final.finish,1);assert.equal(final.events,1);assert.equal(final.local_hash,final.expected_hash);assert.equal(final.remote_hash,final.expected_hash);assert.equal(final.models,1);assert.equal(final.samples,0);
  assert(!(await page.content()).includes('top-secret-example-key'));assert.deepEqual(errors,[]);
  await page.locator('#workspace-sync-now').scrollIntoViewIfNeeded();await shot('complete-375-dark');
  await page.setViewportSize({width:1280,height:960});await page.evaluate(()=>document.documentElement.dataset.theme='light');await page.locator('#workspace-sync-title').scrollIntoViewIfNeeded();await shot('complete-1280-light');
  console.log('Real queued transport resumed through Settings, pause survived reload, 256 KiB checkpoint retained, exact permanent history committed once, no inference or model removal; 8 responsive views.');
 }catch(e){await page.screenshot({path:'e2e/screenshots/workspace-chunks-failure.png',fullPage:false}).catch(()=>{});throw e;}finally{await browser.close();}
})();
