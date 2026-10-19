# VPS 與 Mac 0.6.1 的正式操作對照

2026-10-19，VPS-MAC-PARITY-002 候選。使用者要求先完成 VPS 的全部 Mac 介面／功能對齊；不是 Android 下一片，也不是把完整 Master Spec 標成已完成。固定比較 Mac `74e2c378c1117fda3bebdb9f8d50ead872aa5af7`，不讀取或覆寫原始工作樹的五份 staged Mac 修改。

本片以前一輪已驗收 VPS `3aec657` 為基線。下表是正式 caller 的對照；候選仍須最新 HEAD 六項 CI、原十六個 Browser 加新增完整流程、實圖及 expanded workload 驗收。最新 SHA／run／job 以 PR #20 與交付驗證檔為準；不以舊綠燈代替本片。

| Mac 正式畫面／操作 | VPS 正式入口 | 本片接續 |
| --- | --- | --- |
| RootView 三工作區／側欄／上方工具 | `ui.go`、`ui_js_mac_alignment.go`、`ui_css_mac_parity.go` | 中性色、三工作區數量、工具列、快速選模與可隱藏側欄；手機仍有 48px 底導覽 |
| 全部／封存／目前連線的對話搜尋及分頁 | sidebar → `/sessions` | 改真實全域及內容搜尋，50 筆分頁，拒絕舊查詢、last-good 與重試，不再只顯示目前連線最近 24 段 |
| ChatView／ChatSettings／ModelPicker | 原 chat／session settings／完整 scope picker | 保留訊息、system／tools、IME、取消、Markdown、明確重試與完整匯出；換對話保留該段記憶體草稿／附件 |
| ChatTranscript、AttachmentTray、MediaOptions | `ui_js_chat.go`、`ui_js_media.go`、`ui_js_tools.go` | 延用正式全部協定、圖片／PDF／音訊、image edit／speech／embedding／rerank、工具接續；不複製或縮減已驗 workload |
| ProvidersView 搜尋／免費連線／待簽到 | Providers 清單 → 明確 Provider 詳情 | 分組清單、描述搜尋、已確認 FREE NOW 篩選；保留 VPS 已加入模型篩選 |
| Quick／Advanced／Batch／Review | 新增與匯入 → onboarding preview／confirm | 多模型 ID、完整用途、認證前綴；原草稿、可恢復 Review、明確模型加入保留 |
| ProviderDetail 概覽／帳戶／Catalog／端點／設定／站方資料 | Provider 六分區 | Catalog 按 Provider 搜尋／加入狀態／分頁；不把 Catalog 當 My Models |
| ProviderAccountEditor／KeyEditor | 帳戶與 Key → 獨立原生 browser dialog | 同一組真 form nodes 移至可捲動 sheet；close／reopen 記憶體草稿、欄位 revision、穩定 ID／鎖與只重讀 GET 恢復均保留 |
| CredentialMetadataEditor／秘密更新／恢復 | Key 更多操作 → metadata Review／rotate | 精確帳戶與 Key；用途／來源／備註／優先序、啟停；更新與版本恢復分開，未知狀態不猜成停用 |
| AccountCheckinController | Provider 概覽與待簽到 | 依設定時區／日期逐帳戶記錄，不使用舊全 Provider 收據冒充每個帳戶完成 |
| ProviderSiteView／ProviderPerformanceView | Provider 站方資料／Model 效能來源 | 匿名公開四來源／獨立錯誤／last-good／實際上游狀態；站方百分比、秒單位與群組各自處理 |
| ProviderAccountDataView | 站方資料 → 帳戶與 Key 用量 | 手動查詢推論 Key 額度或相符帳戶 PAT 用量；切換範圍拒絕晚回應，原始額度不換成 USD／免費資格 |
| ModelsView 我的模型／探索／已隱藏／研究／知識 | Models 五分區 | 共用可反應式清單保留篩選草稿；已隱藏包含封存，不刪除歷史；按帳戶／Key／群組搜尋 |
| ModelDetail 概覽／價格／額度／效能／歷史／設定 | Model 六分區 | 拆開價格與額度；效能以本機／站方／外部三來源切換，來源不混算 |
| ModelInformationEditor／Capabilities | 模型設定 | Vendor、context、描述、別名、模態／端點、全部十九個確認能力，沿用正式 API 驗證 |
| ModelFactsEditor | 模型設定 → 手動價格與額度 | Cache 價格、七個可留空額度值、scope_kind／slice；精確價格文字與安全整數，零不等於未知 |
| ModelPricingPanel／QuotaPanel | 價格／額度頁 | 價格來源軌／四個 leg／完整觀測查詢；各額度池可讀數值、來源、範圍、單位，不平均、不猜總額 |
| TrendChart／ObservationArchive | 價格與本機／站方效能、觀測歷史 | 真時間間距、缺段、滑鼠／鍵盤選點、Escape、精確 loaded-only JSON、Cache、205+ 筆分頁與 request receipt |
| RequestHistory／RequestReceipt | 模型歷史／設定請求紀錄／聊天收據 | 來源／scope／完整匯出保留；圖點收據另開子視窗，主詳情與草稿仍在 |
| ComparisonView | 勾選模型 → 連線比較 | 同 canonical 2–4 連線、相同可編提示詞、pending／cancel、逐連線結果／收據與 JSON，不自動發出請求 |
| ResearchView／ResearchDetails／Knowledge | Models 研究／知識，Settings 研究入口 | 完整既有漏斗、證據／預算／pause／resume、知識與 connector Review／Undo 保留；補可編來源與 Recipe 版本保存／恢復 |
| AssistantView／SecretEditor／ActionReview | 上方 Assistant → 原正式 structured tools | 共用引擎、safe refs、待確認與 Undo，保留所有既有 Browser 的實際操作與秘密邊界 |
| SettingsView 六分區／Services／Backup | 一般／服務／資料／研究／請求紀錄／通知 | 外觀、同步與通知 OFF 預設；LMSpeed 語言／搜尋、AA、JSON 檔匯入預覽、通知讀取與已讀操作 |

## 保留與平台差異

- Mac 的鑰匙圈／Finder／原生通知由 VPS vault、伺服器資料目錄、browser 檔案下載及瀏覽器通知能力對應；不假裝網站能存取 Mac Keychain。服務部署、反向代理、固定 master key、完整歷史／備份與低 RAM 架構不變。
- 沒有新增正式 JS runtime／第三方 UI／圖表依賴，仍為單 Go／SQLite 與原生 HTML／CSS／SVG；沒有 schema／retention／自動 inference／routing／fallback 變更。
- Generic Provider 沒有經確認的 New API 支援時，先顯示本機來源；使用者仍可明確開啟站方來源。不能為仿畫面而對未知站點自動發出錯誤 metadata 請求。
- 列出 caller 不代表所有真實站點／私人 schema／帳戶條件已驗。完整 Master Spec P1–P10 仍須逐項接續，既有 Mac 自身未完成的情境不偽稱成已實作能力。
