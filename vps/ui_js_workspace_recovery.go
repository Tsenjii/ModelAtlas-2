package main

// Recovery only rereads local workspace data. It never replays a write,
// inference, sync action or research task after a connection failure.
const jsWorkspaceRecovery = `
let workspaceRefreshTask=null,workspaceRefreshRequested=0,workspaceHasData=false,workspaceLastRead=null,workspaceReadState="loading";
const workspaceBlockedSettings=new Set();
function workspaceConnectionProblem(error){
 if(!error)return "";
 if(error.code==="READ_TIMEOUT")return "讀取逾時，請重試連線。";
 if(error.status===401)return "需要確認登入狀態，請重新連線。";
 if(error.status===403)return "目前帳號沒有讀取權限，請確認登入資訊。";
 if(error.status===429)return "目前請求較多，請稍等片刻再重新連線。";
 if([502,503,504].includes(error.status))return "服務暫時無法回應，請稍後重新連線。";
 if(error instanceof TypeError)return "無法連接服務，請確認網路或 VPS 是否運作。";
 return "這次讀取未完成，請重新連線。";
}
function workspaceReadStatus(state,error=null){
 workspaceReadState=state;
 document.documentElement.dataset.workspaceState=state;
 document.documentElement.dataset.workspaceReady=String(workspaceHasData);
 const panel=$("workspace-state"),retry=$("workspace-retry"),button=$("header-refresh"),initial=!workspaceHasData,pending=state==="loading",problem=state==="error"||state==="offline";
 if(initial){for(const input of $("settings-form").elements)if(!input.disabled){workspaceBlockedSettings.add(input);input.disabled=true;}}
 else{for(const input of workspaceBlockedSettings)input.disabled=false;workspaceBlockedSettings.clear();}
 panel.hidden=state==="ready"||pending&&!initial;
 panel.dataset.state=state;panel.setAttribute("aria-busy",String(pending));
 retry.disabled=pending;button.disabled=pending;
 button.setAttribute("aria-busy",String(pending));button.dataset.state=state;
 $("workspace-state-title").textContent=pending?"正在連接工作區":state==="offline"?"目前處於離線狀態":"暫時無法更新工作區";
 $("workspace-state-copy").textContent=pending?"正在讀取你的供應商、模型與對話。":workspaceHasData?"上次讀取的資料仍可查看，未送出的草稿會保留。":"尚未取得工作區資料。連線恢復後，按下方按鈕重試。";
 $("workspace-state-problem").textContent=workspaceConnectionProblem(error);
 $("workspace-state-problem").hidden=!error||state==="offline";
 $("workspace-state-progress").hidden=!pending||!initial;
 const at=workspaceLastRead?workspaceLastRead.toLocaleTimeString("zh-TW",{hour:"2-digit",minute:"2-digit",hour12:false}):"";
 $("workspace-state-time").textContent=at?"上次更新 "+at:"";$("workspace-state-time").hidden=!at;
 $("workspace-read-status").textContent=pending?"正在更新資料…":problem?(workspaceHasData?"保留上次資料":"等待重新連線"):"資料已更新";
 button.title=pending?"正在更新工作區":problem?"重新連線並更新資料":at?"上次更新 "+at:"重新整理";
 if(initial&&problem){$("model-picker-title").textContent="等待重新連線…";$("model-picker-open").disabled=true;}
 if(state==="ready"&&document.activeElement===retry)button.focus({preventScroll:true});
}
function refresh(){
 workspaceRefreshRequested++;
 if(workspaceRefreshTask)return workspaceRefreshTask;
 const trigger=document.activeElement,restoreFocus=trigger===$("workspace-retry")||trigger===$("header-refresh");
 workspaceReadStatus("loading");
 workspaceRefreshTask=(async()=>{
  try{
   for(;;){
    const requested=workspaceRefreshRequested;
    try{await refreshWorkspaceSnapshot(requested);}
    catch(error){if(requested!==workspaceRefreshRequested)continue;throw error;}
    if(requested===workspaceRefreshRequested)break;
   }
   workspaceLastRead=new Date();workspaceReadStatus("ready");
  }catch(error){workspaceReadStatus(navigator.onLine===false?"offline":"error",error);throw error;}
  finally{
   workspaceRefreshTask=null;$("header-refresh").disabled=false;$("workspace-retry").disabled=false;
   if(restoreFocus&&document.activeElement===document.body)
    (workspaceReadState==="ready"?$("header-refresh"):trigger).focus({preventScroll:true});
  }
 })();
 return workspaceRefreshTask;
}
function bindWorkspaceRecovery(){
 $("workspace-retry").addEventListener("click",()=>refresh().catch(()=>{}));
 const recover=()=>{if(!document.hidden&&(workspaceReadState==="offline"||workspaceReadState==="error"||!workspaceHasData))refresh().catch(()=>{});};
 window.addEventListener("offline",()=>workspaceReadStatus("offline"));
 window.addEventListener("online",recover);
 window.addEventListener("pageshow",event=>{if(event.persisted&&!document.hidden)refresh().catch(()=>{});});
 document.addEventListener("visibilitychange",()=>{if(!document.hidden)recover();});
}
`
