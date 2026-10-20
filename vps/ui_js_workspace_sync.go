package main

const workspaceSyncHTML = `
<section class="card" id="workspace-sync" aria-labelledby="workspace-sync-title">
<h2 id="workspace-sync-title">跨裝置同步</h2>
<p class="section-description">連線、模型、觀測、研究與歷史保留在各裝置；連上自己的 VPS 後同步。離線修改遇到衝突時，保留兩份資料供你選擇。</p>
<p id="workspace-sync-status" role="status" aria-live="polite">尚未讀取同步狀態。</p>
<div class="actions"><button id="workspace-sync-refresh" type="button" class="ghost">更新同步狀態</button><button id="workspace-sync-now" type="button">立即同步</button></div>
<div id="workspace-peer-summary"></div>
<details id="workspace-connect-panel"><summary>連接同步中心</summary>
<form id="workspace-connect-form">
<label>同步中心網址<input name="url" type="url" required placeholder="https://你的-ModelAtlas-網址" autocomplete="url"></label>
<label>這台裝置的名稱<input name="label" required maxlength="120" placeholder="例如：我的 Mac"></label>
<label>配對碼<input name="code" type="password" required minlength="43" maxlength="43" autocomplete="off" spellcheck="false"></label>
<p class="section-description">先在同步中心產生配對碼。只在自己信任的裝置上使用；配對權限可隨時撤銷。</p>
<button type="submit">配對並開始同步</button>
<p id="workspace-connect-feedback" role="status" aria-live="polite"></p>
</form></details>
<details id="workspace-pair-panel"><summary>配對另一台裝置</summary>
<label class="check"><input id="workspace-pair-secrets" type="checkbox">也同步 API Key 與自訂認證</label>
<p class="section-description">啟用後，秘密經認證 HTTPS 傳送，並在目的端重新加密保存。未啟用時，新裝置需另行輸入 Key。</p>
<button id="workspace-pair-create" type="button" class="ghost">產生一次性配對碼</button>
<div id="workspace-pair-result" hidden><label>配對碼<input id="workspace-pair-code" type="password" readonly autocomplete="off" aria-label="一次性配對碼"></label><p id="workspace-pair-expiry"></p><button id="workspace-pair-copy" type="button" class="ghost">複製配對碼</button></div>
</details>
<details><summary>已配對裝置</summary><div id="workspace-device-list"></div></details>
<div id="workspace-conflict-list"></div>
</section>
`
const jsWorkspaceSync = `
const workspaceKindNames={providers:"供應商",accounts:"帳戶",credentials:"Key",models:"模型",my_models:"我的模型",membership_state:"模型狀態",model_metadata:"模型資訊",observations:"觀測",telemetry:"測速紀錄",entity_notes:"筆記",sessions:"對話",messages:"訊息",research_candidates:"研究候選",knowledge_versions:"研究知識",account_checkins:"簽到"};
let workspaceSyncState=null,workspaceSyncLoading=false;
function workspaceSyncProblem(e){const s=e.message||"";if(s.includes("revoked")||s.includes("expired"))return "裝置權限已撤銷，或配對碼已過期。請重新產生配對碼。";if(s.includes("unreachable"))return "暫時連不上同步中心，本機資料與待同步修改已保留。";if(s.includes("changed; review"))return "資料剛剛又更新了，請重新檢視兩份內容後選擇。";return s;}
async function loadWorkspaceSync(){
 if(workspaceSyncLoading)return;workspaceSyncLoading=true;const status=$("workspace-sync-status");
 try{const value=await api("/workspace");workspaceSyncState=value;const peer=value.peer;
 status.textContent=(peer?"已連接同步中心":"此裝置可作同步中心")+" · "+value.pending+" 筆待同步"+(value.conflict_count?" · "+value.conflict_count+" 個衝突":"");$("workspace-sync-now").hidden=!peer;$("workspace-connect-panel").hidden=!!peer;$("workspace-pair-panel").hidden=!!peer;
 const summary=$("workspace-peer-summary");summary.replaceChildren();if(peer){summary.append(el("p",peer.url));summary.append(el("small",(peer.last_success?"上次成功："+new Date(peer.last_success).toLocaleString():"尚未完成第一次同步")+(peer.share_secrets?" · 已允許 Key 同步":" · Key 不同步"),"section-description"));if(peer.last_error)summary.append(el("p",workspaceSyncProblem({message:peer.last_error}),"error"));action(summary,"中斷這台裝置的連線",async()=>{if(!window.confirm("中斷同步連線？本機資料及歷史都會保留。"))return;await submit("/workspace/disconnect",{confirm:true});await loadWorkspaceSync();});}
 const devices=$("workspace-device-list");devices.replaceChildren();if(!value.devices.length)devices.append(el("p","尚無已配對裝置。","section-description"));
 value.devices.forEach(d=>{const card=el("div",undefined,"item");card.append(el("strong",d.label),el("small",d.platform+" · "+(d.revoked_at?"已撤銷":d.share_secrets?"可同步 Key":"只同步資料")+(d.last_seen?" · "+new Date(d.last_seen).toLocaleString():"")));if(!d.revoked_at)action(card,"撤銷這台裝置",async()=>{if(!window.confirm("撤銷「"+d.label+"」的同步權限？已下載到該裝置的資料仍會保留。"))return;await submit("/workspace/revoke",{device_id:d.id,confirm:true});await loadWorkspaceSync();});devices.append(card);});
 const conflicts=$("workspace-conflict-list");conflicts.replaceChildren();if(value.conflict_count){conflicts.append(el("h3","需要你選擇的修改"));value.conflicts.forEach(c=>item(conflicts,workspaceKindNames[c.kind]||"工作區資料","兩端都有修改 · "+new Date(c.created_at).toLocaleString(),()=>openWorkspaceConflict(c.id)));}
 }catch(e){status.textContent=workspaceSyncProblem(e);status.classList.add("error");}finally{workspaceSyncLoading=false;}
}
function workspaceRecordTitle(record){const d=record.data||{};return record.deleted?"已移除":d.name||d.label||d.display_name||d.title||d.model_id||workspaceKindNames[record.kind]||"工作區資料";}
async function openWorkspaceConflict(id){
 const value=await api("/workspace/conflict?id="+encodeURIComponent(id)),box=modal("選擇要保留的修改");box.append(el("p","兩份修改與衝突紀錄都會保留。選擇後會重新核對最新版本。","section-description"));
 [["本機",value.local],["同步中心",value.remote]].forEach(([label,record])=>{const card=el("div",undefined,"card");card.append(el("h3",label),el("strong",workspaceRecordTitle(record)));const d=record.data||{};["description","base_url","notes","content","state","label"].forEach(k=>{if(typeof d[k]==="string"&&d[k])card.append(el("p",d[k]));});const details=el("details");details.append(el("summary","查看完整差異"),el("pre",JSON.stringify(d,(k,v)=>k==="_secret_digest"?undefined:v,2)));card.append(details);box.append(card);});
 const actions=el("div",undefined,"actions");box.append(actions);
 [["保留本機修改","local"],["採用同步中心","remote"]].forEach(([label,choice])=>action(actions,label,async()=>{await submit("/workspace/resolve",{id,choice,confirm:true,local_hash:value.local_hash});$("detail").close();await runWorkspaceSync();}));
}
async function runWorkspaceSync(){const status=$("workspace-sync-status");status.textContent="正在同步，本機修改會保留…";try{const r=await submit("/workspace/sync",{});await refresh();await loadWorkspaceSync();if(r.has_more)status.textContent+=" · 其餘歷史會接續同步";}catch(e){status.textContent=workspaceSyncProblem(e);throw e;}}
function bindWorkspaceSync(){
 $("workspace-sync-refresh").addEventListener("click",loadWorkspaceSync);$("settings-data-tab").addEventListener("click",loadWorkspaceSync);$("workspace-sync-now").addEventListener("click",()=>runWorkspaceSync().catch(e=>notice(workspaceSyncProblem(e),true)));
 $("workspace-connect-form").addEventListener("submit",async e=>{e.preventDefault();const form=e.target;if(form.dataset.pending)return;form.dataset.pending="true";const button=form.querySelector('button[type="submit"]'),feedback=$("workspace-connect-feedback");button.disabled=true;feedback.textContent="正在配對…";try{const v=data(form);await submit("/workspace/connect",{...v,platform:"VPS"});form.elements.code.value="";feedback.textContent="配對成功";await loadWorkspaceSync();await runWorkspaceSync();}catch(error){feedback.textContent=workspaceSyncProblem(error);}finally{button.disabled=false;delete form.dataset.pending;}});
 $("workspace-pair-create").addEventListener("click",async e=>{const b=e.currentTarget;b.disabled=true;try{const r=await submit("/workspace/pair",{share_secrets:$("workspace-pair-secrets").checked});$("workspace-pair-code").value=r.code;$("workspace-pair-expiry").textContent="有效至 "+new Date(r.expires_at).toLocaleTimeString()+" · 限用一次";$("workspace-pair-result").hidden=false;}catch(error){notice(workspaceSyncProblem(error),true);}finally{b.disabled=false;}});
 $("workspace-pair-copy").addEventListener("click",async()=>{try{await navigator.clipboard.writeText($("workspace-pair-code").value);notice("配對碼已複製，請只交給自己的裝置。");}catch{notice("無法使用剪貼簿，請在配對碼欄選取複製。",true);}});
 setInterval(()=>{if(currentPane==="settings"&&!$("settings-data").hidden&&!document.hidden)loadWorkspaceSync();},15000);
}
document.addEventListener("DOMContentLoaded",bindWorkspaceSync);
`
