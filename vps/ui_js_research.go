package main

const jsResearch = `
function researchRunStamp(runs){return JSON.stringify(runs.slice(0,20).map(r=>[r.id,r.status,r.used,r.summary?.retry_waiting_count,r.summary?.next_retry_at,r.summary?.origin_waiting_count,r.summary?.next_origin_at,r.summary?.aa_waiting_count,r.summary?.next_aa_at,r.summary?.brave_waiting_count,r.summary?.next_brave_at]));}
let researchCandidates=[],researchSources=[],researchRuns=[],researchLimit=100,researchStamp="",researchLoading=false;
const researchStates={DISCOVERED:"新線索",VERIFYING:"驗證中",VERIFIED:"符合門檻",HIGH_VALUE_GREY_ZONE:"待查證",STALE:"資料待更新",FAILED:"失敗",REJECTED:"不符條件"};
async function loadResearch(){
 loadResearchSchedules();
 loadResearchInferenceBudget();
 if($("research-resource-budget").open)loadResearchResourceBudget();
 if($("research-quota-pools").open)loadResearchQuotaPools();
 if(researchLoading)return;researchLoading=true;try{const [dashboard,aa]=await Promise.all([api("/research/dashboard"),api("/research/aa")]);const {candidates,sources,runs,funnel,search_usage}=dashboard;if(search_usage){const u=search_usage;$("research-search-usage").textContent="今日 Brave 用量：目前 Key 版本 "+(u.credential_revision_used===null?"未設定":u.credential_revision_used)+" 次 · 所有 Key 共 "+u.shared_used+" / "+u.local_daily_limit+" 次本機上限 · "+new Date(u.reset_at).toLocaleString()+" 重設。站方剩餘額度與費用尚未量測。";}[researchCandidates,researchSources,researchRuns]=[candidates,sources,runs];researchStamp=researchRunStamp(runs);renderResearch(funnel);renderAA(aa);}catch(e){notice(e.message,true);}finally{researchLoading=false;}
}
function renderResearch(funnel){
 const counts=$("research-funnel");counts.replaceChildren();Object.entries(funnel.states).forEach(([state,count])=>item(counts,researchStates[state],String(count)));renderResearchCandidates();syncResearchScheduleSources();syncPrivateScheduleEditor();syncProgressiveResearchEditors();
 const sources=$("research-sources");sources.replaceChildren();researchSources.forEach(s=>{const card=item(sources,s.name,(s.enabled?"啟用":"停用")+" · "+s.kind+" · "+s.language);action(card,"編輯來源",()=>researchSourceEditor(s));action(card,s.enabled?"停用來源":"啟用來源",async()=>{await submit("/research/sources",{...s,enabled:!s.enabled});await loadResearch();});});
 const selected=[...$("research-run-sources").selectedOptions].map(n=>n.value);$("research-run-sources").replaceChildren();researchSources.filter(s=>s.enabled).forEach(s=>{option($("research-run-sources"),s.id,s.name);if(selected.includes(s.id))$("research-run-sources").lastChild.selected=true;});
 const provider=$("research-source-provider"),old=provider.value;provider.replaceChildren();option(provider,"","未確認");providers.forEach(p=>option(provider,p.id,p.name));provider.value=old;
 const target=$("research-run-target"),oldTarget=target.value;target.replaceChildren();option(target,"","不查我的 Key");myModels.filter(m=>m.enabled&&m.state==="ACTIVE"&&chatOperation(targetOperation(m))).forEach(m=>option(target,key(m),m.model_id+" · "+connectionLabel(m)));target.value=oldTarget;
 const runs=$("research-runs");runs.replaceChildren();researchRuns.slice(0,20).forEach(run=>{const card=item(runs,(run.schedule_name?run.schedule_name+" · ":"研究 · ")+run.status,new Date(run.started_at).toLocaleString()+" · "+(run.budget===null?"手動 checkpoint":run.used+" / "+run.budget+" 次 HTTP"+(["QUEUED","RUNNING"].includes(run.status)?(run.summary?.origin_waiting_count?" · 等待網站恢復":run.summary?.retry_waiting_count?" · 等待來源重試":run.summary?.aa_waiting_count?" · 等待 AA 分頁恢復":run.summary?.brave_waiting_count?" · 等待搜尋分頁恢復":""):"")));action(card,"執行紀錄",()=>researchRunDetail(run));if(["QUEUED","RUNNING"].includes(run.status))action(card,"暫停",async()=>{await submit("/research/control",{id:run.id,action:"PAUSE"});await loadResearch();});if(["PAUSED","BUDGET_EXHAUSTED"].includes(run.status))action(card,"恢復研究",()=>researchRunDetail(run));if(["PAUSED","QUEUED","RUNNING","BUDGET_EXHAUSTED"].includes(run.status))action(card,"取消研究",async()=>{await submit("/research/control",{id:run.id,action:"CANCEL"});await loadResearch();});});
}
function renderResearchCandidates(){const state=$("research-filter").value,list=researchCandidates.filter(c=>!state||c.state===state),box=$("research-candidates");box.replaceChildren();list.slice(0,researchLimit).forEach(c=>item(box,(c.model_id==="UNKNOWN"?"未確認模型":c.model_id)+" · "+(providers.find(p=>p.id===c.provider_id)?.name||(()=>{try{return new URL(c.website).hostname;}catch(e){return c.provider_id;}})()),researchStates[c.state]+(c.rpd!==null?" · "+c.rpd+" RPD":"")+(c.tpd!==null?" · "+c.tpd+" TPD":""),()=>researchCandidateDetail(c)));if(!list.length)item(box,"尚無候選","加入研究來源或選擇已加入的連線，開始一輪研究。");$("research-more").hidden=list.length<=researchLimit;}
function renderAA(value){const box=$("aa-ranking");box.replaceChildren();if(!value.snapshot){item(box,"尚無有效榜單","在設定保存 AA Key 後更新。");return;}const s=value.snapshot;box.append(el("p",(value.status==="FRESH"?"目前有效":"已過期；Top-4 特例暫停")+" · 指標版本 "+s.index_version+" · "+new Date(s.observed_at).toLocaleString()));s.models.slice(0,10).forEach(m=>item(box,m.rank+". "+m.name,"總分 "+m.score));}
async function researchCandidateDetail(c){
 const box=modal(c.model_id),evidence=await api("/research/evidence?candidate_id="+encodeURIComponent(c.id));if(!$("detail").open)return;box.append(el("p",researchStates[c.state]+" · "+(c.reason||"尚未判讀")));factsView(box,"候選與配額",c);
 evidence.forEach(e=>{const d=el("details"),summary=el("summary",e.source_type+" · "+(e.verified?"已驗證來源／人工判讀":"未驗證主張"));d.append(summary,el("p",e.claim));const a=el("a","查看證據來源");a.href=e.source_url;a.target="_blank";a.rel="noopener noreferrer";d.append(a);factsView(d,"正規化與時間",e);box.append(d);});
 const form=el("form");box.append(el("h3","確認研究判讀"),form);const selects={};for(const [name,label,values] of [["taiwan","台灣可用性",["UNKNOWN","YES","NO"]],["strength","模型能力",["UNKNOWN","STRONG","WEAK"]],["access_method","使用方式",["UNKNOWN","INFERENCE_READY","CODING_AGENT_ONLY","WEB_ONLY","CUSTOM"]]]){const wrap=el("label",label),select=el("select");select.name=name;values.forEach(v=>option(select,v,v));select.value=c[name]||"UNKNOWN";wrap.append(select);form.append(wrap);selects[name]=select;}
 const reason=field(form,"判讀理由","textarea"),proof=el("select"),proofLabel=el("label","依據的證據");reason.required=true;proof.multiple=true;proof.required=true;evidence.forEach(e=>option(proof,e.id,e.source_type+" · "+e.claim.slice(0,60)));proofLabel.append(proof);form.append(proofLabel,el("button","確認研究判讀"));form.addEventListener("submit",async event=>{event.preventDefault();try{await submit("/research/review",{candidate_id:c.id,taiwan:selects.taiwan.value,strength:selects.strength.value,access_method:selects.access_method.value,reason:reason.value,evidence_ids:[...proof.selectedOptions].map(o=>o.value),confirm:true});$("detail").close();await loadResearch();notice("研究判讀已保存");}catch(e){notice(e.message,true);}});
 if(providers.some(p=>p.id===c.provider_id))action(box,"開啟 Provider",()=>providerDetail(providers.find(p=>p.id===c.provider_id)));else if(c.base_url)action(box,"以此資料新增 Provider",()=>{$("detail").close();show("providers");const f=$("onboarding-form");f.closest("details").open=true;f.elements.name.value=new URL(c.base_url).hostname;f.elements.base_url.value=c.base_url;f.elements.model_id.value=c.model_id==="UNKNOWN"?"":c.model_id;f.elements.secret.focus();});
}
function renderResearchOriginWait(box,task,run){
 const wait=task.result?.origin_wait;if(!wait)return;
 const panel=el("section","","detail-section research-origin-wait-panel");panel.append(el("h3","等待網站恢復"));
 let message="這個網站先前回覆暫時限制，其他研究也會一起等待，避免重複連線。";
 if(run.status==="CANCELLED"||task.status==="CANCELLED")message="研究已取消，不會再連線；先前的等待紀錄已保留。";
 else if(run.status==="PAUSED")message="研究已暫停，網站的等待時間仍會保留。";
 panel.append(el("p",message),el("p",wait.origin),el("p","等待期間不扣 HTTP 額度，也不增加來源失敗次數；其他網站仍可繼續研究。"));
 const date=new Date(wait.next_attempt_at);panel.append(el("p",Number.isFinite(date.getTime())?"下次連線不早於 "+date.toLocaleString():"網站要求的等待時間過長，目前無法排定恢復時間。"));box.append(panel);
}
function renderResearchRetry(box,task,run){
 const retry=task.result?.retry;if(!retry?.history?.length)return;
 const panel=el("section","","detail-section research-retry-panel"),last=retry.history.at(-1);panel.append(el("h3","來源連線恢復"));
 let message;
 if(task.status==="SUCCESS")message="來源已恢復連線，暫時失敗的紀錄已保留。";
 else if(run.status==="CANCELLED"||task.status==="CANCELLED")message="研究已取消，不再自動重試。";
 else if(run.status==="PAUSED")message="研究已暫停，等待時間與重試紀錄已保留。";
 else if(retry.stopped_reason==="ATTEMPT_LIMIT")message="已達自動重試上限；請稍後開始新一輪研究。";
 else if(retry.stopped_reason==="REMOTE_WAIT_TOO_LONG")message="來源要求等待超過 24 小時，已停止自動重試；請稍後重新研究。";
 else if(task.status==="QUEUED"&&retry.next_attempt_at)message="來源暫時無法連線（HTTP "+last.http_status+"），預計 "+new Date(retry.next_attempt_at).toLocaleString()+" 之後重試。";
 else message="目前任務已停止或正在重試；執行紀錄會保留先前的暫時失敗。";
 panel.append(el("p",message),el("p","每次實際連線都計入 HTTP 額度；最多自動重試 2 次。"));
 if(run.status==="BUDGET_EXHAUSTED")panel.append(el("p","目前額度已用完，須依原上限恢復後才會重試。"));
 const history=el("ol");retry.history.forEach(event=>history.append(el("li",new Date(event.observed_at).toLocaleString()+" · HTTP "+event.http_status+(event.retry_at?" · 下次連線不早於 "+new Date(event.retry_at).toLocaleString():""))));panel.append(history);box.append(panel);
}
function renderAAResearchProgress(box,task,run){
 const progress=task.result?.aa_progress;if(!progress)return;
 const panel=el("section","","detail-section research-aa-panel");panel.append(el("h3","AA 榜單更新進度"));
 if(progress.busy){panel.append(el("p","另一輪 AA 更新正在執行，完成後會接續。"));box.append(panel);return;}
 panel.append(el("p","已保存 "+progress.completed_pages+" / "+(progress.total_pages||"待確認")+" 頁 · "+progress.model_count+" 個已評分模型"));
 let message="接續第 "+progress.next_page+" 頁；已完成頁面不會重抓。";
 if(progress.complete)message="完整榜單已驗證並更新，先前的恢復紀錄已保留。";
 else if(run.status==="CANCELLED"||task.status==="CANCELLED")message="研究已取消，不再連線；部分頁面不會取代有效榜單。";
 else if(run.status==="PAUSED")message="研究已暫停，已完成頁面與等待時間已保留。";
 else if(progress.stopped_reason){const reasons={ATTEMPT_LIMIT:"此頁已達 3 次連線上限",REMOTE_WAIT_TOO_LONG:"AA 要求等待超過 24 小時",CREDENTIAL_CHANGED:"AA Key 已變更",CHECKPOINT_EXPIRED:"部分頁面已過期",INCONSISTENT_PAGES:"指標版本、分頁或模型資料不一致",REQUEST_FAILED:"此頁無法完成驗證"};message=(reasons[progress.stopped_reason]||"此輪更新已停止")+"，請確認後開始新一輪更新。";}
 else if(progress.next_attempt_at)message="第 "+progress.next_page+" 頁暫時無法連線，預計 "+new Date(progress.next_attempt_at).toLocaleString()+" 之後重試。已完成頁面不會重抓。";
 panel.append(el("p",message));
 if(!progress.complete)panel.append(el("p","部分頁面不更新 Top-4，上一份有效榜單保留原期限。每頁最多自動重試 2 次，實際連線計入 HTTP 與 AA 額度。"));
 if(run.status==="BUDGET_EXHAUSTED")panel.append(el("p","目前額度已用完，恢復額度後從下一頁接續。"));
 if(progress.history?.length){const history=el("ol");for(const event of progress.history)history.append(el("li","第 "+event.page+" 頁 · HTTP "+event.http_status+" · "+new Date(event.observed_at).toLocaleString()+(event.retry_at?" · 下次連線不早於 "+new Date(event.retry_at).toLocaleString():"")));panel.append(history);}
 box.append(panel);
}
async function queueAAResearch(){
 const queued=await submit("/research/start",{refresh_aa:true,budget:30,probe_budget:0});await loadResearch();const run=researchRuns.find(r=>r.id===queued.id);if(run)await researchRunDetail(run);notice("AA 榜單更新已開始，可在研究紀錄查看進度。");
}
function renderBraveResearchProgress(box,task,run){
 const p=task.result?.brave_progress;if(!p)return;
 const panel=el("section","","detail-section research-aa-panel research-brave-panel");panel.append(el("h3","搜尋分頁進度"),el("p","已保存 "+p.completed_pages+" / 最多 "+p.page_limit+" 頁 · "+p.candidate_count+" 個來源線索 · 地區 "+p.country));
 let message="接續第 "+(p.next_offset+1)+" 頁；已完成頁面不會重抓。";
 if(p.complete)message=p.end_reason==="NO_MORE_RESULTS"?"搜尋服務表示沒有後續結果；此輪已完成。":"已達設定頁數上限；本輪已完成，並非搜尋全部結果。";
 else if(run.status==="CANCELLED"||task.status==="CANCELLED")message="研究已取消，不再連線；已保存線索與紀錄仍保留。";
 else if(run.status==="PAUSED")message="研究已暫停，已完成頁面與等待時間已保留。";
 else if(p.stopped_reason){const reasons={ATTEMPT_LIMIT:"此頁已達 3 次連線上限",REMOTE_WAIT_TOO_LONG:"搜尋服務要求等待超過 24 小時",CREDENTIAL_CHANGED:"Brave Key 已變更",CHECKPOINT_EXPIRED:"分頁期限已過",PLAN_CHANGED:"搜尋設定已變更",PAGINATION_UNAVAILABLE:"服務未提供續頁提示",INVALID_SCHEMA:"回應格式不符",HTTP_REJECTED:"服務拒絕搜尋",INVALID_RESPONSE:"回應中斷或過大"};message=(reasons[p.stopped_reason]||"此輪搜尋已停止")+"；已保存線索保留，請確認後開始新一輪。";}
 else if(p.next_attempt_at)message="第 "+(p.next_offset+1)+" 頁暫時無法連線，預計 "+new Date(p.next_attempt_at).toLocaleString()+" 之後重試。已完成頁面不會重抓。";
 panel.append(el("p",message),el("p","線索尚未驗證免費額度、模型身份或台灣資格。每頁最多自動重試 2 次；每次核准連線皆計入 HTTP 與 Brave 每日用量。"));
 if(run.status==="BUDGET_EXHAUSTED")panel.append(el("p","目前額度已用完，恢復額度後從未完成頁接續。"));
 if(p.duplicate_hits||p.skipped_hits)panel.append(el("p","重複網址 "+p.duplicate_hits+" · 略過不安全或無效線索 "+p.skipped_hits));
 if(p.history?.length){const list=el("ol");for(const e of p.history)list.append(el("li","第 "+(e.offset+1)+" 頁 · "+(e.kind==="TRANSPORT"?"回應中斷":e.http_status?"HTTP "+e.http_status:"連線中斷")+" · 第 "+e.attempt+" 次 · "+new Date(e.observed_at).toLocaleString()+(e.retry_at?" · 下次連線不早於 "+new Date(e.retry_at).toLocaleString():"")));panel.append(list);}
 box.append(panel);
}
async function researchRunDetail(run,taskSnapshot=null){const box=modal("研究紀錄"),ticket=detailVersion,tasks=taskSnapshot||await api("/research/tasks?run_id="+encodeURIComponent(run.id));if(!$("detail").open||ticket!==detailVersion)return;action(box,"更新執行紀錄",async()=>{const [runs,tasks]=await Promise.all([api("/research/runs"),api("/research/tasks?run_id="+encodeURIComponent(run.id))]);if(!$("detail").open||ticket!==detailVersion)return;const fresh=runs.find(r=>r.id===run.id);if(!fresh)throw Error("找不到這一輪研究，請重新載入研究清單。");await researchRunDetail(fresh,tasks);});renderResearchRunResources(box,run.summary?.research_resources);researchProbeHistory(box,"run_id="+encodeURIComponent(run.id),ticket);factsView(box,"本輪結果",run.summary||{});tasks.forEach(t=>{const d=el("details");d.append(el("summary",t.kind+" · "+t.status+" · "+t.attempts+" 次嘗試"));factsView(d,"計畫",t.payload);renderResearchOriginWait(d,t,run);renderResearchRetry(d,t,run);renderAAResearchProgress(d,t,run);renderBraveResearchProgress(d,t,run);renderResearchInferenceLimit(d,t,run);renderResearchResourceLimit(d,t,run);factsView(d,"結果",t.result);box.append(d);});if(!run.query_comparison&&["PAUSED","BUDGET_EXHAUSTED","COMPLETED"].includes(run.status)&&tasks.some(t=>["QUEUED","INTERRUPTED","BUDGET_EXHAUSTED"].includes(t.status)||(t.status==="FAILED"&&t.attempts<3&&!t.result?.aa_progress?.stopped_reason&&!t.result?.brave_progress?.stopped_reason))){const form=el("form"),extra=field(form,"追加 HTTP 預算","number","0"),probeExtra=field(form,"追加推論預算","number","0"),retry=el("input"),label=el("label","重試失敗項目","check");probeExtra.min=0;probeExtra.max=run.schedule_id?0:10-(run.probe_budget||0);if(run.schedule_id){probeExtra.disabled=true;form.append(el("p","此輪屬於排程；請先啟用原排程，並遵守已核准的每輪與每日上限。"));}extra.min=0;extra.max=200-(run.budget||0);retry.type="checkbox";label.prepend(retry);form.append(label,el("button","確認恢復研究"));box.append(form);form.addEventListener("submit",async e=>{e.preventDefault();try{await submit("/research/control",{id:run.id,action:"RESUME",additional_budget:Number(extra.value),additional_probes:Number(probeExtra.value),retry_failed:retry.checked});$("detail").close();await loadResearch();}catch(e){notice(e.message,true);}});}}
function bindResearch(){
 bindResearchSchedules();
 bindResearchInferenceBudget();
 bindResearchResourceBudget();
 bindResearchQuotaPools();
 $("research-workspace").addEventListener("toggle",()=>{if($("research-workspace").open)loadResearch();});$("research-refresh").addEventListener("click",loadResearch);$("research-filter").addEventListener("change",()=>{researchLimit=100;renderResearchCandidates();});$("research-more").addEventListener("click",()=>{researchLimit+=100;renderResearchCandidates();});
 $("research-source-form").addEventListener("submit",async e=>{e.preventDefault();const f=e.target,v=data(f);try{await submit("/research/sources",{id:crypto.randomUUID(),name:v.name,url:v.url,kind:v.kind,language:v.language,enabled:true,config:{provider_id:v.provider_id,official:f.elements.official.checked,items_pointer:v.items_pointer,fields:v.fields?JSON.parse(v.fields):{}}});await loadResearch();notice("研究來源已保存");}catch(e){notice(e.message,true);}});
 bindProgressiveResearch();
 $("research-key-form").addEventListener("submit",async e=>{e.preventDefault();try{await submit("/research/integrations",data(e.target));e.target.elements.secret.value="";notice("研究 Key 已保存");}catch(e){notice(e.message,true);}});$("aa-sync").addEventListener("click",async e=>{const button=e.currentTarget;if(button.disabled)return;button.disabled=true;try{await queueAAResearch();}catch(e){notice(e.message,true);}finally{button.disabled=false;}});
 setInterval(async()=>{if(document.hidden||busy||!$("research-workspace").open||($("models").hidden&&($("settings").hidden||$("settings-research").hidden)))return;try{const runs=await api("/research/runs"),stamp=researchRunStamp(runs);if(researchStamp&&stamp!==researchStamp)await loadResearch();researchStamp=stamp;}catch(e){}},3000);
}
`
