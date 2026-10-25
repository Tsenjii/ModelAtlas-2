const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const assert=require('node:assert/strict'),fs=require('node:fs/promises');
const {chooseTarget}=require('./model-picker-helper.cjs');
(async()=>{
 const browser=await chromium.launch({headless:true}),origin=process.argv[2],legacy=process.argv[3];
 const context=await browser.newContext({viewport:{width:375,height:900},reducedMotion:'reduce',httpCredentials:{username:'admin',password:process.env.MODELATLAS_TEST_TOKEN}});
 const page=await context.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message));
 const scope={provider_id:'builtin-kilo-anonymous',account_id:'builtin-kilo-anonymous',credential_id:'builtin-kilo-anonymous',model_id:'nvidia/nemotron-3.5-lightning:free',group:'',endpoint:'CHAT_COMPLETIONS'};
 const state=async()=>await(await context.request.get(origin+'/test/kilo/state')).json();
 const shot=async(name,locator=page)=>{assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'horizontal overflow: '+name);await locator.screenshot({path:'e2e/screenshots/factory-kilo-'+name+'.png',fullPage:true,animations:'disabled'});};
 const idle=async()=>page.waitForFunction(()=>workspaceRefreshTask===null);
 await fs.mkdir('e2e/screenshots',{recursive:true});
 try{
  await page.goto(origin);await page.waitForFunction(()=>document.querySelector('#target').options.length===15);await idle();
  assert.deepEqual(await state(),{catalogs:0,chats:0,installs:0,samples:0,messages:0,members:15});assert.equal(await page.locator('#target').inputValue(),JSON.stringify(scope));
  await chooseTarget(page,JSON.stringify(scope));await idle();await page.locator('#factory-kilo-notice').waitFor();
  await page.locator('#factory-kilo-notice summary').click();await page.locator('#factory-kilo-notice p').waitFor();assert((await page.locator('#factory-kilo-notice p').textContent()).includes('請勿輸入個人或機密資料'));await page.locator('#factory-kilo-notice summary').click();
  for(const width of [320,375,1280])for(const theme of ['light','dark']){await page.setViewportSize({width,height:900});await page.emulateMedia({colorScheme:theme});await shot('ready-'+width+'-'+theme);}
  await page.setViewportSize({width:375,height:900});await page.emulateMedia({colorScheme:'light'});
  await page.locator('#new-session').click();await page.locator('#send:enabled').waitFor();await page.locator('#prompt').fill('僅有合成資料的出廠對話');await page.locator('#send').click();
  await page.getByText('KILO_BROWSER_OK',{exact:true}).waitFor();await page.locator('#send:enabled').waitFor();await page.locator('#notice.notice-visible').waitFor({state:'hidden'});await shot('chat-375-light');
  assert.deepEqual(await state(),{catalogs:1,chats:1,installs:0,samples:1,messages:2,members:15});
  await context.request.post(origin+'/test/kilo/paid');await page.locator('#prompt').fill('價格改變後保留的草稿');await page.locator('#send').click();await page.locator('#send:enabled').waitFor();
  await page.getByText(/這個Kilo模型目前未確認為免費可用/).waitFor();assert.equal(await page.locator('#prompt').inputValue(),'價格改變後保留的草稿');assert(await page.locator('#factory-kilo-problem').isVisible());
  assert.deepEqual(await state(),{catalogs:2,chats:1,installs:0,samples:1,messages:2,members:15});
  const other=await page.locator('#target option').evaluateAll(nodes=>nodes.find(n=>!n.value.includes('nvidia/nemotron-3.5-lightning:free')).value);await chooseTarget(page,other);await idle();assert(await page.locator('#factory-kilo-problem').isHidden());await chooseTarget(page,JSON.stringify(scope));await idle();assert(await page.locator('#factory-kilo-problem').isVisible());assert.equal(await page.locator('#prompt').inputValue(),'價格改變後保留的草稿');
  await page.emulateMedia({colorScheme:'dark'});await shot('guard-375-dark');await page.evaluate(()=>document.documentElement.style.fontSize='200%');await shot('guard-375-large-dark');await page.evaluate(()=>document.documentElement.style.fontSize='100%');
  await page.locator('#settings-toggle').click();await page.locator('#settings-general-tab').click();const card=page.locator('#factory-kilo-card');await card.getByRole('button',{name:'已加入Kilo預設模型',exact:true}).waitFor();
  assert(await card.getByRole('button',{name:'已加入Kilo預設模型',exact:true}).isDisabled());await page.emulateMedia({colorScheme:'light'});await shot('installed-settings-375-light',card);
  const fail=async route=>route.fulfill({status:503,body:'fixture read unavailable'});await page.route('**/api/v1/defaults',fail);await card.getByRole('button',{name:'重新讀取預設資料',exact:true}).click();await card.getByText('請先讀取已保存狀態，避免重複加入。',{exact:true}).waitFor();
  assert((await card.textContent()).includes('15 個預載模型'));await page.setViewportSize({width:320,height:900});await page.emulateMedia({colorScheme:'dark'});await shot('settings-read-failed-320-dark',card);await page.unroute('**/api/v1/defaults',fail);await card.getByRole('button',{name:'重新讀取預設資料',exact:true}).click();await page.waitForFunction(()=>factoryKiloReadRequired===false);
  const removed=await context.request.delete(origin+'/api/v1/my-models',{data:scope});assert.equal(removed.status(),200);await page.reload();await page.waitForFunction(()=>document.querySelector('#target').options.length===14);assert.equal((await state()).members,14);
  const legacyContext=await browser.newContext({viewport:{width:375,height:900},reducedMotion:'reduce',httpCredentials:{username:'admin',password:process.env.MODELATLAS_LEGACY_TEST_TOKEN}});
  const old=await legacyContext.newPage();old.on('pageerror',e=>errors.push(e.message));await old.goto(legacy);await old.locator('#settings-toggle').click();await old.locator('#settings-general-tab').click();const oldCard=old.locator('#factory-kilo-card');
  await oldCard.getByRole('button',{name:'加入Kilo免費預設',exact:true}).waitFor({state:'visible'});await old.waitForFunction(()=>factoryKiloInfo!==null&&!factoryKiloBusy);assert(!(await oldCard.getByRole('button',{name:'加入Kilo免費預設',exact:true}).isDisabled()));
  await oldCard.screenshot({path:'e2e/screenshots/factory-kilo-existing-before-375-light.png',animations:'disabled'});
  await old.route('**/api/v1/defaults/kilo',async route=>{await route.fetch();await route.fulfill({status:503,body:'fixture lost acknowledgment after commit'});});
  await oldCard.getByRole('button',{name:'加入Kilo免費預設',exact:true}).click();await oldCard.getByText('請先讀取已保存狀態，避免重複加入。',{exact:true}).waitFor();assert(await oldCard.getByRole('button',{name:'加入Kilo免費預設',exact:true}).isDisabled());
  await old.emulateMedia({colorScheme:'dark'});await oldCard.screenshot({path:'e2e/screenshots/factory-kilo-unconfirmed-375-dark.png',animations:'disabled'});
  await oldCard.getByRole('button',{name:'重新讀取預設資料',exact:true}).click();await oldCard.getByRole('button',{name:'已加入Kilo預設模型',exact:true}).waitFor();assert(await oldCard.getByRole('button',{name:'已加入Kilo預設模型',exact:true}).isDisabled());
  await oldCard.screenshot({path:'e2e/screenshots/factory-kilo-recovered-375-dark.png',animations:'disabled'});assert.equal((await state()).installs,1);
  await old.waitForFunction(()=>workspaceRefreshTask===null&&providers.length===2&&myModels.length===15);await old.locator('#settings-toggle').click();await old.getByRole('button',{name:'Models',exact:true}).click();
  assert(!(await old.locator('#my-models').textContent()).includes('FREE NOW'));await old.setViewportSize({width:1280,height:900});await old.emulateMedia({colorScheme:'light'});assert(await old.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));await old.screenshot({path:'e2e/screenshots/factory-kilo-models-1280-light.png',animations:'disabled',fullPage:true});
  assert.deepEqual(errors,[]);await legacyContext.close();console.log('PASS: fresh offline 15-model picker, exact anonymous catalog + TLS chat, paid model guard preserves draft/history, installed and failed-read last-good, removal/reload, legacy explicit atomic addition and lost-ack read recovery without repost. 320/375/1280, light/dark, 200%, 15 screenshots.');
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exit(1);});
