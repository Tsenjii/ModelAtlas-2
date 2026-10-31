const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const assert=require('node:assert/strict'),fs=require('node:fs/promises');
(async()=>{
 const browser=await chromium.launch({headless:true}),origin=process.argv[2];
 const context=await browser.newContext({httpCredentials:{username:'admin',password:process.env.MODELATLAS_TEST_TOKEN},viewport:{width:375,height:1000},reducedMotion:'reduce'});
 const page=await context.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message));await fs.mkdir('e2e/screenshots',{recursive:true});
 try{
  await page.goto(origin);await page.waitForFunction(()=>modelPickerReady);await page.getByRole('button',{name:'Models',exact:true}).click();await page.getByRole('tab',{name:'我的模型',exact:true}).click();await page.getByRole('tab',{name:'全球研究',exact:true}).click();
  await page.waitForFunction(()=>researchSources.length===2);
  const form=page.locator('#research-run-form');
  await page.locator('#research-run-sources').selectOption('origin-limited');await form.locator('input[name=budget]').fill('1');await form.getByRole('button',{name:'開始研究',exact:true}).click();
  await page.waitForFunction(()=>researchRuns.some(r=>r.used===1&&r.status==='BUDGET_EXHAUSTED'&&r.summary?.retry_waiting_count===1));
  const first=await page.evaluate(()=>researchRuns[0].id);
  await page.locator('#research-run-sources').selectOption('origin-waiting');await form.locator('input[name=budget]').fill('3');await form.getByRole('button',{name:'開始研究',exact:true}).click();
  await page.waitForFunction(id=>researchRuns.some(r=>r.id!==id&&r.used===0&&r.summary?.origin_waiting_count===1),first);
  const second=await page.evaluate(id=>researchRuns.find(r=>r.id!==id).id,first),card=page.locator('#research-runs .item').first();
  await card.getByText(/等待網站恢復/).waitFor();await card.getByRole('button',{name:'執行紀錄',exact:true}).click();
  const task=page.locator('#research-run-snapshot > details').filter({has:page.locator('summary').filter({hasText:/^SOURCE ·/})});await task.locator('summary').first().click();
  const panel=page.locator('.research-origin-wait-panel');await panel.getByRole('heading',{name:'等待網站恢復',exact:true}).waitFor();await panel.getByText('https://provider.example',{exact:true}).waitFor();await panel.getByText(/等待期間不扣 HTTP 額度/).waitFor();
  const tasks=await (await context.request.get(origin+'/api/v1/research/tasks?run_id='+second)).json();assert.equal(tasks[0].attempts,0,'admission wait counted as an attempt');assert(!tasks[0].result.retry,'another task failure became this task failure');
  await page.waitForFunction(()=>!document.querySelector('#notice').classList.contains('notice-visible'));
  for(const width of [320,375,1280])for(const theme of ['light','dark']){
   await page.setViewportSize({width,height:1000});await page.evaluate(theme=>{document.documentElement.dataset.theme=theme;document.documentElement.style.fontSize='100%';},theme);await panel.scrollIntoViewIfNeeded();
   assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),`${width}/${theme} page overflow`);assert(await panel.evaluate(n=>n.scrollWidth<=n.clientWidth),`${width}/${theme} origin wait overflow`);
   await page.locator('#detail').screenshot({path:`e2e/screenshots/research-origin-${width}-${theme}.png`,animations:'disabled'});
  }
  await page.setViewportSize({width:375,height:1000});await page.evaluate(()=>document.documentElement.style.fontSize='200%');await panel.scrollIntoViewIfNeeded();assert(await panel.evaluate(n=>n.scrollWidth<=n.clientWidth),'200% origin wait overflow');await page.locator('#detail').screenshot({path:'e2e/screenshots/research-origin-375-large.png',animations:'disabled'});await page.evaluate(()=>document.documentElement.style.fontSize='100%');await page.locator('#detail-close').click();
  await card.getByRole('button',{name:'暫停',exact:true}).click();await page.waitForFunction(id=>researchRuns.find(r=>r.id===id)?.status==='PAUSED',second);
  assert.equal((await context.request.post(origin+'/test/advance-origin-clock')).status(),204);
  await card.getByRole('button',{name:'恢復研究',exact:true}).click();await task.locator('summary').first().click();await panel.getByText(/研究已暫停/).waitFor();
  assert.equal((await (await context.request.get(origin+'/api/v1/research/runs')).json()).find(r=>r.id===second).used,0,'paused task sent a request');
  await page.locator('#detail-content').getByRole('button',{name:'確認恢復研究',exact:true}).click();await page.waitForFunction(id=>{const r=researchRuns.find(r=>r.id===id);return r?.status==='COMPLETED'&&r.used===1;},second);
  await card.getByRole('button',{name:'執行紀錄',exact:true}).click();await task.locator('summary').first().click();assert.equal(await panel.count(),0,'completed task kept a stale origin warning');
  await page.locator('#detail').screenshot({path:'e2e/screenshots/research-origin-recovered-375.png',animations:'disabled'});
  const finalTasks=await (await context.request.get(origin+'/api/v1/research/tasks?run_id='+second)).json();assert.equal(finalTasks[0].attempts,1);assert.equal(finalTasks[0].status,'SUCCESS');
  for(const button of await page.locator('#detail button:visible').all()){const box=await button.boundingBox();assert(box.height>=48,'48px recovery action');}
  assert.deepEqual(errors,[]);console.log('PASS: genuine UI/TLS shared origin cooldown across runs, zero HTTP/attempt/failure charge while waiting, pause beyond remote deadline and explicit recovery, 320/375/1280 light/dark and 200%, 8 screenshots.');
 }catch(error){await page.screenshot({path:'e2e/screenshots/research-origin-failure.png',fullPage:true}).catch(()=>{});throw error;}finally{await context.close();await browser.close();}
})().catch(error=>{console.error(error);process.exit(1)});
