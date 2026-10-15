package main

const jsProviderForms = `
const providerPending=new WeakSet(),providerFeedbackNodes=new WeakMap();
function providerFeedback(parent,message,bad=false){
 let node=providerFeedbackNodes.get(parent);
 if(!node){node=el("div",undefined,"detail-state");node.dataset.providerFeedback="true";parent.prepend(node);providerFeedbackNodes.set(parent,node);}
 node.hidden=!message;node.classList.toggle("detail-state-error",bad);node.setAttribute("role",bad?"alert":"status");node.replaceChildren();if(message)node.append(el("p",message));
 if(bad&&!parent.closest(".detail-panel")?.hidden)node.scrollIntoView({block:"nearest"});
}
async function providerRun(parent,ticket,work,feedback=parent){
 if(providerPending.has(parent)||!detailCurrent(ticket))return;
 providerPending.add(parent);parent.dataset.pending="true";providerFeedback(feedback,"");
 const controls=[...parent.querySelectorAll("input,select,textarea,button")].map(n=>[n,n.disabled]);controls.forEach(([n])=>n.disabled=true);parent.setAttribute("aria-busy","true");let saved=false;
 const commit=async(path,payload)=>{const result=await submit(path,payload);saved=true;return result;};
 try{const message=await work(commit);if(detailCurrent(ticket)&&message)providerFeedback(feedback,message);}
 catch(e){if(detailCurrent(ticket))providerFeedback(feedback,e instanceof SyntaxError?"JSON 格式不正確，請檢查後重試。":(saved?"操作已保存，但畫面重新載入失敗：":"操作未完成：")+e.message,true);}
 finally{controls.forEach(([n,disabled])=>n.disabled=disabled);parent.removeAttribute("aria-busy");delete parent.dataset.pending;providerPending.delete(parent);}
}
function providerSubmit(form,ticket,work){form.addEventListener("submit",e=>{e.preventDefault();providerRun(form,ticket,work);});}
function providerAction(container,label,ticket,work,parent=container,feedback=parent){return action(container,label,()=>providerRun(parent,ticket,work,feedback));}
`
