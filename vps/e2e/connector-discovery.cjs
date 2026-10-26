const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const assert=require('node:assert/strict'),fs=require('node:fs');
fs.mkdirSync('e2e/screenshots',{recursive:true});
(async()=>{
 const browser=await chromium.launch({headless:true}),context=await browser.newContext({viewport:{width:375,height:812},httpCredentials:{username:'admin',password:process.env.MODELATLAS_TEST_TOKEN}}),page=await context.newPage(),errors=[];
 const base=process.argv[2],headers={Authorization:'Bearer '+process.env.MODELATLAS_TEST_TOKEN};let discoveries=0,activations=0,undos=0;
 page.on('pageerror',e=>errors.push(e.message));page.on('request',r=>{if(r.method()!=='POST')return;if(r.url().endsWith('/connectors/discover'))discoveries++;if(r.url().endsWith('/connectors/activate'))activations++;if(r.url().endsWith('/versions/undo'))undos++;});
 async function json(path,data){const r=await context.request[data?'post':'get'](base+'/api/v1'+path,{headers,...(data?{data}:{})});assert(r.ok(),await r.text());return r.json();}
 async function openKnowledge(){await page.getByRole('button',{name:'Models',exact:true}).click();await page.getByRole('tab',{name:'我的模型',exact:true}).click();await page.getByRole('tab',{name:'研究知識',exact:true}).click();await page.locator('#connector-discovery-target option').first().waitFor({state:'attached'});}
 async function openDraft(){await page.locator('#connector-discovery-list .item-button').first().click();await page.getByRole('button',{name:'檢視價格草稿',exact:true}).click();}
 async function shot(name,width,dark=false,large=false){await page.setViewportSize({width,height:width>700?900:812});await page.emulateMedia({colorScheme:dark?'dark':'light'});await page.evaluate(large=>document.documentElement.style.fontSize=large?'200%':'',large);assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'connector review overflow');assert(await page.locator('#detail').evaluate(n=>n.getBoundingClientRect().left>=0&&n.getBoundingClientRect().right<=innerWidth),'connector dialog escapes viewport');await page.screenshot({animations:'disabled',path:'e2e/screenshots/connector-discovery-'+name+'.png',fullPage:false});}
 try{
  await page.goto(base);await openKnowledge();
  let failRead=true;await page.route('**/api/v1/connectors',async route=>{if(failRead&&route.request().method()==='GET'&&discoveries){failRead=false;await route.fulfill({status:503,body:'read unavailable'});}else await route.continue();});
  await page.getByRole('button',{name:'辨識站點並產生草稿',exact:true}).click();await page.getByRole('button',{name:'讀取辨識結果',exact:true}).waitFor();assert.equal(discoveries,1);assert(await page.locator('#connector-discovery-form input[name=source_path]').isDisabled());
  await page.getByRole('button',{name:'讀取辨識結果',exact:true}).click();await page.getByRole('button',{name:'檢視價格草稿',exact:true}).click();
  await page.getByText('欄位樣本檢查已通過；仍需重新讀取來源實測。',{exact:true}).waitFor();assert.equal(await page.getByRole('button',{name:'確認啟用擷取規則',exact:true}).count(),0);assert.equal(discoveries,1);
  for(const width of [320,375,1280])for(const dark of [false,true])await shot('review-'+width+'-'+(dark?'dark':'light'),width,dark);
  await shot('review-375-200-dark',375,true,true);await page.evaluate(()=>document.documentElement.style.fontSize='');await page.setViewportSize({width:375,height:812});
  await page.getByRole('button',{name:'測試資料擷取',exact:true}).click();await page.getByText('擷取測試通過',{exact:true}).waitFor();const version=(await json('/connectors'))[0];assert.equal(version.status,'VALIDATED');assert.equal(version.test.facts.price.input,'0');assert.equal(version.test.facts.price.output,'0.002');assert.equal(version.test.facts.effective_charge,undefined);
  failRead=true;await page.getByRole('button',{name:'確認啟用擷取規則',exact:true}).click();await page.getByRole('button',{name:'重新讀取擷取版本',exact:true}).waitFor();assert.equal(activations,1);assert(await page.getByRole('button',{name:'確認啟用擷取規則',exact:true}).isDisabled());
  await page.getByRole('button',{name:'重新讀取擷取版本',exact:true}).click();await page.getByRole('button',{name:'Undo 擷取規則啟用',exact:true}).waitFor();
  await page.reload();await openKnowledge();await openDraft();assert.equal(activations,1);assert.equal(discoveries,1);await page.getByRole('button',{name:'Undo 擷取規則啟用',exact:true}).waitFor();
  const selection=version.discovery.selection;await json('/intelligence/sync',selection);const detail=await json('/models/detail?'+new URLSearchParams(selection));assert(JSON.stringify(detail).includes('DECLARATIVE_PROVIDER_META:'+version.id),'production Intelligence did not use activated connector');
  await context.request.post(base+'/test/change-connector-unit');await page.getByRole('button',{name:'測試資料擷取',exact:true}).click();await page.getByText('擷取尚未通過，已保存原因',{exact:true}).waitFor();assert.equal((await json('/connectors'))[0].status,'FAILED');assert.equal((await json('/connectors'))[0].active,true);
  await page.reload();await openKnowledge();await openDraft();await page.getByText('需重新驗證 · 已啟用',{exact:true}).waitFor();assert.equal(await page.getByRole('button',{name:'確認啟用擷取規則',exact:true}).count(),0);await shot('changed-source-375-light',375);
  await page.getByRole('button',{name:'Undo 擷取規則啟用',exact:true}).click();await page.getByText('擷取規則已還原',{exact:true}).waitFor();assert.equal(undos,1);assert.equal((await json('/connectors'))[0].active,false);assert.deepEqual(errors,[]);
  console.log('PASS: formal public-site discovery -> evidence/unit/scope/Unknown draft -> fixture replay -> bounded TLS test -> Review/one activation despite failed read -> production Intelligence -> changed-unit failure retained -> reload-persistent Undo; no added membership/inference; 8 responsive images.');
 }catch(e){await page.screenshot({path:'e2e/screenshots/connector-discovery-failure.png',fullPage:false}).catch(()=>{});throw e;}finally{await browser.close();}
})().catch(e=>{console.error(e);process.exit(1);});
