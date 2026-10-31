package main

const researchExecutionHTML = `
<details id="research-execution-policy">
<summary>研究執行上限</summary>
<p>設定排程與研究佇列同時處理的輪數；同一輪每次只處理一項工作。預設為 1，每次最多 4 輪。增加輪數會更快使用已核准的額度，HTTP、推論、token、USD 與共用池上限仍逐次核對。</p>
<p id="research-execution-meter" class="section-description">展開後讀取目前設定。</p>
<p id="research-execution-problem" role="status" hidden></p>
<button id="research-execution-refresh" type="button" class="ghost">更新執行設定</button>
<form id="research-execution-form"><fieldset disabled><legend>本機研究執行</legend>
<label>同時研究輪數<input name="max_parallel_runs" type="number" min="1" max="4" step="1" required value="1"></label>
<p>已開始的工作會先完成，新上限套用到後續工作。保存不會啟用已停用的排程，也不會恢復已暫停或取消的研究。</p>
<div class="toolbar"><button>保存執行上限</button><button id="research-execution-discard" type="button" class="ghost">捨棄變更並重讀</button></div>
</fieldset></form>
</details>`

const jsResearchExecution = `
let researchExecutionState=null,researchExecutionLoading=false,researchExecutionWriting=false,researchExecutionDirty=false,researchExecutionRevision=0;
function setResearchExecutionBusy(){const root=$('research-execution-policy'),busy=researchExecutionLoading||researchExecutionWriting||researchScheduleWriting;root.setAttribute('aria-busy',String(busy));root.querySelector('fieldset').disabled=busy||!researchExecutionState;root.querySelectorAll('button').forEach(b=>b.disabled=busy);}
function renderResearchExecution(){
 const v=researchExecutionState;if(!v)return;
 $('research-execution-meter').textContent='目前執行 '+v.running_runs+' 輪 · 已排入 '+v.scheduled_rounds+' 輪排程 · 設定上限 '+v.policy.max_parallel_runs+' 輪。';
 if(!researchExecutionDirty){$('research-execution-form').elements.max_parallel_runs.value=String(v.policy.max_parallel_runs);researchExecutionRevision=v.policy.revision;}
}
async function loadResearchExecution(){
 if(researchExecutionLoading||researchExecutionWriting)return;researchExecutionLoading=true;setResearchExecutionBusy();
 try{const v=await api('/research/execution-policy');if(!v.policy||!Number.isSafeInteger(v.policy.revision)||!Number.isInteger(v.policy.max_parallel_runs)||v.policy.max_parallel_runs<1||v.policy.max_parallel_runs>4||!Number.isInteger(v.running_runs)||!Number.isInteger(v.scheduled_rounds))throw Error('無法辨識研究執行設定。');researchExecutionState=v;renderResearchExecution();$('research-execution-problem').hidden=true;}
 catch(e){$('research-execution-problem').textContent=(researchExecutionState?'讀取失敗，保留上次設定與未保存變更。 ':'尚未取得執行設定。 ')+e.message;$('research-execution-problem').hidden=false;}
 finally{researchExecutionLoading=false;setResearchExecutionBusy();}
}
async function saveResearchExecution(){
 if(researchExecutionLoading||researchExecutionWriting||researchScheduleWriting||!researchExecutionState)return;
 researchExecutionWriting=true;setResearchExecutionBusy();
 const value={revision:researchExecutionRevision,max_parallel_runs:Number($('research-execution-form').elements.max_parallel_runs.value)};
 let committed=false;
 try{const receipt=await submit('/research/execution-policy',value);if(!Number.isSafeInteger(receipt.revision)||receipt.revision!==value.revision+1||receipt.max_parallel_runs!==value.max_parallel_runs)throw Error('保存回應無法確認，請更新設定核對。');researchExecutionState={...researchExecutionState,policy:receipt};researchExecutionDirty=false;renderResearchExecution();$('research-execution-problem').hidden=true;notice('研究執行上限已保存，現有工作與用量保留。');committed=true;}
 catch(e){$('research-execution-problem').textContent='保存尚未確認，請更新設定核對；草稿已保留。 '+e.message;$('research-execution-problem').hidden=false;}
 finally{researchExecutionWriting=false;setResearchExecutionBusy();}
 if(committed)await loadResearchExecution();
}
function bindResearchExecution(){
 $('research-execution-policy').addEventListener('toggle',()=>{if($('research-execution-policy').open)loadResearchExecution();});
 $('research-execution-refresh').addEventListener('click',loadResearchExecution);
 $('research-execution-discard').addEventListener('click',()=>{researchExecutionDirty=false;loadResearchExecution();});
 const form=$('research-execution-form');form.addEventListener('input',()=>researchExecutionDirty=true);form.addEventListener('submit',event=>{event.preventDefault();saveResearchExecution();});
}
`

const researchExecutionCSS = `
#research-execution-policy{min-width:0;overflow-wrap:anywhere}#research-execution-policy>summary{min-height:48px;padding-block:12px;cursor:pointer;font-weight:600}#research-execution-policy fieldset{min-width:0;max-width:100%;margin:0;padding:16px;border:1px solid var(--border);border-radius:12px}#research-execution-policy legend{padding-inline:6px;font-weight:600}#research-execution-problem{color:var(--danger)}
`
