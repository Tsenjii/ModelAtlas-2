package main

// Saved views contain explicit display preferences only. They never change
// membership, credentials, ranking weights or the active inference scope.
const modelViewsHTML = `
<div class="model-view-tools">
<label class="saved-view-label"><span class="sr-only">已儲存的模型檢視</span><select id="model-view-select" aria-label="已儲存的模型檢視"><option value="">尚無已儲存檢視</option></select></label>
<button id="model-view-save-open" type="button" class="ghost" data-icon="plus" aria-expanded="false" aria-controls="model-view-form">儲存檢視</button>
<button id="model-view-delete" type="button" class="text-button" hidden>刪除檢視</button>
</div>
<form id="model-view-form" class="model-view-form" hidden>
<label>檢視名稱<input id="model-view-name" maxlength="48" required autocomplete="off" placeholder="例如：常用免費模型"></label>
<p class="section-description">保存目前搜尋、篩選、排序與效能來源，只儲存在此瀏覽器。</p>
<div class="actions"><button id="model-view-save" type="submit">儲存為新檢視</button><button id="model-view-cancel" type="button" class="ghost">取消</button></div>
</form>
<p id="model-view-status" class="model-view-status section-description" role="status" aria-live="polite" hidden></p>
<div id="model-active-filters" class="model-active-filters" aria-label="目前模型篩選" hidden></div>
`

const jsModelViews = `
const modelViewStorageKey="modelatlas.model-views.v1";
let modelSavedViews=[],modelSavedViewID="";
function modelFilterDefault(id){return id==="model-lifecycle"&&modelCollectionMode==="hidden"?"RETAINED":filterDefaults[id]||"";}
function modelFilterValues(){return Object.fromEntries(modelFilterIDs.map(id=>{const n=$(id);return [id,n.type==="checkbox"?n.checked:n.value];}));}
function readModelViews(){
 const raw=localStorage.getItem(modelViewStorageKey);if(!raw)return [];const doc=JSON.parse(raw);if(doc?.version!==1||!Array.isArray(doc.views)||doc.views.length>12)throw Error("檢視資料格式無法辨識。");
 const ids=new Set();return doc.views.filter(v=>{if(!v||typeof v.id!=="string"||!v.id||v.id.length>80||ids.has(v.id)||typeof v.name!=="string"||!v.name.trim()||v.name.length>48||!v.filters||typeof v.filters!=="object")return false;ids.add(v.id);return modelFilterIDs.every(id=>id==="model-usable"?typeof v.filters[id]==="boolean":typeof v.filters[id]==="string"&&v.filters[id].length<=1000);}).map(v=>({id:v.id,name:v.name,filters:Object.fromEntries(modelFilterIDs.map(id=>[id,v.filters[id]]))}));
}
function modelViewStatus(text,error=false){const n=$("model-view-status");n.hidden=!text;n.textContent=text;n.classList.toggle("error",error);n.setAttribute("role",error?"alert":"status");}
function writeModelViews(views){localStorage.setItem(modelViewStorageKey,JSON.stringify({version:1,views}));modelSavedViews=views;}
function modelViewNameChanged(){const selected=modelSavedViews.find(v=>v.id===modelSavedViewID);$("model-view-save").textContent=selected&&selected.name===$("model-view-name").value.trim()?"更新檢視":"儲存為新檢視";}
function renderModelViews(){
 const select=$("model-view-select"),values=modelFilterValues();select.replaceChildren();option(select,"",modelSavedViews.length?"選擇已儲存的檢視":"尚無已儲存檢視");for(const v of modelSavedViews){const changed=v.id===modelSavedViewID&&modelFilterIDs.some(id=>v.filters[id]!==values[id]);option(select,v.id,v.name+(changed?" · 已調整":""));}if(modelSavedViews.some(v=>v.id===modelSavedViewID))select.value=modelSavedViewID;else modelSavedViewID="";select.disabled=!modelSavedViews.length;$("model-view-delete").hidden=!modelSavedViewID;modelViewNameChanged();
}
function renderActiveModelFilters(){
 const box=$("model-active-filters");box.replaceChildren();for(const id of modelFilterIDs){const n=$(id);if(n.type==="checkbox"?!n.checked:n.value===modelFilterDefault(id))continue;
  const label=id==="model-search"?"搜尋":n.labels?.[0]?.firstChild?.textContent?.trim()||n.getAttribute("aria-label")||id,value=n.type==="checkbox"?"是":n.tagName==="SELECT"?n.selectedOptions[0]?.textContent||n.value:n.value,button=el("button",undefined,"filter-chip"),text=label+"："+value;button.type="button";button.title=text;button.setAttribute("aria-label","移除篩選："+text);button.append(el("span",text),el("span","×","filter-chip-remove"));button.lastChild.setAttribute("aria-hidden","true");button.addEventListener("click",()=>{if(n.type==="checkbox")n.checked=false;else n.value=modelFilterDefault(id);modelDisplayLimit=100;renderModels();$("model-search").focus({preventScroll:true});});box.append(button);
 }box.hidden=!box.children.length;renderModelViews();
}
function applyModelView(id){
 if(!id){modelSavedViewID="";renderModelViews();return;}const view=modelSavedViews.find(v=>v.id===id);if(!view)return;
 for(const field of modelFilterIDs){const n=$(field),value=view.filters[field];if(n.tagName==="SELECT"&&![...n.options].some(o=>o.value===value)){modelViewStatus("「"+view.name+"」的部分連線或選項已不存在，請調整目前條件後重新儲存。",true);renderModelViews();return;}if(n.type==="number"&&value!==""&&(!Number.isFinite(Number(value))||Number(value)<Number(n.min||0)||n.max!==""&&Number(value)>Number(n.max))){modelViewStatus("「"+view.name+"」有無效的篩選數值，請重新儲存。",true);renderModelViews();return;}}
 workspace("models",view.filters["model-lifecycle"]==="ACTIVE"?"models-owned":"models-hidden");for(const field of modelFilterIDs){const n=$(field);if(n.type==="checkbox")n.checked=view.filters[field];else n.value=view.filters[field];}if($("model-scope").value==="GLOBAL"){modelSelectionMode=false;selectedCompare.clear();}modelSavedViewID=id;modelDisplayLimit=100;renderModels();modelViewStatus("");
}
function setModelQuick(kind){$("model-favorites").value=kind==="favorite"?"favorite":kind==="watchlist"?"watchlist":"";$("model-price").value=kind==="free"?"FREE_NOW":"";$("model-changes").value=kind==="recent"?"recently_used":"";modelDisplayLimit=100;renderModels();}
function syncModelQuick(){const price=$("model-price").value,favorite=$("model-favorites").value,recent=$("model-changes").value;document.querySelectorAll("[data-model-quick]").forEach(b=>{const type=b.dataset.modelQuick,active=type==="all"?!price&&!favorite&&!recent:type==="favorite"?favorite==="favorite"&&!price&&!recent:type==="watchlist"?favorite==="watchlist"&&!price&&!recent:type==="recent"?recent==="recently_used"&&!price&&!favorite:price==="FREE_NOW"&&!favorite&&!recent;b.setAttribute("aria-pressed",String(active));});}
function bindModelViews(){
 try{modelSavedViews=readModelViews();}catch{modelViewStatus("無法讀取已儲存檢視；目前篩選仍可正常使用。",true);}renderModelViews();
 const form=$("model-view-form"),toggle=$("model-view-save-open"),close=()=>{form.hidden=true;toggle.setAttribute("aria-expanded","false");toggle.focus({preventScroll:true});};toggle.addEventListener("click",()=>{form.hidden=!form.hidden;toggle.setAttribute("aria-expanded",String(!form.hidden));if(!form.hidden){$("model-view-name").value=modelSavedViews.find(v=>v.id===modelSavedViewID)?.name||"";modelViewNameChanged();$("model-view-name").focus();}});$("model-view-cancel").addEventListener("click",close);$("model-view-name").addEventListener("input",modelViewNameChanged);$("model-view-select").addEventListener("change",e=>applyModelView(e.target.value));
 form.addEventListener("submit",e=>{e.preventDefault();const name=$("model-view-name").value.trim();if(!name)return;try{const views=readModelViews(),selected=views.find(v=>v.id===modelSavedViewID),update=selected?.name===name;if(views.some(v=>v.name===name&&(!update||v.id!==modelSavedViewID)))throw Error("已有同名檢視，請換個名稱或先選取該檢視再更新。");if(!update&&views.length>=12)throw Error("最多保存 12 個檢視，請先刪除不需要的檢視。");const filters=modelFilterValues();if(modelFilterIDs.some(id=>typeof filters[id]==="string"&&filters[id].length>1000))throw Error("搜尋或篩選內容過長，請縮短後再儲存。");const id=update?modelSavedViewID:crypto.randomUUID(),view={id,name,filters};writeModelViews(update?views.map(v=>v.id===id?view:v):[...views,view]);modelSavedViewID=id;renderModelViews();close();modelViewStatus("已"+(update?"更新":"儲存")+"「"+name+"」。");}catch(error){modelViewStatus("無法儲存檢視。"+error.message,true);}});
 $("model-view-delete").addEventListener("click",()=>{const id=modelSavedViewID;try{const views=readModelViews(),view=views.find(v=>v.id===id);writeModelViews(views.filter(v=>v.id!==id));modelSavedViewID="";renderModelViews();modelViewStatus(view?"已刪除「"+view.name+"」，目前篩選保留。":"此檢視已刪除，目前篩選保留。");}catch{modelViewStatus("無法刪除檢視，請確認瀏覽器允許儲存資料。",true);}});
 window.addEventListener("storage",e=>{if(e.key!==modelViewStorageKey&&e.key!==null)return;try{modelSavedViews=readModelViews();renderModelViews();}catch{modelViewStatus("其他分頁的檢視資料無法讀取，目前篩選保留。",true);}});
}
// Reuse the picker's exact joined-scope validation and its normal change path.
// Navigation never issues inference or silently starts a new conversation.
function openModelConnection(selection){
 if(busy){notice("請先等待目前請求完成，或停止後再切換連線。",true);return;}const value=key(selection);if(!modelPickerReady||!pickerAvailable().some(m=>key(m)===value)){notice("這個連線目前無法選取，請確認模型與 Key 的設定。",true);return;}
 choosePickerScope(value);$("detail").close();show("playground");workspace("playground","work-chat");$("model-picker-open").focus({preventScroll:true});
}
`
