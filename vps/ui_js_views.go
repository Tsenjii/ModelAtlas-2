package main

const jsViews = `
const settingsEdits=new Set();let settingsEditVersion=0;
function renderSettingsForm(){
 const form=$("settings-form"),values={performance_window:settings.performance_window||"7d",timezone:settings.timezone||"Asia/Taipei",notifications:!!settings.notifications,background_enabled:!!settings.background?.enabled,background_minutes:settings.background?.interval_minutes||60,background_scopes:settings.background?.max_scopes||2,quota_remaining_below:settings.notification_rules?.quota_remaining_below??100};
 notificationRuleNames.forEach(k=>values[k]=!!settings.notification_rules?.[k]);
 for(const [name,value]of Object.entries(values)){if(settingsEdits.has(name))continue;const input=form.elements.namedItem(name);if(input.type==="checkbox")input.checked=value;else input.value=value;}
}
async function refresh(){
 const version=++refreshVersion,values=await Promise.all([api("/providers"),api("/accounts"),api("/credentials"),apiPages("/models"),apiPages("/my-models?all=true"),api("/settings"),api("/endpoints")]);if(version!==refreshVersion)return;
 [providers,accounts,credentials,models,myModels,settings,endpointProfiles]=values;refreshAccountCheckins();renderSettingsForm();modelCatalogIndex=new Map(models.map(m=>[JSON.stringify([m.provider_id,m.id]),m]));modelFilterOptions();catalogIndexes();renderProviders();renderModels();renderCatalog();assistantTargets();
 const oldTarget=$("target").value;$("target").replaceChildren();myModels.filter(m=>m.state==="ACTIVE"&&m.enabled&&operationLabels[targetOperation(m)]).forEach(m=>option($("target"),key(m),modelDisplayName(m)+" · "+operationLabels[targetOperation(m)]+" · "+(providers.find(p=>p.id===m.provider_id)?.name||m.provider_id)+" / "+connectionLabel(m)));if([...$("target").options].some(o=>o.value===oldTarget))$("target").value=oldTarget;
 if($("detail").open&&$("detail").dataset.kind==="provider")await $("detail-content").providerReload?.();modelPickerReady=true;syncModelPickerButton();const target=$("target").value;await sessionSelectRefresh();if(version!==refreshVersion)return;
 if(oldTarget!==target)resetMediaDraft();updatePlayground();
 if(!busy)await loadChat();
}
function renderProviders(){
 const q=$("provider-search").value.toLowerCase(),box=$("provider-list");box.replaceChildren();
 const rows=providers.slice().sort((a,b)=>{if($("provider-sort").value!=="smart")return a.name.localeCompare(b.name);const x=providerRanking(a),y=providerRanking(b);return x===null||y===null?(x===y?0:x===null?1:-1):y-x;}).filter(p=>(p.name+" "+p.base_url).toLowerCase().includes(q)).filter(p=>providerQuick==="pending"?pendingCheckins(p.id).length>0:providerQuick==="models"?myModels.some(m=>m.provider_id===p.id&&m.state==="ACTIVE"):true);
 $("provider-result-count").textContent=rows.length+" 個供應商";
 rows.forEach(p=>{const count=myModels.filter(m=>m.provider_id===p.id&&m.state==="ACTIVE").length,{body}=collectionItem(box,"provider",p.name,p.description||adapterLabels[p.config?.adapter]||"自訂連線",()=>providerDetail(p));let host=p.base_url;try{host=new URL(p.base_url).host;}catch(e){}body.append(el("small",host));const metrics=el("div",undefined,"metric-line");metricChip(metrics,"模型",String(count));metricChip(metrics,"帳戶",String(accounts.filter(a=>a.provider_id===p.id).length));metricChip(metrics,"已啟用 Key",String(credentials.filter(c=>c.provider_id===p.id&&c.enabled&&c.role==="INFERENCE").length));if(pendingCheckins(p.id).length)metrics.append(el("span",pendingCheckins(p.id).length+" 個帳戶待簽到","state-chip"));body.append(metrics);});
 if(!rows.length)workspaceEmpty(box,providers.length?"沒有符合的供應商":"連接你的第一個 Provider",providers.length?"換個名稱或網址搜尋。":"加入 API 連線後，就能探索模型、開始對話。","provider",providers.length?"清除搜尋":"新增連線",()=>{if(providers.length){$("provider-search").value="";renderProviders();}else workspace("providers","providers-new",true);});
}
function renderModels(){
 const box=$("my-models"),global=$("model-scope").value==="GLOBAL";box.replaceChildren();
 const matching=(global?models.map(catalogOffer):myModels).filter(modelPasses).sort(compareModelSort);$("model-more").hidden=matching.length<=modelDisplayLimit;
 matching.slice(0,modelDisplayLimit).forEach(m=>{
  if(global){const original=modelRecord(m);collectionItem(box,"models",original.display_name,original.provider_id+" · Catalog",()=>catalogDetail(original));return;}
  const row=el("div",undefined,"model-row"),check=el("input");check.type="checkbox";check.title="勾選進行管理；比較僅限可用的文字對話";check.checked=selectedCompare.has(key(m));check.setAttribute("aria-label","比較 "+m.model_id+" · "+m.provider_id);check.addEventListener("change",()=>{if(check.checked)selectedCompare.set(key(m),scope(m));else selectedCompare.delete(key(m));updateModelSelection();});const hit=el("label",undefined,"model-select-check");hit.append(check);row.append(hit);
  const op=targetOperation(m),kind=/IMAGE/.test(op)?"image":/AUDIO|SPEECH/.test(op)?"audio":"models",provider=providers.find(p=>p.id===m.provider_id)?.name||m.provider_id,credential=credentials.find(c=>c.id===m.credential_id&&c.provider_id===m.provider_id&&c.account_id===m.account_id),{body}=collectionItem(row,kind,modelDisplayName(m),provider+" · "+(operationLabels[op]||op)+" · "+(m.group||credential?.label||"預設 Group"),()=>modelDetail(m));
  if(modelDisplayName(m)!==m.model_id)body.append(el("small",m.model_id,"model-id"));
  if(m.favorite){const star=icon("star");star.classList.add("favorite-mark");star.setAttribute("aria-label","已加入最愛");star.removeAttribute("aria-hidden");body.querySelector("strong").prepend(star);}
  if(m.price_state==="FREE_NOW")body.append(el("span","FREE NOW","state-chip"));
  if(!m.enabled)body.append(el("small","連線未就緒"));
  const metric=modelMetrics(m),metrics=el("div",undefined,"metric-line");if(metric.source==="我的實測"&&m.performance?.success_rate!==null&&m.performance?.success_rate!==undefined)metricChip(metrics,"上線率",(m.performance.success_rate*100).toFixed(0)+"%");if(metric.ttft!==null)metricChip(metrics,"TTFT",Math.round(metric.ttft)+" ms");if(metric.tps!==null)metricChip(metrics,"TPS",Number(metric.tps.toFixed(1)).toString());if(metrics.children.length){body.append(metrics,el("div",metric.source+(metric.source==="我的實測"&&Number.isInteger(m.performance?.samples)?" · "+m.performance.samples+" 次請求":""),"source-line"));}box.append(row);
 });
 if(!matching.length){const any=myModels.some(m=>m.state==="ACTIVE"),filtered=modelFilterIDs.some(id=>{const n=$(id);return n.type==="checkbox"?n.checked:n.value!==(filterDefaults[id]||"");});workspaceEmpty(box,filtered?"沒有符合的模型":any?"目前沒有可顯示的模型":"打造你的模型收藏",filtered?"調整搜尋或篩選條件，再找一次。":"從 Catalog 選擇模型，或手動加入連線。","models",filtered?"清除搜尋與篩選":"探索模型",()=>{if(filtered)resetModelFilters();else workspace("models","models-catalog",true);});}
 const old=$("model-provider").value;$("model-provider").replaceChildren();option($("model-provider"),"","全部");providers.forEach(p=>option($("model-provider"),p.id,p.name));$("model-provider").value=old;modelFilterStatus(matching.length);
}
let chatHistorySession="",chatHistoryStamp=null;
async function loadChat(){
 if(busy)return;
 if(!$("target").value){chatEmpty(false);chatHistorySession="";chatHistoryStamp=null;chatHistoryReady();return;}
 if(!chatOperation(selectedOperation()))return;
 const version=++loadVersion,id=$("session").value;
 if(!id){chatEmpty(false);chatHistorySession="";chatHistoryStamp=null;chatHistoryReady();return;}
 try{
  const [history,options]=await Promise.all([api("/messages?session_id="+encodeURIComponent(id)),api("/sessions/settings?session_id="+encodeURIComponent(id))]);
  if(version!==loadVersion||id!==$("session").value||busy)return;
  if(chatSettingsSession!==id||!chatSettingsDirty){$("chat-system").value=options.system;$("chat-max-tokens").value=options.max_tokens;$("chat-streaming").checked=options.streaming;$("tools-json").value=options.tools?.length?JSON.stringify(options.tools,null,2):"";chatSettingsSession=id;chatSettingsDirty=false;}
  $("conversation-title").textContent=sessions.find(s=>s.id===id)?.title||"新對話";
  const stamp=JSON.stringify(history.map(m=>[m.id,m.sample_id,m.request_outcome]));
  if(chatHistorySession===id&&chatHistoryStamp===stamp)return;
  const reading=chatHistorySession===id?chatReadPosition():null;
  chatHistorySession=id;chatHistoryStamp=stamp;$("chat").replaceChildren();
  if(history.length===100){const more=action($("chat"),"讀取更早訊息",()=>olderMessages(id,100,more));more.classList.add("history-more");}
  if(!history.length)chatEmpty(true);
  pendingTools=[];const unresolved=new Map();
  history.forEach(m=>{(m.tool_calls||[]).forEach(c=>unresolved.set(c.id,c));if(m.tool_call_id)unresolved.delete(m.tool_call_id);historyMessage($("chat"),m);});
  pendingTools=[...unresolved.values()];renderToolResults();if(reading)restoreChatReadPosition(reading);else chatHistoryReady();
 }catch(e){notice(e.message,true);}
}
async function loadStatus(){renderSettingsForm();try{const [status,tel,candidates,runs,onboarding]=await Promise.all([api("/status"),api("/telemetry"),api("/research/candidates"),api("/intelligence/history"),api("/onboarding")]);$("status").textContent=status.providers+" 個 Provider · "+status.telemetry+" 次請求";$("telemetry").replaceChildren();tel.slice(0,30).forEach(t=>item($("telemetry"),t.model_id+" · "+t.outcome,t.source+" · "+(t.latency_ms??"—")+" ms · "+new Date(t.created_at).toLocaleString(),()=>requestDetail(t.id)));$("research").textContent="研究線索 "+candidates.length+" 筆";$("sync-history").replaceChildren();runs.slice(0,15).forEach(r=>item($("sync-history"),r.selection.model_id+" · "+r.status,new Date(r.at).toLocaleString()));$("onboarding-list").replaceChildren();onboarding.filter(r=>r.status==="REVIEW").forEach(r=>item($("onboarding-list"),r.provider.name,r.models.length+" 個候選",()=>openReview(r)));await pollNotifications(false);}catch(e){notice(e.message,true);}}
`
