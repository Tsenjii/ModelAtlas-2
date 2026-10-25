package main

const jsChat = `
let chatSettingsSession="",chatSettingsDirty=false;
async function saveChatSettings(){const session_id=$("session").value;if(!session_id)return;const result=await submit("/sessions/settings",{session_id,system:$("chat-system").value,max_tokens:Number($("chat-max-tokens").value),streaming:$("chat-streaming").checked});chatSettingsDirty=false;return result;}

function renderAttachments(){const box=$("attachments");box.replaceChildren();attachments.forEach(a=>{const chip=el("div",undefined,"attachment");if(a.kind==="image"){const img=el("img");img.src="data:"+a.mime+";base64,"+a.data;img.alt=a.filename;chip.append(img);}chip.append(el("span",a.filename));action(chip,"移除",()=>{attachments=attachments.filter(x=>x!==a);renderAttachments();});box.append(chip);});}
async function readAttachment(kind,file){if(file.size>4*1024*1024||file.size===0)throw Error("每份附件限 4 MiB");const bytes=new Uint8Array(await file.arrayBuffer()),head=String.fromCharCode(...bytes.slice(0,12));if(kind==="pdf"&&!head.startsWith("%PDF-"))throw Error("PDF 檔案內容無法辨識。");if(kind==="audio"&&!(head.startsWith("RIFF")&&head.slice(8,12)==="WAVE"||head.startsWith("ID3")||bytes[0]===255&&(bytes[1]&224)===224))throw Error("音訊需為有效的 WAV 或 MP3。");if(kind==="image"&&!(bytes[0]===137&&head.slice(1,4)==="PNG"||bytes[0]===255&&bytes[1]===216&&bytes[2]===255||head.startsWith("RIFF")&&head.slice(8,12)==="WEBP"))throw Error("圖片需為有效的 PNG、JPEG 或 WebP。");let mime=file.type;if(kind==="pdf")mime="application/pdf";if(kind==="audio")mime=String.fromCharCode(...bytes.slice(0,4))==="RIFF"?"audio/wav":"audio/mpeg";if(kind==="image"){mime=bytes[0]===137?"image/png":bytes[0]===255?"image/jpeg":"image/webp";}let binary="";for(let i=0;i<bytes.length;i+=8192)binary+=String.fromCharCode(...bytes.subarray(i,i+8192));const filename=file.name.replace(/[\\/\u0000-\u001f\u007f\u200b-\u200f\u202a-\u202e\u2060-\u206f]/g,"").slice(0,75)||"attachment";return {kind,filename,mime,data:btoa(binary)};}
async function pickFile(kind,input){const file=input.files[0];input.value="";if(!file)return;const version=fileReadVersion,readID=Symbol();attachmentReads[kind]=readID;const attachment=await readAttachment(kind,file);if(version!==fileReadVersion||attachmentReads[kind]!==readID||busy)return;attachments=attachments.filter(a=>a.kind!==kind);attachments.push(attachment);renderAttachments();}
function setBusy(value,streaming=true){busy=value;document.querySelectorAll("#chat .native-welcome button").forEach(n=>n.disabled=value);$("chat").setAttribute("aria-busy",String(value&&chatOperation(selectedOperation())));["target","session","new-session","conversation-library","conversation-manage","prompt","image-picker","pdf-picker","audio-picker","media-prompt","media-file","media-size","tools-json","chat-system","chat-max-tokens","chat-streaming","chat-settings-save","speech-voice","speech-format","speech-speed","speech-instructions","speech-speakers","speech-turns","speech-dialect","vector-inputs","vector-dimensions","rerank-top"].forEach(id=>$(id).disabled=value);$("stop").hidden=!value||!streaming||!chatOperation(selectedOperation());$("media-stop").hidden=!value||!streaming||chatOperation(selectedOperation());document.querySelectorAll("#tool-results textarea,#tool-results button").forEach(n=>n.disabled=value);$("conversation-manage").disabled=value||!$("session").value;updatePlayground();renderRecentConversations();}
async function send(e){e.preventDefault();return sendChat();}
async function sendChat(toolResults=[],retryID=""){
 const session_id=$("session").value,prompt=retryID||toolResults.length?"":$("prompt").value.trim(),selected=retryID||toolResults.length?[]:attachments.slice();
 if(!session_id||sessionScope!==$("target").value||(!prompt&&!toolResults.length&&!retryID)||busy)return;
 loadVersion++;chatHistoryStamp=null;setBusy(true);factoryKiloChatError();chatChanged(true,false);
 const requestController=new AbortController();controller=requestController;
 let answer=null,answerText=null,thinkText=null,reader=null,done=false,started=false,sampleID=null,toolSummary="";
 try{
  await saveChatSettings();const payload={session_id,prompt,attachments:selected,tool_results:toolResults,retry_id:retryID};
  if($("tools-json").value.trim())payload.tools=JSON.parse($("tools-json").value);
  const resp=await fetch("/api/v1/infer",{method:"POST",credentials:"same-origin",signal:requestController.signal,headers:{"Content-Type":"application/json"},body:JSON.stringify(payload)});
  sampleID=resp.headers.get("X-ModelAtlas-Sample-ID");if(!resp.ok||!resp.body){const raw=await resp.text(),error=Error(readableProblem(raw,resp.status));try{error.code=JSON.parse(raw)?.error?.code;}catch{}throw error;}
  if(prompt)bubble("user",prompt);answer=bubble("assistant","");answerText=streamText(answer,()=>chatChanged());if(!retryID&&!toolResults.length)$("prompt").value="";
  reader=resp.body.getReader();const decoder=new TextDecoder();let pending="";
  for(;;){
   const chunk=await reader.read();pending+=decoder.decode(chunk.value||new Uint8Array(),{stream:!chunk.done});let index;
   while((index=pending.indexOf("\n\n"))>=0){
    const frame=pending.slice(0,index);pending=pending.slice(index+2);
    const event=frame.split("\n").find(x=>x.startsWith("event:")),raw=frame.split("\n").filter(x=>x.startsWith("data:")).map(x=>x.slice(5).trim()).join("\n");
    if(!event||!raw)continue;const type=event.slice(6).trim(),payload=JSON.parse(raw);
    if(type==="start"){sampleID=payload.sample_id;pendingTools=[];renderToolResults();started=true;if(!retryID&&!toolResults.length){attachments=[];renderAttachments();}$("prompt").disabled=false;}
    if(type==="delta")answerText.append(payload.text);
    if(type==="reasoning"){
     if(!thinkText){const details=el("details"),summary=el("summary","推理過程"),think=el("div",undefined,"reasoning");details.append(summary,think);answer.before(details);thinkText=streamText(think,()=>chatChanged());}
     thinkText.append(payload.text);
    }
    if(type==="tool_calls"){toolSummary="工具呼叫："+payload.map(c=>c.function?.name||c.name).join("、");pendingTools=payload;renderToolResults();}
    if(type==="error")throw Error(payload.category==="CONVERSATION_LIMIT_START_NEW_CONVERSATION"?"對話已達上下文上限，請開新對話；原始紀錄仍完整保留":inferenceProblem(payload.category,payload.http_status));
    if(type==="done"){done=true;const d=payload.details;sampleID=d.sample_id;notice("已保存 · "+(d.ttft_ms===undefined?"":"TTFT "+d.ttft_ms+" ms · ")+d.latency_ms+" ms");}
   }
   if(chunk.done)break;
  }
  if(!done)throw Error("串流中斷");
 }catch(err){
  if(err.name==="AbortError")notice("已停止回應");
  else{if(err.code?.startsWith("KILO_"))factoryKiloChatError(err.message);else notice("回應失敗："+err.message,true);if(!started&&!$("prompt").value)$("prompt").value=prompt;}
 }finally{
  if(!done)requestController.abort();if(reader)reader.releaseLock();answerText?.finish();thinkText?.finish();
  if(answer){if(!answer.textContent)answer.textContent=toolSummary||"未完成回應";finishBubble(answer,sampleID);}
  controller=null;setBusy(false);if(!done)await loadChat();
 }
}


document.addEventListener("DOMContentLoaded",async()=>{
 bindWorkspace();bindModelPicker();bindMacAlignment();bindNativeShell();bindProviderEditors();bindMacSettings();bindNativeCollections();bindProviderQuickFilters();bindModelViews();bindWorkspaceRecovery();document.querySelectorAll(".bottom-nav button").forEach(b=>b.addEventListener("click",()=>show(b.dataset.tab)));$("settings-toggle").addEventListener("click",()=>show(currentPane==="settings"?previousPane:"settings"));$("detail-close").addEventListener("click",()=>$("detail").close());$("review-close").addEventListener("click",()=>$("review").close());
 $("session").addEventListener("change",()=>{sessionListVersion++;rememberComposerDraft();fileReadVersion++;restoreComposerDraft();loadChat();renderRecentConversations();});$("target").addEventListener("change",()=>{rememberComposerDraft();fileReadVersion++;prepareComposerSelection();pendingTools=[];renderToolResults();$("tools-json").value="";resetMediaDraft();updatePlayground();refresh().catch(e=>notice(e.message,true));});$("new-session").addEventListener("click",()=>newConversation().catch(e=>notice(e.message,true)));$("send-form").addEventListener("submit",send);$("stop").addEventListener("click",stopRequest);["chat-system","chat-max-tokens","chat-streaming","tools-json"].forEach(id=>$(id).addEventListener("input",()=>chatSettingsDirty=true));$("chat-settings-save").addEventListener("click",()=>saveChatSettings().then(()=>notice("對話設定已保存")).catch(e=>notice(e.message,true)));bindConversations();bindConversationCreation();bindReceipts();bindChatExperience();bindMedia();bindResearch();bindAssistant();bindKnowledge();bindModelControls();bindCatalog();
 for(const kind of ["image","pdf","audio"]){$(kind+"-picker").addEventListener("change",e=>pickFile(kind,e.target).catch(e=>notice(e.message,true)));}
 $("provider-search").addEventListener("input",renderProviders);$("model-search").addEventListener("input",renderModels);$("model-lifecycle").addEventListener("change",renderModels);$("model-provider").addEventListener("change",renderModels);$("model-capability").addEventListener("change",renderModels);$("model-favorites").addEventListener("change",renderModels);$("model-price").addEventListener("change",renderModels);
 $("compare").addEventListener("click",openComparison);
 bindOnboarding();$("restore-form").addEventListener("submit",async e=>{e.preventDefault();try{await submit("/backup/restore",{confirm:e.target.elements.confirm.checked,backup:JSON.parse(e.target.elements.json.value)});e.target.reset();await refresh();notice("已還原；請在 Provider 詳情重新設定 Key");}catch(e){notice(e.message,true);}});$("settings-form").addEventListener("submit",async e=>{e.preventDefault();const f=e.target,v=data(f),revision=settingsEditVersion,notify=f.elements.notifications.checked;try{await submit("/settings",{timezone:v.timezone,performance_window:v.performance_window,notifications:notify,background:{enabled:f.elements.background_enabled.checked,interval_minutes:Number(f.elements.background_minutes.value),max_scopes:Number(f.elements.background_scopes.value)},notification_rules:Object.fromEntries([...notificationRuleNames.map(k=>[k,f.elements[k].checked]),["quota_remaining_below",Number(f.elements.quota_remaining_below.value)]])});if(notify&&"Notification" in window&&Notification.permission==="default")await Notification.requestPermission();await refresh();if(revision===settingsEditVersion){settingsEdits.clear();renderSettingsForm();}notice("設定已儲存");}catch(e){notice(e.message,true);}});
 $("lmspeed-form").addEventListener("submit",async e=>{e.preventDefault();try{await submit("/lmspeed/key",data(e.target));e.target.elements.secret.value="";notice("LMSpeed Key 已保存");}catch(e){notice(e.message,true);}});$("lmspeed-sync").addEventListener("click",async()=>{try{await submit("/lmspeed/sync",{locale:$("lmspeed-locale").value});await loadServiceReferences();notice("外部資料已同步");}catch(e){notice(e.message,true);}});
 let knownSync="",syncPolling=false;setInterval(async()=>{if(document.hidden||busy||syncPolling)return;syncPolling=true;try{const rows=await api("/intelligence/history?limit=5"),stamp=JSON.stringify(rows.map(x=>[x.id,x.status,x.at]));if(knownSync&&stamp!==knownSync){await refresh();}knownSync=stamp;if(settings.notifications||currentPane==="settings"&&!$("settings-notifications").hidden)await pollNotifications();}catch(e){}finally{syncPolling=false;}},10000);
 try{await refresh();}catch(e){/* The persistent recovery card owns startup failures. */}
});
`
