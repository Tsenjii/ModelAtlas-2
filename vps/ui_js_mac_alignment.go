package main

// Non-sensitive appearance preferences may persist. Conversation drafts and
// attachments remain in this browser tab's memory and are never sent as metadata.
const jsMacAlignment = `
const composerDrafts=new Map(),lastConversationByScope=new Map();let composerDraftOwner="";
function composerDraftKey(){return $("target").value?(chatOperation(selectedOperation())?"session:"+(sessionScope===$("target").value&&$("session").value?$("session").value:$("target").value):"media:"+$("target").value):"";}
function rememberComposerDraft(){if(sessionScope&&$("session").value)lastConversationByScope.set(sessionScope,$("session").value);if(composerDraftOwner)composerDrafts.set(composerDraftOwner,{text:$("prompt").value,attachments:attachments.slice()});}
function restoreComposerDraft(){const id=composerDraftKey();if(id===composerDraftOwner)return;const previous=composerDraftOwner;rememberComposerDraft();if(previous==="session:"+$("target").value&&id!==previous){const pending=composerDrafts.get(previous);if(pending&&(pending.text||pending.attachments.length))composerDrafts.set(id,pending);composerDrafts.delete(previous);}composerDraftOwner=id;const draft=composerDrafts.get(id);$("prompt").value=draft?.text||"";attachments=draft?.attachments.slice()||[];renderAttachments();$("prompt").dispatchEvent(new Event("input"));}
function prepareComposerSelection(){rememberComposerDraft();sessionScope="";sessionListVersion++;loadVersion++;$("session").replaceChildren();restoreComposerDraft();chatEmpty(true);chatHistorySession="";chatHistoryStamp=null;chatHistoryReady();updatePlayground();}
function applyAccent(value){if(!["graphite","violet","indigo"].includes(value))value="graphite";document.documentElement.dataset.accent=value;$("appearance-accent").value=value;}
let sidebarRows=[],sidebarQuery="",sidebarOffset=0,sidebarMore=false,sidebarRevision=0,sidebarPending=false,sidebarProblem="",sidebarSearchTimer=null;
function sidebarParameters(){const params=new URLSearchParams({q:$("sidebar-conversation-search").value,state:$("sidebar-session-state").value});if($("sidebar-session-current").checked&&$("target").value)for(const [k,v]of new URLSearchParams(query(JSON.parse($("target").value))))params.set(k,v);return params;}
async function refreshSidebarSessions(more=false){
 const params=sidebarParameters(),requested=params.toString(),revision=++sidebarRevision;sidebarPending=true;sidebarProblem="";renderRecentConversations();params.set("limit","50");params.set("offset",String(more&&requested===sidebarQuery?sidebarOffset:0));
 try{const rows=await api("/sessions?"+params);if(revision!==sidebarRevision||requested!==sidebarParameters().toString())return;const previous=more&&requested===sidebarQuery?sidebarRows:[],ids=new Set(previous.map(s=>s.id));if(rows.some(s=>ids.has(s.id)))throw Error("對話清單已更新，請重新搜尋。");sidebarRows=[...previous,...rows];sidebarQuery=requested;sidebarOffset=sidebarRows.length;sidebarMore=rows.length===50;}
 catch(e){if(revision===sidebarRevision)sidebarProblem=e.message;}
 finally{if(revision===sidebarRevision){sidebarPending=false;renderRecentConversations();}}
}
function renderRecentConversations(){
 const box=$("sidebar-conversations");if(!box)return;box.replaceChildren();const current=$("session").value;
 for(const s of sidebarRows){const button=el("button",undefined,"sidebar-conversation"),name=s.title||"新對話";button.type="button";button.dataset.sessionId=s.id;button.setAttribute("aria-label","開啟對話："+name);button.setAttribute("aria-current",String(s.id===current));button.disabled=busy;const title=el("span",(s.pinned?"置頂 · ":"")+name);button.append(title,el("small",(providers.find(p=>p.id===s.provider_id)?.name||s.provider_id)+" · "+(modelDisplayName(s)||s.model_id||"")+(s.archived?" · 已封存":"")));button.title=connectionLabel(s);button.addEventListener("click",()=>selectSidebarConversation(s).catch(e=>notice(e.message,true)));box.append(button);}
 const state=$("sidebar-session-status");state.replaceChildren();if(sidebarPending)state.append(el("small","搜尋中…"));if(sidebarProblem){state.append(el("p",sidebarProblem+(sidebarRows.length?" 仍顯示上次結果。":"")));action(state,"重新載入",()=>refreshSidebarSessions());}
 if(!sidebarRows.length&&!sidebarPending&&!sidebarProblem)box.append(el("p",$("sidebar-conversation-search").value?"沒有符合的對話":"尚無對話","sidebar-caption"));
 $("sidebar-session-more").hidden=!sidebarMore;$("sidebar-session-more").disabled=sidebarPending;$("sidebar-new-session").disabled=busy||!$("target").value;$("sidebar-history").disabled=busy;
}
let conversationSwitchRevision=0;
async function selectSidebarConversation(session){
 if(busy)return;if(session.archived||!pickerAvailable().some(m=>key(m)===key(session))){await conversationDetail(session);return;}
 rememberComposerDraft();fileReadVersion++;loadVersion++;const revision=++conversationSwitchRevision;$("target").value=key(session);prepareComposerSelection();pendingTools=[];renderToolResults();resetMediaDraft();updatePlayground();await sessionSelectRefresh(session.id);if(revision!==conversationSwitchRevision)return;restoreComposerDraft();show("playground");workspace("playground","work-chat");await loadChat();renderRecentConversations();
}
function syncNativeShell(){
 const sidebar=$("sidebar-chat-section");if(sidebar)sidebar.hidden=currentPane!=="playground";document.querySelectorAll(".bottom-nav [data-tab]").forEach(b=>{let count=b.querySelector(".nav-count");if(!count){count=el("small",undefined,"nav-count");count.setAttribute("aria-hidden","true");b.append(count);}count.textContent=b.dataset.tab==="providers"?providers.length:b.dataset.tab==="models"?myModels.filter(m=>m.state==="ACTIVE").length:"";count.hidden=b.dataset.tab==="playground";});
}
function bindNativeShell(){
 bindNativeAttachments();const picker=$("model-picker-open");$("send-form").querySelector(".composer-tools").insertBefore(picker,$("send-form").querySelector(".send-actions"));picker.querySelector("kbd").hidden=true;$("chat-ui").querySelector(".conversation-controls").prepend($("new-session"));
 $("header-refresh").addEventListener("click",async()=>{if($("header-refresh").disabled)return;try{await refresh();await refreshSidebarSessions();}catch(e){/* Workspace and sidebar retain their own retry feedback. */}});
 $("header-assistant").addEventListener("click",()=>{show("playground");workspace("playground",$("assistant-panel").hidden?"assistant-panel":"work-chat");});$("sidebar-model-picker").addEventListener("click",openModelPicker);
 let hidden=false;try{hidden=localStorage.getItem("modelatlas.sidebar-hidden")==="true";}catch{}const setSidebar=value=>{document.documentElement.dataset.sidebarHidden=String(value);$("sidebar-toggle").setAttribute("aria-pressed",String(value));};setSidebar(hidden);$("sidebar-toggle").addEventListener("click",()=>{hidden=!hidden;setSidebar(hidden);try{localStorage.setItem("modelatlas.sidebar-hidden",String(hidden));}catch{}});
 document.addEventListener("keydown",e=>{if(e.isComposing||e.keyCode===229||e.repeat||document.querySelector("dialog[open]")||e.altKey)return;const key=e.key.toLowerCase(),command=e.ctrlKey||e.metaKey;if(!command)return;if(key===","&&!e.shiftKey){e.preventDefault();show("settings");}else if(key==="n"&&!e.shiftKey){e.preventDefault();if(!busy)newConversation().catch(e=>notice(e.message,true));}else if(e.metaKey&&key==="l"&&!e.shiftKey){e.preventDefault();show("playground");workspace("playground","work-chat");$("prompt").focus();}else if(e.metaKey&&e.ctrlKey&&key==="s"){e.preventDefault();$("sidebar-toggle").click();}});
 syncNativeShell();
}
function bindMacAlignment(){
 let accent="graphite",reduced=false;try{accent=localStorage.getItem("modelatlas.accent")||accent;reduced=localStorage.getItem("modelatlas.reduce-motion")==="true";}catch{}
 applyAccent(accent);document.documentElement.dataset.reduceMotion=String(reduced);$("appearance-reduce-motion").checked=reduced;
 $("appearance-accent").addEventListener("change",()=>{const value=$("appearance-accent").value;applyAccent(value);try{localStorage.setItem("modelatlas.accent",value);}catch{}});
 $("appearance-reduce-motion").addEventListener("change",()=>{const value=$("appearance-reduce-motion").checked;document.documentElement.dataset.reduceMotion=String(value);try{localStorage.setItem("modelatlas.reduce-motion",String(value));}catch{}});
 $("sidebar-conversation-search").addEventListener("input",()=>{sidebarRevision++;clearTimeout(sidebarSearchTimer);sidebarSearchTimer=setTimeout(()=>refreshSidebarSessions(),200);});for(const id of ["sidebar-session-state","sidebar-session-current"])$(id).addEventListener("change",()=>refreshSidebarSessions());$("sidebar-session-more").addEventListener("click",()=>refreshSidebarSessions(true));
 $("sidebar-new-session").addEventListener("click",()=>{if(busy)return;show("playground");workspace("playground","work-chat");newConversation().catch(e=>notice(e.message,true));});$("sidebar-history").addEventListener("click",()=>{if(!busy)$("conversation-library").click();});renderRecentConversations();
}
`
