package main

const jsMedia = `
function selectedOperation(){return $("target").value?targetOperation(JSON.parse($("target").value)):"";}
function updatePlayground(){
 const operation=selectedOperation(),media=["IMAGES_GENERATION","IMAGES_EDIT","AUDIO"].includes(operation);
 $("chat-ui").hidden=media;$("media-ui").hidden=!media;$("new-session").hidden=media;
 $("new-session").disabled=busy||operation!=="CHAT_COMPLETIONS";$("send").disabled=busy||operation!=="CHAT_COMPLETIONS"||!$("session").value;
 $("media-send").disabled=busy||!media;$("media-clear").disabled=busy;
 $("media-operation").textContent=operationLabels[operation]||"";$("playground-description").textContent={IMAGES_GENERATION:"描述想生成的圖片。",IMAGES_EDIT:"選擇圖片，描述要修改的內容。",AUDIO:"上傳一段音訊，取得轉寫文字。"}[operation]||"選一個模型，開始對話。";$("media-size-label").hidden=operation!=="IMAGES_GENERATION";
 $("media-file-label").hidden=operation==="IMAGES_GENERATION";$("media-input-name").hidden=operation==="IMAGES_GENERATION";
 $("media-file-caption").textContent=operation==="AUDIO"?"選擇 WAV／MP3":"選擇要編輯的圖片";
 $("media-file").accept=operation==="AUDIO"?"audio/wav,audio/mpeg,.wav,.mp3":"image/png,image/jpeg,image/webp";
 $("media-prompt").required=operation!=="AUDIO";$("media-prompt-label").textContent=operation==="AUDIO"?"轉寫提示（選填）":"描述";
 $("media-prompt").placeholder=operation==="AUDIO"?"可提供人名、專有名詞等上下文":"描述想生成的圖片，或這次要修改的內容";
 $("media-send").textContent=operation==="AUDIO"?"開始轉寫":operation==="IMAGES_EDIT"?"編輯圖片":"生成圖片";
}
function clearMediaResult(){mediaResultURLs.forEach(url=>URL.revokeObjectURL(url));mediaResultURLs=[];$("media-result").replaceChildren();}
function resetMediaDraft(){fileReadVersion++;mediaAttachment=null;$("media-file").value="";$("media-prompt").value="";$("media-input-name").textContent="每份檔案限 4 MiB";clearMediaResult();}
function mediaBlob(encoded,mime){const raw=atob(encoded),bytes=new Uint8Array(raw.length);for(let i=0;i<raw.length;i++)bytes[i]=raw.charCodeAt(i);const url=URL.createObjectURL(new Blob([bytes],{type:mime}));mediaResultURLs.push(url);return url;}
function mediaDownload(parent,url,filename,label){const a=el("a",label,"link-button");a.href=url;a.download=filename;parent.append(a);}
function renderMediaResult(result){
 clearMediaResult();const box=$("media-result"),operation=targetOperation(result.selection);
 box.append(el("h2",operation==="AUDIO"?"轉寫結果":"圖片結果"));
 if(operation==="AUDIO"){
  const transcript=el("div",result.text,"bubble transcript");box.append(transcript);
  const url=URL.createObjectURL(new Blob([result.text],{type:"text/plain;charset=utf-8"}));mediaResultURLs.push(url);mediaDownload(box,url,"modelatlas-transcript.txt","下載文字");
 }else{
  const images=el("div",undefined,"media-images");box.append(images);
  result.data.forEach((data,i)=>{
   const card=el("figure",undefined,"media-image");images.append(card);
   if(data.b64_json){
    if(!["image/png","image/jpeg","image/webp"].includes(data.mime))throw Error("不支援的圖片格式");
    const url=mediaBlob(data.b64_json,data.mime),img=el("img");img.src=url;img.alt="圖片結果 "+(i+1);img.addEventListener("error",()=>{img.hidden=true;card.prepend(el("p","圖片無法預覽，請下載後查看。"));},{once:true});card.append(img);
    mediaDownload(card,url,"modelatlas-"+(i+1)+"."+({"image/png":"png","image/jpeg":"jpg","image/webp":"webp"}[data.mime]),"下載圖片");
   }else if(data.url){
    const url=new URL(data.url);if(url.protocol!=="https:"||url.username||url.password)throw Error("不安全的圖片網址");
    card.append(el("p","供應商以網址回傳圖片。"));const a=el("a","開啟供應商圖片","link-button");a.href=url.href;a.target="_blank";a.rel="noopener noreferrer";a.referrerPolicy="no-referrer";card.append(a);
   }
  });
 }
 const details=result.details||{};box.append(el("p","完成 · "+(details.latency_ms??"—")+" ms","media-metrics"));
 box.append(el("p","結果保留於本頁，可下載保存。","media-hint"));
}
async function sendMedia(e){
 e.preventDefault();if(busy||!$("target").value)return;const selection=JSON.parse($("target").value),operation=targetOperation(selection),prompt=$("media-prompt").value.trim();
 if(!["IMAGES_GENERATION","IMAGES_EDIT","AUDIO"].includes(operation))return;
 if(operation!=="AUDIO"&&!prompt)return;if(operation!=="IMAGES_GENERATION"&&(!mediaAttachment||mediaAttachment.kind!==(operation==="AUDIO"?"audio":"image"))){notice("請先選擇這次要處理的檔案",true);return;}
 const request_id=crypto.randomUUID(),payload={...selection,prompt,request_id};if(operation==="IMAGES_GENERATION")payload.size=$("media-size").value;else payload[operation==="AUDIO"?"audio":"image"]=mediaAttachment;
 setBusy(true);mediaRequestID=request_id;controller=new AbortController();clearMediaResult();$("media-result").append(el("p",operation==="AUDIO"?"正在轉寫…":"正在處理圖片…"));
 try{
  const response=await fetch("/api/v1/infer/media",{method:"POST",credentials:"same-origin",signal:controller.signal,headers:{"Content-Type":"application/json"},body:JSON.stringify(payload)});
  const raw=await response.text();let result;try{result=JSON.parse(raw);}catch(e){throw Error(raw.trim()==="selected media capability unavailable"?"請在模型詳情確認此連線的能力，並檢查 Key 是否啟用":raw.trim().slice(0,150)||"HTTP "+response.status);}
  if(!response.ok||result.outcome!=="SUCCESS")throw Error({PARSER:"供應商回傳的結果無法使用",RATE_LIMITED:"供應商目前限制請求，請稍後重試",AUTH_INVALID:"Key 無效，請在 Provider 更新",INFERENCE_DENIED:"此 Key 沒有使用這個模型的權限",PAYMENT_REQUIRED:"供應商要求付款或補充額度",MODEL_UNAVAILABLE:"供應商無法提供這個模型",GONE:"供應商已移除此端點",UPSTREAM_5XX:"供應商暫時無法處理請求",NETWORK:"無法連上供應商",TIMEOUT:"處理逾時",CANCELLED:"已停止處理",TELEMETRY_SAVE:"請求紀錄無法保存"}[result.outcome]||result.outcome||"HTTP "+response.status);
  if(key(result.selection)!==key(selection)||result.request_id!==request_id)throw Error("回應連線不符");
  if($("target").value===key(selection)){renderMediaResult(result);notice("已完成 · "+operationLabels[operation]);}
 }catch(err){clearMediaResult();notice(err.name==="AbortError"?"已停止處理":"處理失敗："+err.message,err.name!=="AbortError");}
 finally{controller=null;mediaRequestID=null;setBusy(false);}
}
async function stopRequest(){const request_id=mediaRequestID,session_id=$("session").value;controller?.abort();try{await submit("/infer/cancel",request_id?{request_id}:{session_id});}catch(e){if(!e.message.includes("no active"))notice(e.message,true);}}
function bindMedia(){
 $("media-form").addEventListener("submit",sendMedia);$("media-stop").addEventListener("click",stopRequest);$("media-clear").addEventListener("click",()=>{clearMediaResult();notice("已清除本頁結果");});
 $("media-file").addEventListener("change",async e=>{const file=e.target.files[0],version=++fileReadVersion;e.target.value="";if(!file)return;try{const attachment=await readAttachment(selectedOperation()==="AUDIO"?"audio":"image",file);if(version!==fileReadVersion||busy)return;mediaAttachment=attachment;$("media-input-name").textContent=attachment.filename;}catch(e){notice(e.message,true);}});
 window.addEventListener("pagehide",()=>{controller?.abort();clearMediaResult();});
}
`
