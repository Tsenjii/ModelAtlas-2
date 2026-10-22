# ModelAtlas VPS — Go / SQLite

最新優先序是 **VPS 第一、Mac 第二、Android 第三**。同一來源的三平台下載見 [Releases](https://github.com/Tsenjii/ModelAtlas-2/releases)，固定 tag／完整套件／發布檢查見 [RELEASES.md](../docs/RELEASES.md)。VPS 提供 AMD64／ARM64 tar.gz、VERSION.json／RELEASE.json、逐檔校驗與 install／update／download 工具；原備份、健康驗證與失敗／中斷回復保留。不使用自動安裝 HEAD 的更新方式。

以下 SHA／量測是各切片歷史基線；最新整合版本以 Release 的來源 commit／RELEASE-MANIFEST.json 與 PR #20 同 SHA 九項為準，不把舊六項綠燈移作新版本證據。

接續 VPS 公開研究排程第一階段：正式 UI／啟停／紀錄、每輪及每日 HTTP 上限、重啟接續與來源變更確認見 [RESEARCH_SCHEDULES.md](RESEARCH_SCHEDULES.md)。code `b1e0c66` 已同 SHA 六項 SUCCESS／23 Browser／303圖、Android976與原完整 workload；證據見 `../docs/verification/20261020/vps-scheduler-ci-baseline.json`。整體研究願望仍 PARTIAL，後續 HEAD 各自六項 CI；未遠端部署。維運 code `034e775` 已六項成功，證據見 `../docs/verification/20261020/vps-ops-ci-baseline.json`。

已驗維運基線 VPS-OPS-006：先完成 VPS 日常使用與後續更新。完整架構套件包含版本／校驗、首次安裝、完整備份與副本預檢、健康驗證、自動回復及中斷復原；設定→資料可查看服務版本與儲存／Key 狀態。操作見 [UPDATE.md](UPDATE.md)。同 SHA 六項 CI 才驗收，不宣稱已在使用者主機部署；原跨平台與完整研究願望清單仍 PARTIAL。


這是與 Android／Mac 同 repo、同來源交付、獨立程序執行的 VPS Web 服務。完整 V1 功能與實測驗收見 [DELIVERY.md](DELIVERY.md)。源自 Android 已驗收的產品契約（`docs/DECISIONS.md`、`docs/PRODUCT_SPEC.md`、`docs/RESEARCH_POLICY.md`），不是把 Android APK 改名當伺服器，也不會憑空聲稱全部同等功能。

## 架構及安全

- Go 1.26、純 Go SQLite driver、單一 Linux binary（AMD64 / ARM64），沒有常駐 Node.js、沒有 Java Runtime。
- SQLite WAL + 單連線順序化 + FK + 交易；Provider、Account、Credential、Catalog、My Models、Sessions、Messages、Telemetry、研究 Candidate/Evidence/Run、行動審核與版本化 Recipe 皆落地。
- 單一明確 Provider × Account × Credential × Model × Group × Endpoint scope；不自動換 Key、不 Fallback、不提供無授權的 API Gateway。
- AES-256-GCM 加密 API Key，master key **僅私密環境設定**；資料庫不寫 plaintext key，GET API 不回傳 key。管理端 HTTP Basic/Bearer 使用長隨機密碼，預設強制 loopback 監聽；明確啟用的平台模式須符合隔離的 HTTPS ingress 契約。
- Web UI 僅 Playground／Providers／Models 三工作區，設定在右上角；桌面側欄／手機底導覽、繁中、中性色與可選重點色、深淺色、48px 觸控與窄螢幕適配。

## 目前可用功能

- Quick / Advanced 新增、可恢復 Review、批次匯入、多 Account / Key、啟用／停用、加密的自訂 header/query、Key version／rollback。
- Catalog 與 My Models 分開；canonical/alias、多維分類、搜尋、favorite/watchlist、hidden/archive、詳情與來源／歷史；加入後自動排入 durable metadata enrichment。
- 真實 Chat Completions：文字與 reasoning SSE、完整對話、停止與新對話；圖片／PDF／WAV／MP3 每種一份且各限 4 MiB，exact-scope 能力重新驗證。
- Manual Test / Compare Live 都走同一推論與 telemetry；預設 7d，可切 1h/24h/30d/All；取消排除 My Uptime，rolling-60s 用量、TTFT/TTFB/TPS 各自有語義。
- New API rc.25 status/notice/pricing/PAT account/inference token/perf；保留精確價格／group 倍率／版本／來源，nominal price 不冒充 effective charge。
- Dedicated adapter registry、LMSpeed 固定正主單頁匯入與持久化 budget/cache；官方、站方、本地、外部不互相覆蓋。
- Check-in 按設定時區本地日期；通知預設 OFF；無 Key 的設定匯出與原子還原（credential 必須重填再啟用）。
- 研究 candidate/evidence、可逆 action preview/confirm/undo、recipe version/rollback 保留既有 API。

功能核對、修復清單、Android 主線本身尚未完成的能力，以及測試限制見 [PARITY.md](PARITY.md)。多模態 raw 附件不入 DB；服務重啟後，含媒體的舊對話必須另建新對話。沒有支援的 transport 會明確拒絕，不能藉設定混送 protocol。

## 本機測試與編譯

```sh
cd vps
go mod download
go test -mod=readonly -race ./...
go vet ./...
CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o modelatlas-vps .
```

## VPS 部署（Debian / Ubuntu，systemd）

Infrlo 等 Git 平台的 build／run 命令、`PORT`、私密環境變數與持久資料要求，見 [PLATFORM_DEPLOY.md](PLATFORM_DEPLOY.md)。平台模式為明確 opt-in；沒有聲稱已在 Infrlo 實測部署。

從通過六項檢查的 Actions 下載架構套件，核對 commit／SHA256、解開 tar.gz 後，首次執行 `sudo sh deploy/install.sh "$PWD"`；後續在新套件內執行 `sudo sh deploy/update.sh "$PWD"`。會沿用原資料與金鑰，失敗回復及斷電復原流程見 [UPDATE.md](UPDATE.md)。

預設 loopback 監聽；外部以 SSH tunnel 或固定 HTTPS 入口連線。管理帳號 `admin`，密碼保存在 root-only `/etc/modelatlas/modelatlas.env`。既有非標準路徑／無 root 平台依更新文件使用離線 ops，不套用 systemd 工具。

目前未提供目標主機 IP／服務網址／登入方式，尚未遠端部署；CI 不代表使用者主機已可登入。

## 備份

SQLite 資料庫、WAL、SHM 與 `MODELATLAS_MASTER_KEY` 必須有相互一致、分開保管的備份。請使用 SQLite backup API 或關閉服務後才複製 DB，避免不一致。丟失 master key 後無法復原 encrypted API Key。

## API 摘要

`GET /healthz` 公開只回服務健康；其餘 API 必須管理認證。路徑前綴 `/api/v1`：

- 維運：`GET /system`（管理認證、版本／schema／啟動 vault 狀態）；`version` 與離線 `ops` 見更新文件。
- Providers / Accounts / Credentials：`GET, POST /providers`、`/accounts`、`/credentials`
- Models：`GET, POST /models`、`POST /models/sync`、`GET, POST, DELETE /my-models`
- Playground：`GET, POST /sessions`、`GET /messages?session_id=`、`POST /infer`（text/event-stream）
- Telemetry：`GET /telemetry`
- Intelligence：`GET /models/detail`（完整七維 selected scope）、`POST /intelligence/sync`、`GET /intelligence/history`
- Endpoint Profiles：`GET,POST /endpoints`，同 HTTPS origin 的獨立端點 ID／URL／protocol，不互借歷史與能力。
- Models 操作：`POST /models/{metadata,capabilities,facts,lifecycle,test,compare}`
- Onboarding：`GET /onboarding`、`POST /onboarding/{preview,retry,confirm,import}`
- 憑證：`POST /credentials/{toggle,rotate}`；自訂秘密只存在 encrypted vault
- New API：`POST /newapi/{status,notice,pricing,account,token,summary,performance}`、`GET /newapi/snapshots`
- LMSpeed：`POST /lmspeed/{key,sync}`、`GET /lmspeed/models`
- 設定：`GET,POST /settings`、`GET,POST /checkins`、`GET /backup`、`POST /backup/restore`
- 媒體 Playground：`POST /infer/media`（圖片生成／編輯／音訊轉寫，對應表單、圖片預覽／下載、多行文字結果）。結果只保留於頁面記憶體；圖片總 4 MiB／4 MiPixels，JSON 上限 8 MiB。
- 停止：`POST /infer/cancel`，Chat body 為 `session_id`；媒體則為送出時的 `request_id`，兩者擇一。共用四個 in-flight 上限。
- Research：`GET, POST /research/candidates`、`/research/evidence`、`/research/runs`、`GET /research/eligibility`、`POST /research/probe`
- Assistant：`POST /assistant/preview`、`/assistant/confirm`、`/assistant/undo`
- Recipes：`GET, POST /recipes`、`POST /recipes/rollback`

勿在 API 網址 query string 傳遞 Token／Key。

## 路徑與 Group

Provider Config 的自訂 endpoint path 以 origin-root 為基準，例如 `/v1/chat/completions`；留空則沿用 Base URL + adapter default。所有攜带憑證的 URL 保持同一 HTTPS origin，拒絕 redirect。Group 在 Credential 建立時明確綁定，只有相同 group 的選定對話可發出；不附加杜撰的 `X-Group` header。

## Browser CI

Node.js / Playwright 僅供開發與 CI，VPS 產品 runtime 不需要 Node.js。測試執行正常 `Server.Handler`、SQLite 與 TLS fixture provider，實際操作 Review／model／chat／hide/restore，不以 HTML 字串或 mock DOM 當 browser 驗收。


### 媒體操作

在 Provider 詳情新增同 origin 的 `IMAGES_GENERATION`／`IMAGES_EDIT`／`AUDIO` endpoint profile；從 Catalog 將模型加入該 endpoint，在模型詳情明確確認圖片生成／圖片編輯／音訊能力，再選「使用此模型」。Playground 會顯示對應表單。WAV／MP3 不需要錄音或麥克風權限；圖片編輯使用本機 PNG／JPEG／WebP。沒有已確認的能力時，在 HTTP 前拒發。

圖片結果可直接預覽／下載；轉寫保留換行並可下載文字。供應商只回傳 HTTPS 圖片網址時，提供「開啟供應商圖片」，不自動向該網址發請求，也不轉送認證。結果在切換連線／清除／關閉頁面後釋放；請下載需要保留的結果。

此為 VPS 新結果 UI 切片，驗收依最新 PR #20 同 SHA Actions；不代表 Android 剩餘 PLAY-004 或完整 P8–P10 已完成。

### 背景與輕量設定

Settings 可逐項啟用背景 metadata/catalog 同步（預設關閉；間隔 15 分鐘到 7 天、每輪最多 10 個連線）。研究／推論需明確啟動。Notification 總開關與每條規則皆預設關閉；瀏覽器開著且有通知權限時顯示本機通知，離線的事件保留在通知紀錄，不宣稱 Web Push。

預設 Go soft memory limit 為 192 MiB，可透過 `GOMEMLIMIT` 調整；SQLite cache 2 MiB、暫存排序寫檔，`MODELATLAS_MAX_FLIGHTS=2..4` 可調整並行。Soft limit 不等於行程 RSS hard cap；實際 standalone PID 的 CI footprint 才是具體量測，完整 workload／數字見 [DELIVERY.md](DELIVERY.md)。SQL percentiles 計算完整 history；chart 最近 400 筆、列表 `limit=1..200&offset=N`，對話 history 永久保存。每次推論的原始上下文最多 1024 訊息／32 MiB，超過會提示開新對話，沒有刪除既有內容。

Safe export v2 包含 definitions／aliases／notes／settings／recipes／knowledge／connector，讀取 v1/v2。Restored credentials 禁用需再輸入 Key；研究來源／背景／通知不自動啟用，知識提案需重建 evidence、connector 需重新測試。完整 SQLite 與 master key 要在服務停止後分開安全保存，才能保留全部聊天／research／raw telemetry；設定匯出只作可攜定義。


原生 Gemini 語音使用獨立 `GEMINI_SPEECH` 端點與確認的 AUDIO_OUTPUT，手動選 CURRENT 或 LEGACY，不依模型名稱換協定。支援單聲音與 CURRENT 雙人對話；原生 WAV 或 mono s16le PCM（依回報取樣率包 WAV）均需完整內容驗證，取消和 4 MiB 上限沿用媒體入口。CURRENT wire 依 [Google 官方 TTS 文件](https://ai.google.dev/gemini-api/docs/generate-content/speech-generation) 2026-10-14 查核。沒有持有人 Gemini Key 的真外部語音實測；TLS 和 Browser fixture 僅驗 production transport、播放與下載。

CUSTOM 在 Provider 詳情新增不可變端點，以 `custom_protocol` 的 JSON request 模板＋`response_fields` JSON Pointer 映射回應。允許完整值 `$MODEL`、`$MESSAGES`、`$SYSTEM`、`$TOOLS`、`$MAX_TOKENS`、`$STREAM`；訊息、附件與工具格式沿用 Chat Completions。必須帶 model/messages/max_tokens，實際使用 system/tools 時必須有其 placeholder；最多 16 KiB、8 層，不執行腳本、不插入 auth。串流為 SSE（需明確完成欄位或 done_sentinel），工具 delta 含 index；非串流為一份完整 JSON。修改要新增端點，設定隨 safe backup 匯出，舊對話保持原協定。

Playground 的「對話設定」保存該段系統指示、1..32768 回應 Token 上限和串流選項。手動設定不影響 App Assistant 固定系統指示；工具定義亦逐段保存。

完整 RAM 實測使用 `MODELATLAS_FOOTPRINT_TEST=1 MODELATLAS_FOOTPRINT_OUT=footprint.json go test -run TestStandaloneProcessFootprint -v -count=1 -timeout=3m`：編譯 stripped standalone binary、十萬筆完整歷史統計、4 個 TLS chats、4 路各 31 MiB 完整 context、11,000 訊息／100,008 請求完整匯出、持久研究／65 個 Catalog models、4 個同時 2048×2048 圖片解碼、4 個同時 4 MiB 原生語音結果。以服務 PID 的 VmRSS/VmHWM 記錄每階段，fixture/test runner/browser 不算入服務 RAM；需要可讀取子行程 `/proc` 的 Linux，Work 本機限制不冒充數字。

公開研究來源的位址 guard 在 2026-10-15 依 [IANA IPv4 special-use](https://www.iana.org/assignments/iana-ipv4-special-registry/) 與 [IPv6 special-use](https://www.iana.org/assignments/iana-ipv6-special-registry/) 查核；private/link-local/shared/documentation/benchmark/reserved、不可驗證內嵌目的地的 tunnel 不當成 public web，NAT64 的嵌入 IPv4 重新驗證。原有 DNS pin／HTTPS／不跟 redirect／無 auth 與 proxy 邊界保留。


### 對話與請求紀錄

Playground「對話紀錄」可全文搜尋、置頂、命名、封存／恢復、下載完整 JSON／Markdown。封存保留全部資料；原始附件仍僅在當次服務記憶體，export 明列 metadata-only。長對話可分批讀取／完整匯出，超過32MiB／1024則的送出會要求新對話，從不靜默刪除歷史。

回應串流使用分批追加；往上閱讀會暫停跟隨，按「回到最新」恢復。生成中可以先寫下一則草稿，仍需待目前請求結束後明確傳送。`Ctrl／⌘ + Enter` 傳送、`Ctrl／⌘ + K` 搜尋對話、`Ctrl／⌘ + Shift + O` 新對話、`Esc` 停止目前回應（modal 開啟時先關閉 modal）；IME 組字不觸發。完整行為、參考專案及測試見 [CHAT_UX.md](CHAT_UX.md)。

訊息「請求詳情」與「請求紀錄」顯示當次用量、費率／額度快照與篩選／NDJSON匯出。手動重試最後失敗請求不會重複新增使用者訊息；歷次HTTP仍各有telemetry。Models「手動價格」未勾明確確認時只是manual quote，Catalog zero也只是nominal；不等於Key免費。所有estimate與Provider-reported charge清楚分开。

最新 code `57ef23e` 擴充 standalone workload 實測待機 **25.36 MiB**、全程峰值 **406.36 MiB**，通過服務 ≤512 MiB 目標；原始值與 run/artifact/hash 見 [footprint-expanded.json](verification/20261015/footprint-expanded.json)。預設仍 192 MiB soft Go limit、4 flights，保留全功能／原始歷史；OS 和反向代理另計，尚無目標主機量測。Schema16 不供較舊 binary 直接開啟，更新前一致備份 SQLite 及獨立 master key。

頁面分類、外觀偏好與這輪 UI 交接見 [WORKSPACE_UI.md](WORKSPACE_UI.md)。完整 V1 功能與資料語意保留，最新候選仍需完整六項 CI。

詳情頁分區、來源與時間窗操作、錯誤及晚回應處理，見 [DETAIL_UI.md](DETAIL_UI.md)。


### 工作區連線與恢復

0.8.2候選改善首次載入、離線與讀取失敗的持續提示、手機重新整理入口、保留上次資料／草稿、合併刷新與20秒JSON GET期限；不自動重送寫入／推論。詳[WORKSPACE_RECOVERY.md](WORKSPACE_RECOVERY.md)，實際發布以Releases為準。


### 新對話的確認與恢復

0.8.3候選改善建立成功後的立即切換、失敗時草稿／附件保留、只讀清單恢復及每次明確操作的請求識別，避免HTTP傳輸重送重複寫入。schema仍19，詳[CONVERSATION_CREATION.md](CONVERSATION_CREATION.md)。


### AA 逐頁持久恢復

0.8.5候選將全球研究與設定服務的更新榜單接至可恢復任務：已完成頁面不重抓、每頁最多初次＋2次自動重試、跨重啟／排程預算接續保留上限與等待。AA固定window、研究與排程每日HTTP一次核准；部分榜單、換Key／版本／頁數衝突或過期均不取代last-good。詳[RESEARCH_SCHEDULES.md](RESEARCH_SCHEDULES.md)；原生專用UI、Brave與完整Crawler仍有待辦，實際公開狀態以Releases為準。
