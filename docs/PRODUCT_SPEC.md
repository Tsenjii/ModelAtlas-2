# ModelAtlas Authoritative Product Spec

> 本文件由使用者提供的「Astra / OpenCode 最終整合 Prompt — Final Structured v2」整理而來，作為 repo 內可直接閱讀的產品母規格。
> 原始母規格本身仍屬高階完整大綱；遺漏細節不得被解讀為「不需要做」。後續使用者明確決策記在 `docs/DECISIONS.md`，發生衝突時由較新的決策覆蓋本文件。
> **Provider / Model Intelligence 專門規格：`docs/PROVIDER_MODEL_INTELLIGENCE_CONTRACT.md`。在 Provider / Model / Performance / Pricing / Quota / FREE / Intelligence UI / Assistant Research 範圍內，該 contract 的較新【已拍板】規則取代本文件中相衝突的舊描述。**
> **2026-10-19 願望清單補充：** 原始 [`ASTRA_MASTER_SPEC.md`](ASTRA_MASTER_SPEC.md) Part V 新增 WISH-CRAWL-001／002、WISH-REFL-001、WISH-AUTH-001、WISH-ACCEPT-001，細化持續發現排程、未知站點 Connector、可量測研究改進閉環、模型真實性及真站／跨平台／手機驗收。原有需求全部保留；WISH-CRAWL-001 為 PARTIAL（VPS 公開排程第一階段 code b1e0c66 已驗），其餘四項為 OPEN（待完成／待驗收），實際狀態追蹤見 `TRACEABILITY.md`，不因文件新增而視為已交付。

## 0. 成功定義

ModelAtlas 不是一般 API Key Manager、一般聊天 App、模型列表或一次性爬蟲。

產品定位：

**全球免費強模型 API Intelligence + LLM Provider Manager + Playground + App Control Assistant**

完整 v1 必須累積交付：
1. Foundation
2. Playground
3. Providers / Onboarding
4. New API
5. Dedicated Adapters
6. Models Intelligence
7. LMSpeed
8. Crawler Intelligence
9. App Assistant
10. Self-improving Research

不得因困難而刪減後半段功能，也不得以 schema / enum / mock / fixture 冒充產品 flow 已完成。

## 1. 永久產品鐵律

- Unknown != 0。
- Unknown != Free。
- FREE_NOW 仍走完整 inference + telemetry。
- 一次 sync failure 不刪模型、不清空 last-good facts、不宣告 removed。
- 一次性 Credit / Welcome Bonus 不等於 recurring free。
- model id / group 名稱含 free 不代表 FREE_NOW。
- 第三方 benchmark 不得冒充 local telemetry。
- raw API key / PAT 不進 LLM prompt、repo、log、telemetry；LLM 只看到 safe credential refs。
- 不同 Provider / Account / Credential / Model / Group / Endpoint / Operation / Time 不互相覆蓋。
- manual / official / observed / external 不互相冒充。
- 重要事實必須有 Evidence / provenance。
- 未驗證資料不得標成 VERIFIED。
- 現有 repo 先檢查 HEAD 與綠 CI，不因舊文件重做。
- Catalog != My Models；完整 catalog 可以保存/研究，但主 Models UI 只放使用者/Assistant/Explore 明確加入的模型。
- My Uptime / Provider Success / External Availability 分離；User Cancel 不算 My Uptime。
- nominal price / effective charge / availability / quota / free mechanism / recurring-free semantics 分離。
- Hidden / Archived 不刪歷史；永久 purge 必須是明確 destructive action。

## 2. 技術與 UX

- Native Android / Kotlin / Jetpack Compose / Material 3。
- Coroutines / Flow / Room / WorkManager。
- Android Keystore-backed secret protection。
- Repository / Service / Adapter 分層。
- 目標裝置包含手機：避免無意義 benchmark、過度發熱、高頻 DB writes、無效 recomposition。
- 正常 Playground traffic 優先成為 telemetry；不要有新鮮使用資料時又額外燒 quota。
- 核心 UX：**Collect everything, personalize display.**

Bottom Navigation 固定：
1. Playground
2. Providers
3. Models

Settings 放右上角，不是第四個 tab。

## 3. Playground

支援架構必須能逐步承載：
- Chat / Reasoning / Coding / Streaming
- Vision / Files / Tool calls
- Image generation / editing
- Audio / 未來 modalities

快速選擇：
- Provider
- Account / Credential
- Group（需要時）
- Model

每次 inference 統一進 `InferenceEngine` / telemetry，至少保存：
- providerId / accountId / safeCredentialId
- canonicalModelId / providerModelId
- group / endpoint / operation / telemetry source
- requestStartedAt / firstTokenAt / completedAt
- streaming
- input/output/reasoning/cache/total tokens
- HTTP status / error category / 429 / retry count
- responseModelId
- TTFT / TTFB / TPS / total latency
- relevant rate-limit headers
- quota snapshot before/after
- pricing snapshot
- PriceState / QuotaState
- actual / estimated cost

Non-streaming TTFT 必須為 unavailable；不可拿 total latency 冒充。

## 4. Providers

Provider card 可呈現：
- name / short description
- Health
- Account / Credential count
- meaningful Balance / Quota summary
- 強模型 / FREE NOW count
- pending check-in
- last synced / stale

不要常駐空欄位或沒有意義的 Balance: Unknown。

Provider Detail：
- Overview
- Models
- Performance
- History

Overview 應可承載：
- 平台介紹 / 免費機制
- 註冊要求 / 地區限制 / 驗證限制
- Check-in / Stability / Notices
- User notes / Assistant researched summary

Models 主面向是 **My Models**，不是整站 catalog。My Models 內可依 selected group / FREE 狀態與 Smart Ranking 排序；背景 catalog、candidate、歷史 offering 繼續完整保存，但不得自動污染主列表。詳細規則見 `PROVIDER_MODEL_INTELLIGENCE_CONTRACT.md`。

## 5. Quick Add / Advanced Add / Review

### Quick Add
使用者只需最少資訊：
- Website URL
- Base URL
- API Key（需要時）
- optional Model ID

系統自動：
inspect website → detect provider/auth → discover endpoints → test credential → list models → test target model → pricing/quota/rate-limit → notice/about/docs → 建 Provider/Account/CredentialRef/EndpointProfile/ModelOffering/CredentialAvailability/Evidence。

`list models` 的結果預設只是 catalog/intelligence；**只有使用者或 Assistant 明確指定要加入的 target 才進 My Models**。加入後自動接 pricing / group / quota / provider performance / external / history；某 enrichment 缺失不得阻止模型加入。

缺一欄不得整個放棄；已驗證與 Unknown 必須分開回報。

### Advanced Add
完整控制：
- provider name / website
- adapter type Auto + override
- auth mode
- account
- multiple credentials
- custom header/query
- endpoint profiles
- base/chat/responses/anthropic/image/gemini endpoints
- group
- manual model IDs
- manual pricing / quota / classification metadata
- notes / check-in / tags

### Review
保存前至少顯示：
- provider type
- auth
- models found / verified
- pricing / quota status
- health
- unknown fields
- evidence confidence

Quick / Advanced / Assistant onboarding 必須共用同一可恢復 workflow，不能為 Assistant 另開旁路繞過 Review。

## 6. Accounts / Credentials / Selected-context UI

Provider 支援多 Account；每個 Account 多 Credential。

Credential metadata：
- label / note / account
- group / enabled / priority
- last success/failure / health
- quota association
- visible / usable models

支援：
- bulk import
- 每把 key 輕量驗證
- search/filter
- enable/disable
- batch enable/disable
- bulk test quota protection

Auth modes 至少：
NONE / API_KEY / BEARER / CUSTOM_HEADER / PAT_ACCOUNT。

Custom auth 可含 header/query/project/account 欄位；secret value 只在 privileged boundary 取用。

同 Provider 不同 Credential / Group 可能有不同模型、quota、pricing、availability。
因此：
- 使用 `CredentialAvailability`
- CanonicalModel 不因不同 key 重複建立
- UI 優先顯示當前 selected Credential / Group 的相關資料

## 7. Endpoint / Canonical Model / Classification

EndpointProfile 必須承載：
- OpenAI Chat Completions
- Responses
- Anthropic Messages
- Gemini Native
- Images
- Audio
- Embeddings
- Rerank
- Custom

同 Provider 可有多 base URL / endpoint path。

模型正規化：
- CanonicalModel
- ProviderModelAlias
- ModelOffering
- 原始 provider model ID 永遠保存
- mapping 可來自 official / LMSpeed / metadata / normalization / agent inference / user override
- mapping 必須有來源與 confidence

Classification 不是單一 modelType：
- primaryCategory
- capabilities[]
- modalities[]
- endpointTypes[]

分類必須真正用於 Search / Filter / Playground feature gating / Agent tool selection / Compare。

## 8. Models / Search / Compare Live

Global Search 支援：
- exact
- fuzzy
- alias
- normalized name

Manual / Assistant Model Add：
使用者或 Assistant 明確加入即建立/保留 My Model；**成功 Test 不再是「能不能存在於 My Models」的 gate**。Test 與後續真實 inference 改為累積 availability / My Uptime / History。背景 catalog 發現的模型若未被明確加入，仍只留在 catalog/candidate。

Filter Sheet 至少能擴展：
- FREE NOW
- input/output/cache price
- success 1h/24h
- TPS / TTFT / latency
- context / group / provider / vendor
- primary category / capabilities / modalities
- tools / vision / files / reasoning
- newly added / changed / price volatility / free uptime
- favorites / recently used / watchlist
- selected-key usable

Compare Live：
同一 canonical model 多 offering 使用相同小 prompt，比較 TTFT/TPS/latency/success/error/quota impact/PriceState，全部走正常 telemetry。

## 9. Adapter Architecture

禁止 giant switch。

v1 first-class adapters：
- NewApiAdapter
- NvidiaNimAdapter
- AmdRadeonAdapter
- OpenRouterFreeAdapter
- GeminiFreeAdapter
- GroqFreeAdapter
- GenericOpenAIAdapter

Adapter capability 應能獨立缺失，不因某 capability 不支援讓整個 Provider 失效：
discoverModels / fetchPricing / fetchBalance / fetchQuotaPools / fetchUsage /
fetchProviderPerformance / fetchNotice / fetchAbout / fetchCapabilities /
fetchAccount / fetchEndpointProfiles / testModel / probeEndpoint。

## 10. Data Provenance / FREE / Pricing / Quota

重要資料不能只有 value，要帶：
- source / evidence ID
- observedAt / lastVerifiedAt
- confidence
- scope
- parser/adapter version

Pipeline：
Raw Evidence → Normalized Observation → Reconciled Fact → Derived State → Snapshot → Event。

來源分層：
- Official：pricing / metadata / official quota
- Local：這台裝置、這把 key、現在的真實使用
- LMSpeed：external benchmark / discovery / cross-provider reference
- Web/Crawler：research evidence
- User：notes / corrections / alias override

FREE 是 derived state。
**Nominal price 不等於 effective charge**；Group 名稱、balance=0、quota=0 也都不能單獨推導 FREE / PAID / unavailable。
Text/Chat 只有有足夠 evidence 證明這個 selected credential/group 的必要 effective input/output = 0 且 fixed request = 0/不存在，才能標準化為 FREE_NOW；若只能證明目前可免費存取但機制不明，保留獨立 evidence/state，不硬推成永久 FREE。
Cache > 0 可顯示 FREE NOW · Cache costs extra。
必要價格未知 = FREE_STATUS_UNKNOWN。

PriceState：
FREE_NOW / PAID / PARTIALLY_FREE / UNKNOWN。

QuotaState：
QUOTA_AVAILABLE / QUOTA_LOW / QUOTA_EXHAUSTED / RATE_LIMIT_ONLY /
PUBLIC_POOL / UNLIMITED_CLAIMED / NO_KNOWN_CONSUMABLE_QUOTA / UNKNOWN。

Quota 是 Resource Pool，不強迫一條 provider-global quota；可能按 Provider / Account / Credential / Group / Model / Provider+Model / Custom。
多 pool 不做假平均；不知道 total 不畫百分比。

## 11. Telemetry / Health / Check-in / History

Performance：
- TTFT / TTFB / TPS
- observed RPM / input TPM / output TPM / total TPM
- success rate / latency / request count
- HTTP error / 429 rate

個人可用性另有 **My Uptime**：只算同一 Provider/Account/Credential/Group/Model/Endpoint 的真實可用歷史；User Stop/Cancel 完全排除。Provider site-wide success 與 External availability 分開保存，不能冒充 My Uptime。

Current Observed RPM/TPM 使用 rolling 60s；預設 Performance 視窗為 7d（1h/24h/7d/30d/custom）。Observed RPM/TPM 只是本地觀測，不是 provider limit。
禁止暴力 load test 猜 Max RPM/TPM；上限只有 Official API/Header/Docs 才可標 Official。

Provider Health：
HEALTHY / DEGRADED / UNSTABLE / OFFLINE / UNKNOWN。
officialHealth 與 observedHealth 分開。

Daily Check-in：
只有 requires_daily_checkin=true 才顯示；依 device-local date；支援 pending badge、外部 URL、手動 complete，預留未來 adapter。

History 至少能保存 free/paid 轉換、price/quota、outage/recovery、success/performance 等；一次 sync failure 不抹掉 last-good。

## 12. Dedicated Adapters

### New API
- PAT = Account/Management credential
- API key = Inference credential
- structured endpoints 優先：status/pricing/perf/notice/about/user/groups/token/logs/usage
- billing_mode / billing_expr / tiered_expr / legacy fallback
- 任意站方 expression 不 eval；只能安全 parser/IR
- site-wide performance 與 local telemetry 分開

### NVIDIA NIM
Hosted 與 self-hosted 分開。
Hosted：catalog 出現 ≠ 這把 key 可用；模型狀態至少 CATALOG / VERIFIED_USABLE / TEMPORARILY_UNAVAILABLE / NOT_AVAILABLE_FOR_ACCOUNT。
避免同步時全部 inference probe；用 catalog sync + high-value probe + Playground telemetry + Manual Test。
Self-hosted 如可用 Prometheus metrics，與 local telemetry 分開。

### AMD
使用 structured metadata；若有 quota/usage/header 正規化 RPM/RPD/TPM/TPD/reset/by-model；性能缺口用 local telemetry。

### OpenRouter Free
主要產品池只收經 pricing 驗證的 Free Models；不要把 paid catalog 塞滿 Models。
Free request quota / credit-spend / RPM / RPD 分開；估算 remaining 必須明示 Estimated。

### Gemini Free
Quota 可能是 Project × Model，RPM/TPM/RPD 不得硬合 provider-global。

### Groq Free
官方 model info + rate-limit docs/headers；可能 per-model RPM/RPD/TPM/TPD。

### Generic
至少能 list models（若有）、Playground inference、availability、local TTFT/TPS/latency/success。
Balance/Pricing/Quota optional；不要為 Balance 做脆弱 scraping。

## 13. LMSpeed / Global Crawler / Authenticity

LMSpeed 兩個角色：
1. enrichment
2. discovery seed

不是 ground truth；external 與 local 指標永遠分開。

Crawler 是「全球免費強模型 API 情報庫」，不是推薦清單。
完整 funnel：
DISCOVERED → VERIFYING → VERIFIED / HIGH_VALUE_GREY_ZONE / STALE / FAILED / REJECTED。

保存：
- rejection / downgrade reason
- evidence / confidence / last checked
- scanned / high-signal / verified / grey-zone / rejected counts

不得只顯示最後幾家。

多語 discovery 至少涵蓋 English / 中文 / Русский / 日本語 / 한국어 / Español / Português / Bahasa Indonesia / 其他有效語言。
來源包括 live provider/model audit、LMSpeed、regional aggregators、公告、catalog、pricing/free-group、frontend XHR/JSON、GitHub source/constants、direct probe、一般 web search。

Candidate verification：
- status / website / base URL / auth
- Taiwan usability
- QQ-only / China real-name / China phone / region/payment restrictions
- models / recurring free / reset semantics
- per-model/shared/account quota
- AccessMethod / compatibility
- context / tools / reasoning / function calling
- stability / actual inference / authenticity

非標準 API 資源可保存為 CODING_AGENT / WEB_ONLY / CUSTOM 等；只有能直接 inference 的才標 INFERENCE_READY。

Authenticity 以 response/provider metadata、capability behavior、tool/reasoning、fingerprint suite、known-good cross-provider 等建立 evidence + confidence；弱 evidence 不得百分百宣稱 substitution。

Probe progressive：
Passive → structured endpoint → minimal inference → high-value deeper probe。
優先正常使用 telemetry；禁止為了 Max RPM/TPM 暴力測試。

ResearchExecutionBackend 必須可演進 LOCAL / REMOTE / HYBRID；第一版不要求完整 server backend，但 schema 不綁死手機。

## 14. App Assistant

Assistant 是 App Control Agent，不是普通聊天機器人。

它必須能真正完成 Add Provider / Add Model，而不是只教使用者操作：使用者可只給 Base URL + Key + Model，Assistant 研究網站、套用已知 New API recipe 或 provider-research skill、建立 Provider/My Model，並自動接上可取得的 intelligence。

可讀：
Providers / Accounts / safe credential metadata / Models / selected key/group /
Balance / Quota / Pricing / History / Performance / Notices / Notes / Events /
Favorites / Watchlists / Settings / Crawler Evidence。

可操作：
Add/Edit Provider、update website/base URL、Add/Edit Credential metadata、
Add/Remove/Test Model、tags/notes、Sync/Test、switch provider/key/model、
Favorite/Watchlist、Query History、Web Research。

Assistant model 不硬綁；也是 Provider + Credential + Model 設定。

Secret boundary：
LLM 永遠看不到 raw key/PAT；需要 secret 由 privileged local tool layer 取。
Read-only 通常可直接做；可逆修改有 Undo + ActionLog；destructive（刪 Provider/Credential、換 PAT、critical Base URL、bulk delete）要 confirmation。

Structured tools 至少能擴展：
search/fetch/inspect/detect/discover/probe/list/test/measure/fetch pricing/quota/usage/perf/notice/
search source/authenticity/evidence/candidate/provider/model/sync。

## 15. Self-improving Research

每次 ResearchRun 後 Reflection：
- source/query effectiveness
- stale/fake-free source
- language/region hit-rate
- endpoint/auth/base-url patterns
- pricing/quota extraction
- authenticity probe value
- wasted probes
- candidate promotion/demotion
- ProviderRecipe / source reliability / strategy priorities

持久保存 versioned/reversible：
ProviderRecipe / SourceReliabilityProfile / ResearchStrategyProfile /
EndpointPattern / AuthPattern / Successful/FailedDiscoveryPath /
ModelAliasRule / VerificationOutcome。

禁止：
- 自改 System Prompt
- 直接改 App source code
- 改 Secret Boundary
- 改 destructive permission policy

Tool/code 改善只能 Proposal + Evidence + Rationale；learned rules versioned/reversible；evidence 不足標 tentative。

## 16. Background / Notifications / Backup / Retention

Background 使用 WorkManager；不要承諾 24/7 每分鐘可靠。
Stale safety：一次 failure 不刪 model/pricing；多次 successful catalog snapshots 才能判 REMOVED；group unavailable != model removed。

Notifications 先做 rule/settings architecture，可支援：
became FREE NOW / quota low / pending check-in / favorite recovered / important price change / outage/recovery。
**所有通知預設 OFF**；Settings 提供總開關與逐規則開關（依 D-2026-09-30-005）。

Backup 可匯出 provider definitions、endpoint profiles、aliases、notes、settings、recipes/research knowledge；secrets 預設不可 plaintext export。

**Telemetry / evidence / snapshots / history / research data 永久保留，不啟用 purge**（依 D-2026-09-30-006）。Rollup 只為查詢效能，不得拿來靜默刪除 raw 歷史。

## 17. Future-only Extension Points

目前只留架構，不做產品 UI/服務：
- automatic cross-provider fallback
- key auto rotation
- routing engine
- app HTTP/API gateway
- local API server
- complete server-side crawler backend

## 18. 完成條件

任何 Phase / requirement 只有同時滿足以下才可標 accepted：
1. 真實 production flow 存在，不只是 class/schema/test fixture。
2. 對應 automated tests。
3. 完整 build + JVM + device-tests 綠。
4. 重要 integration 有獨立 reviewer。
5. secret / scope / Unknown / last-good invariants 未破壞。
6. `docs/TRACEABILITY.md` 有 requirement → code → tests → green SHA。
7. 狀態文件更新，不把未做寫成完成，也不把已完成長期寫成未做。
