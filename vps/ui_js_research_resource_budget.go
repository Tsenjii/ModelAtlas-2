package main

const researchResourceBudgetHTML = `
<details id="research-resource-budget" class="research-schedules">
<summary>研究 token／USD 預算</summary>
<p>每次研究先保留你設定的 token 與 USD 預算；完成後以同一請求的供應商回報結算。缺用量、幣別不明、失敗或取消時保留預留，不當成零費用。超出預留時保留原回報並計入後續核准。</p>
<p id="research-resource-meter" class="section-description">展開後讀取本機研究預算。</p>
<p id="research-resource-problem" role="status" hidden></p>
<div class="toolbar"><button id="research-resource-refresh" type="button" class="ghost">更新 token／USD 用量</button><button id="research-resource-history" type="button" class="ghost">預算變更紀錄</button></div>
<form id="research-resource-form"><fieldset disabled><legend>本機研究預算</legend>
<p>每日上限留空表示未啟用；0 明確停止核准。預留額必須大於 0，它是本機核准政策，不是 token 估算或供應商帳單保證。請依可接受的耗用設定；供應商可能回報超出預留。</p>
<div class="toolbar"><label>全部研究每日 token 上限<input name="daily_tokens" type="number" min="0" max="1000000000000" step="1"></label><label>全部研究每日 USD 上限<input name="daily_usd" type="text" inputmode="decimal"></label></div>
<div class="toolbar"><label>每份憑證預設每日 token 上限<input name="credential_tokens" type="number" min="0" max="1000000000000" step="1"></label><label>每份憑證預設每日 USD 上限<input name="credential_usd" type="text" inputmode="decimal"></label></div>
<div class="toolbar"><label>每次研究預留 token<input name="reserve_tokens" type="number" min="1" max="1000000" step="1" required></label><label>每次研究預留 USD<input name="reserve_usd" type="text" inputmode="decimal" required></label></div>
<div class="toolbar"><button>保存 token／USD 預算</button><button id="research-resource-discard" type="button" class="ghost">捨棄預算變更並重讀</button></div>
</fieldset></form>
<h3>逐憑證研究預算</h3><p>同一 Provider／帳戶／憑證跨 Key 版本、模型、Group、端點與研究輪次共同計數，仍受全部研究上限限制。</p>
<div id="research-resource-credentials" class="cards"></div><button id="research-resource-more" type="button" class="ghost" hidden>顯示更多研究憑證</button>
<p id="research-resource-started" class="section-description"></p>
<p class="section-description">每日 UTC 00:00（台灣 08:00）另起一天。修改政策、預留額或跨日不清除舊紀錄，也不會自動恢復研究；日常聊天及重用既有觀測不扣此研究預算。此引擎以外的耗用未計入，共用帳戶／Project 的官方 quota 另行處理。</p>
</details>`

const jsResearchResourceBudget = `
let researchResourceState=null,researchResourceLoading=false,researchResourceWriting=false,researchResourceDirty=false,researchResourceRevision=0;
const resourceLimitText=v=>v===null?'未啟用':String(v),resourceNullable=(v,numeric=false)=>v.trim()===''?null:numeric?Number(v):v.trim();
function setResearchResourceBusy(){const root=$("research-resource-budget"),busy=researchResourceLoading||researchResourceWriting;root.setAttribute("aria-busy",String(busy));root.querySelector('fieldset').disabled=busy||!researchResourceState;root.querySelectorAll('button').forEach(b=>b.disabled=busy);}
async function loadResearchResourceBudget(more=false){
 if(researchResourceLoading||researchResourceWriting)return;researchResourceLoading=true;setResearchResourceBusy();
 try{const v=await api('/research/resource-budget'+(more&&researchResourceState?.next_cursor?'?cursor='+encodeURIComponent(researchResourceState.next_cursor):''));if(!Number.isInteger(v.revision)||!v.policy||!v.usage||!Array.isArray(v.credentials))throw Error('無法辨識研究預算。');if(more&&researchResourceState&&(v.revision!==researchResourceState.revision||v.day!==researchResourceState.day))throw Error('日期或政策已改變，請更新研究預算後再讀取。');if(more&&researchResourceState)v.credentials=[...researchResourceState.credentials,...v.credentials];researchResourceState=v;renderResearchResourceBudget();$('research-resource-problem').hidden=true;
 }catch(e){$('research-resource-problem').textContent=(researchResourceState?'讀取失敗，保留上次預算與未保存變更。 ':'尚未取得預算，請重新讀取。 ')+e.message;$('research-resource-problem').hidden=false;}finally{researchResourceLoading=false;setResearchResourceBusy();}
}
function resourceUsageText(u){return '已計入 '+u.accounted_tokens+' token／'+u.accounted_usd+' USD；供應商回報小計 '+u.known_reported_tokens+' token／'+u.known_reported_usd+' USD。尚未確認 token '+u.unknown_token_requests+' 筆／USD '+u.unknown_usd_requests+' 筆，仍保留預留；待結算 '+u.pending_requests+' 筆／超出預留 '+u.overrun_requests+' 筆。';}
function renderResearchResourceBudget(){
 const v=researchResourceState;if(!v)return;$('research-resource-meter').textContent=v.day+' · '+resourceUsageText(v.usage)+' 每日總上限 '+resourceLimitText(v.policy.daily_tokens)+' token／'+resourceLimitText(v.policy.daily_usd)+' USD。';
 $('research-resource-started').textContent='從 '+new Date(v.started_at).toLocaleString()+' 開始保存本機預留；今日有 '+v.older_unbudgeted_requests+' 筆較舊請求未追溯計入本預算。';
 if(!researchResourceDirty){const f=$('research-resource-form');for(const [name,value]of Object.entries(v.policy))if(f.elements[name])f.elements[name].value=value===null?'':String(value);researchResourceRevision=v.revision;}
 const list=$('research-resource-credentials');list.replaceChildren();for(const c of v.credentials){const row=item(list,c.label+' · '+c.provider_name+' / '+c.account_label,resourceUsageText(c.usage)+' '+(c.override?'個別上限':'預設上限')+' '+resourceLimitText(c.limits.tokens)+' token／'+resourceLimitText(c.limits.usd)+' USD'+(c.available?'':' · 憑證目前不可用'));action(row,'設定憑證研究預算',()=>editResearchResourceCredential(c,v.revision));}if(!v.credentials.length)item(list,'尚無研究憑證','加入模型連線後可設定。');const more=$('research-resource-more'),focused=document.activeElement===more;more.hidden=!v.next_cursor;if(focused&&more.hidden)$('research-resource-refresh').focus();
}
async function saveResearchResourceBudget(value,committed){
 if(researchResourceLoading||researchResourceWriting){notice('正在更新研究預算，請稍後再保存。',true);return;}researchResourceWriting=true;setResearchResourceBusy();
 try{const receipt=await submit('/research/resource-budget',value);committed(receipt);notice('研究預算已保存，用量與研究暫停狀態保留。');}catch(e){notice(e.message,true);return;}finally{researchResourceWriting=false;setResearchResourceBusy();}await loadResearchResourceBudget();
}
function editResearchResourceCredential(c,revision){
 const box=modal('憑證 token／USD 預算'),ticket=detailVersion;box.append(el('p',c.label+' · '+c.provider_name+' / '+c.account_label),el('p',c.provider_id+' / '+c.account_id+' / '+c.credential_id),el('p',resourceUsageText(c.usage)),el('p','留空表示此憑證不設該項上限，仍受全部研究上限限制。改用預設不清除用量。'));
 const form=el('form'),tokens=field(form,'此憑證每日 token 上限','number',c.limits.tokens===null?'':String(c.limits.tokens)),usd=field(form,'此憑證每日 USD 上限','text',c.limits.usd===null?'':c.limits.usd);tokens.min=0;tokens.max=1000000000000;tokens.step=1;usd.inputMode='decimal';form.append(el('button','保存憑證研究預算'));box.append(form);let saving=false;
 const save=async actionName=>{if(saving||ticket!==detailVersion)return;saving=true;box.querySelectorAll('button,input').forEach(n=>n.disabled=true);const value={action:actionName,revision,provider_id:c.provider_id,account_id:c.account_id,credential_id:c.credential_id};if(actionName==='CREDENTIAL'){value.tokens=resourceNullable(tokens.value,true);value.usd=resourceNullable(usd.value);}try{await saveResearchResourceBudget(value,()=>{if(ticket===detailVersion)$('detail').close();});}finally{saving=false;if(ticket===detailVersion)box.querySelectorAll('button,input').forEach(n=>n.disabled=false);}};
 form.addEventListener('submit',e=>{e.preventDefault();save('CREDENTIAL');});if(c.override)action(box,'改用預設研究預算',()=>save('RESET'));
}
async function researchResourceHistory(){
 const box=modal('研究預算變更紀錄'),ticket=detailVersion,list=el('div'),problem=el('p'),more=el('button','讀取更多預算變更'),retry=el('button','重讀預算變更');box.append(el('p','只顯示本機核准政策；預留與供應商回報分開保存。'),list,problem,more,retry);let before=0,loading=false;
 const read=async()=>{if(loading)return;loading=true;more.disabled=retry.disabled=true;try{const v=await api('/research/resource-budget?history=1'+(before?'&before='+before:''));if(ticket!==detailVersion)return;const fragment=document.createDocumentFragment();for(const row of v.history){const x=row.value,text=row.action==='POLICY'?'每日 '+resourceLimitText(x.daily_tokens)+' token／'+resourceLimitText(x.daily_usd)+' USD；每次預留 '+x.reserve_tokens+' token／'+x.reserve_usd+' USD':row.provider_id+' / '+row.account_id+' / '+row.credential_id+' · '+(row.action==='RESET'?'改用預設研究預算':resourceLimitText(x.tokens)+' token／'+resourceLimitText(x.usd)+' USD');item(fragment,'版本 '+row.revision+' · '+new Date(row.changed_at).toLocaleString(),text);}list.append(fragment);before=v.next_before;more.hidden=!before;retry.hidden=true;problem.textContent='';}catch(e){if(ticket===detailVersion){problem.textContent=e.message;retry.hidden=false;more.hidden=true;}}finally{loading=false;more.disabled=retry.disabled=false;}};more.addEventListener('click',read);retry.addEventListener('click',read);await read();
}
function renderResearchResourceLimit(box,task,run){
 const b=task.result?.resource_budget;if(!b)return;const panel=el('section',undefined,'detail-section research-resource-limit-panel');panel.append(el('h3','研究 token／USD 預算'));
 if(b.metric==='UNAVAILABLE')panel.append(el('p','目前無法核對本機政策或用量，此任務尚未發送推論。請先恢復預算讀取。'));
 else if(b.metric==='OUTPUT_ENVELOPE')panel.append(el('p','請求最大輸出 '+b.used+' token 超過每次預留 '+b.limit+' token，請調整預留或請求設定。'));
 else panel.append(el('p',(b.scope==='SCHEDULE'?'排程「'+b.schedule_name+'」':b.scope==='POOL'?'共用池「'+b.pool_label+'」':b.scope==='CREDENTIAL'?'此憑證':'全部研究')+'已計入 '+b.used+' '+b.metric+'，下次需預留 '+b.reservation+'，超過每日上限 '+b.limit+'。此任務未發送推論，沒有新增預留。'));
 panel.append(el('p','每日重設：'+new Date(b.reset_at).toLocaleString()+'。調整預算或跨日後仍須明確恢復研究，既有預留與原始回報保留。'));if(run.status==='CANCELLED')panel.append(el('p','研究已取消。'));box.append(panel);
}
function bindResearchResourceBudget(){
 $('research-resource-budget').addEventListener('toggle',()=>{if($('research-resource-budget').open)loadResearchResourceBudget();});document.addEventListener('visibilitychange',()=>{if(researchScheduleVisible()&&$('research-resource-budget').open)loadResearchResourceBudget();});
 const f=$('research-resource-form');f.addEventListener('input',()=>researchResourceDirty=true);f.addEventListener('submit',e=>{e.preventDefault();const value={action:'POLICY',revision:researchResourceRevision};for(const name of ['daily_tokens','credential_tokens'])value[name]=resourceNullable(f.elements[name].value,true);for(const name of ['daily_usd','credential_usd'])value[name]=resourceNullable(f.elements[name].value);value.reserve_tokens=Number(f.elements.reserve_tokens.value);value.reserve_usd=f.elements.reserve_usd.value.trim();saveResearchResourceBudget(value,receipt=>{researchResourceDirty=false;researchResourceRevision=receipt.revision;});});
 $('research-resource-refresh').addEventListener('click',()=>loadResearchResourceBudget());$('research-resource-discard').addEventListener('click',()=>{researchResourceDirty=false;loadResearchResourceBudget();});$('research-resource-history').addEventListener('click',researchResourceHistory);$('research-resource-more').addEventListener('click',()=>loadResearchResourceBudget(true));
}
`

const researchResourceBudgetCSS = `
#research-resource-budget{min-width:0;overflow-wrap:anywhere}#research-resource-budget fieldset{min-width:0;max-width:100%;margin:0;padding:16px;border:1px solid var(--border);border-radius:12px}#research-resource-budget legend{padding-inline:6px;font-weight:600}#research-resource-problem{color:var(--danger)}.research-resource-limit-panel{min-width:0;overflow-wrap:anywhere}
`
