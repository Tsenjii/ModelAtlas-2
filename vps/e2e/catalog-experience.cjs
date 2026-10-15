const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const assert=require('node:assert/strict'),fs=require('node:fs/promises');
(async()=>{
 const browser=await chromium.launch({headless:true});
 const context=await browser.newContext({viewport:{width:375,height:812},reducedMotion:'reduce',httpCredentials:{username:'admin',password:process.env.MODELATLAS_TEST_TOKEN}});
 const page=await context.newPage(),errors=[],external=[];
 page.on('pageerror',e=>errors.push(e.message));page.on('request',r=>{if(!r.url().startsWith(process.argv[2]+'/'))external.push(r.url());});
 const fixture=path=>page.evaluate(async path=>(await fetch('/__catalog_fixture/'+path)).json(),path);
 const close=()=>page.getByRole('button',{name:'關閉詳情',exact:true}).click();
 const search=text=>page.locator('#catalog-search').fill(text);
 const row=name=>page.locator('#catalog .item-button').filter({hasText:name});
 const open=name=>row(name).click();
 const chooseB=()=>page.getByLabel('使用的 Key',{exact:true}).selectOption(JSON.stringify(['p','a','second']));
 const add=()=>page.locator('#detail').getByRole('button',{name:'加入 My Models',exact:true});
 const shot=async name=>{
  assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),name+' horizontal overflow');
  if(await page.locator('#detail').isVisible())assert(await page.locator('#detail').evaluate(n=>n.scrollWidth<=n.clientWidth),name+' dialog overflow');
  await page.screenshot({path:'e2e/screenshots/catalog-'+name+'.png',fullPage:false,animations:'disabled'});
 };
 await fs.mkdir('e2e/screenshots',{recursive:true});
 try{
  await page.goto(process.argv[2]);await page.waitForFunction(()=>document.querySelector('#target').options.length===1);
  await page.locator('#prompt').fill('Catalog 探索不能清掉聊天草稿');
  await page.getByRole('button',{name:'Models',exact:true}).click();await page.locator('#model-search').fill('保留我的模型篩選');
  await page.getByRole('tab',{name:'探索 Catalog',exact:true}).click();
  assert.equal(await page.locator('#catalog .item-button').count(),100);
  assert.equal(await page.locator('#catalog-result-count').textContent(),'顯示 100 / 108 個模型');
  await page.locator('#catalog-more').click();assert.equal(await page.locator('#catalog .item-button').count(),108);
  assert(await page.locator('#catalog-more').isHidden());
  for(const [term,title] of [['AuroraChat','日常助手'],['common-chat','日常助手'],['NebulaCodr','程式夥伴'],['AtlasLab','程式夥伴']]){await search(term);assert.equal(await page.locator('#catalog .item-button').count(),1,term);assert((await page.locator('#catalog').textContent()).includes(title));}
  await search('尚未設定 Key');assert.equal(await page.locator('#catalog .item-button').count(),1);assert(!(await page.locator('#catalog').textContent()).includes('已加入'));
  await page.locator('#catalog-reset').click();assert.equal(await page.locator('#catalog .item-button').count(),100);
  await page.locator('#catalog-provider').selectOption('p');assert.equal(await page.locator('#catalog-result-count').textContent(),'顯示 100 / 106 個模型');
  await page.locator('.catalog-filters > summary').click();await page.locator('#catalog-category').selectOption('CODING');assert.equal(await page.locator('#catalog .item-button').count(),1);
  await page.locator('#catalog-category').selectOption('');await page.locator('#catalog-capability').selectOption('VISION');assert.equal(await page.locator('#catalog .item-button').count(),1);assert((await row('日常助手').textContent()).includes('已加入 1 個連線'));
  assert(!(await page.locator('#catalog').textContent()).includes('FREE'));
  await page.locator('#catalog-capability').selectOption('');await page.locator('#catalog-membership').selectOption('joined');assert.equal(await page.locator('#catalog .item-button').count(),2);
  await shot('joined-375-light');
  await open('日常助手');assert(await page.getByRole('button',{name:'查看已加入的連線',exact:true}).isVisible());
  await page.getByRole('button',{name:'查看已加入的連線',exact:true}).click();await page.locator('#detail-model-settings-tab').waitFor();await close();
  await open('封存模型');assert((await page.locator('#detail').textContent()).includes('已封存'));await page.getByRole('button',{name:'查看已加入的連線',exact:true}).click();await page.locator('#detail-model-settings-tab').waitFor();await close();
  assert.equal((await fixture('state')).writes,0,'browsing/duplicate/archived lookup wrote membership');
  await page.locator('#catalog-reset').click();await search('不可能存在的型錄結果');assert.equal(await page.locator('#catalog .item-button').count(),0);await page.getByRole('button',{name:'清除 Catalog 篩選',exact:true}).last().click();
  for(const title of ['尚未設定 Key','停用連線']){
   await search(title);await open(title);assert(await add().isDisabled());await shot(title==='停用連線'?'disabled-key-375-light':'missing-key-375-light');
   await page.getByRole('button',{name:'新增或啟用 Key',exact:true}).click();assert.equal(await page.locator('#detail-provider-keys-tab').getAttribute('aria-selected'),'true');await close();
  }
  await search('NebulaCoder');await open('程式夥伴');assert(!(await page.getByLabel('使用的 Key',{exact:true}).textContent()).includes('已停用 Key'));
  await chooseB();assert(await add().isDisabled());assert.equal(await page.getByLabel('端點',{exact:true}).locator('option').count(),0);
  await page.getByRole('button',{name:'設定對應端點',exact:true}).click();assert.equal(await page.locator('#detail-provider-endpoints-tab').getAttribute('aria-selected'),'true');
  await page.getByText('新增獨立端點',{exact:true}).click();await page.getByLabel('端點 URL',{exact:true}).fill(process.argv[3]);await page.getByLabel('端點類型',{exact:true}).selectOption('CHAT_COMPLETIONS');
  const created=page.waitForResponse(r=>r.url().endsWith('/api/v1/endpoints')&&r.request().method()==='POST');await page.getByRole('button',{name:'新增端點',exact:true}).click();const endpoint=await (await created).json();
  await page.getByText('已新增獨立端點；舊對話端點不變',{exact:true}).waitFor();await close();
  await open('程式夥伴');await chooseB();assert.equal(await page.getByLabel('端點',{exact:true}).inputValue(),endpoint.id);assert.equal((await fixture('state')).writes,0);
  await add().click();await page.locator('#detail [role=alert]').waitFor();assert((await page.locator('#detail [role=alert]').textContent()).includes('synthetic membership interruption'));
  assert.equal(await page.getByLabel('使用的 Key',{exact:true}).inputValue(),JSON.stringify(['p','a','second']));assert.equal(await page.getByLabel('端點',{exact:true}).inputValue(),endpoint.id);
  assert(await page.locator('#detail [role=alert]').evaluate(n=>{const r=n.getBoundingClientRect(),d=document.querySelector('#detail').getBoundingClientRect();return r.top>=d.top&&r.bottom<=d.bottom;}),'join error is outside visible modal');await shot('retry-375-light');
  await add().click();await page.waitForFunction(async()=>(await(await fetch('/__catalog_fixture/state')).json()).ready);assert(await page.getByLabel('使用的 Key',{exact:true}).isDisabled());
  await close();await search('尚未設定 Key');await open('尚未設定 Key');
  const held=page.waitForResponse(r=>r.url().endsWith('/api/v1/my-models')&&r.request().method()==='POST'&&r.status()===201);await fixture('release');await held;
  await page.waitForFunction(()=>catalogScopeIndex.has(JSON.stringify({provider_id:'p',account_id:'a',credential_id:'second',model_id:'other',group:'研發',endpoint:document.querySelector('#manual-endpoint')?.value}))||myModels.some(m=>m.model_id==='other'&&m.credential_id==='second'));
  assert.equal(await page.locator('#detail-title').textContent(),'尚未設定 Key','late add closed or replaced a newer modal');await close();
  await search('NebulaCoder');await open('程式夥伴');assert(await add().isEnabled(),'A scope borrowed B membership');await chooseB();assert(await page.getByRole('button',{name:'查看已加入的連線',exact:true}).isVisible());
  await page.getByLabel('使用的 Key',{exact:true}).selectOption(JSON.stringify(['p','a','c']));const addedA=page.waitForResponse(r=>r.url().endsWith('/api/v1/my-models')&&r.request().method()==='POST'&&r.status()===201);await add().click();await addedA;await page.locator('#detail').waitFor({state:'hidden'});
  await page.waitForFunction(()=>myModels.filter(m=>m.model_id==='other').length===2);assert((await row('程式夥伴').textContent()).includes('已加入 2 個連線'));
  await page.getByText('手動加入模型',{exact:true}).click();await page.locator('#manual-target').selectOption(JSON.stringify(['p','a','second']));assert.equal(await page.locator('#manual-endpoint').inputValue(),endpoint.id);
  await page.locator('#model-form input[name=model_id]').fill('manual-b');await page.locator('#model-form input[name=display_name]').fill('手動模型');
  const manual=page.waitForResponse(r=>r.url().endsWith('/api/v1/my-models')&&r.request().method()==='POST'&&r.status()===201);await page.locator('#manual-add').click();await manual;
  await page.waitForFunction(()=>myModels.some(m=>m.model_id==='manual-b'));assert.equal(await page.locator('#manual-target').inputValue(),JSON.stringify(['p','a','second']));assert.equal(await page.locator('#manual-endpoint').inputValue(),endpoint.id);assert.equal(await page.locator('#model-form input[name=model_id]').inputValue(),'manual-b');
  await page.locator('#manual-add:enabled').waitFor();await page.locator('#manual-add').click();await page.locator('#detail-model-settings-tab').waitFor();assert.equal((await fixture('state')).models,1);await close();
  await page.getByText('手動加入模型',{exact:true}).click();await search('NebulaCoder');
  for(const [width,font,scheme]of [[375,'','light'],[1280,'','dark'],[375,'200%','dark']]){
   await page.setViewportSize({width,height:900});await page.emulateMedia({colorScheme:scheme});await page.evaluate(font=>document.documentElement.style.fontSize=font,font);await shot('browse-'+width+(font?'-large':'')+'-'+scheme);
   await open('程式夥伴');await chooseB();await shot('connection-'+width+(font?'-large':'')+'-'+scheme);await close();
  }
  assert.equal(await page.locator('#model-search').inputValue(),'保留我的模型篩選');await page.getByRole('button',{name:'Playground',exact:true}).click();assert.equal(await page.locator('#prompt').inputValue(),'Catalog 探索不能清掉聊天草稿');
  const state=await fixture('state');assert.equal(state.members,5);assert.equal(state.writes,4);assert.equal(state.endpoints,1);assert.equal(state.provider_calls,0);assert.deepEqual(external,[]);assert.deepEqual(errors,[]);
  console.log('Catalog: 108 models/100-row pagination, aliases/canonical/fuzzy/vendor/provider filters, metadata-only capabilities, scoped duplicate/archived lookup, missing/disabled Key, explicit origin-B endpoint setup, visible 503 retry, late committed add guard, exact A/B memberships, manual endpoint/draft preservation; no inference, 375/1280px light/dark/200% passed.');
 }catch(e){await page.screenshot({path:'e2e/screenshots/catalog-failure.png',fullPage:false}).catch(()=>{});throw e;}finally{await browser.close();}
})().catch(e=>{console.error(e);process.exit(1);});
