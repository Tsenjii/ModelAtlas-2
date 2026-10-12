# ModelAtlas P9 Assistant 準備盤點（非阻塞）

> 性質：**盤點文件**。不含任何程式碼、不改任何 contract、不宣告任何 requirement 完成。
> 對應 TRACEABILITY Phase 9：`ASST-001` … `ASST-010`。
> 本文件只回答三件事：**(A) 有哪些現成 API 可重用、(B) 缺什麼、(C) 驗收時要測什麼**。
> 交付時仍須依 `docs/TRACEABILITY.md` 的 Accepted 硬條件（production flow ＋ tests ＋ reviewer ＋ 完整綠 CI ＋ 文件更新）。

## 0. 盤點基準與限制

| 項目 | 值 |
|---|---|
| 分支 | `main` |
| HEAD | `96bb27e`（Docs-only handoff: P5 status, B1 dependency, next-AI briefing, P6 next） |
| 文件基準 | `docs/TRACEABILITY.md` §Status 基準列：code HEAD `96bb27e`、綠 CI run `36512213157`（build＋JVM＋device-tests） |
| 讀取範圍 | `PRODUCT_SPEC` §5／§6／§7／§14、`PROVIDER_ONBOARDING_CONTRACT` §3.3／§3.4／§7、`TRACEABILITY` Phase 9、`DELIVERY_MATRIX` P9、`BACKLOG.md`、`AtlasRepository.kt`、`AtlasViewModel.kt`、`app/security/*`、`app/onboarding/*`、`app/data/*`、`app/ui/*`、`core/*` |
| 限制 | 本 session **只新建本檔**；其他檔案只讀；未 commit、未 push、未委派 worker、未執行建置或測試 |

### 0.1 基準警告（實作前必讀）

1. **工作樹非乾淨**。`git status` 顯示 16 個已修改檔（`AtlasRepository.kt`、`AtlasViewModel.kt`、`AtlasDatabase.kt`、`Entities.kt`、`Daos.kt` 之外的 `OnboardingStore.kt`、`SnapshotFacts.kt`、`DedicatedSnapshotSync.kt`、`ConnectionRecord.kt`、`CanonicalStore.kt`、`AtlasScreen.kt`、`core/domain/Domain.kt`、三個 migration 測試）＋ 3 個未追蹤測試檔。這些是其他 session 的 P6 進行中變更。
   → **P9 實作前必須先確認工作樹乾淨或明確標記 P6 的寫入範圍**，否則 Assistant 的接線點會建立在未穩定的 API 上。
2. **Room v7 過渡中**。`AtlasDatabase` `version = 7`、`MIGRATION_6_7` 已註冊，但 `app/schemas/…/7.json` **尚未納管**（目錄只有 `1.json`…`6.json`）。`TRACEABILITY` FND-002／FND-007 已自行標註此過渡狀態。
   → P9 若要新增 `action_log` 表，會是 **v8**，且必須在同一波把 `7.json` 一起納管，否則 FND-007 的 schema 完整性會持續債化。
3. **P6 尚未 ACCEPTED**。`DELIVERY_MATRIX` 記 P6 為下一步主要任務、B1（dedicated sync 產品呼叫者）尚未閉合。Assistant 的 Models 相關讀取工具不應假設 P6 已定案。

---

## A. 可重用 API 清單

欄位：**函式名**（`檔案:行`）／**參數**／**用途與 Assistant 適用性**。
「可重用」定義：**該 API 已有 production caller 且帶有既有 contract 保護（scope 驗證、ownership 檢查、last-good 語意）**，Assistant 直接呼叫即可繼承這些保護，不需重寫。

### A.1 讀取側 — Providers / Accounts / Credentials / Models

| 函式名 | 參數 | 用途 |
|---|---|---|
| `AtlasRepository.connections`（`AtlasRepository.kt:51`） | 無（`Flow`） | 全部連線投影（provider/account/credential/endpoint/offering/availability ＋ `priceState`／`quotaState` enrichment）。**Assistant `list_providers` 主來源**。已含 `UNKNOWN` 不升格語意。 |
| `AtlasRepository.managementConnections`（`:53`） | 無（`Flow`） | 含已停用 credential 的管理視圖。Assistant 讀取「已停用／匿名」狀態必用此流（`connections` 會過濾 disabled）。 |
| `AtlasRepository.samples`（`:100`） | 無（`Flow`） | 最新 100 筆 telemetry（performance history）。Assistant `query_history`／`performance` 讀取。**注意：只有 100 筆、非全量歷史**（見 C.3 缺口）。 |
| `AtlasRepository.checkins`（`:103`） | 無（`Flow`） | 全部簽到紀錄。Assistant 讀 `checkinPending`／歷史。 |
| `AtlasRepository.getCheckin`（`:231`） | `providerId: String, date: String, accountId: String = ""` | 單筆簽到查詢；`accountId` 非空時驗 provider ownership（跨 provider 拋錯）。 |
| `AtlasRepository.hasCredential`（`:193`） | `credentialId: String` | safe ref 是否存在於 metadata。Assistant 驗證 ref 有效性（**不碰 vault**）。 |
| `AtlasRepository.dedicatedSupportsSync`（`:601`） | `providerId: String` | 同步（純本地家族鍵快路，無網路／DB）。同步按鈕可見性 gating。 |
| `AtlasRepository.dedicatedSupportsSyncResolved`（`:612`） | `providerId: String`（suspend） | UUID 家族 gating（讀 endpoint／provider_config，fail-closed false）。 |
| `AtlasRepository.usableRowsFor`（`:1260`，`AtlasRepository` 成員） | `accountId: String, credentialId: String, group: String?` | selected-key 可用模型投影（唯讀）。**未驗證 hidden offering 永不進入**；缺 availability 標 `UNKNOWN` 不隱藏。Assistant `switch key/group` 後的唯讀回饋。 |
| `AtlasViewModel.allModelRows`（`AtlasViewModel.kt:1252`） | 無（`StateFlow`） | Models 全量列（offerings ＋ availability ＋ classification ＋ price/quota heads）。Assistant `list_models` 最佳來源。**已在 P6 建立**，但 P6 未 ACCEPTED。 |
| `AtlasViewModel.selectedAccountId`／`selectedCredentialId`／`selectedGroup`（`:1256`／`:1260`／`:1264`） | 無（`StateFlow`） | 目前 selected key 三維。Assistant `switch provider/key/model` 的讀取面。 |
| `AtlasViewModel.state`（`:128`） | 無（`StateFlow`） | `connections`／`managedConnections`／`selectedId`／`busy`／`saving`／`samples`／`error`。Assistant 全域狀態讀取。 |
| `AtlasViewModel.dedicatedSyncing`／`dedicatedSyncError`／`dedicatedLastSynced`／`dedicatedLatestObserved`／`dedicatedAccountFallback`（`:1068`–`:1084`） | 無（`StateFlow`） | dedicated 同步執行狀態五件。Assistant sync 工具的回饋面（**觸發時間與觀測時間已分離**，見 A.5）。 |

### A.2 讀取側 — Price / Quota / History / Evidence

| 函式名 | 參數 | 用途 |
|---|---|---|
| `NewApiSnapshotStore.price`（`NewApiSnapshotStore.kt:109`） | `scope: PricingScope, manual: Boolean = false` | 精確七維 scope 讀價格（`PRICE` 或 `MANUAL_PRICE` 分槽）。回 `StoredPrice(latest, lastGood)`。 |
| `NewApiSnapshotStore.quota`（`:116`） | `scope: PricingScope, kind: SnapshotKind` | 讀配額（限 `ACCOUNT_QUOTA`／`TOKEN_QUOTA`／`MANUAL_QUOTA`）。 |
| `NewApiSnapshotStore.history`（`:123`） | `scope: PricingScope, kind: SnapshotKind` | 該 scope 的完整觀測序列（append-only `snapshot_facts`）。Assistant `price history`／`quota history` 的正確來源。 |
| `NewApiSnapshotStore.buildPricingScopes`（`:141`） | `providerId, accountId, pat: AccountPatRef, pricing: NewApiPricing, …` | 由本地 offerings/endpoints 建真實七維 scope。**會驗 PAT 角色與 ownership**。Assistant 組 scope 時必經此路徑，不可自組字串。 |
| `SnapshotFactDao.head`／`headsByKind`／`evidence`／`history`／`byScopeKindObserved`（`SnapshotFacts.kt:100`/`:108`/`:111`/`:114`/`:122`） | `scopeKey`＋`kind` 等 | 低階讀取。**Assistant 盡量不直接碰 DAO**（會繞過 store 層語意）；僅在需要 `evidenceIds` 回溯時經 store 或新增 typed 讀取 API。 |
| `ModelSearchLogic.rankResults`（`ModelSearchLogic.kt:194`） | `query: String, rows: List<ModelRow>, maxResults: Int` | 四層搜尋排序（EXACT > ALIAS > NORMALIZED > FUZZY）。Assistant `search models` 直接重用，**不得自寫模糊比對**。 |
| `ModelSearchLogic.filterModels`（`:373`） | `rows, filter: ModelFilter, nowMs: Long` | 35 維篩選（FREE NOW／價／效能／分類／時間／收藏／watchlist／usable）。Assistant `filter` 工具。 |
| `ModelSearchLogic.sortForDefaultView`（`:280`） | `rows: List<ModelRow>` | 預設排序（FREE NOW 優先）。 |
| `ModelRowMapper.build`（`ModelRowMapper.kt:126`） | `offerings, availabilities, selected: SelectedKey, canonicals, aliases, classifications, priceQuotaHeads` | DB row → `ModelRow` 的唯一映射。**Assistant 不得自建另一套映射**（會重複 Unknown 填值規則）。 |
| `ModelDetailStateBuilder.build`（`ModelDetailState.kt:101`） | `canonicalId, displayName, priceState, quotaState, priceHistory, quotaPools, performance, extraEvents` | 單模型詳情（價格變動次數、freeSinceAt、歷史合成）。**`priceChangeCount` 只在有數值時推導，否則 null**。Assistant `model detail`。 |
| `CanonicalStore.getClassification`／`classificationsOf`／`observeClassification`（`CanonicalStore.kt:385`／`:393`／`:411`） | `canonicalId`, `source: String` | 分類讀取（含 capabilities/modalities/endpointTypes 三組關聯）。Assistant 讀多維分類。 |
| `EvidenceEntity`（`Entities.kt:213`） | 欄位：`id`／`requestId`／`providerId`／`modelId`／`outcome`／`status`／`timestamp`／`source`／`version` | Assistant `evidence` 工具的資料源。**已知限制見 C.4（BACKLOG F11）**。 |

### A.3 寫入側 — 可逆／唯讀 metadata 寫入

| 函式名 | 參數 | 用途 |
|---|---|---|
| `AtlasRepository.setProviderCheckinConfig`（`AtlasRepository.kt:239`） | `providerId: String, url: String?, requiresDailyCheckin: Boolean` | 設定簽到 URL。**`requires=true` 必須有 url**；url 過 `EndpointPolicy.validate` ＋正規化。**可直接重用**（BACKLOG F6 已閉合，明列「Assistant 寫入走同一 repository API」）。 |
| `AtlasRepository.completeCheckin`（`:220`） | `providerId, date, completedAt, accountId = ""` | 記錄當日簽到完成（`source=USER`）。`accountId` 非空時驗 provider ownership。 |
| `AtlasRepository.addAccount`（`:163`） | `providerId: String, accountId: String` | 新增帳號（id 由呼叫端決定，blank 拒絕、provider 不存在拋錯）。 |
| `AtlasRepository.attachCredential`（`:170`） | `offeringId, accountId, credentialId, credentialLabel, availabilityId` | 掛載 credential metadata ＋ availability。**內建防護**：跨 provider 拒絕、跨帳號 credential 拒絕、同 vault ref 保留既有 label。 |
| `AtlasRepository.setCredentialEnabled`（`:188`） | `credentialId: String, enabled: Boolean` | 啟用／停用。**`rows == 0` 拋 "Credential not found"**（不靜默成功）。 |
| `AtlasRepository.savePrice`（`:261`） | `snapshot: PriceSnapshot` | 手填價格 → 委派 `NewApiSnapshotStore.saveManualPrice`。**`MANUAL_PRICE` 分槽、LOW confidence 永不升 official last-good、七維 scope 擁有權驗證**。 |
| `AtlasRepository.saveQuota`（`:265`） | `snapshot: QuotaSnapshot` | 手填配額 → `saveManualQuota`，同上語意。 |
| `AtlasRepository.addManualOffering`（`:1143`） | `draft: OfferingDraft` | 新增手動模型（`OfferingDraft`：`id`／`providerId`／`endpointId`／`providerModelId`／`source`／`evidenceId`／`observedAt`／`hidden`／`verificationEvidenceId`）。**冪等鍵 = (provider, endpoint, model) 三元組**；`source` 僅接受 `MANUAL`／`USER`（official 冒充拋 `IllegalStateException`）；未驗證強制 `hidden=true`。 |
| `promoteManualOffering`（`:1305`，top-level 純函式） | `draft: OfferingDraft, verificationEvidenceId: String` | 驗證後升為可見。回 `Promoted`／`Rejected`，**不拋崩**。持久化仍須呼叫端重送 `addManualOffering`（該路徑會 UPDATE 寫回 `hidden`／`verificationEvidenceId`）。 |
| `ManualOfferingStore.canPromote`／`promoteOnVerified`／`downgradeOnFailure`／`isVisible`／`isUsable`（`ManualOfferingStore.kt:111`/`:123`/`:137`/`:150`/`:156`） | `draft`, `verificationEvidenceId` | 純函式。Assistant 在呼叫 DB 前先做可見性／可用性判斷。 |

### A.4 寫入側 — destructive（**必須走 confirmation，見 C.2**）

| 函式名 | 參數 | 用途 |
|---|---|---|
| `CredentialLifecycle.delete`（`CredentialLifecycle.kt:28`） | `safeCredentialId: String` | 刪除 credential。**三段式且不可逆**：停用 → `vault.remove` → 刪 metadata。`Mutex` ＋ `NonCancellable` 串行化。**無 Undo**（見 C.1）。 |
| `CredentialLifecycle.setEnabled`（`:21`） | `safeCredentialId, enabled: Boolean` | 啟用／停用。`enabled=true` 前 `check(vault.has(id))`。**可逆**，可作 Undo 的候選補償動作。 |
| `QuickAddService.abandon`（`QuickAddService.kt:267`） | `workflowId: String, reason: String? = null` | 放棄 workflow（丟棄已驗證結果）＋清除孤兒 vault refs。**KDoc 明寫「destructive，確認由呼叫端負責」**。已 commit 的會先拋錯、不動 vault。 |
| `OnboardingWorkflow.abandon`（`OnboardingWorkflow.kt:281`） | `workflowId, reason` | core 層放棄。**同上，確認由呼叫端負責**。 |
| `AtlasRepository.deleteCredentialMetadata`（`AtlasRepository.kt:196`） | `credentialId: String` | **只刪 metadata**（availability ＋ credential row），不動 vault。Assistant **不得**直接呼叫（會留下孤兒 secret）；必須走 `CredentialLifecycle.delete`。 |

### A.5 同步 / 測試 / 執行側

| 函式名 | 參數 | 用途 |
|---|---|---|
| `AtlasRepository.syncNewApi`（`AtlasRepository.kt:282`） | `providerId: String, adapter: NewApiAdapter` | New API 手動同步（pricing ＋ account quota ＋ token quota）。**參數驗證在首次網路請求前完成**（缺 PAT／缺 scope 拋錯不燒額度）。回 `NewApiSyncResult(prices, accountQuota, tokenQuota?)`；`tokenQuota = null` 表無 `INFERENCE` key 跳過（**不偽造**）。 |
| `AtlasRepository.syncDedicatedPricing`（`:704`） | `providerId, accountId: String? = null, credentialId: String? = null, group: String? = null` | Dedicated 價格同步。**`credentialId != null` 直接拒絕**（價格 scope 永不帶 credential）。回 `DedicatedSyncSummary`。 |
| `AtlasRepository.syncDedicatedQuotaPools`（`:746`） | 同上 | Dedicated 配額池同步。`credentialId` 必須是 `provider_config` 中**單一 `INFERENCE` role** 的 safe ref（PAT／重疊／缺 config 全拒）；DB 存在 ＋ enabled ＋ ownership ＋ 同帳號四重檢查。 |
| `AtlasViewModel.syncDedicated`（`AtlasViewModel.kt:1120`） | `providerId, accountId, credentialId, group` | UI 層 single-flight 同步入口。**成功觀測才計 done**；unsupported／0 筆不計。 |
| `AtlasViewModel.syncNewApi`（`:572`） | `providerId: String` | UI 層 New API 同步入口（provider 級 single-flight，busy/saving 時拒絕）。 |
| `InferenceEngine.execute`（`core/inference/InferenceEngine.kt:21`） | `target: InferenceTarget, messages: List<PromptMessage>, source: TelemetrySource = PLAYGROUND, priceState, quotaState, pricingSnapshotId, quotaSnapshotBeforeId` | **唯一 inference 入口**。Assistant 每次 probe／test 必經此路徑，**`source` 必須傳 `TelemetrySource.ASSISTANT_TEST`**（enum 已預留，目前無 caller）。FREE_NOW／零價格不得 bypass。 |
| `CompareLiveRunner.run`（`core/models/CompareLiveRunner.kt:76`） | `engine, request: CompareLiveRequest, feed: CompareLiveSampleFeed?` | 跨 offering 同 prompt 併發比較。**目前無 production caller**（只有 core 測試）→ Assistant 不得宣稱可用（見 C.5）。 |
| `CatalogProbe.inspect`（`core/network/CatalogProbe.kt`） | `baseUrl: String` | 唯讀探測（單 request、禁 redirect、禁重試）。Assistant `inspect` 工具的底層。 |
| `ProbeStepExecutor`（`core/onboarding/ProbeExecutors.kt:29`） | `snapshot, grant: RequestGrant` | onboarding 各步驟執行器（inspect/detect/auth/credentialTest/listModels/testModel/pricingQuotaNotice）。**Assistant 不應直接驅動單步**，應走 workflow（見 A.6）。 |

### A.6 Shared onboarding workflow 接線點（ASST-006 的既有基礎）

這是 P9 最重要的一組既有 API。`PROVIDER_ONBOARDING_CONTRACT` §3.3 已定死：Assistant 不得另起旁路，必須呼叫同一 workflow。

| 函式名 | 參數 | 用途 |
|---|---|---|
| `QuickAddService.startQuickAdd`（`QuickAddService.kt:150`） | `baseUrl, websiteUrl: String? = null, secretTransient: String? = null, suggestedModelId: String? = null, workflowId: String = UUID…` | 開始 Quick Add，跑到 `REVIEW` 待確認或 `NEEDS_INPUT`／`BLOCKED`，回 `workflowId`。**`secretTransient` 只在 vault 路徑使用**；失敗回滾孤兒 ref。 |
| `QuickAddService.snapshots`（`:130`） | 無（`Flow`） | 全部 workflow 狀態流（解碼失敗固定安全訊息）。Assistant 進度／恢復讀取。 |
| `QuickAddService.loadSnapshot`（`:217`） | `workflowId: String` | 單筆讀取（`null` = 不存在）。 |
| `QuickAddService.reviewSummary`（`:233`） | `workflowId: String` | **回 `ReviewItems`**（`providerType`／`auth`／`modelsFound`／`modelsVerified`／`pricingStatus`／`quotaStatus`／`officialHealth`／`observedHealth`／`unknownFields`／`confidences`／`evidenceIds`／`requestsSpent`／`rateLimited`）。**這是 ASST-010「Review 摘要回傳」的既有資料源。** |
| `QuickAddService.editReview`（`:228`） | `workflowId, name: String?, selectedModels: List<String>, manualModelId: String? = null` | Review 階段編輯（改名／選模型）。 |
| `QuickAddService.editQuick`（`:333`） | `workflowId, baseUrl, websiteUrl, secretTransient, suggestedModelId, providerName`（全 `String?`，`null` = 不改、空字串 = 清除） | 安全重跑輸入。**origin 變更不可沿用舊 key**（舊 refs dropped）；**改輸入必清 approval**；不重置總預算。 |
| `QuickAddService.resume`（`:293`） | `workflowId: String` | 從 Room 恢復續跑（不重跑已成功步驟）。 |
| `QuickAddService.retryStep`（`:300`） | `workflowId, step: OnboardingStepId` | 單步重試（舊結果標 `SUPERSEDED`、下游標 `STALE`；**總預算不重置**）。 |
| `QuickAddService.skipStep`（`:307`） | `workflowId, step, reason: String? = null` | 單步跳過。**`REVIEW`／`COMMIT` 不可跳過**（core 拋錯）→ **Review 不可繞過的第一道閘門**。 |
| `QuickAddService.approveAndCommit`（`:251`） | `workflowId, approvedBy: String? = null` | 寫 `ReviewApproval` 後 commit。**Assistant 不得自行呼叫**（見 D.2）。 |
| `AdvancedAddService.start`（`AdvancedAddService.kt:46`） | `metadata: OnboardingSnapshot, credentials: List<AdvancedCredentialInput>, fields: List<AdvancedAuthInput> = emptyList(), workflowId` | Advanced Add 入口。`AdvancedCredentialInput(label, secretTransient, role, accountRef)`。 |
| `AdvancedAddService.edit`（`:53`） | `workflowId, metadata, credentials, fields` | Advanced 編輯重跑。 |
| `BatchImportService.start`／`resume`／`retryItem`／`load`／`listBatchIds`／`requestCancel`（`BatchImportService.kt:110`–`:134`） | `rawInput: String` / `batchId` / `batchId, itemId` | 批次匯入（Assistant `import keys` 候選工具；**目前無 UI caller**）。 |
| `OnboardingViewModel.startQuick`／`startAdvanced`／`editQuick`／`editAdvanced`／`rename`／`updateReview`／`resume`／`retry`／`skip`／`cancel`／`commit`／`abandon`／`close`（`OnboardingViewModel.kt:100`–`:310`） | 見各函式 | **UI 層**入口。Assistant 應呼叫 **service 層**（上表），不應搶 UI ViewModel 的 job/state；但 `abandon()` KDoc 明寫「destructive，需 UI 先確認」。 |

### A.7 ViewModel 可重用入口

| 函式名 | 參數 | 用途 |
|---|---|---|
| `AtlasViewModel.selectConnection`（`AtlasViewModel.kt:278`） | `id: String` | 切換 selected connection（**已停用者直接拒絕**）。Assistant `switch provider`。 |
| `AtlasViewModel.selectModelKey`（`:1267`） | `accountId: String?, credentialId: String?, group: String? = null` | 切換 selected key 三維並重算投影。Assistant `switch key/group`。 |
| `AtlasViewModel.refreshModelRows`（`:1275`） | `accountId, credentialId, group`（皆預設取 selected） | 重算 Models 列。**失敗保留 last-good，不清空**。 |
| `AtlasViewModel.send`（`:402`） | `prompt: String` | Playground 推論。**注意：這是聊天路徑，非 Assistant 推論路徑**（見 C.6）。 |
| `AtlasViewModel.cancel`（`:557`） | 無 | 中止進行中推論（保留 job 至 finally，telemetry 真的結束）。Assistant 對話中止。 |
| `AtlasViewModel.configureCheckin`（`:764`） | `connectionId: String, url: String` | UI 層簽到設定（內含 `cancelAndJoinSend` ＋ `EndpointPolicy` 驗證）。 |
| `AtlasViewModel.setCredentialEnabled`（`:689`） / `deleteCredential`（`:724`） | `connectionId: String, enabled: Boolean` / `connectionId: String` | UI 層 credential 管理（**`deleteCredential` 額外清理 custom-auth 兄弟 refs，PAT 永不列入**）。Assistant 需複刻此清理邏輯或重用 `customAuthCleanupRefs`。 |
| `AtlasViewModel.completeCheckin`（`:667`） | `connectionId: String` | UI 層簽到完成。 |
| `AtlasViewModel.dismissError`（`:562`） | 無 | 清除錯誤。 |

### A.8 Security 邊界層（**Assistant 唯一合法接觸點**）

| 函式名 | 參數 | 用途 |
|---|---|---|
| `AndroidCredentialVault.store`（`AndroidCredentialVault.kt:34`） | `id: String, origin: String, secret: String` | 寫入 secret。**AES-GCM，AAD 綁 `id｜origin`**；`id` 必須 match `[A-Za-z0-9-]{1,128}`；origin 必須是**無 path 的正規 HTTPS origin**；**已存在則拋錯不覆寫**；secret ≤ 8192 且無 CR/LF。 |
| `AndroidCredentialVault.bearerToken`（`:55`） | `credentialId: String, origin: String` | **唯一解密點**（`CredentialAccess` 介面實作）。**`preferences[id.origin] != boundOrigin` 即拋 "Credential scope mismatch"** → 憑證無法被換 origin 重綁。 |
| `AndroidCredentialVault.has`（`:70`） | `id: String` | 該 ref 是否有密文／IV／origin。**只回布林，不回密文**。 |
| `AndroidCredentialVault.remove`（`:78`） | `id: String` | 冪等移除（回是否原本存在）。 |
| `ensureSnapshotJsonHasNoSecretRefs`（`OnboardingStore.kt:105`，internal） | `snapshotJson: String` | **fail-closed secret guard**：擋裸字樣（`secret`／`password`／`access_token`／`rawkey`…）、key 位置樣式（`api_key`／`authorization`／`bearer`／`token` 後接 `:`）、值樣式（`sk-`＋8 碼以上）。**`RoomWorkflowStore.save`／`ProviderManualConfig.toEntity`／`RoomBatchStateStore` 三條寫入路徑都必經此 guard。** |
| `redactSecrets`（`core/network/OpenAiTransport.kt:48`） | `text: String, token: String?` | 遮蔽原值／URL-encoded／base64 standard+url／去 padding 變體；`< 8` 字元跳過。**已知限制見 C.7。** |
| `InferenceTarget`（`core/inference/Contracts.kt:6`） | `providerId, accountId, credentialId: String? = null, endpointId, endpointUrl, modelId, canonicalModelId, group, operation, maxOutputTokens, authFields` | **推論目標只帶 safe ref**。`credentialId = null` = 匿名（不送 `Authorization`）。`authFields` 只帶 `InferenceAuthField(name, credentialId, query, overrideConfirmed)`——**值永不進此型別**。 |

---

## B. 缺口清單

### B.0 總覽

| ID | 缺口 | 對應 ASST | 現況 | 風險 |
|---|---|---|---|---|
| C.1 | **ActionLog 表不存在** | ASST-007 | Room v7 的 23 個 entity 無任何 action/audit 表 | Assistant 每次寫入無紀錄可回溯；Reviewer 無法驗「可逆修改有 Undo ＋ ActionLog」 |
| B.1a | **Undo 完全不存在** | ASST-008 | 全 repo 無 undo 概念；`CredentialLifecycle.delete` 不可逆 | 可逆修改（enable/disable、checkin config、notes/tags、manual offering）目前全部不可還原 |
| C.2 | **destructive confirmation 無架構層** | ASST-009 | 確認邏輯只散落在 UI（`AdvancedAddForm` 的 `AlertDialog`、`AtlasScreen` 的 `confirmDelete`）；service 層 KDoc 一律寫「確認由呼叫端負責」 | Assistant 若直呼 service 層即可無確認刪除 credential／abandon workflow |
| C.3 | **Assistant 無 structured tool 層** | ASST-002/003/004 | 無 tool schema、無 dispatcher、無 tool↔repository adapter。`core` 層 `OpenAiTransport` **不送 `tools` 欄位**、不解析 tool_call | ASST-002/003/004 全部從零 |
| C.4 | **Assistant model 未獨立選擇** | ASST-001 | 無 assistant provider/model 設定欄位；`selectConnection` 只有單一 `selectedId` 供 Playground | Assistant 無法用不同 provider/model 跑；且會與 Playground 搶 selected 狀態 |
| C.5 | **共用 workflow 接線點未接** | ASST-006 | `QuickAddService`／`AdvancedAddService` 齊備但**無 Assistant caller**；且 `RoomCommitter.commit` 內**無 ActionLog 寫入點**（Undo 需要 commit 前的快照） | 旁路風險（Assistant 另寫一套 onboarding）；Undo 無法還原 commit |
| C.6 | **`InferenceSource.ASSISTANT_TEST` 無 caller** | X-002 | enum 預留但全 repo 無人傳 | Assistant 推論會被記成 `PLAYGROUND`，telemetry 歸因錯誤 |
| C.7 | **Evidence 缺 scope 欄位（BACKLOG F11）** | ASST-010 | `EvidenceEntity` 只有 `id`／`requestId`／`providerId`／`modelId`／`outcome`／`status`／`timestamp`／`source`／`version` | Assistant 無法回答「這個價格觀測是哪個 credential/group/endpoint 來的」 |
| C.8 | **收藏 / Watchlist 無持久化** | §14 可操作清單 | `onlyFavorites`／`onlyWatched`／`favoriteIds`／`watchlistIds` 只在 `ModelFilter` 內**由呼叫端傳入集合**，無 entity／DAO／repo | Assistant `Favorite`／`Watchlist` 工具**無資料可寫** |
| C.9 | **Notes / Tags 無獨立寫入 API** | §14 可操作清單 | `notes`／`tags` 只存在 `provider_configurations.snapshotJson` 與 `onboarding_snapshots`；`ProviderConfigDao.updateSnapshotJson` 標記為「測試／修復用」 | Assistant 改 tags/notes 只能走 onboarding 重跑（代價過高）或新增 API |
| C.10 | **Query History 未持久化** | §14 可讀清單 | `historyByConnection: MutableMap<String, MutableList<PromptMessage>>` 在 ViewModel 記憶體、**上限 20 條、App 重啟即失** | Assistant `Query History` 讀不到跨 session 歷史 |
| C.11 | **Settings 是純文字對話框** | §14 可讀/可操作清單 | `AtlasScreen.kt:342` 的 Settings 只顯示靜態字串，無設定儲存 | Assistant 無 settings 讀寫面 |
| C.12 | **Crawler Evidence 對 Assistant 不可讀** | §14 可讀清單 | BACKLOG **F10：candidate 零持久化**（無 entity／table／DAO／repo／UI），只有 `core/domain` 的型別 | Assistant 無法讀 crawler funnel 證據 |
| C.13 | **Web Research 無工具** | §14 structured tools 清單 | `CatalogProbe` 只探已知 `baseUrl`；無通用 search/fetch 工具 | `search`／`fetch` 類工具需自建，且須受 §15「禁止自改 source code / 破 secret boundary」約束 |
| C.14 | **`CompareLiveRunner` 無 production caller** | — | 只有 core 測試 | Assistant 不得宣稱有 compare 工具（除非本 Phase 順帶接線） |
| C.15 | **Assistant 無 UI 落點** | — | Bottom navigation 固定 3 tab（Playground／Providers／Models），Settings 在右上。**PRODUCT_SPEC §2 明文禁止加第 4 tab** | Assistant 入口位置未定（見 D.4） |
| C.16 | **Performance history 只 100 筆** | §14 可讀清單 | `samples` = `observeLatest()` `LIMIT 100` | Assistant 長歷史查詢會誤報「無資料」 |
| C.17 | **Provider 網址／base URL 無編輯 API** | §14 可操作清單 | `ProviderDao` 只有 `insert`／`getById`／`updateCheckinConfig`；`EndpointDao` 只有 `insert`／`getById`／`byProvider` | Assistant `update website/base URL` 無 API（且屬 destructive：origin 變更牽動 vault 綁定） |

### B.1 ActionLog（ASST-007）— 需要什麼

現況：**完全不存在**。`AtlasDatabase` 的 23 個 entity（`AtlasDatabase.kt:38`–`:61`）無任何 action/audit 表。

設計必須滿足（依 `PRODUCT_SPEC` §14「可逆修改有 Undo ＋ ActionLog」＋ §1「重要事實必須有 Evidence／provenance」）：

- **不可放 raw secret**。至少要有與 `ensureSnapshotJsonHasNoSecretRefs` 等價的寫入前 guard。
- **必須記錄 undo 所需的補償資料**（前一狀態），否則 Undo 無法實作（ASST-008）。
- 必須區分 **reversible** 與 **destructive** 兩類（destructive 即使有 ActionLog 也不等於可 Undo）。
- `Room version 7 → 8`；`MIGRATION_7_8` 註冊；`7.json` ＋ `8.json` 一併納管。
- `DELIVERY_MATRIX` P9 已明寫「ActionLog 表未建」為缺口，Gate 為「secret 洩漏邊界測試（key 出現在對話→transient 處置）」。

### B.2 Undo（ASST-008）— 需要什麼

現況：**完全不存在**。

可逆動作的現況逐一盤點：

| 動作 | 現況 | 可逆性 | Undo 需要的補償資料 |
|---|---|---|---|
| `CredentialLifecycle.setEnabled` | 已實作 | **可逆** | 前一 `enabled` 布林 |
| `setProviderCheckinConfig` | 已實作 | **可逆** | 前一 `url` ＋ `requiresDailyCheckin` |
| `completeCheckin` | 已實作 | **不可逆**（已寫 `completedAt`） | 需刪列或標 `reverted`（`CheckinEntity` 無此欄位） |
| `addManualOffering`（hidden） | 已實作 | **可逆**（hidden 冪等） | 前一 `hidden` ＋ `verificationEvidenceId` |
| `promoteManualOffering` | 已實作 | **可逆**（降級路徑 `downgradeOnFailure` 語意接近但非同一 API） | 前一 `hidden`／`verificationEvidenceId` |
| `savePrice`／`saveQuota` | 已實作 | **append-only，不可逆** | `snapshot_facts` append-only 契約不可破；Undo 只能寫新事實（降級／revert 事件），不能刪歷史 |
| `onboarding.commit` | 已實作 | **不可逆** | `RoomCommitter` 是單一 transaction 寫 8+ 張表，**無反向刪除路徑** |
| `CredentialLifecycle.delete` | 已實作 | **不可逆**（secret 已從 Keystore 移除，無法復原） | 只能提示「需重新輸入」 |
| `QuickAddService.abandon` | 已實作 | **不可逆** | workflow snapshot 被標 `ABANDONED`，孤兒 vault refs 已刪 |

> **結論**：`DELIVERY_MATRIX` Gate 寫「Undo 可還原 commit」在現有架構下**不可達成**——`RoomCommitter.commit` 沒有 snapshot-before 機制。
> 這是一個**需要在 P9 動手前由主 AI 裁定的架構決策**（見 D.3），不應由 worker 自行決定。

### B.3 Destructive confirmation（ASST-009）— 需要什麼

現況：**沒有架構層，只有人工 UI 對話框**。

- `AdvancedAddForm.kt:241` 有 `AlertDialog`（`AuthMode.NONE` 匿名二次確認）。
- `AtlasScreen.kt` 卡片有 `confirmDelete` 布林 + 對話框（credential 刪除）。
- `AdvancedAddForm` 的 `authorizationOverrideConfirmed`、`anonymousConfirmed` 是 `AdvancedAuthInput.authorizationOverrideConfirmed`／`OnboardingSnapshot.anonymousConfirmed` 的**資料層落地**（進 Room JSON）。
- service 層全部寫「destructive，確認由呼叫端負責」：`QuickAddService.abandon`（`:267`）、`OnboardingWorkflow.abandon`（`:281`）、`OnboardingViewModel.abandon`（`:277`）。

**`PRODUCT_SPEC` §14 列的 destructive 動作**：刪 Provider／Credential、換 PAT、critical Base URL、bulk delete。
**注意：「刪 Provider」目前連 API 都沒有**（見 C.17）——`ProviderDao` 無 delete。

需要的是一個**在 service／repository 之下方的強制閘門**（例如 confirmation token 必須由人類 UI 產生並在一次性 transaction 內核銷），而不是再散落一個 `AlertDialog`。

### B.4 Shared onboarding workflow 接線點（ASST-006）— 具體接線位置

`PROVIDER_ONBOARDING_CONTRACT` §3.3 已定死 tool 清單：`onboarding.start` / `resume` / `retryStep` / `skipStep` / `commit`。

映射到現有 API：

| Contract tool | 既有 API | 接線注意 |
|---|---|---|
| `onboarding.start(flowType, inputs)` | `QuickAddService.startQuickAdd`（QUICK）／`AdvancedAddService.start`（ADVANCED） | `flowType` 已有 `OnboardingFlowType`。`secretTransient` 必須標 transient、不進 LLM context。 |
| `onboarding.resume(workflowId)` | `QuickAddService.resume` | 從 Room 恢復。 |
| `onboarding.retryStep(workflowId, step)` | `QuickAddService.retryStep` | 總預算不重置。 |
| `onboarding.skipStep(workflowId, step, reason)` | `QuickAddService.skipStep` | `REVIEW`／`COMMIT` 不可跳過。 |
| `onboarding.commit(workflowId)` | `QuickAddService.approveAndCommit` | **Assistant 不得直接呼叫**（見 D.2）。 |
| （contract 未列但 §3.3 提及 destructive） | `QuickAddService.abandon` | 需 confirmation。 |

**接線點的架構缺口**：
1. `QuickAddService` 是 `OnboardingViewModel` 的 private 欄位（`OnboardingViewModel.kt:47`），**沒有任何 app 層 holder 讓 Assistant 共用同一 instance**。共用同一 instance 是 single-flight 契約（`QuickAddService` KDoc 明寫：每次 `newWorkflow()` 會讓 gate 失效）的要求。
2. `RoomCommitter.commit`（`OnboardingStore.kt:233`）**沒有 ActionLog 寫入點**。
3. `QuickAddService` 沒有 `editReview` 之外、讓 Assistant 觸發「人類確認」的鉤子。

### B.5 其他 `PRODUCT_SPEC` §14 要求但本盤點未找到落點的

- **§14 可讀：Notices / Events** — `Entities.kt` 無 `ProviderNotice`／`Event` entity。`IMPLEMENTATION_PLAN` 把 `ProviderNotice/Event` 列入「隨階段增加」但尚未實作。Assistant 無法讀。
- **§14 structured tools 清單**：`search`／`fetch`／`inspect`／`detect`／`discover`／`probe`／`list`／`test`／`measure`／`fetch pricing`／`quota`／`usage`／`perf`／`notice`／`search source`／`authenticity`／`evidence`／`candidate`／`provider`／`model`／`sync`。
  現有可對應：`inspect`（`CatalogProbe`）、`detect`（`OnboardingStepId.DETECT_TYPE`）、`probe`（`ProbeStepExecutor`）、`list`／`test`（同）、`pricing`／`quota`（`NewApiSnapshotStore`）、`perf`（`samples`）、`evidence`（`SnapshotFactEntity`／`EvidenceEntity`）、`provider`／`model`（Repository 讀取）、`sync`（A.5）。
  **無對應**：`search`（通用搜尋）、`fetch`（通用抓取）、`discover`、`measure`、`usage`、`notice`、`search source`、`authenticity`（屬 P7/P8）、`candidate`（F10 未做）。

---

## C. safeCredentialId 邊界檢查清單

**目標**：`PRODUCT_SPEC` §1「raw API key／PAT 不進 LLM prompt、repo、log、telemetry；LLM 只看到 safe credential refs」＋ `PROVIDER_ONBOARDING_CONTRACT` §3.3「若使用者在對話中貼出原始 key，該 key 標 transient、只走 vault 寫入路徑，不進 ActionLog／LLM context 持久化，並在成功後要求使用者撤回／以 UI 重新輸入」。

### D.1 現有保護（已實作，Assistant 必須繼承而非繞過）

| # | 保護點 | 位置 | 保護內容 |
|---|---|---|---|
| S1 | Vault AAD 綁定 | `AndroidCredentialVault.kt:43` | `updateAAD("$id\|$boundOrigin")` — 密文與 (id, origin) 綁定，無法複製到別的 ref／origin |
| S2 | Vault origin 比對 | `:58` | `bearerToken` 檢查 `preferences[id.origin] == boundOrigin`，不符即 `Credential scope mismatch` |
| S3 | Vault id 格式 | `:87` | `[A-Za-z0-9-]{1,128}` — 擋 `sk-` 前綴 secret 當 id |
| S4 | Vault 拒絕覆寫 | `:38` | `check(!preferences.contains("$id.ciphertext"))` — 換 PAT 必須走新 ref |
| S5 | Vault secret 格式 | `:37` | 非空、≤ 8192、無 CR/LF |
| S6 | Vault origin 無 path | `:93` | `require(url.encodedPath == "/")` |
| S7 | 密文 byte 清除 | `:51`/`:66` | `bytes.fill(0)` in `finally` |
| S8 | Secret guard（三條寫入路徑） | `OnboardingStore.kt:105` | `RoomWorkflowStore.save`／`ProviderManualConfig.toEntity`／`RoomBatchStateStore` 寫入前必過 |
| S9 | `InferenceTarget` 型別邊界 | `Contracts.kt:6` | `authFields` 只帶 ref；`credentialId` 只帶 ref。**值在型別上無法進入** |
| S10 | `redactSecrets` | `OpenAiTransport.kt:48` | telemetry 內遮蔽（見 S15 限制） |
| S11 | Rate header 白名單 | `OpenAiTransport.kt:158`/`:239` | 只落 `SAFE_RATE_HEADERS` 內的 header，值再過 redact 並截 128；**白名單外 header 值根本不進 telemetry** |
| S12 | Dedicated ref 檢查 | `AtlasRepository.kt:618` | 非空、≤ 256、無控制字元、**`!ref.startsWith("sk-")`**、無空白 |
| S13 | INFERENCE role 單一性 | `:661` | PAT（`ACCOUNT` role）永不進 inference quota 路徑；重疊 ref 全拒 |
| S14 | Custom auth 只存 refs | `ProviderConfig.kt:26` | `customAuthFields: List<CustomAuthFieldRef>`，值永不進 JSON |
| S15 | Commit 端外來 ref 阻擋 | `OnboardingStore.kt:255`–`:262` | 未列入 `snapshot.credentials` 的 custom ref 不可在 DB 已存在（防走私他人 PAT） |
| S16 | UI 層 fail-closed | `AtlasViewModel.kt:451`–`:462` | `provider_config` 載入失敗一律拒發；`API_KEY`/`CUSTOM_HEADER` 取回空 `customFields` 拒發 |
| S17 | Snapshot JSON 拒絕訊息 | `OnboardingStore.kt:84` | `OnboardingSecretRefusedException` 固定訊息，**不攜帶 JSON 內容** |
| S18 | 取消不刪 vault ref | `QuickAddService.kt` start 的 `catch` | 只有 snapshot 未落地才清孤兒 ref；已落地則保留（workflow 還需要） |

### D.2 Assistant 新增落點（**必做清單**）

| # | 落點 | 必須做到 |
|---|---|---|
| N1 | **Tool schema 的參數型別** | 每個 tool 的參數 schema **只能是 safe ref**（`safeCredentialId` 字串）。**不得提供 `apiKey`／`token`／`secret` 參數欄位**——若 schema 存在該欄位，即使值不落庫也已經是 contract 破口。 |
| N2 | **對話內貼 key 的偵測** | Assistant 輸入路徑必須偵測疑似 raw secret（例如 `sk-` 前綴、`apiKey=`／`token=` 樣式、`Authorization: Bearer`），**標為 transient、不寫入任何持久化、不回顯給使用者、不進 LLM 的後續 context**。 |
| N3 | **Transient 處置流程** | 偵測到後：**立即要求使用者在安全輸入框重新輸入**（UI 路徑），或**當次 tool call 直接拒絕並回固定安全訊息**。兩者都不可把 raw 值送進 `InferenceTarget`／`PromptMessage`。 |
| N4 | **推論 context 組裝** | Assistant 送 LLM 的 `List<PromptMessage>` **不得包含 vault 取出值**。`InferenceTarget` 型別已保證（N9），但 Assistant 自己的 system prompt／tool result 組裝是**新程式碼**，必須自己驗。 |
| N5 | **Tool result 回傳** | Tool 結果回給 LLM 前的序列化必須過一次遮蔽。**注意 S10 的已知限制**（見 C.7 之下）。 |
| N6 | **ActionLog 寫入** | ActionLog 的 before/after snapshot 必須過 `ensureSnapshotJsonHasNoSecretRefs` 等價 guard。 |
| N7 | **錯誤訊息** | 所有 tool 錯誤回給 LLM 的字串必須是**固定安全字串或 error code**，不得夾帶 URL／server body／exception message 原文。現有慣例：`QuickAddService` 的 `ERR_*` 常數。 |
| N8 | **Vault 存取唯一性** | Assistant 不得**新增**任何解密路徫。raw 值只能由 `AndroidCredentialVault.bearerToken` 在 HTTP 邊界取出（`InferenceTransport` 內）。 |
| N9 | **不建新推論入口** | Assistant 探測／測試必經 `InferenceEngine.execute`（`source=ASSISTANT_TEST`），不得自建 OkHttp 呼叫。 |
| N10 | **Telemetry source** | 每一次 Assistant 發起的 inference（含失敗／取消／FREE_NOW）都要 `source = TelemetrySource.ASSISTANT_TEST`，讓遙測可歸因。 |
| N11 | **Query／tool log** | 若 P9 建立 tool call 紀錄，內容同樣過 S10 遮蔽。 |
| N12 | **redact 限制的補償** | `redactSecrets` 只擋**完全等值／編碼變體**且 `< 8` 字元跳過。Assistant 的 tool result 遮蔽**不可只依賴它**——建議在**組裝層**就只放 safe ref 與已過濾欄位，而非「先放全部再遮蔽」。 |

### D.3 已知的邊界薄弱點（實作時必須主動補）

1. **`redactSecrets` 不做子字串／前後綴比對**，`< 8` 字元短 token 不遮蔽（`BACKLOG` F18 殘餘，刻意接受）。→ N12 補償。
2. **`CredentialLifecycle.delete` 後 secret 不可復原**。Assistant 刪除後**不能**宣稱 Undo，只能提示需重新輸入。
3. **`savePrice`／`saveQuota` append-only**。Undo 不得實作為刪除 `snapshot_facts` 列（會破 last-good 契約）。只能寫新事實。
4. **`abandon` 會刪孤兒 vault refs**（`QuickAddService.kt:281`–`:289`）。若 Assistant 在 abandon 後要 Undo，**secret 已不存在**。
5. **自訂 auth 兄弟 refs**：`deleteCredential` 會連帶清掉同 provider 的 custom field refs（`AtlasViewModel.kt` `customSiblingRefs` ＋ `customAuthCleanupRefs`），**PAT 永不列入**。Assistant 若只刪主 ref，會留下孤兒 sibling secret。

---

## E. Review 不可繞過的保證點

**規格來源**：`PRODUCT_SPEC` §5「Quick／Advanced／Assistant onboarding 必須共用同一可恢復 workflow，不能為 Assistant 另開旁路繞過 Review」＋ `PROVIDER_ONBOARDING_CONTRACT` §3.3「Assistant 不得替使用者省略 Review：必須先回傳 Review 摘要（含 §1.3 八項 ＋ Unknown 清單），取得明確確認後才可 commit」＋ §3.4 commit 冪等。

### F.1 現有三道閘門（**已實作，Assistant 必須走**）

| # | 閘門 | 位置 | 機制 |
|---|---|---|---|
| G1 | **commit 需 ReviewApproval** | `OnboardingStore.kt:236`–`:239` | `approval == null \|\| !approval.approved` → `throw CommitRejectedException(REVIEW_NOT_APPROVED)`。**在 transaction 之前**。 |
| G2 | **commit 需至少一模型** | `OnboardingStore.kt:240`–`:243` | `commitModelIds(snapshot).isEmpty()` → `NO_MODELS_TO_TEST` |
| G3 | **REVIEW／COMMIT 不可 skip** | `OnboardingWorkflow.kt:225`–`:227` | `if (step == REVIEW \|\| step == COMMIT) throw OnboardingStateException("review / commit 不可跳過")` |
| G4 | **已 commit 不可 abandon** | `OnboardingWorkflow.kt:283`–`:285` | `COMMITTED` → `throw OnboardingStateException`（且不動 vault） |
| G5 | **已 abandon 不可 commit** | `OnboardingWorkflow.kt:256`–`:258` | `ABANDONED` → 拋錯 |
| G6 | **已結束 workflow 不可 skipStep** | `OnboardingWorkflow.kt:222`–`:224` | `COMMITTED \|\| ABANDONED` → 拋錯 |
| G7 | **commit 冪等（去重鍵 = workflowId）** | `OnboardingStore.kt:246`–`:249` | `onboardingCommitDao().getByWorkflow(...)` 命中即回既有 receipt；insert `ABORT` 防併發重放 |
| G8 | **approvedBy 記錄** | `QuickAddService.kt:252`–`:256` | `ReviewApproval(approved, approvedAtMillis, approvedBy)` 落 snapshot JSON |
| G9 | **改輸入必清 approval** | `QuickAddService.editQuick` KDoc | 清 `reviewApproval`；`AUTH_FAILED` 換 key 從 AUTH 重跑 |
| G10 | **Review 摘要資料源** | `QuickAddService.reviewSummary`（`:233`） | 回 `ReviewItems`（含 `unknownFields`／`confidences`／`evidenceIds`） |

### F.2 Assistant 新增必須建立的保證點

| # | 保證點 | 說明 |
|---|---|---|
| **A1** | **Assistant 不持有 commit 權限** | Assistant tool 層**不提供** `onboarding.commit`。commit 只能由人類在 UI 觸發（`OnboardingViewModel.commit`），或由 UI 收到 Assistant 的 commit 建議後由人按下。 |
| **A2** | **Review 摘要先於 commit** | Assistant 在建議 commit 前**必須**先輸出 `reviewSummary(workflowId)` 的完整內容（含 `unknownFields` 與 `confidences`），不得只給摘要的一層。 |
| **A3** | **Assistant 不得自填 `approvedBy`** | `approvedBy` 必須是人類身分標記，**不得由 LLM 產生**。這是最容易被繞過的一點——`approveAndCommit(workflowId, approvedBy)` 的第二個參數是 `String?`，型別上 Assistant 可以塞任意字串。**必須靠架構限制（不提供該 tool）而非約定。** |
| **A4** | **Assistant 不得直接呼叫 `RoomCommitter`／`AtlasRepository.addConnection`** | 兩條路徑都**不經 Review**：`addConnection` 是單一 transaction 直插 6 張表（`AtlasRepository.kt:109`）。Assistant 走這條就是旁路。 |
| **A5** | **Assistant 不得用 `AtlasViewModel.saveConnection`／`saveManualConnection`** | 這兩個函式（`AtlasViewModel.kt:194`／`:289`）**完全沒有 Review 流程**（自建 UUID 直插）。Assistant 不得以「UI 可以用」為由重用。 |
| **A6** | **旁路防護測試** | 需一個測試：構造 Assistant 對既有 provider 的 onboarding 流程，斷言**未經 Review/commit 確認時 DB 內 provider 數量不變**。 |
| **A7** | **Review 內容不得被 Assistant 摘要稀釋** | `ReviewItems.unknownFields` 非空時，Assistant 回覆**必須包含**每一項（不得只說「有些欄位未知」）。 |
| **A8** | **Unknown 不得被 Assistant 升格** | Assistant 回答中不得把 `PRICING_UNKNOWN`／`QUOTA_UNKNOWN` 說成免費或 0。`ReviewItems.pricingStatus`／`quotaStatus` 是 `PriceState`／`QuotaState` enum，直接透傳。 |
| **A9** | **provenance 不得被 Assistant 混淆** | `ReviewItems.confidences: Map<ReviewItem, EvidenceConfidence>` 與 `officialHealth`／`observedHealth` 雙軌，Assistant 不得把 observed 說成 official。 |
| **A10** | **Assistant 不得刪除 `snapshot_facts`／`snapshot_heads`** | last-good 契約（X-006）。Assistant 的 Undo 若觸及價格／配額，只能新增事實。 |

---

## F. 驗收清單

> 依 `TRACEABILITY` Accepted 硬條件：production flow ＋ tests ＋ reviewer ＋ **完整綠 CI** ＋ 文件更新。只有 class/schema 不算完成（這正是 B1 的教訓）。

### G.1 逐 requirement 驗收

| ASST | 驗收條件（可測） |
|---|---|
| **ASST-001** 獨立 assistant model 選擇 | ① Assistant 的推論 target 可獨立於 Playground `selectedId` 選擇；② 兩者並存互不干擾（有測試）；③ Assistant target 走 `InferenceTarget` 型別邊界（raw value 型別上進不來） |
| **ASST-002** structured read tools | ① 每個 §14「可讀」項目至少一個 tool（Providers／Accounts／safe credential metadata／Models／selected key-group／Balance／Quota／Pricing／History／Performance／Notes／Events／Favorites／Watchlists／Settings／Crawler Evidence）；② **未實作的項目（Notices／Events／Favorites／Watchlists／Settings／Crawler Evidence）必須明確回報不可用，不得回空集合冒充「無資料」**；③ tool result 序列化後過 secret 遮蔽 |
| **ASST-003** structured research/probe tools | ① 探測必經 `ProbeStepExecutor`／`CatalogProbe`／`InferenceEngine`（無自建 HTTP）；② 每次 probe 有 telemetry 且 `source=ASSISTANT_TEST`；③ 預算（`RequestGrant`）由 workflow 計數，Assistant 不可重置；④ `429`／`402`／`401`／`403`／`404` 不自動重試 |
| **ASST-004** structured app mutation tools | ① 每個 mutation 經 A.3／A.5 的既有 repository/service API；② 每次 mutation 寫 ActionLog；③ mutation 失敗不清 last-good；④ 跨 provider／跨 account 引用全被拒 |
| **ASST-005** safeCredentialId-only LLM boundary | ① **tool schema 無任何 raw secret 參數欄位**（靜態斷言 schema 欄位名）；② **對話貼入疑似 key 的測試**：raw 值不進 `PromptMessage`、不進 ActionLog、不進 DB、不回顯；③ 全部 Assistant 推論經 vault 邊界；④ tool result 過遮蔽 |
| **ASST-006** shared onboarding workflow | ① Assistant 與 UI **共用同一 `QuickAddService` instance**（single-flight gate 不得分裂，有測試）；② Assistant 無 commit tool；③ Assistant 走 `onboarding.start`／`resume`／`retryStep`／`skipStep`；④ Assistant 不得呼叫 `addConnection`／`saveConnection` |
| **ASST-007** ActionLog | ① 表存在（Room v8，`MIGRATION_7_8`，`7.json`＋`8.json` 納管）；② 每個 mutation 有對應 ActionLog（含 before/after）；③ 寫入前過 secret guard（測試：含 `sk-` 的 payload 被拒）；④ ActionLog 不含 chat 內容全文 |
| **ASST-008** Undo | ① 可逆 mutation 可 Undo 且還原到 before 狀態（逐項測試）；② 不可逆 mutation（`delete`／`abandon`／`commit`／`savePrice`）**明確回報不可 Undo**，不假裝成功；③ Undo 本身也寫 ActionLog；④ Undo 不刪 `snapshot_facts` |
| **ASST-009** destructive confirmation | ① 刪 credential／刪 provider／換 PAT／critical base URL／bulk delete **無 confirmation token 一律拒絕**（測試）；② confirmation token 由人類 UI 產生且一次性；③ `abandon` 同樣需要；④ 確認前 DB 不變 |
| **ASST-010** evidence-aware answers | ① 每個事實性回答附 evidence ref 或明確標 UNKNOWN；② Official vs Local vs External 不混淆；③ `unknownFields` 逐項回報；④ Unknown 不升格為 0／Free／Success |

### G.2 跨 cutting 驗收（`TRACEABILITY` Cross-cutting）

| ID | 驗收條件 |
|---|---|
| **X-001** | Assistant 回答中 Unknown ≠ 0、Unknown ≠ Free（有測試：資料為 UNKNOWN 時，Assistant 回覆不得含「0」或「免費」） |
| **X-002** | Assistant 的每次推論（含 FREE_NOW、零價格）都保存 telemetry |
| **X-003** | raw secret 不出現在 LLM context／repo／log／telemetry／ActionLog／tool result／例外訊息／logcat |
| **X-004** | Assistant 觸發的 pricing／quota 寫入不縮水 `PricingScope`（七維） |
| **X-005** | manual／official／observed／external 不互相冒充 |
| **X-006** | Assistant 觸發的 sync 失敗不清 last-good |
| **X-007** | 一次性 Credit 不被 Assistant 說成 recurring free |
| **X-008** | 無 mock／fixture 冒充產品資料 |
| **X-010** | 本文件／TRACEABILITY／DELIVERY_MATRIX 狀態與 HEAD ＋ 綠 CI 一致 |

### G.3 Reviewer 必查重點（`ORCHESTRATION.md` §Reviewer 對 P9 的適用項）

1. **race / cancellation**：`QuickAddService` 的 `operations` gate ＋ `OnboardingWorkflow` 的 `gate` 雙層；Assistant 與 UI 同時驅動同一 workflow 的 single-flight 是否真的成立。
2. **scope 汙染**：Assistant 寫入的 `PricingScope` 七維是否被 Assistant 偷偷簡化（尤其 `group` 與 `operation`）。
3. **PAT／inference role 混淆**：Assistant 會不會把 PAT 當 inference key 傳進 tool。
4. **secret leak**：Reviewer 必須實際讀 tool result 序列化路徑與 ActionLog 寫入路徑，不能只看 schema。
5. **Undo 偽造**：Undo 若只是「刪掉新列」而沒有恢復 before 狀態，視同未做。
6. **Review 繞過**：必須實際嘗試一條旁路（例如 Assistant 直接觸 `addConnection`）並確認被結構性阻擋，而不是靠「我們沒寫那個 tool」。
7. **Unknown → 0/Free/Success**：Assistant 的自然語言輸出層最容易被 reviewer 漏掉，需專門檢查字串。
8. **只有 schema 沒有 production caller**：B1 的教訓。`action_log` 表建好但沒有 mutation 寫入點 = 未完成。

### G.4 文件更新待辦（P9 完成時）

- `docs/TRACEABILITY.md`：Phase 9 十列補 ACCEPTED ＋ code/tests/reviewer/green SHA。
- `docs/DELIVERY_MATRIX.md`：P9 段由「缺口：全部；ActionLog 表未建」更新為實際狀態。
- `docs/BACKLOG.md`：若 P9 有關閉 F10／F11（Crawler candidate 持久化、Evidence scope 欄位），需在此標 HEAD 證據。
- `docs/ORCHESTRATION.md`／`ACTIVE_TASK.md`：Phase 推進。
- `app/schemas/…/7.json`：若 P9 前未由 P6 納管，**必須在 P9 第一波處理**（否則 v8 的 migration 鏈會斷在缺檔狀態）。

---

## G. 本文件明確不做的事

- 不寫任何程式碼、schema、migration。
- 不宣告任何 ASST requirement 為 ACCEPTED 或 PARTIAL。
- 不修改任何 contract（`PRODUCT_SPEC`／`DECISIONS`／`PROVIDER_ONBOARDING_CONTRACT`）。
- 不判定 P6 的 B1 狀態（該項歸 P6 主 session）。
- 不提出架構決策（B.2 的 Undo-vs-append-only 取捨、D.4 的 Assistant 入口位置）——這些需主 AI／使用者依 `ORCHESTRATION.md` §自主權裁定。

## H. 需要主 AI／使用者裁定的未決事項

| # | 事項 | 為何不能由本 session 決定 |
|---|---|---|
| U1 | **Assistant 的 UI 落點**（C.15） | `PRODUCT_SPEC` §2 明文固定 bottom navigation 為 3 tab、Settings 在右上。加第 4 tab 會違反 §2；不加則 Assistant 入口位置是產品決策。 |
| U2 | **Undo 對 `commit` 的語意**（B.2） | `DELIVERY_MATRIX` Gate 寫「Undo 可還原 commit」，但 `RoomCommitter` 是單 transaction 寫 8+ 表、無反向路徑。達成 Gate 需要在 `RoomCommitter` 前加 snapshot-before 機制（架構成本高），或修改 Gate 定義（需使用者同意，因為 Gate 是交付條件）。 |
| U3 | **不可逆動作的 Undo 契約**（B.2 表格） | `savePrice` append-only 與 Undo 語意衝突；需決定 Undo 是「新增 revert 事實」還是「軟刪除」。前者新增 `snapshot_facts` kind，後者破 last-good 契約。 |
| U4 | **`delete Provider` 是否為 v1 範圍**（B.3） | `PRODUCT_SPEC` §14 把「刪 Provider」列為需 confirmation 的 destructive 動作，但 `ProviderDao` 無 delete、且無 Undo 可設計。 |
| U5 | **Assistant 是否可用 crawler evidence**（C.12） | 依賴 BACKLOG F10（F10 歸 P8）。P9 若要在 §14「Crawler Evidence」可讀清單上打勾，會跨 Phase 依賴。 |
| U6 | **tool-calling 協議**（C.3） | `OpenAiTransport` 目前不送 `tools` 欄位。Assistant 的 structured tool 是走 OpenAI function-calling、還是用本地 grammar／prefix 解析，是架構決策且會影響所有 adapter 的相容性。 |
| U7 | **Assistant 是否計入 quota 保護預算**（ASST-003） | `PROVIDER_ONBOARDING_CONTRACT` §5 的預算是 per-workflow。Assistant 發起的獨立 probe 是否共享同一預算池，需定義。 |
