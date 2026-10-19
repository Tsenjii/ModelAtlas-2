# VPS 最新交付入口

VPS-MAC-ALIGN-001 的 Mac 對齊與保留範圍見 [MAC_ALIGNMENT.md](MAC_ALIGNMENT.md)，更新／部署見 [UPDATE.md](UPDATE.md)。最新 commit、六項同 SHA 驗收、實際 Browser 圖及新 RAM 結果以 PR #20 與交付套件 VERSION.json／VERIFICATION.json 為準。

以下保留 2026-10-15 的歷史驗收，不能套用為最新 HEAD 的結果。

# ModelAtlas VPS：完整 V1、對話與請求管理交付

2026-10-15（台北）。最新功能 code 基線 `57ef23e96c335413003b497b06cd99b7b8838b6e`；[PR #20](https://github.com/Tsenjii/ModelAtlas-2/pull/20)。完整 V1 之後，已補齊對話管理、安全 Markdown、請求收據、明確重試、完整匯出，以及價格／免費判定修復。Android source 維持 main `5009e0c8`，VPS 的成果不改寫 Android PLAY-004／P8–P10 的驗收狀態。

下列測試與實測對應上述 code SHA；末尾交付文件提交的最新 HEAD 另須通過六項 gate，以 PR checks/body 為準。沒有合併或部署到使用者主機。

## 功能範圍

| 範圍 | 實際入口 |
|---|---|
| 對話 | Chat Completions／Responses／Anthropic／Gemini／宣告式 CUSTOM；串流／非串流、多輪與工具接續 |
| 對話管理 | 全文搜尋、置頂、命名、可恢復封存、較早訊息分頁、完整 JSON／Markdown 下載；過期編輯與生成中變更有防護 |
| 回應呈現 | 安全 DOM Markdown、程式碼與表格、程式碼／原訊息複製；HTML／scripts／危險連結維持純文字，遠端圖片不自動下載 |
| 請求管理 | 訊息連到當次請求，成功／失敗／停止／重啟均保留；按對話或精確模型 scope 查看、篩選、分頁、完整 NDJSON 匯出；明確重試最後失敗請求不重複 user input |
| 用量與費用 | Token／cache／reasoning、TTFT／耗時、immutable 當次費率與額度來源／時間／scope；站方實際回報、nominal 估算、已確認 Key 費率估算分開，未知資料不填零 |
| 多模態 | 圖片／PDF／音訊輸入，圖片生成／編輯，轉寫，OpenAI 語音及 Gemini CURRENT／LEGACY／雙人語音，播放與下載，embedding／rerank |
| 對話設定 | 每段 Playground 的 system、Token 上限、串流、工具定義持久保存；Assistant 固定 system 獨立 |
| 管理 | 多帳戶／Key／不可變網址與端點，onboarding Review／confirm／batch，safe refs、Key rotation／rollback、Group／PAT epoch 隔離 |
| Models | Catalog 與 My Models 分離，alias、notes／tags、filter／weighted ranking、favorite／watchlist、hide／archive／batch／Undo、compare |
| Intelligence | local／provider／external 分開，nominal／effective price、quota／balance／usage／AA／LMSpeed 來源與歷史；Catalog zero 不推成 Key 免費 |
| 研究 | durable queue／HTTP 與 probe 預算／pause／resume／cancel／來源與 8 語言／漏斗／manual review／最新 AA 動態 top-4 |
| Assistant／Reflection | 17 個固定安全工具、真 selected-model caller、共享 Review／confirm／Undo、重啟日誌；成效報告、知識版本、source snapshots、有限可逆學習及實測的唯讀 connector |
| 背景與保存 | 真 metadata/catalog sync，全部通知預設 OFF；全量原始歷史保留，portable config backup v2 與完整 SQLite 備份，失敗不抹 last-good |
| 輕量執行 | 一個 Go binary＋SQLite，沒有常駐 JVM／Node／Browser；SQL 全量統計、分批串流匯出、有界 HTTP／media／context／2..4 flights |

## 同 SHA 測試與 review

以下六項均在 `57ef23e` SUCCESS。

| Workflow | Job | Job ID |
|---|---|---:|
| [VPS 38631190515](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38631190515) | Go race／TLS／vet／runtime smoke／擴充 standalone RAM | 116082947267 |
| 同上 | Linux AMD64 | 116082947725 |
| 同上 | Linux ARM64 | 116082947595 |
| 同上 | 真 Browser 九個 production workflows | 116082947583 |
| [Android 38631190578](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38631190578) | build | 116083047901 |
| 同上 | device-tests | 116083048486 |

Browser 驗過真播放、下載 WAV／PNG／文字／JSON／Markdown／NDJSON 的 bytes、工具接續、onboarding／confirm／Undo、研究／知識版本／背景設定、多網址／CUSTOM、對話搜尋／命名／置頂／封存／恢復、clipboard、429→明確重試→成功且僅一則 user input，以及 375／1024px、深淺色、200% 字級與取消。已檢視本輪六張對話／收據／列表截圖；本機 Chromium 限制沒有被當成成功。

本輪自行 diff review 重點為 authentication／scope／credential epoch、不可變費率、實際扣款與估算分離、Unknown／zero、重試 ancestry、匯出完整性與 DB connection 釋放、secrets 與 DOM 安全；沒有獨立外部 reviewer。測試另覆蓋 1,100 則完整對話、501+ 筆請求、匯出途中新增／狀態變更、輪替憑證／價格中途變更、快取計費缺失、raw attachment／header-name secrets。

初次擴充 workload 的 11,000 訊息匯出暴露 OFFSET 重掃逾時，已用 schema16 的 session sequence index 與 rowid cursor 修復，保留完整工作量與原有 timeout。新增收據按鈕後，clipboard 測試改用精確「複製訊息」角色；保留整段文字相等斷言。沒有刪除測試或降低預期。

精確 CI／artifact／已檢視畫面索引：[post-v1-checks.json](verification/20261015/post-v1-checks.json)。Browser artifact `11925724015`／SHA256 `7067477b6d9489978eba1ded1d2cc25b1320f9980e8fcdd28032295a8a41a4ca`。

## RAM：擴充後的獨立服務行程

使用編譯後 stripped binary，以服務 PID 的 `/proc` VmRSS／VmHWM 實測。測試 runner、TLS fixture server、瀏覽器及作業系統不算在下表；SQLite 檔案大小是磁碟用量。

工作負載保留原來十萬筆 raw samples／完整 SQL 統計、4 個 TLS chats、真研究／65 個 Catalog 模型、4 個同時 2048×2048 圖片解碼、4 個同時 4 MiB 原生 PCM→WAV；另增加 **4 路各 31 MiB 完整對話 context**（四份均由 TLS upstream 完整讀取後才一起完成）、**11,000 則訊息完整匯出**及 **100,008 筆請求完整匯出**。沒有縮短歷史、減少並行或刪掉功能。

| 階段 | 階段完成時 RSS |
|---|---:|
| 啟動待機 | 25.36 MiB |
| 十萬筆保留歷史、完整統計、4 個 TLS chats | 30.38 MiB |
| 4 路各 31 MiB 完整 context | 206.14 MiB |
| 完整對話與請求匯出後 | 34.73 MiB |
| 持久研究與 65 個 Catalog 模型 | 34.53 MiB |
| 4 個同時 2048×2048 圖片解碼後 | 97.51 MiB |
| 4 個同時 4 MiB 原生語音後 | 166.36 MiB |
| **整輪 VmHWM 峰值** | **406.36 MiB** |

本次 binary **13,570,210 bytes（12.94 MiB）**，SQLite **155,455,488 bytes（148.25 MiB）**。原始值與 code/run/job/artifact/hash：[footprint-expanded.json](verification/20261015/footprint-expanded.json)。VmHWM 是整輪累積最高值，不能把後續每個階段相同的 peak 欄位解讀成各階段各自峰值。

指定 workload 的服務 RSS 通過 **512 MiB** 目標；預設仍為 192 MiB Go **soft** memory limit 與 4 flights。Soft limit 不是 RSS hard cap。整台 VPS 還要另計 OS、反向代理與其他程序；尚無目標主機量測。512 MiB 整機可視實測調到 `MODELATLAS_MAX_FLIGHTS=2` 控制並行，不能直接把本次服務量測當成整機保證。

舊 [footprint.json](verification/20261015/footprint.json) 的 155 MiB 對應較小工作負載及 `ce2ca91`，保留作歷史證據，不能與本輪擴充 profile 宣稱同條件改善比例。全量 raw history 永久保留；只清理暫存匯出索引，不清原始資料。

## Kilo 真實測試

[Live run 38629907160](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38629907160)，code `a39fa2a9d6fcb4cb6f3fc5ca3b4cec78d6e0e6d2`、job `116078568504`，**20.06 秒 SUCCESS**。這次包含新收據 engine；其後 `57ef23e` 只修匯出索引與 clipboard 精確選取，不冒充另一次 Kilo 呼叫。

匿名 `nvidia/nemotron-3.5-lightning:free` 經真 App Assistant tools 執行 Provider 研究：6 次 Kilo HTTP、7 次 public HTTP；7 tasks 中 3 SUCCESS，3 candidates、4 evidence。

| 來源 | 實際結果 | 判讀 |
|---|---|---|
| Groq 官方 rate-limits | HTTP 200 | 取得公開資料，保留研究線索 |
| OpenRouter 官方 pricing | HTTP 200 | 取得公開資料，保留研究線索 |
| Cerebras pricing 舊入口 | HTTP 307，不跟隨 | 保留 FAILED／Unknown 線索 |

Groq 的一份公開 JS 資料亦成功讀取，僅當文字資料，不執行網站腳本。沒有註冊帳號／取得使用者 Key／購買服務。候選數不是 eligible 數；普通門檻、recurring-free、台灣、強度與 model identity 尚需各自證據。LLM 回答和回報 model 名稱不作身分證明。較早真 Kilo conversation／native tool／Assistant membership Review→confirm 測試亦已通過；外部語音沒有持有人 Gemini Key，僅聲稱 TLS＋Browser 的真 caller、播放與下載驗證。

精簡實測紀錄：[kilo-followup.json](verification/20261015/kilo-followup.json)。完整外部文字不複製進 Git；raw artifact `11926167081`／SHA256 `21e604fe54999ad5305fe6e1edeeb8ee9b0e82dc3b9ad31ce4b2aeeae5bead14`。舊 [kilo-summary.json](verification/20261015/kilo-summary.json) 保留。正式 deterministic CI 不耗外部服務，opt-in marker 已關閉。

## 安裝、升級與備份

從最終 PR HEAD 的 VPS Actions `modelatlas-vps-linux-amd64` 或 `modelatlas-vps-linux-arm64` artifact 下載單一 binary，依 [README.md](README.md) 建立私密 env，執行 `deploy/install.sh`，以 SSH tunnel 或已認證 HTTPS proxy 連線。目前未取得目標 VPS 存取，尚未遠端部署。

目前 **schema16**；更新前停止服務並一致備份 SQLite，另外安全保存 master key。較舊 binary 不直接開啟新版 schema；需要回退時恢復對應備份與舊 binary。

Safe export 是不含 Key／PAT／聊天／telemetry raw 的 portable **設定**。完整資料需 SQLite backup API 或停止服務後一致備份 SQLite 並分開保存 master key；憑證欄位使用 AES-GCM，完整資料庫不是全庫加密儲存。Raw history 永久保留，無自動 purge。原始附件只在當次服務記憶體，重啟後需新對話重新上傳；metadata／history／telemetry 仍保留。對話 JSON／Markdown 及請求 NDJSON 匯出不包含原始附件，也不能取代完整資料庫備份。
