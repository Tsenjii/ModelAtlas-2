const {waitForAsync}=require('./async-wait-helper.cjs');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const {openProviderForm,closeProviderEditor}=require('./provider-editor-helper.cjs');
const assert=require('node:assert/strict'),fs=require('node:fs/promises');
(async()=>{
 const browser=await chromium.launch({headless:true});
 const context=await browser.newContext({viewport:{width:375,height:900},reducedMotion:'reduce',httpCredentials:{username:'admin',password:process.env.MODELATLAS_TEST_TOKEN}});
 const page=await context.newPage(),errors=[],external=[];
 page.on('pageerror',e=>errors.push(e.message));page.on('request',r=>{if(!r.url().startsWith(process.argv[2]+'/'))external.push(r.url());});
 const fixture=path=>page.evaluate(async path=>(await fetch('/__provider_forms_fixture/'+path)).json(),path);
 const form=id=>page.locator('#provider-'+id+'-form');
 const tab=async id=>{await closeProviderEditor(page);await page.locator('#detail-provider-'+id+'-tab').click();};
 const expand=id=>openProviderForm(page,id);
 const close=async()=>{await closeProviderEditor(page);await page.getByRole('button',{name:'關閉詳情',exact:true}).click();};
 const open=name=>page.locator('#provider-list .item-button').filter({has:page.getByText(name,{exact:true})}).click();
 const input=(id,label)=>form(id).getByLabel(label,{exact:true});
 const pendingDone=id=>page.waitForFunction(id=>!document.querySelector('#provider-'+id+'-form').hasAttribute('aria-busy'),id);
 const feedback=(id,role='status')=>form(id).locator('[data-provider-feedback][role='+role+']');
 const assertErrorVisible=async id=>assert(await feedback(id,'alert').evaluate(n=>{const r=n.getBoundingClientRect(),d=n.closest('dialog').getBoundingClientRect(),bar=n.closest('dialog').querySelector('.dialog-bar').getBoundingClientRect();return r.top>=bar.bottom&&r.bottom<=d.bottom-1;}),id+' error is clipped or covered by the sticky heading');
 const shot=async name=>{
  assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),name+' page overflow');
  assert(await page.locator('#detail').evaluate(n=>n.scrollWidth<=n.clientWidth),name+' dialog overflow');
  assert(await page.locator('#detail .detail-rows>div').evaluateAll(rows=>rows.filter(n=>n.checkVisibility()).every(row=>{const a=document.createRange(),b=document.createRange();a.selectNodeContents(row.querySelector('dt'));b.selectNodeContents(row.querySelector('dd'));return [...a.getClientRects()].every(x=>[...b.getClientRects()].every(y=>x.right<=y.left+1||y.right<=x.left+1||x.bottom<=y.top+1||y.bottom<=x.top+1));})),name+' metadata overlap');
  await page.screenshot({path:'e2e/screenshots/provider-forms-'+name+'.png',fullPage:false,animations:'disabled'});
 };
 let expectedKeyName='未送出的 Key 草稿';
 const assertDrafts=async()=>{
  assert.equal(await input('key','名稱').inputValue(),expectedKeyName);
  assert.equal(await input('key','Key 所屬 Group').inputValue(),'專案草稿');
  assert.equal(await input('endpoint','端點 URL').inputValue(),process.argv[3]);
  assert.equal(await input('edit','Provider 備註').inputValue(),'其他保存動作不能清除此草稿');
  for(const id of ['key','account','rotate','endpoint','config','edit']){
   assert.equal(await form(id).getAttribute('data-retention'),'original',id+' form was recreated');
   assert(await form(id).locator('..').evaluate(n=>n.open),id+' disclosure was collapsed');
  }
 };
 await fs.mkdir('e2e/screenshots',{recursive:true});
 try{
  await page.goto(process.argv[2]);await page.waitForFunction(()=>document.querySelector('#target').options.length===1);
  await page.getByRole('button',{name:'Providers',exact:true}).click();await open('P');
  await tab('settings');await expand('config');await expand('edit');
  const config=JSON.parse(await input('config','設定 JSON').inputValue());config.notes='表單保存驗證';
  await input('config','設定 JSON').fill(JSON.stringify(config,null,2));await input('edit','Provider 備註').fill('其他保存動作不能清除此草稿');
  await tab('endpoints');await expand('endpoint');await input('endpoint','端點 URL').fill(process.argv[3]);
  await tab('keys');for(const id of ['key','account','rotate'])await expand(id);
  await expand('key');await input('key','名稱').fill('未送出的 Key 草稿');await input('key','Key 所屬 Group').fill('專案草稿');await input('key','API Key / PAT').fill('synthetic-new-key');
  const fields='[{"name":"X-Project-Token","secret":"synthetic-auth-field"}]';await input('key','額外認證欄位 JSON（選填）').fill(fields);
  await expand('account');await input('account','顯示名稱').fill('新專案帳戶');await expand('rotate');await input('rotate','新的 Key').fill('synthetic-b-rotated');
  const choices=await input('rotate','選擇 Key').locator('option').allTextContents();
  assert.deepEqual(choices,['P / A / C / 預設 Group','P / B / C / 研發']);
  await input('rotate','選擇 Key').selectOption(JSON.stringify(['p','b','c']));
  await page.locator('#detail form,#provider-editor form').evaluateAll(forms=>forms.forEach(f=>f.dataset.retention='original'));
  await closeProviderEditor(page);const aCard=()=>page.locator('#detail-provider-keys .cards > .item').filter({hasText:'A · 推論'});
  for(const label of ['停用','啟用']){
   await aCard().getByRole('button',{name:label,exact:true}).click();
   await aCard().getByRole('button',{name:label==='停用'?'啟用':'停用',exact:true}).waitFor();
   assert.equal(await page.locator('#detail-provider-keys-tab').getAttribute('aria-selected'),'true');await assertDrafts();
   assert.equal(await input('key','API Key / PAT').inputValue(),'synthetic-new-key');assert.equal(await input('rotate','選擇 Key').inputValue(),JSON.stringify(['p','b','c']));
  }
  await expand('account');await form('account').getByRole('button',{name:'新增帳戶',exact:true}).click();await feedback('account','alert').waitFor();await pendingDone('account');
  assert((await feedback('account','alert').textContent()).includes('synthetic account interruption'));assert.equal(await input('account','顯示名稱').inputValue(),'新專案帳戶');await assertDrafts();
  assert(await feedback('account','alert').evaluate(n=>{const a=n.getBoundingClientRect(),d=n.closest('dialog').getBoundingClientRect();return a.top>=d.top&&a.bottom<=d.bottom;}));await assertErrorVisible('account');await shot('account-retry-375-light');
  await form('account').getByRole('button',{name:'新增帳戶',exact:true}).click();
  await waitForAsync(page,async()=>(await(await fetch('/__provider_forms_fixture/state')).json()).account_ready);
  assert.equal(await form('account').getAttribute('aria-busy'),'true');assert(await input('account','顯示名稱').isDisabled());
  await form('account').evaluate(n=>{n.requestSubmit();n.requestSubmit();});assert.equal((await fixture('state')).accounts,2,'duplicate pending account submission');
  assert(await input('key','名稱').isEnabled(),'unrelated Key draft was locked');await expand('key');await input('key','名稱').fill('處理中仍可編輯的 Key');await input('key','名稱').fill('未送出的 Key 草稿');
  await expand('account');await fixture('release-account');await feedback('account').filter({hasText:'帳戶已新增'}).waitFor();await pendingDone('account');
  assert.equal(await input('account','顯示名稱').inputValue(),'');assert.equal(await input('key','帳戶').inputValue(),'a');assert.equal(await input('key','帳戶').locator('option').count(),3);await assertDrafts();
  await expand('key');await input('key','額外認證欄位 JSON（選填）').fill('[{"secret":"synthetic-auth-field"');
  await form('key').getByRole('button',{name:'保存 Key',exact:true}).click();await feedback('key','alert').waitFor();await pendingDone('key');
  assert.equal((await fixture('state')).credentials,0,'invalid JSON reached server');assert.equal(await feedback('key','alert').textContent(),'JSON 格式不正確，請檢查後重試。');
  assert(!(await feedback('key','alert').textContent()).includes('synthetic-auth-field'));assert.equal(await input('key','API Key / PAT').inputValue(),'synthetic-new-key');await assertErrorVisible('key');await shot('key-json-error-375-light');
  await input('key','額外認證欄位 JSON（選填）').fill(fields);await form('key').getByRole('button',{name:'保存 Key',exact:true}).click();
  await feedback('key','alert').filter({hasText:'synthetic key interruption'}).waitFor();await pendingDone('key');assert.equal(await input('key','API Key / PAT').inputValue(),'synthetic-new-key');assert.equal(await input('key','額外認證欄位 JSON（選填）').inputValue(),fields);await assertErrorVisible('key');
  await form('key').getByRole('button',{name:'保存 Key',exact:true}).click();await feedback('key').filter({hasText:'Key 已保存'}).waitFor();await pendingDone('key');expectedKeyName='';await assertDrafts();
  assert.equal(await input('key','API Key / PAT').inputValue(),'');assert.equal(await input('key','額外認證欄位 JSON（選填）').inputValue(),'');assert.equal(await input('rotate','新的 Key').inputValue(),'synthetic-b-rotated');
  await expand('rotate');await form('rotate').getByRole('button',{name:'確認更新 / 還原',exact:true}).click();await feedback('rotate').filter({hasText:'Key 已更新，原版本與歷史仍保留'}).waitFor();await pendingDone('rotate');
  assert.equal(await input('rotate','新的 Key').inputValue(),'');assert.equal(await input('rotate','選擇 Key').inputValue(),JSON.stringify(['p','b','c']));await assertDrafts();
  await tab('endpoints');await form('endpoint').getByRole('button',{name:'新增端點',exact:true}).click();await feedback('endpoint').filter({hasText:'已新增獨立端點；舊對話端點不變'}).waitFor();await pendingDone('endpoint');await assertDrafts();
  assert((await page.locator('#detail-provider-endpoints .cards').textContent()).includes(process.argv[3]));
  await tab('settings');const valid=await input('config','設定 JSON').inputValue();await input('config','設定 JSON').fill('{ invalid');
  await form('config').getByRole('button',{name:'儲存 Provider 設定',exact:true}).click();await feedback('config','alert').waitFor();await pendingDone('config');assert.equal((await fixture('state'))['providers/config'],0);
  assert.equal(await feedback('config','alert').textContent(),'JSON 格式不正確，請檢查後重試。');await assertErrorVisible('config');await input('config','設定 JSON').fill(valid);await assertDrafts();
  for(const [width,font,scheme]of [[375,'','light'],[1280,'','dark'],[375,'200%','dark']]){
   await page.setViewportSize({width,height:900});await page.emulateMedia({colorScheme:scheme});await page.evaluate(font=>document.documentElement.style.fontSize=font,font);
   await tab('keys');await expand('rotate');await input('rotate','選擇 Key').scrollIntoViewIfNeeded();await shot('rotation-'+width+(font?'-large':'')+'-'+scheme);
   await tab('endpoints');await form('endpoint').getByRole('button',{name:'新增端點',exact:true}).scrollIntoViewIfNeeded();await shot('endpoint-saved-'+width+(font?'-large':'')+'-'+scheme);
   await tab('settings');await input('config','設定 JSON').fill('{ invalid');await form('config').getByRole('button',{name:'儲存 Provider 設定',exact:true}).click();await feedback('config','alert').waitFor();await pendingDone('config');await assertErrorVisible('config');await shot('config-error-'+width+(font?'-large':'')+'-'+scheme);await input('config','設定 JSON').fill(valid);
  }
  await page.evaluate(()=>document.documentElement.style.removeProperty('font-size'));await page.setViewportSize({width:375,height:900});await page.emulateMedia({colorScheme:'light'});
  await page.evaluate(()=>window.pendingProviderForm=document.querySelector('#provider-config-form'));
  await form('config').getByRole('button',{name:'儲存 Provider 設定',exact:true}).click();await waitForAsync(page,async()=>(await(await fetch('/__provider_forms_fixture/state')).json()).config_ready);
  await form('config').evaluate(n=>n.requestSubmit());assert.equal((await fixture('state'))['providers/config'],1);await close();await open('Q');
  await fixture('release-config');await page.waitForFunction(()=>!window.pendingProviderForm.hasAttribute('aria-busy'));assert.equal(await page.evaluate(()=>providers.find(p=>p.id==='p').config.notes),'表單保存驗證');
  assert.equal(await page.locator('#detail-title').textContent(),'Q','late config save reopened previous Provider');assert.equal(await page.locator('#detail [data-provider-feedback]').count(),0,'late save wrote into new dialog');
  await close();await open('P');await tab('settings');await expand('config');assert.equal(JSON.parse(await input('config','設定 JSON').inputValue()).notes,'表單保存驗證');
  const state=await fixture('state');assert.equal(state.provider_calls,0);assert.deepEqual(external,[]);assert.deepEqual(errors,[]);
  assert(!(await page.locator('body').textContent()).includes('synthetic-new-key'));assert(!(await page.locator('body').textContent()).includes('synthetic-b-rotated'));
  console.log('Provider forms: real scoped toggle/account/Key/endpoint/config/rotation, same Key ID in A/B isolated, six original form nodes and drafts/disclosures retained, visible generic JSON/503 errors and manual retries, pending duplicate writes blocked without locking other drafts, only submitted secrets cleared, late committed config cannot replace Q; retained membership/history and zero Provider HTTP; 375/1280px light/dark/200% passed.');
 }catch(e){await page.screenshot({path:'e2e/screenshots/provider-forms-failure.png',fullPage:false}).catch(()=>{});throw e;}finally{await browser.close();}
})().catch(e=>{console.error(e);process.exit(1);});
