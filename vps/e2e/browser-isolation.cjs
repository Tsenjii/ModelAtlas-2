const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const assert=require('node:assert/strict');
(async()=>{
 const first=await chromium.launch({headless:true}),context=await first.newContext();
 await context.addCookies([{name:'synthetic-isolation-cookie',value:'fixture',domain:'fixture.example',path:'/'}]);assert.equal((await context.cookies()).length,1);await context.newPage();const concurrent=await first.newContext();assert.deepEqual(await concurrent.cookies(),[]);assert.equal(concurrent.pages().length,0);await concurrent.close();await first.close();assert.equal(first.isConnected(),false);
 const second=await chromium.launch({headless:true}),fresh=await second.newContext();assert.deepEqual(await fresh.cookies(),[]);assert.equal(fresh.pages().length,0);await second.close();
})().catch(error=>{console.error(error);process.exit(1)});
