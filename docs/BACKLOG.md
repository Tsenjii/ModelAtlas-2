# 稽核 Deferred Backlog（首條 slice 之後）

> **狀態用途提醒（2026-09-30）**：本檔是歷史稽核 / deferred ledger，**不是 live 任務來源**。部分條目會落後 HEAD。當前工作模式、最新綠基線、下一步只看 `docs/ACTIVE_TASK.md`；requirement 是否 ACCEPTED 只看 `docs/TRACEABILITY.md` + code/tests/reviewer/full-green evidence。禁止只因本檔仍寫「未完成」就重做已 ACCEPTED 的 P3–P7。

## 現況快照（2026-10-06；**非權威，唯一 live 真相見 `docs/ACTIVE_TASK.md`**）

> 本段只是歷史稽核檔內的即時快照，**不是** live 任務來源，也不取代任何其他檔。現行 live mode／綠基線／下一步一律以 `docs/ACTIVE_TASK.md` 為準，acceptance 以 `docs/TRACEABILITY.md` 為準；下方 Archived 段內所有狀態文字皆為歷史快照。衝突時以 `ACTIVE_TASK.md` ＋ `TRACEABILITY.md` ＋ code／full-green evidence 為準。

| 項目 | 現況（2026-10-06 快照） |
| --- | --- |
| 最新已驗 full-green code | `ca7cb03` ／ Actions run `37549586195`（build＋device-tests SUCCESS） |
| HEAD | `7f445c9`（docs-only，不改變 code delta 判定） |
| MODE / FREEZE | CORRECTNESS_REPAIR／**FEATURE FREEZE OFF**；P8 production STOP |
| 最新 ACCEPTED slice | CR-011 `c8a59f4`／`37514282914`（quota-slice head namespace Room v15）；CR-012 `ca7cb03`／`37549586195`（Tier-1 test KDoc 引用修復，comment-only） |
| 下一唯一 production slice | **D028-F1**（auto metadata 補抓；ruling 已定：D2-YES charge at lock entry、D3-YES budgets 獨立、passportReader port、slice-union；見 `DECISIONS` D-2026-10-06-030） |
| P1–P7 wave-1 | PROV-001/002/010、MODEL-005/008/009、LMS-001…005 已 ACCEPTED；不得因 Archived 描述重做 |
| PLAY-004 | **PARTIAL**。media transport（image generation／edit／audio transcription）已含入綠基線，但 UI／VM user flow 未驗收，不得升 ACCEPTED |
| Wave-2 cross-cutting | X-016 rollup、X-017 retention 抽象（**永久保留＝D-2026-09-30-006**）、X-018 事件層（Room v10）、X-011 排程骨架、X-024 權限、X-019 NoticeFetcher、X-015 backup 已有 code 落地；acceptance 以 `TRACEABILITY.md` 為準，不得整批宣稱完成 |
| 通知預設值 | **預設 OFF ＋ Settings 總開關／逐規則開關**（D-2026-09-30-005） |
| Telemetry retention | **永久保留、不刪資料**（D-2026-09-30-006） |
| 尚未交付的 X-ID | X-012、X-014、X-020、X-021、X-022、X-023 等依 §1 草案維持 NOT_STARTED／PARTIAL；以 `TRACEABILITY.md` 為準 |
| ASTRA UI | 見 `ACTIVE_TASK.md`；phase-1（AtlasTheme＋元件＋PlaygroundScreen）在樹上，未經使用者視覺驗收 |
| P8 Crawler | STOP；開工門檻與第一個 slice 見 `ACTIVE_TASK.md` |

## Archived（歷史稽核紀錄，狀態已過期）

> 以下三份快照分別以 `79747d2`（2026-09-27）、`77dcd72`（2026-09-29）、`b4a4334`（2026-09-30 wave-2 收尾）為基準，只留作稽核軌跡。與上方 HEAD 現況表或 `ACTIVE_TASK.md` 衝突時，一律以現況表為準。

### 歷史快照 A — 2026-09-27 獨立稽核（Space Bunny）＋ 主 session 審查（基準 `79747d2` ＋ run `36506718096`）

2026-09-27 獨立稽核（Space Bunny）+ 主 session 審查的發現。狀態基準：**code HEAD `79747d2` ＋ 綠 CI run `36506718096`**（build＋device-tests），已包含前序綠基線 `211c389` ＋ run `36461251039`。本文件的「已關」一律以 HEAD 程式碼存在 production caller ＋ 測試 ＋ 綠 CI 為準，不以文件自述為準。

已在本輪修的：F2（讀取側 safe-parse）、F3（ModelsTab 透傳 price/quota）、F7（CheckinEntity.completedAt nullable + VM 走 domain isCheckinPending）、F19（formClearToken 一次性清空）、F20（checkin https 白名單 + try/catch）、F22（chatEndpoint 冪等）、messageFor SUCCESS 分支、serialization 測試 imports、CI workflow。

### 歷史快照 A.1 — 已關閉（closed，附當時 HEAD 證據）

- F6 — `checkinUrl`／`requiresDailyCheckin` 手動 provisioning 路徑：`AdvancedAddForm` checkin 欄位 → `AdvancedAddService` 走 `EndpointPolicy.validate` → `AtlasRepository.setProviderCheckinConfig`（requires=true 必須有 url）→ `ProviderDao.updateCheckinConfig`；`AtlasViewModel.configureCheckin` 有 UI caller；`AtlasPhase3Test.providerCheckinConfigHttpsValidation` 覆蓋 http／缺 url／帶 query 拒絕。Assistant 寫入走同一 repository API，歸 P9 ASST-004／ASST-006。
- F8 — `CheckinEntity` PK 改 `(providerId, date, accountId)`，`MIGRATION_1_2` 帶資料搬遷（單帳號 provider 才綁帳號，多帳號留 legacy `""` 不當 wildcard）。
- F9 — 跨午夜自更新：`AtlasViewModel.registerDateObservers` 有 `ACTION_DATE_CHANGED`／`TIME_CHANGED`／`TIMEZONE_CHANGED` receiver ＋ 下一個本地午夜的 `dateTicker`，每次 `refreshLocalDate()`。
- F12 — 價格／quota 觀測有獨立 evidence 寫入者：`NewApiSnapshotStore.record` 寫 `EvidenceEntity` ＋ `SnapshotFactEntity`（`evidenceId` 為 `snapshot_facts` PK、外鍵指 `evidence.id`）；`saveManualPrice`／`saveManualQuota` 產生 `USER` source 與自己的 evidenceId。
- F14 — `savePrice`／`saveQuota` 已有 scope 路徑：`AtlasRepository` 委派 `NewApiSnapshotStore.saveManualPrice`／`saveManualQuota`（`validateScope` 七維擁有權檢查、price 需 model/endpoint、credential 需 account），落 `snapshot_facts`（append-only）＋ `snapshot_heads`（PK `(scopeKey, kind)`，即七維唯一）＋ 索引 `(scopeKey, kind, observedAt)`，讀取有 `head(scopeKey, kind)`／`history(scopeKey, kind)`。手填與官方分 `MANUAL_PRICE`／`MANUAL_QUOTA`／`PRICE` 等 kind 槽，LOW confidence 永不升為官方 last-good。**殘留**：`PriceDao.upsert(REPLACE)`／`QuotaDao.upsert(REPLACE)` 已無任何 caller（寫入全走 `insert(ABORT)`），屬可刪死碼，非正確性缺口。
- F15 — scope nullable 語意已定義並強制：`validateScope` 要求 providerId/operation 非空、pricing 需 modelId、credentialId 需對應 accountId、pricing 或有 endpoint 時需對應 provider 的 endpoint；`PricingScope.storageKey()` 以長度前綴 + `-1:` 編碼 null，null 不碰撞也不當 wildcard（`NewApiSnapshotStoreTest.nullAndEmptyGroupHaveIndependentHeadsAndHistory`、`accountQuotaUsesNullCredential_tokenQuotasStaySeparate...`）；`PricingPolicy` 對不在 `SUPPORTED_OPERATIONS` 的 operation 一律 `UNKNOWN`。**注意**：scope 擁有權驗證在寫入層 `NewApiSnapshotStore.validateScope`，`PricingPolicy` 本身維持純數字評估器（只讀 input/output/fixed/cache），不重複做 scope 檢查。
- F17 — `CredentialAvailabilityEntity` 改 surrogate `id` ＋ `UNIQUE(offeringId, accountId, credentialId)`（v2 索引同名），多組 (account, credential) 可掛載。
- F18 — token 遮蔽不再只比對完全相等：`redactSecrets` 覆蓋原值、URL-encoded、base64 standard/url、去 padding 變體，`< 8` 字元跳過避免誤殺；且 `OpenAiTransport` 只落 `SAFE_RATE_HEADERS` 白名單內的 header（值再過 redact 並截 128），白名單外的 header 值根本不進 telemetry。`OpenAiTransportTest` 有對應測試。**殘餘（刻意接受）**：未做 token 前後綴／子字串比對，`< 8` 字元的短 token 不遮蔽。
- F21 — 停用／刪除路徑：`CredentialLifecycle.setEnabled`／`delete`（刪除同步 `vault.remove`、metadata 刪除、樣本保留），`AtlasViewModel` 有停用／刪除 caller，`CredentialLifecycleTest` 覆蓋。
- F23 — vault origin 走 `EndpointPolicy.validate` ＋ `EndpointPolicy.origin` 正規化，`store` 與 `bearerToken` 都比對綁定 origin。
- F24 — `app/schemas/dev.modelatlas.app.data.AtlasDatabase/` 已追蹤 `1.json`…`6.json`。
- F25 — `Entities.kt` 過時註解已處理（contracts 內 `TelemetrySource.UNKNOWN` 存在）。
- F27 — `finally` 內 `telemetry.save` 不再覆蓋原失敗：`InferenceEngine` 降級為一次 best-effort retry，失敗以 `addSuppressed` 掛在原 `InferenceFailure` 上；成功路徑的 save 失敗才上拋。`InferenceEngineTest.finalSaveFailureDoesNotMaskTheInferenceOutcome`／`finalSaveFailureSurfacesOnTheSuccessPathAfterOneRetry`。
- F28 — `cancel()` 會標記被截斷的 assistant 訊息（`CANCEL_TRUNCATION_MARK`，UI-only、不進 history、不捏造內容），`AtlasAnonymousTargetTest` 驗證。
- F29 — `recoverInterrupted` 失敗不再永遠 `ready=false`：`AtlasApplication.runStartupRecovery` 有限重試（`MAX_RECOVERY_ATTEMPTS = 3`）＋降級啟動（`ready=true` ＋ `recoveryDegraded`／`initializationError` 可觀察，RUNNING 原樣保留不刪不改）＋ `retryRecovery` 自癒；`AtlasStartupRecoveryTest` 六項覆蓋含「不吞 cancellation」。

### 歷史快照 A.2 — 以下留給對應 Phase（依 `79747d2` 基準；現況可能已被 wave-1/wave-2 推進，以 HEAD 現況表為準）

## Phase 5（Dedicated Adapters）
- F5：`officialCheckedAt`／`observedCheckedAt`／`officialSource`（或獨立 health snapshot 表）仍無任何欄位或寫入者；`ProviderEntity` 只有 `officialHealth`／`observedHealth` 字串，`ProviderDao` 只有 `insert`／`getById`／`updateCheckinConfig`，observed health 尚不能由 telemetry 推導。**P5 程式碼波次（6 adapter ＋ `DedicatedSnapshotSync` ＋ Room v6）已在綠 run `36506718096` 完成，但 F5 未隨之關閉**，仍留此處。

## P5 相依的新 blocker（非 BACKLOG 編號項，但屬 P6 硬性交付）
- **B1 — dedicated sync 無產品呼叫者。** `app/data/DedicatedSnapshotSync.kt` 的 `syncPricing`／`syncOpenRouterPricing`／`syncAmdPricing`／`syncPricingMode`／`syncAccountQuota`／`syncTokenQuota`／`syncOpenRouterKeyQuota`／`syncQuotaPools` 皆無 `AtlasRepository` API、無 `AtlasViewModel` 函式、無 `AtlasScreen` 觸發、無 onboarding 入口（`grep -rn DedicatedSnapshotSync app/src/main/` 除檔案自身外無命中）。因此 ADAPT-001…007 維持 IMPLEMENTED。**解除方式歸 P6**：P6 建立 dedicated sync 的 product flow（repository 寫入 API ＋ ViewModel 狀態 ＋ Models/Provider 卡片同步入口）並補 device test，B1 閉合後 ADAPT 才可轉 ACCEPTED。

## Phase 6（Models）
- F1：`ModelClassification` 仍只有 domain 型別；`ManualClassificationInput` 只存在於 `provider_configurations.snapshotJson`（Advanced Add／Review 顯示），**沒有** `model_classification` 表與三組關聯表，也未接進 `ConnectionRecord`／搜尋／篩選。
- F4：`Outcome` 仍無 `CREDENTIAL_SCOPE` 等細分（列於 `core/inference/Contracts.kt`，`OTHER` 仍同時承載多語意），vault scope mismatch 仍被歸類成一般請求失敗。

## Phase 8（Crawler）
- F10：candidate 零持久化（無 entity／table／DAO／repo／UI）；仍只有 `core/domain` 的 `CrawlerCandidate`／`CandidateState`／`CandidateDecisionReason`。
- F11：`EvidenceEntity` 仍只有 `id`／`requestId`／`providerId`／`modelId`／`outcome`／`status`／`timestamp`／`source`／`version`，**無** `candidateId`／`offeringId`／scope 欄位，淘汰／降級理由仍無法關聯證據鏈。

## 通用 / 待定 Phase
- F16：`PriceSnapshot`／`QuotaSnapshot` 的 domain 預設 `id = ""` 仍被 store 的 `require(snapshot.id.isNotBlank())` 拒絕（`AtlasRepositoryTest.blankPriceSnapshotIdIsRejected`／`blankQuotaSnapshotIdIsRejected` 明確斷言）。store 隨即自己 `newId()` 並覆寫傳入 id，所以呼叫端被迫提供一個「被驗證後丟棄」的非空白 id，與 domain 預設值互相矛盾。要嘛 domain 改為必填／自動生成，要嘛移除這個殘留前置條件。
- F26：`PerformanceSample.streaming`／`InferenceContracts.streaming` 仍恆 `true`，全 repo 無 non-streaming 路徑；驗收「Non-streaming TTFT=null」目前 N/A。

---

### 歷史快照 B — 2026-09-29 橫切稽核之 HEAD 盤點（repair worker D，只盤點不實作；基準 `77dcd72`）

> 稽核者：repair worker D（**只盤點不實作**）。狀態基準：code HEAD `77dcd72`（`git log` 確認）。
> 本節只建立 **requirement ID 草案 ＋ Phase owner ＋ acceptance tests 草案**，全部為 `NOT_STARTED`；未寫一行 production code。
> 契約來源：`PRODUCT_SPEC` §11（Telemetry/Health/Check-in/History）、§16（Background/Notifications/Backup/Retention）、§4／§6（Provider 與 Credential 歷史）、§14（Assistant 可讀清單）、§10（Provenance pipeline）、`IMPLEMENTATION_PLAN` §Room 與 Migration 路徑、§未來僅留契約、§驗收矩陣、`UI_CONTRACT`（Settings 現況）。
> 依 `TRACEABILITY` §Maintenance rule：以下 ID **必須由主 AI 寫入 `TRACEABILITY.md` 的 Cross-cutting 段**才成為正式 requirement；本檔僅為提案來源。

#### 0. HEAD 現況盤點（`77dcd72` 當下實測；**已被 wave-2 落地部分取代**，見 HEAD 現況表）

| 稽核項 | HEAD `77dcd72` 實測 | 依據 |
| --- | --- | --- |
| WorkManager | **完全不存在**。`app/build.gradle.kts` 無 `androidx.work` 依賴；全 repo 0 筆 `WorkManager`／`CoroutineWorker`／`WorkRequest`／`PeriodicWorkRequest` | `rg` 全 repo 0 hit |
| Notification | **完全不存在**。`AndroidManifest.xml` 僅 `INTERNET` 權限，**無 `POST_NOTIFICATIONS`**；全 repo 0 筆 `NotificationManager`／`NotificationChannel` | manifest + `rg` 0 hit |
| Backup/export | **無使用者可觸發的匯出**。`android:allowBackup="false"` 僅關閉 OS 自動備份（安全面正確），但無任何 `ACTION_SEND`／寫檔／JSON 匯出路徑 | manifest ＋ `rg` 0 hit（`export` 命中全為測試/無關字樣） |
| Retention / rollup | **無任何 abstraction**。全 repo 0 筆 `RetentionPolicy`／`rollup`／`prune`／`purge` 的 production 實作；`SampleDao` 只有 `observeLatest() LIMIT 100`／`running()`／`getById`／`countById`，**無聚合查詢、無刪除路徑** | `Daos.kt:156-171` |
| PerformanceSummary | **無**。`ModelRow.success1h`／`success24h`／`tps`／`ttftMs`／`latencyMs`／`priceChangeCount`／`freeSinceAt` 全欄在 `ModelRowMapper.buildRows`（`ModelRowMapper.kt:231-259`）**從未被賦值**，永遠 `null`（= 未知）。`AtlasRepository` 無任何效能聚合 API | `ModelRowMapper.kt` 僅 KDoc line 26 提及，無賦值 |
| ProviderNotice / Event / PriceEvent | **無 entity**。`AtlasDatabase` 25 個 entity（v8）全列於 `AtlasDatabase.kt:38-63`，**無 `ProviderNotice`／`ProviderEvent`／`PriceEvent`／`QuotaEvent`／`HistoryEvent` 表**。`IMPLEMENTATION_PLAN` 早已把 `PriceEvent`／`ProviderNotice`／`Event` 列為「隨階段增加」但未落地 | `AtlasDatabase.kt:37-65` |
| Event producer | **無**。`DedicatedContracts.kt:141` 有 `FETCH_NOTICE` capability enum 值，但**無 adapter 實作**；`ProbeExecutors.kt:954` 硬寫 `noticeFetched = false` | `ProbeExecutors.kt:940-954` |
| Settings | **僅一個靜態文字 AlertDialog**。`AtlasScreen.kt:364-400` 只有 `settings_body` 說明字串 ＋ LMSpeed 同步按鈕；**無任何可變更設定狀態、無持久化**。`strings.xml` `settings_body` 宣稱「保留政策」但程式碼**無 retention 機制**，屬文字與實作不一致 | `AtlasScreen.kt:364-400`、`strings.xml:12` |
| History（credential/model/provider） | **無**。`AtlasRepository.samples` 走 `observeLatest() LIMIT 100`（`ASSISTANT_P9_PREP` C.16 已記）；`modelDetailStateFor`（`AtlasViewModel.kt:1459-1470`）只透傳 row 的 price/quota/performance，**priceHistory／quotaPools／history 從不載入** | `AtlasViewModel.kt:1459-1470` |
| Stale safety / REMOVED | **無 REMOVED 判定機制**。`ManualOfferingStore.downgradeOnFailure` 的 `removed` 恆 `false`（正確的保守行為），但也因此**從不宣告 REMOVED**；§16「多次 successful catalog snapshots 才能判 REMOVED」的計數器不存在 | `ManualOfferingStore.kt:41,133,146` |

**結論（`77dcd72` 當下）**：§16 四個子題（Background／Notifications／Backup／Retention）當時在 HEAD 零實作，且 §11 History 的 Event 層與 PerformanceSummary 亦零實作。wave-2 已部分落地（見 HEAD 現況表），本表僅留作稽核軌跡。

### 歷史快照 C — Wave-2 收尾紀錄（2026-09-30 主 AI；基準 `b4a4334` ＋ run `36664972498`）

#### 7. Repair wave-2 收尾（原文）

- 綠基線：code `b4a4334` ＋ run `36664972498`（build＋device-tests）。**已被 `0e3ff953` ＋ run `36680926920`（最新完整綠）與其後的紅 HEAD `ff7fe75c` 取代。**
- 實作落地：X-016 rollup／X-017 retention（null 未決）／X-018 事件層 v10／X-011 排程骨架（work-runtime 已加，doWork 經 SyncWorkerLogic，pipeline runner 待接真實 sync）／X-024 權限／X-019 NoticeFetcher／X-015 backup／UI 空態統一（兩 variant 並存皆有 caller，非死碼）／ProviderDetailScreen＋導航／F D5 去重。
- 回退：PLAY-004 payload 實作因破壞核心編譯已整批回退，PLAY-004 維持 PARTIAL（dispatch＋最小 kind 映射保留）。**其後 `3cfb523c` 重新落地 media transport 並在 `ff7fe75c` 註冊，現為 implemented-under-test（CI 紅）。**
- ASTRA UI phase-1（AtlasTheme＋元件＋PlaygroundScreen）在樹上，未經使用者視覺驗收；phase-2 待 astra 額度。
- P8 仍 STOP：待 PLAY-004 真實 payload 方案、X-IDs caller 覆核（SyncWorker runner、Notice→EventStore 接線）、ASTRA 視覺驗收。

---

## 橫切稽核 X-ID 草案（2026-09-29 提案，仍為本檔的有效 backlog 內容）

> 快照 B 的盤點表與下列各條目的「現況」欄皆以 `77dcd72` 為歷史基準，可能落後 wave-2；X-ID 是否已正式成立／acceptance 一律以 `TRACEABILITY.md` 為準。§5 的兩個待決策事項已於 2026-09-30 由 D-2026-09-30-005／D-2026-09-30-006 結案。

## 1. Requirement ID 草案（X-011 … X-024）

> 編號接續 `TRACEABILITY` Cross-cutting 既有的 `X-001…X-010`。
> **Phase owner 欄位代表「建議交付時機與歸屬」**，非指派特定 worker。標 `可平行` 者不阻擋當前 P1–P7 repair 週期。

### 群組 A — Background sync（§16 第 1 段）

- **X-011 WorkManager background sync 排程基礎**
  - Spec：`PRODUCT_SPEC` §16「Background 使用 WorkManager；不要承諾 24/7 每分鐘可靠」；§2 技術棧明列 WorkManager。
  - Phase owner：**P11 新增（Background & Notifications）**，建議排在 P8 Crawler 之後；`可平行`（不依賴 crawler 資料模型）。
  - 現況（歷史，`77dcd72`）：gradle 無 `androidx.work`；全 repo 0 筆 WorkManager 類別。**2026-09-30：骨架已落地（work-runtime ＋ SyncWorkerLogic），pipeline runner 待接真實 sync。**
  - Scope：新增 `androidx.work:work-runtime-ktx` 依賴、`SyncScheduler`（`enqueueUniquePeriodicWork` + `ExistingPeriodicWorkPolicy.KEEP`）、`SyncWorker`（`CoroutineWorker`）、`AtlasApplication` 內註冊排程。**不得**用 `AlarmManager`／`JobScheduler` 自製輪詢當替代。
  - Acceptance tests 草案：
    1. `WorkManagerSyncSchedulingTest.scheduleUsesUniquePeriodicWorkAndKeepsExisting` — 連續 `schedule()` 兩次只存在**一筆** unique work，且 policy = `KEEP`（不重置週期）。
    2. `SyncWorkerTest.syncFailureDoesNotClearLastGood` — worker 內部 sync 丟例外時，`snapshot_heads.lastGoodObservedAt`／`lastGoodEvidenceId` 與呼叫前**逐欄相等**（X-006）。
    3. `SyncWorkerTest.syncFailureNeverDeletesOfferings` — 失敗後 `offerings` 筆數與 `visible` 旗標不變，**不出現 REMOVED**。
    4. `SyncWorkerTest.retryReturnsResultRetryWithoutClearingState` — 回傳 `Result.retry()` 不動資料。
    5. `WorkManagerSyncSchedulingTest.constraintsRequireNetworkAndBatteryNotLow` — `Constraints` 網路條件存在；且**不**設定需要 `UNMETERED`（否則熱點下永不同步，違反「不要承諾 24/7」的務實語意）。
    6. 負向：`SyncWorkerTest` 中 worker **不得**直接刪 `sample_facts`／`snapshot_facts`（靜態審查項，reviewer checklist）。
- **X-012 Stale safety / REMOVED 判定**
  - Spec：§16「一次 failure 不刪 model/pricing；多次 successful catalog snapshots 才能判 REMOVED；group unavailable != model removed」。
  - Phase owner：**P11**；與 X-011 同一 worker 可並行，`可平行`。
  - 現況（歷史，`77dcd72`）：`removed` 恆 `false`（`ManualOfferingStore.kt:41`），無計數器、無 `catalog_snapshots` 表。
  - Scope：`CatalogSnapshotEntity`（provider／account／credential 維度 ＋ `fetchedAt` ＋ `success` ＋ model 清單雜湊）＋ `CatalogRunDao`；`RemovedDecision.evaluate(consecutiveSuccessfulAbsences, required)` 純函式（門檻常數不得 hard-code 為 1，須 named const ＋ 說明來源）；`ManualOfferingStore` 升級為「缺席計數」而非直接 `removed`。
  - Acceptance tests 草案：
    1. `RemovedDecisionTest.singleAbsenceNeverMarksRemoved` — 缺席 1 次 `removed=false`。
    2. `RemovedDecisionTest.absentForNConsecutiveSuccessfulSnapshotsMarksRemoved` — 連續 N 次**成功** catalog 缺席 → `removed=true`。
    3. `RemovedDecisionTest.absentThenFailedSnapshotResetsCounter` — 中間插入一次 **failed** snapshot → 計數歸零（失敗不得累加缺席票）。
    4. `RemovedDecisionTest.groupUnavailableIsNotModelRemoved` — 該 group 不可用但 model 在其他 group 可用 → `removed=false`（§16 明文）。
    5. `CatalogSnapshotStoreTest.removalIsPerCredentialNotGlobal` — 不同 credential 的缺席票**互不覆蓋**（X-004 隔離）。
    6. 負向：無任何路徑因 **fetch error／timeout／空回應** 而增加缺席票。

### 群組 B — Notifications（§16 第 2 段）

- **X-013 Notification rule / settings 架構**
  - Spec：§16「Notifications 先做 rule/settings architecture，可支援：became FREE NOW / quota low / pending check-in / favorite recovered / important price change / outage/recovery」；`IMPLEMENTATION_PLAN` §未來僅留契約。
  - Phase owner：**P11**；`可平行`。
  - 現況（歷史，`77dcd72`）：無 `POST_NOTIFICATIONS` 權限、無 rule 型別、無設定持久化。
  - Scope：`NotificationRule`（`ruleId` ＋ `enabled: Boolean?` nullable ＋ `threshold` ＋ `scope` ＋ `channels`）、`NotificationSettingsStore`（Room 表）、`SettingsScreen` 從現有靜態 AlertDialog 升級為真實設定頁。
  - **決策護欄**：`enabled` 必為 nullable；首次安裝不得寫入非使用者明確設定的值；UI 對 `null` 顯示「未設定（依預設）」，不得以 `?: true` 塌縮成開啟。**2026-09-30 決策更新：預設值已定為 OFF（`DECISIONS` D-2026-09-30-005）**——dispatch 層預設常數即 OFF，Settings 提供總開關＋逐規則開關；程式碼不得把預設塌縮成開啟，測試斷言預設 OFF。
  - Acceptance tests 草案：
    1. `NotificationSettingsStoreTest.freshInstallLeavesEveryRuleUndecided` — 全新 DB 讀出全部 `enabled == null`。
    2. `NotificationSettingsStoreTest.noRuleDefaultsToTrueOrFalseOnRead` — 讀取層**不得**回傳非 null（防讀取層塌縮）。
    3. `NotificationRuleTest.setExplicitTrueAndFalseRoundTrips` — 使用者明確設定後可持久化並讀回。
    4. `NotificationRuleTest.undecidedRuleUsesDocumentedDefaultAtDispatchTimeOnly` — dispatch 層才套用預設，且該預設是**單一常數函式**＝OFF（D-2026-09-30-005），可被 reviewer 一眼審查。
    5. `NotificationRuleTest.allSevenSpecRulesHaveIds` — §16 六類＋pending check-in 皆具 `ruleId`，缺一即 fail（防止「架構存在但漏 rule」）。
    6. 負向：`SettingsScreen` 不得顯示「已開啟」字樣於 `enabled == null` 的 rule。
- **X-014 事件偵測與投遞管線**
  - Spec：§16 rule 清單；§10 pipeline `Raw Evidence → … → Event`；§1「FREE_NOW 仍走完整 telemetry」。
  - Phase owner：**P11**，**依賴 X-018（事件層）**；不可早於 X-018 交付。
  - Scope：`NotificationDispatcher`（評估 rule → 產生 `NotificationRequest`）；事件來源必須來自**真實觀測**（`snapshot_facts` 的 price/quota 轉換、`samples` 的 outage/recovery、`CheckinDao` 的 pending）。
  - Acceptance tests 草案：
    1. `NotificationDispatcherTest.becameFreeNowFiresOnlyOnActualStateTransition` — PAID→FREE_NOW 才發；FREE_NOW→FREE_NOW 不重複轟炸。
    2. `NotificationDispatcherTest.undecidedRuleDoesNotFire` — `enabled == null` 時套用 D-2026-09-30-005 預設 OFF，不發送。
    3. `NotificationDispatcherTest.quotaLowThresholdNotGuessed` — 未設定門檻時不發 `quota low`。
    4. `NotificationDispatcherTest.priceChangeRequiresTwoDistinctKnownPrices` — 只有**兩個已知**價格才判「important price change」；舊值 `null`（未知）時不發（X-001）。
    5. `NotificationDispatcherTest.outageRequiresConsecutiveFailuresNotSingle` — 單次失敗不發 outage（§1 一次 failure 不抹 last-good 的同源語意）。
    6. `NotificationDispatcherTest.permissionDeniedIsSilentNotCrash` — 無 `POST_NOTIFICATIONS` 時記錄「未投遞」狀態，不 crash、不假裝已送。
    7. 負向：payload 不得含 raw key／PAT／完整 response body（X-003）。

### 群組 C — Backup / export（§16 第 3 段）

- **X-015 safe backup-export**
  - Spec：§16「Backup 可匯出 provider definitions、endpoint profiles、aliases、notes、settings、recipes/research knowledge；secrets 預設不可 plaintext export」。
  - Phase owner：**P11**；`可平行`（但 recipes/research knowledge 欄位須留可擴充，見下）。
  - 現況（歷史，`77dcd72`）：無任何匯出路徑。**2026-09-30：BackupExporter 已落地（wave-2），acceptance 待覆核。**
  - Scope：`BackupExporter`（純函式 `AtlasBackup` → JSON）＋ `BackupSchema`（version 欄位）＋ `ACTION_CREATE_DOCUMENT` 分享。
  - **明確邊界**：
    - 可匯出：provider／account／endpoint profile／canonical model／alias／classification／notes／tags／checkin 設定／notification settings／（**若 P10 落地後**）recipes。
    - **絕不匯出**：`AndroidCredentialVault` 的 ciphertext／iv／SharedPreferences（`"credential_ciphertexts"`）、任何 `SafeCredentialId` 以外的憑證識別、telemetry 原始 prompt。
    - 匯出檔內的 credential 一律以 `safeCredentialId` 佔位匯入需使用者重新輸入 secret。
  - Acceptance tests 草案：
    1. `BackupExporterTest.exportContainsNoVaultCiphertextOrIv` — 全文掃描不得出現 vault key 名稱前綴／base64 密文。
    2. `BackupExporterTest.exportOmitsRawSecretsAndKeepsSafeCredentialRefs` — 含 `safeCredentialId`，不含 raw key。
    3. `BackupExporterTest.roundTripRestoresProviderEndpointsAliasesAndNotes` — 匯出→匯入後 provider／endpoint／alias／notes 全等。
    4. `BackupExporterTest.exportDeclaresSchemaVersion` — 檔案含 `schemaVersion`，未知版本匯入 fail-closed。
    5. `BackupExporterTest.manualPricingExportedAsUserProvenanceNotOfficial` — 手填價格匯出後 provenance 仍是 `USER`，不得升格 official（X-005）。
    6. `BackupExporterTest.recipesFieldIsOptionalWhenP10Absent` — P10 未落地時該欄省略而非寫空字串冒充「有 recipes」。
    7. 負向：`AndroidManifest` 不得為此目的把 `allowBackup` 改成 `true`（那是 OS 層全量備份，會連 vault 一起捲走，違反 X-003）。

### 群組 D — Performance / rollup / retention（§11 + §16 第 4 段）

- **X-016 PerformanceSummary 聚合**
  - Spec：§11「TTFT/TTFB/TPS、observed RPM / input TPM / output TPM / total TPM、success rate / latency / request count、HTTP error / 429 rate」＋「Observed RPM/TPM 只是本地觀測，不是 provider limit」。
  - Phase owner：**P11**；`可平行`。
  - 現況（歷史，`77dcd72`）：`ModelRow` 七個效能欄恆 `null`；`SampleDao` 無聚合查詢。**2026-09-30：rollup 已落地（wave-2），acceptance 待覆核。**
  - Scope：`PerformanceSummaryDao`（`GROUP BY` + 視窗函數，**在 SQL 層算**，不拉全表到 Kotlin）；`SampleDao.aggregate(scope, window)`；`ModelRowMapper` 改為消費聚合結果。
  - Acceptance tests 草案：
    1. `PerformanceSummaryTest.successRateCountsOnlyTerminalOutcomes` — `RUNNING` 不計入分母（否則進行中請求會拉低成功率，X-001 精神）。
    2. `PerformanceSummaryTest.windowBoundaryIsHalfOpenAndPinnedToClock` — 注入假 clock，邊界不含歧義。
    3. `PerformanceSummaryTest.emptyWindowReturnsNullNotZero` — 無樣本 → `null`（**不得回 0**，X-001）。
    4. `PerformanceSummaryTest.observedRatesAreNeverLabelledAsProviderLimits` — 聚合結果欄位名／型別不含 `limit`／`max`，且下游 UI 標「本地觀測」。
    5. `PerformanceSummaryTest.aggregationIsIsolatedByAccountAndCredential` — 跨帳號／跨憑證不混合（X-004）。
    6. `PerformanceSummaryTest.cancelledRequestsCountedExplicitlyNotAsSuccess` — `CANCELLED` 單獨分類，不混入成功也不混入伺服器錯誤。
    7. 負向：聚合查詢**不得**使用 `LIMIT` 截斷後再算比率（會產生錯誤分母）。
- **X-017 Telemetry rollup abstraction ＋ RetentionPolicy（永久保留，不做定時刪除）**
  - Spec：§16「Telemetry retention 要有 abstraction/rollup」；`IMPLEMENTATION_PLAN` retention 契約。
  - Phase owner：**P11**；`可平行`。
  - 現況（歷史，`77dcd72`）：無 rollup、無 retention、無刪除路徑。**2026-09-30：retention 抽象已落地（wave-2），acceptance 待覆核。**
  - Scope：`RetentionPolicy` 型別（`rawSampleWindow: Duration?` ＋ `rollupBuckets` ＋ `deletesRawEvidence: Boolean = false`）＋ `RollupStore`（把舊樣本聚合成 `performance_rollups`）＋ `RetentionRunner`。**不實作自動刪除排程**，只交付 abstraction 與可注入 policy。
  - **決策護欄**：**2026-09-30 決策更新：`DECISIONS` D-2026-09-30-006 定為永久保留、不刪任何資料**——`rawSampleWindow` 恆 `null`、`deletesRawEvidence` 恆 `false`，`RetentionRunner` 對產品資料是 no-op，purge 路徑不啟用；rollup 抽象僅供聚合查詢效能。若未來 DB 體積造成裝置問題，另立決策處理，不得靜默刪資料。
  - Acceptance tests 草案：
    1. `RetentionPolicyTest.defaultPolicyDeletesNothing` — 預設 policy 跑完後 `samples` 筆數不變。
    2. `RetentionRunnerTest.nullWindowIsNoOp` — `rawSampleWindow = null` → 無刪除呼叫。
    3. `RollupStoreTest.rollupPreservesAggregatesWhileRawIsPruned` — 刪 raw 後聚合查詢結果**與刪除前等值**（僅在未來另立決策允許 purge 時適用）。
    4. `RollupStoreTest.rollupNeverDeletesRunningOrTerminalEvidenceRows` — `RUNNING` 與 evidence 一律保留（FND-006／F29 語意）。
    5. `RetentionRunnerTest.pruneIsScopedPerProviderAndNeverGlobal` — 不得無條件全表刪。
    6. 靜態審查項：repo 內**無**裸數字天數常數（reviewer 用 `rg` 掃 `val .*Days = \d+` 類模式）。
    7. 負向：`RetentionPolicyTest.rawEvidenceDeletionIsOptInAndDefaultsOff` — `deletesRawEvidence` 預設 `false`。
- **X-018 事件層：ProviderNotice / ProviderEvent / PriceEvent / QuotaEvent**
  - Spec：§10 pipeline 末端 `… → Snapshot → Event`；§11「History 至少能保存 free/paid 轉換、price/quota、outage/recovery、success/performance」；`IMPLEMENTATION_PLAN`「隨階段增加 … PriceEvent/QuotaPool、ProviderNotice/Event」；§14 Assistant 可讀 `Notices`／`Events`。
  - Phase owner：**P11**（`可平行`）。
  - 現況（歷史，`77dcd72`）：25 entity 中無任何 notice／event 表；`FETCH_NOTICE` 無實作；`ProbeExecutors` 硬寫 `noticeFetched = false`。**2026-09-30：事件層已落地（Room v10，wave-2），acceptance 待覆核。**
  - Scope：Room 遷移（只加表、不改既有欄、無 destructive）＋ `ProviderNoticeEntity`（provider／observedAt／expiry／source／evidenceId）＋ `PriceEventEntity`（scopeKey／from／to／observedAt／evidenceId，**append-only**）＋ `QuotaEventEntity` ＋ `ProviderEventEntity`（類型：OUTAGE／RECOVERY／NOTICE）＋ 對應 DAO ＋ `EventStore`（從既有 `snapshot_facts` 與 `samples` **衍生**事件，非另抓資料）＋ schema json 納管 ＋ 對應 migration。
  - Acceptance tests 草案：
    1. `EventStoreTest.priceTransitionEmitsEventWithBothKnownValues` — 舊新皆有值才發事件；舊值 `null` → 不發。
    2. `EventStoreTest.unknownNeverBecomesZeroInEventPayload` — 事件欄位 null 保持 null（X-001）。
    3. `EventStoreTest.eventsAreScopeIsolatedSevenDimensions` — 同時點不同 account/credential/group/operation 不互相覆蓋（X-004）。
    4. `EventStoreTest.provenanceSourceIsCarriedAndNotUpgraded` — 事件 source 為 `MANUAL` 時不得升為 `OFFICIAL`（X-005）。
    5. `EventStoreTest.syncFailureEmitsNoEventAndKeepsLastGood` — 失敗路徑不產生轉換事件（否則會記錄不存在的狀態變化）。
    6. `EventStoreTest.outageRequiresHealthEvidenceNotSingleFailure` — 單次失敗不產 OUTAGE。
    7. migration：舊 rows 全保留、無 destructive migration，且 `AtlasMigrationTest` 全路徑鏈到最新版。
    8. 負向：`ProviderNoticeEntity` 不得存 raw response body（僅 ref ＋摘要，X-003）。
- **X-019 Notice 抓取（補 `FETCH_NOTICE` 空缺）**
  - Spec：§9 capability 清單含 `fetchNotice`；§4 Overview「Check-in / Stability / Notices」；§5 Quick Add「notice/about/docs」。
  - Phase owner：**P11**；`可平行`。
  - 現況（歷史，`77dcd72`）：`DedicatedContracts.kt:141` 有 `FETCH_NOTICE` enum 但無實作；`ProbeExecutors.kt:954` 硬寫 `noticeFetched = false`。**2026-09-30：NoticeFetcher 已落地（wave-2），acceptance 待覆核。**
  - Scope：至少 Generic OpenAI 與 New API 路徑實作 `fetchNotice`；不支援者**回 Unsupported 而非 false**（PROV-002 修復語意）。
  - Acceptance tests 草案：
    1. `ProbeExecutorTest.noticeStepAttemptsFetchForCapableAdapter` — 支援者 `noticeFetched = true` 且有 evidence。
    2. `ProbeExecutorTest.noticeStepReportsUnsupportedForIncapableAdapter` — 不支援者明確 `UNSUPPORTED`，**不得**回 `noticeFetched = false` 冒充「抓過但沒有」。
    3. `ProbeExecutorTest.noticeFailureDoesNotBlockOnboardingCommit` — notice 失敗仍可進 Review（X-008：部分成功語意）。
    4. 負向：notice 內容不得直接寫入 `ProviderNoticeEntity` 的長文字欄（須截斷 ＋ 存 ref）。

### 群組 E — History 讀取模型（§4 / §6 / §11 / §14）

- **X-020 model price / quota / history 真實讀取（MODEL-009 的資料基礎）**
  - Spec：§4 Provider Detail「History」分頁；§10 provenance 分層（Official／Local／LMSpeed／Web／User 分欄）；§11 History。
  - Phase owner：**併入 P6 MODEL-009 修復**（owner 見 §3）。**2026-09-30：MODEL-009 已 ACCEPTED（wave-1），本條剩餘範圍以 `TRACEABILITY.md` 為準。**
  - 現況（歷史，`77dcd72`）：`modelDetailStateFor`（`AtlasViewModel.kt:1459-1470`）不傳 `priceHistory`／`quotaPools`／`extraEvents`，故 `ModelDetailStateBuilder` 這三個參數**恆空**。
  - Acceptance tests 草案（接 `ModelDetailTest`／`ModelDetailEdgeTest` 既有 24 項）：新增 device test `ModelDetailHistoryLoadTest`：從 `snapshot_facts` 讀該 scope 的 price 序列 → `DetailPricePoint`；quota 序列 → `DetailQuotaPool`（**不合併不同 scope 的 pool**，§10「多 pool 不做假平均」）；official 與 external 分欄不混。
- **X-021 credential / account history**
  - Spec：§6「last success/failure / health、quota association、visible / usable models」；§14 Assistant 可讀 safe credential metadata。
  - Phase owner：**P11**；`可平行`。
  - 現況（歷史，`77dcd72`）：`CredentialEntity` 有 `lastSuccess`／`lastFailure` 欄位（`Entities.kt:53`）但**無任何寫入者**（`CredentialDao` 僅 `insert`／`getById`／`updateCheckinConfig` 等）。
  - Scope：`CredentialDao.recordOutcome(credentialId, success, at)`，由 `InferenceEngine` 遙迴／或由 `CredentialLifecycle` 統一寫；`credential_history` 視圖或 `performance_rollups` 借用。
  - Acceptance tests 草案：
    1. `CredentialHistoryTest.successfulRequestUpdatesLastSuccessOnly` — 成功只動 `lastSuccess`。
    2. `CredentialHistoryTest.failureUpdatesLastFailureAndHealth` — 失敗動 `lastFailure` ＋ `health`，**不清** `lastSuccess`。
    3. `CredentialHistoryTest.outcomeIsRecordedPerCredentialNotPerProvider` — 多憑證互不覆蓋（X-004）。
    4. `CredentialHistoryTest.freeNowRequestAlsoUpdatesHistory` — FREE_NOW 走完整路徑（X-002）。
    5. 負向：history 不得記錄 raw key（只存 `safeCredentialId`）。
- **X-022 provider history / 跨 session query history**
  - Spec：§4 Provider Detail History 分頁；§11 History；§14 Assistant `Query History` 可讀；`ASSISTANT_P9_PREP` C.10（`historyByConnection` 記憶體上限 20、重啟即失）。
  - Phase owner：**P11**；`可平行`。**注意與 PLAY-005 的邊界**：PLAY-005 已 ACCEPTED「chat memory separated from telemetry persistence」，因此查詢歷史**不得**寫進 `samples`（那是效能表）。需獨立 `query_history` 表。
  - Acceptance tests 草案：
    1. `QueryHistoryStoreTest.queryHistoryIsPersistedSeparatelyFromSamples` — 送出的訊息不進 `samples` 表（PLAY-005 回歸保護）。
    2. `QueryHistoryStoreTest.historySurvivesAppRestart` — 重建 repository 後仍可讀。
    3. `QueryHistoryStoreTest.historyIsScopedPerConnection` — 切換連線不混雜。
    4. `QueryHistoryStoreTest.promptContentNeverEntersTelemetryOrSamples` — 查詢歷史內容不進 `samples`／`evidence`（X-003 + 隱私）。
- **X-023 assistant 可讀 history surface**
  - Spec：§14 可讀清單含 `History`／`Performance`／`Notices`／`Events`。
  - Phase owner：**P9 ASST-002**（**不屬 P11**；列此僅標明依賴方向）。
  - 現況（歷史，`77dcd72`）：`ASSISTANT_P9_PREP` 已記 C.16（`LIMIT 100` 誤報無資料）＋「Notices/Events entity 不存在」。
  - Acceptance tests 草案：`ASST-002` 驗收時須含「history 讀取不誤報空資料」；依賴 X-016（解除 `LIMIT 100`）與 X-018（notice/event 可讀）。

### 群組 F — 權限與基礎設施

- **X-024 Notification runtime 權限基礎**
  - Spec：§16（投遞前提）。
  - Phase owner：**P11**；`可平行`；**X-014 的硬前提**。
  - 現況（歷史，`77dcd72`）：無 `POST_NOTIFICATIONS`。**2026-09-30：權限已落地（wave-2），acceptance 待覆核。**
  - Scope：`AndroidManifest` 加 `POST_NOTIFICATIONS`（API 33+ runtime 請求，minSdk 26 需版本守衛）＋ `NotificationChannel` 建立。
  - Acceptance tests 草案：
    1. `NotificationPermissionTest.channelIsCreatedWithNoSecretsInMetadata` — channel 描述不含 provider 憑證資訊。
    2. device test：API 33+ 未授權時 `dispatch` 回「未投遞」而非 crash。

## 2. Phase owner 建議總表

| ID | 標題 | 建議 Phase owner | 依賴 | 可與當前 repair 週期並行 |
| --- | --- | --- | --- | --- |
| X-011 | WorkManager background sync 基礎 | **P11（新增）** | X-018（可先做骨架） | ✅ |
| X-012 | Stale safety / REMOVED 判定 | **P11** | X-011 | ✅ |
| X-013 | Notification rule/settings 架構 | **P11** | — | ✅ |
| X-014 | 事件偵測與投遞管線 | **P11** | **X-013, X-018, X-024** | ❌ |
| X-015 | safe backup-export | **P11** | — | ✅ |
| X-016 | PerformanceSummary 聚合 | **P11**（建議提前） | — | ✅ |
| X-017 | rollup ＋ RetentionPolicy abstraction | **P11** | X-016 | ✅ |
| X-018 | ProviderNotice/Event/PriceEvent | **P11** | — | ✅ |
| X-019 | `fetchNotice` 實作 | **P11** | **PROV-002 修復** | ⚠ 部分 |
| X-020 | model history 真實讀取 | **P6 MODEL-009 修復** | X-018（可先做 price 部分） | ⚠ 已排程 |
| X-021 | credential/account history | **P11** | — | ✅ |
| X-022 | provider / query history | **P11** | — | ✅ |
| X-023 | assistant 可讀 history | **P9 ASST-002** | X-016, X-018 | ❌ |
| X-024 | Notification runtime 權限 | **P11** | — | ✅ |

**排程建議**：P1–P7 repair 週期**不受本節阻擋**。X-011／X-013／X-015／X-016／X-017／X-018／X-021／X-022／X-024 為**唯讀相依或獨立**，可在 repair 週期尾聲並行委派。X-014／X-023 需等上游。

## 3. 與既有 TRACEABILITY 條目的關係（避免重複建案）

- **X-016 應優先於 MODEL-005**：MODEL-005（Filter Sheet）已標 PARTIAL，repair 範圍含「從 telemetry 推導 availability」。但 `ModelRow.success1h/success24h/tps/ttftMs/latencyMs` **永遠是 `null`**，所以即使修好 `FilterAvailability` 傳參，success 1h/24h 與 TPS/TTFT 進階 filter 仍永遠 unavailable。**建議：MODEL-005 的 availability 修復只處理價格／分類／selected-key 維度；效能維度掛 X-016**，避免 MODEL-005 worker 順手寫一個假的效能聚合。
- **X-020 是 MODEL-009 的資料基礎而非替代**：MODEL-009 修復需要 `snapshot_facts` 讀取能力，該能力已存在（`SnapshotFactDao.history`）。故 **MODEL-009 修復不必等 X-018**；但若要呈現 notice/event 類歷史，才需 X-018。建議 MODEL-009 先用既有 `snapshot_facts` 修復，X-018 之後再接 event 類型。
- **F5 未被本節涵蓋**：`ProviderEntity` 仍無 `officialCheckedAt`／`observedCheckedAt`／`officialSource`。X-016 只做 observed 側聚合，official 側欄位歸屬仍留原 F5（Phase 5）條目，**不得**在 X-016 順手加欄而讓 F5 懸空。
- **§16 不屬任何既有 Phase**：P1–P10 的定義（見 `PRODUCT_SPEC` §0）皆未涵蓋 Background／Notifications／Backup／Retention。建議主 AI 在 `TRACEABILITY.md` 正式建立 **Phase 11 — Background / Notifications / Backup / Retention**，並將本節 X-011…X-024 掛入；否則 §16 會持續是「文件有、ID 無」的無主缺口。

## 4. F4 / F16 / F26 三查：是否 correctness blocker

> 判準（依 `ORCHESTRATION` §永久鐵律 ＋ `PRODUCT_SPEC` §1 ＋ `DELIVERY_MATRIX` §全域驗收 gate）：是否會導致**錯誤事實被寫入**、**secret 外洩**、**scope 互相覆蓋**、**last-good 被清除**、或**Unknown 被偽造成 0／Free／Success**。

### F4 — `Outcome` 缺少 scope 細分（`OTHER` 多義）→ **不是 blocker，留 owner**

- 事實：`Outcome` 13 態無 `CREDENTIAL_SCOPE`（`core/inference/Contracts.kt:36`）。vault origin 不符時 `check(...)` 丟 `IllegalStateException`（`AndroidCredentialVault.kt:58`），在 `InferenceEngine`（`InferenceEngine.kt:91-93`）落入 `else -> Outcome.OTHER`。
- 為何**不是** correctness blocker：
  1. **沒有偽造事實**——`OTHER` 是誠實的「未分類」，不是錯標為 `SUCCESS` 或 `FREE`。
  2. **沒有資料損毀**——telemetry 仍照常落庫，欄位完整，只是 error category 粒度不足。
  3. **不影響 scope 隔離**——vault 在**送出前**就擋掉，錯誤從未到達 provider，不存在跨 scope 污染。
  4. `AtlasRepositoryTest`／`CredentialBoundaryTest` 已覆蓋 vault scope mismatch 的**丟出**行為，邊界本身沒破。
- 判定：**MAJOR 非 BLOCKER**。屬錯誤可診斷性（diagnosability）問題，不屬正確性。
- 建議 owner：**併入 P11**（與 X-014 通知事件分類一起做，因為「outage 通知」最需要能區分 credential 設定錯 vs provider 故障）。若主 AI 要提前，可併入任何 repair 波次的附帶修復，但**不得**為此降級任何 contract 或改既有測試 expectation。

### F16 — `PriceSnapshot.id` / `QuotaSnapshot.id` 契約矛盾 → **不是 blocker，留 owner**

- 事實：domain 預設 `id = ""`（`Domain.kt:176,191`），store `require(id.isNotBlank())`（`NewApiSnapshotStore.kt:366,380`）隨即 `newId()` 覆寫。
- 為何**不是** correctness blocker：
  1. store **永遠覆寫**傳入 id，實際落庫 id 唯一且正確，無重複寫入風險。
  2. `AtlasRepositoryTest.blankPriceSnapshotIdIsRejected`／`blankQuotaSnapshotIdIsRejected` **明確斷言**此前置條件——測試與實作一致，CI 是綠的。
  3. 無資料遺失、無 secret 風險、無 scope 覆蓋。
  4. 目前 production caller 只有 `AtlasRepository.savePrice`／`saveQuota`（僅測試呼叫），實際主流程（New API／Dedicated）都自己 `newId()` 後才呼叫，**未受影響**。
- 判定：**MINOR 非 BLOCKER**，純 API 契約整潔度問題。
- 建議 owner：**P11 文件債清理**，與 F16 併檔處理（移除該 require 或把 domain 改為必填）。**注意**：這是唯一允許「改測試 expectation」的情形之一，但**必須是為了讓 domain 與 store 契約自洽**，且需在 diff 中明確說明理由——不是為了讓 CI 變綠而放寬。

### F26 — `streaming` 恆 `true`，無 non-streaming 路徑 → **不是 blocker，但有明確 prerequisite**

- 事實：`InferenceContracts.streaming` 預設 `true`（`Contracts.kt:84`）、`SampleEntity.streaming` 預設 `true`（`Entities.kt:182`），全 repo 無 non-streaming 呼叫路徑。
- 為何**現在**不是 blocker：
  1. 目前**所有** inference 確實都是 streaming，記 `true` 是**真實觀測**，不是偽造。
  2. TTFT 仍只從 `StreamEvent.Delta` 首個生成內容推出（`InferenceEngine.kt:66`），**未拿 total latency 冒充**——這正是 §3 的禁令，現況反而是符合的。
  3. 「Non-streaming TTFT=null」驗收為 **N/A 而非 FAIL**：無 non-streaming 資料被錯誤標記。
- 為何仍需登記 prerequisite：
  - 一旦**未來**新增 non-streaming 路徑（PROV-010「可擴充 transport／adapter dispatch」的必然結果），若沿用 `streaming = true` 預設，就會**立刻製造偽造事實**：non-streaming 請求被標成 streaming，且 TTFT 欄位語意錯誤。這是**潛在** correctness 風險，只是尚未觸發。
- 判定：**非 BLOCKER（現在）＋ 明確 prerequisite（未來）**。
- 建議 owner：**PROV-010 修復的前置條件**。任何新增 non-streaming transport 的 worker，**必須**同時：(a) 顯式傳 `streaming = false`；(b) 該路徑 `ttftMs` 恆 `null`；(c) 補一條測試斷言 non-streaming 的 TTFT 為 `null` 且 `tps` 不以 total latency 推算。**不得**靠改預設值了事。

## 5. 給主 AI 的行動建議（不在本 worker 權限內）

1. **採納或修改**本節 X-011…X-024，寫入 `TRACEABILITY.md` Cross-cutting 段，並建立 **Phase 11** 容器（否則 §16 持續無主）。
2. **不阻擋**當前 P1–P7 repair 週期；X-011／X-013／X-015／X-016／X-017／X-018／X-021／X-022／X-024 可並行委派。
3. **MODEL-005** 修復時明確切分：效能維度 filter 掛 X-016，不要在 MODEL-005 worker 內順手造聚合。
4. **MODEL-009** 修復不必等 X-018（既有 `snapshot_facts.history` 已足夠）。
5. **F4／F16／F26 皆非 blocker**，不需搶修；F26 需寫進 PROV-010 的 worker 任務契約作為 prerequisite。
6. **F5 維持原歸屬**（Phase 5），X-016 只做 observed 側，不得順手加 `officialCheckedAt` 讓 F5 懸空。
7. **兩個待決策事項**（依 `ORCHESTRATION` §自主權）：**通知預設開關**（X-013）與 **telemetry retention 天數**（X-017）。**2026-09-30 結案：D-2026-09-30-005 通知預設 OFF＋Settings 可調；D-2026-09-30-006 telemetry 永久保留、不刪資料。本項已無待決事項。**

## 6. Quota-cover 免費（2026-09-29 使用者觀察，待決策）

- 現象：有效價格未歸零，但實際可免費使用（試用金／活動額度／免費池 cover）。
- 判定邏輯現況：verdict 層（availability）是對的（實測可用即標可用），但**原因說不清楚**——UI 無法區分「牌價即免費」vs「額度 cover 中」vs「剩餘未知」。
- 判定：**非 BLOCKER（repair／P8 可並行）**。但 P8 CRAWL-006 eligibility 必須引用本條：牌價非零＋quota-cover 的模型**不得判 PAID 淘汰**，須走 RPD／TPD＋AA 判定。
- Owner：MODEL-009／MODEL-005 修復時補「cover 原因」顯示（PARTIALLY_FREE 語意＋剩餘提示）；Crawler 側由 P8 承接。
