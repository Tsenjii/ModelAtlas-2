package main

const researchInferenceBudgetHTML = `
<details id="research-inference-budget" class="research-schedules">
<summary>研究推論每日上限</summary>
<p id="research-inference-meter" class="section-description">正在讀取研究推論用量…</p>
<p>只限制此引擎新核准的研究推論，跨研究輪次累計；日常聊天與重用既有觀測不扣次數。已核准後的失敗、取消或重啟仍保留次數。</p>
<p id="research-inference-problem" role="status" hidden></p>
<div class="toolbar"><button id="research-inference-refresh" type="button" class="ghost">更新推論用量</button><button id="research-inference-history" type="button" class="ghost">上限變更紀錄</button></div>
<form id="research-inference-policy-form">
<fieldset disabled><legend>本機每日核准上限</legend>
<div class="toolbar"><label>全部研究每日推論上限<input name="daily_limit" type="number" min="0" max="2000" required></label><label>每份憑證預設每日推論上限<input name="default_credential_limit" type="number" min="0" max="2000" required></label></div>
<p>0 表示停止核准。每日 UTC 00:00（台灣 08:00）重新計數；調整上限不清除今日用量，也不會自動恢復研究。</p>
<div class="toolbar"><button>保存每日推論上限</button><button id="research-inference-reset" type="button" class="ghost">捨棄變更並重新讀取</button></div>
</fieldset></form>
<h3>逐憑證用量與上限</h3><p>同一 Provider／帳戶／憑證的模型、Group、端點及 Key 版本共同計數；不同憑證仍受全部研究上限約束。</p>
<div id="research-inference-credentials" class="cards"></div><button id="research-inference-more" type="button" class="ghost" hidden>顯示更多憑證</button>
<p id="research-inference-started" class="section-description"></p>
<p class="section-description">這是本機核准次數，不代表供應商實際收費或剩餘額度。此處只記核准次數，token 與費用另見研究預算；共用帳戶／Project 額度及私人自動排程仍待補。</p>
</details>`

const jsResearchInferenceBudget = `
let researchInferenceState=null,researchInferenceLoading=false,researchInferenceWriting=false,researchInferenceDirty=false,researchInferenceRevision=0;
function setResearchInferenceBusy(){const root=$("research-inference-budget");root.setAttribute("aria-busy",String(researchInferenceLoading||researchInferenceWriting));root.querySelector("fieldset").disabled=researchInferenceLoading||researchInferenceWriting||!researchInferenceState;root.querySelectorAll("button").forEach(b=>b.disabled=researchInferenceLoading||researchInferenceWriting);}
async function loadResearchInferenceBudget(more=false){
 if(researchInferenceLoading||researchInferenceWriting)return;researchInferenceLoading=true;setResearchInferenceBusy();
 try{const v=await api("/research/inference-budget"+(more&&researchInferenceState?.next_cursor?"?cursor="+encodeURIComponent(researchInferenceState.next_cursor):""));if(!Number.isInteger(v.revision)||!Array.isArray(v.credentials))throw Error("無法辨識推論用量。");
  if(more&&researchInferenceState&&(v.revision!==researchInferenceState.revision||v.day!==researchInferenceState.day))throw Error("日期或上限已變更，請更新推論用量後再讀取。");
  if(more&&researchInferenceState)v.credentials=[...researchInferenceState.credentials,...v.credentials];researchInferenceState=v;renderResearchInferenceBudget();$("research-inference-problem").hidden=true;
 }catch(e){$("research-inference-problem").textContent=(researchInferenceState?"讀取失敗，保留上次用量與未保存變更。 ":"尚未取得上限，請更新後再設定。 ")+e.message;$("research-inference-problem").hidden=false;}
 finally{researchInferenceLoading=false;setResearchInferenceBusy();}
}
function renderResearchInferenceBudget(){
 const v=researchInferenceState;if(!v)return;$("research-inference-meter").textContent=v.day+" · 今日全部研究已核准 "+v.today_used+" / "+v.daily_limit+" 次 · "+new Date(v.reset_at).toLocaleString()+" 重設";
 $("research-inference-started").textContent="本版本從 "+new Date(v.started_at).toLocaleString()+" 開始記錄；升級前或其他裝置的耗用不在此計數內。";
 if(!researchInferenceDirty){const f=$("research-inference-policy-form");f.elements.daily_limit.value=v.daily_limit;f.elements.default_credential_limit.value=v.default_credential_limit;researchInferenceRevision=v.revision;}
 const list=$("research-inference-credentials");list.replaceChildren();for(const c of v.credentials){const row=item(list,c.label+" · "+c.provider_name+" / "+c.account_label,"今日已核准 "+c.used+" / "+c.daily_limit+" 次 · "+(c.override?"個別上限":"預設上限")+(c.available?"":" · 憑證目前不可用"));action(row,"設定憑證上限",()=>editResearchInferenceCredential(c,v.revision));}
 if(!v.credentials.length)item(list,"尚無研究憑證","加入模型連線後可設定個別上限。");$("research-inference-more").hidden=!v.next_cursor;
}
async function saveResearchInferenceBudget(value,committed){
 if(researchInferenceWriting||researchInferenceLoading){notice("正在更新推論用量，請稍後再保存。",true);return;}researchInferenceWriting=true;setResearchInferenceBusy();
 try{const receipt=await submit("/research/inference-budget",value);committed(receipt);notice("推論上限已保存，今日用量與研究暫停狀態保留。");}
 catch(e){notice(e.message,true);return;}
 finally{researchInferenceWriting=false;setResearchInferenceBusy();}
 await loadResearchInferenceBudget();
}
function editResearchInferenceCredential(c,revision){
 const box=modal("研究憑證每日上限"),ticket=detailVersion;box.append(el("p",c.label+" · "+c.provider_name+" / "+c.account_label),el("p",c.provider_id+" / "+c.account_id+" / "+c.credential_id),el("p","今日已核准 "+c.used+" 次；換 Key、模型或端點不會清除用量。"));
 const form=el("form"),limit=field(form,"此憑證每日推論上限","number",String(c.daily_limit));limit.required=true;limit.min=0;limit.max=2000;form.append(el("button","保存憑證上限"));box.append(form);let saving=false;
 const save=async actionName=>{if(saving||ticket!==detailVersion)return;saving=true;box.querySelectorAll("button,input").forEach(b=>b.disabled=true);try{await saveResearchInferenceBudget({action:actionName,revision,provider_id:c.provider_id,account_id:c.account_id,credential_id:c.credential_id,daily_limit:Number(limit.value)},()=>{if(ticket===detailVersion)$("detail").close();});}finally{saving=false;if(ticket===detailVersion)box.querySelectorAll("button,input").forEach(b=>b.disabled=false);}};
 form.addEventListener("submit",e=>{e.preventDefault();save("CREDENTIAL");});if(c.override)action(box,"改用預設上限",()=>save("RESET"));box.append(el("p","0 表示停止核准。修改只影響後續核准，已送出請求不會因此退款或中斷。"));
}
async function researchInferenceHistory(){
 const box=modal("研究推論上限變更紀錄"),ticket=detailVersion,list=el("div"),problem=el("p"),more=el("button","讀取更多變更"),retry=el("button","重新讀取變更"),controls=el("div",undefined,"toolbar");box.append(el("p","只顯示此引擎的上限設定；全部歷史保留，修改不會清除已核准用量。"),list,problem,controls);controls.append(more,retry);let before=0,loading=false;
 const read=async()=>{if(loading)return;loading=true;more.disabled=retry.disabled=true;try{const v=await api("/research/inference-budget?history=1"+(before?"&before="+before:""));if(ticket!==detailVersion)return;for(const e of v.history){const title="版本 "+e.revision+" · "+new Date(e.changed_at).toLocaleString(),text=e.action==="POLICY"?"全部研究 "+e.daily_limit+" 次／預設憑證 "+e.default_credential_limit+" 次":e.provider_id+" / "+e.account_id+" / "+e.credential_id+" · "+(e.action==="RESET"?"改用預設上限":e.daily_limit+" 次");item(list,title,text);}before=v.next_before;more.hidden=!before;retry.hidden=true;problem.textContent="";}catch(e){if(ticket===detailVersion){problem.textContent=e.message;retry.hidden=false;more.hidden=true;}}finally{loading=false;more.disabled=retry.disabled=false;}};
 more.addEventListener("click",read);retry.addEventListener("click",read);await read();
}
function renderResearchInferenceLimit(box,task,run){
 const b=task.result?.inference_budget;if(!b)return;const panel=el("section",undefined,"detail-section research-inference-limit-panel");panel.append(el("h3","研究推論每日上限"),el("p",(b.scope==="CREDENTIAL"?"此憑證":"全部研究")+"已核准 "+b.used+" / "+b.limit+" 次。此任務尚未發送推論，沒有新增用量。"),el("p","每日重設："+new Date(b.reset_at).toLocaleString()+"。調整上限或跨日後，仍須明確恢復這輪研究。"));if(run.status==="CANCELLED")panel.append(el("p","研究已取消；上限紀錄保留。"));box.append(panel);
}
function bindResearchInferenceBudget(){
 $("research-inference-budget").addEventListener("toggle",()=>{if($("research-inference-budget").open)loadResearchInferenceBudget();});
 document.addEventListener("visibilitychange",()=>{if(researchScheduleVisible()&&$("research-inference-budget").open)loadResearchInferenceBudget();});
 const f=$("research-inference-policy-form");f.addEventListener("input",()=>researchInferenceDirty=true);f.addEventListener("submit",e=>{e.preventDefault();saveResearchInferenceBudget({action:"POLICY",revision:researchInferenceRevision,daily_limit:Number(f.elements.daily_limit.value),default_credential_limit:Number(f.elements.default_credential_limit.value)},receipt=>{researchInferenceDirty=false;researchInferenceRevision=receipt.revision;});});
 $("research-inference-refresh").addEventListener("click",()=>loadResearchInferenceBudget());$("research-inference-reset").addEventListener("click",()=>{researchInferenceDirty=false;loadResearchInferenceBudget();});$("research-inference-more").addEventListener("click",()=>loadResearchInferenceBudget(true));$("research-inference-history").addEventListener("click",researchInferenceHistory);
}
`

const researchInferenceBudgetCSS = `
#research-inference-budget{min-width:0;overflow-wrap:anywhere}#research-inference-budget fieldset{min-width:0;max-width:100%;margin:0;padding:16px;border:1px solid var(--border);border-radius:12px}#research-inference-budget legend{padding-inline:6px;font-weight:600}#research-inference-budget .item{min-width:0}#research-inference-problem{color:var(--danger)}.research-inference-limit-panel{min-width:0;overflow-wrap:anywhere}
`
