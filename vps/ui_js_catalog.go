package main

const jsCatalog = `
let catalogOrdered=[],catalogSearchIndex=new Map(),catalogMemberships=new Map(),catalogScopeIndex=new Map(),catalogCredentialIndex=new Map(),manualAdding=false;
const catalogFilterIDs=["catalog-search","catalog-provider","catalog-category","catalog-capability","catalog-membership"];
const catalogID=m=>JSON.stringify([m.provider_id,m.id||m.model_id]);
const credentialChoiceID=c=>JSON.stringify([c.provider_id,c.account_id,c.id]);
function catalogIndexes(){
 const names=new Map(providers.map(p=>[p.id,p.name]));
 catalogOrdered=models.slice().sort((a,b)=>(a.display_name||a.id).localeCompare(b.display_name||b.id)||a.provider_id.localeCompare(b.provider_id)||a.id.localeCompare(b.id));
 catalogSearchIndex=new Map(catalogOrdered.map(m=>[catalogID(m),[m.id,m.display_name,m.provider_id,names.get(m.provider_id),m.metadata?.canonical_id,m.metadata?.vendor,...(m.metadata?.aliases||[])].filter(Boolean).map(normalizedSearch)]));
 catalogMemberships=new Map();catalogScopeIndex=new Map();
 for(const m of myModels){const id=catalogID(m);if(!catalogMemberships.has(id))catalogMemberships.set(id,[]);catalogMemberships.get(id).push(m);catalogScopeIndex.set(key(m),m);}
 catalogCredentialIndex=new Map(credentials.filter(c=>c.enabled&&c.role==="INFERENCE").map(c=>[credentialChoiceID(c),c]));
 const select=$("catalog-provider"),old=select.value;select.replaceChildren();option(select,"","全部供應商");providers.forEach(p=>option(select,p.id,p.name));if([...select.options].some(o=>o.value===old))select.value=old;
 manualConnections();
}
function catalogMatches(m){
 const q=normalizedSearch($("catalog-search").value),joined=(catalogMemberships.get(catalogID(m))||[]).length,meta=m.metadata||{};
 return (!q||(catalogSearchIndex.get(catalogID(m))||[]).some(v=>v.includes(q)||(q.length>=4&&fuzzyNear(v,q))))&&(!$("catalog-provider").value||m.provider_id===$("catalog-provider").value)&&(!$("catalog-category").value||meta.primary_category===$("catalog-category").value)&&(!$("catalog-capability").value||(meta.capabilities||[]).includes($("catalog-capability").value))&&(!$("catalog-membership").value||($("catalog-membership").value==="joined"?joined>0:joined===0));
}
function resetCatalogFilters(){for(const id of catalogFilterIDs)$(id).value="";catalogDisplayLimit=100;renderCatalog();}
function openCatalogForProvider(id){resetCatalogFilters();$("catalog-provider").value=id;renderCatalog();show("models");workspace("models","models-catalog",true);}
function renderCatalog(){
 const box=$("catalog"),matching=catalogOrdered.filter(catalogMatches);box.replaceChildren();
 for(const m of matching.slice(0,catalogDisplayLimit)){
  const provider=providers.find(p=>p.id===m.provider_id)?.name||m.provider_id,{body}=collectionItem(box,"models",m.display_name||m.id,provider,()=>catalogDetail(m));
  body.append(el("small",m.id,"model-id"));const count=(catalogMemberships.get(catalogID(m))||[]).length;
  if(count)body.append(el("span","已加入 "+count+" 個連線","state-chip"));
 }
 $("catalog-result-count").textContent="顯示 "+Math.min(matching.length,catalogDisplayLimit)+" / "+matching.length+" 個模型";
 $("catalog-more").hidden=matching.length<=catalogDisplayLimit;$("catalog-reset").hidden=!catalogFilterIDs.some(id=>$(id).value);
 if(!matching.length)workspaceEmpty(box,models.length?"沒有符合的 Catalog 模型":"Catalog 尚無資料",models.length?"調整供應商、名稱或篩選條件。":"先連接供應商並同步模型，也可以手動加入。","models",models.length?"清除 Catalog 篩選":"連接供應商",()=>{if(models.length)resetCatalogFilters();else{show("providers");workspace("providers","providers-new",true);}});
}
function inferenceEndpoints(c){
 if(!c)return [];const p=providers.find(p=>p.id===c.provider_id);if(!p)return [];
 const base=c.base_url||p.base_url;let origin;try{origin=new URL(base).origin;}catch(e){return [];}
 const rows=endpointProfiles.filter(ep=>{try{return ep.provider_id===c.provider_id&&!!operationLabels[ep.operation]&&new URL(ep.url).origin===origin;}catch(e){return false;}});
 if(base===p.base_url&&!endpointProfiles.some(ep=>ep.provider_id===c.provider_id&&ep.id==="CHAT_COMPLETIONS"))rows.unshift({provider_id:c.provider_id,id:"CHAT_COMPLETIONS",operation:"CHAT_COMPLETIONS",url:base.replace(/\/$/,"")+"/chat/completions"});
 return rows;
}
function credentialChoiceLabel(c){return (providers.find(p=>p.id===c.provider_id)?.name||c.provider_id)+" / "+(accounts.find(a=>a.provider_id===c.provider_id&&a.id===c.account_id)?.label||c.account_id)+" / "+c.label+" / "+(c.group||"預設 Group");}
function chooseInferenceEndpoints(select,c){const previous=select.value;select.replaceChildren();for(const ep of inferenceEndpoints(c))option(select,ep.id,(operationLabels[ep.operation]||ep.operation)+" · "+ep.url);if([...select.options].some(o=>o.value===previous))select.value=previous;}
function catalogSelection(c,modelID,endpoint){return {provider_id:c.provider_id,account_id:c.account_id,credential_id:c.id,model_id:modelID,group:c.group||"",endpoint};}
function rememberCatalogMembership(selection){const member={...selection,state:"ACTIVE"};catalogScopeIndex.set(key(member),member);const id=catalogID(member),rows=catalogMemberships.get(id)||[];if(!rows.some(m=>key(m)===key(member)))rows.push(member);catalogMemberships.set(id,rows);}
function catalogError(box,message){box.replaceChildren();box.hidden=!message;if(message){box.append(el("p",message));box.scrollIntoView({block:"nearest"});}}
function connectionSetup(box,c,provider){
 if(!c){box.append(el("p","尚無已啟用的推論 Key。"));action(box,provider?"新增或啟用 Key":"新增連線",()=>{if(provider)providerDetail(provider,"keys");else{$("detail").close();show("providers");workspace("providers","providers-new",true);}});}
 else{box.append(el("p","此 Key 的網址尚無可用端點。"));action(box,"設定對應端點",()=>providerDetail(providers.find(p=>p.id===c.provider_id),"endpoints"));}
}
function catalogDetail(m){
 const box=modal(m.display_name||m.id),ticket=detailVersion,p=providers.find(p=>p.id===m.provider_id);$("detail").dataset.kind="catalog";
 detailHero(box,"models",p?.name||m.provider_id,m.id);
 const error=el("div",undefined,"detail-state detail-state-error");error.setAttribute("role","alert");error.hidden=true;box.prepend(error);
 box.append(el("p","選擇這次要加入的連線。型錄標示不代表這把 Key 的價格、能力或可用性已確認。","section-description"));
 const select=el("select"),label=el("label","使用的 Key"),endpoint=el("select"),endpointLabel=el("label","端點");select.setAttribute("aria-label","使用的 Key");endpoint.setAttribute("aria-label","端點");
 for(const c of catalogCredentialIndex.values())if(c.provider_id===m.provider_id)option(select,credentialChoiceID(c),credentialChoiceLabel(c));
 label.append(select);endpointLabel.append(endpoint);box.append(label,endpointLabel);
 const state=el("div",undefined,"catalog-connection-state"),selectionSummary=el("details",undefined,"catalog-selection-summary"),selectionRows=el("div"),button=el("button","加入 My Models");selectionSummary.append(el("summary","查看完整連線與網址"),selectionRows);button.type="button";box.append(state,selectionSummary,button);let adding=false;
 const update=()=>{
  const c=catalogCredentialIndex.get(select.value);state.replaceChildren();
  const selected=c&&endpoint.value?catalogSelection(c,m.id,endpoint.value):null,existing=selected&&catalogScopeIndex.get(key(selected)),profile=c&&inferenceEndpoints(c).find(ep=>ep.id===endpoint.value);
  selectionRows.replaceChildren();selectionSummary.hidden=!profile;
  if(profile)detailRows(selectionRows,[["帳戶",accounts.find(a=>a.provider_id===c.provider_id&&a.id===c.account_id)?.label||c.account_id],["Key",c.label],["Group",c.group||"預設 Group"],["用途",operationLabels[profile.operation]||profile.operation],["網址",profile.url]]);
  if(!c||!endpoint.value)connectionSetup(state,c,p);
  else if(existing)state.append(el("p","此連線已加入 · "+({ACTIVE:"使用中",HIDDEN:"已隱藏",ARCHIVED:"已封存"}[existing.state]||existing.state)+"。開啟詳情可管理狀態。"));
  else state.append(el("p","加入後會同步可取得的模型資料；不會自動發送推論。","section-description"));
  button.textContent=existing?"查看已加入的連線":"加入 My Models";button.disabled=adding||!selected;select.disabled=endpoint.disabled=adding;
 };
 select.addEventListener("change",()=>{chooseInferenceEndpoints(endpoint,catalogCredentialIndex.get(select.value));catalogError(error,"");update();});endpoint.addEventListener("change",()=>{catalogError(error,"");update();});
 chooseInferenceEndpoints(endpoint,catalogCredentialIndex.get(select.value));update();
 button.addEventListener("click",async()=>{
  if(adding)return;const c=catalogCredentialIndex.get(select.value);if(!c||!inferenceEndpoints(c).some(ep=>ep.id===endpoint.value)){update();return;}
  const selection=catalogSelection(c,m.id,endpoint.value),existing=catalogScopeIndex.get(key(selection));if(existing){modelDetail(existing);return;}
  adding=true;catalogError(error,"");update();
  try{await submit("/my-models",selection);rememberCatalogMembership(selection);if(detailCurrent(ticket))$("detail").close();try{await refresh();notice("已加入；Intelligence 會自動同步");}catch(e){notice("模型已加入；清單重新載入失敗："+e.message,true);}}
  catch(e){if(detailCurrent(ticket))catalogError(error,"加入失敗："+e.message);}
  finally{adding=false;if(detailCurrent(ticket))update();}
 });
 const members=catalogMemberships.get(catalogID(m))||[];
 if(members.length){const list=el("details"),summary=el("summary","已加入的連線（"+members.length+"）");list.append(summary);for(const member of members)item(list,connectionLabel(member),({ACTIVE:"使用中",HIDDEN:"已隱藏",ARCHIVED:"已封存"}[member.state]||member.state)+" · "+(operationLabels[targetOperation(member)]||member.endpoint),()=>modelDetail(member));box.append(list);}
 factsView(box,"型錄分類與來源",m.metadata);
}
function manualConnections(){
 const select=$("manual-target"),old=select.value;select.replaceChildren();for(const c of catalogCredentialIndex.values())option(select,credentialChoiceID(c),credentialChoiceLabel(c));if([...select.options].some(o=>o.value===old))select.value=old;
 manualEndpoints();
}
function manualEndpoints(){
 const c=catalogCredentialIndex.get($("manual-target").value);chooseInferenceEndpoints($("manual-endpoint"),c);
 $("manual-add").disabled=manualAdding||!c||!$("manual-endpoint").value;
 $("manual-setup").hidden=!!c&&!!$("manual-endpoint").value;
 $("manual-setup").textContent=!c?"先新增或啟用推論 Key":"此 Key 尚無對應端點，前往設定";
}
async function addManualModel(e){
 e.preventDefault();if(manualAdding)return;const form=e.target,c=catalogCredentialIndex.get($("manual-target").value),modelID=form.elements.model_id.value.trim(),displayName=form.elements.display_name.value.trim(),endpoint=$("manual-endpoint").value,error=$("manual-error");
 if(!c||!modelID||!displayName||!inferenceEndpoints(c).some(ep=>ep.id===endpoint)){catalogError(error,"請填寫模型名稱，並選擇完整的 Key 與端點。");return;}
 const selection=catalogSelection(c,modelID,endpoint),existing=catalogScopeIndex.get(key(selection));if(existing){modelDetail(existing);return;}
 manualAdding=true;manualEndpoints();catalogError(error,"");let saved=false;
 try{await submit("/models",{provider_id:c.provider_id,id:modelID,display_name:displayName});saved=true;await submit("/my-models",selection);rememberCatalogMembership(selection);try{await refresh();notice("已加入模型；Intelligence 會自動同步");}catch(e){catalogError(error,"模型已加入；清單重新載入失敗："+e.message);}}
 catch(e){catalogError(error,(saved?"型錄已保存，連線尚未加入：":"加入失敗：")+e.message);}
 finally{manualAdding=false;manualEndpoints();}
}
function bindCatalog(){
 for(const id of catalogFilterIDs)$(id).addEventListener(id==="catalog-search"?"input":"change",()=>{catalogDisplayLimit=100;renderCatalog();});
 $("catalog-reset").addEventListener("click",resetCatalogFilters);$("catalog-more").addEventListener("click",()=>{catalogDisplayLimit+=100;renderCatalog();});
 $("manual-target").addEventListener("change",manualEndpoints);$("manual-endpoint").addEventListener("change",manualEndpoints);$("model-form").addEventListener("submit",addManualModel);
 $("manual-setup").addEventListener("click",()=>{const c=catalogCredentialIndex.get($("manual-target").value);if(c)providerDetail(providers.find(p=>p.id===c.provider_id),"endpoints");else{show("providers");workspace("providers","providers-new",true);}});
}
`
