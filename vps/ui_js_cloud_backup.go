package main

const cloudBackupHTML = `
<section class="card" id="cloud-backup-card" aria-labelledby="cloud-backup-title">
<div class="section-heading"><h2 id="cloud-backup-title">加密雲端備份</h2><button id="cloud-backup-refresh" type="button" class="ghost">重新讀取</button></div>
<p id="cloud-backup-summary" role="status" aria-live="polite">尚未讀取備份狀態。</p>
<p id="cloud-backup-problem" class="error" role="alert" hidden></p>
<dl id="cloud-backup-details" class="system-status-details"></dl>
<form id="cloud-backup-policy" hidden>
<label class="check"><input id="cloud-backup-enabled" type="checkbox">定時備份</label>
<label>備份間隔（分鐘）<input id="cloud-backup-interval" type="number" min="15" max="10080" step="1" inputmode="numeric" required></label>
<label class="check"><input id="cloud-backup-keep-local" type="checkbox">完成後另保留本機快照</label>
<p class="section-description">成功上傳並重新下載驗證後，會保留雲端版本與完整工作紀錄。未勾選時只清除這次的本機暫存；失敗快照保留供重試。</p>
<p id="cloud-backup-draft" role="status"></p>
<div class="form-actions"><button id="cloud-backup-save" type="submit">確認並保存</button><button id="cloud-backup-discard" type="button" class="ghost">放棄草稿並重讀</button></div>
</form>
<div class="form-actions"><button id="cloud-backup-run" type="button" hidden>立即備份目前資料</button><button id="cloud-backup-cancel" type="button" class="ghost" hidden>取消目前備份</button></div>
<details id="cloud-backup-history-details"><summary>備份紀錄</summary><div id="cloud-backup-history"></div><button id="cloud-backup-more" type="button" class="ghost" hidden>更早的備份</button></details>
<p class="section-description">備份包含完整資料庫，不會將復原金鑰放進同一份檔案。請另行安全保存備份密碼與金鑰。下載及還原由主機端執行；下方設定匯出仍只包含可攜設定。</p>
</section>
`

const jsCloudBackup = `
let cloudBackupState=null,cloudBackupDraft=null,cloudBackupReading=false,cloudBackupWriting=false,cloudBackupAvailable=false,cloudBackupNeedsRead=false,cloudBackupRequestID=null,cloudBackupRequestFingerprint=null,cloudBackupNextBefore=null,cloudBackupTimer=null;
const cloudBackupRows=new Map(),cloudBackupStates={PENDING:'等待中',SNAPSHOT:'建立一致快照',UPLOADING:'加密上傳中',VERIFYING:'重新下載並驗證',SUCCESS:'已回讀驗證',FAILED:'備份未完成',INTERRUPTED:'程序曾中斷',CANCELLED:'已取消'};
const cloudBackupEventPages=new Map(),cloudBackupOpenEvents=new Set();
const cloudBackupErrors={SNAPSHOT_FAILED:'快照尚未通過驗證，已停止建立新快照，請檢查主機後重試。',TRANSFER_FAILED:'上傳未完成，本機快照仍保留。',READBACK_FAILED:'重新下載驗證未完成，尚不能作為已驗備份。',LOCAL_STATE_UNAVAILABLE:'工作狀態需要重新讀取，請保留原識別碼。',PROCESS_INTERRUPTED:'程序在完成確認前中斷，保留原始狀態。',CANCELLED_OR_TIMED_OUT:'傳輸已停止，既有備份保留。'};
const cloudBackupInt=(v,min=0,max=Number.MAX_SAFE_INTEGER)=>Number.isSafeInteger(v)&&v>=min&&v<=max;
function cloudBackupValidJob(j){return j&&cloudBackupInt(j.sequence,1)&&typeof j.id==='string'&&/^backup-[A-Za-z0-9_.-]+$/.test(j.id)&&Object.hasOwn(cloudBackupStates,j.state)&&cloudBackupInt(j.created_at,1)&&cloudBackupInt(j.updated_at,1)&&cloudBackupInt(j.snapshot_at)&&typeof j.destination==='string'&&typeof j.error_code==='string'&&typeof j.current_source==='boolean'&&typeof j.sha256==='string'&&(j.sha256===''||/^[a-f0-9]{64}$/.test(j.sha256))&&(j.state!=='SUCCESS'||j.snapshot_at>0&&j.sha256.length===64);}
function cloudBackupValidState(s){if(!s||typeof s.configured!=='boolean'||typeof s.available!=='boolean')return false;if(!s.configured)return s.available===false&&typeof s.reason==='string';const p=s.policy;return s.available===true&&typeof s.destination==='string'&&/^[a-f0-9]{64}$/.test(s.fingerprint)&&typeof s.key_recovery_confirmed==='boolean'&&p&&cloudBackupInt(p.revision)&&typeof p.enabled==='boolean'&&typeof p.binding_valid==='boolean'&&cloudBackupInt(p.interval_minutes,15,10080)&&typeof p.keep_local==='boolean'&&typeof s.active_id==='string'&&cloudBackupInt(s.last_success_at)&&cloudBackupInt(s.last_snapshot_at)&&cloudBackupInt(s.next_before)&&Array.isArray(s.jobs)&&s.jobs.length<=25&&s.jobs.every(cloudBackupValidJob)&&(s.recovered_job===null||cloudBackupValidJob(s.recovered_job));}
function cloudBackupSetDraft(s){cloudBackupDraft={revision:s.policy.revision,fingerprint:s.fingerprint,enabled:s.policy.enabled,interval:String(s.policy.interval_minutes),keep_local:s.policy.keep_local,dirty:false};$('cloud-backup-enabled').checked=cloudBackupDraft.enabled;$('cloud-backup-interval').value=cloudBackupDraft.interval;$('cloud-backup-keep-local').checked=cloudBackupDraft.keep_local;}
function cloudBackupCanSave(){const d=cloudBackupDraft,s=cloudBackupState;return cloudBackupAvailable&&!cloudBackupReading&&!cloudBackupWriting&&!cloudBackupNeedsRead&&s&&d&&d.dirty&&d.revision===s.policy.revision&&d.fingerprint===s.fingerprint&&/^\d+$/.test(d.interval)&&cloudBackupInt(Number(d.interval),15,10080)&&(!d.enabled||s.key_recovery_confirmed);}
function cloudBackupRender(){
 const s=cloudBackupState,locked=!cloudBackupAvailable||cloudBackupReading||cloudBackupWriting||cloudBackupNeedsRead;
 $('cloud-backup-refresh').disabled=cloudBackupReading||cloudBackupWriting;$('cloud-backup-policy').hidden=!s;
 for(const id of ['cloud-backup-enabled','cloud-backup-interval','cloud-backup-keep-local'])$(id).disabled=cloudBackupWriting||!cloudBackupState;
 $('cloud-backup-save').disabled=!cloudBackupCanSave();$('cloud-backup-discard').disabled=cloudBackupReading||cloudBackupWriting||!s;
 $('cloud-backup-run').hidden=!s;$('cloud-backup-run').textContent=cloudBackupRequestID?'以原識別碼重試建立':'立即備份目前資料';$('cloud-backup-run').disabled=locked||!s?.key_recovery_confirmed||!!s?.active_id||[...cloudBackupRows.values()].some(j=>['PENDING','SNAPSHOT','UPLOADING','VERIFYING'].includes(j.state));
 $('cloud-backup-cancel').hidden=!s?.active_id;$('cloud-backup-cancel').disabled=cloudBackupWriting;
 $('cloud-backup-more').hidden=!cloudBackupNextBefore;$('cloud-backup-more').disabled=cloudBackupReading||cloudBackupWriting||cloudBackupNeedsRead;
 const stale=cloudBackupDraft?.dirty&&s&&(cloudBackupDraft.revision!==s.policy.revision||cloudBackupDraft.fingerprint!==s.fingerprint);
 $('cloud-backup-draft').textContent=stale?'設定已被其他操作更新，草稿仍保留；請明確放棄草稿後再編輯。':cloudBackupNeedsRead?'操作結果尚待讀取，請先重新讀取狀態。':cloudBackupDraft?.dirty?'有尚未保存的設定。':s&&!s.policy.binding_valid?'目的地尚未確認綁定，定時備份不會執行。':'';
 if(!s)return;
 const details=$('cloud-backup-details');details.replaceChildren();const row=(title,value)=>details.append(el('dt',title),el('dd',value));
 row('目的地',s.destination);row('最近已驗證快照',s.last_snapshot_at?new Date(s.last_snapshot_at).toLocaleString():'尚無已驗證快照');row('最近驗證完成',s.last_success_at?new Date(s.last_success_at).toLocaleString():'尚無成功紀錄');row('金鑰另存',s.key_recovery_confirmed?'你已確認另存備份密碼與金鑰':'需要先在主機確認另行保存');row('定時備份',s.policy.enabled&&s.policy.binding_valid?'已啟用 · 每 '+s.policy.interval_minutes+' 分鐘':'未啟用');
 const history=$('cloud-backup-history');history.replaceChildren();
 for(const j of [...cloudBackupRows.values()].sort((a,b)=>b.sequence-a.sequence)){
  const box=el('article',undefined,'card');box.append(el('h3',cloudBackupStates[j.state]),el('p',j.destination+' · '+new Date(j.snapshot_at||j.created_at).toLocaleString()));
  const identity=el('p',j.id);identity.style.overflowWrap='anywhere';box.append(identity);if(!j.current_source)box.append(el('p','先前部署設定的備份，不借作目前資料的成功紀錄。'));if(j.error_code)box.append(el('p',cloudBackupErrors[j.error_code]||'備份尚未完成，原檔保留。'));
  if(j.sha256){const digest=el('p','SHA256 '+j.sha256);digest.style.overflowWrap='anywhere';box.append(digest);}
  if(j.current_source&&['FAILED','INTERRUPTED','CANCELLED'].includes(j.state)){const retry=el('button','重試此備份','ghost');retry.type='button';retry.dataset.backupRetry=j.id;retry.disabled=locked||!!s.active_id;retry.addEventListener('click',()=>runCloudBackup(j.id,true));box.append(retry);}
  const steps=el('details');steps.dataset.backupEvents=j.id;steps.open=cloudBackupOpenEvents.has(j.id);steps.append(el('summary','過程紀錄'));const holder=el('div');steps.append(holder);steps.addEventListener('toggle',()=>{if(steps.open){cloudBackupOpenEvents.add(j.id);if(!cloudBackupEventPages.has(j.id))loadCloudBackupEvents(j.id);}else cloudBackupOpenEvents.delete(j.id);});box.append(steps);cloudBackupRenderEvents(j.id,holder);history.append(box);
 }
 if(!cloudBackupRows.size)history.append(el('p','尚無備份紀錄。'));
}
function cloudBackupRenderEvents(id,holder){
 holder.replaceChildren();const state=cloudBackupEventPages.get(id);if(!state){holder.append(el('p','展開後讀取此備份的過程。'));return;}
 if(state.error)holder.append(el('p','讀取失敗，保留已取得的過程。 '+state.error,'error'));
 for(const event of state.rows){const row=el('p',new Date(event.at).toLocaleString()+' · '+cloudBackupStates[event.state]);if(event.error_code)row.append(document.createTextNode(' · '+(cloudBackupErrors[event.error_code]||'尚未完成確認。')));holder.append(row);}
 const refresh=el('button',state.reading?'正在讀取…':'重新讀取過程','ghost');refresh.type='button';refresh.disabled=state.reading;refresh.addEventListener('click',()=>loadCloudBackupEvents(id));holder.append(refresh);
 if(state.next){const more=el('button','更早的過程','ghost');more.type='button';more.disabled=state.reading;more.addEventListener('click',()=>loadCloudBackupEvents(id,state.next));holder.append(more);}
}
async function loadCloudBackupEvents(id,before=0){
 let state=cloudBackupEventPages.get(id);if(state?.reading)return;if(!state){state={rows:[],next:0,reading:false,error:null};cloudBackupEventPages.set(id,state);}state.reading=true;state.error=null;
 const render=()=>{for(const details of $('cloud-backup-history').querySelectorAll('details[data-backup-events]'))if(details.dataset.backupEvents===id)cloudBackupRenderEvents(id,details.querySelector('div'));};render();
 try{const value=await api('/backup/cloud/events?id='+encodeURIComponent(id)+(before?'&before='+before:''));if(!value||!cloudBackupValidJob(value.job)||value.job.id!==id||!Array.isArray(value.events)||value.events.length>25||!cloudBackupInt(value.next_before)||!value.events.every(e=>e&&e.job_id===id&&cloudBackupInt(e.sequence,1)&&cloudBackupInt(e.at,1)&&Object.hasOwn(cloudBackupStates,e.state)&&typeof e.error_code==='string'))throw Error('無法辨識此備份的完整過程。');const rows=before?state.rows:[];const seen=new Set(rows.map(e=>e.sequence));for(const event of value.events)if(!seen.has(event.sequence)){rows.push(event);seen.add(event.sequence);}state.rows=rows;state.next=value.next_before;}
 catch(error){state.error=error.message;}finally{state.reading=false;render();}
}
async function loadCloudBackups(discard=false,before=0,poll=false){
 if(cloudBackupReading||cloudBackupWriting)return false;cloudBackupReading=true;clearTimeout(cloudBackupTimer);$('cloud-backup-problem').hidden=true;cloudBackupRender();
 try{
  const lookup=cloudBackupRequestID||cloudBackupState?.active_id,params=new URLSearchParams();if(before)params.set('before',String(before));if(lookup)params.set('id',lookup);let missingOriginal=false,state;try{state=await api('/backup/cloud'+(params.size?'?'+params:''));}catch(error){if(error.status!==404||!cloudBackupRequestID)throw error;state=await api('/backup/cloud');missingOriginal=true;}
  if(!cloudBackupValidState(state))throw Error('無法辨識完整備份狀態，請重新讀取。');
  cloudBackupAvailable=state.configured&&state.available;
  if(!state.configured){$('cloud-backup-summary').textContent=state.reason;if(!cloudBackupState)$('cloud-backup-history').replaceChildren();else{$('cloud-backup-problem').textContent='主機目前沒有可用備份設定，以下保留上次結果與草稿。';$('cloud-backup-problem').hidden=false;}return true;}
  if(cloudBackupRequestID&&cloudBackupRequestFingerprint!==state.fingerprint)throw Error('備份目的地已變更，請重新開啟此頁；原操作不會轉送到另一目的地。');if(cloudBackupRequestID&&!missingOriginal&&(!state.recovered_job||state.recovered_job.id!==cloudBackupRequestID))throw Error('尚未讀到原識別碼的結果，沒有重新送出備份。');
  if(cloudBackupState&&cloudBackupState.fingerprint!==state.fingerprint){cloudBackupRows.clear();cloudBackupEventPages.clear();cloudBackupOpenEvents.clear();cloudBackupNextBefore=null;}cloudBackupState=state;cloudBackupNeedsRead=false;if(cloudBackupNextBefore===null||before||!poll)cloudBackupNextBefore=state.next_before;
  if(discard||!cloudBackupDraft||!cloudBackupDraft.dirty)cloudBackupSetDraft(state);
  for(const j of state.jobs)cloudBackupRows.set(j.id,j);if(state.recovered_job)cloudBackupRows.set(state.recovered_job.id,state.recovered_job);
  if(cloudBackupRequestID&&!missingOriginal){cloudBackupRequestID=null;cloudBackupRequestFingerprint=null;}
  $('cloud-backup-summary').textContent=missingOriginal?'未查到原操作紀錄；如需重試，會沿用原識別碼。':state.active_id?'備份進行中，關閉網頁不會停止主機工作。':state.last_success_at?'已有重新下載並驗證的完整備份。':'尚無已完成回讀驗證的備份。';return true;
 }catch(error){cloudBackupAvailable=false;$('cloud-backup-problem').textContent=(cloudBackupState?'讀取失敗，保留上次結果與草稿。 ':'')+error.message;$('cloud-backup-problem').hidden=false;return false;}
 finally{cloudBackupReading=false;cloudBackupRender();if(cloudBackupState?.active_id&&currentPane==='settings'&&!$('settings-data').hidden)cloudBackupTimer=setTimeout(()=>loadCloudBackups(false,0,true),1500);}
}
for(const id of ['cloud-backup-enabled','cloud-backup-interval','cloud-backup-keep-local'])$(id).addEventListener('input',()=>{if(!cloudBackupDraft)return;cloudBackupDraft.enabled=$('cloud-backup-enabled').checked;cloudBackupDraft.interval=$('cloud-backup-interval').value;cloudBackupDraft.keep_local=$('cloud-backup-keep-local').checked;cloudBackupDraft.dirty=true;cloudBackupRender();});
$('cloud-backup-policy').addEventListener('submit',async event=>{
 event.preventDefault();if(!cloudBackupCanSave())return;const d={...cloudBackupDraft};cloudBackupWriting=true;cloudBackupRender();let saved=false;
 try{const receipt=await submit('/backup/cloud/policy',{revision:d.revision,enabled:d.enabled,interval_minutes:Number(d.interval),keep_local:d.keep_local,fingerprint:d.fingerprint,confirm:true});if(!receipt||receipt.revision!==d.revision+1)throw Error('設定保存結果尚待確認。');cloudBackupDraft.revision=receipt.revision;cloudBackupDraft.dirty=false;saved=true;}
 catch(error){$('cloud-backup-problem').textContent='保存結果尚待重新讀取，草稿仍保留。 '+error.message;$('cloud-backup-problem').hidden=false;}
 finally{cloudBackupWriting=false;cloudBackupNeedsRead=true;cloudBackupRender();}
 if(saved){const read=await loadCloudBackups();notice(read?'備份設定已保存。':'設定已保存，但讀取失敗；請重新讀取狀態。',!read);}
});
async function runCloudBackup(id=null,retry=false){
 if(!cloudBackupAvailable||cloudBackupReading||cloudBackupWriting||cloudBackupNeedsRead||cloudBackupState?.active_id)return;
 const request=id||cloudBackupRequestID||'backup-'+crypto.randomUUID();cloudBackupRequestID=request;cloudBackupRequestFingerprint=cloudBackupState.fingerprint;cloudBackupWriting=true;cloudBackupRender();let acknowledged=false;
 try{const receipt=await submit('/backup/cloud/run',{id:request,fingerprint:cloudBackupState.fingerprint,retry,confirm:true});if(!cloudBackupValidJob(receipt)||receipt.id!==request)throw Error('備份建立結果尚待確認。');acknowledged=true;cloudBackupRows.set(receipt.id,receipt);}
 catch(error){cloudBackupNeedsRead=true;if(error.status&&[400,403,409,422].includes(error.status))cloudBackupRequestID=null;$('cloud-backup-problem').textContent='備份結果尚待讀取，沒有自動重新送出。 '+error.message;$('cloud-backup-problem').hidden=false;}
 finally{cloudBackupWriting=false;cloudBackupRender();}
 if(acknowledged)await loadCloudBackups();
}
$('cloud-backup-cancel').addEventListener('click',async()=>{const id=cloudBackupState?.active_id;if(!id||cloudBackupWriting)return;cloudBackupWriting=true;cloudBackupRender();try{const receipt=await submit('/backup/cloud/cancel',{id});if(receipt?.id!==id||receipt.cancelling!==true)throw Error('取消結果尚待讀取。');}catch(error){notice(error.message,true);}finally{cloudBackupWriting=false;await loadCloudBackups();}});
$('cloud-backup-run').addEventListener('click',()=>runCloudBackup());$('cloud-backup-refresh').addEventListener('click',()=>loadCloudBackups());$('cloud-backup-discard').addEventListener('click',()=>loadCloudBackups(true));$('cloud-backup-more').addEventListener('click',()=>loadCloudBackups(false,cloudBackupNextBefore||0));
$('settings-data-tab').addEventListener('click',()=>loadCloudBackups());$('settings-toggle').addEventListener('click',()=>{if(currentPane==='settings')loadCloudBackups();});
`
