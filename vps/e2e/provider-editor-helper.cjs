// Exercises the same visible sheet launchers and Close control as a user.
async function closeProviderEditor(page){if(await page.locator('#provider-editor').isVisible())await page.locator('#provider-editor-close').click();}
async function openProviderForm(page,id){
 const form=page.locator('#provider-'+id+'-form');
 if(['key','account','rotate'].includes(id)){
  if(!await form.evaluate(n=>n.closest('#provider-editor')!==null)){
   await closeProviderEditor(page);await page.locator('[data-provider-editor="provider-'+id+'-form"]').first().click();
  }
  if(id==='key'){const advanced=form.getByText('來源與進階設定',{exact:true}).locator('..');if(!await advanced.evaluate(n=>n.open))await advanced.locator(':scope > summary').click();}
 }else{await closeProviderEditor(page);const d=form.locator('..');if(!await d.evaluate(n=>n.open))await d.locator(':scope > summary').click();if(id==='config'){const advanced=form.getByText('進階 JSON 設定',{exact:true}).locator('..');if(!await advanced.evaluate(n=>n.open))await advanced.locator(':scope > summary').click();}}
}
module.exports={openProviderForm,closeProviderEditor};
