# Crawler P8 實作藍圖（Prep Session，非阻塞設計文件）

> 性質：P8 實作前設計藍圖，不寫 code、不改 schema、不加 DAO/Repo/UI。
> 規範優先序：目前 session 使用者指令 > `docs/DECISIONS.md` 較新決策 > `docs/RESEARCH_POLICY.md` / `docs/PRODUCT_SPEC.md §13` > 其他狀態文件。
> 母規格 `PRODUCT_SPEC §13`＋`§10`＋`§15` 為大綱；摘要漏字不得縮水（D-2026-09-28-004）。
> 本文件關聯 BACKLOG F10（candidate 零持久化）、F11（Evidence 無 candidate/offering/scope 關聯）。

規範來源：
- `docs/RESEARCH_POLICY.md` 全文（§2 eligibility、§3 recurring 語意、§4 Taiwan、§5 funnel、§8 evidence-first、§10 probe budget、§11 run output、§12 reflection）
- `docs/PRODUCT_SPEC.md §13`（funnel、verification、authenticity、probe progressive、ResearchExecutionBackend LOCAL/REMOTE/HYBRID）
- `docs/DECISIONS.md` D-2026-09-28-001（ordinary RPD>=100＋TPD>=1,000,000 inclusive hard gate）、D-2026-09-28-002（AA 最新總分前 4，family-agnostic，禁 hard-code）、D-2026-09-28-003（不可量化 quota 保存方式）
- 現況：`core/domain/Domain.kt` 只有 `CrawlerCandidate`（id/providerId/modelId/state/rejectionReason/downgradeReason/evidenceIds）、`CandidateState` 7 態、`CandidateDecisionReason` 15 理由；`app/data/Entities.kt` 的 `EvidenceEntity` 只有 id/requestId/providerId/modelId/outcome/status/timestamp/source/version。

---

## 1. P8 範圍與非目標

### 1.1 P8 必須交付（關 F10/F11）
1. `crawler_candidates` 持久化（全 funnel，不只推薦結果）。
2. `EvidenceEntity` 追加 `candidateId` / `offeringId` / scope 欄（F11），淘汰/降級理由可關聯證據鏈。
3. `research_runs` / `research_tasks` / `research_checkpoints` 持久化（含 funnel counts＋reflection 輸入）。
4. ordinary RPD＋TPD gate 判定函式（domain 純函式，可測）。
5. AA top-4 動態判定流程（禁 hard-code 家族/模型名）。
6. probe 預算狀態機（progressive，不暴力 load test）。
7. `ResearchExecutionBackend` LOCAL/REMOTE/HYBRID 抽象（第一版可只有 LOCAL 實作，但 schema 不綁死手機）。

### 1.2 非目標（不得在 P8 順手做）
- 不做 server-side 完整 crawler backend（PRODUCT_SPEC §17，只留 extension point）。
- 不做自動 cross-provider fallback / key rotation / routing engine。
- 不做 Crawler UI 豪華版；P8 只要求 funnel counts 可查詢、可報表（UI 綁定是後續 slice）。
- 不自改 system prompt / App source code / secret boundary / destructive permission（RESEARCH_POLICY §12 禁止項）。
- 不把 LMSpeed external 指標寫入 local telemetry（RESEARCH_POLICY §7）。
- 不 eval 任意站方 expression（沿用 New API 安全 parser 原則）。

---

## 2. Candidate Entity 欄位設計（F10 核心）

### 2.1 設計原則
- 一列 = 一個「待驗證的免費供給假說」，粒度建議 **(providerHost × accessMethod × modelOrGroup)**，不是「整個 provider 一列」也不是「每個 URL 一列」。同一 provider 不同 accessMethod（INFERENCE_READY vs WEB_ONLY）必須分列，不互相覆蓋（PRODUCT_SPEC §1 鐵律）。
- 全 funnel 常駐：DISCOVERED / VERIFYING / VERIFIED / HIGH_VALUE_GREY_ZONE / STALE / FAILED / REJECTED都要存列，淘汰列不刪除（RESEARCH_POLICY §4/§5）。
- Unknown≠0、Unknown≠Free：所有 quota 數字欄一律 nullable；`null`＝未知，不填 0、不填 -1。
- manual / official / observed / external 不互冒：每個重要 claim 欄都要有 companion `*Source`＋`*EvidenceId`，見 §2.2。
- PricingScope 不縮水：quota 數字若要轉成正式 `quota_snapshots`，必須能拼出七維（provider/account/credential/model/group/endpoint/operation）；candidate 側先存「宣稱 scope 描述」，轉正時才做 `validateScope`。
- Room 慣例沿用：surrogate `@PrimaryKey id: String`（UUID/`cand_<ulid>`），FK 一律 `RESTRICT`（不 CASCADE 刪證據），索引見 §2.3。

### 2.2 `crawler_candidates` 欄位表（提案，P8 實作時轉 Room Entity）

| 欄 | 型別（Room） | 可空 | 說明 / 規範錨點 |
|---|---|---|---|
| `id` | TEXT PK | 否 | surrogate，`cand_` 前綴。對應 domain `CrawlerCandidate.id`。 |
| `state` | TEXT | 否 | `CandidateState` 7 態原文。CHECK 約束僅 APP 層驗證＋測試斷言，不靠 SQLite CHECK 綁死（沿用既有 `safe*` 風格）。 |
| `providerId` | TEXT nullable | 是 | 已建檔 provider 才填；seed 階段未知可 null。null 不當 wildcard。 |
| `providerName` | TEXT nullable | 是 | 展示名；discovery seed 原文保留，不正規化覆蓋。 |
| `websiteUrl` | TEXT nullable | 是 | 官網/註冊頁。https 白名單驗證在寫入/驗證層，不在本欄預設值動手腳。 |
| `baseUrl` | TEXT nullable | 是 | API base；UNA verified 前不得標 INFERENCE_READY。 |
| `accessMethod` | TEXT nullable | 是 | `INFERENCE_READY` / `CODING_AGENT` / `WEB_ONLY` / `CUSTOM` / `UNKNOWN`。只有能直接 inference 才 `INFERENCE_READY`（SPEC §13）。未知填 `UNKNOWN`，不猜。 |
| `authMode` | TEXT nullable | 是 | `NONE` / `API_KEY` / `BEARER` / `CUSTOM_HEADER` / `PAT_ACCOUNT` / `UNKNOWN`。secret 值永不存本表。 |
| ` Taiwan 區塊（§2.4 展開）` | — | — | 見 §2.4，至少 7 欄＋`taiwanUsable` 三態。 |
| `modelsClaimRaw` | TEXT nullable | 是 | 站方宣稱模型原文（JSON array string 或原文摘錄），不直接當 canonical。 |
| `canonicalModelId` | TEXT nullable | 是 | 已正規化才填（`normalizeModelId`）；未驗證映射 `verified=false` 對應 alias 表，不在本欄冒充。 |
| `freeClaim` | TEXT nullable | 是 | `RECURRING_FREE_CLAIMED` / `ONE_TIME_TRIAL` / `WELCOME_BONUS` / `TEMPORARY_PROMOTION` / `UNLIMITED_CLAIMED` / `NO_CLAIM` / `UNKNOWN`。ONE_TIME 系不得冒充 recurring（POLICY §3）。 |
| `freeClaimSource` | TEXT nullable | 是 | `OFFICIAL` / `CRAWLER_VERIFY` / `LMSPEED` / `USER` / `UNKNOWN`。 |
| `freeClaimEvidenceId` | TEXT nullable | 是 | FK→`evidence.id`（RESTRICT）。重要 claim 必有 evidence（鐵律）。 |
| `quota 區塊（§2.5 展開）` | — | — | RPD/TPD/RPM/TPM nullable Long/Double＋scope 描述＋reset 語意 enum。 |
| `quotaSource` | TEXT nullable | 是 | 同上來源分層。LMSpeed hint 只標 `LMSPEED`，不得升為 `OFFICIAL`。 |
| `quotaEvidenceId` | TEXT nullable | 是 | FK→`evidence.id`。 |
| `recurringKind` | TEXT nullable | 是 | `DAILY_RESET` / `ROLLING_24H` / `ROLLING_7D` / `WEEKLY` / `MONTHLY` / `PER_MODEL` / `SHARED_ACCOUNT` / `RPM_ONLY` / `PUBLIC_POOL` / `DYNAMIC_POOL` / `UNLIMITED_CLAIMED` / `ONE_TIME_TRIAL` / `WELCOME_BONUS` / `TEMPORARY_PROMOTION` / `UNKNOWN`。可複選的語意以關聯表或 JSON set 存（見 §2.5 決策點）。 |
| `resetDescription` | TEXT nullable | 是 | 原文摘錄（如 "resets 00:00 UTC"），不解析成 cron 猜測。 |
| `aa 區塊（§5 展開）` | — | — | `aaRank` / `aaScore` / `aaObservedAt` / `aaEvidenceId` / `topTierExceptionEligible`。缺失一律 null/false，不猜排名。 |
| `confidence` | TEXT nullable | 是 | `HIGH` / `MEDIUM` / `LOW` / `UNKNOWN`。沿用 alias confidence 語意；manual 恆 LOW。 |
| `rejectionReason` | TEXT nullable | 是 | `CandidateDecisionReason` 原文；僅 REJECTED/FAILED 必填，其他態必須 null（狀態機強制，見 §7）。 |
| `downgradeReason` | TEXT nullable | 是 | 同上；常用 `PERFORMANCE_BELOW_THRESHOLD` / `STALE_NO_SIGNAL` / `SUPERSEDED`，僅 GREY/STALE 允許非 null。 |
| `decisionEvidenceId` | TEXT nullable | 是 | 淘汰/降級判定的關鍵 evidence FK。F11 證據鏈要求。 |
| `decisionAt` | INTEGER nullable | 是 | 淘汰/降級時間 epoch ms；未決策 null。 |
| `decidedBy` | TEXT nullable | 是 | `LOCAL_VERIFY` / `REMOTE_VERIFY` / `USER` / `REFLECTION`。可追溯是誰降級。 |
| `lastCheckedAt` | INTEGER | 否 | 預設 0；每次 probe/verify 更新。STALE 判定輸入。 |
| `nextCheckAt` | INTEGER nullable | 是 | 下次排程時間；null＝未排程。checkpoint 恢復用。 |
| `staleSince` | INTEGER nullable | 是 | 進入 STALE 的時間；非 STALE 必須 null。 |
| `supersededById` | TEXT nullable | 是 | 被誰取代（candidate id 自引用，RESTRICT；不設 FK 級聯）。`SUPERSEDED` 必填。 |
| `fingerprintConfidence` | TEXT nullable | 是 | authenticity 指紋信心（HIGH/MEDIUM/LOW/UNKNOWN）；弱 evidence 不得宣稱 substitution（SPEC §13）。 |
| `fingerprintEvidenceId` | TEXT nullable | 是 | FK→evidence。 |
| `probeLevel` | TEXT | 否 | `PASSIVE` / `STRUCTURED_ENDPOINT` / `MINIMAL_INFERENCE` / `DEEPER_PROBE`，預設 `PASSIVE`。見 §6。 |
| `probeBudgetSpent` | INTEGER | 否 | 已耗 probe 計數/權重；預設 0。 |
| `sourceUrls` | TEXT | 否 | JSON array string；來源衝突保留多條，不無聲覆蓋（POLICY §8）。 |
| `notes` | TEXT nullable | 是 | 研究筆記；不存 secret。 |
| `createdAt` / `updatedAt` | INTEGER | 否 | epoch ms。 |
| `version` | INTEGER | 否 | 樂觀鎖/學習迭代版本，預設 1。Reflection 更新语意版本＋1。 |

> 與現有 domain `CrawlerCandidate` 的差：現有 7 欄只是 funnel 最小骨架；P8 entity 是其持久化超集。P8 需同步擴 `CrawlerCandidate` domain（加 Taiwan/quota/AA/probe 欄）或另建 `CrawlerCandidateDetail`，二選一（推薦後者，避免一次改爆既有 `DomainTest`）。

### 2.3 索引與約束（提案）
- `Index(state)`、`Index(providerId)`、`Index(canonicalModelId)`、`Index(lastCheckedAt)`、`Index(nextCheckAt)`、`Index(topTierExceptionEligible)`。
- `Index(value=[accessMethod, baseUrl])` 非唯一（同一 host 多 accessMethod 並存）。
- FK：`freeClaimEvidenceId` / `quotaEvidenceId` / `decisionEvidenceId` / `fingerprintEvidenceId` / `aaEvidenceId` → `evidence(id)`，全部 `RESTRICT`（證據不可被 candidate 刪除拖走）。
- 不變式（APP 層強制＋單測）：
  - `state=VERIFIED` ⇒ `decisionEvidenceId NOT NULL` 且 `confidence != UNKNOWN`。
  - `state=REJECTED/FAILED` ⇒ `rejectionReason NOT NULL` 且 `decisionEvidenceId NOT NULL`。
  - `state=HIGH_VALUE_GREY_ZONE/STALE` ⇒ `downgradeReason NOT NULL`。
  - `state=DISCOVERED/VERIFYING` ⇒ `rejectionReason IS NULL AND downgradeReason IS NULL`。
  - `accessMethod != INFERENCE_READY` ⇒ 不得進 ordinary 主結果（即使 quota 達標）。

### 2.4 Taiwan 可用性欄（RESEARCH_POLICY §4＋SPEC §13 全量）
| 欄 | 型別 | 說明 |
|---|---|---|
| `taiwanUsable` | TEXT nullable | `USABLE` / `BLOCKED` / `CONDITIONAL` / `UNKNOWN`。未知填 UNKNOWN，不猜可用。 |
| `twQqOnly` | INTEGER nullable | 1/0/null（null＝未知）。QQ-only 限制。 |
| `twCnRealName` | INTEGER nullable | 中國實名要求。 |
| `twCnPhone` | INTEGER nullable | 中國手機要求。 |
| `twPaymentRestriction` | TEXT nullable | 特定付款限制原文；無則 null（不用 "" 冒充無限制）。 |
| `twRegionBlocked` | INTEGER nullable | region blocked。 |
| `twEduRestriction` | INTEGER nullable | `*.edu.cn` 等特殊資格限制；有則存原文於 `twEduDetail`。 |
| `twEduDetail` | TEXT nullable | 原文。 |
| `twEvidenceId` | TEXT nullable | FK→evidence。Taiwan 判定必有 evidence。 |

規則：不可用仍保存整列＋reason＋evidence，不從歷史消失；UI/report 以 `taiwanUsable` 過濾但 funnel counts 仍計入。

### 2.5 quota / recurring 語意欄（POLICY §3＋DECISIONS D-001/D-003）
| 欄 | 型別 | 說明 |
|---|---|---|
| `rpd` | INTEGER nullable | 日請求數宣稱；null＝未知/未披露。RPM-only 時本欄 null，不填 0。 |
| `tpd` | INTEGER nullable | 日 tokens 宣稱；同上。 |
| `rpm` | INTEGER nullable | 分鐘請求；僅 RPM-only 情境有值時填。 |
| `tpm` | INTEGER nullable | 分鐘 tokens。 |
| `quotaScopeKind` | TEXT nullable | `PER_MODEL` / `SHARED_ACCOUNT` / `PROVIDER_GLOBAL` / `PROJECT_X_MODEL` / `GROUP_SCOPED` / `CUSTOM_POOL` / `UNKNOWN`。Gemini 類 Project×Model 不得硬合成 provider-global（沿用 §12 教訓）。 |
| `quotaScopeDetail` | TEXT nullable | 原文 scope 描述；轉正式 snapshot 時的 `validateScope` 輸入。 |
| `isPublicPool` / `isDynamicPool` | INTEGER nullable | 三態 boolean；public pool 不得當固定 daily quota（POLICY §6 禁止項）。 |
| `unlimitedClaimed` | INTEGER nullable | 三態；claimed 不得寫入 ground truth。 |
| `quotaStateHint` | TEXT nullable | 映射 `QuotaState` 原文（`RATE_LIMIT_ONLY` / `PUBLIC_POOL` / `UNLIMITED_CLAIMED` / `NO_KNOWN_CONSUMABLE_QUOTA` / `UNKNOWN`…）；正式 `QuotaState` 以 snapshot 層為準，本欄只是 hint，不互冒。 |

決策點（P8 實作前由主 session 拍板，二選一）：
- (A) `recurringKind` 用 JSON set 存多值（簡單，查詢弱）。
- (B) `candidate_recurring_kinds(candidateId, kind)` 關聯表（查詢強，migration 多一表）。
- 本藍圖推薦 (B) 若 P8 同時要做 funnel 篩選 UI；若 P8 只做持久化＋報表，(A) 可接受並在 P8+1 切 (B)。

---

## 3. EvidenceEntity 需加欄（F11）

### 3.1 現況缺口
現 `evidence` 只有 request/telemetry 語意（requestId/providerId/modelId/outcome/status/timestamp/source/version），無法表達「這條 evidence 是為哪個 candidate、哪個 offering、哪個 scope 採集的」。導致淘汰/降級理由無法關聯證據鏈（BACKLOG F11 原文）。

### 3.2 追加欄位表（全部 nullable，避免舊列 migration 補假值）
| 欄 | 型別 | 可空/預設 | 說明 |
|---|---|---|---|
| `candidateId` | TEXT nullable | 是/null | FK→`crawler_candidates(id)` RESTRICT＋`Index(candidateId)`。telemetry 舊列全部 null，不回填假 id。 |
| `offeringId` | TEXT nullable | 是/null | FK→`offerings(id)` RESTRICT＋`Index(offeringId)`。手填/manual offering 驗證鏈用。 |
| `scopeKey` | TEXT nullable | 是/null | 七維 scope storageKey（沿用 `PricingScope.storageKey()` 長度前綴＋`-1:` null 編碼），null 不碰撞不 wildcard。供 price/quota evidence 精確回查。 |
| `scopeKind` | TEXT nullable | 是/null | `PRICING` / `QUOTA` / `CANDIDATE_VERIFY` / `AUTHENTICITY` / `TAIWAN` / `AA_RANKING` / `OTHER`。查詢切面用。 |
| `claim` | TEXT nullable | 是/null | 重要 claim 原文（POLICY §8：claim/source/type/URL/fetchedAt/confidence/normalized/verification）。既有 `outcome/status` 不刪，本欄是 claim 語意補充。 |
| `sourceType` | TEXT nullable | 是/null | `OFFICIAL_DOC` / `OFFICIAL_API` / `STRUCTURED_ENDPOINT` / `FRONTEND_XHR` / `GITHUB_SOURCE` / `DIRECT_PROBE` / `LMSPEED` / `WEB_SEARCH` / `USER` / `UNKNOWN`。與既有 `source`（TelemetrySource 語意）分欄，不互冒。 |
| `urlOrEndpoint` | TEXT nullable | 是/null | URL 或 endpoint；secret query 值不得存（只存 safe ref＋redact 後 header 白名單原則）。 |
| `fetchedAt` | INTEGER nullable | 是/null | 採集時間；既有 `timestamp` 保留（telemetry 語意），本欄是 research fetch 語意，兩者不合併。 |
| `confidence` | TEXT nullable | 是/null | `HIGH`/`MEDIUM`/`LOW`/`UNKNOWN`；manual 恆 LOW。 |
| `verificationStatus` | TEXT nullable | 是/null | `UNVERIFIED` / `CORROBORATED` / `CONFLICTED` / `SUPERSEDED`。衝突保留衝突，不無聲覆蓋。 |
| `normalizedJson` | TEXT nullable | 是/null | 正規化後 observation JSON；raw 與 normalized 分開存。 |
| `supersedesId` | TEXT nullable | 是/null | 自引用 evidence FK（RESTRICT）；被取代的舊 evidence 不刪除。 |

### 3.3 索引與 migration 注意
- 新增 `Index(candidateId)`、`Index(offeringId)`、`Index(scopeKey, scopeKind)`、`Index(fetchedAt)`。
- Migration 策略：`ALTER TABLE evidence ADD COLUMN … DEFAULT NULL`（不可 DEFAULT ""/0，避免 Unknown 變有值）。既有列 `candidateId/offeringId/scopeKey` 全 null，查詢必須 `IS NULL` 單獨處理，不把 null 當 wildcard。
- `toEvidence()`（`PerformanceSample.toEvidence()`）不動：telemetry evidence 的新增欄全 null；crawler 寫入走新的 `CrawlerEvidenceWriter`（P8 建），兩條寫入路徑不共用預設值。
- Secret 紅線：`urlOrEndpoint`/`normalizedJson` 寫入前走 `redactSecrets`＋`SAFE_RATE_HEADERS` 白名單原則；`<8` 短 token 不遮蔽的已知殘留（F18）照樣適用，不得存 raw key/PAT。

---

## 4. ResearchRun / Task / Checkpoint 表設計（POLICY §11/§12＋SPEC §15）

### 4.1 `research_runs`（一輪研究）
| 欄 | 型別 | 說明 |
|---|---|---|
| `id` | TEXT PK | `run_<ulid>`。 |
| `startedAt` / `finishedAt` | INTEGER | finished null＝進行中。 |
| `trigger` | TEXT | `MANUAL` / `SCHEDULED` / `REFLECTION_RETRY` / `USER_QUERY`。 |
| `backend` | TEXT | `LOCAL` / `REMOTE` / `HYBRID`（執行時實際 backend，見 §7）。 |
| `languages` | TEXT | JSON array（English/中文/Русский/日本語/한국어/Español/Português/Bahasa Indonesia…）。同一 provider 不因多語重複計數（POLICY §6）。 |
| `scannedCount` | INTEGER | 預設 0。POLICY §11 必需輸出。 |
| `highSignalCount` | INTEGER | 同上。 |
| `verifiedCount` | INTEGER | 同上。 |
| `greyZoneCount` | INTEGER | 同上。 |
| `rejectedCount` | INTEGER | 同上。 |
| `newProvidersJson` | TEXT | JSON；本輪 new/updated providers 摘要。 |
| `quotaChangesJson` | TEXT | JSON；重要 free/quota 變化。 |
| `authenticityFindingsJson` | TEXT | JSON；指紋/替代性發現。 |
| `unresolvedHighValueJson` | TEXT | JSON；未解決高價值 candidates id 清單。 |
| `reflectionJson` | TEXT nullable | Reflection 輸出摘要；詳見 §4.4。完成後必填（POLICY §11「完成後執行 Reflection」）。 |
| `status` | TEXT | `RUNNING` / `COMPLETED` / `FAILED` / `CANCELLED`。一次 failure 不刪數據（鐵律）。 |

### 4.2 `research_tasks`（run 內可恢復單元）
| 欄 | 型別 | 說明 |
|---|---|---|
| `id` | TEXT PK | `task_<ulid>`。 |
| `runId` | TEXT FK→runs RESTRICT＋Index | 所屬 run。 |
| `kind` | TEXT | `DISCOVERY` / `ENRICH` / `VERIFY` / `PROBE` / `AA_RANKING_FETCH` / `REFLECTION`。 |
| `target` | TEXT nullable | candidateId / providerHost / query，依 kind 而定。 |
| `language` | TEXT nullable | 本 task 語言。 |
| `endpointOrSource` | TEXT nullable | 來源/端點（redact 後）。 |
| `status` | TEXT | `PENDING` / `RUNNING` / `SUCCEEDED` / `FAILED` / `SKIPPED`。 |
| `attemptCount` | INTEGER | 重試計數；預設 0。 |
| `lastError` | TEXT nullable | 脫敏後錯誤（不存 secret）。 |
| `startedAt` / `finishedAt` | INTEGER nullable | 恢復/重跑依據。 |
| `resultEvidenceIds` | TEXT | JSON array；本 task 產生的 evidence ids。 |

### 4.3 `research_checkpoints`（斷點恢復）
| 欄 | 型別 | 說明 |
|---|---|---|
| `id` | TEXT PK | `ckpt_<ulid>`。 |
| `runId` | TEXT FK＋Index | 所屬 run。 |
| `taskId` | TEXT nullable FK | 關聯 task（null＝run 級 checkpoint）。 |
| `createdAt` | INTEGER | 建立時間。 |
| `stateJson` | TEXT | 可恢復狀態（query cursor、已掃 hosts、待驗證 queue）。不存 secret。 |
| `funnelSnapshotJson` | TEXT | 當時 funnel counts 快照（DISCOVERED…REJECTED 七數）。供 UI「掃很多只展最後幾個」稽核。 |

### 4.4 Reflection 產出（SPEC §15，versioned/reversible）
Reflection 不直接 UPDATE 生產列，產出以下 profile 的**新版本提案**（Proposal＋Evidence＋Rationale），由使用者/主 session 批准後生效：
- `ProviderRecipe` / `SourceReliabilityProfile` / `ResearchStrategyProfile` / `EndpointPattern` / `AuthPattern` / `SuccessfulDiscoveryPath` / `FailedDiscoveryPath` / `ModelAliasRule` / `VerificationOutcome`。
- 每條含 `version`、`supersedesVersion`、`evidenceIds`、`rationale`、`tentative`（evidence 不足標 tentative）。
- P8 表設計預留 `research_reflections(id, runId, profileKind, version, supersedesVersion, proposalJson, evidenceIdsJson, rationale, tentative, status[ PROPOSED/APPROVED/REJECTED ])`；若 P8 範圍塞不下，至少保留 `runs.reflectionJson`＋本節 contract，表推遲到 P10（Self-improving）但不得把 P8 counts 設計成 reflection 無法消費。

---

## 5. Ordinary RPD＋TPD Gate 判定函式簽名（D-001）

> 純 domain 函式，無 Android/Room 依賴，可單測。Unknown 語意由呼叫端保證（null 傳入），函式內不猜。

```kotlin
// core/domain/CrawlerEligibility.kt（提案檔名，P8 建）

/** Quota 輸入：全部 nullable；null＝未知/未披露。絕不以 0 代入。 */
data class QuotaClaim(
  val rpd: Long?,
  val tpd: Long?,
  val rpm: Long?,
  val tpm: Long?,
  val scopeKind: String?,        // PER_MODEL / SHARED_ACCOUNT / … / UNKNOWN
  val isPublicPool: Boolean?,    // 三態：null＝未知
  val isDynamicPool: Boolean?,
  val unlimitedClaimed: Boolean?,
)

/** 普通門檻常量：RPD>=100 且 TPD>=1_000_000，inclusive（D-001）。 */
object OrdinaryQuotaGate {
  const val MIN_RPD: Long = 100L
  const val MIN_TPD: Long = 1_000_000L
}

enum class OrdinaryGateResult {
  PASS,                 // rpd!=null && tpd!=null 且雙達標
  FAIL_BELOW_THRESHOLD, // 有值但任一未達標
  UNQUANTIFIABLE,       // 缺 rpd/tpd 任一，或 pool/unlimited 語意 → 不得當 0，不得進主結果
}

/** 普通門檻判定：只看數字，不看 AA、不看 Taiwan、不看 accessMethod。 */
fun evaluateOrdinaryGate(claim: QuotaClaim): OrdinaryGateResult

/** 主結果 eligibility 總判定：ordinary PASS 且 accessMethod==INFERENCE_READY 且非 ONE_TIME 系。 */
enum class MainResultEligibility { ELIGIBLE_ORDINARY, ELIGIBLE_TOP_TIER_EXCEPTION, INELIGIBLE }

fun evaluateMainResultEligibility(
  claim: QuotaClaim,
  accessMethod: String?,          // INFERENCE_READY / CODING_AGENT / WEB_ONLY / …
  freeClaim: String?,             // RECURRING_FREE_CLAIMED / ONE_TIME_TRIAL / … / UNKNOWN
  topTierExceptionEligible: Boolean, // 來自 §6 AA 判定，不在本函式內重算
): MainResultEligibility
```

判定真值表（驗收用）：
| rpd | tpd | ordinary | 備註 |
|---|---|---|---|
| 100 | 1_000_000 | PASS | inclusive 邊界 |
| 99 | 5_000_000 | FAIL_BELOW_THRESHOLD | RPD 差 1 即 fail |
| 500 | 999_999 | FAIL_BELOW_THRESHOLD | TPD 差 1 即 fail |
| null | 2_000_000 | UNQUANTIFIABLE | 不得當 0，不得 PASS |
| 200 | null | UNQUANTIFIABLE | 同上 |
| 有值但 `isPublicPool=true` | — | UNQUANTIFIABLE（或 FAIL，視 P8 拍板，但絕不 PASS） | public pool 不當固定 daily quota |

---

## 6. AA Top-4 動態判定流程（D-002，禁 hard-code 家族）

### 6.1 鐵律
- 例外資格只看「AA 最新有效榜單＋使用者指定最新總分最高前 4 名」，不看家族（GPT/Claude/Gemini/Grok/DeepSeek/GLM/Kimi/Qwen 一視同仁）。
- 禁止在 code/config/測試 fixture 寫死任何模型名或家族名作為例外條件；測試用假名（如 `aa-fake-alpha`）＋假榜單。
- AA evidence 過期/缺失時 `topTierExceptionEligible=false`，不得猜排名；掉出前 4 立即恢復 ordinary gate（D-002 原文）。

### 6.2 AA ranking evidence 欄（存 candidate 列＋獨立 evidence 列）
candidate 側：
- `aaRank: INTEGER nullable`（1-based；null＝未知）。
- `aaScore: REAL nullable`（總分；null＝未知）。
- `aaObservedAt: INTEGER nullable`（榜單觀測時間 epoch ms）。
- `aaEvidenceId: TEXT nullable`（FK→evidence，`scopeKind=AA_RANKING`）。
- `topTierExceptionEligible: INTEGER`（0/1，預設 0）。**唯一例外開關**，UI/報表只讀本欄，不自行重算排名。
- `aaRankStale: INTEGER`（0/1，衍生標記：observedAt 超 TTL 即 1，見 §6.4）。

evidence 側（`scopeKind=AA_RANKING`）：
- `claim`＝榜單原文摘錄（如 "rank 3 / score 1284"），`urlOrEndpoint`＝榜單 URL，`fetchedAt`＝抓取時間，`confidence` 依來源 CORROBORATED/UNVERIFIED，`verificationStatus` 標衝突與否。

### 6.3 流程（sequence）
1. `AA_RANKING_FETCH` task 抓最新 AA 榜單 → 寫一條 `AA_RANKING` evidence（含 fetchedAt＋URL＋normalizedJson 前 4 名單）。
2. 對每個候選 model（canonicalId）：查其最新有效 AA evidence（`fetchedAt` 在 TTL 內）→ 取 `rank/score` → 若 `rank<=4` 則 `topTierExceptionEligible=1`，否則 0。
3. 排名變化（進/出前 4）寫 `research_tasks.resultEvidenceIds`＋`runs.quotaChangesJson`，並更新 candidate `aa*` 五欄＋`updatedAt`。舊 evidence 保留（`supersedesId` 鏈），不刪除。
4. 主結果查詢：`ELIGIBLE_TOP_TIER_EXCEPTION` ⇔ `topTierExceptionEligible=1 AND aaRankStale=0 AND accessMethod==INFERENCE_READY`（quota 數字不限，但仍需 evidence 鏈完整；見驗收清單）。

### 6.4 有效期（TTL，P8 拍板點）
- 提案：`AA_RANKING_TTL_HOURS = 168`（7 天），存在 domain 常量而非散落 magic number；過期即 `aaRankStale=1`＋例外開關視為 false（查詢層 `eligible AND NOT stale`）。
- 備選：TTL 由 `ResearchStrategyProfile` versioned 配置（P10 自適應）。P8 先 hard-code 常量＋單測，P10 再轉配置（需 DECISIONS 追加）。

### 6.5 簽名（提案）
```kotlin
data class AaRankingObservation(
  val canonicalModelId: String,
  val rank: Int?,          // null＝榜單無此模型
  val score: Double?,
  val observedAt: Long,    // 榜單觀測時間
  val evidenceId: String,
)

fun isAaRankingFresh(observedAt: Long, now: Long, ttlHours: Long = AA_RANKING_TTL_HOURS): Boolean

/** 動態判定：只吃觀測，不吃家族名。names/families 不得作為參數。 */
fun evaluateTopTierException(
  observation: AaRankingObservation?,
  now: Long,
): Boolean // == (observation != null && fresh && rank != null && rank in 1..4)
```

---

## 7. Probe 預算狀態機（POLICY §10＋SPEC §13）

### 7.1 Progressive 等級
`PASSIVE` → `STRUCTURED_ENDPOINT` → `MINIMAL_INFERENCE` → `DEEPER_PROBE` →（`VERIFIED` 由 verification 判定，不由 probe 等級直接升）。

| 等級 | 允許動作 | 禁止 |
|---|---|---|
| PASSIVE | 讀 docs/catalog/pricing 頁、frontend XHR/JSON、GitHub constants | 任何 inference、任何需 secret 的呼叫 |
| STRUCTURED_ENDPOINT | `status/pricing/models/groups/notice/about` 等結構化端點（無 secret 或 safe ref） | 推斷 quota 上限的壓測 |
| MINIMAL_INFERENCE | 單次小 prompt 可用性探測（走正常 telemetry＋Evidence） | 連發壓測、猜 Max RPM/TPM |
| DEEPER_PROBE | 僅 HIGH_VALUE（VERIFIED 候選或 GREY 高價值）多一次指紋/能力探測 | 暴力 load test；上限只有 Official API/Header/Docs 才標 Official |

### 7.2 預算狀態機
- 每 candidate：`probeLevel`＋`probeBudgetSpent`；每 run：全域 `probeBudgetTotal`（提案：LOCAL 預設小預算，REMOTE 由 server 配額，見 §8）。
- 升級條件：`canEscalateProbe(candidate, runBudget) = probeBudgetSpent < perCandidateCap AND runSpent < runTotal AND state in (DISCOVERED, VERIFYING, HIGH_VALUE_GREY_ZONE)`。`STALE/FAILED/REJECTED` 不得再耗預算（除非 USER 手動重開→回 VERIFYING）。
- 每次 probe 必寫 evidence（`scopeKind=CANDIDATE_VERIFY` 或 `AUTHENTICITY`），`lastCheckedAt` 更新；失敗不刪 last-good（鐵律）。
- 正常 Playground traffic 優先當 benchmark；有新鮮 telemetry 時不重複燒 quota（SPEC §2/§13）。
- 簽名（提案）：
```kotlin
enum class ProbeLevel { PASSIVE, STRUCTURED_ENDPOINT, MINIMAL_INFERENCE, DEEPER_PROBE }
fun nextProbeLevel(current: ProbeLevel, signal: ProbeSignal): ProbeLevel?
// ProbeSignal: STRONG_POSITIVE / WEAK_POSITIVE / NEGATIVE / INCONCLUSIVE
fun canEscalateProbe(candidateProbeSpent: Int, runSpent: Int, runTotal: Int, state: CandidateState): Boolean
```

---

## 8. LOCAL / REMOTE / HYBRID 抽象介面（SPEC §13）

> 第一版不要求完整 server backend，但 schema（§2–§4）不得綁死手機；所有 run/task/checkpoint 以 id＋JSON 序列化，可搬運。

```kotlin
// core/crawler/ResearchBackend.kt（提案檔名，P8 建）

enum class ResearchBackendKind { LOCAL, REMOTE, HYBRID }

interface ResearchExecutionBackend {
  val kind: ResearchBackendKind
  /** 發現/豐富：回傳 candidateIds＋evidenceIds，不直接寫 UI。 */
  suspend fun discover(input: DiscoveryInput): DiscoveryOutput
  /** 驗證單 candidate（含 Taiwan＋quota＋AA 引用），回傳新 state＋decision＋evidence。 */
  suspend fun verify(candidateId: String): VerifyOutput
  /** 受預算約束的 probe（內部走 §7 狀態機）。 */
  suspend fun probe(candidateId: String, level: ProbeLevel): ProbeOutput
  /** 抓 AA 榜單（REMOTE 可委派 server，LOCAL 直接 fetch＋evidence）。 */
  suspend fun fetchAaRanking(): List<AaRankingObservation>
  /** 斷點：匯出/匯入 checkpoint stateJson（供 HYBRID 手機↔server 接力）。 */
  suspend fun checkpoint(runId: String): CheckpointData
  suspend fun restore(checkpoint: CheckpointData)
}

data class DiscoveryInput(
  val queries: List<String>,
  val languages: List<String>,
  val seedHosts: List<String>,
  val budget: Int,
)
```

- LOCAL：Room＋WorkManager 執行，預算最小；secret 只在 privileged local tool layer 取用（沿用 Assistant secret boundary）。
- REMOTE：預留介面，P8 不實作 transport；contract 要求 request/response 全部可序列化、不含 raw key（只傳 safe refs＋redacted headers）。
- HYBRID：discovery 可 REMOTE、verify/probe 敏感步 LOCAL；checkpoint 接力（`stateJson` 不含 secret）。
- 所有 backend 共用同一 eligibility 函式（§5/§6 純函式），不在 backend 內各寫一套門檻。

---

## 9. Funnel 轉換規則（狀態機一覽）

```
DISCOVERED ──開始驗證──▶ VERIFYING ──證據充分＋gate(ordinary或AA例外)＋INFERENCE_READY──▶ VERIFIED
   │                        │  ├─高價值但證據/額度未齊──▶ HIGH_VALUE_GREY_ZONE（downgradeReason 必填）
   │                        │  ├─長期無信號──▶ STALE（downgradeReason=STALE_NO_SIGNAL，staleSince 必填）
   │                        │  └─硬失敗（auth/endpoint/policy）──▶ FAILED（rejectionReason＋decisionEvidence 必填）
   │                        └─（VERIFYING 超時無進展按 STALE 處理，不靜默丟棄）
   └─首篩即政策違規/假免費──▶ REJECTED（rejectionReason＋decisionEvidence 必填）
VERIFIED ──quota 掉線/AA 掉出前4/證據被推翻──▶ HIGH_VALUE_GREY_ZONE 或 STALE（經 Reflection 提案，不直接刪列）
HIGH_VALUE_GREY_ZONE ──補齊證據＋重驗──▶ VERIFYING（downgradeReason 保留歷史，decisionEvidence 鏈追加）
STALE ──新信號──▶ VERIFYING；長期無信號保留 STALE，不轉 REJECTED 冒充已證偽
FAILED / REJECTED ──僅 USER 手動或新強證據──▶ VERIFYING（原 decision 保留，不覆寫歷史）
```

UI/report 必顯示七態 counts（`research_runs` counts＋即時 `SELECT state, COUNT(*)` 兩路一致）。

---

## 10. P8 驗收測試清單（P8 實作 slice 照此驗收，F10/F11 關閉以 HEAD＋綠 CI 為準）

### A. Schema / Migration
- [ ] `crawler_candidates` 建表＋§2.3 索引；Room 版本＋1；migration `ADD COLUMN`/`CREATE TABLE` 綠（JVM＋device）。
- [ ] `evidence` 追加 §3.2 欄全 nullable；舊列 `candidateId/offeringId/scopeKey` 全 null；既有 `toEvidence()` 行為不變（telemetry 單測仍綠）。
- [ ] `research_runs/tasks/checkpoints(/reflections)` 建表＋FK RESTRICT；`schemas/` JSON 更新。
- [ ] 未知語意測試：quota 全 null 列寫讀往返仍 null（不變 0）；`scopeKey` null 不與 `-1:` 編碼碰撞（沿用 `PricingScope.storageKey()` 單測風格）。

### B. Ordinary Gate（D-001）
- [ ] 邊界：(100, 1_000_000)→PASS；(99, x)→FAIL；(x, 999_999)→FAIL（inclusive）。
- [ ] null 任一→UNQUANTIFIABLE，且 `evaluateMainResultEligibility` 在無 AA 例外時→INELIGIBLE（不得冒充主結果）。
- [ ] RPM-only / public pool / unlimited claimed→絕不 PASS（D-003）；列可進 GREY（HIGH_VALUE_GREY_ZONE 單測覆蓋）。

### C. AA Top-4（D-002）
- [ ] 假榜單 rank 1..4→`evaluateTopTierException=true`；rank 5→false；`observation=null`→false；過期→false。
- [ ] 家族無關：同一測試矩陣跑 GPT-like / Claude-like / Gemini-like / Grok-like / DeepSeek-like / GLM-like / Kimi-like / Qwen-like 假名，結果只隨 rank 變（禁 hard-code 回歸測試）。
- [ ] 掉出前 4→`topTierExceptionEligible` 翻 0＋舊 evidence 保留（`supersedesId` 鏈單測）。
- [ ] code/config/fixtures 全文 grep 不含以家族名決策例外的邏輯（`rg -i "gpt.*exception|claude.*exception"` 零命中）。

### D. Taiwan / Recurring / AccessMethod
- [ ] Taiwan 七因子任一未知→`taiwanUsable=UNKNOWN`（不猜 USABLE）；BLOCKED 列仍在 funnel counts。
- [ ] `ONE_TIME_TRIAL/WELCOME_BONUS/TEMPORARY_PROMOTION`＋quota 達標→主結果仍 INELIGIBLE（不得冒充 recurring）。
- [ ] `accessMethod=WEB_ONLY/CODING_AGENT`＋quota 達標→主結果仍 INELIGIBLE（只有 INFERENCE_READY 可進主結果）。

### E. Probe / Backend
- [ ] `STALE/FAILED/REJECTED` 調 `canEscalateProbe=false`（預算不漏）。
- [ ] probe 每次寫 evidence＋更新 `lastCheckedAt`；暴力連發測試（模擬 100 連發）在 LOCAL backend 被拒（單測斷言）。
- [ ] LOCAL backend 跑通 discover→verify→probe→checkpoint→restore 最小閉環（fake transport，不碰真網）；REMOTE/HYBRID 只有介面＋fake，不要求真 server。

### F. Run 報表 / Reflection
- [ ] 假 run 跑完 `scanned/highSignal/verified/grey/rejected` 五數與 `SELECT state COUNT` 一致。
- [ ] Reflection 產出 Proposal＋Evidence＋Rationale 結構；直接 UPDATE 生產列的路徑不存在（code review＋測試斷言無直寫 API）。
- [ ] LMSpeed external 指標未寫入 `samples`/local telemetry（source 分層單測）。

### G. 不變式回歸（既有鐵律）
- [ ] 一次 sync/verify failure 不清空 last-good（candidate 舊 VERIFIED 列保留＋STALE/GREY，而非刪除）。
- [ ] secret 不進 `evidence.claim/normalizedJson/urlOrEndpoint`（redact 單測：埋假 key `sk-test-…(≥8)` 斷言被遮蔽）。
- [ ] `TRACEABILITY.md` 補 requirement→code→tests→綠 SHA；BACKLOG F10/F11 關閉註記 HEAD 證據。

---

## 11. 未決策點（需主 session / 使用者拍板，不在本 prep 自行決定）
1. `recurringKind` 多值存 JSON vs 關聯表（§2.5 A/B）。
2. `AA_RANKING_TTL_HOURS` 取 168h 或轉 `ResearchStrategyProfile` 配置（§6.4）。
3. `CrawlerCandidate` domain 原地擴欄 vs 新建 `CrawlerCandidateDetail`（§2.2 末）。
4. `research_reflections` 獨立表進 P8 或推遲 P10（§4.4）。
5. probe 預算數值（per-candidate cap / run total）與 WorkManager 排程頻率（SPEC §16：不承諾 24/7 每分鐘可靠）。
