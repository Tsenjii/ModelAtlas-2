const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const assert=require('node:assert/strict'),fs=require('node:fs/promises');
(async()=>{
 const browser=await chromium.launch({headless:true}),origin=process.argv[2];
 const context=await browser.newContext({httpCredentials:{username:'admin',password:process.env.MODELATLAS_TEST_TOKEN},viewport:{width:375,height:1000},reducedMotion:'reduce'});
 const page=await context.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message));await fs.mkdir('e2e/screenshots',{recursive:true});
 try{
  await page.goto(origin);await page.waitForFunction(()=>modelPickerReady);await page.getByRole('button',{name:'Models',exact:true}).click();await page.getByRole('tab',{name:'我的模型',exact:true}).click();await page.getByRole('tab',{name:'全球研究',exact:true}).click();
  await page.waitForFunction(()=>researchSources.length===1);
  const form=page.locator('#research-run-form');await page.locator('#research-run-sources').selectOption('browser-retry');await form.locator('input[name=budget]').fill('4');await form.getByRole('button',{name:'開始研究',exact:true}).click();
  await page.waitForFunction(()=>researchRuns.some(r=>r.used===1&&r.summary?.retry_waiting_count===1));
  const firstDeadline=await page.evaluate(()=>researchRuns[0].summary.next_retry_at);
  const runID=await page.evaluate(()=>researchRuns[0].id),card=page.locator('#research-runs .item').first();
  await card.getByText(/等待來源重試/).waitFor();await card.getByRole('button',{name:'執行紀錄',exact:true}).click();
  const task=page.locator('#research-run-snapshot > details').filter({has:page.locator('summary').filter({hasText:/^SOURCE ·/})});await task.locator('summary').first().click();
  const panel=page.locator('.research-retry-panel');await panel.getByText(/來源暫時無法連線（HTTP 429）/).waitFor();assert.equal(await panel.locator('li').count(),1);
  await page.waitForFunction(()=>!document.querySelector('#notice').classList.contains('notice-visible'));
  for(const width of [320,375,1280])for(const theme of ['light','dark']){
   await page.setViewportSize({width,height:1000});await page.evaluate(theme=>{document.documentElement.dataset.theme=theme;document.documentElement.style.fontSize='100%';},theme);await panel.scrollIntoViewIfNeeded();
   assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),`${width}/${theme} page overflow`);assert(await panel.evaluate(n=>n.scrollWidth<=n.clientWidth),`${width}/${theme} recovery overflow`);
   await page.locator('#detail').screenshot({path:`e2e/screenshots/research-retry-${width}-${theme}.png`,animations:'disabled'});
  }
  await page.setViewportSize({width:375,height:1000});await page.evaluate(()=>document.documentElement.style.fontSize='200%');await panel.scrollIntoViewIfNeeded();assert(await panel.evaluate(n=>n.scrollWidth<=n.clientWidth),'200% recovery overflow');await page.locator('#detail').screenshot({path:'e2e/screenshots/research-retry-375-large.png',animations:'disabled'});await page.evaluate(()=>document.documentElement.style.fontSize='100%');await page.locator('#detail-close').click();
  await card.getByRole('button',{name:'暫停',exact:true}).click();await page.waitForFunction(id=>researchRuns.find(r=>r.id===id)?.status==='PAUSED',runID);
  assert.equal((await context.request.post(origin+'/test/advance-retry-clock')).status(),204);
  await card.getByRole('button',{name:'恢復研究',exact:true}).click();await task.locator('summary').first().click();await panel.getByText(/研究已暫停/).waitFor();
  assert.equal((await (await context.request.get(origin+'/api/v1/research/runs')).json()).find(r=>r.id===runID).used,1,'paused task retried after deadline');
  await page.locator('#detail-content').getByRole('button',{name:'確認恢復研究',exact:true}).click();await page.waitForFunction(({id,after})=>{const r=researchRuns.find(r=>r.id===id);return r?.used===2&&r.summary?.next_retry_at>after;},{id:runID,after:firstDeadline});
  await card.getByRole('button',{name:'執行紀錄',exact:true}).click();await task.locator('summary').first().click();await panel.getByText(/來源暫時無法連線（HTTP 502）/).waitFor();assert.equal(await panel.locator('li').count(),2);
  assert.equal((await context.request.post(origin+'/test/advance-retry-clock')).status(),204);
  await page.waitForFunction(id=>researchRuns.find(r=>r.id===id)?.status==='COMPLETED',runID);await page.locator('#detail-content').getByRole('button',{name:'更新執行紀錄',exact:true}).click();await task.locator('summary').first().click();await panel.getByText('來源已恢復連線，暫時失敗的紀錄已保留。',{exact:true}).waitFor();assert.equal(await panel.locator('li').count(),2);assert.equal(await page.locator('#detail-content').getByRole('button',{name:'確認恢復研究',exact:true}).count(),0,'completed successful run advertised an invalid resume');
  await page.locator('#detail').screenshot({path:'e2e/screenshots/research-retry-recovered-375.png',animations:'disabled'});
  for(const button of await page.locator('#detail button:visible').all()){const box=await button.boundingBox();assert(box.height>=48,'48px recovery action');}
  // A failed or late detail refresh keeps the visible result and cannot reopen a closed dialog.
  const taskURL='**/api/v1/research/tasks?run_id='+runID;
  await page.route(taskURL,route=>route.fulfill({status:503,contentType:'text/plain',body:'temporary detail read failure'}));
  await page.locator('#detail-content').getByRole('button',{name:'更新執行紀錄',exact:true}).click();await page.locator('#research-run-problem').filter({hasText:'讀取失敗'}).waitFor();await panel.getByText('來源已恢復連線，暫時失敗的紀錄已保留。',{exact:true}).waitFor();assert.equal(await panel.locator('li').count(),2);await page.unroute(taskURL);
  let arrived,release;const entered=new Promise(r=>arrived=r),hold=new Promise(r=>release=r);
  await page.route(taskURL,async route=>{const response=await route.fetch();arrived();await hold;await route.fulfill({response});});
  await page.locator('#detail-content').getByRole('button',{name:'更新執行紀錄',exact:true}).click();await entered;await page.locator('#detail-close').click();const finished=page.waitForResponse(r=>r.url().includes('/research/tasks?run_id='+runID));release();await finished;await page.evaluate(()=>new Promise(requestAnimationFrame));assert(!await page.locator('#detail').evaluate(n=>n.open),'late refresh reopened a closed dialog');
  assert.deepEqual(errors,[]);console.log('PASS: real UI and TLS 429 -> durable wait / budget -> pause past deadline -> explicit resume -> 502 backoff -> 200 recovery; both failures retained. 320/375/1280 light/dark, 200%, 8 screenshots.');
 }catch(error){await page.screenshot({path:'e2e/screenshots/research-retry-failure.png',fullPage:true}).catch(()=>{});throw error;}finally{await context.close();await browser.close();}
})().catch(error=>{console.error(error);process.exit(1)});
