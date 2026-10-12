# 首條 slice 儲存契約

委派 session 僅實作 `app/src/main/kotlin/dev/modelatlas/app/data/` 與 `app/src/androidTest/kotlin/dev/modelatlas/app/data/`。
Secret 原始值/聊天內容不得進 DB。其他 session 同時工作，不碰 build/security/UI。

`ConnectionRecord`（safe data class）欄位：
id:String（offering id）、providerId:String、accountId:String、credentialId:String、endpointId:String、name:String、baseUrl:String、endpointUrl:String、modelId:String、credentialLabel:String="主要憑證"、priceState:String="UNKNOWN"、quotaState:String="UNKNOWN"、officialHealth:String="UNKNOWN"、observedHealth:String="UNKNOWN"、checkinUrl:String?=null、requiresDailyCheckin:Boolean=false。

`AtlasDatabase` 是 RoomDatabase，companion `create(context:Context):AtlasDatabase`，DB file modelatlas.db，version=1、exportSchema=true，無destructive fallback，暴露 DAO。

`AtlasRepository(database:AtlasDatabase)` 實作 `dev.modelatlas.core.inference.TelemetrySink`：
- `connections: Flow<List<ConnectionRecord>>`
- `samples: Flow<List<PerformanceSample>>`，只取最新100筆作UI展示，不刪歷史。
- `checkins: Flow<List<CheckinEntity>>`
- `suspend fun addConnection(record:ConnectionRecord)`：transaction 插入各表，非 replace 既有。
- `suspend fun save(sample:PerformanceSample)`：同id upsert，開始/完成只有兩次write，全部欄位完整保存。`PerformanceSample` 將由主session加 kotlinx.serialization @Serializable，可用 Json.encodeToString/decodeFromString，Room table 額外 id/providerId/modelId/startedAt/outcome query cols。
- `suspend fun recoverInterrupted()`：App process 啟動一次將 RUNNING 標 INTERRUPTED，completedAt/latency 不猜；可先讀 RUNNING rows 再 json copy保存。
- `suspend fun completeCheckin(providerId:String, date:String, completedAt:Long)`：local ISO date，source=USER。

首條 slice normalized Room tables：ProviderEntity（health兩欄/checkin），AccountEntity，CredentialEntity（safe ref/label/enabled），EndpointEntity（base/endpoint/protocol），OfferingEntity（model/endpoint/manual/source，不宣稱已驗證）。外鍵 RESTRICT、有索引，不任意cascade刪歷史。ConnectionRecord 用 join @Query 投影。
整合修正：Offering 不綁 Account/Credential；由 CredentialAvailabilityEntity(id,offeringId,accountId,credentialId,priceState,quotaState) 建立關聯。ConnectionRecord.id 指 availability id。Endpoint.protocol=CHAT_COMPLETIONS（不是 HTTPS）；新增 transaction 用 ABORT，禁止静默部分成功；join 過濾 disabled credentials。
另外可建立 EvidenceEntity（安全的摘要、scope refs、source/time/version）、PriceSnapshotEntity（id + 完整provider/account/credential/model/group/endpoint/operation/observedAt scope + json/decimal metadata）、QuotaSnapshotEntity（unknown可null）；別生成產品fixture。
`save(sample)` transaction 同時保存 PerformanceSample 與簡短本地 Evidence（request id/outcome/status/timestamp），不得存 prompt/response/secret/raw headers之外資料。
checkin日期唯一(providerId,date)，不需每午夜刪紀錄。

增加 Android instrumentation Room in-memory tests：connection重啟讀取（可用file DB reopening）、FREE_NOW telemetry保存、same id更新不重複、interrupted recovery、checkin date隔離。測試fixture只能在androidTest。
