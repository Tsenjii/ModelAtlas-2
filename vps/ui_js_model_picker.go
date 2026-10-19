package main

// Display-only projection of joined, enabled scopes. Confirmation revalidates
// against the current target list and uses the existing change handler.
const jsModelPicker = `
let modelPickerReady=false,pickerRows=[],pickerCursor="",pickerLimit=100,pickerComposing=false;
function pickerFold(value){return String(value).normalize("NFKD").replace(/\p{M}/gu,"").toLowerCase();}
function pickerIdentity(m){
 const provider=providers.find(p=>p.id===m.provider_id),account=accounts.find(a=>a.provider_id===m.provider_id&&a.id===m.account_id),credential=credentials.find(c=>c.provider_id===m.provider_id&&c.account_id===m.account_id&&c.id===m.credential_id);
 return {provider:provider?.name||m.provider_id,account:account?.label||m.account_id,credential:credential?.label||m.credential_id};
}
function pickerAvailable(){const values=new Set([...$("target").options].map(o=>o.value));return myModels.filter(m=>m.state==="ACTIVE"&&m.enabled&&operationLabels[targetOperation(m)]&&values.has(key(m)));}
function pickerMatches(m,terms){
 const identity=pickerIdentity(m),metadata=modelRecord(m)?.metadata||{},aliases=Array.isArray(metadata.aliases)?metadata.aliases:[];
 const fields=[m.model_id,modelDisplayName(m),m.provider_id,m.account_id,m.credential_id,m.group,m.endpoint,targetOperation(m),operationLabels[targetOperation(m)],identity.provider,identity.account,identity.credential,metadata.canonical_id,metadata.vendor,...aliases].filter(v=>typeof v==="string").map(pickerFold);
 return terms.every(term=>fields.some(value=>value.includes(term)));
}
function syncModelPickerButton(){
 const selected=pickerAvailable().find(m=>key(m)===$("target").value),button=$("model-picker-open");
 $("model-picker-title").textContent=selected?modelDisplayName(selected):"選擇模型";button.disabled=busy||!modelPickerReady;if(!modelPickerReady)$("model-picker-title").textContent="正在讀取模型…";
 button.title=selected?modelDisplayName(selected)+" · "+pickerIdentity(selected).provider+" · "+connectionLabel(selected)+" · "+selected.endpoint:"選擇模型（⌘ / Ctrl K）";
 if(busy&&$("model-picker").open)$("model-picker").close();
 if($("model-picker").open)renderModelPicker(true);
}
function pickerMarkCursor(){
 const rows=[...$("model-picker-results").querySelectorAll("[data-scope-value]")];
 for(const row of rows){const highlighted=row.dataset.scopeValue===pickerCursor;row.classList.toggle("highlighted",highlighted);row.setAttribute("aria-selected",String(highlighted));}
 const active=rows.find(row=>row.dataset.scopeValue===pickerCursor);$("model-picker-search").setAttribute("aria-activedescendant",active?.id||"");
 $("model-picker-use").disabled=busy||!active;
 return active;
}
function renderModelPicker(refreshProviders=false){
 const available=pickerAvailable(),provider=$("model-picker-provider"),wantedProvider=provider.value;
 if(refreshProviders){provider.replaceChildren();option(provider,"","所有供應商");const ids=new Set(available.map(m=>m.provider_id));for(const p of providers.filter(p=>ids.has(p.id))){const duplicate=providers.filter(x=>ids.has(x.id)&&x.name===p.name).length>1;option(provider,p.id,p.name+(duplicate?" · "+p.id:""));}if([...provider.options].some(o=>o.value===wantedProvider))provider.value=wantedProvider;}
 const text=$("model-picker-search").value,terms=pickerFold(text).trim().split(/\s+/u).filter(Boolean),favorites=$("model-picker-favorites").checked;
 const matching=available.filter(m=>(!provider.value||m.provider_id===provider.value)&&(!favorites||m.favorite)&&pickerMatches(m,terms));
 matching.sort((a,b)=>Number(key(b)===$("target").value)-Number(key(a)===$("target").value)||Number(!!b.favorite)-Number(!!a.favorite)||modelDisplayName(a).localeCompare(modelDisplayName(b))||key(a).localeCompare(key(b)));
 pickerRows=matching.slice(0,pickerLimit);if(!pickerRows.some(m=>key(m)===pickerCursor))pickerCursor=pickerRows.some(m=>key(m)===$("target").value)?$("target").value:(pickerRows[0]?key(pickerRows[0]):"");
 const box=$("model-picker-results");box.replaceChildren();pickerRows.forEach((m,index)=>{
  const value=key(m),identity=pickerIdentity(m),row=el("button",undefined,"model-choice"),mark=el("span",undefined,"identity-avatar"),body=el("span",undefined,"model-choice-body");
  row.type="button";row.id="model-choice-"+index;row.tabIndex=-1;row.dataset.scopeValue=value;row.setAttribute("role","option");
  mark.append(icon(/IMAGE/.test(targetOperation(m))?"image":/AUDIO|SPEECH/.test(targetOperation(m))?"audio":"models"));
  const title=el("strong",modelDisplayName(m));if(m.favorite){const star=icon("star");star.classList.add("favorite-mark");title.append(star);}
  body.append(title,el("span",identity.provider+" · "+identity.account+" / "+identity.credential+(m.group?" / "+m.group:" / 未指定群組")),el("small",(operationLabels[targetOperation(m)]||targetOperation(m))+" · "+m.endpoint));
  if(modelDisplayName(m)!==m.model_id)body.append(el("small",m.model_id));row.append(mark,body);
  if(value===$("target").value){const check=icon("check");check.classList.add("model-choice-current");row.append(check);}
  row.addEventListener("click",()=>choosePickerScope(value));box.append(row);
 });
 if(!matching.length){const empty=el("div",undefined,"model-picker-empty");empty.append(icon("search"),el("h3",available.length?"沒有符合的連線":"尚未加入模型"),el("p",available.length?"試試模型、供應商、帳戶、Key 或端點，或清除篩選。":"到 Models 的 Catalog 加入要使用的模型。"));if(!available.length)action(empty,"前往 Models",()=>{$("model-picker").close();show("models");workspace("models","models-catalog",true);});box.append(empty);}
 $("model-picker-count").textContent=matching.length+" / "+available.length+" 個連線";$("model-picker-reset").hidden=!text&&!provider.value&&!favorites;$("model-picker-more").hidden=matching.length<=pickerRows.length;pickerMarkCursor();
}
function choosePickerScope(value){
 if(busy||!pickerAvailable().some(m=>key(m)===value)){renderModelPicker(true);return;}
 const changed=$("target").value!==value;$("model-picker").close();if(!changed)return;
 $("target").value=value;$("target").dispatchEvent(new Event("change",{bubbles:true}));
}
function openModelPicker(){
 if(!modelPickerReady||busy||document.querySelector("dialog[open]"))return;
 show("playground");workspace("playground","work-chat");pickerLimit=100;pickerCursor=$("target").value;
 $("model-picker-search").value="";$("model-picker-provider").value="";$("model-picker-favorites").checked=false;
 renderModelPicker(true);$("model-picker").showModal();$("model-picker-search").setAttribute("aria-expanded","true");$("model-picker-search").focus();
}
function bindModelPicker(){
 const dialog=$("model-picker"),input=$("model-picker-search");$("model-picker-open").addEventListener("click",openModelPicker);$("model-picker-close").addEventListener("click",()=>dialog.close());
 const filter=()=>{pickerLimit=100;renderModelPicker();};input.addEventListener("input",filter);$("model-picker-provider").addEventListener("change",filter);$("model-picker-favorites").addEventListener("change",filter);
 $("model-picker-reset").addEventListener("click",()=>{input.value="";$("model-picker-provider").value="";$("model-picker-favorites").checked=false;filter();input.focus();});
 $("model-picker-more").addEventListener("click",()=>{pickerLimit+=100;renderModelPicker();});$("model-picker-use").addEventListener("click",()=>{if(pickerCursor)choosePickerScope(pickerCursor);});
 input.addEventListener("compositionstart",()=>pickerComposing=true);input.addEventListener("compositionend",()=>pickerComposing=false);
 input.addEventListener("keydown",event=>{
  if(event.isComposing||event.keyCode===229||pickerComposing||event.altKey||event.ctrlKey||event.metaKey)return;
  if(event.key==="Enter"){event.preventDefault();if(pickerCursor)choosePickerScope(pickerCursor);return;}
  if(!["ArrowUp","ArrowDown"].includes(event.key))return;event.preventDefault();if(!pickerRows.length)return;
  const index=pickerRows.findIndex(m=>key(m)===pickerCursor),next=Math.max(0,Math.min(pickerRows.length-1,index+(event.key==="ArrowDown"?1:-1)));pickerCursor=key(pickerRows[next]);pickerMarkCursor()?.scrollIntoView({block:"nearest"});
 });
 dialog.addEventListener("cancel",event=>{if(pickerComposing)event.preventDefault();});dialog.addEventListener("close",()=>{pickerComposing=false;input.setAttribute("aria-expanded","false");$("model-picker-open").focus({preventScroll:true});});
}
`
