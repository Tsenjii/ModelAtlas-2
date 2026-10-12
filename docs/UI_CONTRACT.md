# 首條 Slice UI / ViewModel 契約

UI 使用 `dev.modelatlas.app.ui.AtlasScreen(viewModel: AtlasViewModel)`。
ViewModel 由 MainActivity 的 `by viewModels<AtlasViewModel>()` 建立（AndroidViewModel，Application）。

`dev.modelatlas.app.AtlasViewModel` 提供：
- `state: StateFlow<AtlasUiState>`，以 collectAsStateWithLifecycle 讀。
- `saveConnection(name:String, baseUrl:String, modelId:String, secret:String)`：HTTPS base URL（例如 https://seekai.cc/v1），建立全新 Provider/Account/Credential/Endpoint/Offering，成功清空 UI key；不替換既有。
- `selectConnection(id:String)`
- `send(prompt:String)`
- `cancel()`
- `dismissError()`
- `completeCheckin(providerId:String)`

`AtlasUiState`：
- connections: List<ConnectionItem>
- selectedId: String?
- busy: Boolean
- saving: Boolean
- formClearToken: Int（儲存成功 +1；表單以此一次性清空，不以 error 通道判斷，避免無關錯誤讓 secret 殘留）
- messages: List<ChatMessage>
- samples: List<SampleItem>（最新在前）
- error: String?（本地安全訊息，不含 URL/key/server body）

`ConnectionItem`：id, providerId, name, baseUrl, modelId, credentialLabel:String；priceState:String="UNKNOWN"；quotaState:String="UNKNOWN"；officialHealth:String="UNKNOWN"；observedHealth:String="UNKNOWN"；checkinUrl:String?；checkinPending:Boolean。
`ChatMessage`：id:String, role:String("user"/"assistant"), text:String。
`SampleItem`：id:String, providerId:String, modelId:String, outcome:String, startedAt:Long, ttftMs:Long?, ttfbMs:Long?, latencyMs:Long?, outputTokens:Long?, tps:Double?, priceState:String, quotaState:String。

## UI 範圍
僅先完成首條真實 slice 的安全手動連線，不假稱完整 Quick/Advanced Add。安全 key 欄位只用 remember，不用 rememberSaveable；密碼遮罩，無回顯/日誌。不保存聊天/輸入的 secret 到 Bundle。
三個 bottom tabs：Playground（預設）、Providers、Models；Settings 右上。Settings 以對話框說明本機資料/保留政策現況。
Playground：簡潔聊天區、當前連線選擇、composer、send/cancel、本地指標卡。
Providers：空狀態/連線卡、提供安全手動新增表單，有明確名稱/base URL/model/key labels；顯示 official/observed Health、價格/quota unknown；有 checkinUrl 時才顯示開啟簽到及完成按鈕（外部 Intent）。
Models：只顯示已建立手動 model ID，與 Providers 同一筆 price/quota 狀態（透傳，不另行判定），不造 catalog、不要宣稱手動模型已驗證。

checkinPending 以 domain `isCheckinPending` 判定（無紀錄、非當日、或 completedAt 為 null 皆為待簽到）；`CheckinEntity.completedAt` 為 nullable。
`requiresDailyCheckin`/`checkinUrl` 的手動 provisioning 為 Phase 3（Advanced Add 欄位），首條 slice 預設不寫入；開啟外部簽到只允許 https scheme。

## 設計依據
採 Material 3 系統字體、system light/dark、teal accent、簡潔扁平卡片。ui-ux 搜尋的 landing-page pattern 與本產品不符，捨棄；native 通用 guidance：48dp touch targets、8dp spacing、semantics、insets/IME、可滾動表單、無 emoji 結構 icon。字串全部 values/strings.xml（繁中 default），技術名詞可英文。正式尚需小螢幕/橫向/大字/深色裝置驗收。
