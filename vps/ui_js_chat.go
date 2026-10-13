package main

const jsChat = `
async function send(e){
 e.preventDefault();const session_id=$("session").value,prompt=$("prompt").value.trim();
 if(!session_id||!prompt){notice("先選對話及輸入訊息",true);return;}
 const btn=$("send");btn.disabled=true;bubble("user",prompt);$("prompt").value="";const answer=bubble("assistant","");
 try{
  const resp=await fetch("/api/v1/infer",{method:"POST",credentials:"same-origin",headers:{"Content-Type":"application/json"},body:JSON.stringify({session_id,prompt})});
  if(!resp.ok||!resp.body)throw Error("HTTP "+resp.status);
  const rd=resp.body.getReader(),decoder=new TextDecoder();let pending="",done=false;
  for(;;){
   const chunk=await rd.read();if(chunk.done)break;pending+=decoder.decode(chunk.value,{stream:true});
   let i;while((i=pending.indexOf("\n\n"))>=0){
    const frame=pending.slice(0,i);pending=pending.slice(i+2);
    const e=frame.split("\n").find(x=>x.startsWith("event: ")),d=frame.split("\n").find(x=>x.startsWith("data: "));
    if(!e||!d)continue;
    const type=e.slice(7).trim(),payload=JSON.parse(d.slice(6));
    if(type==="delta"){answer.textContent+=payload.text;$("chat").scrollTop=$("chat").scrollHeight;}
    if(type==="error")throw Error(payload.category||"stream error");
    if(type==="done")done=true;
   }
  }
  if(!done)throw Error("串流中斷");notice("回應及 Telemetry 已保存");
 }catch(err){notice("推論失敗："+err.message,true);if(!answer.textContent)answer.textContent="[沒有完整回應]";}
 finally{btn.disabled=false;}
}
document.addEventListener("DOMContentLoaded",async()=>{
 document.querySelectorAll(".bottom-nav button").forEach(b=>b.addEventListener("click",()=>show(b.dataset.tab)));
 $("settings-toggle").addEventListener("click",()=>show("settings"));
 const target=document.createElement("select");target.id="target";target.setAttribute("aria-label","新對話指定連線");
 const label=document.createElement("label");label.textContent="新對話使用的連線";label.append(target);$("new-session").before(label);
 $("session").addEventListener("change",loadChat);
 $("new-session").addEventListener("click",async()=>{if(!target.value)return;try{const m=JSON.parse(target.value);const created=await submit("/sessions",{...m,title:"新對話 · "+m.model_id});await refresh();$("session").value=created.id;await loadChat();notice("對話已建立");}catch(e){notice("新增對話失敗："+e.message,true);}});
 for(const [id,path] of [["provider-form","/providers"],["account-form","/accounts"],["credential-form","/credentials"],["model-form","/models"],["membership-form","/my-models"],["sync-form","/models/sync"]]){
  $(id).addEventListener("submit",async ev=>{ev.preventDefault();const form=ev.currentTarget,button=form.querySelector("button");button.disabled=true;
   try{await submit(path,data(form));if(id==="credential-form")form.elements.secret.value="";notice("已儲存");await refresh();}
   catch(err){notice("操作失敗："+err.message,true);}finally{button.disabled=false;}
  });
 }
 $("send-form").addEventListener("submit",send);
 try{await refresh();notice("連線正常");}catch(e){notice("連線失敗："+e.message,true);}
});
`
