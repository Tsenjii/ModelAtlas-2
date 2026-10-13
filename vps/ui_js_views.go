package main

const jsViews = `
async function refresh(){
 [providers,models,myModels,sessions]=await Promise.all([api("/providers"),api("/models"),api("/my-models"),api("/sessions")]);
 document.querySelectorAll(".provider-select").forEach(select=>{const old=select.value;select.replaceChildren();providers.forEach(p=>option(select,p.id,p.name+" ("+p.id+")"));if(providers.some(p=>p.id===old))select.value=old;});
 $("provider-list").replaceChildren();if(!providers.length)item($("provider-list"),"尚未新增 Provider","先新增 API Provider。");
 providers.forEach(p=>item($("provider-list"),p.name,p.id+" · "+p.base_url+" · "+p.auth_mode));
 $("catalog").replaceChildren();if(!models.length)item($("catalog"),"Catalog 尚無資料","新增模型或從 Provider /models 同步。");
 models.forEach(m=>item($("catalog"),m.display_name,m.provider_id+" / "+m.id+" · "+m.price_state+" · "+m.provenance));
 $("my-models").replaceChildren();if(!myModels.length)item($("my-models"),"尚未選擇","請明確加入 My Models。");
 myModels.forEach(m=>item($("my-models"),m.model_id,m.provider_id+" / "+m.account_id+" / "+m.credential_id+" / "+(m.group||"default")));
 const old=$("session").value;$("session").replaceChildren();sessions.forEach(s=>option($("session"),s.id,(s.title||"新對話")+" · "+s.model_id));if(sessions.some(s=>s.id===old))$("session").value=old;
 $("target").replaceChildren();myModels.forEach(m=>option($("target"),JSON.stringify(m),m.provider_id+" · "+m.model_id+" ("+m.account_id+")"));
 $("new-session").disabled=!myModels.length;await loadChat();
}
async function loadChat(){
 const id=$("session").value,log=$("chat");log.replaceChildren();
 if(!id){item(log,"尚無對話","請先建立指定連線的新對話。");return;}
 try{const history=await api("/messages?session_id="+encodeURIComponent(id));if(!history.length)item(log,"尚無訊息","輸入訊息開始聊天。");history.forEach(m=>bubble(m.role,m.content));}
 catch(e){notice("讀取對話失敗："+e.message,true);}
}
async function loadStatus(){
 try{const [status,tel,candidates]=await Promise.all([api("/status"),api("/telemetry"),api("/research/candidates")]);
 $("status").textContent=JSON.stringify(status,null,2);$("telemetry").textContent=JSON.stringify(tel.slice(0,15),null,2);
 $("research").textContent="研究線索 "+candidates.length+" 筆\n"+JSON.stringify(candidates.slice(0,10),null,2);
 }catch(e){notice("讀取紀錄失敗："+e.message,true);}
}
`
