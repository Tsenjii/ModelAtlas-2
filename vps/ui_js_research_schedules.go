package main

const researchScheduleHTML = `
<section id="research-schedule-card" class="research-schedules" aria-labelledby="research-schedule-title">
<div class="heading-actions"><div><h3 id="research-schedule-title">持續研究</h3><p>明確啟用後，由目前服務持續讀取公開資料；離開網頁仍會執行。</p></div><button id="research-schedule-new" type="button" class="ghost">新增排程</button></div>
<p id="research-schedule-meter" class="section-description">正在讀取排程…</p>
<p id="research-schedule-problem" role="status" hidden></p>
<button id="research-schedule-refresh" type="button" class="ghost">更新排程</button>
<div id="research-schedule-list" class="cards"></div>
<details id="research-schedule-editor"><summary>排程設定</summary>
<p>保存後預設停用。編輯會結束尚未完成的舊輪次，原始紀錄繼續保留。</p>
<form id="research-schedule-form">
<label>排程名稱<input name="name" required maxlength="120"></label>
<div class="toolbar"><label>間隔（分鐘）<input name="interval_minutes" type="number" value="60" required min="15" max="10080"></label><label>每輪 HTTP 上限<input name="budget" type="number" value="10" required min="1" max="200"></label><label>每日 HTTP 上限<input name="daily_http_limit" type="number" value="30" required min="1" max="2000"></label></div>
<label>排程公開來源<select name="source_ids" id="research-schedule-sources" aria-label="排程公開來源" multiple size="4"></select></label>
<label>排程重驗候選<select name="candidate_ids" id="research-schedule-candidates" multiple size="3"></select></label>
<label>觀測有效期（分鐘）<input name="observation_minutes" type="number" value="1440" min="15" max="10080" required></label>
<label class="check"><input name="search" type="checkbox">多語網路探索（需 Brave Search Key）</label>
<div class="toolbar"><label>每語言最多搜尋頁數<input name="search_pages" type="number" value="1" min="1" max="10" required></label><label>搜尋地區<select name="search_country" aria-label="搜尋地區"><option value="TW">台灣</option><option value="ALL">全球</option><option value="US">美國</option><option value="JP">日本</option><option value="KR">韓國</option><option value="DE">德國</option><option value="FR">法國</option><option value="GB">英國</option><option value="CA">加拿大</option><option value="AU">澳洲</option><option value="BR">巴西</option><option value="IN">印度</option></select></label></div><p class="section-description">每頁最多 10 筆線索；只有搜尋服務明示仍有結果才會續頁。搜尋地區不代表已驗證台灣資格。</p>
<label>探索語言<select name="languages" aria-label="探索語言" multiple size="4"><option value="en" selected>English</option><option value="zh" selected>中文</option><option value="ru">Русский</option><option value="ja">日本語</option><option value="ko">한국어</option><option value="es">Español</option><option value="pt">Português</option><option value="id">Bahasa Indonesia</option></select></label>
<label class="check"><input name="lmspeed_seeds" type="checkbox">使用已保存的 LMSpeed 發現線索</label>
<label class="check"><input name="refresh_aa" type="checkbox">更新 AA 榜單（需 AA Key）</label>
<label class="check"><input name="private_enabled" id="research-schedule-private-enabled" type="checkbox">加入我的模型連線（需要另外確認）</label>
<fieldset id="research-schedule-private-fields" hidden><legend>私人模型研究</legend>
<p>只會使用你逐一選取的模型、帳戶、Key、Group 與端點。保存後仍停用，啟用時會顯示目前來源和 Key 版本供你確認。</p>
<label>排程模型連線<select name="private_selections" id="research-schedule-private-selections" aria-label="排程模型連線" multiple size="4"></select></label>
<label class="check"><input name="minimal_inference" type="checkbox">需要時以最小推論驗證（優先重用有效觀測）</label>
<label>深入驗證連線（選填，最多 8 份）<select name="deeper_selections" id="research-schedule-deeper" multiple size="3"></select></label>
<p>只從上方已選的連線中逐一指定；未選擇就不做深入題目。每份包含一題語意與一題工具格式，先重用有效觀測；工具能力未確認時不送工具題。小型題目結果不證明身份。</p>
<div class="toolbar"><label>每輪推論上限<input name="probe_budget" type="number" value="1" min="1" max="10"></label><label>此排程每日推論上限<input name="daily_inferences" type="number" value="5" min="0" max="2000"></label></div>
<div class="toolbar"><label>此排程每日 token 上限<input name="daily_tokens" type="number" min="0" max="1000000000000"></label><label>此排程每日 USD 上限<input name="daily_usd" type="text" inputmode="decimal"></label></div>
<label class="check"><input name="resume_after_reset" type="checkbox">研究推論額度重設後自動接續此排程</label>
<p>token／USD 留空停用該項限制，0 停止新核准。沿用研究預留額與全域／逐 Key／共用池限制。缺回報、失敗或取消保留預留；中斷的推論不自動重送。預設用盡後須手動接續。</p></fieldset>
<p class="section-description">HTTP 計數包含送出前已保留的次數；遇到中斷仍保留用量。</p>
<div class="toolbar"><button id="research-schedule-save">保存排程</button><button id="research-schedule-reset" type="button" class="ghost">放棄編輯</button></div>
</form></details>
` + researchExecutionHTML + `
<details><summary>全部排程的每日上限</summary><form id="research-schedule-limit-form"><label>全部排程每日 HTTP 上限<input name="daily_http_limit" type="number" value="100" required min="1" max="2000"></label><p>只計算自動排程。每日 UTC 00:00（台灣 08:00）重新計數；同時研究輪數依上方的本機執行上限。模型資料更新和手動研究保有各自的設定。</p><button>保存全部排程上限</button></form></details>
</section>`

const researchScheduleCSS = `
#research-schedule-form{min-width:0;grid-template-columns:minmax(0,1fr)}#research-schedule-form label{overflow-wrap:anywhere}#research-schedule-form select[multiple] option{white-space:normal;overflow-wrap:anywhere}
#research-schedule-form .check{display:grid;grid-template-columns:20px minmax(0,1fr)}#research-schedule-form .check>input{margin:0}
#research-schedule-private-fields{min-width:0;max-width:100%;padding:14px;border:1px solid var(--border);border-radius:12px;overflow-wrap:anywhere}#research-schedule-private-fields select{max-width:100%}

.research-schedules{padding-block:20px;margin-bottom:24px;border-block:1px solid var(--border);min-width:0}.research-schedules .heading-actions{align-items:start}.research-schedules h3{margin-bottom:6px}.research-schedules .heading-actions p{margin:0;color:var(--muted);font-size:.85rem}.research-schedules>.cards{margin-top:16px}.research-schedule-row{min-width:0;padding:16px;border:1px solid var(--border);border-radius:12px}.research-schedule-row h4{margin:0;font-size:1rem;overflow-wrap:anywhere}.research-schedule-row p{margin:8px 0;color:var(--muted);font-size:.85rem}.research-schedule-row .toolbar{margin-top:12px}.research-schedules form{margin:16px 0}.research-schedules select[multiple]{width:100%;min-width:0}.research-schedule-state{display:inline-block;color:var(--ink);font-size:.78rem;margin-block:8px 0;padding:4px 8px;background:var(--surface);border-radius:6px}#research-schedule-problem{color:var(--danger)}
@media(max-width:959px){.research-schedules{padding-block:16px;margin-bottom:16px}.research-schedules .heading-actions{gap:12px}.research-schedules .heading-actions>button{width:100%}.research-schedule-row{padding:16px}.research-schedule-row .toolbar>button{flex:1 1 9rem}}
`

const jsResearchSchedules = `
let researchScheduleState=null,researchScheduleLoading=false,researchScheduleWriting=false,researchScheduleEdit=null,researchScheduleDraftID=crypto.randomUUID(),researchScheduleLimitDirty=false,researchScheduleStamp="",researchScheduleSourcesStamp="";
const researchScheduleStates={OFF:"已停用",NEEDS_REVIEW:"來源或紀錄已變更 · 請確認",WAITING:"等待下次執行",QUEUED:"已排入佇列",RUNNING:"正在研究",PAUSED:"本輪已暫停",INFERENCE_LIMIT:"研究預算已用盡 · 手動接續",INFERENCE_WAIT:"研究預算 · 等待每日重設",DAILY_LIMIT:"每日上限 · 等待重設",INTEGRATION_LIMIT:"整合服務額度 · 等待重設",ROUND_LIMIT:"本輪達上限 · 等待下輪"};
const researchRunStates={QUEUED:"排隊中",RUNNING:"研究中",PAUSED:"已暫停",COMPLETED:"已完成",CANCELLED:"已結束",BUDGET_EXHAUSTED:"已達上限"};
function researchScheduleVisible(){return !document.hidden&&!$("research-workspace").hidden&&$("research-workspace").open&&(!$("models").hidden||!$("settings").hidden&&!$("settings-research").hidden);}
function setResearchScheduleBusy(){const card=$("research-schedule-card");card.setAttribute("aria-busy",String(researchScheduleWriting));card.querySelectorAll("button,input,select").forEach(n=>n.disabled=researchScheduleWriting);$("research-schedule-refresh").disabled=researchScheduleLoading||researchScheduleWriting;setResearchExecutionBusy();}
async function loadResearchSchedules(force=false){
 if(researchScheduleLoading||researchScheduleWriting&&!force)return;researchScheduleLoading=true;$("research-schedule-refresh").disabled=true;
 try{const value=await api("/research/schedules");if(!Array.isArray(value.schedules)||!Number.isInteger(value.daily_http_limit))throw Error("無法辨識排程資料，請重試。");researchScheduleState=value;$("research-schedule-problem").hidden=true;renderResearchSchedules();}
 catch(error){$("research-schedule-problem").textContent=(researchScheduleState?"更新失敗，保留上次排程資料。 ":"尚未取得排程資料。 ")+error.message;$("research-schedule-problem").hidden=false;if(!researchScheduleState)$("research-schedule-meter").textContent="尚未讀取排程；更新後可確認目前狀態。";}
 finally{researchScheduleLoading=false;setResearchScheduleBusy();}
}
function syncResearchScheduleSources(){
 const select=$("research-schedule-sources"),stamp=JSON.stringify(researchSources);if(stamp===researchScheduleSourcesStamp)return;researchScheduleSourcesStamp=stamp;
 const selected=new Set([...select.selectedOptions].map(o=>o.value));select.replaceChildren();researchSources.filter(s=>s.enabled).forEach(s=>{option(select,s.id,s.name);select.lastChild.selected=selected.has(s.id);selected.delete(s.id);});for(const id of selected){option(select,id,id+" · 來源停用或已移除");select.lastChild.selected=true;}
}
function resetResearchScheduleEditor(){
 researchScheduleEdit=null;researchScheduleDraftID=crypto.randomUUID();$("research-schedule-form").reset();$("research-schedule-save").textContent="保存排程";$("research-schedule-editor").open=false;
 syncPrivateScheduleEditor();}
function editResearchSchedule(v=null){
 const form=$("research-schedule-form");researchScheduleEdit=v?{...v}:null;researchScheduleDraftID=v?.id||crypto.randomUUID();form.reset();syncResearchScheduleSources();
 if(v){form.elements.name.value=v.name;form.elements.interval_minutes.value=v.interval_minutes;form.elements.budget.value=v.options.budget;form.elements.search_pages.value=v.options.search_pages||1;form.elements.search_country.value=v.options.search_country||"TW";form.elements.daily_http_limit.value=v.daily_http_limit;for(const name of ["search","lmspeed_seeds","refresh_aa"])form.elements[name].checked=!!v.options[name];const selected=new Set(v.options.source_ids||[]);for(const id of selected)if(![...form.elements.source_ids.options].some(o=>o.value===id))option(form.elements.source_ids,id,id+" · 待確認");for(const o of form.elements.source_ids.options)o.selected=selected.has(o.value);for(const o of form.elements.languages.options)o.selected=(v.languages?.length?v.languages:["en","zh","ru","ja","ko","es","pt","id"]).includes(o.value);}
 $("research-schedule-save").textContent=v?"保存排程修改":"保存排程";$("research-schedule-editor").open=true;form.elements.name.focus();
 if(v?.private){form.elements.private_enabled.checked=true;form.elements.minimal_inference.checked=!!v.options.minimal_inference;form.elements.probe_budget.value=v.options.probe_budget||1;form.elements.daily_inferences.value=v.private.daily_inferences;form.elements.daily_tokens.value=v.private.daily_tokens===null?'':v.private.daily_tokens;form.elements.daily_usd.value=v.private.daily_usd===null?'':v.private.daily_usd;form.elements.resume_after_reset.checked=v.private.resume_after_reset;}syncPrivateScheduleEditor(v?.options?.selections||[]);syncResearchDeeperEditor(v?.options?.deeper_selections||[]);syncResearchCandidateSelect(form.elements.candidate_ids,v?.options?.candidate_ids||[]);form.elements.observation_minutes.value=v?.options?.observation_minutes||1440;
}
async function mutateResearchSchedule(path,value,onCommitted,message){
 if(researchScheduleWriting)return false;researchScheduleWriting=true;setResearchScheduleBusy();
 try{const receipt=await submit(path,value);onCommitted?.(receipt);notice(message);await loadResearchSchedules(true);if(researchScheduleVisible())await loadResearch();return true;}
 catch(error){notice(error.message,true);return false;}
 finally{researchScheduleWriting=false;setResearchScheduleBusy();}
}
function researchScheduleAction(v,action){
 return mutateResearchSchedule("/research/schedules/control",{id:v.id,revision:v.revision,action},receipt=>{if(researchScheduleState){const at=researchScheduleState.schedules.findIndex(s=>s.id===v.id);if(at>=0)researchScheduleState.schedules[at]={...v,...receipt,state:receipt.enabled?"WAITING":"OFF",last_reason:""};renderResearchSchedules();}},action==="DISABLE"?"排程已停用，本輪進度與用量已保留。":action==="ENABLE"?"排程已啟用，會接續尚未完成的本輪。":"已要求立即研究；仍遵守每日與每輪上限。");
}
function reviewResearchSchedule(v){
 if(v.private)return reviewPrivateResearchSchedule(v);
 const box=modal("確認研究來源"),ticket=detailVersion;box.append(el("p","來源或同步紀錄變更後已停用。確認目前設定後，才會重新開始本機排程。"));
 for(const id of v.options.source_ids||[]){const src=researchSources.find(s=>s.id===id);item(box,src?.name||id,src?src.url+" · "+(src.enabled?"啟用":"停用"):"來源不存在，請先編輯排程。");}
 item(box,"研究範圍",[v.options.search?"網路探索 · "+(v.languages||[]).join(" / ")+" · "+(v.options.search_country||"TW")+" · 每語言最多 "+(v.options.search_pages||1)+" 頁":"",v.options.refresh_aa?"更新 AA 榜單":"",v.options.lmspeed_seeds?"LMSpeed 線索":""].filter(Boolean).join("；")||"已選公開來源");item(box,"HTTP 上限",v.options.budget+" / 輪 · "+v.daily_http_limit+" / 日");
 action(box,"確認並啟用",async()=>{if(ticket!==detailVersion)return;$("detail").close();await researchScheduleAction(v,"ENABLE");},false);
}
function renderResearchSchedules(){
 const state=researchScheduleState;if(!state)return;$("research-schedule-meter").textContent="今日全部排程 "+state.today_used+" / "+state.daily_http_limit+" 次 HTTP · "+new Date(state.reset_at).toLocaleString()+" 重設";
 if(!researchScheduleLimitDirty)$("research-schedule-limit-form").elements.daily_http_limit.value=state.daily_http_limit;
 const stamp=JSON.stringify(state.schedules);if(stamp===researchScheduleStamp){setResearchScheduleBusy();return;}researchScheduleStamp=stamp;
 const list=$("research-schedule-list"),focus=document.activeElement?.closest("[data-schedule-id]"),focusID=focus?.dataset.scheduleId;list.replaceChildren();
 if(!state.schedules.length)list.append(el("p","尚無排程。新增並保存後，再明確啟用。","section-description"));
 state.schedules.forEach(v=>{
  const card=el("article",undefined,"research-schedule-row"),heading=el("h4",v.name);card.dataset.scheduleId=v.id;heading.tabIndex=-1;card.append(heading,el("span",researchScheduleStates[v.state]||v.state,"research-schedule-state"));list.append(card);
  const next=["INTEGRATION_LIMIT","INFERENCE_WAIT"].includes(v.state)?v.resume_at:v.state==="DAILY_LIMIT"?Math.max(state.reset_at,v.resume_at||0):v.next_at;
  card.append(el("p","每 "+v.interval_minutes+" 分鐘 · "+v.options.budget+" HTTP / 輪 · 今日 "+v.today_used+" / "+v.daily_http_limit));
  if(v.private){card.append(el('p','私人連線 '+v.options.selections.length+' 份 · 每輪 '+v.run_probes+' / '+v.options.probe_budget+' 次推論 · 每日 '+(v.private_usage?.requests||0)+' / '+v.private.daily_inferences+' 次。 '+(v.private_usage?resourceUsageText(v.private_usage):'')+' '+(v.private.resume_after_reset?'每日重設後自動接續。':'用盡後須手動接續。')));}
  card.append(el("p",v.state==="PAUSED"?"手動接續後才會繼續。":v.enabled?"下次檢查："+new Date(next).toLocaleString():"確認啟用後才會執行。"));
  if(v.last_run_id&&v.last_started_at)card.append(el("p","上輪："+new Date(v.last_started_at).toLocaleString()+" · "+(researchRunStates[v.run_status]||v.run_status)+" · "+v.run_used+" / "+v.run_budget+" HTTP"));
  if(v.last_run_id&&Number.isInteger(v.summary?.scanned_count)){const summary=v.summary;card.append(el("p","掃描 "+summary.scanned_count+" · 符合門檻 "+(summary.verified_count||0)+" · 待查證 "+(summary.grey_zone_count||0)+" · 新來源 "+(summary.new_providers||0)));}
  const actions=el("div",undefined,"toolbar");card.append(actions);
  action(actions,v.enabled?"停用排程":v.state==="NEEDS_REVIEW"?"檢視並啟用":"啟用排程",()=>!v.enabled&&(v.private||v.state==="NEEDS_REVIEW")?reviewResearchSchedule(v):researchScheduleAction(v,v.enabled?"DISABLE":"ENABLE"));
  if(v.enabled)action(actions,"立即研究",()=>researchScheduleAction(v,"RUN_NOW"));else action(actions,"編輯排程",()=>editResearchSchedule(v));
  if(v.enabled&&(["QUEUED","RUNNING","PAUSED"].includes(v.run_status)||v.private&&v.state==='INFERENCE_LIMIT')){const resume=['PAUSED','INFERENCE_LIMIT'].includes(v.state);action(actions,resume?"接續本輪":"暫停本輪",()=>mutateResearchSchedule("/research/control",{id:v.last_run_id,action:resume?"RESUME":"PAUSE"},null,resume?"本輪已接續。":"本輪已暫停；重新啟動服務也會維持暫停。"));}
  if(v.run_status||v.last_started_at)action(actions,"排程紀錄",()=>researchScheduleHistory(v));if(v.private)action(actions,"私人核准紀錄",()=>privateScheduleHistory(v));
  if(v.id===focusID)heading.focus({preventScroll:true});
 });setResearchScheduleBusy();
}
async function researchScheduleHistory(v){
 const box=modal(v.name+" · 排程紀錄"),ticket=detailVersion;box.append(el("p","正在讀取…"));
 try{const rows=await api("/research/runs?schedule_id="+encodeURIComponent(v.id));if(ticket!==detailVersion||!$("detail").open)return;box.replaceChildren();box.append(el("p","顯示最近 100 輪；完整原始紀錄繼續保留。"));if(!rows.length)box.append(el("p","尚未開始研究。"));for(const run of rows){const card=item(box,researchRunStates[run.status]||run.status,new Date(run.started_at).toLocaleString()+" · "+run.used+" / "+run.budget+" HTTP");action(card,"查看本輪工作",()=>researchRunDetail(run));}}
 catch(error){if(ticket===detailVersion){box.replaceChildren();detailState(box,error.message,true,()=>researchScheduleHistory(v));}}
}
function bindResearchSchedules(){
 $("research-schedule-new").addEventListener("click",()=>editResearchSchedule());$("research-schedule-reset").addEventListener("click",resetResearchScheduleEditor);$("research-schedule-refresh").addEventListener("click",()=>loadResearchSchedules());
 $('research-schedule-private-enabled').addEventListener('change',()=>syncPrivateScheduleEditor());
 $("research-schedule-form").addEventListener("submit",event=>{
  event.preventDefault();const form=event.target,value=data(form),languages=[...form.elements.languages.selectedOptions].map(o=>o.value);if(form.elements.search.checked&&!languages.length){notice("請選擇至少一種探索語言。",true);return;}
  const draft={id:researchScheduleDraftID,revision:researchScheduleEdit?.revision||0,name:value.name,interval_minutes:Number(value.interval_minutes),daily_http_limit:Number(value.daily_http_limit),languages,options:{observation_minutes:Number(value.observation_minutes),candidate_ids:[...form.elements.candidate_ids.selectedOptions].map(o=>o.value),source_ids:[...form.elements.source_ids.selectedOptions].map(o=>o.value),search:form.elements.search.checked,search_pages:Number(value.search_pages),search_country:value.search_country,refresh_aa:form.elements.refresh_aa.checked,lmspeed_seeds:form.elements.lmspeed_seeds.checked,budget:Number(value.budget),probe_budget:0}};
  if(form.elements.private_enabled.checked){draft.options.selections=[...form.elements.private_selections.selectedOptions].map(o=>JSON.parse(o.value));if(!draft.options.selections.length){notice('請明確選擇至少一份模型連線。',true);return;}draft.options.deeper_selections=[...form.elements.deeper_selections.selectedOptions].map(o=>JSON.parse(o.value));if(draft.options.deeper_selections.length>8||draft.options.deeper_selections.some(v=>!draft.options.selections.some(s=>key(s)===key(v)))||draft.options.deeper_selections.length&&!form.elements.minimal_inference.checked){notice("深入題目最多 8 份，必須包含於已選連線並啟用最小推論驗證。",true);return;}draft.options.minimal_inference=form.elements.minimal_inference.checked;draft.options.probe_budget=draft.options.minimal_inference?Number(value.probe_budget):0;draft.private={daily_inferences:Number(value.daily_inferences),daily_tokens:resourceNullable(value.daily_tokens,true),daily_usd:resourceNullable(value.daily_usd),resume_after_reset:form.elements.resume_after_reset.checked};}
  mutateResearchSchedule("/research/schedules",draft,()=>resetResearchScheduleEditor(),"排程已保存，尚未啟用。");
 });
 $("research-schedule-limit-form").addEventListener("input",()=>researchScheduleLimitDirty=true);$("research-schedule-limit-form").addEventListener("submit",event=>{event.preventDefault();mutateResearchSchedule("/research/schedules/limit",{daily_http_limit:Number(event.target.elements.daily_http_limit.value)},receipt=>{researchScheduleLimitDirty=false;if(researchScheduleState){researchScheduleState.daily_http_limit=receipt.daily_http_limit;renderResearchSchedules();}},"全部排程的每日上限已保存。");});
 setInterval(()=>{if(!busy&&researchScheduleVisible()){loadResearchSchedules();if($("research-inference-budget").open&&researchInferenceState?.reset_at<=Date.now())loadResearchInferenceBudget();if($("research-resource-budget").open&&researchResourceState?.reset_at<=Date.now())loadResearchResourceBudget();if($("research-quota-pools").open&&researchPoolState?.reset_at<=Date.now())loadResearchQuotaPools();}},15000);
}
`
