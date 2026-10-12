# Phase 3 Provider Onboarding 契約（Quick Add / Advanced Add）

> 範圍：Phase 3 Providers onboarding。首條 slice 僅提供安全手動連線（`saveConnection`），本文件定義未來 Quick Add / Advanced Add 的完整契約，現階段不實作、不冒稱已完成。
> 語言：繁體中文；識別字一律英文（`QuickAdd`、`AdvancedAdd`、`OnboardingWorkflow`、`EndpointProfile` 等）。
> 上游契約：`docs/IMPLEMENTATION_PLAN.md`、首條 slice `docs/UI_CONTRACT.md`、`docs/STORAGE_CONTRACT.md`。

## 0. 目的、非目標、共通原則

### 0.1 目的
- 定義兩個明確且互不混淆的 onboarding flow：`QuickAdd`（最少輸入 + 自動探測 + Review）與 `AdvancedAdd`（完整手動欄位 + 可選探測輔助）。
- 定義可恢復、可重入的 `OnboardingWorkflow` state machine，未來人類 UI 與 Assistant 共用同一 workflow（Assistant 透過 structured tool 呼叫，不另起旁路）。
- 定義失敗分類、quota 保護、部分成功語意，以及與首條 slice `saveConnection` 的接軌點。

### 0.2 非目標（Phase 3 不做）
- 不做 Routing / Fallback / Key auto rotation / HTTP gateway / Local API server / 完整 remote crawler（見 IMPLEMENTATION_PLAN「未來僅留契約」）。
- 不做 batch import（獨立 story，沿用本文件的 `OnboardingWorkflow` 逐筆執行）。
- 不擅自決定通知預設開關與 retention 天數。
- 不把 mock / 測試 fixture / 第三方 benchmark / 名稱關鍵字當成產品資料。

### 0.3 共通原則（不可簡化）
1. `Unknown` 不猜、不等於 `0`、不等於免費。一次 sync / probe failure 不刪模型、不清空 last-good facts。
2. 原始 Key / PAT 不進 prompt、repo、log、telemetry、LLM context；持久化只保留 safe credential refs（`safeCredentialId` / `credentialId` / label），原始值只存 Android Keystore-backed vault，且成功後清空 UI 記憶體（`remember`，不用 `rememberSaveable`，無 Bundle 殘留）。
3. 所有 inference（含 `FREE_NOW`、含探測用的 test model call、含失敗 / 取消）都走完整 inference / usage / performance pipeline，寫 Room telemetry；零價格沒有 bypass。
4. 定價隔離：PricingScope superset = Provider × Account / Credential × Model × Group × Endpoint × Operation × Time。不同 Key、Endpoint、Operation 不互相覆蓋。
5. 分類多維：`primaryCategory` + `capabilities[]` + `modalities[]` + `endpointTypes[]`，不得只用單一 `modelType`。合法值見 IMPLEMENTATION_PLAN §分類與 quota 的精確值。
6. 健康雙軌：`officialHealth` 與 `observedHealth` 分離，值域 `HEALTHY / DEGRADED / UNSTABLE / OFFLINE / UNKNOWN`。
7. `FREE` 判定不依 model id；無可靠價格一律 `UNKNOWN`；`cache costs extra` 獨立呈現；一次性贈金不是 recurring free。
8. HTTP redirect 不轉送憑證；錯誤訊息不含 URL / key / server body；密碼欄位遮罩、無回顯。

---

## 1. Quick Add（快速新增）

### 1.1 最少輸入
| 欄位（識別字） | 型別 | 必填 | 說明 |
|---|---|---|---|
| `baseUrl` | String (HTTPS URL) | 必填 | 推理基址，例如 `https://api.example.com/v1`。僅接受 HTTPS；尾端 `/` 正規化；不接受 query；strip 後為空則 `INPUT_INVALID`。 |
| `websiteUrl` | String (HTTPS URL) | 選填 | 供應商官網，用於 notice / check-in 發現與 evidence 記錄，不直接用於推理。若缺失則 notice 步驟標 `SKIPPED`，不阻擋全流程。 |
| `apiKey` | Secret (transient) | 選填但多數必填 | 若偵測到需 auth 的型別卻缺 key，則停在 `NEEDS_INPUT` 而非直接失敗全流程。UI 輸入後只傳 `safeCredentialId`，成功後清空。 |
| `suggestedModelId` | String | 選填 | 使用者猜測的 model ID。若 `list models` 失敗或不支援，該 ID 作為 manual fallback 的候選；不信任其價格 / capability。 |

- 不收集：`name` 預設由 host 自動產生（可於 Review 改名）、`adapterType`（自動偵測，不開放 override；要 override 請用 Advanced Add）、`group`、`tags`、`notes`（Review 可加一組最小 notes，完整編輯屬 Advanced）。
- `baseUrl` 與 `websiteUrl` 分離：前者是機器呼叫位址，後者是人類查證位址，不互相推導、不拼湊路徑。

### 1.2 探測步驟序（固定，不可調換）
順序為：`inspect` → `detectType` → `auth` → `credentialTest` → `listModels` → `testModel` → `pricingQuotaNotice` → `review` → `commit`。

| 順序 | Step ID | 目的 | 是否需 credential | 失敗時語意 |
|---|---|---|---|---|
| 1 | `inspect` | 可達性 + TLS + HTTPS 檢查 + server 指紋摘要（不存敏感 header） | 否 | 失敗則 workflow `BLOCKED`（後續全 `SKIPPED`），唯一整體阻擋點之一。 |
| 2 | `detectType` | 偵測 adapter type：`OPENAI_COMPATIBLE` / `ANTHROPIC_MESSAGES` / `GEMINI_NATIVE` / `GENERIC_CUSTOM` / `UNKNOWN` | 否 | 永不硬猜；無把握即 `UNKNOWN` + `confidence=LOW`，流程繼續。 |
| 3 | `auth` | 依 `detectType` + 有無 key 決定 `authMode`（`NONE` / `API_KEY` / `BEARER`，Quick Add 不開放 `CUSTOM_HEADER` / `PAT_ACCOUNT`） | 視情況 | 缺 key 需 key → `NEEDS_INPUT`（可恢復），不是 `FAILED`。 |
| 4 | `credentialTest` | 最小成本已認證呼叫（優先 `GET {baseUrl}/models`；無該 API 則 1 次最小 `testModel` 代替）。只記狀態碼分級，不存 body / secret。 | 是 | `401/403` → `AUTH_FAILED`，停留本步，可換 key 重試；不自動重試消耗 quota。 |
| 5 | `listModels` | 列舉 catalog（若 adapter 支援）。輸出 `modelsFound`（原始數量）與 `modelsFoundIds`（截斷保存，截斷需標示）。 | 是 | 不支援 / 失敗 → `SKIPPED` 或 `PARTIAL`，改走 `suggestedModelId` / 手動單 ID 路徑，不整單放棄。 |
| 6 | `testModel` | 對至多 N 個模型各做 1 次最小 inference（例如 `max_tokens=1` / `hi`），驗證可用性並產生 telemetry + latency 指標。 | 是 | 單模型失敗只記該模型 `FAILED`，不否決其他模型；全部失敗則整步 `FAILED` 但仍可進 Review（顯示 0 verified）。 |
| 7 | `pricingQuotaNotice` | 查官方定價（若 adapter 宣告）、推導 `QuotaState`、抓 `notice` / `checkinUrl` / `requiresDailyCheckin`。 | 是（定價）/ 否（notice） | 查無 → 全 `UNKNOWN` + evidence，不阻擋 commit。 |
| 8 | `review` | 人類（或 Assistant + 人類確認）檢查 Review Step（見 1.3），可改名、刪減 models、補 `suggestedModelId`、放棄或 commit。 | — | 使用者拒絕 → `ABANDONED`；確認 → `commit`。 |
| 9 | `commit` | 寫入 Provider / Account / Credential(metadata) / EndpointProfile / Offering / Availability / Snapshots（見 §6 接軌）。 | — | 採 transaction（見 STORAGE_CONTRACT 增補：`ABORT`，禁止靜默部分成功）；失敗可重放 `commit`（冪等，見 §3.4）。 |

每步產出 `StepResult { status, evidenceRef, confidence, quotaCost, finishedAt }`，全部可恢復（見 §3）。

### 1.3 Review Step 必顯欄位（缺一不可，未知亦須明示）
Review 畫面必須同時顯示以下欄位；無資料時顯示 `Unknown`（見 §9），不得隱藏列：
1. `providerType`（偵測結果：`OPENAI_COMPATIBLE` 等或 `UNKNOWN`）+ 偵測依據摘要。
2. `auth`（實際採用的 `authMode` + credential label + `credentialTest` 結果：成功 / 失敗原因分類）。
3. `modelsFound`（`listModels` 原始數量；不支援列舉則明示「不支援列舉」而非 `0`）與 `modelsVerified`（`testModel` 成功的數量與 ID 清單）。
4. `pricingStatus`（`VERIFIED_FREE` / `VERIFIED_PAID` / `MIXED` / `UNKNOWN`；Quick Add 不顯示金額明細，只顯示狀態；金額明細屬 Advanced 手動 pricing metadata）。
5. `quotaStatus`（`QuotaState` 八值之一：`QUOTA_AVAILABLE / QUOTA_LOW / QUOTA_EXHAUSTED / RATE_LIMIT_ONLY / PUBLIC_POOL / UNLIMITED_CLAIMED / NO_KNOWN_CONSUMABLE_QUOTA / UNKNOWN`）。
6. `health`（`officialHealth` 與 `observedHealth` 雙欄；初始多為 `UNKNOWN / UNKNOWN`，不得合併為單一燈號）。
7. `unknownFields`（本單缺了什麼：例如 pricing source 缺、quota 證據缺、catalog 不支援、notice 缺；以清單明示）。
8. `evidenceConfidence`（每項結論的 `confidence: HIGH / MEDIUM / LOW / UNKNOWN` + evidence 連結 / 摘要 ID；人工手填一律 `LOW`；自動偵測無把握一律 `LOW` 或 `UNKNOWN`）。

另須顯示：探測成本摘要（用了幾次 request、是否觸發 429）、`checkinUrl` / `requiresDailyCheckin`（若有）、下一步按鈕（`Commit` / `Edit & Re-run` / `Abandon`）。

### 1.4 缺欄位不整個放棄、部分成功語意
- 原則：除 `inspect` 不可達與 `commit` 前零可用模型（既無 found 又無 manual）外，任何單步缺資料都不整單放棄。
- `StepResult.status` 四值：`SUCCESS` / `PARTIAL`（部分模型成功、部分定價缺）/ `SKIPPED`（不適用，例如無 website 故 skip notice）/ `FAILED`（該步無可用輸出）。
- Workflow 整體狀態四值：
  - `READY`：全部關鍵步驟 `SUCCESS`，可直接 commit。
  - `READY_WITH_WARNINGS`：有 `PARTIAL / SKIPPED / FAILED` 但仍有 ≥1 可用模型且 auth 通過；Review 以警告橫幅列出缺口，仍可 commit。
  - `NEEDS_INPUT`：缺 key / 缺 manual model ID，等使用者補；保留已完成步驟，不重跑。
  - `BLOCKED`：`inspect` 失敗或 auth 明確被拒且無替代路徑；只允許 `Edit & Re-run` 或 `Abandon`。
- 部分成功寫入語意：commit 時只寫入已驗證輸出；`FAILED` 步驟不寫反向事實（例如 pricing 查無不寫 `price=0`，寫 `priceState=UNKNOWN` + evidence）；`listModels` 失敗不刪既有 Offering（本單新建則為 0 offering + 1 manual offering 若使用者有補 ID）。

---

## 2. Advanced Add（進階新增）

### 2.1 完整欄位清單
| 欄位（識別字） | 型別 | 必填 | 說明 / 驗證 |
|---|---|---|---|
| `name` | String | 必填 | 人類可讀名稱；預設建議 `{host} ({adapterType})`，可改；重名需在 Review 提示。 |
| `websiteUrl` | HTTPS URL | 選填 | 同 Quick Add；用於 evidence / notice。 |
| `baseUrl` | HTTPS URL | 必填 | 同 Quick Add；所有 `EndpointProfile.url` 若為相對路徑則以此為 base join。 |
| `adapterTypeOverride` | Enum | 選填 | `OPENAI_COMPATIBLE / ANTHROPIC_MESSAGES / GEMINI_NATIVE / GENERIC_CUSTOM`；若填則跳過 `detectType` 自動值（仍保留自動值作 evidence 對照）；若不填則沿用自動偵測。 |
| `authMode` | Enum | 必填 | `NONE / API_KEY / BEARER / CUSTOM_HEADER / PAT_ACCOUNT`（見 2.2）。預設 `BEARER`；`NONE` 需二次確認。 |
| `credentials[]` | List<CredentialInput> | 依 `authMode` | 多憑證。每筆 `{ label, secretTransient, accountRef? }`；`label` 必填且單 provider 內唯一；原始值 transient，僅回傳 `safeCredentialId`。`NONE` 時為空陣列。 |
| `customAuthFields[]` | List<{ location, name, valueTransient }> | `CUSTOM_HEADER` 必填，其餘禁用 | `location: HEADER / QUERY`；`name` 需符合 token / query-key 規則，禁止覆寫 `Authorization`（除非 `authMode=CUSTOM_HEADER` 且使用者明確確認）、禁止 `Cookie`；`value` transient。範例：`HEADER X-Api-Key`、`QUERY api_key`。 |
| `endpointProfiles[]` | List<EndpointProfile> | ≥1 筆必填 | 每筆 `{ endpointType, url, protocol, enabled }`。`endpointType ∈ CHAT_COMPLETIONS / RESPONSES / ANTHROPIC_MESSAGES / GEMINI_NATIVE / IMAGES_GENERATION / IMAGES_EDIT / AUDIO / EMBEDDINGS / RERANK / CUSTOM`；`protocol` 由 core strict endpoint policy 決定（例如 `CHAT_COMPLETIONS`，不是 `HTTPS`）；`url` 允許絕對或相對路徑；HTTPS only；redirect 不帶憑證。至少 1 筆 `enabled=true`。 |
| `group` | String | 選填 | 價格 / 配額分組鍵（對應 PricingScope 的 Group 維度）；空字串視為無分組，不與具名分組混用。 |
| `manualModelIds[]` | List<String> | 與探測結果二選一必填 | 手動 model ID 清單；去重、trim；每筆標 `source=USER_MANUAL`、`verified=false`（除非後續 `testModel` 成功）；不宣稱已驗證、不推導價格 / capability。 |
| `manualPricing[]` | List<PricingMetadata> | 選填 | 每筆 `{ scope (model/group/endpoint/operation), decimalPrice, currency, perUnit, effectiveFrom, source=USER_MANUAL, note }`；金額用精確 decimal 字串；`confidence` 恆為 `LOW`；與自動探測價格分開存放、不互相覆蓋；缺則該 scope `UNKNOWN`。 |
| `manualQuota` | QuotaState + note | 選填 | 手填 `QuotaState` 八值之一 + 文字依據；無依據不得填 `QUOTA_AVAILABLE`，應填 `UNKNOWN`。 |
| `manualClassification` | Object | 選填 | `{ primaryCategory, capabilities[], modalities[], endpointTypes[] }`；供 catalog / UI 初始顯示；`confidence=LOW` 直到 verification 更新。 |
| `notes` | String | 選填 | 本機備註；禁止貼入 secret；僅本機存放。 |
| `checkinUrl` | HTTPS URL | 選填 | 每日簽到 URL；有則 `requiresDailyCheckin=true`（按裝置本地日期，見 STORAGE_CONTRACT `completeCheckin`）；開啟用外部 Intent。 |
| `tags[]` | List<String> | 選填 | 自由標籤；正規化小寫、去重；僅本機檢索用，不進 pricing scope。 |

### 2.2 `authMode` 定義
- `NONE`：無憑證。僅允許使用者二次確認後 commit；`credentials[]` 必須為空；`credentialTest` 改為匿名可達測試。
- `API_KEY`：供應商定義的 key 模式（如 `x-api-key` header 或 `api_key` query）。實際 header / query 名寫在 `customAuthFields[]`（單筆），本 mode 只是語意標記。
- `BEARER`：`Authorization: Bearer <secret>`；secret transient。
- `CUSTOM_HEADER`：完全自訂 header / query 組合（`customAuthFields[]` ≥1 筆）；Review 必須回顯欄位名（不回顯值）。
- `PAT_ACCOUNT`：帳號級 PAT + 推理 credential 分離（Phase 4 前導）。本 Phase 只收 metadata（帳號名、PAT transient → vault + `safeCredentialId`），不拿 PAT 直接做 inference；inference 仍用 `credentials[]`。UI 必須區分「帳號憑證」與「推理憑證」兩區。

### 2.3 Advanced 流程差異
- 預設跳過自動探測，改為「填表 → 可選單步探測（`Run detection` / `Test selected` 按鈕）→ Review → commit」。任何自動值只填入建議、不覆寫手填（手填優先，自動值留 evidence 對照）。
- `endpointProfiles[]` 多型別可逐筆 `Test`（受 §5 bulk 上限約束）；`manualModelIds[]` 可逐筆打勾納入 `testModel`。
- Review 必顯欄位同 §1.3，外加：`adapterTypeOverride` 對照（自動值 vs 手填值）、`customAuthFields` 欄位名回顯、`manualPricing` 的 `LOW` confidence 標示、`group` 與 scope 說明。

---

## 3. 可恢復 `OnboardingWorkflow` State Machine

### 3.1 狀態圖（文字）
```
DRAFT
 → INSPECTING → TYPE_DETECTED → AUTH_CHECKED → CREDENTIAL_TESTED
 → MODELS_LISTED → MODELS_TESTED → PRICING_QUOTA_NOTICE_DONE
 → REVIEW → COMMITTED
                        ↘ ABANDONED（任何狀態可放棄，需確認）
```
- 每個箭頭對應一個 step 完成事件；任何狀態可轉 `NEEDS_INPUT`（等輸入）或 `BLOCKED`（不可繼續）；`REVIEW` 可退回任一先前狀態（`EDIT_INPUT_AND_RERUN_FROM_STEP`）。
- Quick Add 按序全跑；Advanced Add 允許從 `DRAFT` 直跳 `REVIEW`（未跑的 steps 記 `SKIPPED` + 理由）。

### 3.2 持久化與恢復
- `OnboardingWorkflow { workflowId, flowType: QUICK / ADVANCED, inputs (非 secret), stepResults[], safeCredentialIds[], createdAt, updatedAt, expiresAt }`。原始 secret 永不進 workflow snapshot；snapshot 只存 `safeCredentialId`。
- 每步完成即寫一次 snapshot（Room，`exportSchema=true`，不 destructive migration）。App 被殺 / 進程中斷後，下次開啟 Providers 即提示 `Resume onboarding?`，從最後已完成步的下一步繼續，不重跑已成功步驟（除非使用者要求）。
- `single-flight`：同一 `workflowId` 同時只跑一個 step；重入呼叫直接回傳現行狀態，不開第二份探測。
- 可取消：任一探測 step 支援取消（中止 socket，不盲目重試）；取消後狀態停留該步開頭，可重試或跳過。

### 3.3 Assistant 共用同一 Workflow
- 未來 Assistant（Phase 9）不得另起旁路，必須呼叫同一 workflow 的 structured tools：
  - `onboarding.start(flowType, inputs)`、`onboarding.resume(workflowId)`、`onboarding.retryStep(workflowId, step)`、`onboarding.skipStep(workflowId, step, reason)`、`onboarding.commit(workflowId)`。
- 安全邊界：tool args 只接受非 secret 輸入 + `safeCredentialId`；若使用者在對話中貼出原始 key，該 key 標 `transient`、只走 vault 寫入路徑，不進 ActionLog / LLM context 持久化，並在成功後要求使用者撤回 / 以 UI 重新輸入。destructive 動作（`commit` 覆寫既有、`abandon` 丟棄已驗證結果）需人類確認 + ActionLog + Undo（沿用 Phase 9 Undo / action log 契約）。
- Assistant 不得替使用者省略 Review：必須先回傳 Review 摘要（含 §1.3 八項 + Unknown 清單），取得明確確認後才可 `commit`。

### 3.4 冪等與重放
- `commit` 冪等：同一 `workflowId` 重放 `commit` 不產生重複 Provider（以 `workflowId` 去重鍵 + transaction `ABORT` 語意；Room 參照 STORAGE_CONTRACT `addConnection` 之 transaction 模式延伸）。
- Step 重試冪等：重跑某步先作廢該步舊 `StepResult`（標 `SUPERSEDED`），不刪除其他步結果；下游 steps 若依賴該步輸出則標 `STALE` 並提示重跑。

---

## 4. 失敗分類

| 分類（識別字） | 典型訊號 | 可重試 | 阻擋語意 | 使用者動作 |
|---|---|---|---|---|
| `INPUT_INVALID` | 非 HTTPS、空 ID、非法 header 名 | 否（先改輸入） | 該步 `FAILED`，workflow `NEEDS_INPUT` | 修正欄位後 `EDIT_INPUT_AND_RERUN_FROM_STEP` |
| `NETWORK_UNREACHABLE` / `TLS_FAILED` / `TIMEOUT` | DNS / 連線拒絕 / TLS / 逾時 | 是（1 次手動重試，不自動連打） | `inspect` 失敗 → `BLOCKED`；他步失敗 → 該步 `FAILED` | 檢查 URL / 網路後重試 |
| `AUTH_FAILED_401_403` | 401 / 403 | 否（換 key，不是重打） | 停留 `credentialTest`，`NEEDS_INPUT` | 換 key 或改 `authMode` |
| `RATE_LIMITED_429` | 429 | 否（立即停手，退避；不盲目重試消耗 quota） | 該步 `PARTIAL`，記 `RATE_LIMIT_ONLY` 候選 | 稍後重試單步 |
| `QUOTA_EXHAUSTED` | 402 / 明確額度用盡分級 | 否 | 該模型 `FAILED`，quota 記 `QUOTA_EXHAUSTED` | 換 credential / 換帳號 / 簽到後重試 |
| `MODEL_NOT_FOUND_404` | 404 model_not_found | 否 | 該模型 `FAILED`，提示檢查 ID | 修正 `manualModelIds` |
| `PROTOCOL_MISMATCH_422` | 422 / schema 不合 strict endpoint policy | 否 | 該 endpoint `FAILED` | 改 `adapterType` / `EndpointProfile` |
| `SERVER_ERROR_5xx` | 5xx | 是（1 次手動重試） | 該步 `FAILED`，可重試一次 | 稍後重試 |
| `PRICING_UNKNOWN` / `QUOTA_UNKNOWN` | 無官方來源 | — | 非阻擋，記 `UNKNOWN` + evidence | 可直接 commit |
| `NOTICE_FETCH_FAILED` | website / notice 抓不到 | — | 非阻擋，`SKIPPED` | 可直接 commit |
| `CANCELLED` / `INTERRUPTED` | 使用者取消 / 進程被殺 | 是（恢復後繼續） | 停留該步開頭 | Resume |
| `POLICY_BLOCKED` | `NONE` 未二次確認、header 名違規 | 否 | `NEEDS_INPUT` | 補確認 / 修正 |

- 訊息規範：本地安全訊息，不含 URL / key / server body；完整技術證據只進 Evidence（不進 UI error / logcat 明文 secret）。
- 一次失敗不刪模型、不清空 last-good facts；`observedHealth` 可降級（`DEGRADED / UNSTABLE / OFFLINE`），`officialHealth` 無官方來源維持 `UNKNOWN`。

---

## 5. Quota 保護（探測預算 / Bulk Test 上限）

1. 預設預算（Quick Add 單次 workflow）：`listModels ≤ 1 call`；`testModel` 預設 ≤ 3 個模型 × 各 1 次最小 prompt（`max_tokens=1` 等級）；`pricingQuotaNotice ≤ 3 calls`；全 workflow 探測 request 上限 10 次、單 step timeout 15s（超時秒數等產品常數未決，實作必須集中一處可調，本文件只定上限語意）。
2. Bulk 行為：Advanced 多模型 / 多 endpoint 的「全部測試」按鈕必須二次確認，並仍受上限（預設 ≤ 10 個目標，超出需分批）；觸發 `429` 立即停止後續排程，已完成結果保留（`PARTIAL`）。
3. 不盲目重試：`429 / 402 / 401 / 403 / 404` 永不自動重試；`5xx / timeout` 最多 1 次手動重試；取消即中止 socket。
4. 所有探測 inference（含成功 / 失敗 / 取消）寫 telemetry（含 `ttftMs / ttfbMs / latencyMs / outputTokens / tps`，缺值記 null 不造數；role-only chunk 不算 TTFT；non-streaming `TTFT=null`）。
5. 單裝置併發：同時只跑一個 onboarding 探測（`single-flight`）；WorkManager 任務可中斷恢復；Room 不逐 token 寫入（開始 / 完成兩次 write）。

---

## 6. 與首條 Slice `saveConnection` 的接軌點

- 現況：`AtlasViewModel.saveConnection(name, baseUrl, modelId, secret)` 建立全新 Provider / Account / Credential / Endpoint / Offering（HTTPS 檢查、成功清空 UI key、不替換既有）。
- Phase 3 接軌：
  1. `saveConnection` 視為 `AdvancedAdd` 的最小特例：單 `EndpointProfile { CHAT_COMPLETIONS, enabled }` + 單 credential（`BEARER` 語意）+ 單 `manualModelIds[0]` + 無 `manualPricing`（全 `UNKNOWN`）+ `source=USER_MANUAL` + 雙 health `UNKNOWN`。
  2. `commit` 實作必須重用 `AtlasRepository.addConnection` 的 transaction 語意並延伸到多模型 / 多 endpoint / 多 credential（以 `CredentialAvailability` 關聯 Offering ↔ Account / Credential，`ConnectionRecord.id` 仍指 availability id；`Endpoint.protocol=CHAT_COMPLETIONS` 等，非 `HTTPS`）。
  3. 不破壞 v1：既有 `ConnectionRecord` / `ProviderEntity / AccountEntity / CredentialEntity / EndpointEntity / OfferingEntity` 欄位只增不改；pricing decimal 字串、snapshot scope（provider / account / credential / model / group / endpoint / operation / observedAt）完整保留；`schema export` 開啟、無 destructive migration。
  4. `saveConnection` 本體在 Phase 3 不刪除（保留為離線 / 最小路徑），Quick / Advanced 的 `commit` 與其共用同一 Repository 入口，差異僅在輸入豐富度與 evidence 完整度。

---

## 7. Evidence 與 Confidence

- 每次探測寫 `Evidence { evidenceId, workflowId, scopeRefs, source (PROBE / USER_MANUAL / OFFICIAL_DOC / NOTICE_PAGE), observedAt, version, summary }`；不存 prompt / response 全文 / secret / raw headers。
- `confidence`：`HIGH` 僅限官方定價 / 官方 catalog + 成功驗證；自動偵測無把握、`USER_MANUAL` 手填一律 `LOW`；查無來源為 `UNKNOWN`。
- Crawler funnel 值（`DISCOVERED / VERIFYING / VERIFIED / HIGH_VALUE_GREY_ZONE / STALE / FAILED / REJECTED`）與淘汰 / 降級理由屬 Phase 8，本 Phase 只為其預留 `evidenceRef` 欄位，不提前實作。

---

## 8. UI 驗收點

1. Providers 有兩個明確入口：`Quick Add` 與 `Advanced Add`，文案不混用、不互跳（Quick 內無 adapter override；要 override 必須去 Advanced）。
2. Quick Add 表單僅四欄（base URL / website / key / optional model），欄位有明確 label；key 欄密碼遮罩、無回顯、不存 Bundle。
3. 探測進度條按 §1.2 順序顯示目前 step；每步顯示 `SUCCESS / PARTIAL / SKIPPED / FAILED` 狀態；失敗顯示 §4 分類（安全訊息），不貼 server body。
4. Review 一次顯示 §1.3 全部八項；Unknown 以徽章明示（見 §9），不得隱藏列、不得以 `0` / `$0` 代替。
5. `READY_WITH_WARNINGS` 有警告橫幅 + 缺口清單，仍可 commit；`BLOCKED` 只有 `Edit & Re-run` / `Abandon`。
6. Advanced 表單含 §2.1 全部欄位；`CUSTOM_HEADER` 顯示欄位名回顯（不回顯值）；`PAT_ACCOUNT` 分「帳號憑證 / 推理憑證」兩區；`NONE` 需二次確認。
7. 取消按鈕全程可用；取消後可重試或跳過該步；App 重啟後提示 Resume。
8. Commit 成功後清空所有 secret 輸入、跳轉新 Provider 卡片；失敗可重放 commit 不產生重複。
9. Models 頁不把未驗證手動模型標成已驗證；不塞 mock catalog；分類缺失顯示「未知」而非臆測類別。
10. 無障礙 / 版式沿用 UI_CONTRACT：48dp targets、8dp spacing、可滾動表單、system light/dark、字串進 `strings.xml`（繁中 default）。

---

## 9. Unknown 顯示矩陣（節錄）

| 資料 | 內部值 | UI 顯示（繁中） | 禁止 |
|---|---|---|---|
| 定價狀態 | `priceState=UNKNOWN` | 「價格：未知」徽章 + 「尚無官方價格資料」 | 顯示 `$0` / `免費` |
| 單 scope 價格 | 無 snapshot | 該列顯示「未知」，不併入總價 | 以他組價格覆蓋 |
| Quota | `QUOTA_UNKNOWN` / snapshot null | 「配額：未知」+ 依據缺說明 | 顯示「充足」 |
| Health（官方 / 觀察） | `UNKNOWN` | 雙欄各顯示「未知」 | 合併單一綠燈 |
| Adapter 型別 | `UNKNOWN` | 「型別：未知（已保留手填入口）」 | 硬套 OpenAI 標籤 |
| Models found | 不支援列舉 | 「不支援列舉」+ manual 路徑提示 | 顯示 `0` 充數 |
| Models verified | 0 成功 | 「已驗證 0（共測試 N）」+ 失敗分類 | 顯示「無模型」並刪單 |
| Confidence | `LOW / UNKNOWN` | 灰色徽章「證據弱 / 無證據」+ evidence 摘要入口 | 顯示「已確認」 |
| Check-in | 無 URL | 不顯示簽到按鈕（有 URL 才顯示） | 顯示失效按鈕 |
| 一次性贈金 | 有額度但非 recurring | 「一次性額度（非 recurring）」 | 標「免費無限」 |

---

## 10. 未決與明確不做（本文件不擅自定政策）
- 通知預設開關、retention 天數、探測超時秒數的最終產品常數未決；實作時集中可調，不寫死 UI 文案。
- Batch import、Generic Adapter 完整語意、Crawler funnel、Assistant tools 細節分屬其 Phase story，本文件只定義介面接軌，不提前實作。
