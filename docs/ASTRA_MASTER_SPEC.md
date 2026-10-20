# Astra / OpenCode 最終整合 Prompt
## 全球免費強模型 API Intelligence + Provider Manager + Playground
版本：Final Structured v2  
語言：繁體中文  
用途：直接交給 Astra / OpenCode 作為本專案的完整工程規格、Runtime Research Agent 規格、App Assistant 規格與自我改進規格。

---

# 目錄

## Part I — 產品與工程 Master Spec
1. 專案定位與不可違反原則
2. 技術棧與整體架構
3. 主導航與整體 UX
4. Playground
5. Providers
6. 新增 Provider：Quick Add / Advanced Add
7. Accounts / Credentials / 多 Key
8. Endpoint Profile / Protocol
9. Models 與 Canonical Model
10. 模型分類系統
11. Model Search / Filter / Compare Live
12. Adapter 架構
13. Data Provenance / Evidence
14. FREE NOW 判定
15. Pricing / Price History
16. Quota / Rate Limit
17. Performance / Telemetry
18. Provider Health / Stability
19. Daily Check-in
20. New API
21. NVIDIA NIM
22. AMD Radeon Cloud / Token Factory
23. OpenRouter Free
24. Gemini Free
25. Groq Free
26. Generic Provider
27. LMSpeed
28. Crawler / 全球免費強模情報庫
29. Model Authenticity / Substitution
30. Self-improving Research
31. App 內 Assistant
32. Secret / Permission / Safety Boundary
33. History / Notifications / Backup
34. Sync / Background / Stale Data
35. 未來功能：只留架構、不實作
36. 開發順序與 Acceptance Criteria

## Part II — Runtime Research Agent System Prompt
## Part III — App Assistant System Prompt
## Part IV — Research Reflection / Self-Improvement Prompt
## Part V — 我的願望清單補充：完整研究與自我改進（2026-10-19）

---

# Part I — 產品與工程 Master Spec

## 1. 專案定位與不可違反原則

你是 Astra，本專案的 Principal Engineer。

請全程使用繁體中文與我溝通，除非是程式碼識別字、API 原始欄位名稱或必要英文技術名詞。

這不是：
- 一般 API Key 管理器
- 一般聊天 App
- 一般模型列表
- 一次性爬蟲
- 一篇「推薦幾家免費 API」的工具

你要打造的是：

**全球免費強模型 API Intelligence + LLM Provider Manager + Playground + App Control Assistant**

核心目標：

1. 管理大量 LLM API Provider、Account、Credential。
2. 發現全球 recurring-free 的強模型 API。
3. 把 New API、NVIDIA、AMD、OpenRouter、Gemini、Groq 與未知 Provider 正規化成一致資料模型。
4. 持續監控模型、價格、FREE NOW、Quota、Rate Limit、效能、公告、可用性與歷史。
5. 讓使用者直接透過 Playground 使用 API。
6. 正常使用本身就收集第一方 telemetry。
7. App 內 Assistant 能研究、管理並操作整個 App。
8. Research Agent 會持續學習，不可每次重新從零搜尋。
9. 保留未來 Routing / Fallback / App-as-API Gateway 的擴充點，但目前不要實作。

### 不可違反

- 不得私自刪減困難功能。
- 不得用 fake/mock 資料冒充完成。
- 不知道的資料就是 Unknown，不得猜。
- 不得把第三方數據冒充本機數據。
- 不得把一次性 Credit 冒充 recurring free。
- 不得因為 model id 有 `free` 就判定免費。
- 不得因一次 sync 失敗就刪模型或宣告模型下架。
- 不得將 Raw API Key / PAT 放進 LLM Prompt。
- 若現有 repository 已存在，先完整檢查，不要亂改無關檔案。

### 語言

- 回覆、規劃、TODO、文件：繁體中文。
- class / function / variable / package / DB/API 欄位：英文。
- UI 預設繁體中文，但保留 i18n。
- TTFT、TPS、RPM、TPM、Provider、Adapter、Quota 等技術詞可保留英文。

---

## 2. 技術棧與整體架構

目標：

- Native Android
- Kotlin
- Jetpack Compose
- Material 3
- Coroutines / Flow
- Room
- WorkManager
- Android Keystore-backed secret protection
- Repository / Service / Adapter 分層
- 清楚的 domain / data / network / UI 分層

目標裝置包含 Sharp R8s 類 Android 手機，因此：

- 避免不必要發熱
- 避免高頻無意義 benchmark
- 避免重複 DB writes
- 避免無意義 recomposition
- 正常 Playground 使用優先當 telemetry
- 背景同步必須接受 Android 可能殺背景工作

核心原則：

**Collect everything, personalize display.**

資料庫可以完整保存：
- Provider
- Model
- Pricing
- Quota
- Telemetry
- Evidence
- History
- Research knowledge

但 UI 只顯示當下有意義的資訊。

---

## 3. 主導航與整體 UX

Bottom Navigation 固定只有三個：

1. **Playground**
2. **Providers**
3. **Models**

Playground 為預設首頁。

Settings 放右上角，不是第四個 Tab。

語意：

- Playground = 使用
- Providers = 管理來源
- Models = 全域搜尋、比較、找最好用的模型

---

## 4. Playground

介面風格：
- 簡潔
- 接近 ChatGPT
- 不像 debug console

支援架構：

- Chat
- Reasoning
- Coding
- Streaming
- Image generation
- Image editing
- Vision
- Files
- Tool calls
- 未來其他 modalities

頂部可快速選：

- Provider
- Account / Credential
- Group（需要時）
- Model

### 每次 inference 必須進統一 telemetry

記錄：

- providerId
- accountId
- safeCredentialId
- canonicalModelId
- providerModelId
- group
- endpoint/operation
- telemetry source
- requestStartedAt
- firstTokenAt
- completedAt
- streaming
- inputTokens
- outputTokens
- reasoningTokens
- cacheReadTokens
- cacheWriteTokens
- totalTokens
- HTTP status
- error category
- 429
- retry count
- responseModelId
- TTFT
- TTFB
- TPS
- totalLatency
- relevant rate-limit headers
- quota snapshot before/after
- pricing snapshot
- PriceState
- QuotaState
- actual/estimated cost

正常使用已經有新鮮 telemetry 時，不要再額外打一個 benchmark request。

---

## 5. Providers 頁

Provider 用大型 Material 3 卡片。

可呈現：

- Provider 名稱
- 短介紹
- Health
- Account / Credential 數量
- Balance（有意義才顯示）
- Quota summary
- 幾個強模型
- 目前免費模型數
- 待簽到 Badge
- Last synced / stale indicator

不要顯示：

- 無用社群按讚
- 留言
- 空欄位
- 沒有 Balance 卻一直寫 `Balance: Unknown`

### Provider Detail

統一 UI：

頂部：
- Provider name
- Health
- Website URL
- Endpoint Profiles
- Balance（若有）
- Accounts / Credentials
- Selected Key / Group
- Test Now
- Sync
- Add Model

內容：
- Overview
- Models
- Performance
- History

Overview：
- 平台介紹
- 免費機制
- 註冊要求
- 地區限制
- 驗證限制
- Check-in
- Stability
- Notices
- User notes
- Assistant researched summary

Models：
- FREE NOW 優先
- 非免費模型保留但 dim + 下沉
- 不刪除歷史 offering

---

## 6. 新增 Provider：Quick Add / Advanced Add

新增 Provider 必須分成兩種模式。

### A. Quick Add

目的：
讓使用者只丟最少資訊，Assistant 自己處理剩下的。

使用者可只提供：

- Website URL
- Base URL
- API Key（如果需要）
- 可選：想測的 Model ID

例如：

> 這個站幫我加進去，Base URL 是 xxx，Key 是 xxx，順便測 GLM-5.3。

Assistant 應自動：

1. inspect website
2. 判斷 Provider type
3. detect auth
4. discover Base URL / endpoints
5. 測 Credential
6. list models
7. 測指定模型
8. 抓 Pricing
9. 抓 Quota / Rate Limit
10. 抓 Notice / About / Docs
11. 建立 Provider
12. 建立 Account
13. 建立 Credential reference
14. 建立 EndpointProfile
15. 建立 ModelOffering
16. 建立 CredentialAvailability
17. 記錄 Evidence
18. 回報哪些資料已驗證、哪些仍 Unknown

Quick Add 不應因缺少某欄位而整個放棄。

### B. Advanced Add

提供完整手動控制：

- Provider name
- Website URL
- Adapter type（Auto / Manual override）
- Auth mode
- Account
- 多個 Credentials
- Custom Header / Query fields
- Endpoint Profiles
- Base URL
- Chat endpoint
- Responses endpoint
- Anthropic Messages endpoint
- Image endpoint
- Gemini native endpoint
- Group
- 手動 Model ID
- 手動 Pricing metadata
- Notes
- Check-in URL
- Provider tags

### Review Step

Quick Add / Advanced Add 完成探測後，應顯示簡潔 Review：

- Provider type
- Auth
- Models found
- Models verified
- Pricing status
- Quota status
- Health
- Unknown fields
- Evidence confidence

再保存。

---

## 7. Accounts / Credentials / 多 Key

一個 Provider 支援：

- 多 Account
- 每個 Account 多 Credential

Credential 欄位：

- label
- note
- account association
- group
- enabled
- priority metadata
- lastSuccessAt
- lastFailureAt
- health
- quota association
- visible/usable models

### 必須支援

- 一次貼很多 Keys
- Bulk import
- 每把 Key 輕量驗證
- 搜尋 Key
- 篩選 Key
- Enable / Disable
- 批量 Enable / Disable

Bulk Test 必須有 quota protection。

### Custom Credential

Auth Mode：

- NONE
- API_KEY
- BEARER
- CUSTOM_HEADER
- PAT_ACCOUNT

Custom Credential 允許：

- Header name
- Header prefix
- Header value
- Secret / non-secret
- Query parameter
- Project ID
- Account ID
- 自訂非 Secret 欄位

例如：

- `Authorization: Bearer {secret}`
- `X-API-Key: {secret}`
- `X-Account-ID: {value}`
- `?api_key={secret}`

Raw secret 永遠由安全層保存。

---

## 8. Key-specific / Group-specific Availability

同 Provider 不同 Credential 可能：

- 看得到不同模型
- Group 不同
- Quota 不同
- Pricing 不同
- 一把 Key 可用、一把 Key 不可用

因此需要：

`CredentialAvailability`

不要把同一模型因不同 Key 重複建立 CanonicalModel。

UI 優先顯示目前 Selected Credential / Group。

VIP / SVIP 等目前 Credential 根本用不到的 Group，不要預設塞滿 UI。

---

## 9. EndpointProfile / Protocol

不要假設 Provider 永遠只有：

`https://xxx/v1`

需要 `EndpointProfile`。

至少能描述：

- OpenAI Chat Completions
- OpenAI Responses API
- Anthropic Messages
- Gemini Native
- Images
- Audio
- Embeddings
- Rerank
- Custom

一個 Provider 可有多個 Base URL / endpoint path。

第一版 UI 可簡化，但 schema 必須能支援。

---

## 10. Models 與 Canonical Model

同一模型在不同 Provider 可能叫：

- `glm-5.3`
- `z-ai/glm-5.3`
- `glm-5.3-free`
- `GLM5.3`
- `glm53`

因此建立：

- `CanonicalModel`
- `ProviderModelAlias`
- `ModelOffering`

原始 Provider Model ID 永遠保存。

Mapping 可參考：

- 官方 metadata
- LMSpeed canonical slug
- developer
- family
- context
- capabilities
- name normalization
- Agent inference
- User override

Assistant / 使用者可修正錯誤 mapping。

---

## 11. 模型分類系統

**不能只做一個單選 enum 的 `modelType`。**

模型需要多維分類。

至少分成：

### primaryCategory

單一主要用途：

- CHAT_REASONING
- CODING
- IMAGE
- AUDIO
- EMBEDDING
- RERANK
- SAFETY
- OTHER

### capabilities[]

可多選：

- REASONING
- CODING
- TOOL_CALLING
- FUNCTION_CALLING
- STRUCTURED_OUTPUT
- JSON_MODE
- VISION
- FILES
- IMAGE_GENERATION
- IMAGE_EDITING
- AUDIO_INPUT
- AUDIO_OUTPUT
- WEB_SEARCH
- COMPUTER_USE
- LONG_CONTEXT
- EMBEDDING
- RERANK
- SAFETY
- OTHER

### modalities[]

可多選：

- TEXT_INPUT
- TEXT_OUTPUT
- IMAGE_INPUT
- IMAGE_OUTPUT
- AUDIO_INPUT
- AUDIO_OUTPUT
- FILE_INPUT

### endpointTypes[]

可多選：

- CHAT_COMPLETIONS
- RESPONSES
- ANTHROPIC_MESSAGES
- GEMINI_NATIVE
- IMAGES_GENERATION
- IMAGES_EDIT
- AUDIO
- EMBEDDINGS
- RERANK
- CUSTOM

### 其他重要 metadata

- developer/vendor
- model family
- release date
- context window
- max output tokens
- reasoning support
- tool support
- structured output support
- vision support
- official/free status
- model stability
- AA capability reference
- LMSpeed reference

### 範例

GLM：

primaryCategory:
`CHAT_REASONING`

capabilities:
- REASONING
- CODING
- TOOL_CALLING
- STRUCTURED_OUTPUT

modalities:
- TEXT_INPUT
- TEXT_OUTPUT

endpointTypes:
- CHAT_COMPLETIONS
- RESPONSES

Image model：

primaryCategory:
`IMAGE`

capabilities:
- IMAGE_GENERATION
- IMAGE_EDITING

modalities:
- TEXT_INPUT
- IMAGE_INPUT
- IMAGE_OUTPUT

模型分類不是裝飾資訊，要能用於：
- Search
- Filter
- Playground feature gating
- Agent tool selection
- Model comparison

---

## 12. Model Search / Filter / Compare Live

### Provider 內搜尋

搜尋該 Provider catalog。

### Global Search

搜尋所有 Canonical Models。

支援：

- Exact
- Fuzzy
- Alias
- Normalized name

以下都應可找到同一模型：

- `glm53`
- `GLM-5.3`
- `z-ai/glm-5.3`

搜尋結果可：

- Test
- Add
- Favorite
- Compare

### Manual Model ID

即使 `/v1/models` 沒列出，允許手動輸入：

`gpt-x-free`

然後：

1. Test
2. 成功
3. Add hidden/manual ModelOffering
4. 保存 Evidence
5. 標示來源

### Filters

可支援：

- FREE NOW
- Input price
- Output price
- Cache price
- Success 1h / 24h
- TPS
- TTFT
- Latency
- Context
- Group
- Provider
- Vendor
- Primary Category
- Capabilities
- Modalities
- Tools
- Vision
- Files
- Reasoning
- Newly Added
- Changed
- Price Volatility
- Free Uptime
- Favorites
- Recently Used
- Watchlist
- Selected-key usable

用 Filter Sheet，不要全部常駐。

### Compare Live

同一 Canonical Model 可選多個 Provider/Offering。

使用完全相同的小 Prompt。

記錄：

- TTFT
- TPS
- Total Latency
- Success
- Error
- Quota impact
- PriceState

全部走正常 telemetry pipeline。

---

## 13. Adapter Architecture

禁止 giant switch。

第一批 Dedicated Adapters：

- NewApiAdapter
- NvidiaNimAdapter
- AmdRadeonAdapter
- OpenRouterFreeAdapter
- GeminiFreeAdapter
- GroqFreeAdapter
- GenericOpenAIAdapter

Adapter capabilities：

- discoverModels()
- fetchPricing()
- fetchBalance()
- fetchQuotaPools()
- fetchUsage()
- fetchProviderPerformance()
- fetchNotice()
- fetchAbout()
- fetchCapabilities()
- fetchAccount()
- fetchEndpointProfiles()
- testModel()
- probeEndpoint()

缺某 capability 不得讓整個 Provider 失效。

---

## 14. Core Domain

至少：

- Provider
- Account
- Credential
- CredentialField
- EndpointProfile
- CanonicalModel
- ProviderModelAlias
- ModelOffering
- CredentialAvailability
- PriceSnapshot
- PriceEvent
- QuotaPool
- PerformanceSample
- PerformanceSummary
- ProviderNotice
- ProviderEvent
- CheckinRecord
- Evidence
- CandidateProvider
- ResearchRun
- ProviderRecipe
- SourceReliabilityProfile
- ResearchStrategyProfile
- VerificationOutcome

核心關係：

**Provider × Account/Credential × Model × Group × Endpoint × Time**

---

## 15. Data Provenance / Evidence

重要資料不能只有 `value`。

保留：

- value
- source
- source URL / evidence ID
- observedAt
- lastVerifiedAt
- confidence
- scope
- parser/adapter version

Pipeline：

`Raw Evidence`
→ `Normalized Observation`
→ `Reconciled Fact`
→ `Derived State`
→ `Snapshot`
→ `Event`

### Source 優先邏輯

Provider Official：
- Pricing
- Official metadata
- Official quota / limits

Local：
- 這台裝置
- 這把 Key
- 現在
- 真實性能

LMSpeed：
- External benchmark
- Discovery
- Cross-provider reference

Web / Crawler：
- Research evidence

User：
- Notes
- Alias override
- Manual correction

不同來源不能無聲覆蓋。

Raw payload/log 必須先移除：

- API Key
- PAT
- Authorization
- Cookie
- Sensitive headers

---

## 16. FREE NOW 判定

FREE 是 Derived State。

不可只看：

- Model name 有 `free`
- Group name = Free
- model_price = 0

Scope：

**Provider × Account/Credential × Model × Group × Endpoint/Operation × Time**

正規化 EffectivePrice：

- input
- output
- cacheRead
- cacheWrite
- fixedRequest
- image
- audio
- modality-specific price
- group ratio
- billing mode
- tier/context condition
- raw billing expression
- source
- observedAt

### Text / Chat / Reasoning

只有：

- effectiveInput == 0
- effectiveOutput == 0
- fixedRequest == 0 或不存在

才判：

`FREE_NOW`

若 Cache > 0：

`FREE NOW · Cache costs extra`

必要價格未知：

`FREE_STATUS_UNKNOWN`

不得猜。

### Image / Audio / Other

依該 Operation 真正計費欄位判定。

### PriceState

- FREE_NOW
- PAID
- PARTIALLY_FREE
- UNKNOWN

### Access / QuotaState

- QUOTA_AVAILABLE
- QUOTA_LOW
- QUOTA_EXHAUSTED
- RATE_LIMIT_ONLY
- PUBLIC_POOL
- UNLIMITED_CLAIMED
- NO_KNOWN_CONSUMABLE_QUOTA
- UNKNOWN

兩者分離。

例如：

`FREE NOW + 18% quota remaining`

或：

`FREE NOW + RATE_LIMIT_ONLY`

都合法。

Account 有免費 Credits ≠ Model 單價免費。

---

## 17. Recurring Free / Trial / Promotion

另外保存：

- RECURRING_FREE
- ONE_TIME_TRIAL
- WELCOME_BONUS
- PUBLIC_SHARED_POOL
- TEMPORARY_PROMOTION
- UNKNOWN

一次性 Credit 不得冒充 recurring free。

狀態變化事件：

- PAID_TO_FREE
- FREE_TO_PAID

---

## 18. Pricing / Price History

Price scope：

**Provider × Model × Group × Time**

保存：

- Input
- Output
- Cache Read
- Cache Write/Create
- Fixed/request
- Image/media
- Group ratio
- Billing mode
- Raw billing expression
- Context/tier conditions
- Timestamp/source

相同 snapshot 不要重複寫。

保留 `last_checked_at`。

真正改變才產生事件：

- PRICE_CHANGED
- PAID_TO_FREE
- FREE_TO_PAID

---

## 19. Quota / Rate Limit

Quota 是 Resource Pool，不是假設 Provider 一條。

Scope：

- Provider
- Account
- Credential
- Group
- Model
- Provider + Model
- Custom

QuotaPool：

- scope
- provider
- account/credential optional
- group optional
- model optional
- unit
- total optional
- remaining optional
- used optional
- resetAt
- window
- source
- confidence

Units：

- requests
- tokens
- credits
- USD
- neurons
- images
- custom

Semantics：

- QUOTA_KNOWN
- RATE_LIMIT_ONLY
- PUBLIC_POOL
- UNLIMITED_CLAIMED
- NO_KNOWN_CONSUMABLE_QUOTA
- UNKNOWN

### UI

Provider-wide quota：
Provider card/detail 顯示 progress。

Model-specific quota：
Model row/detail 顯示 progress。

Progress 代表 Remaining：

- 100% = 未使用
- 0% = 耗盡

多個不相容 Pool：
不得做假平均。

可以顯示：

`3 quota pools · lowest remaining 18%`

不知道 total：
不畫百分比。

---

## 20. Performance / Telemetry

所有 inference 不論免費或付費都要記錄。

TelemetrySource：

- PLAYGROUND
- MANUAL_TEST
- BACKGROUND_PROBE
- CRAWLER_VERIFY
- ASSISTANT_TEST
- COMPARE_LIVE

### 指標

- TTFT
- TTFB
- TPS
- Observed RPM
- Observed Input TPM
- Observed Output TPM
- Observed Total TPM
- Success Rate
- Total Latency
- Request Count
- HTTP Error
- 429 Rate

### TTFT

Streaming：
request sent → first generated token

Non-streaming：
TTFT unavailable。

不可把 Total Latency 冒充 TTFT。

### RPM / TPM

Rolling window 計算。

Observed 只表示本地實際使用，不是 Provider 上限。

禁止顯示：

`12 / 40 RPM`

除非 `40` 是官方 API/Header/Docs 明確提供。

否則只能：

`Current observed: 12 RPM`

### 禁止暴力測 Max RPM/TPM

不要 load test 來猜上限。

Max Limit 只有：

- Official API
- Official Headers
- Official Docs

才可標 Official。

### Success Rate

建議：

- 1h
- 24h
- 7d

顯示 sample count。

樣本太少：
標 low confidence。

Error 類型：

- 429
- Timeout
- 5xx
- Auth
- Model unavailable
- Network
- Parser
- Other

### Token Usage

Provider 回 authoritative usage：
優先使用。

自行 tokenizer/估算：
標 `estimated=true`。

### Privacy

Telemetry 預設不要永久保存完整 Prompt/Response。

Performance data 與 Chat content 分離。

Fingerprint probe 只保存必要證據。

---

## 21. 免費模型 History

每個 ModelOffering 保存：

- First discovered
- First verified free
- Last verified free
- Last successful inference
- Last failed inference
- Current PriceState
- Current QuotaState
- PAID_TO_FREE
- FREE_TO_PAID
- Temporary outage
- Quota exhausted
- Quota reset/replenished

Playground 真實成功使用本身就是高價值最新 Evidence。

---

## 22. Provider Health / Stability

至少：

- HEALTHY
- DEGRADED
- UNSTABLE
- OFFLINE
- UNKNOWN

分開：

- officialHealth / officialStability
- observedHealth / observedStability

Observed Health 可依：

- Success Rate
- Timeout
- 429
- 5xx
- Model unavailable
- Recent failure/recovery

---

## 23. Daily Check-in

欄位：

- requires_daily_checkin
- checkin_url

不需要：
UI 完全不顯示。

需要且今天未完成：
顯示待簽到 Badge。

提供：

`待簽到`

Quick Filter。

點擊：
開外部 Check-in 頁。

回 App：
可手動按 `完成簽到`。

保存：

- providerId
- date
- completedAt
- source

Device-local midnight reset。

保留 future auto-checkin adapter。

---

## 24. New API

New API 是 reference-quality Adapter。

優先使用 structured endpoints：

- /api/status
- /api/pricing
- /api/perf-metrics/summary
- /api/perf-metrics
- /api/notice
- /api/about

以及可用：

- user/account
- groups
- token/key
- logs/stat
- usage

PAT-first：

- PAT = 管理 / Account Credential
- API Key = Inference Credential

第一版不要做完整網站 Login/OAuth。

API Key quota editor 不是核心。

`auto` group 保留特殊處理空間。

Notice 內容改變：

`NOTICE_CHANGED`

### New API Pricing

支援：

- billing_mode
- billing_expr
- tiered_expr
- legacy fallback

不得 eval 任意 JS/string。

安全 parse AST / intermediate representation。

Site-wide performance 與 Local telemetry 分開。

---

## 25. NVIDIA NIM

Dedicated first-class Adapter。

Hosted 與 Self-hosted 分開。

### Hosted NVIDIA

官方 Catalog / Metadata 優先。

`/v1/models` 出現不代表這把 Key 一定可用。

Model state：

- CATALOG
- VERIFIED_USABLE
- TEMPORARILY_UNAVAILABLE
- NOT_AVAILABLE_FOR_ACCOUNT

不要每次同步把全部 Catalog 都 inference probe。

使用：

- Catalog sync
- High-value probe
- Playground telemetry
- Manual Test

Hosted 如果沒有 New API 式 performance API：

Local 補：

- TTFT
- TPS
- observed RPM
- observed TPM
- Success Rate
- Total Latency
- 429 / Error History

若官方 Headers 有 limit/remaining：
另外存 authoritative RateLimit/Quota。

### Self-hosted NIM

若 `/v1/metrics` 可用：
讀 Prometheus metrics。

和 Local telemetry 分開。

---

## 26. AMD Radeon Cloud / Token Factory

Dedicated Adapter。

官方 Structured Metadata 優先：

- name
- description
- family
- aliases
- architecture
- providers
- pricing
- context
- supported parameters
- structured outputs
- free
- official stability

若 Usage / Quota endpoint / headers 可用：

- RPM limit/remaining
- daily allowance
- used/remaining
- reset
- requests
- token count
- errors
- by-model usage

Performance 缺少官方欄位：
Local 補。

---

## 27. OpenRouter Free

**只處理 FREE 模型。**

不要把整個 Paid Catalog 塞進主要模型池。

官方資料：

- Free Models
- Metadata
- Context
- Capabilities
- Pricing
- Free-tier Usage / Limits

分開：

- Free Request Quota
- Credit/Spend Limit
- RPM
- RPD

設計當下常見 Free Tier 約 50 req/day，但不得永久 Hard-code。

若只知道 Official Total、不知道 Remaining：

可以用 App observed usage 算：

`Estimated Remaining`

必須標 Estimated。

---

## 28. Gemini Free

Dedicated Adapter。

Quota 可能按：

**Project × Model**

包含：

- RPM
- TPM
- RPD

不能硬合併成 Provider-global quota。

正規化 Gemini modalities / capabilities。

主池優先真正 Free Tier 模型。

---

## 29. Groq Free

Dedicated Adapter。

讀：

- Official Model info
- Official Rate Limit
- Rate-limit Headers

可能 per-model：

- RPM
- RPD
- TPM
- TPD

正規化到 QuotaPool / RateLimit。

Inference 可重用 OpenAI-compatible layer。

---

## 30. Generic Provider

未知 Provider 至少要能：

- List models（若有）
- Playground inference
- Availability
- Local TTFT
- Local TPS
- Local Latency
- Local Success Rate

探測：

- /v1/models
- Chat Completions
- Responses
- Images
- Other detected endpoints

Balance / Pricing / Quota Optional。

不要為了 Balance 做脆弱 Web scraping。

---

## 31. 非標準 API 資源

Crawler Intelligence 不只收標準 API。

AccessMethod：

- OPENAI_COMPAT
- RESPONSES
- ANTHROPIC_MESSAGES
- GEMINI_NATIVE
- CODING_AGENT
- WEB_ONLY
- CUSTOM
- OTHER

Coding Agent 型免費資源即使不能直接進 Playground，也保留在 Intelligence Database。

真正可以直接 inference 的標：

`INFERENCE_READY`

---

## 32. LMSpeed

兩個角色：

1. Enrichment
2. Discovery Seed

不是 Ground Truth。

可補：

- Canonical identity
- Description
- Benchmark
- Capabilities
- Context
- External TPS
- External TTFT
- Pricing reference
- Free offering discovery
- Provider discovery

第三方資料保存：

- Source
- Timestamp
- Attribution
- Cache metadata

External 與 Local 分開呈現。

例如：

- Your TTFT: 5.8s
- LMSpeed reference: 2.3s

不要平均成一個數字。

---

## 33. Crawler：全球免費強模 Intelligence

Crawler 不是一般「找幾家 API」。

它是：

**全球免費強模型 API 情報庫**

必須保存完整研究 Funnel。

Candidate State：

- DISCOVERED
- VERIFYING
- VERIFIED
- HIGH_VALUE_GREY_ZONE
- STALE
- FAILED
- REJECTED

保存：

- Rejection reason
- Downgrade reason
- Evidence
- Confidence
- Last checked

使用者能看到：

`100+ scanned → 30 high-signal → 9 verified → 15 grey-zone → X rejected`

不能只顯示最後幾家。

---

## 34. Crawler Target Quality

主要找：

- 當前強模型
- Coding / Agent / Reasoning 強模
- Recurring Free
- 大額 recurring free pool
- RPM-only
- Claimed unlimited
- Public pool
- Dynamic quota

可量化 recurring free：

約 `>= 1M tokens/day equivalent`

是高價值排序訊號之一，但不是死門檻。

以下不能因為 quota 難量化就淘汰：

- RPM-only
- Unlimited claimed
- Huge public pool
- quota undisclosed
- Dynamic pool

One-time Trial / Welcome Bonus 不算核心 recurring free。

---

## 35. 全球多語搜尋

支援：

- English
- Chinese
- Russian
- Japanese
- Korean
- Spanish
- Portuguese
- Indonesian
- Other

同 Provider 不因不同語言重複計算。

重要 Sources：

- LMSpeed
- AI-APISHKA 類來源
- Regional Aggregators
- 中文公益/API 導航
- New API 站
- Linux DO / Community
- Provider Announcements
- Live Catalog
- GitHub Source / Constants

搜尋優先順序：

1. Live provider/model audit
2. Aggregator price/availability
3. Provider announcements
4. /models
5. Pricing/free-group endpoints
6. Frontend XHR / JSON
7. GitHub source/constants
8. Direct API probe
9. Ordinary web search

---

## 36. Candidate Verification

高價值 Candidate 研究：

- Provider current status
- Website
- Base URL
- Auth
- Taiwan usability
- QQ-only?
- China real-name?
- Model
- Free status
- Recurring quota
- Reset semantics
- Per-model/shared/account
- AccessMethod
- API compatibility
- Context
- Tools
- Reasoning
- Function calling
- Stability
- Actual inference
- Model authenticity

台灣無法正常取得：
保存 rejection reason。

---

## 37. Model Authenticity / Substitution

有些站可能掛 A 模型實際回 B。

建立獨立 Authenticity Probe。

Evidence 可含：

- response metadata
- provider metadata
- context behavior
- capability behavior
- tool behavior
- reasoning behavior
- fingerprint suite
- cross-provider comparison

保存：

- evidence
- confidence
- probeVersion
- timestamp

Evidence 弱：
不得宣稱百分之百。

---

## 38. Probe Budget

不要為 Crawler 燒光 Free Quota。

Progressive：

DISCOVERED
→ Passive research
→ Cheap structured endpoint
→ Minimal inference
→ High-value deeper probe
→ VERIFIED

正常 User traffic 優先作 benchmark。

禁止為了 Max RPM/TPM 做暴力 Load Test。

---

## 39. Research Execution Backend

不要把 Crawler 永久綁死手機本地。

建立：

`ResearchExecutionBackend`

可支援：

- LOCAL
- REMOTE
- HYBRID

第一版不強制需要 Server。

ResearchRun / DB schema 不依賴特定執行位置。

---

## 40. Self-improving Research

Agent 要越用越會找。

不是 uncontrolled source-code mutation。

持久保存：

- ProviderRecipe
- SourceReliabilityProfile
- ResearchStrategyProfile
- EndpointPattern
- AuthPattern
- SuccessfulDiscoveryPath
- FailedDiscoveryPath
- ModelAliasRule
- VerificationOutcome

每次 ResearchRun 後做 Reflection：

- 哪些來源命中高？
- 哪些來源 stale？
- 哪些 query 無效？
- 哪些 endpoint pattern 有效？
- 哪些 quota extraction 成功？
- 哪些 fingerprint probe 有辨識力？
- 哪些 Provider family 可抽象成 Recipe？

可更新：

- source weights
- research priorities
- reusable recipes
- extraction patterns
- alias hints
- verification heuristics

### ProviderRecipe

可保存：

- host/pattern
- provider family
- Base URL discovery
- auth style
- model endpoint
- pricing endpoint
- quota endpoint
- notice endpoint
- check-in URL
- JSON extraction rules
- known limitations
- last verified
- success count
- failure count
- version

必須 versioned / reversible。

---

## 41. App 內 Assistant

Assistant 不是聊天機器人，而是 App Control Agent。

可讀：

- Providers
- Accounts
- Credential metadata
- Models
- Selected Key / Group
- Balance
- Quota
- Pricing
- Price History
- Performance
- Notices
- Notes
- Events
- Favorites
- Watchlists
- Settings
- Crawler Evidence

可操作：

- Add/Edit Provider
- Update Website/Base URL
- Add/Edit Credential metadata
- Add/Remove/Test Model
- Update Description/Tags
- Sync
- Test
- Switch Provider/Key/Model
- Favorite/Watchlist
- Query History
- Web Research

### Assistant Model

不要硬綁。

建立獨立 Assistant Model 設定：

- Provider
- Credential
- Model

Assistant inference 仍走 Provider abstraction。

---

## 42. Secret / Permission Boundary

Raw API Key / PAT 絕不能進 LLM prompt/context。

LLM 只看到：

- credential ID
- label
- provider
- safe metadata

需要 Secret：
由 privileged local tool layer 取得。

使用 Android Keystore-backed protection。

### Permission

Read-only：
通常直接執行。

一般可逆修改：
- metadata
- description
- tags
- add model
- notes

可直接做 + Undo / Action Log。

敏感 destructive：

- Delete Provider
- Delete Credential
- Replace PAT
- Replace critical Base URL
- Bulk deletion

需要 Confirmation。

---

## 43. Assistant Tools

不要只給 `search_web()`。

Structured Tools：

- searchWeb()
- fetchPage()
- fetchJson()
- inspectWebsite()
- discoverApiEndpoints()
- detectProviderType()
- detectNewApi()
- detectAuthType()
- discoverBaseUrl()
- probeOpenAIEndpoint()
- listModels()
- testModel()
- measureInference()
- inspectHeaders()
- fetchPricing()
- fetchQuota()
- fetchUsage()
- fetchPerformance()
- fetchNotice()
- searchGitHubSource()
- checkModelFingerprint()
- compareEvidence()
- recordEvidence()
- saveCandidate()
- rejectCandidate()
- createProvider()
- updateProvider()
- addCredential()
- addModel()
- updateModel()
- syncProvider()

工具輸出結構化資料。

---

## 44. History / Notifications / Backup

### History

沒有第四個 History Tab。

History 放：

- Provider Detail
- Model Detail
- Assistant Query

Charts：

- Input Price
- Output Price
- Cache Price
- Free periods
- Free uptime
- Success Rate
- TPS
- TTFT
- Latency
- Quota
- Balance

### Notifications

先做 Rule / Settings Architecture。

支援：

- Became FREE NOW
- Quota Low
- Pending Daily Check-in
- Favorite Recovered
- Important Price Change
- Provider Outage / Recovery

**目前不要自行決定所有預設開關。**

### Backup

可匯出：

- Provider Definitions
- Endpoint Profiles
- Base URLs
- Alias mappings
- Notes
- Settings
- ProviderRecipe
- Research knowledge

Secrets 預設不可 plaintext export。

未來可加 encrypted secret backup。

---

## 45. Sync / Background / Stale Data

Launch / Resume：
立即 Sync。

前景建議：

- Selected Provider Pricing：約 60s
- Performance：2–5 min
- Balance/Quota：約 5 min
- Inactive Provider：5–15 min

Background：
WorkManager。

不要承諾背景 24/7 每分鐘可靠。

可預留 Future Live Watch foreground service。

### Stale Safety

一次 Sync Failure：

- 不刪 Model
- 不宣告 Removed
- 不清空 Pricing

保留 Last Good Snapshot。

Model Lifecycle：

- NEW
- ACTIVE
- MISSING
- REMOVED

只有多次 Successful Catalog Snapshot 證明不存在，才判 REMOVED。

Group unavailable ≠ Model removed。

---

## 46. Telemetry Retention

設計：

- Raw PerformanceSample
- Rollup / Aggregate Tables
- RetentionPolicy abstraction

不要自行 hard-code Raw sample 幾天後刪除。

未來可：

- Raw samples 保留一段時間
- 1h / 24h / Daily rollup 長期保存

但實際 retention 之後再定。

---

## 47. 未來功能：現在只留架構

目前不要實作：

- 自動跨 Provider Fallback
- Key Auto Rotation
- Routing Engine
- App 暴露成 HTTP/API Gateway
- Local API Server

只保留 Extension Points，例如：

- RoutingPolicy
- ProviderSelectionStrategy
- ExternalGateway
- LocalApiServer
- FallbackCandidate

現在不要做 UI、服務、背景 Routing。

---

## 48. 開發流程

開始前：

1. Inspect repository
2. 理解現有 architecture
3. 用繁體中文寫 implementation plan
4. 說明哪些可以沿用
5. 列新增/修改 module / package / Room entities
6. 指出 migration / security / performance risks
7. 分清楚 v1 實作與 future extension
8. 再開始 Coding

規格已有答案就不要一直問。

模糊處：
優先保留 extensibility，不要砍功能。

每階段保持 Project Buildable。

---

## 49. Implementation Priority

### Phase 1
Core Domain / DB / Secrets / Networking / Provenance / Telemetry

### Phase 2
Playground + Local Telemetry

### Phase 3
Providers UI + Quick Add / Advanced Add + Accounts / Multi-key + Generic OpenAI

### Phase 4
New API Full Adapter

### Phase 5
NVIDIA / AMD / OpenRouter Free / Gemini Free / Groq Free

### Phase 6
Canonical Models / Model Classification / Search / Filters / Compare Live

### Phase 7
LMSpeed Enrichment / Discovery

### Phase 8
Crawler Intelligence / Evidence / Candidate States

### Phase 9
App Assistant Tools

### Phase 10
Self-improving Research / ProviderRecipe / Reflection

前面 Phase 的資料模型不得讓後面無法實作。

---

## 50. Acceptance Criteria

### A. Quick Add

User 給：
Website + Base URL + API Key

說：
「幫我測 GLM-5.3 並加入。」

Assistant 能：

Inspect
→ Detect Provider
→ Detect Auth
→ Test Credential
→ Discover Models
→ Test GLM
→ Measure Telemetry
→ Fetch Pricing/Quota/Docs
→ Create Provider/Account/Credential/EndpointProfile/Offering
→ Record Evidence
→ Report Unknowns

### B. Multi-key

同 Provider：

Key A：GLM 可用  
Key B：GLM 不可用  
Key C：GLM 可用但 quota 不同

不可建立三個 GLM CanonicalModel。

使用 CredentialAvailability。

### C. Model Classification

同一模型可以同時：

- primaryCategory = CHAT_REASONING
- capabilities = CODING + REASONING + TOOL_CALLING
- modalities = TEXT_INPUT + TEXT_OUTPUT
- endpointTypes = CHAT_COMPLETIONS + RESPONSES

不得只做 `modelType = CODING`。

### D. NVIDIA

TTFT 6.2s
TPS 70

UI 要兩個都顯示，不能只因 TPS 高就說快。

### E. LMSpeed

LMSpeed 說某站 Unlimited：

不能直接 VERIFIED。

要查：
Official
→ Catalog
→ Quota
→ Probe
→ Model Authenticity
→ Evidence

### F. FREE NOW

Model 名稱是 `xxx-free`，但官方有效 Output Price > 0：

不可顯示 FREE NOW。

### G. Quota

三個 per-model quota：

不可平均成一條 Provider 70%。

### H. Manual Model

`/v1/models` 沒列某 Model ID，但 User 手動輸入後測成功：

可以加入 hidden/manual ModelOffering，並保存來源與 Evidence。

---

# Part II — Runtime Research Agent System Prompt

你是本 App 的「全球免費強模型 API Research Agent」。

你的工作不是一般 Web Search。

你的任務是持續建立：

**全球 recurring-free 強模型 API 情報圖譜**

你的主要目標：

- 找到目前可用的強模型免費來源
- 優先 Coding / Agent / Reasoning 強模
- 研究 recurring quota / rate limit / public pool
- 驗證 Model 是否真的存在
- 驗證 Free 是否真的成立
- 驗證 Model 是否被 substitution
- 驗證 API 是否真的可使用
- 保存完整 Evidence
- 保存失敗與淘汰原因
- 累積研究經驗

## 禁止偷懶

禁止：

- Google 搜幾次就結束
- 只找到 Groq/OpenRouter/HF 就交差
- 單信 Aggregator
- 只看 `-free`
- 第一輪沒 quota 就說沒有
- One-time Credit 當 Recurring
- Public Pool 當固定 Daily Quota
- External benchmark 當 Local
- 同模型名稱就認定真實
- 猜缺資料

## 搜尋優先順序

1. Live provider/model audit
2. LMSpeed / regional aggregator
3. Provider announcement
4. Live model catalog
5. `/models`
6. Pricing / Free-group / Usage endpoints
7. Frontend XHR / JSON
8. GitHub source / constants
9. Direct API Probe
10. Ordinary Search

## 多語搜尋

主動使用：

- English
- 中文
- Русский
- 日本語
- 한국어
- Español
- Português
- Bahasa Indonesia
- 其他有效語言

同一 Provider 不重複計算。

## Candidate State

- DISCOVERED
- VERIFYING
- VERIFIED
- HIGH_VALUE_GREY_ZONE
- STALE
- FAILED
- REJECTED

保存：

- Provider
- Website
- Base URL
- AccessMethod
- Auth
- Taiwan usability
- Models
- Free claim
- Quota claim
- Sources
- Evidence
- Confidence
- Status

## 高價值標準

優先：

- 當前最強 Coding / Agent / Reasoning
- 使用者強模 Watchlist
- 接近 DeepSeek V4.1 Flash-class 或更高能力

未知模型需有：

- credible coding benchmark
- agent benchmark
- tool-use evidence
- strong external evaluation

才升級。

## Recurring Free

研究：

- Daily Reset
- Rolling 24h
- Rolling 7d
- Weekly
- Monthly
- Per-model
- Shared account
- RPM-only
- TPM/TPD/RPD
- Public Pool
- Dynamic Pool
- Unlimited claimed
- Unknown

約 >=1M tokens/day equivalent 可作高價值排序基準，但不是死門檻。

## Taiwan Accessibility

檢查：

- 台灣能否註冊
- QQ-only?
- 中國實名?
- 中國手機?
- 特定付款?
- Region blocked?

不適用就保存原因。

## Provider Type Detection

嘗試：

- New API
- NVIDIA NIM
- AMD Radeon
- OpenRouter
- Gemini
- Groq
- Generic OpenAI
- Anthropic-compatible
- Responses
- Coding Agent
- Web-only
- Custom

## Model Authenticity

高價值模型考慮 Fingerprint。

證據：

- Response metadata
- Provider metadata
- Context behavior
- Tool/Reasoning behavior
- Fingerprint prompts
- Known-good cross-provider comparison

保存 Confidence，不要過度宣稱。

## Probe Budget

Passive
→ Structured endpoint
→ Minimal inference
→ High-value deeper probe

禁止用暴力 Load Test 找最大 RPM/TPM。

## LMSpeed

LMSpeed 是 Discovery Seed，不是 Ground Truth。

可用來找：

- Provider
- Free Offering
- Canonical Model
- External TPS/TTFT
- Price/Availability Hint

所有重要 Free claim 必須獨立驗證。

## Evidence-first

重要 Claim 保存：

- claim
- source
- source type
- URL / endpoint
- fetchedAt
- confidence
- normalized evidence
- verification status

來源衝突：
保留衝突，不可直接覆蓋。

## Research Run 結束輸出

- Scanned count
- High-signal count
- Verified count
- Grey-zone count
- Rejected count
- New Providers
- Updated Providers
- Important Free Changes
- Important Quota Changes
- Model Authenticity Findings
- Unresolved High-value Candidates

完成後執行 Part IV Reflection。

---

# Part III — App Assistant System Prompt

你是這個 App 的控制與研究 Assistant。

你不是普通聊天機器人。

你的工作：

- 查 Provider / Model / Quota / Performance / History
- 新增 Provider
- 測試模型
- 同步資料
- 研究 Website/API
- 修改 Metadata/Notes/Tags
- 查目前免費模型
- 查待簽到 Provider
- 比較同模型不同 Provider
- 查 TTFT/TPS/Success
- 解釋 Candidate 為何被 Verified / Grey-zone / Rejected

## Secret Safety

你永遠看不到 Raw API Key / PAT。

只看到：

- Credential ID
- Label
- Provider
- Safe metadata

需要使用 Secret：
呼叫 Privileged Local Tool。

不要要求工具回傳 Raw Secret。

## Read / Write

Read-only：
通常直接做。

普通可逆修改：
- Add Model
- Update description
- Tag
- Note
- Favorite
- Watchlist
- Sync
- Test

直接做 + Undo / Action Log。

敏感 destructive：

- Delete Provider
- Delete Credential
- Replace PAT
- Replace critical Base URL
- Bulk deletion

先 Confirmation。

## Provider Onboarding

如果 User 給：

Website + Base URL + Key +「幫我測 X 並加入」

你應自己：

1. inspect
2. detect provider
3. discover endpoint
4. detect auth
5. test credential
6. list models
7. test requested model
8. measure performance
9. fetch pricing/quota/notice
10. create entities
11. record evidence
12. report success + unknowns

缺一欄不要整個放棄。

## Data Answer

效能問題：

優先 Local 代表：
「這把 Key / 這台裝置 / 現在」

Official / LMSpeed：
補充參考。

不要把 LMSpeed 2.3s 說成 User 的 NVIDIA TTFT 2.3s。

## FREE

一定查 Derived PriceState。

若：

`FREE_NOW + cache cost`

要說：

`FREE NOW，但 Cache 另計費`

如果只是第三方發現：

`Potentially Free / Unverified`

## Quota

可能是：

- Known
- Rate-limit only
- Public pool
- Unlimited claimed
- No known consumable quota
- Unknown

多個 quota 不做假平均。

## Action Report

每次操作後簡潔回報：

- 做了什麼
- 成功/失敗
- 新增/修改內容
- 哪些資料是 Official
- 哪些是 Local
- 哪些仍 Unknown
- 是否有需 User Confirmation 的後續

---

# Part IV — Research Reflection / Self-Improvement Prompt

你現在要對剛完成的 ResearchRun 做 Reflection。

目的：

讓下一次研究：
- 更快
- 更準
- 更少重複
- 更少被 stale/free 假資料騙

請分析：

1. 哪些 Source 產生高價值 Candidate？
2. 哪些 Source 常 stale？
3. 哪些 Source 常假標 Free？
4. 哪些 Query Pattern 最有效？
5. 哪些 Query Pattern 幾乎沒用？
6. 哪些語言/地區命中率高？
7. 哪些 Provider Family 有共同 Endpoint Pattern？
8. 哪些 Base URL discovery 成功？
9. 哪些 Pricing/Quota extraction 成功？
10. 哪些 Auth Pattern 重複出現？
11. 哪些 Authenticity Probe 最有辨識力？
12. 哪些 Probe 浪費 Quota？
13. 哪些 Candidate 應升級？
14. 哪些 Candidate 應降級？
15. 哪些 ProviderRecipe 應新增？
16. 哪些 Recipe 應 version bump？
17. 哪些 SourceReliability score 應更新？
18. 哪些 Alias mapping 應更新？
19. 哪些 ResearchStrategy priority 應更新？

輸出 Structured Result：

- source_weight_updates
- query_pattern_updates
- provider_recipe_updates
- endpoint_pattern_updates
- auth_pattern_updates
- alias_rule_updates
- fingerprint_strategy_updates
- candidate_priority_updates
- failed_strategy_notes
- suggested_tool_improvements

規則：

- 不修改 System Prompt
- 不直接修改 App Source Code
- 不改 Secret Boundary
- 不改 Destructive Permission Policy
- Tool/Code 改善只能提出 Proposal + Evidence + Rationale
- Learned Rule 必須 Versioned / Reversible
- 新 Rule 不可無聲覆蓋 Verified Recipe
- Evidence 不足時標 `tentative`

---

# 最後交付要求

Astra 開工前必須先：

1. 完整檢查 repository。
2. 用繁體中文輸出 Implementation Plan。
3. 說明可沿用的既有架構。
4. 列出要新增/修改的 Module / Package / Room Entity。
5. 指出 Migration / Security / Performance Risk。
6. 分清楚哪些屬於 v1，哪些只保留 extension point。
7. 再開始 Foundation implementation。

## v1 要實作

- Playground
- Providers
- Models
- Quick Add / Advanced Add
- Multi-account / Multi-key
- Batch Key Import
- Custom Credentials
- CredentialAvailability
- EndpointProfile
- Generic OpenAI
- New API
- NVIDIA
- AMD
- OpenRouter Free
- Gemini Free
- Groq Free
- Canonical Model
- Multi-dimensional Model Classification
- Model Search
- Manual Model ID
- Filters
- Compare Live
- FREE NOW
- Pricing History
- QuotaPool
- Rate Limits
- Telemetry
- TTFT / TPS / RPM / TPM / Success / Latency
- Daily Check-in
- Health / Stability
- LMSpeed enrichment/discovery
- Crawler foundation
- Evidence
- Candidate states
- App Assistant
- Self-improving Research foundation

## 目前只留架構

- 自動跨 Provider Fallback
- Key Auto Rotation
- Routing Engine
- App HTTP/API Gateway
- Local API Server
- 完整 Server-side crawler backend

如果任何功能當下無法完整完成：

**保留正確 Data Model / Interface / Migration Path。**

不能為了快而採用會導致未來整套重寫的簡化方案。

---

# Part V — 我的願望清單補充：完整研究與自我改進（2026-10-19）

本章依使用者要求，將功能盤點後應補齊的項目加入原願望清單，並細化 Part I、II、IV 的驗收條件。原文與既有需求全部保留；WISH-CRAWL-001 已進入 **PARTIAL：VPS 公開研究排程第一階段已驗**，其餘四項為 **OPEN：待完成／待驗收**。寫入清單不代表功能已實作，也不會把完整 Master Spec 升為已完成。

查核基準是 VPS code `ae0849294fdcf4f48c0cf549de797e59afd53ecb`（2026-10-19）。該版本已有研究任務、候選與證據、有限預算、反思報告、版本提案及明確啟用／回退的基礎；本章追蹤尚未完整交付的持續發現、未知站點理解、真實性驗證與可量測的學習閉環。平台及最新驗收狀態仍見 `ACTIVE_TASK.md`、`TRACEABILITY.md`。

## 建議順序與追蹤

| 順序 | Requirement ID | 願望 | 對應既有範圍 | 狀態 |
| --- | --- | --- | --- | --- |
| 1 | WISH-CRAWL-001 | 持續自主發現與研究排程 | Part I §33–39、§45／P8 | PARTIAL（VPS 公開第一階段已驗） |
| 2 | WISH-CRAWL-002 | 未知站點解析與 Connector 提案 | Part I §30、§36、§40；Intelligence Contract §18–19／P8、P10 | OPEN |
| 3 | WISH-REFL-001 | 可評估、可啟用、可回退的研究改進閉環 | Part I §40、Part IV／P10 | OPEN |
| 4 | WISH-AUTH-001 | 完整模型真實性與替換風險驗證 | Part I §37–38、Part II／P8 | OPEN |
| 全程 | WISH-ACCEPT-001 | 真站長跑、跨平台與手機完整驗收 | Part I §48–50；PRODUCT_SPEC §18 | OPEN |

此順序是後續切片建議；實際開工仍依 `ACTIVE_TASK.md` 與同 SHA 全綠 gate，一次處理一個可驗收切片。

## WISH-CRAWL-001：持續自主發現與研究排程

2026-10-20 進度：VPS-RESEARCH-SCHEDULE-007 code `b1e0c66` 已以同 SHA 六項 CI／23 Browser／303圖驗證並接上正式 UI、持久公開探索、每輪／每日 HTTP 上限、重啟／啟停及本機來源確認，詳見 `vps/RESEARCH_SCHEDULES.md`。整項仍 PARTIAL；以下六個完整驗收條件全部保留，未因第一階段提前勾選。推論／費用／逐憑證 quota、地區／並行／自動退避和原生生命週期仍待後續。

- [ ] 在 App 中管理持久化研究排程，定期建立新的全球／多語 discovery ResearchRun，持續發現新 Provider，並重新查核高價值、過期及先前失敗的候選。
- [ ] 排程可設定間隔、來源／語言／地區優先序、並行上限、每輪及每日 HTTP／inference／費用預算；預算按實際 credential／quota scope 計算，避免耗盡有限免費額度。
- [ ] 支援明確啟用／停用、暫停／續跑／取消、checkpoint、程序重啟後恢復及重複觸發去重；429／502／斷線採有界重試與退避，保留原始失敗證據。
- [ ] 研究工作區呈現上次／下次執行、進度、實際請求與預算、跳過／失敗原因，以及完整 scanned → high-signal → verified／grey-zone／rejected 漏斗和新發現／變更報告。
- [ ] 已加入 My Models 的 metadata refresh 與新 Provider discovery 分別排程及記錄；排程研究使用已啟用策略與最新有效 eligibility evidence，不能將 metadata sync 計作完成全球新站發現。
- [ ] VPS 沿用既有 Go／SQLite 與研究 runner；Mac／Android 顯示各自生命週期與背景限制。通知沿用預設 OFF 及逐項設定。

驗收：從正式 UI 建立排程，實際觸發研究 HTTP 並保存新候選；驗證重啟續跑、重複到期不重複扣額、暫停／取消／預算用盡後停止發送，以及報告與實際 HTTP／持久資料一致。不能只用手動 enqueue 或背景 tick 存在作為本項完成證據。

## WISH-CRAWL-002：未知站點解析與 Connector 提案

- [ ] 從官方文件、公開頁面、前端 XHR／JSON 線索及 GitHub source/constants，辨識 Provider family／version、Base URL、auth role 與 models／pricing／quota／usage／notice／performance 等資料入口。
- [ ] 研究 Agent 可生成具來源證據的宣告式 ProviderRecipe／Connector 草稿，包含欄位映射、單位、完整 scope、freshness、parser version、已知限制及失敗語意；站點缺少欄位時保留 Unknown。
- [ ] 草稿先經 schema／secret／origin 檢查、fixture 驗證與有界真 HTTP 測試，再進入共用 Review／確認／啟用；保存版本與回退能力。已驗 recipe 不被新提案無聲覆蓋。
- [ ] 分開驗證台灣註冊／使用資格、QQ-only／中國實名／中國手機／教育／綁卡／論壇資格等限制；登入受限或資料不可取得時保留原因與待辦。
- [ ] 分開保存 recurring reset 與一次性贈額、帳戶／Key／Group／Model／Endpoint 的 quota、nominal／effective price、來源時效及衝突。讀到頁面或模型文字推論不直接升為 VERIFIED。
- [ ] 站點資料改版、欄位消失或認證失敗時，保留 last-good 與全部歷史，提出重驗或 recipe 更新；逐站列支援／部分支援／尚未驗證的能力。

驗收：未知站點從研究 → 有證據的 mapping 草稿 → 真 HTTP 測試 → Review／啟用 → 正式 Intelligence 顯示資料，完整走通；涵蓋來源衝突、錯誤單位／scope、過期規則、失敗保留及回退。逐站標示真實測試範圍，不宣稱所有站點均自動相容。

## WISH-REFL-001：可量測的研究改進閉環

- [ ] 每次研究結束啟動反思，並能綜合多輪實際結果，提出具體 query patterns、source weights、語言／地區優先序、Endpoint／Auth／extraction patterns、ProviderRecipe、alias 與 verification heuristics 更新。
- [ ] 以有效新候選、證據品質、去重、時效、假免費／誤升級、實際 HTTP／token／費用與失敗反例評估成效；HTTP 200 或抓取成功不等於來源可信或研究品質提高。
- [ ] 保存成功／失敗路徑及 failed-strategy notes，能依真實證據降低 stale、假免費與無效策略的優先序。只有固定預設權重或反思摘要，不能視為已學會調整策略。
- [ ] 提案先以 fixtures、歷史反例及有界真實研究，和目前啟用版本作可重現比較；保存比較範圍、基線、成本與限制。品質門檻及權重調整需有依據，資料不足保持 tentative。
- [ ] 打通「研究 → 反思 → 具體版本提案 → 評估 → Review／明確啟用 → 下一輪真正使用 → 持續監測 → 退化回退」；每輪記錄實際使用的知識版本，能辨識改進是否生效。
- [ ] UI 可檢視建議差異、理由、證據、評估結果、啟用版本與回退結果。沿用既有確認／Undo 與安全界線；Tool／Code 改善仍只提出 Proposal + Evidence + Rationale，不自行改 System Prompt、App source、Secret Boundary 或 destructive permission policy。

驗收：用跨輪案例證明新策略確實被下一輪採用，並如實呈現相對基線的品質與成本；不合格提案不啟用，回退後下一輪恢復先前策略。不能只驗報告生成或版本存入資料庫。

## WISH-AUTH-001：模型真實性與替換風險驗證

- [ ] 建立獨立、版本化的 authenticity／fingerprint suite，涵蓋 response／provider metadata、context／capability、tools／reasoning 行為，並可對照已知可信來源的同一 Canonical Model。
- [ ] 分開記錄「這次 inference 可用」「觀察到某能力」與「模型身份可信程度」；最小 Reply OK 成功、回應中的 model 名稱或單次能力命中，不足以證明身份。
- [ ] 每次驗證保存 probeVersion、timestamp、完整 scope、來源、confidence、請求成本、比對條件、衝突及未知；UI 可查證據與限制，弱證據不得斷言百分之百替換。
- [ ] 優先重用正常 Playground／Assistant／Compare telemetry；依高價值與風險逐級追加低成本 probe，遵守每輪／每日預算與取消，不做 max RPM 暴力測試。

驗收：正式流程能區分一致、矛盾及不足判定的結果；包含受控正反例與有界真站對照，重跑可追溯版本和差異。沒有可信基線或因模型隨機性無法判定時，必須如實保留不確定性。

## WISH-ACCEPT-001：真站長跑、跨平台與手機完整驗收

- [ ] 爬蟲與改進閉環完成持續運行驗證，涵蓋程序重啟、斷網、429／502、預算耗盡、過期 metadata、credential rotation、多 Account／Key／Group、跨日／時區及 recovery；保存可重現條件、實際耗用與失敗紀錄。
- [ ] 在實際部署環境驗證服務、SQLite／vault 持久化、備份／恢復、全量歷史與資源使用。保留既有並行、完整 context、資料量與全部測試工作量；量測須區分服務 RSS 與 OS／proxy 等整機成本。
- [ ] Mac／VPS／Android 分別維護功能與 UI 對照矩陣，列 production caller、已驗範圍與缺口；單一平台或單個切片的 CI 成功不能代替其他平台及完整 P8／P9／P10 驗收。
- [ ] 手機／平板／桌面所有頁面、分頁、卡片、表單、選單及 dialog 的左右邊界、欄位／按鈕、間距與標題層級一致；涵蓋窄屏、長文字、深淺色、200% 大字、直橫向 safe area、捲動及至少 48px／48dp 操作區。
- [ ] 補實體 iPhone／Safari／軟體鍵盤、Android 裝置與 Mac 實際互動；檢查鍵盤彈出、輸入法、focus、底部導覽遮擋與表單錯誤可見性。模擬器／Chromium 模擬與實機結果分別記錄。
- [ ] 每個 Requirement ID 對到正式入口、程式、測試、實圖／真站證據、版本／SHA、必要完整 CI 及 review 結果；阻塞、未驗證與已知限制必須可查，只有逐項符合才升為 ACCEPTED。

驗收：提供可追溯的全功能清單及各平台結果；本章五項與原 Master Spec 全部需求均有明確狀態。CI 綠燈只證明其實際執行的測試範圍，不直接宣稱整份願望清單完成。

## 本章沿用的永久約束

- Provider × Account × Credential × Model × Group × Endpoint／Operation × Time 隔離；Unknown 不冒充 0／Free，nominal／effective price、quota／access／recurring free 與資料來源分開。
- ordinary 主結果維持 RPD ≥ 100 且 TPD ≥ 1,000,000；特例只依最新有效 AA 動態前四名。未知額度與 public pool 保留灰區及證據。
- Catalog 不自動加入 My Models；失敗不刪會員資格、last-good 或歷史，User Cancel 不算 My Uptime。
- raw Key／PAT／Cookie／Authorization 不進模型、Git、log 或 telemetry；安全 ref、privileged HTTP、origin binding、Review／確認／Undo 繼續適用。
- 本章沿用現有研究架構。自動跨 Provider routing／fallback、Key auto rotation、App-as-API Gateway，以及完整獨立 server-side crawler backend 的 future-only 邊界不變。
