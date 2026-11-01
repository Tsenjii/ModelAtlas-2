const {test,before,after}=require('node:test');
const assert=require('node:assert/strict');
const {setTimeout:delay}=require('node:timers/promises');
const fs=require('node:fs'),path=require('node:path');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const {waitForAsync}=require('./async-wait-helper.cjs');
let browser;
before(async()=>{browser=await chromium.launch({headless:true});});
after(async()=>{await browser?.close();});
async function pageFor(t){const page=await browser.newPage();t.after(()=>page.close());await page.goto('about:blank');return page;}

test('awaits actual async results and keeps only one predicate in flight',async t=>{
  const page=await pageFor(t);await page.evaluate(()=>{window.calls=0;window.active=0;window.peak=0;});
  const value=await waitForAsync(page,async expected=>{window.active++;window.peak=Math.max(window.peak,window.active);try{await new Promise(r=>setTimeout(r,10));return ++window.calls===expected;}finally{window.active--; }},3,{timeout:30000,polling:5});
  assert.equal(value,true);assert.deepEqual(await page.evaluate(()=>[window.calls,window.peak]),[3,1]);
});
test('false conditions time out and stop polling after the deadline',async t=>{
  const page=await pageFor(t);await page.evaluate(()=>window.calls=0);
  await assert.rejects(waitForAsync(page,async()=>{window.calls++;return false;},undefined,{timeout:250,polling:5}),{name:'TimeoutError'});
  const count=await page.evaluate(()=>window.calls);assert(count>0);await delay(40);assert.equal(await page.evaluate(()=>window.calls),count);
});
test('a hung browser Promise is bounded and late resolution cannot repoll',async t=>{
  const page=await pageFor(t);await page.evaluate(()=>{window.calls=0;window.pending=new Promise(resolve=>window.release=resolve);});
  await assert.rejects(waitForAsync(page,async()=>{window.calls++;await window.pending;return false;},undefined,{timeout:250,polling:5}),{name:'TimeoutError'});
  await page.evaluate(()=>window.release());await delay(40);assert.equal(await page.evaluate(()=>window.calls),1);
});
test('browser exceptions propagate without waiting for the deadline',async t=>{
  const page=await pageFor(t);
  await assert.rejects(waitForAsync(page,async()=>{throw new Error('controlled async condition error');}),/controlled async condition error/);
});
test('the host deadline works while the page clock is frozen',async t=>{
  const page=await pageFor(t);await page.clock.install({time:new Date('2026-10-26T00:00:00Z')});await page.clock.pauseAt(new Date('2026-10-26T00:00:00Z'));
  await assert.rejects(waitForAsync(page,async()=>false,undefined,{timeout:250,polling:5}),{name:'TimeoutError'});
});
test('closing a page rejects a pending wait and leaves no later poll',async t=>{
  const page=await pageFor(t);await page.evaluate(()=>window.calls=0);
  const waiting=waitForAsync(page,()=>{window.calls++;return false;},undefined,{polling:1000});
  const rejected=assert.rejects(waiting,/closed/);await page.waitForFunction(()=>window.calls===1);await page.close();await rejected;
});
test('invalid or unbounded timer settings are rejected',async t=>{
  const page=await pageFor(t);
  for(const value of [0,-1,Infinity,NaN])for(const key of ['timeout','polling'])await assert.rejects(waitForAsync(page,async()=>true,undefined,{[key]:value}),{name:'TypeError'});
});
test('all nineteen original async conditions retain awaited polling',()=>{
  const counts={'extraction.cjs':3,'provider-forms.cjs':2,'model-connections.cjs':1,'model-picker.cjs':1,'catalog-experience.cjs':1,'browser.cjs':2,'mac-alignment.cjs':3,'detail-workspace.cjs':2,'research-aa.cjs':1,'workspace.cjs':2,'workspace-chunks.cjs':1};
  for(const [name,count]of Object.entries(counts)){const source=fs.readFileSync(path.join(__dirname,name),'utf8');assert(!/page\.waitForFunction\(\s*async/.test(source),name+' reintroduced a Promise-truthiness wait');assert((source.match(/waitForAsync\(page,\s*async/g)||[]).length>=count,name+' lost an original awaited condition');}
});
