# Mac 功能對齊驗收

MAC-PARITY-006，2026-10-17。對照目前 Android main `5009e0c8` 與 VPS `026f58ba`，Mac 起點為已驗收 0.1.4 `10563e3a`。目標為兩者現有功能的聯集；不以 Android 尚未完成的 P8–P10 規劃冒充既有功能。此文件是待驗收工作表，不是完成宣告。

| 功能 | 0.1.4 實況 | 本片原生入口／正式 API | 驗收狀態 |
|---|---|---|---|
| 四種對話協定、CUSTOM、工具結果、停止、歷史與重試 | 已接線，原生長串流／scope／設定已有驗證 | Playground／AtlasStore → infer、sessions、telemetry | 保留原 18 核心／25 圖與全部 guards |
| 影像生成／編輯、只含音訊的轉寫 | 轉寫被空文字 guard 阻擋 | MediaDraft／MediaOptionsView → infer/media | 候選 |
| 語音格式、語速、說話方式、Gemini current/legacy／雙人對話 | 選項不完整、缺雙人 | MediaDraft value snapshot／原生逐段編輯 | 候選 |
| 向量維度、rerank 筆數與完整結果下載 | 缺進階選項／下載 | MediaOptionsView／MediaResultView | 候選 |
| Quick／Advanced／Review、多帳戶／Key／PAT、rotation／版本恢復 | 已接線，缺批次與部分 origin 欄位 | Providers／BatchImportView → onboarding/import → 個別 Review | 候選 |
| 供應商設定、多 origin、端點／自訂 auth、New API | 部分原生欄位缺失 | ProviderDetail／credential origins／performance | 候選 |
| My Models 篩選／排序／權重／FREE NOW | 僅搜尋、類別、收藏 | ModelFilters／ModelFiltersView → scoped my-models | 候選 |
| 模型批次隱藏／封存／恢復、Review／confirm／undo | 缺批次 | Models → assistant/actions/preview、confirm、undo | 候選 |
| Catalog 篩選、詳細資料及精確 Key/Endpoint 加入 | 已加入／手動新增，篩選與 detail 不足 | CatalogView → models／my-models | 候選 |
| 模型分類／別名／價格／額度／notes/tags | 主要以 JSON 輸入、缺 notes/tags | ModelEditors／NotesEditor → metadata、facts、actions | 候選 |
| 效能來源／自訂時間窗、站方效能、同步與請求紀錄 | 無 custom、部分入口不足 | ModelDetail／RequestHistory → detail、intelligence/sync、telemetry | 候選 |
| Brave／AA／LMSpeed 服務金鑰與同步 | 缺專用入口 | Settings 服務 → integrations、aa、lmspeed | 候選 |
| 研究來源／所有 start 選項／狀態控制／追加預算與失敗重試 | 部分接線 | Research → sources、start、control、tasks | 候選 |
| 候選／證據／人工判讀／AA 動態排名 | 僅漏斗 JSON | Research candidate detail → evidence、review、aa | 候選 |
| 反思、知識提案／修訂／啟用／復原 | 唯讀 | Knowledge → knowledge、activate、versions/undo | 候選 |
| 宣告式 connector 草稿／真測試／啟用／修訂／復原 | 缺入口 | Knowledge connector editor → connectors、test、activate、versions/undo | 候選 |
| Assistant 設定／工具執行／停止／共享 Review／confirm／undo／紀錄 | 已接線，須補原生呈現及 onboarding action | Assistant → config、threads、run、actions | 候選 |
| 偏好、背景與通知、備份、完整匯出 | 已接線，原生資料管理可改善 | Settings → settings、notifications、backup | 保留並回歸 |

Unknown 不當 0／Free；Catalog 不等於 My Models。所有操作維持 Provider×Account×Credential×Model×Group×Endpoint；新增 UI 使用同一隨附引擎，沒有路由／fallback／gateway。不自動開啟背景工作或產生外部付費測試。實際 API 與原生畫面驗收完成後補 exact SHA/run/artifact。
