package main

const jsQueryResearch = `
function reflectionDetail(r){
 const box=modal('本輪研究成效');researchCostView(box,r.report.cost_evidence);factsView(box,'來源、語言、查詢與驗證',r.report);box.append(el('p','反思參照：'+r.id));
 if(r.report.status!=='COMPLETED')return;
 const languages=[...new Set((r.report.quality_observations||[]).filter(q=>q.kind==='RESEARCH_STRATEGY'&&q.status==='SUCCESS'&&q.candidates>0).map(q=>q.subject))];
 for(const language of languages){let requestID=crypto.randomUUID(),pending=false;const button=action(box,'產生 '+language+' 查詢提案',async()=>{if(pending)return;pending=true;button.disabled=true;try{const v=await submit('/research/query-generation',{id:requestID,reflection_id:r.id,language});requestID=crypto.randomUUID();await loadKnowledge();const saved=knowledgeVersions.find(x=>x.id===v.id);if(saved)knowledgeDetail(saved);notice('查詢提案已保存，尚未實測或啟用');}catch(e){notice(e.message,true);}finally{pending=false;if(button.isConnected)button.disabled=false;}});}
}

function queryGenerationView(box,v){
 const p=v.query_generation;if(!p){if(v.query_generation_unavailable)box.append(el("p","此查詢只有歷史，缺少本機生成證據；不能借用其他裝置的執行或評估權限。"));return;}
 const section=el('section',undefined,'card');section.dataset.queryGeneration=v.id;
 section.append(el('h3','公開證據生成的查詢草稿'),el('p',p.language+' · '+p.country+' · 每臂最多 '+p.pages+' 頁'),el('p','原觀測查詢：'+p.observed_query),el('p','新查詢：'+p.query),el('p','生成時額外 HTTP '+p.additional_http_requests+' 次／模型推論 '+p.model_inference_requests+' 次。完整貨幣成本 Unknown。'));
 for(const note of p.notes||[])section.append(el('p',note));
 const sources=el('details');sources.append(el('summary','公開來源與內容指紋'));for(const source of p.public_sources||[]){const a=el('a',source.url);a.href=source.url;a.target='_blank';a.rel='noopener noreferrer';sources.append(a,el('p','SHA256 '+source.sha256));}section.append(sources);
 action(section,'規劃查詢對照',()=>queryComparisonDetail(v));box.append(section);
}

function queryComparisonDetail(v){
 const box=modal('查詢實測對照'),ticket=detailVersion;
 box.append(el('p','先預覽基線與新查詢，再明確確認一組兩臂的公開研究。至少完成3組後評估，不自動啟用。'),el('p','搜尋與公開頁面完整貨幣成本 Unknown。模型推論為0次不代表免費。'));
 const form=el('form'),budget=field(form,'每臂 HTTP 上限','number','4');budget.min='1';budget.max='20';budget.required=true;
 const previewButton=el('button','預覽這組對照');previewButton.type='submit';form.append(previewButton);box.append(form);
 const reviewBox=el('div',undefined,'card'),history=el('div'),feedback=el('p');feedback.setAttribute('role','status');box.append(reviewBox,feedback,history);
 let pending=false,review=null,intent='',requestID=crypto.randomUUID(),nextBefore='',all=[];
 const live=()=>ticket===detailVersion&&box.isConnected;
 const validBudget=()=>Number.isInteger(Number(budget.value))&&Number(budget.value)>=1&&Number(budget.value)<=20;
 function invalidate(){review=null;reviewBox.replaceChildren();requestID=crypto.randomUUID();intent='';}
 budget.addEventListener('input',invalidate);
 const gated=async fn=>{if(pending||!live())return;pending=true;previewButton.disabled=true;budget.disabled=true;try{await fn();}catch(e){if(live()){feedback.textContent=e.message;notice(e.message,true);}}finally{pending=false;if(live()){previewButton.disabled=false;budget.disabled=false;}}};
 async function readHistory(more=false){
  const url='/research/query-comparisons?version_id='+encodeURIComponent(v.id)+(more&&nextBefore?'&before='+encodeURIComponent(nextBefore):'');
  const response=await api(url);if(!live())return;
  all=more?[...all,...response.comparisons]:response.comparisons;nextBefore=response.next_before;history.replaceChildren();
  history.append(el('h3','已確認的對照紀錄'));
  for(const pair of all){const card=el('section',undefined,'card');card.dataset.comparisonId=pair.id;card.append(el('p',new Date(pair.at).toLocaleString()+' · '+pair.language+' / '+pair.country+' · 每臂 '+pair.budget_per_arm+' HTTP 上限'),el('p','基線：'+pair.baseline_query),el('p','提案：'+pair.proposed_query));if(pair.approval_changed)card.append(el('p','查詢、目前基線或搜尋 Key 已變；保留結果，需重新預覽建立對照。','error'));
   for(const arm of pair.arms){const row=el('div',undefined,'card'),name=arm.arm==='BASELINE'?'基線':'提案';row.append(el('p',name+' · '+(({QUEUED:'等待中',RUNNING:'研究中',PAUSED:'已暫停',BUDGET_EXHAUSTED:'已達上限',COMPLETED:'已完成',CANCELLED:'已取消'}[arm.status]||arm.status))+' · '+arm.http_requests+' / '+arm.budget+' 次 HTTP · 模型推論 '+arm.model_inference_requests+' 次'));if(arm.quality)row.append(el('p','搜尋品質效益 '+arm.quality.utility.toFixed(2)+'；搜尋 HTTP '+arm.quality.http_requests+' 次；新線索 '+arm.quality.new_candidates+'，仍未證明免費／身份。'));action(row,'查看'+name+'研究任務',()=>researchRunDetail({id:arm.run_id,status:arm.status,query_comparison:true,summary:{http_requests:arm.http_requests,model_inference_requests:arm.model_inference_requests,total_monetary_cost:null}}));
    const control=(label,command)=>action(row,label,()=>gated(async()=>{await submit('/research/control',{id:arm.run_id,action:command});await readHistory();}));
    if(['QUEUED','RUNNING'].includes(arm.status))control('暫停'+name,'PAUSE');
    if(arm.status==='PAUSED'&&!pair.approval_changed)control('接續'+name,'RESUME');
    if(['QUEUED','RUNNING','PAUSED','BUDGET_EXHAUSTED'].includes(arm.status))control('取消'+name,'CANCEL');card.append(row);
   }researchCostComparisonView(card,pair.cost_comparison);card.append(el('p','完整貨幣成本 Unknown；完整 HTTP 與搜尋品質分母分開。'));history.append(card);
  }
  if(!all.length)history.append(el('p','尚無已確認對照。預覽不會建立輪次或發出請求。'));
  if(nextBefore)action(history,'更多對照紀錄',()=>gated(()=>readHistory(true)));
 }
 action(box,'更新對照進度',()=>gated(async()=>{await readHistory();feedback.textContent='對照進度已更新';}));
 action(box,'返回查詢提案',()=>knowledgeDetail(knowledgeVersions.find(x=>x.id===v.id)||v));
 form.addEventListener('submit',e=>{e.preventDefault();gated(async()=>{if(!validBudget())throw Error('每臂上限須為1至20次。');const value=Number(budget.value);review=await submit('/research/query-comparisons/plan',{version_id:v.id,budget_per_arm:value});if(!live())return;intent=v.id+':'+value;requestID=crypto.randomUUID();reviewBox.replaceChildren();reviewBox.append(el('h3','確認這組公開查詢'),el('p','基線：'+review.baseline_query),el('p','新查詢：'+review.proposed_query),el('p',review.language+' / '+review.country+' · 每臂最多 '+review.pages_per_arm+' 頁；兩臂共最多 '+review.maximum_http_requests+' 次 HTTP；模型推論 '+review.model_inference_requests+' 次。'),el('p',review.note));
   const confirm=action(reviewBox,'確認開始這組對照',()=>gated(async()=>{if(!review||intent!==v.id+':'+Number(budget.value))throw Error('查詢設定已變，請重新預覽。');const originalID=requestID;confirm.disabled=true;try{await submit('/research/query-comparisons',{id:originalID,version_id:v.id,budget_per_arm:Number(budget.value),review_fingerprint:review.fingerprint});if(!live())return;review=null;reviewBox.replaceChildren();feedback.textContent='這組對照已保存；更新進度後再評估。';requestID=crypto.randomUUID();await readHistory();}catch(e){if(live()){confirm.disabled=false;if(e.status===409){review=null;confirm.disabled=true;feedback.textContent='確認範圍已變，請重新預覽。';}else feedback.textContent='讀寫未確認；可重試同一組，系統不重複建立輪次。';}notice(e.message,true);return;}}));
  });});
 readHistory().catch(e=>{if(live()){feedback.textContent='對照紀錄讀取失敗；更新進度可重試。';notice(e.message,true);}});
}
`
