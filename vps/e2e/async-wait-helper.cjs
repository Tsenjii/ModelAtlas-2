// Playwright 1.56 treats a Promise as truthy in waitForFunction. Await each
// browser result on the host; its deadline also survives a mocked page clock.
async function waitForAsync(page,predicate,arg,{timeout=30000,polling=16}={}) {
  if(!Number.isFinite(timeout)||timeout<=0||!Number.isFinite(polling)||polling<=0)throw new TypeError('A bounded async wait needs positive timeout and polling values');
  let active=true,deadlineTimer,pollTimer,onClose,releasePause=()=>{};
  const timedOut=new Error('Timed out waiting for async browser condition ('+timeout+'ms)');timedOut.name='TimeoutError';
  const deadline=new Promise((_,reject)=>{deadlineTimer=setTimeout(()=>{active=false;reject(timedOut);},timeout);});
  const closed=new Promise((_,reject)=>{onClose=()=>{active=false;reject(new Error('Browser page closed while waiting for async condition'));};page.once('close',onClose);if(page.isClosed())onClose();});
  const poll=async()=>{
    while(active){
      const value=await page.evaluate(predicate,arg);
      if(!active)return;
      if(value)return value;
      await new Promise(resolve=>{releasePause=resolve;pollTimer=setTimeout(resolve,polling);});
    }
  };
  try{return await Promise.race([poll(),deadline,closed]);}
  finally{active=false;clearTimeout(deadlineTimer);clearTimeout(pollTimer);releasePause();page.off('close',onClose);}
}
module.exports={waitForAsync};
