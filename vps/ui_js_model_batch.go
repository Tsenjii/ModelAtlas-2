package main

const jsModelBatch = `
let modelBatch=null;
const modelBatchStatus={COMPLETED:"測試完成",CANCELLED:"已停止測試",LIMIT_REACHED:"已暫停：遇到額度、限流或忙碌",DATABASE_ERROR:"資料暫時無法保存",DISCONNECTED:"連線中斷，請查閱已留下的請求紀錄"};
function updateModelBatchActions(){
 const button=$("bulk-test"),progress=$("model-batch-progress");
 if(button)button.disabled=selectedCompare.size<1||selectedCompare.size>100;
 if(progress){progress.hidden=!modelBatch;progress.textContent=modelBatch?.running?"測試中 · "+modelBatch.results.size+" / "+modelBatch.rows.length:"測試結果";}
}
function batchSelectionProblem(rows){
 if(rows.length<1||rows.length>100)return "一次選擇 1–100 個模型。";
 if(rows.some(m=>m.state!=="ACTIVE"||!m.enabled||!chatOperation(targetOperation(m))))return "請選擇已啟用的文字對話模型；其他操作可在 Playground 測試。";
 return "";
}
function openModelBatch(selections=null){
 if(selections&&!modelBatch?.running){
  const rows=selections.map(v=>myModels.find(m=>key(m)===key(v))||v),problem=batchSelectionProblem(rows);
  if(problem){notice(problem,true);return;}
  modelBatch={id:crypto.randomUUID(),rows:rows.map(m=>({...m})),results:new Map(),active:-1,running:false,started:false,status:"",stopRequested:false,render:null};
 }
 if(!modelBatch)return;
 const state=modelBatch,box=modal("批次模型測試"),ticket=detailVersion;
 $("detail").dataset.kind="model-batch";
 box.append(el("p","依序測試選取的精確連線，結果會保留在各模型的實測歷史。","section-description"));
 const progress=el("p",undefined,"operation-feedback"),bar=el("progress"),list=el("div",undefined,"cards"),controls=el("div",undefined,"actions"),nodes=[];
 progress.setAttribute("role","status");progress.setAttribute("aria-live","polite");bar.max=state.rows.length;bar.style.width="100%";bar.setAttribute("aria-label","模型測試進度");
 box.append(progress,bar,list,controls);
 for(const [index,m]of state.rows.entries()){
  const card=item(list,modelDisplayName(m),(providers.find(p=>p.id===m.provider_id)?.name||m.provider_id)+" · "+connectionLabel(m));
  card.dataset.batchModelScope=key(m);const outcome=el("p"),metrics=el("small"),actions=el("div",undefined,"actions");card.append(outcome,metrics,actions);nodes.push({outcome,metrics,actions,index});
 }
 const start=action(controls,"開始測試 "+state.rows.length+" 個模型",()=>runModelBatch(state),false),stop=action(controls,"停止測試",()=>stopModelBatch(state)),save=action(controls,"匯出測試結果",()=>downloadJSON("modelatlas-model-tests.json",{format:"modelatlas-manual-test-batch",id:state.id,status:state.status,results:state.rows.map((m,index)=>({selection:scope(m),...(state.results.get(index)||{outcome:state.status==="DISCONNECTED"?"OUTCOME_UNCONFIRMED":state.started?"NOT_TESTED":"PENDING"})}))}));
 const render=()=>{
  updateModelBatchActions();if(!detailCurrent(ticket))return;
  const completed=state.results.size;bar.value=completed;progress.textContent=(state.running?(state.stopRequested?"正在停止…":"正在測試"):modelBatchStatus[state.status]||"準備測試")+" · "+completed+" / "+state.rows.length;
  if(state.started&&!state.running&&completed<state.rows.length)progress.textContent+=" · "+(state.rows.length-completed)+(state.status==="DISCONNECTED"?" 個結果尚未確認":" 個尚未測試");
  start.hidden=state.started;stop.hidden=!state.running;stop.disabled=state.stopRequested;save.disabled=!state.started;
  for(const node of nodes){const result=state.results.get(node.index);node.outcome.textContent=result?(result.outcome==="SUCCESS"?"可用":result.outcome):state.running&&node.index===state.active?"測試中…":state.status==="DISCONNECTED"?"結果未確認，請查閱請求紀錄":state.started&&!state.running?"尚未取得結果":"等待測試";
   if(result){const d=result.details||{};node.metrics.textContent="首字 "+(d.ttft_ms==null?"尚無資料":d.ttft_ms+" ms")+" · 速率 "+(d.tps==null?"尚無資料":Number(d.tps).toFixed(1)+" tokens/s")+" · 延遲 "+(d.latency_ms==null?"尚無資料":d.latency_ms+" ms");if(d.sample_id&&!node.actions.children.length)action(node.actions,"查看這次請求",()=>requestDetail(d.sample_id));}
  }
 };
 state.render=render;render();
}
function acceptModelBatchEvent(state,event){
 if(event.id!==state.id||event.total!==state.rows.length||!Number.isInteger(event.completed)||event.completed<0||event.completed>state.rows.length)throw Error("測試回應的範圍不符。");
 if(event.kind==="done"){
  if(!Object.hasOwn(modelBatchStatus,event.status)||event.completed!==state.results.size||event.status==="COMPLETED"&&event.completed!==state.rows.length)throw Error("測試回應不完整。");
  state.status=event.status;state.finished=true;state.active=-1;return;
 }
 if(!Number.isInteger(event.index)||event.index<0||event.index>=state.rows.length||key(event.selection||{})!==key(state.rows[event.index]))throw Error("測試回應的連線不符。");
 if(event.kind==="start"){
  if(event.index!==state.results.size||event.completed!==state.results.size||state.active!==-1)throw Error("測試順序不符。");state.active=event.index;
 }else if(event.kind==="result"){
  if(state.active!==event.index||state.results.has(event.index)||event.completed!==state.results.size+1||key(event.result?.selection||{})!==key(state.rows[event.index])||typeof event.result?.outcome!=="string")throw Error("測試結果不完整。");
  state.results.set(event.index,event.result);state.active=-1;
 }else throw Error("無法辨識測試回應。");
}
async function runModelBatch(state){
 if(state!==modelBatch||state.running||state.started)return;
 state.started=state.running=true;state.controller=new AbortController();state.render?.();
 try{
  const response=await fetch(workspaceAPI+"/models/test-batch",{method:"POST",credentials:"same-origin",headers:{"Content-Type":"application/json"},body:JSON.stringify({id:state.id,selections:state.rows.map(scope)}),signal:state.controller.signal});
  if(!response.ok)throw Error(readableProblem(await response.text(),response.status));
  if(!response.headers.get("Content-Type")?.startsWith("application/x-ndjson")||!response.body)throw Error("無法讀取測試進度。");
  const reader=response.body.getReader(),decoder=new TextDecoder();let buffer="";
  while(true){const {value,done}=await reader.read();buffer+=done?decoder.decode():decoder.decode(value,{stream:true});if(buffer.length>2*1024*1024)throw Error("測試回應超過上限。");let newline;while((newline=buffer.indexOf("\n"))>=0){const line=buffer.slice(0,newline);buffer=buffer.slice(newline+1);if(!line.trim())continue;if(state.finished)throw Error("測試完成後收到額外回應。");acceptModelBatchEvent(state,JSON.parse(line));state.render?.();}if(done)break;}
  if(buffer.trim()||!state.finished)throw Error("測試連線中斷，尚未完成的操作不會自動重送。");
 }catch(error){
  state.controller.abort();state.status="DISCONNECTED";state.error=error.message;notice(error.message,true);
 }finally{
  state.running=false;state.active=-1;state.render?.();updateModelBatchActions();await refresh();
 }
}
async function stopModelBatch(state){
 if(!state.running||state.stopRequested)return;state.stopRequested=true;state.render?.();
 try{await submit("/models/test-batch/cancel",{id:state.id});}
 catch(error){if(error.status!==404){state.controller.abort();notice(error.message,true);}}
 finally{state.render?.();}
}
function bindModelBatch(){
 $("bulk-test").addEventListener("click",()=>openModelBatch([...selectedCompare.values()].map(scope)));
 $("model-batch-progress").addEventListener("click",()=>openModelBatch());
}
`
