const {waitForAsync}=require('./async-wait-helper.cjs');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const {openProviderForm,closeProviderEditor}=require('./provider-editor-helper.cjs');
const assert=require('node:assert/strict'),fs=require('node:fs/promises');
(async()=>{
 const browser=await chromium.launch({headless:true});
 const context=await browser.newContext({viewport:{width:375,height:812},reducedMotion:'reduce',httpCredentials:{username:'admin',password:process.env.MODELATLAS_TEST_TOKEN}});
 const page=await context.newPage(),errors=[],external=[];
 page.on('pageerror',e=>errors.push(e.message));
 page.on('request',r=>{if(!r.url().startsWith(process.argv[2]+'/'))external.push(r.url());});
 await fs.mkdir('e2e/screenshots',{recursive:true});
 const fixture=path=>page.evaluate(async path=>{const r=await fetch('/__detail_fixture/'+path);if(!r.ok)throw Error('fixture '+r.status);return r.json();},path);
 const tab=async id=>{await closeProviderEditor(page);await page.locator('#detail-'+id+'-tab').click();if(id==='model-performance')await page.locator('#detail-model-local-tab').click();};
 const close=async()=>{await closeProviderEditor(page);await page.getByRole('button',{name:'關閉詳情',exact:true}).click();};
 const openModel=async(name='日常對話')=>{await page.locator('#my-models .item-button').filter({hasText:name}).click();await page.locator('#detail-model-settings input[name=VISION]').waitFor({state:'attached'});};
 const rate=()=>page.locator('#detail-model-performance .metrics .item').first().locator('small').textContent();
 const shot=async name=>{
  assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),name+' document overflow');
  assert(await page.locator('#detail').evaluate(n=>n.scrollWidth<=n.clientWidth),name+' detail overflow');
  assert(await page.locator('#detail').evaluate(n=>{const a=n.querySelector('.dialog-bar h2').getBoundingClientRect(),b=n.querySelector('#detail-close').getBoundingClientRect();return a.right<=b.left+1;}),name+' heading covers close button');
  assert(await page.locator('#detail .detail-primary-actions:visible').count()===0||await page.locator('#detail-model-overview').isVisible(),name+' model actions crowded another tab');
  assert(await page.locator('#detail .detail-rows>div:visible').evaluateAll(rows=>rows.every(row=>{
   const label=document.createRange(),value=document.createRange();label.selectNodeContents(row.querySelector('dt'));value.selectNodeContents(row.querySelector('dd'));
   return [...label.getClientRects()].every(a=>[...value.getClientRects()].every(b=>a.right<=b.left+1||b.right<=a.left+1||a.bottom<=b.top+1||b.bottom<=a.top+1));
  })),name+' field label overlaps its value');
  await page.screenshot({path:'e2e/screenshots/detail-'+name+'.png',fullPage:false,animations:'disabled'});
 };
 try{
  await page.goto(process.argv[2]);await page.waitForFunction(()=>document.querySelector('#target').options.length===2);
  await page.getByRole('button',{name:'Models',exact:true}).click();
  await page.locator('#my-models .item-button').filter({hasText:'日常對話'}).click();
  await page.locator('#detail-model-overview [role=alert]').waitFor();
  assert((await page.locator('#detail-model-overview').textContent()).includes('無法讀取模型詳情'));
  assert(await page.locator('#detail-model-overview [role=alert]').evaluate(n=>{const r=n.getBoundingClientRect(),d=document.querySelector('#detail').getBoundingClientRect();return r.top>=d.top&&r.bottom<=d.bottom;}),'initial error/retry is below the visible detail');
  await shot('error-375-light');
  await page.locator('#detail-model-overview').getByRole('button',{name:'重新載入',exact:true}).click();
  await page.locator('#detail-model-settings input[name=VISION]').waitFor({state:'attached'});
  assert.equal(await page.locator('#detail-title').textContent(),'日常對話');
  assert.equal(await page.locator('#detail .detail-panel:visible').count(),1);
  assert(await page.locator('#detail-model-settings').isHidden(),'settings cluttered the overview');
  await tab('model-settings');
  await page.getByLabel('別名（逗號分隔）',{exact:true}).fill('未儲存的別名');
  await page.getByLabel('備註',{exact:true}).fill('切換時間窗時不能遺失的草稿');
  await page.locator('#detail-model-settings input[name=VISION]').check();
  await tab('model-performance');assert.equal(await rate(),'66.7%');
  await page.getByLabel('效能時間窗',{exact:true}).selectOption('1h');
  await waitForAsync(page,async()=> (await(await fetch('/__detail_fixture/state')).json()).window);
  const seven=page.waitForResponse(r=>r.url().includes('/api/v1/models/detail?')&&new URL(r.url()).searchParams.get('window')==='7d');
  await page.getByLabel('效能時間窗',{exact:true}).selectOption('7d');await seven;
  await page.locator('#detail-model-performance [aria-busy=true]').waitFor({state:'detached'});
  const oldWindow=page.waitForResponse(r=>r.url().includes('/api/v1/models/detail?')&&new URL(r.url()).searchParams.get('window')==='1h');
  await fixture('release-window');await oldWindow;await page.evaluate(()=>new Promise(requestAnimationFrame));
  assert.equal(await rate(),'66.7%','late 1h result replaced the newer 7d result');
  await tab('model-settings');assert.equal(await page.getByLabel('別名（逗號分隔）',{exact:true}).inputValue(),'未儲存的別名');
  assert.equal(await page.getByLabel('備註',{exact:true}).inputValue(),'切換時間窗時不能遺失的草稿');
  assert(await page.locator('#detail-model-settings input[name=VISION]').isChecked());
  await tab('model-performance');await page.getByLabel('效能時間窗',{exact:true}).selectOption('custom');
  const time=ms=>{const d=new Date(ms);return new Date(ms-d.getTimezoneOffset()*60000).toISOString().slice(0,16);};
  const from=time(Date.now()-3*3600000),to=time(Date.now()-60000);
  await page.getByLabel('開始',{exact:true}).fill(to);await page.getByLabel('結束',{exact:true}).fill(from);
  const reads=[];const track=r=>{if(r.url().includes('/api/v1/models/detail?'))reads.push(r.url());};page.on('request',track);
  await page.getByRole('button',{name:'套用時間窗',exact:true}).click();await page.getByText('結束時間須晚於開始，且不能超過現在',{exact:true}).waitFor();
  assert(await page.locator('#detail-model-performance [role=alert]').evaluate(n=>{const r=n.getBoundingClientRect(),d=document.querySelector('#detail').getBoundingClientRect();return r.top>=d.top&&r.bottom<=d.bottom;}),'range validation is hidden behind or below the dialog');assert.equal(reads.length,0,'invalid range was sent to the server');page.off('request',track);
  await page.getByLabel('開始',{exact:true}).fill(from);await page.getByLabel('結束',{exact:true}).fill(to);
  const custom=page.waitForResponse(r=>r.url().includes('/api/v1/models/detail?')&&new URL(r.url()).searchParams.get('window')==='custom');
  await page.getByRole('button',{name:'套用時間窗',exact:true}).click();assert.equal((await custom).status(),200);
  await page.waitForFunction(()=>document.querySelector('#detail-model-performance .metrics .item small').textContent==='50.0%');
  assert((await page.locator('#detail-model-performance').textContent()).includes('2 次有效樣本'));
  await tab('model-pricing');assert((await page.locator('#detail-model-pricing').textContent()).includes('0 USD / 1K tokens'));
  assert((await page.locator('#detail-model-pricing').textContent()).includes('已確認無固定費'));
  assert((await page.locator('#detail-model-pricing').textContent()).includes('FAILED'));
  assert((await page.locator('#detail-model-pricing').textContent()).includes('SUCCESS'),'failed latest sync erased last-good evidence');
  await tab('model-performance');assert.equal(await page.getByLabel('開始',{exact:true}).inputValue(),from);assert.equal(await page.getByLabel('結束',{exact:true}).inputValue(),to);assert.equal(await rate(),'50.0%');
  await tab('model-settings');await page.getByRole('button',{name:'確認能力',exact:true}).click();
  await page.getByText('此連線的能力設定已更新',{exact:true}).waitFor();
  const caps=await page.evaluate(async()=>{const m=myModels.find(m=>m.model_id==='m');return (await api('/models/detail?'+query(m))).capabilities;});assert(caps.includes('VISION'));
  await tab('model-overview');assert((await page.locator('#detail-model-overview').textContent()).includes('VISION'),'confirmed capability did not update overview');
  await tab('model-settings');assert.equal(await page.getByLabel('備註',{exact:true}).inputValue(),'切換時間窗時不能遺失的草稿');
  await page.getByLabel('Canonical ID',{exact:true}).fill('ui-manual');await page.getByLabel('類別',{exact:true}).fill('CODING');
  await page.getByRole('button',{name:'儲存分類',exact:true}).click();await page.getByText('已更新分類與別名',{exact:true}).waitFor();
  await tab('model-overview');assert((await page.locator('#detail-model-overview').textContent()).includes('ui-manual'));assert((await page.locator('#detail-model-overview').textContent()).includes('CODING'),'saved metadata did not update overview');
  await tab('model-settings');assert.equal(await page.getByLabel('備註',{exact:true}).inputValue(),'切換時間窗時不能遺失的草稿');
  await close();await openModel();await tab('model-settings');assert.equal(await page.getByLabel('別名（逗號分隔）',{exact:true}).inputValue(),'未儲存的別名');
  await tab('model-overview');await fixture('arm-action');
  await page.getByRole('button',{name:'加入最愛',exact:true}).click();
  await waitForAsync(page,async()=> (await(await fetch('/__detail_fixture/state')).json()).action);
  await close();await openModel('另一個模型');
  const lateAction=page.waitForResponse(r=>r.url().endsWith('/api/v1/models/lifecycle')&&r.request().method()==='POST');
  await fixture('release-action');await lateAction;await page.evaluate(()=>new Promise(requestAnimationFrame));
  assert.equal(await page.locator('#detail-title').textContent(),'另一個模型','late action reopened the previous model');
  await tab('model-performance');assert.equal(await rate(),'尚無樣本','another model borrowed local uptime');
  await close();await openModel();assert(await page.getByRole('button',{name:'取消最愛',exact:true}).isVisible());
  for(const [width,font,scheme]of [[375,'','light'],[1280,'','dark'],[375,'200%','dark']]){
   await page.setViewportSize({width,height:900});await page.emulateMedia({colorScheme:scheme});await page.evaluate(font=>document.documentElement.style.fontSize=font,font);
   for(const id of ['overview','performance','pricing','settings']){await tab('model-'+id);await shot('model-'+id+'-'+width+(font?'-large':'')+'-'+scheme);}
  }
  await page.evaluate(()=>document.documentElement.style.removeProperty('font-size'));await page.setViewportSize({width:375,height:812});await page.emulateMedia({colorScheme:'light'});
  await close();await page.getByRole('button',{name:'Providers',exact:true}).click();await page.locator('#provider-list .item-button').first().click();
  assert.equal(await page.locator('#detail-provider-overview .item-button').count(),2);assert(!(await page.locator('#detail-provider-overview').textContent()).includes('未加入的候選'));
  await page.locator('#detail-provider-overview-tab').focus();await page.keyboard.press('ArrowRight');assert.equal(await page.locator('#detail-provider-keys-tab').getAttribute('aria-selected'),'true');
  await openProviderForm(page,'key');await page.getByLabel('API Key / PAT',{exact:true}).fill('synthetic-unsent-draft');
  await tab('provider-endpoints');assert((await page.locator('#detail-provider-endpoints').textContent()).includes('新增端點不改變既有對話'));
  await tab('provider-keys');await openProviderForm(page,'key');assert.equal(await page.getByLabel('API Key / PAT',{exact:true}).inputValue(),'synthetic-unsent-draft');await page.getByLabel('API Key / PAT',{exact:true}).fill('');await closeProviderEditor(page);
  await page.locator('#detail-provider-keys').getByRole('button',{name:'停用',exact:true}).click();
  await page.locator('#detail-provider-keys').getByRole('button',{name:'啟用',exact:true}).waitFor();assert.equal(await page.locator('#detail-provider-keys-tab').getAttribute('aria-selected'),'true');
  await page.locator('#detail-provider-keys').getByRole('button',{name:'啟用',exact:true}).click();await page.locator('#detail-provider-keys').getByRole('button',{name:'停用',exact:true}).waitFor();
  for(const [width,font,scheme]of [[375,'','light'],[1280,'','dark'],[375,'200%','dark']]){
   await page.setViewportSize({width,height:900});await page.emulateMedia({colorScheme:scheme});await page.evaluate(font=>document.documentElement.style.fontSize=font,font);
   for(const id of ['overview','keys','endpoints','settings']){await tab('provider-'+id);await shot('provider-'+id+'-'+width+(font?'-large':'')+'-'+scheme);}
  }
  await page.evaluate(()=>document.documentElement.style.removeProperty('font-size'));await page.locator('#detail-provider-settings-tab').focus();await page.keyboard.press('Home');assert.equal(await page.locator('#detail-provider-overview-tab').getAttribute('aria-selected'),'true');
  await page.locator('#detail-provider-overview .item-button').filter({hasText:'日常對話'}).click();await page.locator('#detail-model-settings input[name=VISION]').waitFor({state:'attached'});assert.equal(await page.locator('#detail-title').textContent(),'日常對話');
  assert.deepEqual(external,[]);assert.deepEqual(errors,[]);
  console.log('Detail Browser: actual error/retry, scoped tabs with keyboard navigation, unchanged drafts during real time-window reads, late-response rejection, validated custom range and cancel-excluded rates, independent nominal/effective/last-good sources, real metadata/capability save, late lifecycle completion cannot reopen another model, real key disable/enable, provider My Models excludes Catalog, 375/1280/dark/200%; no inference HTTP or history/member loss.');
 }catch(e){await page.screenshot({path:'e2e/screenshots/detail-failure.png',fullPage:true}).catch(()=>{});throw e;}finally{await browser.close();}
})().catch(e=>{console.error(e);process.exit(1);});
