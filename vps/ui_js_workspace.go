package main

// Workspace navigation changes presentation only. Drafts and selected scopes stay
// in their existing owners; navigating never starts an inference or an action.
const jsWorkspace = `
const iconPaths={external:["M15 3h6v6M10 14 21 3M21 14v7H3V3h7"],play:["m9 5 11 7-11 7V5Z"],chat:["M21 11.5a8.4 8.4 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.4 8.4 0 0 1-3.8-.9L3 21l1.9-5.7a8.4 8.4 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.4 8.4 0 0 1 3.8-.9h.5a8.5 8.5 0 0 1 8 8v.5Z"],models:["m12 3 9 5-9 5-9-5 9-5Z","m3 12 9 5 9-5","m3 16 9 5 9-5"],provider:["M5 3h14v7H5z","M5 14h14v7H5z","M8 6.5h.01M8 17.5h.01M12 6.5h4M12 17.5h4"],settings:["M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8Z","M12 2v3M12 19v3M2 12h3M19 12h3M5 5l2 2M17 17l2 2M5 19l2-2M17 7l2-2"],plus:["M12 5v14M5 12h14"],search:["M21 21l-4.5-4.5","M18 10a8 8 0 1 1-16 0 8 8 0 0 1 16 0Z"],spark:["m12 3 2.8 6.2L21 12l-6.2 2.8L12 21l-2.8-6.2L3 12l6.2-2.8L12 3Z"],book:["M12 5v16M3 3h5a4 4 0 0 1 4 2 4 4 0 0 1 4-2h5v16h-5a4 4 0 0 0-4 2 4 4 0 0 0-4-2H3V3Z"],image:["M3 3h18v18H3z","m3 17 5-5 4 4 4-6 5 7M7 7h.01"],audio:["M12 3v18M8 7v10M4 10v4M16 7v10M20 10v4"],file:["M14 2H5v20h14V7l-5-5ZM14 2v6h5M8 13h8M8 17h6"],send:["M12 19V5m-7 7 7-7 7 7"],stop:["M6 6h12v12H6z"],history:["M3 11a9 9 0 1 1 2.5 7M3 4v7h7M12 7v5l3 2"],more:["M5 12h.01M12 12h.01M19 12h.01"],arrow:["m9 5 7 7-7 7"],back:["m15 5-7 7 7 7M8 12h13"],filter:["M4 6h16M7 12h10M10 18h4"],check:["m5 12 4 4L19 6"],star:["m12 3 2.8 5.7 6.2.9-4.5 4.4 1.1 6.2-5.6-3-5.6 3 1.1-6.2L3 9.6l6.2-.9L12 3Z"]};
function icon(name){const svg=document.createElementNS("http://www.w3.org/2000/svg","svg");svg.setAttribute("viewBox","0 0 24 24");svg.setAttribute("fill","none");svg.setAttribute("stroke","currentColor");svg.setAttribute("stroke-width","1.7");svg.setAttribute("stroke-linecap","round");svg.setAttribute("stroke-linejoin","round");svg.setAttribute("aria-hidden","true");svg.classList.add("icon");for(const d of iconPaths[name]||iconPaths.models){const path=document.createElementNS(svg.namespaceURI,"path");path.setAttribute("d",d);svg.append(path);}return svg;}
function decorateIcons(){document.querySelectorAll("[data-icon]").forEach(n=>{if(!n.querySelector(":scope > svg"))n.prepend(icon(n.dataset.icon));});}
let workspaceChatReading=null;
function workspace(group,id,focus=false){
 const tabs=[...document.querySelectorAll('[data-workspace-tabs="'+group+'"] [role="tab"]')],selected=tabs.find(t=>t.getAttribute("aria-controls")===id);if(!selected)return;
 const pane=selected.closest(".pane");if(group==="playground"&&!$("work-chat").hidden)workspaceChatReading={session:$("session").value,position:chatReadPosition()};
 pane.querySelectorAll('[data-workspace-panel="'+group+'"]').forEach(panel=>{panel.hidden=panel.id!==id;if(panel.tagName==="DETAILS")panel.open=panel.id===id;});
 tabs.forEach(tab=>{const active=tab===selected;tab.setAttribute("aria-selected",String(active));tab.tabIndex=active?0:-1;});
 if(focus)selected.focus({preventScroll:true});if(group==="playground"&&id==="work-chat"){const reading=workspaceChatReading?.session===$("session").value?workspaceChatReading.position:null;if(reading)restoreChatReadPosition(reading);else chatChanged(false,false);}
}
let previousPane="playground",currentPane="playground",noticeTimer=null;
function workspaceEmpty(parent,title,description,kind="models",label="",callback=null){const box=el("div",undefined,"empty-state"),mark=el("div",undefined,"empty-mark");mark.append(icon(kind));box.append(mark,el("h2",title),el("p",description));if(callback)action(box,label,callback,false);parent.append(box);return box;}
function chatEmpty(hasSession){
 const chat=$("chat");chat.replaceChildren();
 const box=workspaceEmpty(chat,hasSession?"今天想完成什麼？":"準備開始一段對話",hasSession?"從一個問題、一段程式，或一個想法開始。":"選擇已加入的模型，建立你的第一段對話。","chat");
 if(hasSession){const suggestions=el("div",undefined,"suggestions");for(const text of ["解釋一個概念","一起規劃任務","檢查一段程式"]){action(suggestions,text,()=>{$("prompt").value=text+"：\n";$("prompt").dispatchEvent(new Event("input"));$("prompt").focus();});}box.append(suggestions);}
 else if($("target").value)action(box,"建立新對話",newConversation,false);
 else action(box,providers.length?"探索可用模型":"加入第一個 Provider",()=>{show(providers.length?"models":"providers");workspace(providers.length?"models":"providers",providers.length?"models-catalog":"providers-new",true);},false);
}
const adapterLabels={GENERIC:"OpenAI 相容",NEW_API:"New API",NVIDIA_NIM_HOSTED:"NVIDIA NIM",NVIDIA_NIM_SELF_HOSTED:"NIM 自架服務",AMD_SHARED:"AMD Token Factory",AMD_DEDICATED:"AMD 獨立服務",OPENROUTER_FREE:"OpenRouter Free",GEMINI_FREE:"Gemini",GROQ_FREE:"Groq"};
function collectionItem(parent,kind,title,description,callback){const button=item(parent,title,description,callback);button.classList.add("identity-item");const body=el("div",undefined,"identity-body");while(button.firstChild)body.append(button.firstChild);const avatar=el("span",undefined,"identity-avatar");avatar.append(icon(kind));const arrow=icon("arrow");arrow.classList.add("item-arrow");button.append(avatar,body,arrow);return {button,body};}
let modelCatalogIndex=new Map();
function modelRecord(m){return modelCatalogIndex.get(JSON.stringify([m.provider_id,m.model_id]));}
function modelDisplayName(m){return modelRecord(m)?.display_name||m.model_id;}
function metricChip(parent,label,value){const chip=el("span",undefined,"metric-chip");chip.append(el("span",label),el("b",value));parent.append(chip);}
let modelSelectionMode=false;
function updateModelSelection(){
 const count=selectedCompare.size,global=$("model-scope").value==="GLOBAL";$("my-models").classList.toggle("selecting",modelSelectionMode&&!global);$("model-select").disabled=global;$("model-select").setAttribute("aria-pressed",String(modelSelectionMode&&!global));$("model-select-label").textContent=modelSelectionMode?"完成選取":"選取";
 $("model-selection-bar").hidden=!count||global;$("model-selected-count").textContent="已選 "+count+" 個模型";$("compare").disabled=count<2;
}
const filterDefaults={"model-lifecycle":"ACTIVE","model-scope":"MY_KEYS","model-sort":"smart","model-performance-source":"local"};
const modelFilterIDs=["model-search","model-lifecycle","model-scope","model-sort","model-performance-source","model-provider","model-capability","model-price","model-favorites","model-key","model-group","model-category","model-modality","model-vendor","model-context-min","model-tps-min","model-ttft-max","model-latency-max","model-success-1h","model-success-24h","model-samples-min","model-input-max","model-output-max","model-cache-max","model-changes","model-usable"];
function resetModelFilters(){for(const id of modelFilterIDs){const n=$(id);if(n.type==="checkbox")n.checked=false;else n.value=filterDefaults[id]||"";}modelDisplayLimit=100;renderModels();}
function modelFilterStatus(count){const changed=modelFilterIDs.filter(id=>{const n=$(id);return n.type==="checkbox"?n.checked:n.value!==(filterDefaults[id]||"");});$("model-result-count").textContent=count+" 個模型";$("model-filter-reset").hidden=!changed.length;$("model-filter-count").textContent=changed.length?" · "+changed.length:"";const price=$("model-price").value,favorite=$("model-favorites").value;document.querySelectorAll("[data-model-quick]").forEach(button=>button.setAttribute("aria-pressed",String(button.dataset.modelQuick==="all"?!price&&!favorite:button.dataset.modelQuick==="favorite"?favorite==="favorite"&&!price:price==="FREE_NOW"&&!favorite)));updateModelSelection();}
function applyAppearance(value){if(!["system","light","dark"].includes(value))value="system";if(value==="system")delete document.documentElement.dataset.theme;else document.documentElement.dataset.theme=value;$("appearance").value=value;}
function bindWorkspace(){
 decorateIcons();document.querySelectorAll("[data-workspace-tabs]").forEach(list=>{
  const tabs=[...list.querySelectorAll('[role="tab"]')],group=list.dataset.workspaceTabs;
  tabs.forEach(tab=>{tab.addEventListener("click",()=>workspace(group,tab.getAttribute("aria-controls")));tab.addEventListener("keydown",event=>{if(!["ArrowLeft","ArrowRight","Home","End"].includes(event.key)||event.altKey||event.ctrlKey||event.metaKey)return;event.preventDefault();let index=tabs.indexOf(tab);if(event.key==="Home")index=0;else if(event.key==="End")index=tabs.length-1;else index=(index+(event.key==="ArrowRight"?1:-1)+tabs.length)%tabs.length;workspace(group,tabs[index].getAttribute("aria-controls"),true);});});
 });
 $("provider-add").addEventListener("click",()=>workspace("providers","providers-new",true));$("settings-back").addEventListener("click",()=>show(previousPane));
 $("model-select").addEventListener("click",()=>{modelSelectionMode=!modelSelectionMode;if(!modelSelectionMode)selectedCompare.clear();updateModelSelection();});$("model-selection-clear").addEventListener("click",()=>{selectedCompare.clear();renderModels();});$("model-filter-reset").addEventListener("click",resetModelFilters);
 document.querySelectorAll("[data-model-quick]").forEach(b=>b.addEventListener("click",()=>{$("model-favorites").value=b.dataset.modelQuick==="favorite"?"favorite":"";$("model-price").value=b.dataset.modelQuick==="free"?"FREE_NOW":"";modelDisplayLimit=100;renderModels();}));
 $("model-scope").addEventListener("change",()=>{if($("model-scope").value==="GLOBAL"){modelSelectionMode=false;selectedCompare.clear();renderModels();}});
 let appearance="system";try{appearance=localStorage.getItem("modelatlas.appearance")||"system";}catch(e){}applyAppearance(appearance);$("appearance").addEventListener("change",()=>{const value=$("appearance").value;applyAppearance(value);try{localStorage.setItem("modelatlas.appearance",value);}catch(e){}});
 $("prompt").addEventListener("input",()=>{const input=$("prompt");input.style.height="auto";input.style.height=Math.min(input.scrollHeight,200)+"px";});
 const menu=$("conversation-more");$("chat-tools-close").addEventListener("click",()=>{menu.open=false;menu.querySelector("summary").focus({preventScroll:true});});
 const fitMenu=()=>{if(!menu.open)return;const bounds=menu.querySelector("summary").getBoundingClientRect(),navTop=document.querySelector(".bottom-nav").getBoundingClientRect().top,below=Math.max(0,navTop-bounds.bottom-12),above=Math.max(0,bounds.top-16),up=below<240&&above>below,pop=menu.querySelector(".conversation-menu");pop.classList.toggle("menu-above",up);pop.style.maxHeight=Math.max(100,Math.min(520,up?above:below))+"px";};
 menu.addEventListener("toggle",fitMenu);window.addEventListener("resize",fitMenu);if("ResizeObserver" in window)new ResizeObserver(fitMenu).observe(document.documentElement);
 menu.addEventListener("focusout",event=>{if(event.relatedTarget&&!menu.contains(event.relatedTarget))menu.open=false;});
 document.addEventListener("click",event=>{if(menu.open&&!menu.contains(event.target))menu.open=false;});
}
`
