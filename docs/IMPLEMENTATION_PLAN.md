> 2026-10-24接續：015H已由9ba2905同來源九項、九份實際產物及16實圖驗收所列範圍（verification/20261024/research-pool-baseline.json）。唯一新片PRIVATE-RESEARCH-015I補明確私人排程／來源確認／排程每日預算／中斷不重播與本機確認歷史；Mac同源引擎取得後端，專用原生UI仍待補。自己的完整本機／九項與實際產物前候選；D-016要求片內驗收後連續推進，公開版本與原需求不變。詳ACTIVE_TASK、vps/RESEARCH_PRIVATE_SCHEDULES.md。

> 2026-10-24：015G已由abcffa0同來源九項及PR #20持久證據驗所列範圍；本輪重查九項仍SUCCESS。唯一新片015H共用研究額度池，正式VPS設定／原子用量與恢復；Mac內建同源引擎取得後端，Mac／Android專用原生呈現仍待補。完整本機與自己的九項／產物前候選；公開版本不變，原功能與願望保留。詳ACTIVE_TASK、RESEARCH_QUOTA_POOLS.md。

> 2026-10-23最新：015F由1d4fc7f同來源九項與九份實際產物／17實圖驗收，精確證據見verification/20261023/research-daily-baseline.json。當前唯一片RESEARCH-RESOURCE-015G，補正式token／USD本機預留、回報結算、未知保留與恢復。完整quota pool／私人排程／其餘願望不減，公開版本不變，新來源自己的九項前候選。

> 2026-10-23最新：015E由ba069a1同來源九項與九份實際產物驗收明列範圍（verification/20261023/research-admission-baseline.json）。當前唯一片RESEARCH-DAILY-015F，補正式每日總／逐憑證推論核准、原子用量與明確恢復；自己的完整九項前候選。Token／USD、共用quota pool、私人排程與完整下一版缺口不減，公開版本不變。

> 2026-10-23最新：015D來源97bcd81同來源九項及全部產物已驗收明列範圍。當前唯一整合片RESEARCH-ADMISSION-015E：本機拒絕不扣額，對話／工具／HTTP與probe reservation／請求紀錄原子提交及恢復；完整token／USD、私人排程與其餘NEXT_RELEASE缺口保留。新來源自己的完整九項前仍候選，公開版本不變。

> 2026-10-22 接續：015B由480bdeac同源九項驗所列範圍；當前唯一整合片CONNECTOR-DISCOVERY-015C，公開來源→有證據與單位／scope的宣告草稿→fixture／實測→共用Review→持久Undo。VPS先補手動JSON落差，原研究剩餘預算與全願望保留；本片完整回歸及九項前仍候選，不變更公開版本。

2026-10-23接續AUTHENTICITY-015D：015C來源294f6ab已九項驗收；本片先用現有正常流量建立版本化證據、使用者確認參考Review／持久Undo與Models／Assistant入口。完整規則見vps/AUTHENTICITY.md；新來源九項前仍候選。完整預算／逐級probe、站點剩餘相容、大資料同步及原生流程仍保留。

同片v2追加：真TLS反例確認Gemini URL串流與Anthropic授權後有效版本必須納入條件，改traffic-fingerprint-v2／local-request-v2；原v1歷史／Undo保留、舊權威不升格。全片21個後端函式與原32 Browser／380圖，最終新來源獨立驗證，公開版本不變。

> 2026-10-22 接續：015A所列範圍已由e384646同源九項驗收，當前唯一整合片RESEARCH-BRAVE-015B；地區／頁數凍結、持久恢復與原子用量接正式研究／排程UI。公開版本仍0.8.5，下一版完整條件不變。015B完整本機與新來源九項前仍候選。

> 2026-10-22 最新使用者要求：下一版須完成幾乎所有功能。依 D-015 進入 NEAR-FULL-015，公開基線0.8.5-preview.1已驗；下一公開版本不再按單一小修發布。完整條件見 NEXT_RELEASE.md，當前唯一片015A研究評估閉環；以下舊版本計畫與歷史保留。

# ModelAtlas Implementation Plan

2026-10-21 唯一新片 VPS-CONVERSATION-RECOVERY-012：0.8.2來源3d7629a九項與實際三平台公開已驗後，補新對話ack即時可用、只讀恢復及HTTP傳輸重送的SQLite原主鍵去重。先驗原失敗，再保留全部測試／歷史與工作量；新HEAD獨立九項、27發布回歸／實套件與公開下載後交付0.8.3預覽，範圍見ACTIVE_TASK。

2026-10-21 唯一新片 VPS-WORKSPACE-RECOVERY-011：從已驗8871九項基線補日常載入／離線恢復、手機刷新與有界讀取。先修原Workspace返回回歸，完整原測試與工作量保留；新HEAD自己的九項及全部三平台套件／公開下載後交付0.8.2預覽。範圍與候選狀態見ACTIVE_TASK／PR #20，原願望不減。

2026-10-21 唯一接續片 VPS-UPDATE-PROVENANCE-010：0.8.1已公開、ffbb697 controller九項成功後，補 CI 重跑的固定 producer／成功時間窗產物選取，防舊失敗QA冒充新成功證據。應用程式版本／既有公開附件不變；新HEAD獨立九項＋27發布回歸及真套件重驗，範圍與即時驗收見ACTIVE_TASK／PR #20。以下為歷史切片。

2026-10-21 唯一接續片 VPS-RESEARCH-RETRY-009：三平台0.8.0已公開、controller3219ecf九項成功後，補公開GET的持久延後重試與可讀紀錄；同SHA九項及完整發布再交付0.8.1預覽。範圍／後續缺口見ACTIVE_TASK與vps/RESEARCH_SCHEDULES.md，原願望與測試不減。

最新 D-014／VPS-RELEASE-008：VPS 第一、Mac 第二、Android 第三。先統一三平台來源／版本／完整發布、更新首頁與 VPS 下載更新入口；九項同 SHA 及完整性 gate 後才公開預覽版。原願望與平台優點保留，詳 ACTIVE_TASK、TRACEABILITY、RELEASES.md。以下優先序為歷史。

2026-10-20 前片使用者優先序：先 VPS-OPS-006（可日常部署與後續安全更新），caller／驗收／未部署界線見 ACTIVE_TASK、TRACEABILITY 及 vps/UPDATE.md。原 SYNC-001 剩餘原生對應／分塊、研究排程、未知站點、可量測改進、authenticity 與實機完整驗收保留；不因調整順序刪需求。


2026-10-20 接續：VPS-OPS-006 code `034e775` 六項成功、真 systemd 及兩架構套件已驗；唯一新整合片為 VPS-RESEARCH-SCHEDULE-007，先完成公開資料排程／上限／重啟／正式 UI，完整研究及各平台缺口繼續保留。詳見 ACTIVE_TASK、TRACEABILITY、`vps/RESEARCH_SCHEDULES.md`，未取得目標主機不冒充遠端部署。

## 產品契約
依使用者 Astra Final Structured v2 規格與追加要求開發。完整 v1 以十階段累積交付；本次先交付第一條可測試 vertical slice，不把 schema/interface 當作完整功能。

不可簡化的要求：
- Model Classification = primaryCategory + capabilities[] + modalities[] + endpointTypes[]，不得單一 modelType。
- Quick Add、Advanced Add 為兩個明確 flow。Quick Add 自動探測後 Review；Advanced Add 完整支援 Account、Credentials、Custom Header/Query、EndpointProfiles、Group、Manual Model ID、Check-in。首條 slice 先提供安全手動連線，完整 onboarding 為 Phase 3，不冒稱已完成。
- OpenRouterFreeAdapter 主要產品資料聚焦經價格驗證的 Free Models；不能僅依名稱判斷或把 Paid Catalog 塞滿 Models UI。
- FREE NOW 仍走完整 inference/usage/performance pipeline；零價格沒有 telemetry bypass。
- Provider Health 包含 HEALTHY/DEGRADED/UNSTABLE/OFFLINE/UNKNOWN，Official 與 Observed 分離。v1 包含 Daily Check-in、待簽到、CheckinRecord，按裝置本地日期。
- Crawler 完整保存 DISCOVERED/VERIFYING/VERIFIED/HIGH_VALUE_GREY_ZONE/STALE/FAILED/REJECTED、淘汰/降級理由與證據，不只保存推薦結果。
- PricingScope superset = Provider × Account/Credential × Model × Group × Endpoint × Operation × Time。不同 Key、Endpoint、Operation 不覆蓋彼此。
- Unknown 不猜；一次 sync failure 不刪模型、不清空 last-good facts；一次性贈金不是 recurring free。
- 原始 Key/PAT 不進 LLM、repo、log、telemetry；UI 安全輸入後只傳 safeCredentialId。

### 分類與 quota 的精確值
- primaryCategory：CHAT_REASONING / CODING / IMAGE / AUDIO / EMBEDDING / RERANK / SAFETY / OTHER。
- capabilities：REASONING / CODING / TOOL_CALLING / FUNCTION_CALLING / STRUCTURED_OUTPUT / JSON_MODE / VISION / FILES / IMAGE_GENERATION / IMAGE_EDITING / AUDIO_INPUT / AUDIO_OUTPUT / WEB_SEARCH / COMPUTER_USE / LONG_CONTEXT / EMBEDDING / RERANK / SAFETY / OTHER。
- modalities：TEXT_INPUT / TEXT_OUTPUT / IMAGE_INPUT / IMAGE_OUTPUT / AUDIO_INPUT / AUDIO_OUTPUT / FILE_INPUT。
- endpointTypes：CHAT_COMPLETIONS / RESPONSES / ANTHROPIC_MESSAGES / GEMINI_NATIVE / IMAGES_GENERATION / IMAGES_EDIT / AUDIO / EMBEDDINGS / RERANK / CUSTOM。
- QuotaState：QUOTA_AVAILABLE / QUOTA_LOW / QUOTA_EXHAUSTED / RATE_LIMIT_ONLY / PUBLIC_POOL / UNLIMITED_CLAIMED / NO_KNOWN_CONSUMABLE_QUOTA / UNKNOWN。

## 現況與邊界
工作目錄原為空；無既有程式碼/DB 可沿用。先用 `:core`（純 JVM Domain/Protocol/Telemetry，可快速單測）與 `:app`（Android Compose/Room/Keystore/UI），package 分層，避免過多 Gradle modules。

`core/domain`：scopes、classification、price/quota、health、candidate、evidence。
`core/network`：OpenAI-compatible SSE、protocol types、strict endpoint policy。
`core/inference`：統一執行、取消、usage、TTFT/TTFB/TPS、completion persistence。
`app/data`：Room、repository、snapshot persistence。
`app/security`：Android Keystore-backed encrypted credential vault。
`app/ui`：Material 3，Playground/Providers/Models 三個 bottom destinations，Settings 右上。

## 本次 vertical slice
1. 建置環境驗證（JDK/SDK/Gradle/aapt2，PRoot 可執行記憶體限制）。
2. Domain/contracts：完整 pricing scope、多維分類、health/checkin、candidate states。
3. HTTPS Provider/Endpoint 與安全 credential input；手動 Model ID，不假定 catalog 支援。
4. Streaming Chat Completions；role-only chunk 不算 TTFT；reasoning/content 首個生成內容才算。取消中止 socket；不盲目重試消耗 quota。
5. 每次 inference 不論價格/成功/失敗/取消都有 Room telemetry；價格及 quota 無官方資料時顯示 Unknown。
6. 介面顯示 streaming、取消、錯誤與最新本地指標；聊天內容只保留記憶體，不混入 performance persistence。
7. Build + unit tests + 可行的裝置驗證。MockWebServer 只用測試，不當產品資料；真實 Provider 驗證另行標示。

## 後續十階段
1 Foundation：domain、Room、secrets、network、provenance、telemetry。
2 Playground：本次先文字串流；後續 vision/files/tool calls/images 等以 capability matrix 逐項交付。
3 Providers：Quick/Advanced onboarding、Review、多 Account/Key、batch import、custom auth、Generic Adapter。
4 New API：PAT/account 與 inference credential 分離，version-aware endpoints、安全 pricing expression parser。
5 Dedicated：NVIDIA、AMD、OpenRouter Free、Gemini Free、Groq Free。
6 Models：canonical mapping/alias、多維分類、search/filters/manual offering/Compare Live。
7 LMSpeed：enrichment/discovery，external 與 local 分離。
8 Crawler：完整 funnel、evidence、budget、checkpoint、local execution backend。
9 Assistant：structured tools、safe credential refs、Undo/action log、destructive confirmation。
10 Reflection：versioned/reversible recipes、source/query weights；不可改 system prompt/source code/security policy。

## 目前進度（快照，2026-10-06）

> live 進度單一真相是 `docs/ACTIVE_TASK.md`；acceptance 單一真相是 `docs/TRACEABILITY.md`。本節只是快照，與上述檔案衝突時以後者為準。

- 最新已驗 full-green baseline：`ca7cb03` ＋ run `37549586195`（build＋device-tests SUCCESS）。HEAD `7f445c9` 為其上 docs-only。
- MODE：CORRECTNESS_REPAIR；**FEATURE FREEZE OFF**；P8 production STOP（開工門檻見 `ACTIVE_TASK`）。
- Phase 1–7（wave-1 repair）：PROV-001/002/010、MODEL-005/008/009、LMS-001…005 等已 ACCEPTED，不得重做。
- 最新 ACCEPTED slice：CR-011 `c8a59f4`／`37514282914`（quota-slice head namespace Room v15）、CR-012 `ca7cb03`／`37549586195`（Tier-1 test KDoc 引用修復，comment-only）。下一唯一 production slice：**D028-F1**（auto metadata 補抓；ruling 已定：D2-YES charge at lock entry、D3-YES budgets 獨立、passportReader port、slice-union；見 `DECISIONS` D-2026-10-06-030）。
- Wave-2 cross-cutting（X-011…X-024，見 `BACKLOG` §橫切稽核草案）：X-016 rollup、X-017 retention 抽象（永久保留＝D-2026-09-30-006）、X-018 事件層（Room v10）、X-011 排程骨架、X-024 權限、X-019 NoticeFetcher、X-015 backup 已有 code 落地，**implemented-under-test**；X-ID acceptance 逐項以 `TRACEABILITY.md` 為準，不得整批宣稱完成。
- PLAY-004：PARTIAL；media transport 已含入綠基線，UI／VM user flow 未驗收，不得升 ACCEPTED。
- 通知架構（X-013/X-014/X-024）依 D-2026-09-30-005：預設 OFF＋Settings 可調。

## Room 與 Migration 路徑
首版持久化 Provider、Account、Credential metadata、EndpointProfile、ModelOffering、PriceSnapshot、QuotaSnapshot、PerformanceSample、Evidence、CheckinRecord。
隨階段增加 CanonicalModel/分類關聯、CredentialAvailability、PriceEvent/QuotaPool、ProviderNotice/Event、CatalogSnapshot/SyncRun、CandidateProvider/ResearchRun/ResearchTask/ProviderRecipe/VerificationOutcome、Assistant ActionLog。
schema export 開啟；不使用 destructive migration；pricing 用精確 decimal 字串；snapshot 有完整 scope、source、時間與版本。telemetry／evidence／snapshot／history／research 資料一律**永久保留**，不做定時刪除（`DECISIONS` D-2026-09-30-006）；RetentionPolicy 僅保留 rollup 抽象（聚合查詢效能用），purge 路徑不得啟用於產品資料；external cache TTL 只作為重抓新鮮度觸發，不得刪除已存觀測歷史。

## 驗收矩陣
- 免費與付費請求都被 telemetry sink 保存；取消/401/429/timeout/parser failure 不遺失。
- FREE 判定不依 model id；必要價格未知維持 UNKNOWN；cache costs extra 獨立呈現。
- Non-streaming TTFT=null；role chunk 非首 token；沒有可靠 token usage 不假造 TPS。
- Provider/Account/Credential/Group/Endpoint/Operation 價格隔離。
- 無 secret 出現在例外/UI error/DB metadata/HTTP logs；HTTP redirect 不轉送憑證。
- UI 只呈現真資料或明示 Unknown/尚無資料；不塞 mock catalog。
- Build/Test 結果與未驗證項目寫入交付紀錄。

## 風險
Security：Keystore 與裝置綁定，備份不包含 secret；endpoint HTTPS/origin binding、防 redirect 洩漏；外部內容非工具指令。
Performance：Room 不逐 token 寫入；monotonic clock 計時；同步 single-flight；有限探測預算；WorkManager 可中斷恢復。
Migration：scope 不能縮減；canonical mapping 可修正；不同来源不無聲覆蓋。
API：各家公開契約需實際查證；名稱/free 標籤/第三方 benchmark 不當 ground truth。

## 未來僅留契約
Routing/Fallback/Key auto rotation/HTTP gateway/Local API server/完整 remote crawler，現在不實作服務或 UI。
通知規則**預設 OFF**（不打擾優先），Settings 右上提供通知總開關＋逐規則開關，使用者可自行開啟（`DECISIONS` D-2026-09-30-005）；程式碼不得把預設塌縮成開啟，測試斷言預設 OFF。telemetry／evidence 資料**永久保留**、不做定時刪除（`DECISIONS` D-2026-09-30-006），見 §Room 與 Migration 路徑。

## 委派
主 session 負責架構、secret boundary、inference pipeline、整合與驗收。
獨立 OpenCode sessions 處理 Domain/測試與建置環境；優先使用使用者指定免費模型，Muse Spark effort=xhigh，其餘嘗試 max。絕不傳遞使用者 API key。
