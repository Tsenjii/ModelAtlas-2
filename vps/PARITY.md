# VPS 與 Android 功能核對／交接

日期：2026-10-14。Android 對照主線：`5009e0c8a733f1863adcf9a5b8ef8c450da76d3d`（PR #18，音訊輸入已合併）。VPS 維護分支：`feature/vps-rebuild-20261014`，PR #20。

## 本輪實作及檢查

| Android 已落地範圍 | VPS 真實入口 | 驗證 |
|---|---|---|
| P1 持久化、憑證、scope、取消與用量 | SQLite v5 additive migration；AES-GCM；Basic/Bearer；認證 API；角色、跨 origin/redirect/CSRF 邊界 | Go race、原有 TLS HTTP tests；`parity_test.go` |
| P2 文字／推理串流、多輪、新對話 | `/sessions`、`/messages`、`/infer`、`/infer/cancel`；同一 engine 用於 Playground/Test/Compare | 真 TLS HTTP、阻塞 socket 取消、零 usage、完整 104 則歷史、非串流 TTFT=null |
| Android 已註冊 media transport | `/infer/media` → 圖片生成、multipart edit／audio transcription，獨立 capability 與 telemetry | TLS wire + operation/source scope、non-streaming TTFT=null；result UI 仍同 Android 未驗收 |
| PLAY-004 已合併的圖片／PDF／音訊輸入 | Browser picker → `Attachment.validate` → exact-scope capability → Chat Completions content array | signature/4 MiB；歷史圖片重新驗能力；原始媒體不進 DB/telemetry |
| P3 Quick / Advanced / Review / Batch、多帳戶／Key、自訂認證 | `/onboarding/preview,retry,confirm,import`；`/providers/config`、`/endpoints`；`/credentials,toggle,rotate`；加密的 extra header/query | Review 不先保存 Provider；未選 Catalog 不進 My Models；原子交易；role/vault/rollback tests |
| P4 New API | rc.25 status/notice/pricing/account/token/perf summary/per-model；精確倍率／scope／保留 billing_expr；有界算術 parser | 版本未知拒發；PAT 不進推論；精確 `0.0002/0.0004`；錯誤 schema 不崩潰 |
| P5 Dedicated adapters | map registry：NVIDIA hosted/self-hosted、AMD shared/dedicated、OpenRouter Free、Gemini Free、Groq Free | 各自 catalog／metadata／quota/metrics 來源；無 structured 數值時 Unknown/RequiresLogin，沒有杜撰額度 |
| P6 My Models／Alias／分類／Filter／Detail／Compare | `model_metadata`、`aliases`、exact-scope observations、favorite/watchlist、hidden/archive、Test/Compare 同 engine | 搜尋、分類、hidden 歷史保留；加入後 durable enrichment job；取消排除 uptime |
| P7 LMSpeed | `x-api-key`、固定正主、單頁 50 models、1000/day durable budget、24h freshness、external history | 真 TLS fixture、secret echo 剔除、budget 擋 HTTP、external 不進 local telemetry；UI attribution |
| Android 跨切設定／匯出 | check-in 本地日、通知預設 OFF、設定匯出／原子還原 | 還原只建立 disabled credential refs，需重新輸入 key；不匯入明文憑證 |
| 原 UI 節奏 | teal／深淺色／三分頁／設定右上／48px／native dialog／Provider、Model Detail | 真 Browser onboarding→Review→chat→hide/restore；375/1024px 與深淺色、200% 大字，CI `vps-browser` |

## 已修正舊程式問題

- 舊推論只讀最早 100 則訊息；現在不會靜默省略近期對話，超出有界 request 時明確要求新對話。
- Endpoint profiles 各有 immutable ID／URL／protocol；預設端點在加入模型時固定 URL，後續 config/recipe 不能改寫舊對話的出站路徑。
- Request 發出前持久化 RUNNING；重啟恢復 INTERRUPTED，single-writer lock 防止第二進程重置在途樣本。
- Assistant 以 membership revision 拒絕過期的 confirm/undo；不會撤銷使用者之後重新加入的模型。
- 空流量的 RPM/TPM 保留 Unknown，不發明 confidence 門檻；分位用 Android 相同線性內插；自訂歷史窗口與 current rolling-60s 分開。
- 停止阻塞 SSE 會立即取消 upstream context，不再記為 PARSER；User Cancel 不進 uptime 分母。
- Browser CI 發現建立新對話時，輸入區在 session 尚未建立完成前已可輸入，可能清掉剛輸入的 prompt；新對話現在在完成前鎖住 composer。
- 已知的 token=0 保留為 0；未知仍 null；TTFT 與 TTFB 分開，non-streaming 不捏造 TTFT。
- 分開 reasoning/content、只選 choice index 0、完整 SSE frame/CRLF、明確 terminator；truncated 不算成功。
- ACCOUNT_PAT 不可加入 inference target；custom-only secret 不被偷偷加成 Bearer。
- Group 必須與選定 credential 的明確 group binding 相同；不發明 group header、不切換到 default。
- 極小非零價格與科學記號不會因 fixed-decimal rounding 被當 0／FREE NOW。
- New API malformed perf envelope 會安全失敗，不會對 nil map 寫入而崩潰。
- 舊 Recipe 只寫 DB；現在安全 catalog/chat path 由實際 HTTP caller 使用，禁止跨 origin path。
- DB 為 0600；遷移拒絕較新的 schema；退出時停止 worker／HTTP；installer 更新時確實 restart service。
- go.mod/go.sum 均鎖定，CI 使用 readonly dependency graph。

## 必須如實保留的差別

- Android 主線整體 PLAY-004 仍 PARTIAL：音訊輸出／轉寫結果、tools 互動 UI、生成／編輯圖片結果仍未驗收。此輪不把它們冒充已完成的 Android parity，也不從未合併 audio-output branch 借驗收。
- P8 自動 discovery/AA ingest、P9 完整 LLM Assistant、P10 自主 Reflection 在 Android 也尚未 production ACCEPTED。VPS 已有的 candidate/evidence、preview/confirm/undo、recipe rollback 仍保留；不把它們說成完整 P8–P10。
- 已宣告的 endpoint profile 可能沒有已註冊 transport；API 對每個不支援 operation 獨立拒發，不會將 Responses/Gemini/Anthropic body 當 Chat 發出。此輪 user-facing 多模態輸入走已合併的 Chat flow。
- raw 附件僅在記憶體保留（全服務 32 MiB 上限），DB 只保留安全檔名／種類。重啟後舊含媒體對話不可靜默降成純文字，須建立新對話。
- Dedicated 文件契約／未登入 quota 的 Unknown 與實際 HTTP quota 分開。任何無官方結構資料的數值都不捏造，文件不冒充新做的帳號實測。
- 此 repo 未取得目標 VPS 的 SSH／環境存取，沒有遠端部署證據。CI build 不代表該主機已更新。

## 驗收與下一個 AI

1. 以最新 PR HEAD 的 `VPS Go Build and Integration`（test、amd64、arm64、browser）及 `Android Build and Test`（build、device-tests）同 SHA 結果為準。
2. 本輪本機 Go race/HTTP、vet 通過。受執行環境限制，本機 Chrome 無法啟動；Browser flow 由 Actions 執行，禁止把本機失敗當成功。
3. PR #20 在同 SHA 全部成功以前保持 Draft。不得挪用基線或前一個 SHA 的 CI；先修本片 regression 再續 Android 尚未完成的功能。
4. `vps/README.md` 是啟動／API／部署說明；schema 在 `migrations.go`，不要 destructive migration。不要複製 private Git history／帳號資料。
5. 具體 CI run、commit 與結論記在 PR #20 的最新 body；次輪啟動先重新讀 repo branch HEAD 與該 SHA checks。長期部署維護仍使用 VPS 獨立分支。


本輪 Endpoint / UI 最後回歸：`/endpoints` 支援同 origin 多個 immutable endpoint profiles；同模型不同 profile 有獨立 telemetry／capability／history；default URL 在 membership 建立時固定並於升級時 backfill。設定匯出／還原亦帶 profile 與 binding。Browser 另驗 200% 大字，主模型列表的多項篩選預設收起，顯示 Provider 名稱而不是 UUID。
