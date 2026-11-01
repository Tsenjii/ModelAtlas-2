package main

const siteRecipeHTML = `<section class="card"><h3>公開資料配方</h3><p>將同一次辨識的公開規則組成配方，逐項實測、Review 後一起啟用，並可一起回復。</p><div id="site-recipe-list" class="cards"></div></section>`

const jsSiteRecipe = `
let siteRecipes=[];
function siteRecipeAction(target,label,callback){const b=el('button',label,'ghost');b.type='button';b.addEventListener('click',async()=>{if(b.disabled)return;b.disabled=true;try{await callback();}catch(e){notice(e.message,true);}finally{if(b.isConnected&&b.dataset.locked!=='true')b.disabled=false;}});target.append(b);return b;}
const siteRecipeStatus={DRAFT:'待實測',TEST_NEEDED:'需要實測',READY_FOR_REVIEW:'可預覽確認',REVIEW_REQUIRED:'來源或範圍需重新確認',RULES_CHANGED:'部分規則已另行變更',LOCAL_EVIDENCE_UNAVAILABLE:'缺少本機證據'};
function renderSiteRecipes(rows){siteRecipes=rows;const list=$('site-recipe-list');list.replaceChildren();for(const v of rows.filter(v=>v.settings.generated_recipe_id))item(list,(providers.find(p=>p.id===v.provider_id)?.name||v.provider_id)+' · 公開資料配方 v'+v.version,(v.active?'已啟用 · ':'')+(siteRecipeStatus[v.status]||v.status),()=>siteRecipeDetail(v));if(!list.children.length)item(list,'尚無公開資料配方','開啟站點辨識紀錄，選取有證據的規則後建立。');}
function siteFamilyEvidenceView(box,items){if(!items?.length)return;const details=el('details');details.append(el('summary','各來源的站型與版本線索'));for(const e of items){const card=el('div',undefined,'card');card.append(el('p',e.family_hint+' · '+e.version_hint+' · '+e.confidence),el('p',e.source_url),el('p','依據：'+e.evidence_pointers.join('、')),el('p','推論認證方式未判定；私人API未實測。'));factsView(card,'來源指紋與版本語意',e);details.append(card);}box.append(details);}
function generateSiteRecipeDetail(report){
 const box=modal('建立公開資料配方'),ticket=detailVersion,form=el('form'),groups=new Map(),feedback=el('p');feedback.setAttribute('role','status');
 box.append(el('p','選取同一份辨識紀錄的公開規則，每種資料最多一個，整組最多6個。生成不發HTTP或推論；之後仍須逐項實測與明確確認。'));
 const candidates=connectorVersions.filter(v=>report.draft_ids.includes(v.id)&&v.config.credential_role==='NONE');for(const v of candidates){if(!groups.has(v.config.kind))groups.set(v.config.kind,[]);groups.get(v.config.kind).push(v);}
 const fields=[];for(const [kind,rows]of groups){const label=el('label',connectorKindLabels[kind]||kind),select=el('select');select.setAttribute('aria-label',(connectorKindLabels[kind]||kind)+'規則');option(select,'','這組不包含此資料');for(const v of rows)option(select,v.id,v.config.path+' · '+v.config.scope+' · '+v.config.unit);if(rows.length===1&&!report.conflicting_kinds.includes(kind))select.value=rows[0].id;label.append(select);form.append(label);fields.push(select);}
 const button=el('button','保存公開配方草稿');form.append(button);box.append(form,feedback);siteFamilyEvidenceView(box,report.family_evidence);let writing=false,requestID=crypto.randomUUID(),request=null;
 const live=()=>ticket===detailVersion&&box.isConnected;
 async function readSaved(){const rows=await api('/recipes');if(!live())return;const v=rows.find(v=>v.id===requestID);if(!v){feedback.textContent='尚未讀到這份配方；請稍後重讀，或用相同請求重試。';return;}await loadKnowledge();siteRecipeDetail(v);notice('公開配方草稿已保存，尚未啟用');}
 siteRecipeAction(box,'讀取已保存配方',async()=>{try{await readSaved();}catch(e){notice(e.message,true);}});
 form.addEventListener('submit',async e=>{e.preventDefault();if(writing||!live())return;const ids=fields.map(f=>f.value).filter(Boolean);if(!ids.length||ids.length>6){notice('請選取1至6個公開規則。',true);return;}const next={id:requestID,discovery_id:report.id,connector_ids:ids};if(request&&JSON.stringify(request.connector_ids)!==JSON.stringify(ids)){requestID=crypto.randomUUID();next.id=requestID;}request=next;writing=true;button.disabled=true;fields.forEach(f=>f.disabled=true);try{const v=await submit('/recipes/proposals',request);if(!live())return;await loadKnowledge();siteRecipeDetail(siteRecipes.find(x=>x.id===v.id)||v);notice('公開配方草稿已保存，尚未啟用');}catch(e){if(live()){feedback.textContent='請先讀取已保存配方確認結果。再次保存會沿用同一個請求，不重複建立。';notice(e.message,true);}}finally{writing=false;if(live()){button.disabled=false;fields.forEach(f=>f.disabled=false);}}});
}

function siteRecipeDetail(v){
 const box=modal('公開資料配方 · v'+v.version),ticket=detailVersion,p=v.site_draft,problem=el('p');problem.setAttribute('role','status');box.append(el('p',(v.active?'此配方已啟用 · ':'此配方尚未啟用 · ')+(siteRecipeStatus[v.status]||v.status)),problem);
 if(!p){box.append(el('p','這份歷史缺少本機生成證據，不能借其他裝置的執行權限。'));return;}
 const d=p.definition;box.append(el('p',d.origin),el('p',d.selection.model_id+' · '+connectionLabel(d.selection)),el('p','站型線索 '+d.family_hint+'／版本線索 '+d.version_hint+'，未獨立驗證身份或私人API。'));
 if(d.conflicting_kinds.length)box.append(el('p','來源曾有不一致：'+d.conflicting_kinds.map(k=>connectorKindLabels[k]||k).join('、')+'；請檢視選取來源。','error'));
 siteFamilyEvidenceView(box,d.family_evidence);
 const rules=el('div',undefined,'cards');box.append(rules);for(const ref of d.rules){const row=el('section',undefined,'card'),state=v.test?.rules.find(x=>x.version_id===ref.version_id);row.append(el('h3',connectorKindLabels[ref.kind]||ref.kind),el('p',ref.config.path+' · '+ref.config.scope+' · '+ref.config.unit),el('p','認證：公開資料，不傳Key／PAT'),el('p',(state?.state==='READY'?'目前範圍實測通過':'待實測')+' · 最近一次實測 HTTP '+(state?.last_test_http_requests??'Unknown')+' 次'));const detail=el('details');detail.append(el('summary','欄位、單位、時效與來源'));factsView(detail,'宣告式規則',ref);row.append(detail);siteRecipeAction(row,'檢視'+(connectorKindLabels[ref.kind]||ref.kind)+'規則',()=>{const c=connectorVersions.find(x=>x.id===ref.version_id);if(c)connectorDetail(c);});rules.append(row);}
 box.append(el('p','實測只發公開GET，不發模型推論。完整貨幣成本Unknown；0次模型推論不表示免費。'),el('p','確認後只替換選取的公開資料規則；原已確認的聊天與目錄設定保留。若包含模型清單規則，目錄會改用該公開來源。'));
 const limitations=el('details');limitations.append(el('summary','尚未判定與限制'));for(const text of d.limitations)limitations.append(el('p',text));factsView(limitations,'未判定欄位',d.unknown_fields);box.append(limitations);
 let busy=false,halt=false,controller=null,readRequired=false;const live=()=>ticket===detailVersion&&box.isConnected;
 const read=async()=>{const rows=await api('/recipes');const fresh=rows.find(x=>x.id===v.id);if(!fresh)throw Error('找不到已保存配方，請重讀清單。');if(!live())return;await loadKnowledge();siteRecipeDetail(fresh);};
 const run=async fn=>{if(busy||readRequired||!live())return;busy=true;const buttons=[...box.querySelectorAll('button')].map(b=>[b,b.disabled]);buttons.forEach(([b])=>b.disabled=true);try{await fn();}catch(e){if(live()){problem.textContent=e.message;notice(e.message,true);}}finally{busy=false;controller=null;if(live()&&!readRequired)buttons.forEach(([b,disabled])=>{if(b.isConnected)b.disabled=disabled;});}};
 const requireRead=message=>{readRequired=true;problem.textContent=message;box.querySelectorAll('button').forEach(b=>{b.disabled=true;b.dataset.locked='true';});const recovery=siteRecipeAction(box,'重新讀取配方確認結果',async()=>{try{await read();}catch(e){notice(e.message,true);}});recovery.focus();};
 siteRecipeAction(box,'更新配方紀錄',()=>run(read));
 if(!v.active){
  siteRecipeAction(box,'逐項實測公開規則',()=>run(async()=>{halt=false;let current=await submit('/recipes/evaluate',{id:v.id});const todo=current.rules.filter(r=>r.state!=='READY');const stop=siteRecipeAction(box,'停止後續實測',()=>{halt=true;controller?.abort();problem.textContent='已要求停止；重讀紀錄確認已保存結果，不自動重送。';});try{for(let i=0;i<todo.length;i++){if(halt||!live())break;problem.textContent='正在實測 '+(i+1)+'／'+todo.length+'；每項最多1次公開GET。';controller=new AbortController();const result=await api('/connectors/test',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({id:todo[i].version_id,selection:d.selection}),signal:controller.signal});if(result.status!=='VALIDATED'){problem.textContent='這項實測未通過，先保留已完成結果；檢視規則的失敗紀錄後再決定。';return;}}if(halt)return;await submit('/recipes/evaluate',{id:v.id});await read();notice('公開規則實測與配方彙整已保存，請預覽確認');}catch(e){if(halt){requireRead('實測已停止；請重讀結果，再決定是否接續尚未完成的規則。');return;}throw e;}finally{stop.remove();}}));
  siteRecipeAction(box,'預覽整組啟用',()=>run(async()=>{await submit('/recipes/evaluate',{id:v.id});const plan=await submit('/recipes/plan',{id:v.id});if(live())siteRecipeReviewDetail(v,plan);}));
 }
 if(v.active&&v.activation_action_id)siteRecipeAction(box,'回復配方啟用前版本',()=>run(async()=>{try{await submit('/versions/undo',{id:v.activation_action_id,confirm:true});}catch(e){requireRead('回復結果尚未確認；請重讀配方與規則，避免重複寫入。');notice(e.message,true);return;}try{await read();notice('已回復配方與原資料規則');}catch{requireRead('回復已保存；請重讀已保存結果。');}}));
}

function siteRecipeReviewDetail(v,plan){
 const box=modal('確認公開資料配方'),ticket=detailVersion;box.append(el('p',plan.note),el('p','此確認新增 '+plan.maximum_activation_http_requests+' 次 HTTP／0次模型推論。之後的資料同步仍會發出HTTP；完整費用Unknown。'));
 for(const ref of plan.changes){const card=el('div',undefined,'card');card.append(el('h3',connectorKindLabels[ref.kind]||ref.kind),el('p','目前：'+(ref.before_id||'尚無已啟用規則')),el('p','新來源：'+ref.source_url),el('p',ref.config.path+' · '+ref.config.scope+' · '+ref.config.unit),el('p','已通過本機fixture與實際公開GET；認證為NONE。'));box.append(card);}
 const inherited=el('details');inherited.append(el('summary','沿用的原已確認設定'));const clean=x=>Object.fromEntries(Object.entries(x||{}).filter(([k])=>!['generated_recipe_id','notes'].includes(k)));factsView(inherited,'目前設定',clean(plan.before_settings));factsView(inherited,'新配方沿用設定',clean(plan.settings));box.append(inherited);
 let writing=false;const confirm=siteRecipeAction(box,'確認啟用這組公開配方',async()=>{if(writing||ticket!==detailVersion)return;writing=true;confirm.disabled=true;confirm.dataset.locked='true';try{await submit('/recipes/activate',{id:v.id,expected_active_id:plan.expected_active_id,review_fingerprint:plan.fingerprint,confirm:true});try{await loadKnowledge();const fresh=siteRecipes.find(x=>x.id===v.id);if(fresh)siteRecipeDetail(fresh);notice('公開資料配方已啟用');}catch{recover('啟用已保存，請讀取配方確認結果。');}}catch(e){recover(e.status===409?'確認範圍已變，請重讀配方後重新預覽。':'啟用結果尚未確認；請先讀取已保存配方，避免重複寫入。');notice(e.message,true);}});
 const recover=message=>{box.append(el('p',message,'error'));siteRecipeAction(box,'讀取配方確認結果',async()=>{try{const rows=await api('/recipes');const fresh=rows.find(x=>x.id===v.id);if(fresh)siteRecipeDetail(fresh);}catch(e){notice(e.message,true);}});};
 siteRecipeAction(box,'返回配方草稿',()=>siteRecipeDetail(v));
}
`
