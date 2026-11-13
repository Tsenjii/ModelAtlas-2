package main

const jsViews = `
const settingsEdits=new Set();let settingsEditVersion=0;
function renderSettingsForm(){
 const form=$("settings-form"),values={performance_window:settings.performance_window||"7d",timezone:settings.timezone||"Asia/Taipei",notifications:!!settings.notifications,background_enabled:!!settings.background?.enabled,background_minutes:settings.background?.interval_minutes||60,background_scopes:settings.background?.max_scopes||2,quota_remaining_below:settings.notification_rules?.quota_remaining_below??100};
 notificationRuleNames.forEach(k=>values[k]=!!settings.notification_rules?.[k]);
 for(const [name,value]of Object.entries(values)){if(settingsEdits.has(name))continue;const input=form.elements.namedItem(name);if(input.type==="checkbox")input.checked=value;else input.value=value;}
}
async function refreshWorkspaceSnapshot(requested){
 if(liteTool&&!["models","providers"].includes(liteTool))return refreshLiteToolSnapshot(requested);
 const version=++refreshVersion,readController=new AbortController(),opts={signal:readController.signal};
 const catalogOnly=liteTool==="models"&&toolQuery.get("tab")!=="batch"&&toolQuery.get("tab")!=="retained";
 const catalog=liteTool?api("/model-catalog"+(catalogOnly?"?memberships=1":""),opts):apiPages("/models",opts).then(models=>({models}));
 const members=catalogOnly?null:liteTool?apiPages("/model-memberships",opts,400):apiPages("/my-models?all=true",opts);
 const reads=[api("/providers",opts),api("/accounts",opts),api("/credentials",opts),catalog,members,api("/settings",opts),api("/endpoints",opts)];
 let values;try{values=await Promise.all(reads);}catch(error){readController.abort();await Promise.allSettled(reads);throw error;}
 if(version!==refreshVersion||requested!==workspaceRefreshRequested)return;
 const [nextProviders,nextAccounts,nextCredentials,index,nextMembers,nextSettings,nextEndpoints]=values;
 providers=nextProviders;accounts=nextAccounts;credentials=nextCredentials;models=index.models;myModels=nextMembers??index.memberships??[];settings=nextSettings;endpointProfiles=nextEndpoints;
 workspaceHasData=true;workspaceReadStatus("loading");renderSettingsForm();
 modelCatalogIndex=new Map(models.map(m=>[JSON.stringify([m.provider_id,m.id]),m]));modelSearchIndex=new WeakMap();catalogIndexes();
 if(liteTool){
  if(liteTool==="providers"||currentPane==="providers"){refreshAccountCheckins();renderProviders();}
  if(!catalogOnly){modelFilterOptions();renderModels();}
  renderCatalog();
  if($("detail").open&&$("detail").dataset.kind==="provider")await $("detail-content").providerReload?.();
  if($("detail").open&&$("detail").dataset.kind==="model")$("detail-content").modelConnectionsReload?.();
  return;
 }
 refreshAccountCheckins();modelFilterOptions();renderProviders();renderModels();renderCatalog();assistantTargets();
 const oldTarget=$("target").value;$("target").replaceChildren();myModels.filter(m=>m.state==="ACTIVE"&&m.enabled&&operationLabels[targetOperation(m)]).forEach(m=>option($("target"),key(m),modelDisplayName(m)+" · "+operationLabels[targetOperation(m)]+" · "+(providers.find(p=>p.id===m.provider_id)?.name||m.provider_id)+" / "+connectionLabel(m)));if([...$("target").options].some(o=>o.value===oldTarget))$("target").value=oldTarget;else if(!oldTarget&&providers.length===1&&providers[0].id==="builtin-kilo-anonymous"){const initial=[...$("target").options].find(o=>JSON.parse(o.value).model_id==="nvidia/nemotron-3.5-lightning:free");if(initial)$("target").value=initial.value;}
 if($("detail").open&&$("detail").dataset.kind==="provider")await $("detail-content").providerReload?.();if($("detail").open&&$("detail").dataset.kind==="model")$("detail-content").modelConnectionsReload?.();modelPickerReady=true;syncModelPickerButton();syncNativeShell();const target=$("target").value;if(oldTarget!==target||!composerDraftOwner)prepareComposerSelection();await sessionSelectRefresh();if(version!==refreshVersion)return;
 if(oldTarget!==target)resetMediaDraft();updatePlayground();
 if(!busy){restoreComposerDraft();await loadChat();}
}
function renderProviders(){
 const q=$("provider-search").value.toLowerCase(),box=$("provider-list"),smart=$("provider-sort").value==="smart";
 const counts=new Map(),accountCounts=new Map(),keyCounts=new Map(),checkinCounts=new Map();
 const increment=(map,id)=>map.set(id,(map.get(id)||0)+1);
 for(const a of accounts)increment(accountCounts,a.provider_id);
 for(const c of credentials)if(c.enabled&&c.role==="INFERENCE")increment(keyCounts,c.provider_id);
 const current=checkinSnapshotCurrent();
 if(current)for(const a of accountCheckins.accounts)if(a.required&&!a.completed_at)increment(checkinCounts,a.provider_id);
 for(const m of myModels){
  if(m.state!=="ACTIVE")continue;
  let entry=counts.get(m.provider_id);
  if(!entry){entry={models:0,free:false,total:0,ranked:0};counts.set(m.provider_id,entry);}
  entry.models++;entry.free||=m.price_state==="FREE_NOW";
  if(smart&&m.enabled){
   const score=modelRanking(m).score;
   if(score!==null){entry.total+=score;entry.ranked++;}
  }
 }
 const rows=providers.filter(p=>{
  if(!(p.name+" "+p.base_url+" "+(p.description||"")).toLowerCase().includes(q))return false;
  if(providerQuick==="free")return counts.get(p.id)?.free;
  if(providerQuick==="pending")return p.config?.requires_daily_checkin&&(!current||checkinCounts.has(p.id));
  if(providerQuick==="models")return counts.has(p.id);
  return true;
 }).sort((a,b)=>{
  if(!smart)return a.name.localeCompare(b.name);
  const x=counts.get(a.id),y=counts.get(b.id);
  if(!x?.ranked||!y?.ranked)return Number(!!y?.ranked)-Number(!!x?.ranked);
  return y.total/y.ranked-x.total/x.ranked;
 });
 box.replaceChildren();
 $("provider-result-count").textContent=rows.length+" 個供應商";$("provider-page-count").textContent=providers.length+" 個供應商 · "+accounts.length+" 個帳戶";
 rows.forEach(p=>{const {body}=collectionItem(box,"provider",p.name,p.description||adapterLabels[p.config?.adapter]||"自訂連線",()=>providerDetail(p));let host=p.base_url;try{host=new URL(p.base_url).host;}catch(e){}body.append(el("small",host));const metrics=el("div",undefined,"metric-line");metricChip(metrics,"模型",String(counts.get(p.id)?.models||0));metricChip(metrics,"帳戶",String(accountCounts.get(p.id)||0));metricChip(metrics,"已啟用 Key",String(keyCounts.get(p.id)||0));if(checkinCounts.has(p.id))metrics.append(el("span",checkinCounts.get(p.id)+" 個帳戶待簽到","state-chip"));body.append(metrics);});
 if(!rows.length)workspaceEmpty(box,providerQuick==="pending"?"目前沒有待簽到的帳戶":providers.length?"沒有符合的供應商":"連接你的第一個 Provider",providerQuick==="pending"?"當日完成紀錄會依設定時區更新。":providers.length?"換個名稱或網址搜尋。":"加入 API 連線後，就能探索模型、開始對話。","provider",providers.length?"清除搜尋":"新增連線",()=>{if(providers.length){$("provider-search").value="";providerQuick="all";document.querySelectorAll("[data-provider-quick]").forEach(n=>n.setAttribute("aria-pressed",String(n.dataset.providerQuick==="all")));renderProviders();}else workspace("providers","providers-new",true);});renderCheckinSummary();
}
const modelSuccessPercent=new Intl.NumberFormat("zh-TW",{style:"percent",maximumFractionDigits:1});
function modelPerformanceValues(model,speed){
 const local=modelConnectionMetrics(model);
 const metrics=el("span",undefined,"model-performance-values");
 const success=local.success===null?null:modelSuccessPercent.format(local.success);
 const samples=local.samples>0?local.samples+" 次":"未實測";
 const values=[
  ["首字等待",speed.ttft===null?null:Math.round(speed.ttft)+" ms"],
  ["生成速度",speed.tps===null?null:Number(speed.tps.toFixed(1))+" tok/s"],
  ["成功率",success,samples]
 ];
 for(const [label,value,caption] of values){
  const cell=el("span",undefined,"model-performance-cell");
  cell.append(el("span",label),el("b",value??"—"));
  if(caption)cell.append(el("small",caption));
  metrics.append(cell);
 }
 return metrics;
}
function renderModels(){
 const box=$("my-models"),global=$("model-scope").value==="GLOBAL";box.replaceChildren();
 const matching=matchingModels(global?models.map(catalogOffer):myModels);$("model-more").hidden=matching.length<=modelDisplayLimit;
 const hasPerformance=!global&&matching.length>0,note=$("model-scan-note");
 box.classList.toggle("has-model-performance",hasPerformance);
 note.hidden=!hasPerformance;
 if(hasPerformance){
  const windowName={"1h":"最近 1 小時","24h":"最近 24 小時","7d":"最近 7 天","30d":"最近 30 天","all":"全部紀錄"}[settings.performance_window||"7d"]||"設定的期間";
  const source=modelMetrics(matching[0]).source;
  note.textContent=source==="我的實測"?"我的實測 · "+windowName:"速度為 "+source+" · 成功率為我的實測（"+windowName+"）";
 }
 matching.slice(0,modelDisplayLimit).forEach(m=>{
  if(global){const original=modelRecord(m);collectionItem(box,"models",original.display_name,original.provider_id+" · Catalog",()=>catalogDetail(original));return;}
  const row=el("div",undefined,"model-row"),check=el("input");check.type="checkbox";check.title="勾選進行管理；比較僅限可用的文字對話";check.checked=selectedCompare.has(key(m));check.setAttribute("aria-label","比較 "+m.model_id+" · "+m.provider_id);check.addEventListener("change",()=>{if(check.checked)selectedCompare.set(key(m),scope(m));else selectedCompare.delete(key(m));updateModelSelection();});const hit=el("label",undefined,"model-select-check");hit.append(check);row.append(hit);
  const op=targetOperation(m),kind=/IMAGE/.test(op)?"image":/AUDIO|SPEECH/.test(op)?"audio":"models",provider=providers.find(p=>p.id===m.provider_id)?.name||m.provider_id,{button,body}=collectionItem(row,kind,modelDisplayName(m),provider+" · "+(operationLabels[op]||op)+" · "+connectionLabel(m),()=>modelDetail(m));
  if(modelDisplayName(m)!==m.model_id)body.append(el("small",m.model_id,"model-id"));
  if(m.favorite){const star=icon("star");star.classList.add("favorite-mark");star.setAttribute("aria-label","已加入最愛");star.removeAttribute("aria-hidden");body.querySelector("strong").prepend(star);}
  if(m.price_state==="FREE_NOW")body.append(el("span","FREE NOW","state-chip"));
  if(!m.enabled)body.append(el("small","連線未就緒"));
  button.classList.add("model-performance-row");
  button.insertBefore(modelPerformanceValues(m,modelMetrics(m)),button.lastElementChild);
  box.append(row);
 });
 if(!matching.length){const any=myModels.some(m=>m.state==="ACTIVE"),filtered=modelFilterIDs.some(id=>{const n=$(id);return n.type==="checkbox"?n.checked:n.value!==(filterDefaults[id]||"");});workspaceEmpty(box,filtered?"沒有符合的模型":any?"目前沒有可顯示的模型":"打造你的模型收藏",filtered?"調整搜尋或篩選條件，再找一次。":"從 Catalog 選擇模型，或手動加入連線。","models",filtered?"清除搜尋與篩選":"探索模型",()=>{if(filtered)resetModelFilters();else workspace("models","models-catalog",true);});}
 const old=$("model-provider").value;$("model-provider").replaceChildren();option($("model-provider"),"","全部");providers.forEach(p=>option($("model-provider"),p.id,p.name));$("model-provider").value=old;modelFilterStatus(matching.length);
}
let chatHistorySession="",chatHistoryStamp=null;
async function loadChat(){
 if(busy)return;restoreComposerDraft();
 if(!$("target").value){chatEmpty(false);chatHistorySession="";chatHistoryStamp=null;chatHistoryReady();return;}
 if(!chatOperation(selectedOperation())||sessionScope!==$("target").value)return;
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
