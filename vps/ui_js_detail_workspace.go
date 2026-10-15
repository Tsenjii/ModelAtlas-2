package main

const jsDetailWorkspace = `
function detailCurrent(ticket){return $("detail").open&&ticket===detailVersion;}
function detailSection(parent,title,description){
 const section=el("section",undefined,"detail-section");
 section.append(el("h3",title));if(description)section.append(el("p",description,"section-description"));
 parent.append(section);return section;
}
function detailHero(parent,kind,title,subtitle){
 const hero=el("div",undefined,"detail-hero"),avatar=el("span",undefined,"identity-avatar"),body=el("div");
 avatar.append(icon(kind));body.append(el("p",title,"detail-eyebrow"),el("p",subtitle,"detail-subtitle"));hero.append(avatar,body);parent.append(hero);
}
function detailRows(parent,rows){
 const list=el("dl",undefined,"detail-rows");
 for(const [name,value]of rows){const row=el("div");row.append(el("dt",name),el("dd",value===null||value===undefined||value===""?"尚無資料":String(value)));list.append(row);}
 parent.append(list);return list;
}
function detailTabs(parent,kind,definitions,initial){
 const tabs=el("div",undefined,"workspace-tabs detail-tabs"),panels={},buttons={};
 tabs.setAttribute("role","tablist");tabs.setAttribute("aria-label",kind==="model"?"模型詳情分區":"供應商詳情分區");parent.append(tabs);
 const activate=(id,focus=false)=>{if(!panels[id])return;tabs.dataset.active=id;for(const [key,panel]of Object.entries(panels)){const chosen=key===id;panel.hidden=!chosen;buttons[key].setAttribute("aria-selected",String(chosen));buttons[key].tabIndex=chosen?0:-1;}if(focus)buttons[id].focus();};
 for(const [id,label]of definitions){
  const b=el("button",label),panel=el("section",undefined,"detail-panel");b.type="button";b.id="detail-"+kind+"-"+id+"-tab";b.setAttribute("role","tab");b.setAttribute("aria-controls","detail-"+kind+"-"+id);panel.id="detail-"+kind+"-"+id;panel.setAttribute("role","tabpanel");panel.setAttribute("aria-labelledby",b.id);panel.tabIndex=0;
  buttons[id]=b;panels[id]=panel;tabs.append(b);parent.append(panel);b.addEventListener("click",()=>activate(id));
  b.addEventListener("keydown",e=>{if(!["ArrowLeft","ArrowRight","Home","End"].includes(e.key))return;e.preventDefault();const ids=definitions.map(v=>v[0]),index=ids.indexOf(id);activate(ids[e.key==="Home"?0:e.key==="End"?ids.length-1:(index+(e.key==="ArrowRight"?1:-1)+ids.length)%ids.length],true);});
 }
 activate(initial||definitions[0][0]);return {panels,activate,current:()=>tabs.dataset.active};
}
function detailState(parent,message,bad=false,retry){
 const state=el("div",undefined,"detail-state"+(bad?" detail-state-error":""));state.setAttribute("role",bad?"alert":"status");state.append(el("p",message));if(retry)action(state,"重新載入",retry);parent.append(state);return state;
}
const detailSourceNames={MANUAL:"手動確認",MY_KEY_LOCAL:"我的實測",NEW_API_OFFICIAL:"New API 站方",LMSPEED:"LMSpeed 外部參考"};
function detailSource(source){return detailSourceNames[source]||source||"來源未提供";}
function detailQuote(parent,title,quote){
 const section=detailSection(parent,title),legs=quote?.legs||quote?.facts?.price;
 if(!legs){section.append(el("p","尚無已確認資料","section-description"));return;}
 const rows=[],tokenUnit=legs.unit==="USD/1K tokens";
 if(tokenUnit)for(const [key,label]of [["input","輸入"],["output","輸出"],["cache","快取"]])rows.push([label,legs[key]===null||legs[key]===undefined?null:legs[key]+" USD / 1K tokens"]);
 rows.push(["固定請求費",legs.fixed!==null&&legs.fixed!==undefined?legs.fixed+" USD / request":legs.fixed_absent?"已確認無固定費":null]);
 if(!tokenUnit&&legs.unit!=="USD/request")rows.push(["原始計費單位",legs.unit]);
 detailRows(section,rows);section.append(el("p",detailSource(quote.source)+(quote.observed_at?" · "+new Date(quote.observed_at).toLocaleString():""),"detail-provenance"));factsView(section,"查看原始價格資料",quote);
}
function detailObservations(parent,title,observations){
 const section=detailSection(parent,title),rows=Array.isArray(observations)?observations:Object.values(observations||{});
 if(!rows.length){section.append(el("p","尚無來源紀錄","section-description"));return;}
 const list=el("div",undefined,"detail-evidence");section.append(list);
 for(const row of rows){
  const d=el("details"),summary=el("summary"),name=el("span",row.kind+" · "+detailSource(row.source)),stamp=el("small",(row.status||"未知")+(row.observed_at?" · "+new Date(row.observed_at).toLocaleString():""));summary.append(name,stamp);d.append(summary,el("pre",JSON.stringify(row,null,2)));list.append(d);
 }
}
function detailLocalMetrics(parent,local){
 parent.replaceChildren();const sample=knownNumber(local.samples)||0,stats=el("div",undefined,"metrics");
 parent.append(el("p","我的實測 · "+sample+" 次有效樣本"+(sample<20?" · 樣本較少":"")+"；使用者取消不計入成功率。","section-description"));
 if(local.window_start!==undefined)parent.append(el("p",new Date(local.window_start).toLocaleString()+" — "+new Date(local.window_end).toLocaleString(),"detail-provenance"));
 parent.append(stats);
 item(stats,"成功率",local.success_rate===null||local.success_rate===undefined?"尚無樣本":(local.success_rate*100).toFixed(1)+"%");
 item(stats,"TTFT · p50",local.ttft?.p50===undefined?"尚無資料":Math.round(local.ttft.p50)+" ms");
 item(stats,"TPS · p50",local.tps?.p50===undefined?"尚無資料":local.tps.p50.toFixed(1));
 item(stats,"延遲 · p50",local.latency?.p50===undefined?"尚無資料":Math.round(local.latency.p50)+" ms");
 const history=el("div",undefined,"history-strip");history.setAttribute("aria-label","最近最多 60 次有效請求");
 (local.history||[]).slice(-60).forEach(v=>{const n=el("span",undefined,v.outcome==="SUCCESS"?"success":"failure");n.title=v.outcome+" · "+new Date(v.at).toLocaleString();n.setAttribute("aria-label",n.title);history.append(n);});parent.append(history);
 const percentiles=el("details"),summary=el("summary","分位數與最近 60 秒觀測"),table=el("table"),thead=el("thead"),head=el("tr"),tbody=el("tbody");
 for(const title of ["指標","p50","p90","p95"]){const th=el("th",title);th.scope="col";head.append(th);}thead.append(head);table.append(thead,tbody);
 for(const [id,label,unit]of [["ttft","TTFT"," ms"],["tps","TPS",""],["latency","延遲"," ms"]]){const tr=el("tr"),th=el("th",label);th.scope="row";tr.append(th);for(const p of ["p50","p90","p95"]){const n=knownNumber(local[id]?.[p]);tr.append(el("td",n===null?"—":(id==="tps"?n.toFixed(1):Math.round(n))+unit));}tbody.append(tr);}
 percentiles.append(summary,table);detailRows(percentiles,[["觀測 RPM",local.observed_rpm],["觀測輸入 TPM",local.observed_input_tpm],["觀測輸出 TPM",local.observed_output_tpm],["觀測總 TPM",local.observed_total_tpm]]);percentiles.append(el("p","最近 60 秒的本地觀測，並非供應商官方上限。","section-description"));parent.append(percentiles);
 factsView(parent,"本地用量與紀錄",local);
}
function detailPerformance(parent,m,initial,local,ticket){
 const section=detailSection(parent,"我的實測"),controls=el("div",undefined,"detail-performance-controls"),select=el("select"),label=el("label","效能時間窗"),range=el("details"),form=el("form");
 for(const [value,name]of [["1h","最近 1 小時"],["24h","最近 24 小時"],["7d","最近 7 天"],["30d","最近 30 天"],["all","全部紀錄"],["custom","自訂"]])option(select,value,name);
 select.setAttribute("aria-label","效能時間窗");select.value=initial.split("&")[0];label.append(select);controls.append(label);range.append(el("summary","自訂時間窗"),form);section.append(controls,range);
 const start=field(form,"開始","datetime-local"),end=field(form,"結束","datetime-local");start.required=end.required=true;
 const dateInput=ms=>{const date=new Date(ms);return new Date(ms-date.getTimezoneOffset()*60000).toISOString().slice(0,16);};
 start.value=dateInput(local.window_start);end.value=dateInput(local.window_end);form.append(el("button","套用時間窗"));
 const status=el("div"),metrics=el("div");section.append(status,metrics);detailLocalMetrics(metrics,local);let serial=0,current=initial;
 const load=async window=>{
  const id=++serial;status.replaceChildren();detailState(status,"正在讀取實測紀錄…");metrics.setAttribute("aria-busy","true");
  try{const value=await api("/models/detail?"+query(m)+"&window="+window);if(!detailCurrent(ticket)||id!==serial)return;current=window;status.replaceChildren();detailLocalMetrics(metrics,value.local);}
  catch(e){if(!detailCurrent(ticket)||id!==serial)return;status.replaceChildren();detailState(status,"無法讀取此時間窗，仍顯示上次成功的結果。"+e.message,true,()=>load(window));}
  finally{if(id===serial)metrics.removeAttribute("aria-busy");}
 };
 select.addEventListener("change",()=>{if(select.value==="custom"){range.open=true;start.focus();return;}load(select.value);});
 form.addEventListener("submit",e=>{e.preventDefault();const from=new Date(start.value).getTime(),to=new Date(end.value).getTime();if(!Number.isFinite(from)||!Number.isFinite(to)||from<0||to<=from||to>Date.now()){notice("結束時間須晚於開始，且不能超過現在",true);return;}select.value="custom";load("custom&start="+from+"&end="+to);});
 return ()=>current;
}
`
