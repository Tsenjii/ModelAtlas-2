# ModelAtlas VPS V1 交付與 RAM 實測

2026-10-15（台北）。正式功能 code 基線 `ce2ca91a8ff21606bcc335c76d3e6dd47bc0ada8`；[PR #20](https://github.com/Tsenjii/ModelAtlas-2/pull/20)。VPS 完整 V1 範圍已實作並通過測試，Android source 維持 main `5009e0c8`，不把 VPS 的進度當成 Android P8–P10 驗收。本文件提交另包含品牌圖示在 200% 字級的修整、靜態截圖停用 transition；最新文件 HEAD 的六項 gate 以 PR checks/body 為準，不借用下表 code 基線的綠燈。

## 功能範圍

| 範圍 | 實際入口 |
|---|---|
| 對話 | Chat Completions／Responses／Anthropic／Gemini／宣告式 CUSTOM；串流／非串流、多輪及工具接續 |
| 多模態 | 圖片／PDF／音訊輸入，圖片生成／編輯，轉寫，OpenAI 語音及 Gemini CURRENT／LEGACY／雙人語音，播放與下載，embedding／rerank |
| 對話設定 | 每段 Playground 的 system、Token 上限、串流、工具定義持久保存；Assistant 固定 system 獨立 |
| 管理 | 多帳戶／Key／不可變網址與端點，onboarding Review／confirm／batch，safe refs、Key rotation／rollback、Group／PAT epoch 隔離 |
| Models | Catalog 與 My Models 分離，alias、notes／tags、filter／weighted ranking、favorite／watchlist、hide／archive／batch／Undo、compare |
| Intelligence | local／provider／external 分開，nominal／effective price、quota／balance／usage／AA／LMSpeed 來源與歷史；Unknown 不當成 0 或 Free |
| 研究 | durable queue／HTTP 與 probe 預算／pause／resume／cancel／來源與 8 語言／漏斗／manual review／最新 AA 動態 top-4 |
| Assistant／Reflection | 17 個固定安全工具、真 selected-model caller、共享 Review／confirm／Undo、重啟日誌；成效報告、知識版本、source snapshots、有限可逆學習及實測的唯讀 connector |
| 背景與保存 | 真 metadata/catalog sync，全部通知預設 OFF；全量原始歷史保留，portable config backup v2 與完整 SQLite 備份，失敗不抹 last-good |
| 輕量執行 | 一個 Go binary＋SQLite，沒有常駐 JVM／Node／Browser；SQL 全量統計，bounded paging／HTTP／media／context／2..4 flights |

## 同 SHA 測試

以下六項均在上述 code SHA SUCCESS。

| Workflow | Job | Job ID |
|---|---|---:|
| [VPS 38612813568](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38612813568) | Go race／TLS／vet／runtime smoke／standalone RAM | 116020220644 |
| 同上 | Linux AMD64 | 116020220452 |
| 同上 | Linux ARM64 | 116020220414 |
| 同上 | 真 Browser 七個 production workflows | 116020220318 |
| [Android 38612813557](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38612813557) | build | 116020220795 |
| 同上 | device-tests | 116020219982 |

Browser 驗過真播放、下載 WAV/PNG/文字/JSON 的 bytes、工具結果接續、onboarding/confirm/Undo、研究/版本/背景設定、多網址與自訂協定，以及 375／1024px、深淺色／200%／取消；不是只驗畫面 markup。本機 Chromium 限制未被當成成功。Browser artifact `11919285257`；精確 digest 與檔案在上述 run。

本輪另修：研究 Catalog 只依同一 auth/HTTP snapshot 的全部回傳模型建立證據（65 模型回歸，不借其他 Key/舊 Catalog，不截成 50）；manual chat/media 亦阻擋所有已存憑證與舊版本進 request；CUSTOM 經 onboarding/restore 同樣驗定義與 secrets；WAV 驗 codec/bits/frame/byteRate；公開來源防 reserved/embedded-private 位址；反思暫時保存失敗可 local-only 重建，不自動 activate 知識。

## RAM：獨立服務行程

使用編譯後的 stripped binary，以服務 PID 的 `/proc` VmRSS/VmHWM 實測。測試 runner、TLS fixture server、瀏覽器與作業系統不算在下表。

| 工作負載 | RSS／峰值 |
|---|---:|
| 啟動待機 | 25.48 MiB |
| 十萬筆保留歷史、完整統計、4 個 TLS chats | 28.65 MiB |
| 持久研究與 65 個 Catalog 模型 | 28.87 MiB |
| 4 個同時 2048×2048 圖片解碼 | 93.10 MiB |
| 4 個同時 4 MiB 原生 PCM→WAV 結果 | 當下 117.00 MiB；全程峰值 **155.00 MiB** |

Binary 13,439,138 bytes（12.82 MiB），本輪 SQLite 20,852,736 bytes。詳細原始值與 run/artifact/hash：[footprint.json](verification/20261015/footprint.json)。前一版相同 workload 峰值 158.46 MiB；新版移除大型 decoder 副本，沒有減少請求數、檔案上限、像素或原始資料。量測可能因 GC／系統配置變動，不能以單次差值聲稱固定改善比例。

可先為服務預留 256 MiB，再另加 OS、反向代理及其他程序。預設 Go **soft** memory limit 192 MiB；它不是所有情況的 hard RSS 保證。`MODELATLAS_MAX_FLIGHTS=2..4` 可以限制同時工作量，不改功能。更大的上下文、Key 歷史或不同媒體組合需在目標主機用相同 profile 再量測。

## Kilo 真實測試

[Live run 38612880443](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38612880443)，同 code SHA、job `116020440806`，41.19 秒 SUCCESS。匿名 `nvidia/nemotron-3.5-lightning:free` 經真 App Assistant tools 執行 Provider 研究：6 次 Kilo HTTP、7 次 public HTTP；7 tasks 中 3 SUCCESS，3 candidates、4 evidence。

| 來源 | 實際結果 | 判讀 |
|---|---|---|
| Groq 官方 rate-limits | HTTP 200 | 取得公開資料，保留研究線索 |
| OpenRouter 官方 pricing | HTTP 200 | 取得公開資料，保留研究線索 |
| Cerebras pricing 舊入口 | HTTP 307，不跟隨 | 保留 FAILED／Unknown 線索；沒有冒充成功 |

沒有註冊帳號／取得使用者 Key／購買服務。候選數不是 eligible 數；普通門檻、recurring-free、台灣、強度與 model identity 尚需各自證據。LLM 回答和回報 model 名稱不作身分證明。較早真 Kilo conversation/native tool/Assistant membership Review→confirm 測試亦已通過；外部語音沒有持有人 Gemini Key，僅聲稱 TLS＋Browser 的真 caller、播放與下載驗證。

精簡實測紀錄：[kilo-summary.json](verification/20261015/kilo-summary.json)。完整外部文字不複製進 Git；raw artifact `11919171632` 的 digest 在 summary。正式 deterministic CI 不耗外部服務，opt-in marker 已還原。

## 安裝與備份

從上述 VPS Actions 的 `modelatlas-vps-linux-amd64` 或 `modelatlas-vps-linux-arm64` artifact 下載單一 binary，依 [README.md](README.md) 建立私密 env，執行 `deploy/install.sh`，以 SSH tunnel 或已認證 HTTPS proxy 連線。二進位檔不能以普通 CI 的編譯成功冒充使用者主機已部署；目前未取得目標 VPS 存取。

Safe export 是不含 Key/PAT/聊天/telemetry raw 的 portable **設定**。完整資料需停止服務後一致備份 SQLite（或使用 SQLite backup API）並分開保存 master key；憑證欄位使用 AES-GCM，完整資料庫不是全庫加密儲存。Raw history 永久保留；無自動 purge。含媒體舊對話的 raw 附件只在記憶體，服務重啟後需新對話重新上傳，metadata/history/telemetry 仍保留。
