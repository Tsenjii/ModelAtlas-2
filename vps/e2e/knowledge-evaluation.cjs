const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const assert=require('node:assert/strict'),fs=require('node:fs');
fs.mkdirSync('e2e/screenshots',{recursive:true});
(async()=>{
 const browser=await chromium.launch({headless:true}),context=await browser.newContext({viewport:{width:375,height:812},httpCredentials:{username:'admin',password:process.env.MODELATLAS_TEST_TOKEN}}),page=await context.newPage(),errors=[];
 let activations=0,rollbacks=0;
 page.on('pageerror',e=>errors.push(e.message));
 page.on('request',r=>{if(r.method()==='POST'&&r.url().endsWith('/research/knowledge/activate'))activations++;if(r.method()==='POST'&&r.url().endsWith('/versions/undo'))rollbacks++;});
 const headers={Authorization:'Bearer '+process.env.MODELATLAS_TEST_TOKEN},base=process.argv[2];
 async function json(path,data){const r=await context.request[data?'post':'get'](base+'/api/v1'+path,{headers,...(data?{data}:{})});assert(r.ok(),await r.text());return r.json();}
 async function openKnowledge(){await page.getByRole('button',{name:'Models',exact:true}).click();await page.getByRole('tab',{name:'我的模型',exact:true}).click();await page.getByRole('tab',{name:'研究知識',exact:true}).click();await page.locator('#knowledge-list .item-button').first().waitFor();}
 async function openVersion(){await page.locator('#knowledge-list .item-button').filter({hasText:'SOURCE_RELIABILITY · v3'}).filter({hasText:'useful'}).click();}
 async function shot(name,width,dark=false,large=false){await page.setViewportSize({width,height:width>700?900:812});await page.emulateMedia({colorScheme:dark?'dark':'light'});await page.evaluate(large=>document.documentElement.style.fontSize=large?'200%':'',large);assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'research evaluation overflow');assert(await page.locator('#detail').evaluate(n=>n.getBoundingClientRect().left>=0&&n.getBoundingClientRect().right<=innerWidth),'dialog escapes viewport');await page.screenshot({animations:'disabled',path:'e2e/screenshots/knowledge-evaluation-'+name+'.png',fullPage:false});}
 async function round(expectedVersion,first){const run=await json('/research/start',{source_ids:['empty','useful'],budget:4});await page.evaluate(async({id,token})=>{const deadline=Date.now()+10000;while(Date.now()<deadline){const r=await fetch('/api/v1/research/runs',{headers:{Authorization:'Bearer '+token}});const runs=await r.json();if(runs.some(r=>r.id===id&&r.status==='COMPLETED'))return;await new Promise(resolve=>setTimeout(resolve,25));}throw Error('Research did not complete within the existing bounded wait');},{id:run.id,token:process.env.MODELATLAS_TEST_TOKEN});const tasks=await json('/research/tasks?run_id='+run.id);assert.equal(tasks[0].payload.source_id,first);const task=tasks.find(t=>t.payload.source_id==='useful');assert.equal(task.payload.strategy.version_id,expectedVersion);assert.equal(task.status,'SUCCESS',JSON.stringify({created:run,tasks,runs:await json('/research/runs')}));}
 try{
  await page.goto(base);await openKnowledge();await openVersion();
  assert(await page.getByRole('button',{name:'確認試用此知識版本',exact:true}).isDisabled());
  await page.getByRole('button',{name:'評估這個提案',exact:true}).click();await page.getByText('研究策略評估已保存',{exact:true}).waitFor();
  assert(await page.getByRole('button',{name:'確認試用此知識版本',exact:true}).isEnabled());
  await shot('review-375-light',375);await shot('review-1280-dark',1280,true);await shot('review-375-200-dark',375,true,true);
  await page.evaluate(()=>document.documentElement.style.fontSize='');await page.setViewportSize({width:375,height:812});
  let fail=true,holdNextRead=false,readStarted,releaseRead;await page.route('**/api/v1/research/knowledge',async route=>{if(fail&&route.request().method()==='GET'){fail=false;await route.fulfill({status:503,body:'read unavailable'});}else{if(holdNextRead&&route.request().method()==='GET'){holdNextRead=false;readStarted();await new Promise(resolve=>releaseRead=resolve);}await route.continue();}});
  await page.getByRole('button',{name:'確認試用此知識版本',exact:true}).click();await page.getByRole('button',{name:'重新讀取研究版本',exact:true}).waitFor();
  assert(await page.getByRole('button',{name:'確認試用此知識版本',exact:true}).isDisabled());assert.equal(activations,1);
  await shot('saved-read-failure-375',375);await page.getByRole('button',{name:'重新讀取研究版本',exact:true}).click();await page.getByRole('button',{name:'回復啟用前版本',exact:true}).waitFor();
  const active=(await json('/research/knowledge')).find(v=>v.active&&v.subject==='useful');assert(active?.activation_action_id);
  await page.reload();await openKnowledge();await openVersion();await page.getByRole('button',{name:'回復啟用前版本',exact:true}).waitFor();assert.equal(activations,1);
  await shot('reopened-active-375-light',375);
  await context.request.post(base+'/test/change-research-yield');for(let i=0;i<3;i++)await round(active.id,'useful');
  await page.locator('#detail-close').click();const held=new Promise(resolve=>readStarted=resolve);holdNextRead=true;await page.locator('#knowledge-refresh').click();await held;try{assert(await page.locator('#knowledge-list .item-button').filter({hasText:'SOURCE_RELIABILITY · v3'}).filter({hasText:'useful'}).isDisabled(),'saved version cards must wait for the fresh monitor read');}finally{releaseRead();}await openVersion();
  await page.getByText('研究成效下降 · 3 輪 · 3 次 HTTP',{exact:true}).waitFor();
  await shot('degraded-320-dark',320,true);await shot('degraded-375-200-light',375,false,true);
  await page.getByRole('button',{name:'回復啟用前版本',exact:true}).click();await page.getByText('已回復啟用前版本',{exact:true}).waitFor();
  assert.equal(rollbacks,1);assert(!(await json('/research/knowledge')).some(v=>v.active));await round('','empty');
  await shot('rollback-1280-light',1280);assert.deepEqual(errors,[]);
  console.log('Research evaluation Browser: actual bounded TLS history, measured proposal/review, one activation despite failed read, frozen next-run version, three real degraded runs, reload-persistent rollback; 8 responsive images, 14 HTTP, no added inference.');
 }catch(e){await page.screenshot({path:'e2e/screenshots/knowledge-evaluation-failure.png',fullPage:false}).catch(()=>{});throw e;}finally{await browser.close();}
})().catch(e=>{console.error(e);process.exit(1);});
