package main

// Acknowledged writes are applied before optional list reads. Recovery is GET
// only; lost POST responses remain unconfirmed. A per-intent request identity
// lets the server deduplicate retries performed by the HTTP transport.
const jsConversationCreation = `
const conversationCreations=new Map();
function renderConversationCreation(){
 const op=conversationCreations.get($("target").value),panel=$("conversation-create-state"),retry=$("conversation-create-retry"),creating=op?.state==="creating",reading=op?.state==="reading";
 panel.hidden=!op||op.state==="ready"||!chatOperation(selectedOperation())||!op.wasProblem;
 $("new-session").setAttribute("aria-busy",String(creating));$("new-session").title=creating?"正在建立新對話":"建立新對話";
 if(!op)return;
 panel.dataset.state=op.state;panel.setAttribute("aria-busy",String(reading));retry.disabled=busy||creating||reading;
 $("conversation-create-title").textContent=op.created?"新對話已建立":"尚未確認建立結果";
 $("conversation-create-copy").textContent=reading?"正在重新讀取對話清單…":op.created?"清單暫時無法更新。這段對話仍可使用。":"請先重新讀取清單，確認是否已有新對話。原本的草稿仍會保留。";
 const selection=JSON.parse(op.scope);$("conversation-create-scope").textContent=modelDisplayName(selection)+" · "+connectionLabel(selection);
}
function conversationListReadComplete(scope){const op=conversationCreations.get(scope);if(op&&op.state!=="creating"){op.state="ready";renderConversationCreation();}}
function adoptCreatedConversation(created,scope){
 if($("target").value!==scope)return false;
 sessionListVersion++;loadVersion++;fileReadVersion++;
 applySessionRows([created,...sessions.filter(s=>s.id!==created.id&&key(s)===scope)],scope,created.id);
 composerDraftOwner="session:"+created.id;composerDrafts.delete(composerDraftOwner);attachments=[];$("prompt").value="";renderAttachments();$("prompt").dispatchEvent(new Event("input"));
 pendingTools=[];renderToolResults();$("tools-json").value="";
 $("chat-system").value="";$("chat-max-tokens").value="512";$("chat-streaming").checked=true;chatSettingsSession=created.id;chatSettingsDirty=false;
 chatHistorySession=created.id;chatHistoryStamp=JSON.stringify([]);chatEmpty(true);chatHistoryReady();return true;
}
function rereadConversationCreation(op){
 if(!op||op!==conversationCreations.get(op.scope)||$("target").value!==op.scope||busy)return Promise.resolve();
 if(op.task)return op.task;
 const retry=$("conversation-create-retry"),restoreFocus=document.activeElement===retry,preferred=$("session").value||op.created?.id||"";
 op.state="reading";renderConversationCreation();
 op.task=(async()=>{
  try{
   const applied=await sessionSelectRefresh(preferred);
   op.state="ready";
   if(applied&&!busy){restoreComposerDraft();await loadChat();}
   if(!op.created&&op===conversationCreations.get(op.scope)&&$("target").value===op.scope)notice("對話清單已更新，請確認是否已有新對話。");
  }catch(error){op.state=op.created?"read-error":"unconfirmed";op.wasProblem=true;}
  finally{
   op.task=null;renderConversationCreation();
   if(restoreFocus&&op.state==="ready"&&$("target").value===op.scope&&(document.activeElement===retry||document.activeElement===document.body))$("new-session").focus({preventScroll:true});
  }
 })();return op.task;
}
async function newConversation(){
 if(busy||!$("target").value)return;
 rememberComposerDraft();const selectedScope=$("target").value,selection=JSON.parse(selectedScope);
 if(!chatOperation(targetOperation(selection))){attachments=[];$("prompt").value="";renderAttachments();resetMediaDraft();updatePlayground();return;}
 const op={scope:selectedScope,state:"creating",created:null,task:null,wasProblem:false,requestID:crypto.randomUUID()};conversationCreations.set(selectedScope,op);sessionListVersion++;setBusy(true,false);
 try{
  const created=await submit("/sessions",{...selection,title:"新對話 · "+selection.model_id,client_request_id:op.requestID});
  if(!created?.id||key(created)!==selectedScope)throw Error("unconfirmed conversation response");
  op.created=created;lastConversationByScope.set(selectedScope,created.id);adoptCreatedConversation(created,selectedScope);
 }catch(error){op.state="unconfirmed";op.wasProblem=true;}
 finally{setBusy(false,false);}
 if(op.created){if($("target").value===selectedScope)await rereadConversationCreation(op);else{op.state="read-error";op.wasProblem=true;renderConversationCreation();if(!busy){restoreComposerDraft();await loadChat();}}}
}
function bindConversationCreation(){$("conversation-create-retry").addEventListener("click",()=>rereadConversationCreation(conversationCreations.get($("target").value)));}
`
