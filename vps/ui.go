package main

import (
 "net/http"
)

func (s *Server) home(w http.ResponseWriter,r *http.Request){
 w.Header().Set("Content-Type","text/html; charset=utf-8")
 w.WriteHeader(200)
 _,_=w.Write([]byte(homeHTML))
}
func (s *Server) stylesheet(w http.ResponseWriter,r *http.Request){
 w.Header().Set("Content-Type","text/css; charset=utf-8")
 _,_=w.Write([]byte(appCSS))
}
func (s *Server) script(w http.ResponseWriter,r *http.Request){
 w.Header().Set("Content-Type","text/javascript; charset=utf-8")
 _,_=w.Write([]byte(appJS))
}
const homeHTML = `<!doctype html>
<html lang="zh-Hant"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="color-scheme" content="light dark"><title>ModelAtlas VPS</title><link rel="stylesheet" href="/app.css"></head>
<body><div class="shell"><header><div class="brand"><span class="mark" aria-hidden="true">◈</span><div><strong>ModelAtlas</strong><small>VPS · Go / SQLite</small></div></div><button id="settings-toggle" class="ghost" type="button" aria-controls="settings">設定</button></header>
<main><div id="notice" role="status" aria-live="polite"></div>
<section id="playground" class="pane" aria-labelledby="playground-title"><div class="heading"><h1 id="playground-title">Playground</h1><p>指定連線、保留對話與請求紀錄，不會自動切換 Provider。</p></div>
<div class="card"><label for="session">對話</label><select id="session"></select><button id="new-session" type="button">建立新對話</button>
<div id="chat" role="log" aria-live="polite" class="chat"><p class="empty">建立對話後開始聊天。</p></div>
<form id="send-form"><label for="prompt">訊息</label><textarea id="prompt" rows="3" maxlength="32000" required placeholder="輸入訊息…"></textarea><button id="send" type="submit">傳送</button></form></div></section>
<section id="providers" class="pane" hidden aria-labelledby="providers-title"><div class="heading"><h1 id="providers-title">Providers</h1><p>獨立的供應商、帳戶及加密憑證。</p></div>
<div class="columns"><div class="card"><h2>新增 Provider</h2><form id="provider-form"><label>識別碼<input name="id" required placeholder="example"></label><label>名稱<input name="name" required></label><label>API Base URL<input name="base_url" type="url" required placeholder="https://example.com/v1"></label><label>登入模式<select name="auth_mode"><option>BEARER</option><option>NONE</option></select></label><button>新增</button></form></div>
<div class="card"><h2>帳戶與憑證</h2><form id="account-form"><label>Provider<select name="provider_id" class="provider-select"></select></label><label>帳戶 ID<input name="id" required></label><label>顯示名稱<input name="label" required></label><button>新增帳戶</button></form><hr>
<form id="credential-form"><label>Provider<select name="provider_id" class="provider-select"></select></label><label>帳戶 ID<input name="account_id" required></label><label>憑證 ID<input name="id" required></label><label>名稱<input name="label" required></label><label>憑證用途<select name="role"><option value="INFERENCE">Inference API Key</option><option value="ACCOUNT_PAT">New API Account PAT（管理專用）</option></select></label><label>API Key / PAT（加密儲存）<input name="secret" type="password" autocomplete="off"></label><button>儲存憑證</button></form></div></div><div class="card"><h2>我的 Providers</h2><div id="provider-list" class="cards"></div></div></section>
<section id="models" class="pane" hidden aria-labelledby="models-title"><div class="heading"><h1 id="models-title">Models</h1><p>Catalog 與 My Models 分離。未知價格不等於免費。</p></div><div class="columns"><div class="card"><h2>加入 Catalog</h2><form id="model-form"><label>Provider<select name="provider_id" class="provider-select"></select></label><label>Model ID<input name="id" required></label><label>顯示名稱<input name="display_name" required></label><button>加入 Catalog</button></form><h2>同步真實 Catalog</h2><form id="sync-form"><label>Provider<select name="provider_id" class="provider-select"></select></label><label>帳戶 ID<input name="account_id" required></label><label>憑證 ID<input name="credential_id" required></label><button>查詢 Provider /models</button></form></div>
<div class="card"><h2>加入 My Models</h2><form id="membership-form"><label>Provider<select name="provider_id" class="provider-select"></select></label><label>帳戶 ID<input name="account_id" required></label><label>憑證 ID<input name="credential_id" required></label><label>Model ID<input name="model_id" required></label><label>Group<input name="group" placeholder="留空為 default"></label><input type="hidden" name="endpoint" value="CHAT_COMPLETIONS"><button>明確加入</button></form></div></div><div class="card"><h2>My Models（已選擇）</h2><div id="my-models" class="cards"></div></div><div class="card"><h2>Catalog</h2><div id="catalog" class="cards"></div></div></section>
<section id="settings" class="pane" hidden aria-labelledby="settings-title"><div class="heading"><h1 id="settings-title">設定及紀錄</h1><p>所有資料存 SQLite；撤銷 My Models 不會刪除歷史。</p></div><div class="card"><h2>狀態</h2><pre id="status"></pre><h2>最近 Telemetry</h2><pre id="telemetry"></pre></div><div class="card"><h2>研究 Evidence / Crawler</h2><p>未驗證線索仍保留，絕不冒充 recurring-free。進階研究以 API 操作。</p><pre id="research"></pre></div></section>
</main><nav class="bottom-nav" aria-label="主分頁"><button data-tab="playground" class="active" aria-current="page">Playground</button><button data-tab="providers">Providers</button><button data-tab="models">Models</button></nav></div><script src="/app.js" defer></script></body></html>`
