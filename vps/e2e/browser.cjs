const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const assert=require('node:assert/strict');
const fs=require('node:fs');
let lastPage;
fs.mkdirSync('e2e/screenshots',{recursive:true});
(async()=>{
 const browser=await chromium.launch({headless:true,...(process.env.CHROME_PATH?{executablePath:process.env.CHROME_PATH}:{})});
 const context=await browser.newContext({viewport:{width:375,height:812},httpCredentials:{username:'admin',password:process.env.MODELATLAS_TEST_TOKEN}});
 const page=await context.newPage(),errors=[];lastPage=page;page.on('pageerror',e=>errors.push(e.message));await page.goto(process.argv[2]);
 await page.getByRole('button',{name:'Providers',exact:true}).click();await page.getByText('新增 Provider',{exact:true}).click();
 await page.locator('#onboarding-form input[name="name"]').fill('Browser Provider');await page.locator('#onboarding-form input[name="base_url"]').fill(process.argv[3]);await page.locator('#onboarding-form input[name="secret"]').fill('top-secret-example-key');await page.locator('#onboarding-form input[name="model_id"]').fill('m');await page.getByRole('button',{name:'檢查並預覽'}).click();
 await page.locator('#review').waitFor({state:'visible'});await page.locator('#review input[name="model"][value="m"]').check();await page.getByRole('button',{name:'確認並加入'}).click();await page.locator('#review').waitFor({state:'hidden'});
 await page.getByRole('button',{name:'Models',exact:true}).click();await page.locator('#my-models .item-button').first().waitFor();assert.equal(await page.locator('#my-models .item-button').count(),1);
 await page.locator('#my-models .item-button').first().click();await page.getByRole('button',{name:'使用此模型',exact:true}).click();await page.locator('#prompt').fill('Hello');await page.getByRole('button',{name:'傳送',exact:true}).click();await page.getByText('Browser reply',{exact:true}).waitFor();await page.locator('#send:enabled').waitFor();
 await page.getByRole('button',{name:'Models',exact:true}).click();await page.locator('#my-models .item-button').first().click();await page.getByRole('button',{name:'隱藏',exact:true}).click();await page.locator('#detail').waitFor({state:'hidden'});await page.locator('#model-lifecycle').selectOption('HIDDEN');await page.locator('#my-models .item-button').first().waitFor();await page.locator('#my-models .item-button').first().click();await page.getByRole('button',{name:'重新顯示',exact:true}).click();await page.locator('#model-lifecycle').selectOption('ACTIVE');await page.locator('#my-models .item-button').first().waitFor();
 for(const colorScheme of ['light','dark']){await page.emulateMedia({colorScheme});for(const width of [375,1024]){await page.setViewportSize({width,height:812});assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'horizontal overflow');await page.screenshot({path:'e2e/screenshots/models-'+width+'-'+colorScheme+'.png',fullPage:true});}}
 assert.deepEqual(errors,[]);console.log('Browser: onboarding → review → model → real chat → hide/restore; 375/1024px light/dark passed.');await browser.close();
})().catch(async e=>{if(lastPage)await lastPage.screenshot({path:"e2e/screenshots/failure.png",fullPage:true}).catch(()=>{});console.error(e);process.exit(1);});
