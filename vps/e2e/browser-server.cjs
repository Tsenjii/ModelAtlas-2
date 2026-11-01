const {chromium}=require(process.env.MODELATLAS_PLAYWRIGHT_REAL_MODULE||'playwright');
(async()=>{
 const server=await chromium.launchServer({headless:true,host:'127.0.0.1',...(process.env.CHROME_PATH?{executablePath:process.env.CHROME_PATH}:{})});
 process.stdout.write(JSON.stringify({endpoint:server.wsEndpoint()})+'\n');
 let closing=false;const close=async()=>{if(closing)return;closing=true;await server.close();};
 process.stdin.resume();process.stdin.on('end',close);process.on('SIGTERM',close);
})().catch(error=>{console.error(error);process.exitCode=1;});
