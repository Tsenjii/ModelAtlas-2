// Real Playwright protocol connection. Each existing flow still creates fresh
// isolated contexts and closes them; disconnecting a client keeps the one
// suite-owned Chromium process alive. No page, request or UI API is mocked.
const real=require(process.env.MODELATLAS_PLAYWRIGHT_REAL_MODULE||'playwright');
const chromium=Object.create(real.chromium);
chromium.launch=async options=>{
 const endpoint=process.env.MODELATLAS_BROWSER_ENDPOINT;
 if(!endpoint)return real.chromium.launch(options);
 if(!options||options.headless!==true||Object.keys(options).some(k=>!['headless','executablePath'].includes(k))||options.executablePath&&options.executablePath!==process.env.CHROME_PATH)throw Error('Shared browser requires the original launch options and selected executable');
 return real.chromium.connect(endpoint);
};
module.exports={...real,chromium};
