package main

const jsMacAlignment = `
function applyAccent(value){if(!["graphite","violet","indigo"].includes(value))value="graphite";document.documentElement.dataset.accent=value;$("appearance-accent").value=value;}
function renderRecentConversations(){
 const box=$("sidebar-conversations");if(!box)return;box.replaceChildren();const term=$("sidebar-conversation-search").value.toLocaleLowerCase(),current=$("session").value;
 const rows=(sessions||[]).filter(s=>!s.archived&&String(s.title||"新對話").toLocaleLowerCase().includes(term)).slice(0,24);
 for(const s of rows){const button=el("button",undefined,"sidebar-conversation"),name=s.title||"新對話";button.type="button";button.setAttribute("aria-label","開啟對話："+name);button.setAttribute("aria-current",String(s.id===current));button.disabled=busy;button.append(el("span",name),el("small",modelDisplayName(s)||s.model_id||""));button.addEventListener("click",()=>{if(busy||![...$("session").options].some(n=>n.value===s.id))return;show("playground");workspace("playground","work-chat");$("session").value=s.id;$("session").dispatchEvent(new Event("change"));renderRecentConversations();});box.append(button);}
 if(!rows.length)box.append(el("p",term?"沒有符合的近期對話":"尚無這個連線的對話","sidebar-caption"));$("sidebar-new-session").disabled=busy||!$("target").value;$("sidebar-history").disabled=busy;
}
function bindMacAlignment(){
 let accent="graphite",reduced=false;try{accent=localStorage.getItem("modelatlas.accent")||accent;reduced=localStorage.getItem("modelatlas.reduce-motion")==="true";}catch{}
 applyAccent(accent);document.documentElement.dataset.reduceMotion=String(reduced);$("appearance-reduce-motion").checked=reduced;
 $("appearance-accent").addEventListener("change",()=>{const value=$("appearance-accent").value;applyAccent(value);try{localStorage.setItem("modelatlas.accent",value);}catch{}});
 $("appearance-reduce-motion").addEventListener("change",()=>{const value=$("appearance-reduce-motion").checked;document.documentElement.dataset.reduceMotion=String(value);try{localStorage.setItem("modelatlas.reduce-motion",String(value));}catch{}});
 $("sidebar-conversation-search").addEventListener("input",renderRecentConversations);$("sidebar-new-session").addEventListener("click",()=>{if(busy)return;show("playground");workspace("playground","work-chat");newConversation().catch(e=>notice(e.message,true));});$("sidebar-history").addEventListener("click",()=>{if(!busy)$("conversation-library").click();});renderRecentConversations();
}
`
