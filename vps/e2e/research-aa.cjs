const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const assert=require('node:assert/strict'),fs=require('node:fs/promises');
(async()=>{
 const browser=await chromium.launch({headless:true}),origin=process.argv[2];
 const context=await browser.newContext({httpCredentials:{username:'admin',password:process.env.MODELATLAS_TEST_TOKEN},viewport:{width:375,height:1000},reducedMotion:'reduce'});
 const page=await context.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message));await fs.mkdir('e2e/screenshots',{recursive:true});
 try{
  await page.goto(origin);await page.waitForFunction(()=>modelPickerReady);await page.getByRole('button',{name:'Models',exact:true}).click();await page.getByRole('tab',{name:'我的模型',exact:true}).click();await page.getByRole('tab',{name:'全球研究',exact:true}).click();
  await page.getByText('AA 最新有效榜單',{exact:true}).click();await page.locator('#aa-ranking').getByText('1. 上一份模型 1',{exact:true}).waitFor();
  const form=page.locator('#research-run-form');await form.locator('input[name=refresh_aa]').check();await form.locator('input[name=budget]').fill('2');await form.getByRole('button',{name:'開始研究',exact:true}).click();
  await page.waitForFunction(()=>researchRuns.some(r=>r.used===2&&r.status==='BUDGET_EXHAUSTED'&&r.summary?.aa_waiting_count===1));
  const runID=await page.evaluate(()=>researchRuns[0].id),card=page.locator('#research-runs .item').first();
  await page.locator('#aa-ranking').getByText('1. 上一份模型 1',{exact:true}).waitFor();
  await card.getByRole('button',{name:'執行紀錄',exact:true}).click();
  const task=page.locator('#detail-content > details').filter({has:page.locator('summary').filter({hasText:/^AA ·/})});await task.locator('summary').first().click();
  const panel=page.locator('.research-aa-panel');await panel.getByText('已保存 1 / 2 頁 · 2 個已評分模型',{exact:true}).waitFor();await panel.getByText(/第 2 頁暫時無法連線/).waitFor();await panel.getByText(/目前額度已用完/).waitFor();assert.equal(await panel.locator('li').count(),1);
  const partial=await (await context.request.get(origin+'/api/v1/research/tasks?run_id='+runID)).json();assert.equal(partial[0].result.aa_progress.completed_pages,1);assert(!JSON.stringify(partial).includes('credential_stamp'),'local credential revision leaked to UI');assert(!JSON.stringify(partial).includes('remote-private-body'),'remote error body leaked to history');assert.equal((await (await context.request.get(origin+'/api/v1/research/aa')).json()).snapshot.id,'aa-last-good');
  await page.waitForFunction(()=>!document.querySelector('#notice').classList.contains('notice-visible'));
  for(const width of [320,375,1280])for(const theme of ['light','dark']){
   await page.setViewportSize({width,height:1000});await page.evaluate(theme=>{document.documentElement.dataset.theme=theme;document.documentElement.style.fontSize='100%';},theme);await panel.scrollIntoViewIfNeeded();
   assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),`${width}/${theme} page overflow`);assert(await panel.evaluate(n=>n.scrollWidth<=n.clientWidth),`${width}/${theme} AA progress overflow`);
   await page.locator('#detail').screenshot({path:`e2e/screenshots/research-aa-${width}-${theme}.png`,animations:'disabled'});
  }
  await page.setViewportSize({width:375,height:1000});await page.evaluate(()=>document.documentElement.style.fontSize='200%');await panel.scrollIntoViewIfNeeded();assert(await panel.evaluate(n=>n.scrollWidth<=n.clientWidth),'200% AA progress overflow');await page.locator('#detail').screenshot({path:'e2e/screenshots/research-aa-375-large.png',animations:'disabled'});await page.evaluate(()=>document.documentElement.style.fontSize='100%');
  await page.locator('#detail-content').getByLabel('追加 HTTP 預算',{exact:true}).fill('1');await page.locator('#detail-content').getByRole('button',{name:'確認恢復研究',exact:true}).click();
  await page.waitForFunction(id=>researchRuns.find(r=>r.id===id)?.status==='QUEUED',runID);await card.getByText(/等待 AA 分頁恢復/).waitFor();await card.getByRole('button',{name:'暫停',exact:true}).click();await page.waitForFunction(id=>researchRuns.find(r=>r.id===id)?.status==='PAUSED',runID);
  assert.equal((await context.request.post(origin+'/test/advance-aa-clock')).status(),204);
  await card.getByRole('button',{name:'恢復研究',exact:true}).click();await task.locator('summary').first().click();await panel.getByText(/研究已暫停/).waitFor();assert.equal((await (await context.request.get(origin+'/api/v1/research/runs')).json()).find(r=>r.id===runID).used,2,'paused AA task retried');
  await page.locator('#detail-content').getByRole('button',{name:'確認恢復研究',exact:true}).click();await page.waitForFunction(id=>{const r=researchRuns.find(r=>r.id===id);return r?.status==='COMPLETED'&&r.used===3;},runID);
  await card.getByRole('button',{name:'執行紀錄',exact:true}).click();await task.locator('summary').first().click();await panel.getByText('完整榜單已驗證並更新，先前的恢復紀錄已保留。',{exact:true}).waitFor();await panel.getByText('已保存 2 / 2 頁 · 4 個已評分模型',{exact:true}).waitFor();assert.equal(await panel.locator('li').count(),1);await page.locator('#detail').screenshot({path:'e2e/screenshots/research-aa-recovered-375.png',animations:'disabled'});
  for(const button of await page.locator('#detail button:visible').all()){const box=await button.boundingBox();assert(box.height>=48,'48px AA recovery action');}
  const complete=await (await context.request.get(origin+'/api/v1/research/aa')).json();assert.notEqual(complete.snapshot.id,'aa-last-good');assert.equal(complete.snapshot.models.length,4);assert.equal(complete.snapshot.models[0].rank,1);await page.locator('#detail-close').click();
  // Both shipped update buttons enqueue the same durable production runner.
  await page.locator('#aa-sync').click();await page.locator('#detail').waitFor({state:'visible'});await page.waitForFunction(()=>researchRuns.length===2&&researchRuns.every(r=>r.status==='COMPLETED'));await page.locator('#detail-close').click();
  await page.getByRole('button',{name:'設定',exact:true}).click();await page.getByRole('tab',{name:'服務',exact:true}).click();await page.locator('#service-aa').getByRole('button',{name:'更新榜單',exact:true}).click();await page.locator('#detail').waitFor({state:'visible'});
  await page.waitForFunction(async()=>{const rows=await api('/research/runs');return rows.length===3&&rows.every(r=>r.status==='COMPLETED');});
  assert.deepEqual(errors,[]);console.log('PASS: genuine AA TLS page 1 -> page 2 HTTP 429, atomic budgets, persisted partial progress and last-good, explicit budget resume, pause past deadline, page 2-only recovery, both production update buttons, 320/375/1280 light/dark and 200%, 8 screenshots.');
 }catch(error){await page.screenshot({path:'e2e/screenshots/research-aa-failure.png',fullPage:true}).catch(()=>{});throw error;}finally{await context.close();await browser.close();}
})().catch(error=>{console.error(error);process.exit(1)});
