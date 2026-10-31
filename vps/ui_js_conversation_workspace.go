package main

// Conversation presentation reuses the existing scoped APIs and revision CAS.
// Ambiguous writes are recovered by reading; they are never automatically sent
// again. Drafts and pending operations remain local to the current app page.
const jsConversationWorkspace = `
const conversationChanges=new Map(),conversationNameDrafts=new Map();
let conversationEditor=null;
function stopConversationLibrary(){const cleanup=conversationLibraryCleanup;conversationLibraryCleanup=null;cleanup?.();}
function conversationUpdatedLabel(session){const at=session.updated_at||session.created_at;if(!at||!Number.isFinite(at))return "";const date=new Date(at);return Number.isNaN(date.getTime())?"":new Intl.DateTimeFormat("zh-TW",{year:date.getFullYear()===new Date().getFullYear()?undefined:"numeric",month:"numeric",day:"numeric",hour:"2-digit",minute:"2-digit"}).format(date);}
function conversationCard(parent,session){
 const provider=providers.find(p=>p.id===session.provider_id),model=modelDisplayName(session),operation=targetOperation(session),endpoint=(operationLabels[operation]||operation)+(session.endpoint!==operation?" · "+session.endpoint:""),row=item(parent,session.title,(provider?.name||session.provider_id)+" · "+model+"\n"+connectionLabel(session)+" · "+endpoint,()=>conversationDetail(session));
 row.dataset.sessionId=session.id;row.classList.add("conversation-card");
 if(session.pinned){const star=icon("star");star.removeAttribute("aria-hidden");star.setAttribute("aria-label","已置頂");star.setAttribute("role","img");row.querySelector("strong").prepend(star);}
 const preview=el("p",session.preview||"尚無訊息","conversation-card-preview"),meta=el("span",undefined,"conversation-card-meta");
 meta.append(el("span",session.message_count+" 則訊息"));const date=conversationUpdatedLabel(session);if(date){const time=el("time",date);time.dateTime=new Date(session.updated_at||session.created_at).toISOString();time.title="更新於 "+new Date(session.updated_at||session.created_at).toLocaleString("zh-TW");meta.append(time);}row.append(preview,meta);return row;
}
async function conversationLibrary(){
 const box=modal("對話紀錄"),ticket=detailVersion,filters=el("div",undefined,"conversation-library-tools"),q=field(filters,"搜尋對話","search"),state=el("select"),stateLabel=el("label","狀態"),onlyLabel=el("label","僅目前模型","check"),only=el("input");
 $("detail").dataset.kind="conversations";q.placeholder="標題、模型或對話內容";q.autocomplete="off";q.maxLength=256;only.type="checkbox";onlyLabel.prepend(only);state.setAttribute("aria-label","狀態");option(state,"ACTIVE","使用中");option(state,"ARCHIVED","已封存");option(state,"ALL","全部");stateLabel.append(state);filters.append(stateLabel,onlyLabel);box.append(filters);
 const summary=el("div",undefined,"conversation-library-summary"),count=el("p","正在讀取對話…"),clear=el("button","清除搜尋","text-button"),status=el("div",undefined,"detail-state conversation-library-status"),rowsBox=el("div",undefined,"cards conversation-list"),more=el("button","顯示更多對話","ghost");
 count.setAttribute("role","status");count.setAttribute("aria-live","polite");clear.type=more.type="button";clear.hidden=more.hidden=status.hidden=true;clear.setAttribute("aria-label","清除對話搜尋");summary.append(count,clear);box.append(summary,status,rowsBox,more);
 let offset=0,displayed=0,hasResult=false,timer,controller=null,composing=false;
 const current=version=>detailCurrent(ticket)&&version===libraryVersion;
 const feedback=(text,bad=false,retry=null)=>{status.hidden=!text;status.classList.toggle("detail-state-error",bad);status.setAttribute("role",bad?"alert":"status");status.replaceChildren();if(text)status.append(el("p",text));if(retry)action(status,"重新讀取對話紀錄",retry);};
 const invalidate=()=>{clearTimeout(timer);libraryVersion++;controller?.abort();rowsBox.setAttribute("aria-busy","false");more.disabled=false;clear.hidden=!q.value;};
 const load=async(append=false)=>{
  if(!detailCurrent(ticket))return;controller?.abort();const readController=new AbortController();controller=readController;const version=++libraryVersion,nextOffset=append?offset:0;
  more.disabled=true;rowsBox.setAttribute("aria-busy","true");clear.hidden=!q.value;feedback(hasResult?"正在讀取，保留上次成功的結果…":"正在讀取對話…");
  try{
   const args=new URLSearchParams({q:q.value,state:state.value,limit:"50",offset:String(nextOffset)});if(only.checked&&$("target").value)for(const [key,value]of new URLSearchParams(query(JSON.parse($("target").value))))args.set(key,value);
   const rows=await api("/sessions?"+args,{signal:readController.signal});if(!current(version))return;
   if(!append){rowsBox.replaceChildren();displayed=0;}
   const existing=new Set([...rowsBox.querySelectorAll("[data-session-id]")].map(n=>n.dataset.sessionId));for(const session of rows)if(!existing.has(session.id)){conversationCard(rowsBox,session);existing.add(session.id);displayed++;}
   offset=nextOffset+rows.length;hasResult=true;count.textContent=displayed?"已顯示 "+displayed+" 段對話":"沒有符合的對話";more.hidden=rows.length<50;feedback("");
   if(!displayed){const empty=el("div",undefined,"empty-state conversation-library-empty");empty.append(icon("search"),el("h3",q.value?"試試其他關鍵字":"這裡還沒有對話"),el("p",q.value?"可以搜尋對話標題、模型或已保存的訊息內容。":"新對話會出現在這裡，封存紀錄可切換狀態查看。"));rowsBox.append(empty);}
  }catch(error){if(!current(version)||readController.signal.aborted)return;if(!hasResult)count.textContent="對話尚未讀取成功";feedback("讀取對話失敗。"+(hasResult?"上次成功的結果仍保留。":"")+error.message,true,()=>load(append));}
  finally{if(current(version)){rowsBox.setAttribute("aria-busy","false");more.disabled=false;}}
 };
 const search=()=>{invalidate();if(!composing)timer=setTimeout(()=>load(),250);};
 q.addEventListener("input",search);q.addEventListener("compositionstart",()=>{composing=true;invalidate();});q.addEventListener("compositionend",()=>{composing=false;search();});q.addEventListener("keydown",event=>{if(event.key==="Enter"&&!event.isComposing&&!composing){event.preventDefault();invalidate();load();}});
 for(const input of [state,only])input.addEventListener("change",()=>{invalidate();load();});more.addEventListener("click",()=>load(true));clear.addEventListener("click",()=>{q.value="";invalidate();load();q.focus();});
 const cleanup=()=>{invalidate();$("detail").removeEventListener("close",closed);},closed=()=>{if(conversationLibraryCleanup===cleanup)stopConversationLibrary();};conversationLibraryCleanup=cleanup;$("detail").addEventListener("close",closed,{once:true});q.focus();await load();
}
function notifyConversationChange(id,event={}){if(conversationEditor?.session.id===id&&detailCurrent(conversationEditor.ticket))conversationEditor.paint(event);}
async function updateConversation(editor,patch){
 const id=editor.session.id;if(!detailCurrent(editor.ticket)||conversationChanges.has(id))return;if(busy){editor.paint({kind:"REJECTED",message:"請先等待目前請求完成，或停止後再修改對話。"});return;}
 const operation={phase:"PENDING",session:{...editor.session},patch,editorTicket:editor.ticket,draftVersion:editor.draftVersion,result:null,reading:false,problem:null};conversationChanges.set(id,operation);notifyConversationChange(id);
 let acknowledged=false;
 try{
  const result=await submit("/sessions/update",{session_id:id,revision:operation.session.revision,...patch});acknowledged=true;operation.phase="SAVED";operation.result={...operation.session,...result};notifyConversationChange(id,{kind:"ACK",operation});
  await refresh();if(conversationChanges.get(id)===operation)conversationChanges.delete(id);notifyConversationChange(id,{kind:"COMPLETE",message:"對話已更新。"});
 }catch(error){
  operation.problem=error.message;
  if(!acknowledged&&[400,422].includes(error.status)){if(conversationChanges.get(id)===operation)conversationChanges.delete(id);notifyConversationChange(id,{kind:"REJECTED",message:"變更未保存。"+error.message});}
  else{operation.phase=acknowledged?"SAVED":"UNKNOWN";notifyConversationChange(id);if(!conversationEditor||conversationEditor.session.id!==id||!detailCurrent(conversationEditor.ticket))notice(acknowledged?"對話已保存，重新開啟後可重讀清單。":"對話操作結果尚未確認，重新開啟後請先讀取保存狀態。",true);}
 }
}
async function recoverConversationChange(id){
 const operation=conversationChanges.get(id);if(!operation||operation.phase==="PENDING"||operation.reading)return;operation.reading=true;notifyConversationChange(id);
 try{
  const rows=await api("/sessions?session_id="+encodeURIComponent(id)+"&state=ALL&limit=1");if(rows.length!==1||rows[0].id!==id)throw Error("這段對話目前無法讀取，名稱草稿仍保留。");
  operation.result=rows[0];operation.phase="VERIFIED";operation.problem=null;notifyConversationChange(id,{kind:"READ",operation});await refresh();if(conversationChanges.get(id)===operation)conversationChanges.delete(id);notifyConversationChange(id,{kind:"COMPLETE",message:"已讀取目前保存內容；名稱草稿保留，沒有重新提交變更。"});
 }catch(error){operation.problem=error.message;}finally{operation.reading=false;notifyConversationChange(id);}
}
function conversationDetail(session){
 const operation=conversationChanges.get(session.id),saved=operation?.result?{...session,...operation.result}:session,box=modal(saved.title),ticket=detailVersion,form=el("form",undefined,"conversation-title-form"),draft=conversationNameDrafts.get(session.id),title=field(form,"對話名稱","text",draft??saved.title),submitButton=el("button","儲存名稱"),reset=el("button","還原已保存名稱","ghost"),formActions=el("div",undefined,"conversation-title-actions"),dirty=el("p",undefined,"conversation-draft-hint"),status=el("div",undefined,"detail-state conversation-change-status"),actions=el("div",undefined,"actions conversation-management-actions");
 $("detail").dataset.kind="conversation";box.append(el("p",modelDisplayName(saved)+" · "+(providers.find(p=>p.id===saved.provider_id)?.name||saved.provider_id)+" / "+connectionLabel(saved),"media-hint"));title.maxLength=200;title.required=true;submitButton.type="submit";reset.type="button";formActions.append(submitButton,reset);form.append(formActions,dirty);status.hidden=true;box.append(form,status,actions);
 const editor={session:{...saved},ticket,draftVersion:draft===undefined?0:1,message:"",bad:false,paint:null};conversationEditor=editor;
 const pin=el("button",undefined,"ghost"),archive=el("button",undefined,"ghost"),use=el("button","使用此對話","ghost");pin.type=archive.type=use.type="button";actions.append(pin,archive);
 for(const [format,label]of [["json","下載完整 JSON"],["markdown","下載 Markdown"]]){const a=el("a",label,"link-button");a.href="/api/v1/sessions/export?session_id="+encodeURIComponent(session.id)+"&format="+format;a.download="";actions.append(a);}actions.append(use);action(actions,"返回對話紀錄",conversationLibrary);
 const feedback=(text,bad,retry)=>{status.hidden=!text;status.classList.toggle("detail-state-error",bad);status.setAttribute("role",bad?"alert":"status");status.replaceChildren();if(text)status.append(el("p",text));if(retry)action(status,retry,()=>recoverConversationChange(session.id));};
 editor.paint=(event={})=>{
  if(conversationEditor!==editor||!detailCurrent(ticket))return;
  if(event.operation?.result&&["ACK","READ"].includes(event.kind)){
   const op=event.operation,keep=event.kind==="READ"?editor.draftVersion>0:op.editorTicket===ticket?editor.draftVersion!==op.draftVersion:editor.draftVersion>0;
   editor.session={...editor.session,...op.result};if(!keep)title.value=editor.session.title;
   if(title.value===editor.session.title){conversationNameDrafts.delete(session.id);editor.draftVersion=0;}
   $("detail-title").textContent=editor.session.title;
  }
  if(event.kind==="COMPLETE"||event.kind==="REJECTED"){editor.message=event.message;editor.bad=event.kind==="REJECTED";}
  const op=conversationChanges.get(session.id),blocked=!!op;form.toggleAttribute("aria-busy",blocked);if(blocked)form.setAttribute("aria-busy","true");submitButton.disabled=blocked||busy||!title.value.trim()||title.value.trim()===editor.session.title;pin.disabled=archive.disabled=use.disabled=blocked||busy;pin.textContent=editor.session.pinned?"取消置頂":"置頂對話";archive.textContent=editor.session.archived?"取消封存":"封存對話";
  use.hidden=editor.session.archived||!pickerAvailable().some(m=>key(m)===key(editor.session));const changed=title.value!==editor.session.title;dirty.hidden=!changed;dirty.textContent=changed?"名稱草稿尚未儲存":"";reset.hidden=!changed;
  if(op?.reading)feedback("正在讀取保存狀態，名稱草稿保留…",false);
  else if(op?.phase==="PENDING")feedback("正在保存，仍可繼續編輯名稱草稿…",false);
  else if(op?.phase==="SAVED")feedback(op.problem?"變更已保存，但清單讀取失敗。"+op.problem:"變更已保存，正在更新清單…",!!op.problem,op.problem?"重新讀取清單":null);
  else if(op?.phase==="VERIFIED")feedback(op.problem?"已讀取保存內容，但清單仍未更新。"+op.problem:"已讀取保存內容，正在更新清單…",!!op.problem,op.problem?"重新讀取清單":null);
  else if(op)feedback("操作結果尚未確認。請先讀取保存狀態，避免重複提交。"+(op.problem||""),true,"讀取保存狀態");
  else feedback(editor.message,editor.bad);
 };
 title.addEventListener("input",()=>{editor.draftVersion++;conversationNameDrafts.set(session.id,title.value);editor.paint();});reset.addEventListener("click",()=>{title.value=editor.session.title;editor.draftVersion++;conversationNameDrafts.set(session.id,title.value);editor.paint();title.focus();});form.addEventListener("submit",event=>{event.preventDefault();updateConversation(editor,{title:title.value.trim()});});pin.addEventListener("click",()=>updateConversation(editor,{pinned:!editor.session.pinned}));archive.addEventListener("click",()=>updateConversation(editor,{archived:!editor.session.archived}));
 use.addEventListener("click",async()=>{if(busy||conversationChanges.has(session.id)||editor.session.archived)return;if(!pickerAvailable().some(m=>key(m)===key(editor.session))){editor.paint({kind:"REJECTED",message:"目前沒有可選的相同連線，仍可檢視與匯出這段對話。"});return;}try{rememberComposerDraft();fileReadVersion++;$("target").value=key(editor.session);updatePlayground();await sessionSelectRefresh(session.id);if(!detailCurrent(ticket))return;$("detail").close();await loadChat();}catch(error){notice(error.message,true);}});
 const preview=el("div",undefined,"conversation-preview"),older=el("button","讀取更早訊息","ghost");older.type="button";preview.append(older);box.append(preview);let offset=0,reading=false;
 older.addEventListener("click",async()=>{if(reading)return;reading=true;older.disabled=true;try{const rows=await api("/messages?session_id="+encodeURIComponent(session.id)+"&limit=100&offset="+offset);if(!detailCurrent(ticket))return;const fragment=document.createDocumentFragment();rows.forEach(m=>historyMessage(fragment,m));older.after(fragment);offset+=rows.length;older.hidden=rows.length<100;}catch(error){if(detailCurrent(ticket))notice(error.message,true);}finally{reading=false;older.disabled=false;}});editor.paint();older.click();
}
`
