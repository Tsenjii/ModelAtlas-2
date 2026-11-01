package main

const jsSystemStatus = `
let systemStatusPending=false,systemStatusLast=null;
async function loadSystemStatus(){
 if(systemStatusPending)return;systemStatusPending=true;const button=$("system-status-refresh"),problem=$("system-status-problem"),summary=$("system-status-summary"),details=$("system-status-details");button.disabled=true;problem.hidden=true;if(!systemStatusLast)summary.textContent="正在檢查服務…";
 try{const state=await api("/system");if(!state.version||typeof state.version.commit!=="string"||typeof state.database_schema!=="number")throw Error("無法辨識服務狀態，請稍後重試。");systemStatusLast=state;summary.textContent=state.maintenance?"服務正在更新，暫停寫入。":state.status==="ok"?"服務正常運作。":"服務需要檢查。";details.replaceChildren();const row=(title,value)=>details.append(el("dt",title),el("dd",value));row("版本",state.version.commit==="development"?"開發版本":state.version.commit);if(state.version.built_at!=="unknown")row("建置時間",new Date(state.version.built_at).toLocaleString());row("執行平台",state.version.os+" / "+state.version.arch);row("資料儲存",state.status==="ok"?"已連線 · 格式 "+state.database_schema:"暫時無法讀取");row("Key 保護",state.vault_verified?"已通過啟動檢查":state.vault_configured?"已設定":"尚未設定 master key");}
 catch(error){problem.textContent=(systemStatusLast?"更新狀態失敗，保留上次檢查結果。 ":"")+error.message;problem.hidden=false;if(!systemStatusLast)summary.textContent="尚未取得服務狀態。";}
 finally{systemStatusPending=false;button.disabled=false;}
}
$("system-status-refresh").addEventListener("click",loadSystemStatus);
$("settings-data-tab").addEventListener("click",loadSystemStatus);
$("settings-toggle").addEventListener("click",()=>{if(currentPane==="settings")loadSystemStatus();});
`
