const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const assert=require('node:assert/strict'),fs=require('node:fs');
fs.mkdirSync('e2e/screenshots',{recursive:true});
(async()=>{
 const browser=await chromium.launch({headless:true});
 const context=await browser.newContext({viewport:{width:375,height:812},httpCredentials:{username:'admin',password:process.env.MODELATLAS_TEST_TOKEN}});
 const page=await context.newPage(),base=process.argv[2],errors=[];page.on('pageerror',e=>errors.push(e.message));
 const message='已準備公開欄位草稿，請到研究知識核對並實測。';
 async function json(path,data){const r=await context.request[data?'post':'get'](base+'/api/v1'+path,{...(data?{data}:{})});assert(r.ok(),await r.text());return r.json();}
 async function state(){return (await context.request.get(base+'/__fixture/assistant-mapping')).json();}
 async function knowledge(){await page.getByRole('button',{name:'Models',exact:true}).click();await page.getByRole('tab',{name:'我的模型',exact:true}).click();await page.getByRole('tab',{name:'研究知識',exact:true}).click();await page.locator('#connector-discovery-list .item-button').first().waitFor();}
 async function draft(){await page.locator('#connector-discovery-list .item-button').first().click();await page.getByRole('button',{name:'檢視價格草稿',exact:true}).click();await page.getByRole('button',{name:'測試資料擷取',exact:true}).waitFor();}
 async function shot(name,width=375,dark=false,large=false,focus){await page.setViewportSize({width,height:width>700?900:812});await page.emulateMedia({colorScheme:dark?'dark':'light'});await page.evaluate(v=>document.documentElement.style.fontSize=v?'200%':'',large);if(focus)await page.getByText(focus,{exact:true}).scrollIntoViewIfNeeded();assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'Assistant mapping horizontal overflow');if(await page.locator('#detail').evaluate(n=>n.open))assert(await page.locator('#detail').evaluate(n=>n.getBoundingClientRect().left>=0&&n.getBoundingClientRect().right<=innerWidth),'Assistant mapping dialog escaped viewport');await page.screenshot({path:'e2e/screenshots/assistant-mapping-'+name+'.png',fullPage:false,animations:'disabled'});}
 try{
  await page.goto(base);await page.getByRole('button',{name:'Assistant',exact:true}).click();await page.locator('#assistant-target option').first().waitFor({state:'attached'});
  await page.getByRole('button',{name:'保存 Assistant 模型',exact:true}).click();await page.getByText('Assistant 模型已保存',{exact:true}).waitFor();
  await page.locator('#assistant-prompt').fill('請辨識 /api/pricing 的公開 JSON，依實際欄位提出價格擷取草稿，保留未知與來源，等待我確認。');
  await page.getByRole('button',{name:'執行工作',exact:true}).click();await page.locator('#assistant-chat').getByText(message,{exact:true}).waitFor();await page.locator('#assistant-send:enabled').waitFor();
  assert.deepEqual(await state(),{metadata_http:1,model_http:4,versions:1,active:0,members:1,facts:0});
  const thread=await page.locator('#assistant-thread').inputValue();assert(thread);await page.getByText('讀取公開欄位',{exact:true}).waitFor();await page.getByText('準備欄位草稿',{exact:true}).waitFor();await shot('agent-375-light');
  await page.reload();await page.getByRole('button',{name:'Assistant',exact:true}).click();await page.locator('#assistant-thread option').nth(1).waitFor({state:'attached'});await page.locator('#assistant-thread').selectOption(thread);await page.locator('#assistant-chat').getByText(message,{exact:true}).waitFor();assert.equal((await state()).model_http,4,'reload replayed inference');
  await knowledge();await draft();await page.getByText(/^Assistant 欄位提案：/).waitFor();assert.equal(await page.getByRole('button',{name:'確認啟用擷取規則',exact:true}).count(),0);await shot('review-375-light');
  const version=(await json('/connectors'))[0];assert.equal(version.discovery.mapping_author,'ASSISTANT');assert.equal(version.status,'DRAFT');assert.equal(version.config.prices_are_effective,false);assert.equal(version.config.fixed_charge_absent,false);
  await page.getByText('/document/reach = MODEL_PROVIDER',{exact:true}).waitFor();await shot('fields-1280-light',1280,false,false,'Assistant 提案欄位');
  await shot('review-320-200-dark',320,true,true,'Assistant 提案欄位');await page.evaluate(()=>document.documentElement.style.fontSize='');
  const test=page.getByRole('button',{name:'測試資料擷取',exact:true});await test.scrollIntoViewIfNeeded();assert((await test.boundingBox()).height>=48);await test.click();await page.getByText('擷取測試通過',{exact:true}).waitFor();assert.equal((await state()).metadata_http,2);assert.equal((await state()).active,0);
  await page.getByRole('button',{name:'確認啟用擷取規則',exact:true}).click();await page.getByRole('button',{name:'Undo 擷取規則啟用',exact:true}).waitFor();await page.getByText(/^原始欄位由 Assistant 提議；你已確認啟用此版本/).waitFor();
  await json('/intelligence/sync',version.discovery.selection);assert.equal((await state()).metadata_http,3);assert.equal((await state()).facts,1);await page.locator('#detail').evaluate(n=>n.scrollTop=0);await shot('active-375-dark',375,true);
  await page.reload();await knowledge();await draft();await page.getByRole('button',{name:'Undo 擷取規則啟用',exact:true}).click();await page.getByText('擷取規則已還原',{exact:true}).waitFor();await shot('undo-1280-dark',1280,true);
  assert.deepEqual(await state(),{metadata_http:3,model_http:4,versions:1,active:0,members:1,facts:1});assert.deepEqual(errors,[]);
  console.log('PASS: real Assistant tool loop -> anonymous unknown JSON -> saved public descriptors -> Assistant-authored immutable fixture DRAFT -> reload without replay -> visible field/scope/unit review -> one real source test -> explicit activation -> formal nominal facts -> reload-persistent Undo. 3 metadata HTTP and 4 explicitly initiated model HTTP, private sources excluded; six responsive screenshots.');
 }catch(e){await page.screenshot({path:'e2e/screenshots/assistant-mapping-failure.png',fullPage:false}).catch(()=>{});throw e;}finally{await browser.close();}
})().catch(e=>{console.error(e);process.exit(1);});
