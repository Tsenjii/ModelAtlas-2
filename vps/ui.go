package main

import (
	"net/http"
)

func (s *Server) home(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	_, _ = w.Write([]byte(homeHTML))
}
func (s *Server) stylesheet(w http.ResponseWriter, r *http.Request) {
	cssAsset.serve(w, r, "text/css; charset=utf-8")
}
func (s *Server) script(w http.ResponseWriter, r *http.Request) {
	jsAsset.serve(w, r, "text/javascript; charset=utf-8")
}

const homeHTML = `<!doctype html>
<html lang="zh-Hant">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="color-scheme" content="light dark">
<title>ModelAtlas</title>
<link rel="stylesheet" href="/app.css">
</head>
<body>
<div class="shell">
<header>
<div class="brand">
<span class="mark" aria-hidden="true">M</span>
<div>
<strong>ModelAtlas</strong>
<small>你的模型工作台</small>
</div>
</div>
<div class="header-actions"><button id="sidebar-toggle" type="button" class="ghost icon-button" aria-label="顯示或隱藏側邊欄" aria-pressed="false" data-icon="sidebar"></button><button id="header-refresh" type="button" class="ghost icon-button" aria-label="重新整理" data-icon="refresh"></button><button id="header-assistant" type="button" class="ghost" data-icon="spark">Assistant</button><button id="settings-toggle" class="ghost header-settings" type="button" aria-controls="settings" aria-expanded="false" data-icon="settings">設定</button></div>
</header>
<a class="skip-link" href="#main-content">跳到主要內容</a>
<aside class="desktop-sidebar" aria-label="對話側邊欄">
<button id="sidebar-model-picker" class="sidebar-more" type="button" data-icon="search">快速選擇模型 <kbd>⌘ K</kbd></button>
<div id="sidebar-chat-section" class="sidebar-chat-section">
<div class="sidebar-tools"><span class="sidebar-caption">最近對話</span><div class="sidebar-tools-actions"><details class="sidebar-filters"><summary aria-label="篩選對話" data-icon="filter"></summary><div class="sidebar-filter-menu"><label>對話狀態<select id="sidebar-session-state"><option value="ACTIVE">使用中</option><option value="ARCHIVED">已封存</option><option value="ALL">全部狀態</option></select></label><label class="check"><input id="sidebar-session-current" type="checkbox">僅目前模型連線</label></div></details><button id="sidebar-new-session" class="ghost icon-button" type="button" aria-label="建立新對話" data-icon="plus"></button></div></div>
<label class="sidebar-search"><span class="sr-only">搜尋近期對話</span><input id="sidebar-conversation-search" type="search" placeholder="搜尋對話"></label><div id="sidebar-session-status" class="sidebar-session-status" role="status" aria-live="polite"></div><div id="sidebar-conversations" class="sidebar-conversations"></div><button id="sidebar-session-more" type="button" class="sidebar-more" hidden>載入更多對話</button><button id="sidebar-history" class="sidebar-more" type="button" data-icon="history">管理對話紀錄</button>
</div><span class="sidebar-footer">VPS 工作區</span></aside>
<main id="main-content">
<p id="notice" role="status" aria-live="polite">
</p>
<section id="playground" class="pane" aria-labelledby="playground-title">
<div class="heading">
<h1 id="playground-title">Playground</h1>
<p id="playground-description"></p>
</div>
<div class="workspace-tabs" role="tablist" aria-label="playground 工作區" data-workspace-tabs="playground">
<button type="button" role="tab" id="work-chat-tab" aria-controls="work-chat" aria-selected="true" tabindex="0" data-icon="chat">對話與創作</button>
<button type="button" role="tab" id="assistant-panel-tab" aria-controls="assistant-panel" aria-selected="false" tabindex="-1" data-icon="spark">Assistant</button>
</div>
<div id="work-chat" class="workspace-panel" role="tabpanel" aria-labelledby="work-chat-tab" data-workspace-panel="playground">
<div class="card chat-card">
<div class="toolbar target-bar">
<button id="model-picker-open" disabled class="ghost model-picker-trigger" type="button" aria-label="選擇模型" aria-haspopup="dialog" aria-controls="model-picker" data-icon="models"><span id="model-picker-title">正在讀取模型…</span><kbd>⌘ / Ctrl K</kbd></button>
<select id="target" aria-label="使用的模型" hidden></select>
<button id="new-session" class="ghost" type="button" data-icon="plus">新對話</button>
</div>
<div id="chat-ui">
<div class="conversation-bar">
<h2 id="conversation-title">新對話</h2>
<div class="conversation-controls">
<button id="conversation-library" type="button" class="ghost icon-button" aria-label="對話紀錄" title="對話紀錄" data-icon="history">
</button>
<details id="conversation-more" class="conversation-more">
<summary aria-label="對話工具與設定" title="對話工具與設定" data-icon="more">
<span class="sr-only">對話工具與設定</span>
</summary>
<div class="conversation-menu">
<div class="conversation-menu-heading"><h3>對話工具</h3><button id="chat-tools-close" type="button" class="ghost" aria-label="關閉工具">完成</button></div>
<div class="actions">
<button id="conversation-manage" type="button" class="ghost">整理對話</button>
<button id="conversation-requests" type="button" class="ghost">請求紀錄</button>
</div>
<details>
<summary>先前對話</summary>
<label>對話<select id="session">
</select>
</label>
</details>
<details id="tools-options">
<summary>工具定義</summary>
<label>Functions JSON<textarea id="tools-json" rows="4" placeholder='[{"type":"function","function":{"name":"get_weather","description":"取得天氣","parameters":{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}}}]'>
</textarea>
</label>
<p>模型提出呼叫後，填入工具結果即可接續。工具定義與結果保留在這段對話。</p>
</details>
<details id="chat-options">
<summary>對話設定</summary>
<label>這段對話的系統指示<textarea id="chat-system" rows="3" maxlength="16000" placeholder="例如：使用繁體中文，附上來源">
</textarea>
</label>
<label>回應 Token 上限<input id="chat-max-tokens" type="number" min="1" max="32768" value="512">
</label>
<label class="check">
<input id="chat-streaming" type="checkbox" checked>串流回應</label>
<button id="chat-settings-save" type="button" class="ghost">儲存對話設定</button>
<p>只套用這段 Playground 對話；傳送時也會保存。</p>
</details>
</div>
</details>
</div>
</div>
<div class="chat-viewport">
<div id="chat" class="chat" role="log" aria-label="對話訊息" aria-live="off" aria-busy="false" tabindex="0">
</div>
<button id="chat-latest" class="ghost chat-latest" type="button" aria-label="回到最新訊息" hidden>
<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
<path d="M12 4v16m-7-7 7 7 7-7"/>
</svg>
<span>回到最新</span>
<span id="chat-unread" class="unread-dot" aria-hidden="true" hidden>
</span>
</button>
</div>
<div id="attachments" class="attachments">
</div>
<div id="tool-results">
</div>
<form id="send-form" class="composer">
<label for="prompt" class="sr-only">訊息</label>
<textarea id="prompt" aria-describedby="prompt-shortcuts" rows="1" maxlength="32000" required placeholder="輸入訊息…">
</textarea>
<div class="toolbar composer-tools">
<div class="attachment-buttons">
<label class="file-button" data-icon="image">圖片<input id="image-picker" type="file" accept="image/png,image/jpeg,image/webp">
</label>
<label class="file-button" data-icon="file">PDF<input id="pdf-picker" type="file" accept="application/pdf">
</label>
<label class="file-button" data-icon="audio">音訊<input id="audio-picker" type="file" accept="audio/wav,audio/mpeg,.wav,.mp3">
</label>
</div>
<div class="send-actions">
<button id="stop" type="button" class="ghost icon-button" aria-label="停止" title="停止回應" data-icon="stop" hidden>
</button>
<button id="send" class="icon-button" aria-label="傳送" title="傳送訊息" data-icon="send">
</button>
</div>
</div>
</form>
<small id="prompt-shortcuts" class="composer-hint">Enter 換行 · Ctrl／⌘ + Enter 傳送</small>
</div>
<div id="media-ui" hidden>
<div id="media-model-bar" class="media-model-bar"></div>
<h2 id="media-operation">
</h2>
<form id="media-form">
<label for="media-prompt" id="media-prompt-label">描述</label>
<textarea id="media-prompt" rows="3" maxlength="32000" required>
</textarea>
<label id="media-size-label">圖片尺寸<select id="media-size">
<option value="">供應商預設</option>
<option>256x256</option>
<option>512x512</option>
<option>1024x1024</option>
<option>1024x1536</option>
<option>1536x1024</option>
<option>auto</option>
</select>
</label>
<div id="vector-options" hidden>
<label id="vector-input-label">輸入文字（每行一筆）<textarea id="vector-inputs" rows="5" maxlength="131072">
</textarea>
</label>
<label id="vector-dimensions-label">向量維度（選填）<input id="vector-dimensions" type="number" min="1" max="8192">
</label>
<label id="rerank-top-label">傳回前幾筆（選填）<input id="rerank-top" type="number" min="1" max="32">
</label>
<p>最多 32 筆，各筆 32 KB；原始結果可下載。</p>
</div>
<div id="speech-options" hidden>
<label>聲音<input id="speech-voice" value="alloy" maxlength="64" required>
</label>
<label>音訊格式<select id="speech-format">
<option value="mp3">MP3</option>
<option value="wav">WAV</option>
<option value="opus">Opus</option>
<option value="flac">FLAC</option>
<option value="aac">AAC</option>
<option value="pcm">PCM 24 kHz</option>
</select>
</label>
<label id="speech-speed-label">語速<input id="speech-speed" type="number" value="1" min="0.25" max="4" step="0.25">
</label>
<label>說話方式（選填）<textarea id="speech-instructions" maxlength="4096">
</textarea>
</label>
<details id="native-speech-options" hidden>
<summary>進階語音設定</summary>
<label>Gemini 語音協定<select id="speech-dialect">
<option value="CURRENT">目前格式</option>
<option value="LEGACY">舊版 prebuiltVoiceConfig</option>
</select>
</label>
<details>
<summary>雙人對話（選填）</summary>
<label>兩位說話者 JSON<textarea id="speech-speakers" rows="3" maxlength="2048" placeholder='[{"name":"Joe","voice":"Puck"},{"name":"Jane","voice":"Kore"}]'>
</textarea>
</label>
<label>對話 JSON<textarea id="speech-turns" rows="4" maxlength="8192" placeholder='[{"speaker":"Joe","text":"你好","style":"親切"}]'>
</textarea>
</label>
<p>使用目前格式時可選兩種預設聲音；填入對話後不需再填上方描述。</p>
</details>
</details>
</div>
<label id="media-file-label" class="file-button">
<span id="media-file-caption">選擇檔案</span>
<input id="media-file" type="file">
</label>
<p id="media-input-name">每份檔案限 4 MiB</p>
<div class="toolbar">
<button id="media-stop" type="button" class="ghost" hidden>停止處理</button>
<button id="media-send">生成圖片</button>
<button id="media-clear" type="button" class="ghost">清除結果</button>
</div>
</form>
<div id="media-result" role="region" aria-label="媒體結果" aria-live="polite">
</div>
</div>
</div>
</div>` + assistantHTML + `</section>
<section id="providers" class="pane" hidden aria-labelledby="providers-title">
<div class="heading heading-actions">
<div>
<small class="page-eyebrow">Connections</small><h1 id="providers-title">供應商</h1>
<p><span id="provider-page-count">管理帳戶與模型連線。</span></p>
</div>
<button id="provider-add" type="button" data-icon="plus">新增供應商</button>
</div>
<div class="workspace-tabs" role="tablist" aria-label="providers 工作區" data-workspace-tabs="providers">
<button type="button" role="tab" id="providers-owned-tab" aria-controls="providers-owned" aria-selected="true" tabindex="0" data-icon="provider">供應商</button>
<button type="button" role="tab" id="providers-new-tab" aria-controls="providers-new" aria-selected="false" tabindex="-1" data-icon="plus">新增與匯入</button>
</div>
<div id="providers-owned" class="workspace-panel" role="tabpanel" aria-labelledby="providers-owned-tab" data-workspace-panel="providers">
<div class="collection-tools">
<label class="search-label">搜尋 Provider<input id="provider-search" type="search" placeholder="搜尋供應商、網址或描述">
</label>
<label>排序<select id="provider-sort">
<option value="name">名稱</option>
<option value="smart">目前可用模型綜合表現</option>
</select>
</label>
</div>
<div class="quick-filters" aria-label="供應商快速篩選"><button type="button" data-provider-quick="all" aria-pressed="true">全部</button><button type="button" data-provider-quick="free" aria-pressed="false">有免費連線</button><button type="button" data-provider-quick="models" aria-pressed="false">已加入模型</button><button type="button" data-provider-quick="pending" aria-pressed="false">待簽到</button></div>
<div id="checkin-summary" class="checkin-summary" role="region" aria-label="今日簽到進度" hidden></div>
<div class="collection-meta">
<span id="provider-result-count">
</span>
</div>
<div id="provider-list" class="cards provider-grid">
</div>
</div>
<div id="providers-new" class="workspace-panel" role="tabpanel" aria-labelledby="providers-new-tab" data-workspace-panel="providers" hidden>
<details class="card" open>
<summary>新增 Provider</summary>
<p class="section-description">填入連線資訊，檢查後再確認加入。</p>
<form id="onboarding-form">
<label>名稱<input name="name" maxlength="120" required>
</label>
<label>API Base URL<input name="base_url" type="url" placeholder="https://example.com/v1" required>
</label>
<label>API Key<input name="secret" type="password" autocomplete="off">
</label>
<label>Model ID（選填）<input name="model_id" placeholder="多個模型 ID 以逗號分隔">
</label>
<details>
<summary>進階設定</summary>
<label>新增方式<select name="mode">
<option value="QUICK">Quick Add</option>
<option value="ADVANCED">Advanced Add</option>
</select>
</label>
<label>Adapter<select name="adapter">
<option value="GENERIC">自動 / OpenAI 相容</option>
<option>NEW_API</option><option>ANTHROPIC</option>
<option>OPENROUTER_FREE</option>
<option>NVIDIA_NIM_HOSTED</option>
<option>NVIDIA_NIM_SELF_HOSTED</option>
<option>AMD_SHARED</option>
<option>AMD_DEDICATED</option>
<option>GEMINI_FREE</option>
<option>GROQ_FREE</option>
</select>
</label>
<label>認證<select name="auth_mode">
<option>BEARER</option>
<option>API_KEY</option>
<option>CUSTOM_HEADER</option>
<option>NONE</option>
</select>
</label>
<label>自訂 Key Header<input name="auth_header" placeholder="x-api-key">
</label>
<label>認證前綴<input name="auth_prefix"></label>
<label>自訂 Key Query<input name="auth_query">
</label>
<label>額外認證欄位 JSON（選填）<textarea name="auth_fields" placeholder='[{"name":"x-token","secret":"...","query":false}]'>
</textarea>
</label>
<label>帳戶名称<input name="account_label" value="主要帳戶">
</label>
<label>Key 所屬 Group<input name="group">
</label>
<label>主要模型用途<select name="endpoint"><option>CHAT_COMPLETIONS</option><option>RESPONSES</option><option>ANTHROPIC_MESSAGES</option><option>GEMINI_NATIVE</option><option>IMAGES_GENERATION</option><option>IMAGES_EDIT</option><option>AUDIO</option><option>AUDIO_SPEECH</option><option>GEMINI_SPEECH</option><option>EMBEDDINGS</option><option>RERANK</option></select></label>
<label>更多端點 JSON（選填）<textarea name="endpoint_profiles" placeholder='[{"operation":"CHAT_COMPLETIONS","url":"https://example.com/v2/chat/completions"}]'>
</textarea>
</label>
<label>Chat 路徑（選填）<input name="chat_path" placeholder="/v1/chat/completions">
</label>
<label>官網<input name="website" type="url">
</label>
<label>簽到網址<input name="checkin_url" type="url">
</label>
<label>New API 帳戶 PAT（選填）<input name="pat" type="password" autocomplete="off">
</label>
</details>
<button>檢查並預覽</button>
</form>
</details>
<details class="card">
<summary>批次匯入</summary>
<form id="import-form">
<label>Provider 設定 JSON<textarea name="json" rows="5" required placeholder='{"items":[...]}'>
</textarea>
</label>
<button>建立待確認清單</button>
</form>
<div id="onboarding-list" class="cards">
</div>
</details>
</div>
</section>
<section id="models" class="pane" hidden aria-labelledby="models-title">
<div class="heading">
<small class="page-eyebrow">Model Library</small><h1 id="models-title">模型</h1>
<p>你的模型收藏與探索空間。</p>
</div>
<div class="workspace-tabs" role="tablist" aria-label="models 工作區" data-workspace-tabs="models">
<button type="button" role="tab" id="models-owned-tab" aria-controls="models-owned" aria-selected="true" tabindex="0" data-icon="models">我的模型</button>
<button type="button" role="tab" id="models-catalog-tab" aria-controls="models-catalog" aria-selected="false" tabindex="-1" data-icon="search">探索 Catalog</button>
<button type="button" role="tab" id="models-hidden-tab" aria-controls="models-hidden" aria-selected="false" tabindex="-1" data-icon="history">已隱藏</button>
<button type="button" role="tab" id="research-workspace-tab" aria-controls="research-workspace" aria-selected="false" tabindex="-1" data-icon="spark">全球研究</button>
<button type="button" role="tab" id="knowledge-panel-tab" aria-controls="knowledge-panel" aria-selected="false" tabindex="-1" data-icon="book">研究知識</button>
</div>
<div id="models-owned" class="workspace-panel" role="tabpanel" aria-labelledby="models-owned-tab" data-workspace-panel="models">
<div class="collection-search">
<label class="search-field">
<span class="sr-only">搜尋模型</span>
<input id="model-search" type="search" placeholder="搜尋模型、帳戶、Key 或群組">
</label>
<button id="model-select" type="button" class="ghost" aria-pressed="false" data-icon="check">
<span id="model-select-label">選取</span>
</button>
</div>
<div class="model-filter-strip">
<div class="quick-filters" aria-label="模型快速篩選">
<button type="button" data-model-quick="all" aria-pressed="true">全部</button>
<button type="button" data-model-quick="favorite" aria-pressed="false">最愛</button>
<button type="button" data-model-quick="free" aria-pressed="false">FREE NOW</button>
</div>
<details id="model-filters">
<summary data-icon="filter">篩選模型<span id="model-filter-count">
</span>
</summary>
<div class="toolbar">
<label>顯示<select id="model-lifecycle">
<option value="ACTIVE">使用中</option>
<option value="HIDDEN">已隱藏</option><option value="RETAINED">已隱藏與封存</option>
<option value="ARCHIVED">已封存</option>
</select>
</label>
</div>
<div class="toolbar">
<label>能力<select id="model-capability">
<option value="">全部</option>
<option>VISION</option>
<option>FILES</option>
<option>AUDIO_INPUT</option>
<option>CODING</option>
<option>REASONING</option>
<option>TOOL_CALLING</option>
</select>
</label>
<label>價格<select id="model-price">
<option value="">全部</option>
<option value="FREE_NOW">FREE NOW</option>
<option value="PAID">付費</option>
<option value="PARTIALLY_FREE">部分免費</option>
<option value="UNKNOWN">尚未確認</option>
</select>
</label>
<label>收藏<select id="model-favorites">
<option value="">全部</option>
<option value="favorite">最愛</option>
<option value="watchlist">追蹤</option>
</select>
</label>
</div>
<label>Provider<select id="model-provider">
<option value="">全部</option>
</select>
</label>` + modelControlsHTML + `</details>
</div>
<div class="collection-meta">
<span id="model-result-count">
</span>
<button id="model-filter-reset" type="button" class="text-button" hidden>重設篩選</button>
</div>
<div id="model-selection-bar" class="selection-bar" role="region" aria-label="批次模型管理" hidden>
<strong id="model-selected-count">
</strong>
<button id="model-selection-clear" type="button" class="ghost">清除選取</button>
<div class="actions">
<button id="compare" class="ghost" type="button">比較勾選的模型</button>
<button id="bulk-hide" class="ghost" type="button">預覽隱藏勾選模型</button>
<button id="bulk-archive" class="ghost" type="button">預覽封存勾選模型</button>
<button id="bulk-restore" class="ghost" type="button">預覽恢復勾選模型</button>
</div>
</div>
<div id="my-models" class="cards">
</div>
<button id="model-more" type="button" class="ghost" hidden>顯示更多模型</button>
<div id="compare-result">
</div>
</div>
<div id="models-hidden" class="workspace-panel" role="tabpanel" aria-labelledby="models-hidden-tab" data-workspace-panel="models" hidden></div>
<details id="models-catalog" class="card workspace-panel" role="tabpanel" aria-labelledby="models-catalog-tab" data-workspace-panel="models" hidden>
<summary>探索 Catalog</summary>
<div class="section-heading">
<h2>探索模型</h2>
<p>Catalog 是供應商清單；選擇加入後才會出現在我的模型。</p>
</div>
<div class="catalog-searchbar">
<label>搜尋 Catalog<input id="catalog-search" type="search" placeholder="名稱、ID、別名或供應商"></label>
<label>供應商<select id="catalog-provider"><option value="">全部供應商</option></select></label>
</div>
<details class="catalog-filters"><summary>篩選 Catalog</summary>
<div class="catalog-filter-grid">
<label>型錄類別<select id="catalog-category"><option value="">全部類別</option><option value="CHAT_REASONING">對話與推理</option><option value="CODING">程式開發</option><option value="IMAGE">圖像</option><option value="AUDIO">音訊</option><option value="EMBEDDING">向量</option><option value="RERANK">排序</option><option value="SAFETY">安全</option><option value="OTHER">其他</option></select></label>
<label>型錄標示能力<select id="catalog-capability"><option value="">全部能力</option><option>REASONING</option><option>CODING</option><option>TOOL_CALLING</option><option>VISION</option><option>FILES</option><option>AUDIO_INPUT</option><option>AUDIO_OUTPUT</option><option>IMAGE_GENERATION</option><option>EMBEDDING</option><option>RERANK</option></select></label>
<label>加入狀態<select id="catalog-membership"><option value="">全部模型</option><option value="new">尚未加入</option><option value="joined">已加入任一連線</option></select></label>
</div>
<p class="section-description">型錄標示供探索參考；每把 Key 的能力與價格仍以連線詳情為準。已加入也包含隱藏或封存的連線。</p>
</details>
<div class="catalog-resultbar"><p id="catalog-result-count" role="status"></p><button id="catalog-reset" type="button" class="ghost" hidden>清除 Catalog 篩選</button></div>
<div id="catalog" class="cards">
</div>
<button id="catalog-more" type="button" class="ghost" hidden>顯示更多 Catalog</button>
<details id="catalog-manual" class="catalog-manual"><summary>手動加入模型</summary>
<form id="model-form">
<p class="section-description">找不到 Model ID 時，可直接指定連線與端點加入。</p>
<div id="manual-error" class="detail-state detail-state-error" role="alert" hidden></div>
<label>連線<select name="selection" id="manual-target">
</select>
</label>
<label>推論端點<select id="manual-endpoint" name="endpoint"></select></label>
<button id="manual-setup" class="ghost" type="button" hidden>設定連線</button>
<label>Model ID<input name="model_id" required>
</label>
<label>顯示名稱<input name="display_name" required>
</label>
<button id="manual-add">加入 My Models</button>
</form>
</details>
</details>
<details id="research-workspace" class="card workspace-panel" role="tabpanel" aria-labelledby="research-workspace-tab" data-workspace-panel="models" hidden>
<summary>全球免費強模研究</summary>
<div class="section-heading">
<h2>全球免費強模研究</h2>
<p>追蹤來源、驗證證據，保留完整研究歷程。</p>
</div>
<div id="research-funnel" class="metrics">
</div>
<div class="toolbar">
<label>研究狀態<select id="research-filter">
<option value="">全部</option>
<option>DISCOVERED</option>
<option>VERIFYING</option>
<option>VERIFIED</option>
<option>HIGH_VALUE_GREY_ZONE</option>
<option>STALE</option>
<option>FAILED</option>
<option>REJECTED</option>
</select>
</label>
<button id="research-refresh" type="button" class="ghost">更新研究資料</button>
</div>
<div id="research-candidates" class="cards">
</div>
<button id="research-more" type="button" class="ghost" hidden>顯示更多候選</button>
<details>
<summary>研究來源</summary>
<div id="research-sources" class="cards">
</div>
<form id="research-source-form">
<label>來源名稱<input name="name" required maxlength="120">
</label>
<label>公開 HTTPS 網址<input name="url" type="url" required>
</label>
<label>資料類型<select name="kind">
<option value="JSON">結構 JSON</option>
<option value="HTML">網頁／文件</option>
</select>
</label>
<label>語言<select name="language">
<option value="en">English</option>
<option value="zh">中文</option>
<option value="ru">Русский</option>
<option value="ja">日本語</option>
<option value="ko">한국어</option>
<option value="es">Español</option>
<option value="pt">Português</option>
<option value="id">Bahasa Indonesia</option>
</select>
</label>
<label>對應 Provider<select name="provider_id" id="research-source-provider">
<option value="">未確認</option>
</select>
</label>
<label class="check">
<input name="official" type="checkbox">Provider 公開結構資料（必須同 origin）</label>
<details>
<summary>欄位對照</summary>
<label>模型清單 JSON Pointer<input name="items_pointer" placeholder="/data">
</label>
<label>欄位對照 JSON（選填）<textarea name="fields" placeholder='{"model_id":"/id","rpd":"/limits/requests_per_day"}'>
</textarea>
</label>
<p>未提供對照時使用 model_id、rpd、tpd、taiwan、recurring、free 等同名欄位。</p>
</details>
<button>保存研究來源</button>
</form>
</details>
<form id="research-run-form">
<h2>開始一輪研究</h2>
<label>已保存來源<select name="source_ids" id="research-run-sources" multiple>
</select>
</label>
<label>已加入的連線（選填）<select name="selection" id="research-run-target">
<option value="">不查我的 Key</option>
</select>
</label>
<label class="check">
<input name="minimal_inference" type="checkbox">必要時做一次小型推論驗證</label>
<label class="check">
<input name="search" type="checkbox">多語網路探索（需 Brave Search Key）</label>
<label class="check">
<input name="lmspeed_seeds" type="checkbox">使用已保存的 LMSpeed 發現線索</label>
<label class="check">
<input name="refresh_aa" type="checkbox">更新 AA 榜單（需 AA Key）</label>
<div class="toolbar">
<label>HTTP 預算<input name="budget" type="number" value="40" min="1" max="200">
</label>
<label>推論預算<input name="probe_budget" type="number" value="2" min="0" max="10">
</label>
</div>
<button>開始研究</button>
</form>
<div id="research-runs" class="cards">
</div>
<details>
<summary>AA 最新有效榜單</summary>
<p>資料來源：<a href="https://artificialanalysis.ai" target="_blank" rel="noopener noreferrer">Artificial Analysis</a>
</p>
<div id="aa-ranking">
</div>
<button id="aa-sync" type="button" class="ghost">更新 AA 榜單</button>
</details>
</details>` + knowledgeHTML + `</section>
<section id="settings" class="pane" hidden aria-labelledby="settings-title">
<div class="heading heading-actions">
<div>
<h1 id="settings-title">設定</h1>
<p>讓工作台符合你的習慣。</p>
</div>
<button id="settings-back" type="button" class="ghost" data-icon="back">返回</button>
</div>
<div class="settings-workspace">
<div class="workspace-tabs" role="tablist" aria-label="settings 工作區" data-workspace-tabs="settings">
<button type="button" role="tab" id="settings-general-tab" aria-controls="settings-general" aria-selected="true" tabindex="0" data-icon="settings">一般</button>
<button type="button" role="tab" id="settings-services-tab" aria-controls="settings-services" aria-selected="false" tabindex="-1" data-icon="spark">服務</button>
<button type="button" role="tab" id="settings-data-tab" aria-controls="settings-data" aria-selected="false" tabindex="-1" data-icon="book">資料</button>
<button type="button" role="tab" id="settings-research-tab" aria-controls="settings-research" aria-selected="false" tabindex="-1" data-icon="search">研究</button>
<button type="button" role="tab" id="settings-requests-tab" aria-controls="settings-requests" aria-selected="false" tabindex="-1" data-icon="history">請求紀錄</button>
<button type="button" role="tab" id="settings-notifications-tab" aria-controls="settings-notifications" aria-selected="false" tabindex="-1" data-icon="bell">通知</button>
</div>
<div id="settings-research" class="workspace-panel" role="tabpanel" aria-labelledby="settings-research-tab" data-workspace-panel="settings" hidden></div>
<div id="settings-requests" class="workspace-panel" role="tabpanel" aria-labelledby="settings-requests-tab" data-workspace-panel="settings" hidden></div>
<div id="settings-notifications" class="workspace-panel" role="tabpanel" aria-labelledby="settings-notifications-tab" data-workspace-panel="settings" hidden></div>
<div id="settings-general" class="workspace-panel" role="tabpanel" aria-labelledby="settings-general-tab" data-workspace-panel="settings">
<div class="card">
<h2>顯示外觀</h2>
<label>主題<select id="appearance">
<option value="system">跟隨系統</option>
<option value="light">淺色</option>
<option value="dark">深色</option>
</select>
</label>
<label>重點色<select id="appearance-accent"><option value="graphite">石墨灰</option><option value="violet">鳶尾紫</option><option value="indigo">靛藍</option></select></label><label class="check"><input id="appearance-reduce-motion" type="checkbox">減少動態效果</label>

<p class="section-description">套用在這個瀏覽器，下次開啟也會保留。</p>
</div>
<div class="card">
<form id="settings-form">
<label>效能時間窗<select name="performance_window">
<option>1h</option>
<option>24h</option>
<option selected>7d</option>
<option>30d</option>
<option value="all">全部</option>
</select>
</label>
<label>時區<input name="timezone" value="Asia/Taipei" required>
</label>
<label class="check">
<input name="notifications" type="checkbox">啟用本機通知</label>
<details>
<summary>背景同步</summary>
<label class="check">
<input name="background_enabled" type="checkbox">定期同步已加入的連線</label>
<label>間隔（分鐘）<input name="background_minutes" type="number" value="60" min="15" max="10080">
</label>
<label>每輪最多連線<input name="background_scopes" type="number" value="2" min="1" max="10">
</label>
<p>同步 Catalog 與資料，保留原歷史；不會自動推論測試或加入模型。</p>
</details>
<details>
<summary>逐項通知規則</summary>
<label class="check">
<input name="became_free" type="checkbox">已確認價格變成 FREE NOW</label>
<label class="check">
<input name="quota_low" type="checkbox">額度低於門檻</label>
<label>剩餘額度門檻<input name="quota_remaining_below" type="number" value="100" min="0">
</label>
<label class="check">
<input name="checkin_pending" type="checkbox">今日尚未簽到</label>
<label class="check">
<input name="favorite_recovered" type="checkbox">最愛模型恢復成功</label>
<label class="check">
<input name="price_changed" type="checkbox">已確認價格變更</label>
<label class="check">
<input name="outage" type="checkbox">請求由成功轉成失敗</label>
<label class="check">
<input name="recovery" type="checkbox">請求恢復成功</label>
</details>
<button>儲存設定</button>
</form>
</div>
<details class="card">
<summary>鍵盤快捷鍵</summary>
<dl class="shortcut-list">
<dt>
<kbd>Ctrl／⌘ + K</kbd>
</dt>
<dd>搜尋模型連線</dd>
<dt><kbd>Ctrl／⌘ + Shift + K</kbd></dt>
<dd>搜尋對話紀錄</dd>
<dt>
<kbd>Ctrl／⌘ + Shift + O</kbd>
</dt>
<dd>建立新對話</dd>
<dt>
<kbd>Ctrl／⌘ + Enter</kbd>
</dt>
<dd>在訊息欄傳送</dd>
<dt>
<kbd>Esc</kbd>
</dt>
<dd>停止目前對話回應；詳情視窗開啟時先關閉視窗。</dd>
</dl>
<p class="media-hint">生成中不切換模型或建立新對話；快捷鍵不會重複送出。</p>
</details>
</div>
<div id="settings-services" class="workspace-panel" role="tabpanel" aria-labelledby="settings-services-tab" data-workspace-panel="settings" hidden>
<div class="card">
<h2>研究服務</h2>
<form id="research-key-form">
<label>服務<select name="name">
<option value="AA">Artificial Analysis</option>
<option value="BRAVE_SEARCH">Brave Search</option>
</select>
</label>
<label>API Key<input name="secret" type="password" required autocomplete="off">
</label>
<button>保存研究 Key</button>
</form>
<p>Key 只在伺服器的安全層使用。</p>
</div>
<div class="card">
<h2>LMSpeed</h2>
<p>外部資料與你的實測分開保存。<a href="https://lmspeed.net" target="_blank" rel="noopener noreferrer">資料來源</a>
</p>
<form id="lmspeed-form">
<label>LMSpeed 公開 API Key<input name="secret" type="password" autocomplete="off" required>
</label>
<button>儲存 Key</button>
</form>
<button id="lmspeed-sync" class="ghost" type="button">同步外部資料</button>
</div>
</div>
<div id="settings-data" class="workspace-panel" role="tabpanel" aria-labelledby="settings-data-tab" data-workspace-panel="settings" hidden>
<div class="card">
<h2>備份與還原</h2>
<p>觀測與歷史持續保留。</p>
<a class="link-button" href="/api/v1/backup" download>匯出設定（不含 Key）</a>
<details>
<summary>還原設定</summary>
<form id="restore-form">
<label>設定 JSON<textarea name="json" required>
</textarea>
</label>
<label class="check">
<input name="confirm" type="checkbox" required>確認匯入（Key 需要重新輸入）</label>
<button>還原設定</button>
</form>
</details>
</div>
<details class="card">
<summary>同步與研究紀錄</summary>
<div id="sync-history">
</div>
<pre id="research">
</pre>
</details>
<details class="card">
<summary>最近請求</summary>
<button id="request-history" type="button" class="ghost">瀏覽全部請求</button>
<div id="telemetry">
</div>
</details>
<details class="card">
<summary>通知紀錄</summary>
<div id="notification-list" class="cards">
</div>
</details>
<p id="status" class="section-description">
</p>
</div>
</div>
</section>
</main>
<nav class="bottom-nav" aria-label="主分頁">
<button data-tab="playground" data-icon="chat" class="active" aria-current="page">
<span>Playground</span>
</button>
<button data-tab="providers" data-icon="provider">
<span>Providers</span>
</button>
<button data-tab="models" data-icon="models">
<span>Models</span>
</button>
</nav>
</div>
<dialog id="model-picker" aria-labelledby="model-picker-heading" aria-describedby="model-picker-hint">
<div class="model-picker-head"><div><h2 id="model-picker-heading">選擇模型</h2><p id="model-picker-hint">從已加入的連線選擇，未送出草稿會留在這個分頁。</p></div><button id="model-picker-close" type="button" class="ghost">完成</button></div>
<div class="model-picker-filters">
<label class="model-picker-search"><span class="sr-only">搜尋模型、供應商、帳戶或 Key</span><svg class="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true"><path d="M21 21l-4.5-4.5M18 10a8 8 0 1 1-16 0 8 8 0 0 1 16 0Z"/></svg><input id="model-picker-search" type="search" placeholder="搜尋模型、供應商、帳戶或 Key" role="combobox" aria-autocomplete="list" aria-controls="model-picker-results" aria-expanded="false" autocomplete="off"></label>
<label><span class="sr-only">篩選選模供應商</span><select id="model-picker-provider" aria-label="篩選選模供應商"><option value="">所有供應商</option></select></label>
<label class="check"><input id="model-picker-favorites" type="checkbox">只看收藏</label>
</div>
<div id="model-picker-results" role="listbox" aria-label="已加入的模型連線"></div>
<button id="model-picker-more" type="button" class="ghost model-picker-more" hidden>載入更多連線</button>
<div class="model-picker-footer"><span id="model-picker-count" role="status" aria-live="polite"></span><button id="model-picker-reset" type="button" class="ghost" hidden>清除篩選</button><button id="model-picker-use" type="button">使用模型</button></div>
</dialog>
<dialog id="detail" aria-labelledby="detail-title">
<div class="dialog-bar">
<h2 id="detail-title">
</h2>
<button id="detail-close" class="ghost" type="button" aria-label="關閉詳情">關閉</button>
</div>
<div id="detail-content">
</div>
</dialog>
<dialog id="request-receipt" aria-labelledby="request-receipt-title"><div class="dialog-bar"><h2 id="request-receipt-title">請求詳情</h2><button id="request-receipt-close" type="button" class="ghost" aria-label="關閉請求收據">關閉</button></div><div id="request-receipt-content"></div></dialog>
<dialog id="provider-editor" aria-labelledby="provider-editor-title"><div class="dialog-bar"><h2 id="provider-editor-title"></h2><button id="provider-editor-close" type="button" class="ghost" aria-label="關閉編輯表單">關閉</button></div><div id="provider-editor-body"></div></dialog>
<dialog id="review">
<div class="dialog-bar">
<h2>確認新增</h2>
<button id="review-close" class="ghost" type="button">關閉</button>
</div>
<form id="review-form">
<div id="review-content">
</div>
<button>確認並加入</button>
</form>
</dialog>
<script src="/app.js" defer>
</script>
</body>
</html>`
