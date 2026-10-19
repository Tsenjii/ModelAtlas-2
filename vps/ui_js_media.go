package main

const jsMedia = `
let speechOperation="";
function isSpeech(op){return ["AUDIO_SPEECH","GEMINI_SPEECH"].includes(op);}
function selectedOperation(){return $("target").value?targetOperation(JSON.parse($("target").value)):"";}
function updatePlayground(){
 syncModelPickerButton();
 const operation=selectedOperation(),media=["IMAGES_GENERATION","IMAGES_EDIT","AUDIO","AUDIO_SPEECH","GEMINI_SPEECH","EMBEDDINGS","RERANK"].includes(operation);
 $("chat-ui").hidden=media;$("media-ui").hidden=!media;$("new-session").hidden=media;
 $("new-session").disabled=busy||!chatOperation(operation);$("send").disabled=busy||!chatOperation(operation)||!$("session").value;
 $("media-send").disabled=busy||!media;$("media-clear").disabled=busy;
 $("media-operation").textContent=operationLabels[operation]||"";$("playground-description").textContent={IMAGES_GENERATION:"描述想生成的圖片。",IMAGES_EDIT:"選擇圖片，描述要修改的內容。",AUDIO:"上傳一段音訊，取得轉寫文字。",AUDIO_SPEECH:"輸入文字，生成可播放及下載的語音。",GEMINI_SPEECH:"輸入文字或雙人對話，生成可播放及下載的語音。",EMBEDDINGS:"取得文字向量，可供搜尋與比較使用。",RERANK:"輸入查詢與文件，依相關度排序。"}[operation]||"選一個模型，開始對話。";$("media-size-label").hidden=operation!=="IMAGES_GENERATION";
 $("vector-options").hidden=!["EMBEDDINGS","RERANK"].includes(operation);$("vector-inputs").required=["EMBEDDINGS","RERANK"].includes(operation);$("vector-dimensions-label").hidden=operation!=="EMBEDDINGS";$("rerank-top-label").hidden=operation!=="RERANK";$("media-prompt-label").hidden=operation==="EMBEDDINGS";$("media-prompt").hidden=operation==="EMBEDDINGS";$("vector-input-label").firstChild.textContent=operation==="RERANK"?"待排序文件（每行一筆）":"輸入文字（每行一筆）";$("speech-options").hidden=!isSpeech(operation);$("speech-voice").required=isSpeech(operation);$("media-file-label").hidden=["IMAGES_GENERATION","AUDIO_SPEECH","GEMINI_SPEECH","EMBEDDINGS","RERANK"].includes(operation);$("media-input-name").hidden=["IMAGES_GENERATION","AUDIO_SPEECH","GEMINI_SPEECH","EMBEDDINGS","RERANK"].includes(operation);
 $("media-file-caption").textContent=operation==="AUDIO"?"選擇 WAV／MP3":"選擇要編輯的圖片";
 $("media-file").accept=operation==="AUDIO"?"audio/wav,audio/mpeg,.wav,.mp3":"image/png,image/jpeg,image/webp";
 const native=operation==="GEMINI_SPEECH";$("native-speech-options").hidden=!native;$("speech-speed-label").hidden=native;[...$("speech-format").options].forEach(o=>{o.hidden=native&&!["wav","pcm"].includes(o.value);});if(isSpeech(operation)&&speechOperation!==operation){$("speech-voice").value=native?"Kore":"alloy";$("speech-format").value=native?"wav":"mp3";speechOperation=operation;}
 $("media-prompt").required=!["AUDIO","EMBEDDINGS","GEMINI_SPEECH"].includes(operation);$("media-prompt-label").textContent=operation==="RERANK"?"查詢":operation==="AUDIO"?"轉寫提示（選填）":"描述";
 $("media-prompt").placeholder=operation==="AUDIO"?"可提供人名、專有名詞等上下文":"描述想生成的圖片，或這次要修改的內容";
 $("media-send").textContent=operation==="EMBEDDINGS"?"取得向量":operation==="RERANK"?"排序文件":isSpeech(operation)?"生成語音":operation==="AUDIO"?"開始轉寫":operation==="IMAGES_EDIT"?"編輯圖片":"生成圖片";
}
function clearMediaResult(){mediaResultURLs.forEach(url=>URL.revokeObjectURL(url));mediaResultURLs=[];$("media-result").replaceChildren();}
function resetMediaDraft(){fileReadVersion++;mediaAttachment=null;$("media-file").value="";$("media-prompt").value="";$("media-input-name").textContent="每份檔案限 4 MiB";$("vector-inputs").value="";$("speech-instructions").value="";$("speech-speakers").value="";$("speech-turns").value="";$("speech-dialect").value="CURRENT";clearMediaResult();}
function mediaBlob(encoded,mime){const raw=atob(encoded),bytes=new Uint8Array(raw.length);for(let i=0;i<raw.length;i++)bytes[i]=raw.charCodeAt(i);const url=URL.createObjectURL(new Blob([bytes],{type:mime}));mediaResultURLs.push(url);return url;}
function mediaDownload(parent,url,filename,label){const a=el("a",label,"link-button");a.href=url;a.download=filename;parent.append(a);}
function renderMediaResult(result){
 clearMediaResult();const box=$("media-result"),operation=targetOperation(result.selection);requestLink(box,result.details?.sample_id,"查看本次請求");
 box.append(el("h2",["EMBEDDINGS","RERANK"].includes(operation)?"推論結果":isSpeech(operation)?"語音結果":operation==="AUDIO"?"轉寫結果":"圖片結果"));
 if(["EMBEDDINGS","RERANK"].includes(operation)){
  const value=result.vectors||result.rankings;box.append(el("pre",JSON.stringify(value,null,2)));const url=URL.createObjectURL(new Blob([JSON.stringify(value,null,2)],{type:"application/json"}));mediaResultURLs.push(url);mediaDownload(box,url,"modelatlas-"+operation.toLowerCase()+".json","下載結果");
 }else if(isSpeech(operation)){
  const audio=result.data?.[0];if(!audio||!["audio/mpeg","audio/wav","audio/flac","audio/ogg","audio/aac","audio/pcm"].includes(audio.mime))throw Error("不支援的音訊格式");
  let url;if(audio.mime==="audio/pcm"){const raw=atob(audio.b64_json),bytes=new Uint8Array(44+raw.length),view=new DataView(bytes.buffer);const word=(o,t)=>[...t].forEach((c,i)=>bytes[o+i]=c.charCodeAt(0));word(0,"RIFF");view.setUint32(4,36+raw.length,true);word(8,"WAVEfmt ");view.setUint32(16,16,true);view.setUint16(20,1,true);view.setUint16(22,1,true);const rate=audio.sample_rate||24000;view.setUint32(24,rate,true);view.setUint32(28,rate*2,true);view.setUint16(32,2,true);view.setUint16(34,16,true);word(36,"data");view.setUint32(40,raw.length,true);for(let i=0;i<raw.length;i++)bytes[44+i]=raw.charCodeAt(i);url=URL.createObjectURL(new Blob([bytes],{type:"audio/wav"}));mediaResultURLs.push(url);}else url=mediaBlob(audio.b64_json,audio.mime);
  const player=el("audio");player.controls=true;player.preload="metadata";player.src=url;player.setAttribute("aria-label","生成的語音");box.append(player);mediaDownload(box,url,"modelatlas-speech."+(audio.format==="pcm"?"wav":audio.format),"下載語音");box.append(el("p","這段語音由 AI 生成。"));
 }else if(operation==="AUDIO"){
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
 if(!["IMAGES_GENERATION","IMAGES_EDIT","AUDIO","AUDIO_SPEECH","GEMINI_SPEECH","EMBEDDINGS","RERANK"].includes(operation))return;
 if(!["AUDIO","EMBEDDINGS","GEMINI_SPEECH"].includes(operation)&&!prompt)return;if(!["IMAGES_GENERATION","AUDIO_SPEECH","GEMINI_SPEECH","EMBEDDINGS","RERANK"].includes(operation)&&(!mediaAttachment||mediaAttachment.kind!==(operation==="AUDIO"?"audio":"image"))){notice("請先選擇這次要處理的檔案",true);return;}
 const request_id=crypto.randomUUID(),payload={...selection,prompt:operation==="EMBEDDINGS"?"":prompt,request_id};if(operation==="IMAGES_GENERATION")payload.size=$("media-size").value;else if(["EMBEDDINGS","RERANK"].includes(operation)){const inputs=$("vector-inputs").value.split("\n").map(s=>s.trim()).filter(Boolean);payload[operation==="EMBEDDINGS"?"inputs":"documents"]=inputs;if(operation==="EMBEDDINGS"&&$("vector-dimensions").value)payload.dimensions=Number($("vector-dimensions").value);if(operation==="RERANK"&&$("rerank-top").value)payload.top_n=Number($("rerank-top").value);}else if(isSpeech(operation)){payload.voice=$("speech-voice").value;payload.format=$("speech-format").value;payload.speed=operation==="GEMINI_SPEECH"?1:Number($("speech-speed").value);payload.instructions=$("speech-instructions").value;if(operation==="GEMINI_SPEECH"){payload.speech_dialect=$("speech-dialect").value;try{if($("speech-speakers").value.trim())payload.speakers=JSON.parse($("speech-speakers").value);if($("speech-turns").value.trim())payload.turns=JSON.parse($("speech-turns").value);}catch(err){notice("說話者或對話 JSON 格式有誤",true);return;}if(payload.turns?.length){payload.prompt="";payload.instructions="";}}}else payload[operation==="AUDIO"?"audio":"image"]=mediaAttachment;
 setBusy(true);mediaRequestID=request_id;controller=new AbortController();clearMediaResult();$("media-result").append(el("p",operation==="AUDIO"?"正在轉寫…":isSpeech(operation)?"正在生成語音…":"正在處理圖片…"));
 try{
  const response=await fetch("/api/v1/infer/media",{method:"POST",credentials:"same-origin",signal:controller.signal,headers:{"Content-Type":"application/json"},body:JSON.stringify(payload)});
  const raw=await response.text();let result;try{result=JSON.parse(raw);}catch(e){throw Error(raw.trim()==="selected media capability unavailable"?"請在模型詳情確認此連線的能力，並檢查 Key 是否啟用":raw.trim().slice(0,150)||"HTTP "+response.status);}
  if(!response.ok||result.outcome!=="SUCCESS"){if(result.details?.sample_id){clearMediaResult();requestLink($("media-result"),result.details.sample_id,"查看失敗請求");}throw Error({PARSER:"供應商回傳的結果無法使用",RATE_LIMITED:"供應商目前限制請求，請稍後重試",AUTH_INVALID:"Key 無效，請在 Provider 更新",INFERENCE_DENIED:"此 Key 沒有使用這個模型的權限",PAYMENT_REQUIRED:"供應商要求付款或補充額度",MODEL_UNAVAILABLE:"供應商無法提供這個模型",GONE:"供應商已移除此端點",UPSTREAM_5XX:"供應商暫時無法處理請求",NETWORK:"無法連上供應商",TIMEOUT:"處理逾時",CANCELLED:"已停止處理",TELEMETRY_SAVE:"請求紀錄無法保存"}[result.outcome]||result.outcome||"HTTP "+response.status);}
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
