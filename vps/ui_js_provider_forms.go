package main

const jsProviderForms = `
const providerPending=new WeakSet(),providerFeedbackNodes=new WeakMap(),providerAccountLocks=new Map();
function syncProviderLocks(){document.querySelectorAll("[data-account-lock]").forEach(n=>n.disabled=providerAccountLocks.has(n.dataset.accountLock));}
// Private drafts exist only in browser memory, never localStorage or logs.
const providerDrafts=new Map(),providerDraftIdentities=new Map();
function providerDraftIDs(provider){if(!providerDraftIdentities.has(provider))providerDraftIdentities.set(provider,{key:crypto.randomUUID(),account:crypto.randomUUID(),endpoint:crypto.randomUUID()});return providerDraftIdentities.get(provider);}
function captureProviderDraft(form){if(!form?.id||!form.dataset.providerId)return;const id=JSON.stringify([form.dataset.providerId,form.id]),old=providerDrafts.get(id);if(old&&old.node!==form)return;providerDrafts.set(id,{node:form,open:form.parentElement.open,values:[...form.querySelectorAll("input,select,textarea")].map(n=>({value:n.value,checked:n.checked}))});}
function rememberProviderDrafts(box,provider){for(const form of box.querySelectorAll("form")){form.dataset.providerId=provider;const id=JSON.stringify([provider,form.id]),saved=providerDrafts.get(id),fields=[...form.querySelectorAll("input,select,textarea")];if(saved){fields.forEach((n,i)=>{const v=saved.values[i];if(!v)return;if(n.tagName!=="SELECT"||[...n.options].some(o=>o.value===v.value))n.value=v.value;if(n.type==="checkbox")n.checked=v.checked;});form.parentElement.open=saved.open;}providerDrafts.delete(id);captureProviderDraft(form);for(const type of ["input","change"])form.addEventListener(type,()=>captureProviderDraft(form));form.parentElement.addEventListener("toggle",()=>captureProviderDraft(form));}box.querySelector("#provider-rotate-form select[aria-label='Key 操作']")?.dispatchEvent(new Event("change"));}
function clearProviderFields(form,fields){const current=providerDrafts.get(JSON.stringify([form.dataset.providerId,form.id]))?.node,original=[...form.querySelectorAll("input,select,textarea")],latest=current?[...current.querySelectorAll("input,select,textarea")]:[];for(const input of fields){const index=original.indexOf(input);if(current&&current!==form&&latest[index]?.value===input.value)latest[index].value="";input.value="";}if(current)captureProviderDraft(current);captureProviderDraft(form);}
function providerFeedback(parent,message,bad=false){
 let node=providerFeedbackNodes.get(parent);
 if(!node){node=el("div",undefined,"detail-state");node.dataset.providerFeedback="true";parent.prepend(node);providerFeedbackNodes.set(parent,node);}
 node.hidden=!message;node.classList.toggle("detail-state-error",bad);node.setAttribute("role",bad?"alert":"status");node.replaceChildren();if(message)node.append(el("p",message));
 if(bad&&!parent.closest(".detail-panel")?.hidden)node.scrollIntoView({block:"center"});
}
async function providerRun(parent,ticket,work,feedback=parent){
 if(providerPending.has(parent)||!detailCurrent(ticket))return;
 const lock=parent.providerAccountScope?.();if(lock&&providerAccountLocks.has(lock)){providerFeedback(feedback,"這個帳戶正在保存其他變更，完成後再操作。",true);return;}if(lock)providerAccountLocks.set(lock,parent);syncProviderLocks();
 providerPending.add(parent);parent.dataset.pending="true";providerFeedback(feedback,"");
 const controls=[...parent.querySelectorAll("input,select,textarea,button")].map(n=>[n,n.disabled]);controls.forEach(([n])=>n.disabled=true);parent.setAttribute("aria-busy","true");let saved=false;
 const commit=async(path,payload)=>{const result=await submit(path,payload);saved=true;return result;};
 try{const message=await work(commit);if(detailCurrent(ticket)&&message)providerFeedback(feedback,message);}
 catch(e){if(detailCurrent(ticket))providerFeedback(feedback,e instanceof SyntaxError?"JSON 格式不正確，請檢查後重試。":(saved?"操作已保存，但畫面重新載入失敗：":"操作未完成：")+e.message,true);}
 finally{controls.forEach(([n,disabled])=>n.disabled=disabled);parent.removeAttribute("aria-busy");delete parent.dataset.pending;providerPending.delete(parent);captureProviderDraft(parent);if(lock&&providerAccountLocks.get(lock)===parent)providerAccountLocks.delete(lock);syncProviderLocks();}
}
function providerSubmit(form,ticket,work){form.addEventListener("submit",e=>{e.preventDefault();providerRun(form,ticket,work);});}
function providerAction(container,label,ticket,work,parent=container,feedback=parent){return action(container,label,()=>providerRun(parent,ticket,work,feedback));}
`
