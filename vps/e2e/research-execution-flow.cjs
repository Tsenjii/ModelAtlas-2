const assert=require('node:assert/strict');

async function verifyResearchExecution(page){
  await page.setViewportSize({width:375,height:1000});await page.evaluate(()=>document.documentElement.style.fontSize='100%');
  const root=page.locator('#research-execution-policy'),form=page.locator('#research-execution-form'),field=form.getByLabel('同時研究輪數',{exact:true}),problem=page.locator('#research-execution-problem');
  await root.locator('summary').click();await page.waitForFunction(()=>researchExecutionState?.policy.revision===0&&!researchExecutionLoading);
  assert.equal(await field.inputValue(),'1');
  const shot=async(name,width,theme,large=false)=>{
    await page.setViewportSize({width,height:1000});await page.evaluate(({theme,large})=>{document.documentElement.dataset.theme=theme;document.documentElement.style.fontSize=large?'200%':'100%';},{theme,large});
    await form.getByRole('button',{name:'保存執行上限',exact:true}).evaluate(n=>n.scrollIntoView({block:'center',inline:'nearest',behavior:'instant'}));
    assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'execution page overflow');assert(await root.evaluate(n=>n.scrollWidth<=n.clientWidth),'execution section overflow');
    for(const button of await root.locator('button:visible').all())assert((await button.boundingBox()).height>=48,'execution action below 48px');
    const target=await form.getByRole('button',{name:'保存執行上限',exact:true}).boundingBox(),nav=await page.locator('.bottom-nav').isVisible()?await page.locator('.bottom-nav').boundingBox():null;
    assert(target&&target.y>=0&&target.y+target.height<=1000&&(!nav||target.x>=nav.x+nav.width||target.x+target.width<=nav.x||target.y>=nav.y+nav.height||target.y+target.height<=nav.y),'execution save hidden behind navigation '+JSON.stringify({name,target,nav}));
    const anchor=large?form.locator('legend'):root.locator('summary').first();
    await anchor.evaluate(n=>{n.scrollIntoView({block:'start',inline:'nearest',behavior:'instant'});window.scrollBy(0,-80);});
    const valueBox=await field.boundingBox();assert(valueBox&&valueBox.y>=0&&valueBox.y+valueBox.height<=1000,'execution capture omitted the configured value');
    await page.screenshot({path:'e2e/screenshots/research-execution-'+name+'.png',fullPage:false,animations:'disabled'});
  };
  let writes=0,failRead=false,dropReply=false,releaseReply,committed;
  const arrived=new Promise(resolve=>committed=resolve),gate=new Promise(resolve=>releaseReply=resolve);
  await page.route('**/api/v1/research/execution-policy',async route=>{
    if(route.request().method()==='POST'){
      writes++;
      if(writes===1){const response=await route.fetch();assert.equal(response.status(),200);committed();await gate;failRead=true;await route.fulfill({response});}
      else if(dropReply){dropReply=false;const response=await route.fetch();assert.equal(response.status(),200);await route.abort('connectionfailed');}
      else await route.continue();
    }else if(failRead){failRead=false;await route.fulfill({status:503,contentType:'text/plain',body:'暫時無法讀取執行上限'});}else await route.continue();
  });
  await field.fill('2');await form.getByRole('button',{name:'保存執行上限',exact:true}).click();await arrived;
  assert(await form.getByRole('button',{name:'保存執行上限',exact:true}).isDisabled());
  await page.evaluate(()=>document.querySelector('#research-execution-form').dispatchEvent(new Event('submit',{cancelable:true,bubbles:true})));assert.equal(writes,1);releaseReply();
  await problem.getByText(/讀取失敗，保留上次設定/).waitFor();assert.equal(await field.inputValue(),'2');assert.equal(await page.evaluate(()=>researchExecutionRevision),1);
  await page.waitForFunction(()=>!document.querySelector('#notice').classList.contains('notice-visible'));await shot('375-last-good',375,'light');
  await field.fill('3');await root.getByRole('button',{name:'更新執行設定',exact:true}).click();await page.waitForFunction(()=>!researchExecutionLoading);
  assert.equal(await field.inputValue(),'3');
  await page.evaluate(()=>submit('/research/execution-policy',{revision:1,max_parallel_runs:4}));
  await root.getByRole('button',{name:'更新執行設定',exact:true}).click();await page.waitForFunction(()=>researchExecutionState?.policy.revision===2&&!researchExecutionLoading);
  assert.equal(await field.inputValue(),'3');assert.equal(await page.evaluate(()=>researchExecutionRevision),1,'refresh silently adopted a newer revision for an unfinished draft');
  const stale=page.waitForResponse(r=>r.url().endsWith('/api/v1/research/execution-policy')&&r.request().method()==='POST');await form.getByRole('button',{name:'保存執行上限',exact:true}).click();assert.equal((await stale).status(),409);
  await problem.getByText(/保存尚未確認/).waitFor();assert.equal(await field.inputValue(),'3');
  await root.getByRole('button',{name:'捨棄變更並重讀',exact:true}).click();await page.waitForFunction(()=>researchExecutionRevision===2&&!researchExecutionLoading);assert.equal(await field.inputValue(),'4');
  await field.fill('2');dropReply=true;await form.getByRole('button',{name:'保存執行上限',exact:true}).click();await problem.getByText(/保存尚未確認/).waitFor();assert.equal(writes,4);
  await root.getByRole('button',{name:'更新執行設定',exact:true}).click();await page.waitForFunction(()=>researchExecutionState?.policy.revision===3&&!researchExecutionLoading);
  assert.equal(writes,4,'read-only recovery reposted the unknown save');assert.equal(await page.evaluate(()=>researchExecutionState.policy.max_parallel_runs),2);
  await root.getByRole('button',{name:'捨棄變更並重讀',exact:true}).click();await page.waitForFunction(()=>researchExecutionRevision===3&&!researchExecutionLoading);assert.equal(await field.inputValue(),'2');
  await page.unroute('**/api/v1/research/execution-policy');
  await shot('375-light',375,'light');await shot('320-200-dark',320,'dark',true);await shot('1280-dark',1280,'dark');
  console.log('PASS: execution policy default1, actual save2, no duplicate POST, retained committed value after failed refresh, dirty draft/revision retained across another writer and stale409, GET-only recovery after lost reply, persisted current limit2; original schedule HTTP workload retained, 4 responsive execution views.');
}
module.exports={verifyResearchExecution};
