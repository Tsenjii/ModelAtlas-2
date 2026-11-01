const assert=require('node:assert/strict');

// Exercise the visible picker; the hidden select remains the existing scope
// owner, never an automation shortcut for changing it.
async function chooseTarget(page,value){
 const selection=JSON.parse(value);
 await page.getByRole('button',{name:'選擇模型',exact:true}).click();
 await page.locator('#model-picker-search').fill(selection.model_id);
 await page.waitForFunction(v=>[...document.querySelectorAll('#model-picker-results [data-scope-value]')].some(n=>n.dataset.scopeValue===v),value);
 const choices=page.locator('#model-picker-results [data-scope-value]');
 let found=false;
 for(let i=0;i<await choices.count();i++){
  const choice=choices.nth(i);
  if(await choice.getAttribute('data-scope-value')===value){await choice.click();found=true;break;}
 }
 assert(found,'Requested exact scope was not available in the visible picker');
 await page.locator('#model-picker').waitFor({state:'hidden'});
 await page.waitForFunction(v=>document.querySelector('#target').value===v,value);
}
module.exports={chooseTarget};
