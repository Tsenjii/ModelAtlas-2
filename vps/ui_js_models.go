package main

const modelSortHTML = `<label>排序<select id="model-sort"><option value="smart">綜合排序</option><option value="name">名稱</option><option value="recent">最近使用</option><option value="new">最近加入</option><option value="strength">模型強度</option><option value="tps">生成速度最快</option><option value="ttft">首字等待最短</option><option value="success">成功率最高</option><option value="latency">延遲</option><option value="quota">額度</option><option value="price">輸入價格</option></select></label>`

const modelControlsHTML = `<div class="toolbar"><label>資料範圍<select id="model-scope"><option value="MY_KEYS">My Models · 我的 Key</option><option value="GLOBAL">Global Catalog</option></select></label></div><label>效能來源<select id="model-performance-source"><option value="local">我的實測</option><option value="provider">Provider 回報</option><option value="external">External 參考</option></select></label><div class="toolbar"><label>Key<select id="model-key"><option value="">全部我的 Key</option></select></label><label>Group<select id="model-group"><option value="">全部 Group</option></select></label></div><div class="toolbar"><label>類別<select id="model-category"><option value="">全部</option><option>CHAT_REASONING</option><option>CODING</option><option>IMAGE</option><option>AUDIO</option><option>EMBEDDING</option><option>RERANK</option><option>SAFETY</option><option>OTHER</option></select></label><label>模態<select id="model-modality"><option value="">全部</option><option>TEXT_INPUT</option><option>TEXT_OUTPUT</option><option>IMAGE_INPUT</option><option>IMAGE_OUTPUT</option><option>AUDIO_INPUT</option><option>AUDIO_OUTPUT</option><option>FILE_INPUT</option></select></label></div><label>Vendor<input id="model-vendor" type="search"></label><div class="toolbar"><label>最低 Context<input id="model-context-min" type="number" min="0"></label><label>最低 TPS<input id="model-tps-min" type="number" min="0" step="any"></label><label>最高 TTFT（ms）<input id="model-ttft-max" type="number" min="0"></label><label>最高延遲（ms）<input id="model-latency-max" type="number" min="0"></label></div><div class="toolbar"><label>成功率 1h 至少（%）<input id="model-success-1h" type="number" min="0" max="100"></label><label>成功率 24h 至少（%）<input id="model-success-24h" type="number" min="0" max="100"></label><label>最少實測次數<input id="model-samples-min" type="number" min="0"></label></div><div class="toolbar"><label>輸入價格上限（USD／1M tokens）<input id="model-input-max" type="number" min="0" step="any"></label><label>輸出價格上限（USD／1M tokens）<input id="model-output-max" type="number" min="0" step="any"></label><label>Cache 價格上限（USD／1M tokens）<input id="model-cache-max" type="number" min="0" step="any"></label></div><label>變更<select id="model-changes"><option value="">全部</option><option value="newly_added">7 天內加入</option><option value="price_changed">價格變更</option><option value="recently_used">7 天內使用</option></select></label><label class="check"><input id="model-usable" type="checkbox">只顯示已啟用且具備認證的連線</label><form id="ranking-form"><details><summary>排序權重</summary><p>強度、速率、額度加權後，再套用實測可靠度；缺資料會標為部分資訊。</p><div class="toolbar"><label>強度<input name="strength" type="number" min="0" max="5" step="0.25" value="1"></label><label>速率<input name="speed" type="number" min="0" max="5" step="0.25" value="1"></label><label>額度<input name="quota" type="number" min="0" max="5" step="0.25" value="1"></label><label>可靠度<input name="reliability" type="number" min="0" max="3" step="0.25" value="1"></label></div><button>保存排序權重</button></details></form>`

const jsModels = `
const notificationRuleNames=["became_free","quota_low","checkin_pending","favorite_recovered","price_changed","outage","recovery"];
async function pollNotifications(deliver=true){const rows=await api("/notifications"),box=$("notification-list");box.replaceChildren();rows.forEach(v=>item(box,v.body,new Date(v.created_at).toLocaleString()+" · "+v.kind));const unread=rows.filter(v=>!v.seen_at);if(deliver&&settings.notifications&&unread.length){if("Notification" in window&&Notification.permission==="granted")unread.slice(0,5).forEach(v=>new Notification("ModelAtlas",{body:v.body,tag:v.id}));await submit("/notifications/seen",{ids:unread.map(v=>v.id)});}}
let modelDisplayLimit=100,catalogDisplayLimit=100;
function normalizedSearch(value){return String(value||"").normalize("NFKD").toLowerCase().replace(/\p{Diacritic}/gu,"").replace(/[\s_./:@-]+/g,"");}
function fuzzyNear(a,b){
 if(Math.abs(a.length-b.length)>2||a.length>128||b.length>128)return false;
 let row=new Uint8Array(b.length+2),next=new Uint8Array(b.length+2);
 for(let j=0;j<=b.length+1;j++)row[j]=j;
 for(let i=1;i<=a.length;i++){
  // Any path that can finish with two edits stays within this band.
  const from=Math.max(1,i-2),to=Math.min(b.length,i+2);
  next[from-1]=from===1?i:3;next[to+1]=3;
  for(let j=from;j<=to;j++)next[j]=Math.min(next[j-1]+1,row[j]+1,row[j-1]+(a[i-1]===b[j-1]?0:1));
  const previous=row;row=next;next=previous;
 }
 return row[b.length]<=2;
}
let modelSearchIndex=new WeakMap();
function modelMatches(m,term){
 if(term===null)return true;
 let values=modelSearchIndex.get(m);
 if(!values){
  const identity=pickerIdentity(m);
  values=[identity.provider,identity.account,identity.credential,m.group,m.endpoint,m.model_id,modelDisplayName(m),m.metadata?.model_id,m.metadata?.canonical_id,m.metadata?.display_name,...(m.metadata?.aliases||[]),...(m.offer?.tags||[])].filter(Boolean).map(normalizedSearch);
  modelSearchIndex.set(m,values);
 }
 return values.some(value=>value.includes(term)||(term.length>=4&&fuzzyNear(value,term)));
}
function knownNumber(value){if(value===null||value===undefined||value==="")return null;const n=Number(value);return Number.isFinite(n)&&n>=0?n:null;}
function meanMetric(value){return knownNumber(value&&typeof value==="object"?value.mean:value);}
function modelMetrics(m,source=$("model-performance-source").value){if(source==="provider"){const f=m.offer?.provider_performance?.facts||{};return {tps:meanMetric(f.tps),ttft:meanMetric(f.ttft_ms),latency:meanMetric(f.latency_ms),source:"Provider 回報"};}if(source==="external"){const f=m.offer?.external_performance||{};return {tps:f.units==="TPS"?knownNumber(f.average_tokens_per_second):null,ttft:f.units==="MS"?knownNumber(f.average_first_token_latency):null,latency:null,source:"External 參考"};}return {tps:meanMetric(m.performance?.tps),ttft:meanMetric(m.performance?.ttft),latency:meanMetric(m.performance?.latency),source:"我的實測"};}
function modelPrice(m,leg){const price=m.offer?.price?.legs;if(price?.unit!=="USD/1K tokens")return null;const n=knownNumber(price[leg]);return n===null?null:n*1000;}
function modelQuota(m){const rpd=knownNumber(m.offer?.quota?.rpd),tpd=knownNumber(m.offer?.quota?.tpd);return rpd===null||tpd===null?null:Math.min(rpd/(rpd+100),tpd/(tpd+1000000));}
function modelRanking(m,metric=modelMetrics(m),w={strength:1,speed:1,quota:1,reliability:1,...settings.ranking_weights}){const strength=knownNumber(m.offer?.strength?.score),values={strength:strength===null?null:Math.min(1,strength/100),speed:metric.tps===null?null:metric.tps/(metric.tps+100),quota:modelQuota(m)};let base=0,used=0,enabled=0;const missing=[];for(const k of ["strength","speed","quota"]){if(w[k]<=0)continue;enabled+=w[k];if(values[k]===null)missing.push(k);else{base+=w[k]*values[k];used+=w[k];}}const reliability=knownNumber(m.performance?.success_rate);if(!m.enabled||used===0)return {score:null,coverage:0,missing:[...missing,"usable"],reliability};return {score:base/used*(reliability===null?1:Math.pow(reliability,w.reliability)),coverage:enabled?used/enabled:0,missing:reliability===null?[...missing,"reliability"]:missing,reliability};}
function numericModelFilter(text,value,max=false,scale=1){if(text==="")return true;const n=knownNumber(value);return n!==null&&(max?n*scale<=Number(text):n*scale>=Number(text));}
function matchingModels(rows){
 const f=Object.fromEntries(modelFilterIDs.map(id=>{
  const input=$(id);return [id.slice(6),input.type==="checkbox"?input.checked:input.value];
 }));
 const search=f.search?normalizedSearch(f.search):null,vendor=normalizedSearch(f.vendor),recent=Date.now()-7*86400000;
 const weights={strength:1,speed:1,quota:1,reliability:1,...settings.ranking_weights},source=f["performance-source"];
 const matching=[];
 for(const m of rows){
  if(f.lifecycle==="RETAINED"?m.state==="ACTIVE":m.state!==f.lifecycle)continue;
  const meta=m.metadata||{};
  if((f.provider&&m.provider_id!==f.provider)||
     (f.key&&[m.provider_id,m.account_id,m.credential_id].join("/")!==f.key)||
     (f.group&&m.group!==(f.group==="__DEFAULT__"?"":f.group))||
     (f.capability&&!(m.capabilities||meta.capabilities||[]).includes(f.capability))||
     (f.modality&&!(meta.modalities||[]).includes(f.modality))||
     (f.category&&meta.primary_category!==f.category)||
     (vendor&&!normalizedSearch(meta.vendor).includes(vendor))||
     (f.price&&m.price_state!==f.price)||(f.favorites&&!m[f.favorites])||(f.usable&&!m.enabled)||
     (f.changes&&(f.changes==="recently_used"?!(Number(m.performance?.last_request_at)>recent):!m.offer?.[f.changes]))||
     !modelMatches(m,search))continue;
  const metric=modelMetrics(m,source);
  if(!numericModelFilter(f["context-min"],meta.context_tokens)||
     !numericModelFilter(f["tps-min"],metric.tps)||
     !numericModelFilter(f["ttft-max"],metric.ttft,true)||
     !numericModelFilter(f["latency-max"],metric.latency,true)||
     !numericModelFilter(f["success-1h"],m.reliability?.["1h"]?.success_rate,false,100)||
     !numericModelFilter(f["success-24h"],m.reliability?.["24h"]?.success_rate,false,100)||
     !numericModelFilter(f["samples-min"],m.performance?.samples)||
     (f["input-max"]&&!numericModelFilter(f["input-max"],modelPrice(m,"input"),true))||
     (f["output-max"]&&!numericModelFilter(f["output-max"],modelPrice(m,"output"),true))||
     (f["cache-max"]&&!numericModelFilter(f["cache-max"],modelPrice(m,"cache"),true)))continue;
  if(f.sort==="smart"){
   const {score,coverage}=modelRanking(m,metric,weights);
   matching.push({m,value:score,coverage});
  }else{
   matching.push({m,value:modelSortValue(m,f.sort,metric)});
  }
 }
 const ascending=["ttft","latency","price"].includes(f.sort);
 matching.sort((a,b)=>{
  if(f.sort==="name")return a.m.model_id.localeCompare(b.m.model_id);
  if(a.value==null||b.value==null)return a.value==b.value?0:a.value==null?1:-1;
  if(f.sort==="smart"&&a.coverage!==b.coverage)return b.coverage-a.coverage;
  return ascending?a.value-b.value:b.value-a.value;
 });
 return matching.map(row=>row.m);
}
function modelSortValue(m,type,metric){
 switch(type){
 case "success":return modelConnectionMetrics(m).success;
 case "strength":return knownNumber(m.offer?.strength?.score);
 case "tps":return metric.tps;
 case "ttft":return metric.ttft;
 case "latency":return metric.latency;
 case "quota":return modelQuota(m);
 case "price":return modelPrice(m,"input");
 case "recent":return knownNumber(m.performance?.last_request_at);
 case "new":return knownNumber(m.offer?.created_at);
 default:return null;
 }
}
function modelFilterOptions(){const f=$("ranking-form");if(f.dataset.dirty!=="true")for(const k of ["strength","speed","quota","reliability"])f.elements[k].value=settings.ranking_weights?.[k]??1;for(const [id,values,label] of [["model-key",credentials.filter(c=>c.role==="INFERENCE").map(c=>[[c.provider_id,c.account_id,c.id].join("/"),(providers.find(p=>p.id===c.provider_id)?.name||c.provider_id)+" / "+c.label]),"全部我的 Key"],["model-group",[...new Set(myModels.map(m=>m.group||"__DEFAULT__"))].map(g=>[g,g==="__DEFAULT__"?"預設 Group":g]),"全部 Group"]]){const select=$(id),old=select.value;select.replaceChildren();option(select,"",label);values.forEach(([v,t])=>option(select,v,t));if([...select.options].some(n=>n.value===old))select.value=old;}}

async function olderMessages(id,offset,button){if(id!==$("session").value||busy)return;pauseChatFollow();const height=$("chat").scrollHeight,scroll=$("chat").scrollTop,version=loadVersion;const rows=await api("/messages?session_id="+encodeURIComponent(id)+"&limit=100&offset="+offset);if(version!==loadVersion||id!==$("session").value||busy)return;const fragment=document.createDocumentFragment();rows.forEach(m=>historyMessage(fragment,m));button.replaceWith(fragment);if(rows.length===100){const wrapper=el("div");const more=action(wrapper,"讀取更早訊息",()=>olderMessages(id,offset+100,more));$("chat").prepend(wrapper);}$("chat").scrollTop=scroll+$("chat").scrollHeight-height;chatLastTop=$("chat").scrollTop;chatFollowButton();}
function catalogOffer(m){return {provider_id:m.provider_id,model_id:m.id,metadata:m.metadata||{},capabilities:m.metadata?.capabilities||[],state:"ACTIVE",enabled:false,price_state:"UNKNOWN",offer:{},performance:{}};}
async function notesEditor(box,subject,value={}){const ticket=detailVersion,form=el("form");box.append(el("h3","備註與標籤"),form);const notes=field(form,"備註","textarea",value.notes||""),tags=field(form,"標籤（逗號分隔）","text",(value.tags||[]).join(","));form.append(el("button","預覽備註變更"));form.addEventListener("submit",async e=>{e.preventDefault();try{const review=await submit("/assistant/actions/preview",{kind:"NOTES",...subject,patch:{notes:notes.value,tags:tags.value.split(",").map(v=>v.trim()).filter(Boolean)}});if(detailCurrent(ticket))assistantAction(review);}catch(e){notice(e.message,true);}});}

function bindModelControls(){bindModelBatch();for(const [id,state] of [["bulk-hide","HIDDEN"],["bulk-archive","ARCHIVED"],["bulk-restore","ACTIVE"]])$(id).addEventListener("click",async()=>{try{assistantAction(await submit("/assistant/actions/preview",{kind:"MODEL_BATCH",selections:[...selectedCompare.values()],patch:{state}}));}catch(e){notice(e.message,true);}});["model-scope","model-sort","model-performance-source","model-key","model-group","model-category","model-modality","model-vendor","model-context-min","model-tps-min","model-ttft-max","model-latency-max","model-success-1h","model-success-24h","model-samples-min","model-input-max","model-output-max","model-cache-max","model-changes","model-usable"].forEach(id=>{$(id).addEventListener("input",()=>{modelDisplayLimit=100;renderModels();});});$("ranking-form").addEventListener("input",()=>$("ranking-form").dataset.dirty="true");$("ranking-form").addEventListener("submit",async e=>{e.preventDefault();try{await submit("/settings",{ranking_weights:Object.fromEntries(Object.entries(data(e.target)).map(([k,v])=>[k,Number(v)]))});e.target.dataset.dirty="false";await refresh();notice("排序權重已保存");}catch(e){notice(e.message,true);}});$("provider-sort").addEventListener("change",renderProviders);$("model-more").addEventListener("click",()=>{modelDisplayLimit+=100;renderModels();});}
`
