const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const assert=require('node:assert/strict'),fs=require('node:fs/promises');
(async()=>{
 const browser=await chromium.launch({headless:true}),origin=process.argv[2],hub=process.argv[3];
 const context=await browser.newContext({httpCredentials:{username:'admin',password:process.env.MODELATLAS_TEST_TOKEN},ignoreHTTPSErrors:true,viewport:{width:1280,height:1000},isMobile:true,hasTouch:true,reducedMotion:'reduce'});
 const page=await context.newPage(),center=await context.newPage(),errors=[];
 for(const p of [page,center])p.on('pageerror',e=>errors.push(e.message));
 const data=async p=>{await p.locator('#settings-toggle').click();await p.locator('#settings-data-tab').click();await p.locator('#workspace-sync-status').filter({hasText:/此裝置可作同步中心|已連接同步中心/}).waitFor();};
 const state=async()=>(await page.request.get(origin+'/__workspace_fixture/state')).json();
 const shot=async(name,p=page)=>{assert(await p.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),name+' overflow');await p.screenshot({path:'e2e/screenshots/workspace-sync-'+name+'.png',animations:'disabled',fullPage:false});};
 await fs.mkdir('e2e/screenshots',{recursive:true});
 try{
  await page.goto(origin);await page.waitForFunction(()=>modelPickerReady);await data(page);await page.locator('#workspace-connect-panel>summary').click();
  for(const width of [320,375])for(const theme of ['light','dark']){
   await page.setViewportSize({width,height:900});await page.evaluate(theme=>{document.documentElement.dataset.theme=theme;document.documentElement.style.fontSize='200%';},theme);
   await page.locator('#workspace-connect-form input[name=url]').scrollIntoViewIfNeeded();await shot(width+'-'+theme+'-large');
   const form=page.locator('#workspace-connect-form');assert(await form.evaluate(n=>[...n.querySelectorAll('input,button')].every(e=>{const r=e.getBoundingClientRect();return r.width<=n.clientWidth+1&&r.height>=48;})));
  }
  await page.setViewportSize({width:375,height:900});await page.evaluate(()=>document.documentElement.style.fontSize='');
  await center.goto(hub);await center.waitForFunction(()=>modelPickerReady);await data(center);await center.locator('#workspace-pair-panel>summary').click();await center.locator('#workspace-pair-secrets').check();await center.locator('#workspace-pair-create').click();await center.locator('#workspace-pair-result').waitFor();
  const code=await center.locator('#workspace-pair-code').inputValue();assert.equal(code.length,43);
  const form=page.locator('#workspace-connect-form');await form.locator('input[name=url]').fill(hub);await form.locator('input[name=label]').fill('手機測試工作區');await form.locator('input[name=code]').fill(code);await form.getByRole('button',{name:'配對並開始同步',exact:true}).click();
  await page.locator('#workspace-sync-status').filter({hasText:'已連接同步中心'}).waitFor();await page.waitForFunction(()=>!workspaceSyncLoading&&workspaceSyncState?.pending===0);assert.equal((await state()).models,1);assert.equal((await state()).samples,0);
  assert(!(await page.content()).includes('top-secret-example-key'));await shot('connected-375');
  await page.request.post(origin+'/__workspace_fixture/conflict');await page.locator('#workspace-sync-now').click();await page.locator('#workspace-conflict-list').getByRole('button').waitFor();assert.equal((await state()).local,'手機離線名稱');assert.equal((await state()).remote,'中心新名稱');
  await page.locator('#workspace-conflict-list').getByRole('button').click();await page.locator('#detail-content').getByText('手機離線名稱',{exact:true}).waitFor();await page.locator('#detail-content').getByText('中心新名稱',{exact:true}).waitFor();await shot('conflict-375');
  await page.locator('#detail-content').getByRole('button',{name:'採用同步中心',exact:true}).click();await page.locator('#detail').waitFor({state:'hidden'});await page.waitForFunction(()=>workspaceSyncState?.conflict_count===0&&!workspaceSyncLoading);assert.equal((await state()).local,'中心新名稱');
  await center.locator('#workspace-sync-refresh').click();await center.locator('#workspace-device-list').locator('..').locator('summary').click();await center.getByText('手機測試工作區',{exact:true}).waitFor();center.once('dialog',d=>d.accept());await center.getByRole('button',{name:'撤銷這台裝置',exact:true}).click();await center.getByText(/已撤銷/).waitFor();
  await page.locator('#workspace-sync-now').click();await page.locator('#workspace-sync-status').filter({hasText:/權限已撤銷|配對碼已過期/}).waitFor();assert.equal((await state()).models,1);assert.equal((await state()).samples,0);assert.deepEqual(errors,[]);await shot('revoked-data-retained-375');
  console.log('Actual TLS workspace pairing, destination vault re-encryption, scoped model propagation, offline conflict preview/remote resolution, token revocation and retained local data; no inference; mobile 320/375, both themes and 200% controls passed.');
 }catch(e){await page.screenshot({path:'e2e/screenshots/workspace-sync-failure.png',fullPage:false}).catch(()=>{});throw e;}finally{await browser.close();}
})().catch(e=>{console.error(e);process.exit(1);});
