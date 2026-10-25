package main

const extractionHTML = `<section class="card"><h3>擷取效率</h3><p>比較同一組公開資料的逐項與合併讀取，確認結果與實際請求數後再試用。</p><button id="extraction-open" type="button" class="ghost">擷取效率與比較</button></section>`

const jsExtraction = `
const extractionLabels={RUNNING:'比較進行中',READY_FOR_TRIAL:'可確認試用',NO_IMPROVEMENT:'尚未證明改善',FAILED:'讀取未完成',CANCELLED:'已停止',INTERRUPTED:'上次比較已中斷',SCOPE_CHANGED:'連線或規則已變',SUCCESS:'正常讀取',AUTO_ROLLED_BACK:'已自動回復逐項讀取',LOCAL_WAIT:'等待本機條件'};
function extractionProblem(error){return error?.status===409?'連線、規則或確認版本已改變，請重新讀取後再預覽。':error?.status===422?'請確認選取二至六項相同來源的公開規則。':'這次操作未確認完成，請先讀取已保存結果。';}
function extractionButton(parent,text,fn){return siteRecipeAction(parent,text,fn);}
function bindExtraction(){ $('extraction-open').addEventListener('click',()=>extractionWorkspace()); }
async function extractionWorkspace(){
 const box=modal('擷取效率與比較'),ticket=detailVersion,problem=el('p'),list=el('div',undefined,'cards'),form=el('form');problem.setAttribute('role','status');
 box.append(el('p','只處理已明確啟用的公開唯讀規則。產生提案不發HTTP；三輪比較會各自實際讀取原版與提案，不呼叫模型。完整貨幣成本Unknown。'),problem);
 let versions=[],rules=[],busy=false,readRequired=false,intent=null,initialized=false;
 const live=()=>detailCurrent(ticket)&&box.isConnected;
 const target=el('select');target.id='extraction-scope';const label=el('label','模型連線');label.append(target);form.append(label);
 for(const m of myModels.filter(m=>m.enabled&&m.state==='ACTIVE'))option(target,key(m),modelDisplayName(m)+' · '+(providers.find(p=>p.id===m.provider_id)?.name||m.provider_id)+' · '+connectionLabel(m)+' · '+(operationLabels[targetOperation(m)]||m.endpoint));
 const choices=el('fieldset'),legend=el('legend','選取目前已啟用的公開規則');choices.append(legend);form.append(choices);
 const create=el('button','產生合併讀取提案');create.id='extraction-create';form.append(create);
 let choicesIdentity='';
 const renderChoices=()=>{const selected=target.value?JSON.parse(target.value):null,available=rules.filter(v=>v.active&&v.provider_id===selected?.provider_id&&v.config.credential_role==='NONE'&&v.config.parser_version==='declarative-get-v2'&&v.config.kind!=='CATALOG');const identity=JSON.stringify([target.value,available.map(v=>v.id)]);if(identity===choicesIdentity)return;const prior=new Set([...choices.querySelectorAll('input:checked')].map(n=>n.value)),sameScope=choices.dataset.scope===target.value;choicesIdentity=identity;choices.dataset.scope=target.value;choices.replaceChildren(legend);for(const v of available){const row=el('label',undefined,'check'),input=el('input');input.type='checkbox';input.name='connector';input.value=v.id;input.checked=sameScope?prior.has(v.id):available.length<=6;row.append(input,el('span',(connectorKindLabels[v.config.kind]||v.config.kind)+' · '+v.config.path));choices.append(row);}if(!available.length)choices.append(el('p','這個連線尚無已啟用的公開規則。先完成站點辨識、實測與確認，再回來比較。'));};
 target.addEventListener('change',()=>{intent=null;renderChoices();});
 const renderList=()=>{list.replaceChildren();for(const v of versions)item(list,(providers.find(p=>p.id===v.definition.selection.provider_id)?.name||'公開資料')+' · '+v.definition.baseline_public_gets+'→'+v.definition.proposed_public_gets+' 次讀取',(v.active?'試用中 · ':'')+(extractionLabels[v.comparison?.status]||'待比較'),()=>extractionDetail(v));if(!versions.length)item(list,'尚無擷取改進提案','先選取連線與公開規則；每份提案都能檢查與回復。');};
 const read=async()=>{if(busy)return;busy=true;refresh.disabled=true;try{const [v,c]=await Promise.all([api('/extraction'),api('/connectors')]);if(!live())return;versions=v;rules=c;renderList();renderChoices();if(!initialized){box.append(form,list);initialized=true;}if(intent){const saved=v.find(x=>x.id===intent.id);if(saved){extractionDetail(saved);return;}}readRequired=false;create.disabled=false;problem.textContent='';}catch(e){if(live())problem.textContent='讀取未完成，保留目前清單與選取。'+extractionProblem(e);}finally{busy=false;if(live())refresh.disabled=false;}};
 const refresh=extractionButton(box,'重新讀取擷取紀錄',read);refresh.id='extraction-refresh';
 form.addEventListener('submit',async e=>{e.preventDefault();if(busy||readRequired||!live())return;const ids=[...choices.querySelectorAll('input:checked')].map(n=>n.value);if(!target.value||ids.length<2||ids.length>6){problem.textContent='請選取二至六項相同來源的公開規則。';return;}const value={selection:JSON.parse(target.value),connector_ids:ids};const signature=JSON.stringify(value);if(!intent||intent.signature!==signature)intent={id:crypto.randomUUID(),signature};busy=true;const controls=[...form.elements].map(n=>[n,n.disabled]);controls.forEach(([n])=>n.disabled=true);try{const result=await submit('/extraction/proposals',{id:intent.id,...value});if(live())extractionDetail(result);}catch(error){if(live()){readRequired=true;problem.textContent=extractionProblem(error)+' 不會自動重新建立提案。';}}finally{busy=false;if(live()){controls.forEach(([n,was])=>n.disabled=was);create.disabled=readRequired;}}});
 await read();
}
function extractionDefinitionView(box,d){
 box.append(el('p','原版每次 '+d.baseline_public_gets+' 次公開GET；提案每次最多 '+d.proposed_public_gets+' 次。只在同一批次共用相同來源回應，不跨對話、Key、群組或批次快取。'));
 for(const ref of d.rules){const row=el('div',undefined,'card');row.append(el('strong',connectorKindLabels[ref.config.kind]||ref.config.kind),el('p',ref.config.path+' · '+({MODEL_PROVIDER:'來源模型資料',MODEL_KEY_GROUP:'此連線與群組',ACCOUNT:'帳戶資料'}[ref.config.scope]||'明確選取的公開資料')+' · '+(ref.config.unit==='NONE'?'各欄位保留原單位':ref.config.unit)));box.append(row);}
}
function extractionDetail(v){
 const box=modal('公開資料擷取改進'),ticket=detailVersion,problem=el('p');problem.setAttribute('role','status');box.append(problem);extractionDefinitionView(box,v.definition);
 box.append(el('p','三輪比較最多 '+(3*(v.definition.baseline_public_gets+v.definition.proposed_public_gets))+' 次公開GET／0次模型推論；完整貨幣成本Unknown。只有三輪結果相同且請求減少，才可確認試用。'));
 if(v.comparison){const c=v.comparison;box.append(el('h3',v.active?'試用中':v.retest_required?'需要重新比較':extractionLabels[c.status]||c.status),el('p','已記錄 HTTP '+(c.http_requests??'Unknown')+' 次；已保存輪次的請求 '+c.completed_round_http_requests+' 次。未完成或中斷的飛行請求不當作0。'));const table=el('table'),head=el('tr');['輪次','原版GET','提案GET','結果'].forEach(t=>head.append(el('th',t)));table.append(head);c.rounds.forEach((r,i)=>{const row=el('tr');[String(i+1),String(r.baseline_http),String(r.proposed_http),r.facts_equal?'相同':'未證明相同'].forEach(t=>row.append(el('td',t)));table.append(row);});box.append(table);}
 if(v.observations?.length){const history=el('details');history.append(el('summary','試用監測與回復紀錄'));for(const r of v.observations)history.append(el('p',new Date(r.at).toLocaleString('zh-TW')+' · '+(extractionLabels[r.status]||r.status)+' · HTTP '+(r.http_requests??'Unknown')+(r.auto_rollback?' · 已回復原模式':'')));box.append(history);}
 let busy=false,readRequired=false,controller=null;const live=()=>detailCurrent(ticket)&&box.isConnected;
 const read=async()=>{const rows=await api('/extraction'),fresh=rows.find(x=>x.id===v.id);if(live()&&fresh)extractionDetail(fresh);};
 const locked=message=>{readRequired=true;problem.textContent=message;box.querySelectorAll('button').forEach(b=>{b.disabled=true;b.dataset.locked='true';});extractionButton(box,'讀取已保存比較結果',read);};
 const run=async fn=>{if(busy||readRequired||!live())return;busy=true;const buttons=[...box.querySelectorAll('button')].map(b=>[b,b.disabled]);buttons.forEach(([b])=>b.disabled=true);try{await fn();}catch(e){if(live())locked(extractionProblem(e));}finally{busy=false;controller=null;if(live()&&!readRequired)buttons.forEach(([b,was])=>{if(b.isConnected)b.disabled=was;});}};
 box.closest('dialog')?.addEventListener('close',()=>controller?.abort(),{once:true});
 extractionButton(box,'更新擷取紀錄',()=>run(read));
 if(!v.active){
  const compare=extractionButton(box,'開始三輪實際比較',()=>run(async()=>{controller=new AbortController();let stopped=false;const stop=extractionButton(box,'停止比較',()=>{stopped=true;controller?.abort();});try{await api('/extraction/compare',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({id:crypto.randomUUID(),version_id:v.id,confirm:true}),signal:controller.signal});await read();}catch(e){if(stopped&&live())locked('已要求停止。請讀取已保存輪次與請求數，不會自動重新比較。');else throw e;}finally{stop.remove();}}));compare.disabled=v.comparison?.status==='RUNNING';
  const preview=extractionButton(box,'預覽擷取試用',()=>run(async()=>{const plan=await submit('/extraction/plan',{id:v.id});if(live())extractionReviewDetail(v,plan);}));preview.disabled=Boolean(v.retest_required)||v.comparison?.status!=='READY_FOR_TRIAL';
 }
 if(v.active&&v.activation_action_id)extractionButton(box,'回復原逐項讀取',()=>run(async()=>{try{await submit('/versions/undo',{id:v.activation_action_id,confirm:true});await read();}catch(e){if(live())locked('回復結果未確認，請先讀取已保存紀錄，不會自動再次回復。');}}));
}
function extractionReviewDetail(v,plan){
 const box=modal('確認擷取效率試用'),ticket=detailVersion;box.append(el('p',plan.note),el('p','這次確認新增0次HTTP／0次模型推論。比較費用Unknown；不保證來源穩定或免費。'));extractionDefinitionView(box,plan.definition);
 let writing=false;const confirm=extractionButton(box,'確認試用合併讀取',async()=>{if(writing||!detailCurrent(ticket))return;writing=true;confirm.disabled=true;confirm.dataset.locked='true';try{await submit('/extraction/activate',{id:v.id,expected_active_id:plan.expected_active_id,review_fingerprint:plan.fingerprint,confirm:true});const rows=await api('/extraction');if(detailCurrent(ticket)){const fresh=rows.find(x=>x.id===v.id);if(fresh)extractionDetail(fresh);}}catch(error){if(!detailCurrent(ticket))return;box.append(el('p',extractionProblem(error)+' 請先讀取確認結果，避免重複寫入。','error'));extractionButton(box,'讀取擷取確認結果',async()=>{const rows=await api('/extraction');const fresh=rows.find(x=>x.id===v.id);if(fresh)extractionDetail(fresh);});}});
}
`
