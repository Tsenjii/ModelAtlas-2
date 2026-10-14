const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const assert=require('node:assert/strict'),fs=require('node:fs');
fs.mkdirSync('e2e/screenshots',{recursive:true});
(async()=>{
 const browser=await chromium.launch({headless:true});
 const context=await browser.newContext({viewport:{width:375,height:812},httpCredentials:{username:'admin',password:process.env.MODELATLAS_TEST_TOKEN}});
 const page=await context.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message));
 try{
  await page.goto(process.argv[2]);await page.getByText('App Assistant',{exact:true}).click();
  await page.locator('#assistant-target option').first().waitFor({state:'attached'});
  await page.getByRole('button',{name:'保存 Assistant 模型',exact:true}).click();
  await page.getByText('Assistant 模型已保存',{exact:true}).waitFor();
  await page.getByText('安全輸入 Key',{exact:true}).click();const secret=page.locator('#assistant-secret-form');
  await secret.locator('[name="base_url"]').fill(process.argv[3]);await secret.locator('[name="label"]').fill('Browser safe ref');await secret.locator('[name="secret"]').fill('browser-private-ref-key');
  await page.getByRole('button',{name:'保存安全參照',exact:true}).click();await page.getByText('安全參照已保存',{exact:true}).waitFor();
  assert.equal(await secret.locator('[name="secret"]').inputValue(),'');assert(!(await page.locator('#assistant-prompt').inputValue()).includes('browser-private-ref-key'));
  await page.locator('#assistant-prompt').fill((await page.locator('#assistant-prompt').inputValue())+'\n新增模型和 Provider 備註');
  await page.getByRole('button',{name:'執行工作',exact:true}).click();await page.getByText('已準備模型和備註，請檢查變更。',{exact:true}).waitFor();await page.locator('#assistant-send:enabled').waitFor();
  const before=await (await page.request.get(process.argv[2]+'/api/v1/my-models')).json();assert.equal(before.length,1,'Assistant must wait for explicit confirmation');
  await page.locator('#assistant-actions .item-button').filter({hasText:'ADD_MY_MODEL'}).click();await page.getByRole('button',{name:'確認此變更',exact:true}).click();await page.getByText('變更已套用',{exact:true}).waitFor();
  const after=await (await page.request.get(process.argv[2]+'/api/v1/my-models')).json();assert.equal(after.length,2);
  await page.locator('#assistant-actions .item-button').filter({hasText:'CONTROL_CHANGE'}).click();await page.getByRole('button',{name:'確認此變更',exact:true}).click();await page.getByText('變更已套用',{exact:true}).waitFor();
  await page.locator('#assistant-actions .item-button').filter({hasText:'CONTROL_CHANGE'}).click();await page.getByRole('button',{name:'Undo 此變更',exact:true}).click();await page.getByText('已還原；歷史保留',{exact:true}).waitFor();
  const thread=await page.locator('#assistant-thread').inputValue();assert(thread);
  await page.screenshot({animations:'disabled',path:'e2e/screenshots/assistant-375-light.png',fullPage:true});
  await page.reload();await page.getByText('App Assistant',{exact:true}).click();await page.locator('#assistant-thread option').nth(1).waitFor({state:'attached'});await page.locator('#assistant-thread').selectOption(thread);
  await page.getByText('已準備模型和備註，請檢查變更。',{exact:true}).waitFor();
  await page.emulateMedia({colorScheme:'dark'});await page.evaluate(()=>document.documentElement.style.fontSize='200%');assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'Assistant large font overflow');
  await page.screenshot({animations:'disabled',path:'e2e/screenshots/assistant-375-large-dark.png',fullPage:true});assert.deepEqual(errors,[]);
  console.log('Assistant Browser: configure own model, safe secret reference, native multi-tool loop, real shared membership confirmation, metadata Undo, persisted history, 375px/200% passed.');
 }catch(e){await page.screenshot({path:'e2e/screenshots/assistant-failure.png',fullPage:true}).catch(()=>{});throw e;}finally{await browser.close();}
})().catch(e=>{console.error(e);process.exit(1);});
