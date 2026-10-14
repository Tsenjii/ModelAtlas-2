package main

const appJS = jsCore + jsViews + jsChat + jsMedia + jsTools + jsResearch + jsDetails + jsOnboard + jsAssistant + jsKnowledge
const jsCore = `
"use strict";
const $=id=>document.getElementById(id);
let endpointProfiles=[],providers=[],accounts=[],credentials=[],models=[],myModels=[],sessions=[],settings={},busy=false,refreshVersion=0,loadVersion=0,attachments=[],controller=null,currentReview=null;
let pendingTools=[];
let mediaAttachment=null,mediaRequestID=null,mediaResultURLs=[],fileReadVersion=0,attachmentReads={};
const selectedCompare=new Map();
function el(tag,text,cls){const n=document.createElement(tag);if(text!==undefined)n.textContent=text;if(cls)n.className=cls;return n;}
function notice(msg,bad=false){$("notice").textContent=msg;$("notice").classList.toggle("error",bad);}
async function api(path,opts={}){const r=await fetch("/api/v1"+path,{credentials:"same-origin",cache:"no-store",...opts});if(!r.ok)throw Error((await r.text()).trim().slice(0,150)||"HTTP "+r.status);return r.json();}
async function submit(path,value,method="POST"){return api(path,{method,headers:{"Content-Type":"application/json"},body:JSON.stringify(value)});}
function data(form){return Object.fromEntries(new FormData(form).entries());}
function option(select,value,title){const n=el("option",title);n.value=value;select.append(n);}
function scope(m){return Object.fromEntries(["provider_id","account_id","credential_id","model_id","group","endpoint"].map(k=>[k,m[k]||""]));}
function key(m){return JSON.stringify(scope(m));}
function connectionLabel(m){const account=accounts.find(a=>a.provider_id===m.provider_id&&a.id===m.account_id),credential=credentials.find(c=>c.provider_id===m.provider_id&&c.account_id===m.account_id&&c.id===m.credential_id);return (account?.label||m.account_id)+" / "+(credential?.label||m.credential_id)+(m.group?" / "+m.group:"");}
function targetOperation(m){return endpointProfiles.find(p=>p.provider_id===m.provider_id&&p.id===m.endpoint)?.operation||m.endpoint;}
function chatOperation(op){return ["CHAT_COMPLETIONS","RESPONSES","ANTHROPIC_MESSAGES","GEMINI_NATIVE"].includes(op);}
const operationLabels={RESPONSES:"Responses 對話",ANTHROPIC_MESSAGES:"Claude 對話",GEMINI_NATIVE:"Gemini 對話",AUDIO_SPEECH:"語音生成",EMBEDDINGS:"文字向量",RERANK:"文件排序",CHAT_COMPLETIONS:"對話",IMAGES_GENERATION:"圖片生成",IMAGES_EDIT:"圖片編輯",AUDIO:"音訊轉寫"};
function query(m){return new URLSearchParams(scope(m)).toString();}
function item(target,title,detail,onClick){const n=el(onClick?"button":"div",undefined,"item");if(onClick){n.type="button";n.classList.add("item-button");n.addEventListener("click",onClick);}const a=el("strong",title);const b=el("small",detail);n.append(a,b);target.append(n);return n;}
function action(target,label,callback,ghost=true){const b=el("button",label,ghost?"ghost":"");b.type="button";b.addEventListener("click",async()=>{b.disabled=true;try{await callback();}catch(e){notice(e.message,true);}finally{b.disabled=false;}});target.append(b);return b;}
function show(tab){document.querySelectorAll(".pane").forEach(n=>n.hidden=n.id!==tab);document.querySelectorAll(".bottom-nav button").forEach(b=>{b.classList.toggle("active",b.dataset.tab===tab);if(b.dataset.tab===tab)b.setAttribute("aria-current","page");else b.removeAttribute("aria-current");});if(tab==="settings")loadStatus();}
function bubble(role,text){const n=el("div",text,"bubble "+(role==="user"?"user":"assistant"));$("chat").append(n);$("chat").scrollTop=$("chat").scrollHeight;return n;}
function modal(title){$("detail-title").textContent=title;$("detail-content").replaceChildren();if(!$("detail").open)$("detail").showModal();return $("detail-content");}
function field(parent,label,type="text",value=""){const wrap=el("label",label),input=el(type==="textarea"?"textarea":"input");if(type!=="textarea")input.type=type;input.value=value;wrap.append(input);parent.append(wrap);return input;}
function factsView(parent,title,facts){const d=el("details"),summary=el("summary",title),pre=el("pre",JSON.stringify(facts,null,2));d.append(summary,pre);parent.append(d);}
`
