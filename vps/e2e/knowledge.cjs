const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const assert=require('node:assert/strict'),fs=require('node:fs');
fs.mkdirSync('e2e/screenshots',{recursive:true});
(async()=>{
 const browser=await chromium.launch({headless:true}),context=await browser.newContext({viewport:{width:375,height:812},httpCredentials:{username:'admin',password:process.env.MODELATLAS_TEST_TOKEN}}),page=await context.newPage(),errors=[];
 page.on('pageerror',e=>errors.push(e.message));
 try{
  await page.goto(process.argv[2]);await page.getByRole('button',{name:'Models',exact:true}).click();await page.getByText('研究知識與擷取規則',{exact:true}).click();
  await page.locator('#knowledge-list .item-button').first().waitFor();await page.locator('#knowledge-list .item-button').filter({hasText:'SOURCE_RELIABILITY'}).click();
  await page.getByLabel('修訂資料 JSON',{exact:true}).fill(JSON.stringify({source_id:'source',weight:2,confidence:'TENTATIVE'}));await page.getByLabel('修訂理由',{exact:true}).fill('Browser evidence-based source priority.');
  await page.getByRole('button',{name:'保存新版本',exact:true}).click();await page.getByText('知識新版本已保存',{exact:true}).waitFor();
  await page.locator('#knowledge-list .item-button').filter({hasText:'SOURCE_RELIABILITY · v2'}).click();await page.getByRole('button',{name:'確認啟用此知識版本',exact:true}).click();await page.getByText('知識版本已啟用',{exact:true}).waitFor();
  await page.getByRole('button',{name:'Undo 知識啟用',exact:true}).click();await page.getByText('知識啟用已還原',{exact:true}).waitFor();
  await page.getByText('宣告式資料擷取',{exact:true}).click();const form=page.locator('#connector-form');await form.locator('[name="provider_id"]').selectOption('p');await form.locator('[name="name"]').fill('browser-quota');await form.locator('[name="path"]').fill('/metadata');await form.locator('[name="root_pointer"]').fill('/data');await form.locator('[name="fields"]').fill(JSON.stringify({model_id:'/model',group:'/group',rpd:'/quota/rpd',tpd:'/quota/tpd'}));await form.locator('[name="reason"]').fill('Real Browser metadata mapping.');
  await page.getByRole('button',{name:'保存擷取草稿',exact:true}).click();await page.getByText('擷取草稿已保存，請先測試',{exact:true}).waitFor();await page.locator('#connector-list .item-button').first().click();assert.equal(await page.getByRole('button',{name:'確認啟用擷取規則',exact:true}).count(),0);
  await page.getByRole('button',{name:'測試資料擷取',exact:true}).click();await page.getByText('擷取測試通過',{exact:true}).waitFor();await page.getByRole('button',{name:'確認啟用擷取規則',exact:true}).click();await page.getByText('擷取規則已啟用',{exact:true}).waitFor();
  await page.screenshot({animations:'disabled',path:'e2e/screenshots/knowledge-375-light.png',fullPage:true});await page.getByRole('button',{name:'Undo 擷取規則啟用',exact:true}).click();await page.getByText('擷取規則已還原',{exact:true}).waitFor();
  await page.emulateMedia({colorScheme:'dark'});await page.evaluate(()=>document.documentElement.style.fontSize='200%');assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'knowledge large font overflow');await page.screenshot({animations:'disabled',path:'e2e/screenshots/knowledge-375-large-dark.png',fullPage:true});assert.deepEqual(errors,[]);
  console.log('Knowledge Browser: real research reflection → new source-weight version → activation/Undo; declarative GET draft → actual scoped TLS extraction → explicit activation/Undo; 375px/200% passed.');
 }catch(e){await page.screenshot({path:'e2e/screenshots/knowledge-failure.png',fullPage:true}).catch(()=>{});throw e;}finally{await browser.close();}
})().catch(e=>{console.error(e);process.exit(1);});
