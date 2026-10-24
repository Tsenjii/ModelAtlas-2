const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const assert=require('node:assert/strict'),fs=require('node:fs/promises');
const {chooseTarget}=require('./model-picker-helper.cjs');

(async()=>{
 const browser=await chromium.launch({headless:true});
 const context=await browser.newContext({viewport:{width:375,height:812},reducedMotion:'reduce',httpCredentials:{username:'admin',password:process.env.MODELATLAS_TEST_TOKEN}});
 const page=await context.newPage(),errors=[],external=[],origin=process.argv[2];
 page.on('pageerror',e=>errors.push(e.message));
 page.on('request',r=>{if(!r.url().startsWith(origin))external.push(r.url());});
 const dialog=page.locator('#model-picker'),search=page.locator('#model-picker-search'),rows=page.locator('#model-picker-results [data-scope-value]');
 const scope=(provider='p',account='a',group='',endpoint='CHAT_COMPLETIONS')=>JSON.stringify({provider_id:provider,account_id:account,credential_id:'c',model_id:'m',group,endpoint});
 const original=scope(),research=scope('p','b','research-team');
 const count=async expected=>page.waitForFunction(n=>document.querySelectorAll('#model-picker-results [data-scope-value]').length===n,expected);
 const calls=async()=>((await(await page.request.get(origin+'/picker-fixture/state')).json()).calls);
 const shot=async name=>{
  assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'page horizontal overflow: '+name);
  assert(await dialog.evaluate(d=>d.scrollWidth<=d.clientWidth+1),'dialog horizontal overflow: '+name);
  await page.locator('#model-picker-use').scrollIntoViewIfNeeded();
  assert(await page.locator('#model-picker-use').evaluate(n=>{const r=n.getBoundingClientRect();return r.top>=0&&r.bottom<=innerHeight;}),'confirmation cannot be reached: '+name);
  await page.screenshot({path:'e2e/screenshots/picker-vps-'+name+'.png',fullPage:true,animations:'disabled'});
 };
 await fs.mkdir('e2e/screenshots',{recursive:true});
 try{
  await page.goto(origin);
  await page.waitForFunction(async()=>(await(await fetch('/picker-fixture/initial-state')).json()).ready);
  assert(await page.locator('#model-picker-open').isDisabled(),'picker opened before its real scope read finished');
  assert.equal(await page.locator('#model-picker-title').textContent(),'正在讀取模型…');await page.keyboard.press('Meta+k');assert(await dialog.isHidden());
  await page.request.get(origin+'/picker-fixture/release-models');
  await page.waitForFunction(()=>document.querySelector('#target').options.length===114);
  await chooseTarget(page,original);
  await page.locator('#new-session').click();await page.locator('#send:enabled').waitFor();
  const previous=await page.locator('#session').inputValue();assert(previous);
  const prompt=page.locator('#prompt');await prompt.fill('選模期間保留的草稿');
  await page.locator('#pdf-picker').setInputFiles({name:'notes.pdf',mimeType:'application/pdf',buffer:Buffer.from('%PDF-1.4\n%%EOF\n')});
  await page.locator('#attachments').getByText('notes.pdf',{exact:true}).waitFor();
  await page.keyboard.press('Meta+k');await dialog.waitFor();await count(100);
  assert.equal(await search.evaluate(n=>n===document.activeElement),true);
  assert.equal(await page.locator('#model-picker-count').textContent(),'114 / 114 個連線');
  await page.locator('#model-picker-more').click();await count(114);
  const all=await rows.evaluateAll(ns=>ns.map(n=>JSON.parse(n.dataset.scopeValue)));
  assert(!all.some(s=>['hidden','catalog-only'].includes(s.model_id)));
  assert.equal(all.filter(s=>s.model_id==='m').length,4,'same model/account/key/endpoint scopes were collapsed');
  await search.fill('ＣＡＦＥ 研究 research-team');await count(1);
  assert.equal(await rows.first().getAttribute('data-scope-value'),research);
  assert((await rows.first().innerText()).includes('同名 Key'));
  await page.locator('#model-picker-reset').click();await count(100);
  assert.deepEqual(await page.locator('#model-picker-provider option').allTextContents(),['所有供應商','Café Lab · p','Café Lab · q']);
  await page.locator('#model-picker-provider').selectOption('q');await count(1);
  assert.equal(JSON.parse(await rows.first().getAttribute('data-scope-value')).provider_id,'q');
  await page.locator('#model-picker-reset').click();await page.locator('#model-picker-favorites').check();await count(1);
  assert.equal(JSON.parse(await rows.first().getAttribute('data-scope-value')).endpoint,'secondary-chat');
  await page.locator('#model-picker-reset').click();await count(100);
  for(const query of ['picker-b-key','private-description-not-searchable','https://']){
   await search.fill(query);await count(0);assert(await page.locator('#model-picker-use').isDisabled());
  }
  await search.fill('程式模型 對話');await count(3);
  const first=await search.getAttribute('aria-activedescendant');
  await search.press('ArrowUp');assert.equal(await search.getAttribute('aria-activedescendant'),first,'ArrowUp wrapped past the first result');
  await search.press('ArrowDown');assert.notEqual(await search.getAttribute('aria-activedescendant'),first);
  assert.equal(await page.locator('#target').inputValue(),original);
  await search.dispatchEvent('compositionstart');await search.dispatchEvent('keydown',{key:'Enter',isComposing:true});
  assert(await dialog.isVisible(),'IME Enter chose a model');
  await dialog.dispatchEvent('cancel',{cancelable:true});assert(await dialog.isVisible(),'IME cancel closed model picker');
  await search.dispatchEvent('compositionend');
  await page.locator('#model-picker-close').click();
  assert.equal(await prompt.inputValue(),'選模期間保留的草稿');
  assert.equal(await page.locator('#attachments .attachment').count(),1);
  assert.equal(await page.locator('#session').inputValue(),previous);assert.equal(await calls(),0);
  await page.locator('#model-picker-open').click();await search.fill('Atlas');await count(4);
  for(const colorScheme of ['light','dark']){
   await page.emulateMedia({colorScheme});
   for(const width of [375,1024]){await page.setViewportSize({width,height:900});await shot(width+'-'+colorScheme);}
  }
  await page.setViewportSize({width:375,height:812});await page.evaluate(()=>document.documentElement.style.fontSize='200%');
  await search.fill('研究');await count(1);await shot('375-large-dark');
  await search.fill('找不到的連線');await count(0);await shot('empty-375-large-dark');
  await page.evaluate(()=>document.documentElement.style.fontSize='100%');
  await search.fill('ＣＡＦＥ 研究 research-team');await count(1);await search.press('Enter');await dialog.waitFor({state:'hidden'});
  await page.waitForFunction(v=>document.querySelector('#target').value===v,research);
  assert.equal(await prompt.inputValue(),'');assert.equal(await page.locator('#attachments .attachment').count(),0);
  const sessions=await(await page.request.get(origin+'/api/v1/sessions')).json();
  assert(sessions.some(s=>s.id===previous),'confirmed selection deleted the earlier conversation');
  assert.equal(await calls(),0,'browsing or selection triggered inference');
  await page.locator('#new-session').click();await page.locator('#send:enabled').waitFor();
  await prompt.fill('驗證完整研究帳戶範圍');await page.locator('#send').click();
  await page.getByText('Scoped picker reply',{exact:true}).waitFor();
  assert(await page.locator('#model-picker-open').isDisabled());
  await page.keyboard.press('Meta+k');assert(await dialog.isHidden(),'busy shortcut changed scope');
  assert.equal(await page.locator('#target').inputValue(),research);
  await page.request.post(origin+'/picker-fixture/release');await page.locator('#send:enabled').waitFor();
  assert.equal(await calls(),1);assert.deepEqual(errors,[]);assert.deepEqual(external,[]);
  console.log('Model picker Browser: exact 4 scopes + 110 paginated rows; full-width/accent/all-term/alias/operation search; duplicate provider IDs, favorite/reset/empty, keyboard boundaries, IME, unchanged draft/attachment until confirmation; actual TLS inference in selected account/group/key/endpoint; busy guard; 375/1024 light/dark/200% passed.');
 }catch(e){await page.screenshot({path:'e2e/screenshots/picker-vps-failure.png',fullPage:true}).catch(()=>{});throw e;}finally{await browser.close();}
})().catch(e=>{console.error(e);process.exit(1);});
