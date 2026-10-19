package main

const jsCheckins = `
let accountCheckins={accounts:[]},checkinRevision=0,providerQuick="all";
async function refreshAccountCheckins(){const revision=++checkinRevision,timezone=settings.timezone||"Asia/Taipei";try{const value=await api("/checkins/accounts?timezone="+encodeURIComponent(timezone));if(revision!==checkinRevision||timezone!==(settings.timezone||"Asia/Taipei"))return;accountCheckins=value;renderProviders();}catch{if(revision===checkinRevision)accountCheckins={accounts:[]};}}
function pendingCheckins(provider){return accountCheckins.accounts.filter(a=>a.provider_id===provider&&a.required&&!a.completed_at);}
function providerCheckins(parent,p,ticket){
 if(!p.config.requires_daily_checkin)return;const box=detailSection(parent,"每日簽到","先到站點完成簽到，再逐帳戶記錄。此按鈕只保存你的完成紀錄。"),status=el("div"),list=el("div",undefined,"cards");box.append(status,list);let revision=0;
 const load=async()=>{const id=++revision,timezone=settings.timezone||"Asia/Taipei";status.replaceChildren();detailState(status,"正在讀取帳戶簽到…");try{const value=await api("/checkins/accounts?timezone="+encodeURIComponent(timezone));if(!detailCurrent(ticket)||id!==revision)return;accountCheckins=value;renderProviders();status.replaceChildren(el("p",value.date+" · "+value.timezone,"detail-provenance"));list.replaceChildren();const rows=value.accounts.filter(a=>a.provider_id===p.id&&a.required);if(!rows.length)list.append(el("p","先新增帳戶，再記錄簽到。","section-description"));for(const a of rows){const card=item(list,a.label||a.account_id,a.completed_at?"今日已記錄 · "+new Date(a.completed_at).toLocaleTimeString():"今天尚未記錄");card.dataset.checkinAccount=a.account_id;if(a.checkin_url){const link=el("a","前往站點簽到","link-button");link.href=a.checkin_url;link.target="_blank";link.rel="noopener noreferrer";card.append(link);}if(!a.completed_at)providerAction(card,"我已完成這個帳戶的簽到",ticket,async commit=>{await commit("/checkins/accounts",{provider_id:p.id,account_id:a.account_id,timezone:value.timezone,date:value.date});await load();return "已保存這個帳戶的完成紀錄";});}}
 catch(e){if(detailCurrent(ticket)&&id===revision){status.replaceChildren();detailState(status,e.message,true,load);}}};load();return load;
}
function bindProviderQuickFilters(){document.querySelectorAll("[data-provider-quick]").forEach(b=>b.addEventListener("click",()=>{providerQuick=b.dataset.providerQuick;document.querySelectorAll("[data-provider-quick]").forEach(n=>n.setAttribute("aria-pressed",String(n===b)));renderProviders();}));}
`
