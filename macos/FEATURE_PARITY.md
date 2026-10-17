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

## 本片驗證入口（候選）

- `ParityTests` 十一項 core 回歸：空文字音訊、strict attachment、語音格式／快照、雙人協定／邊界、向量參數、Unknown 數值／單位、完整帳戶 Key 篩選、排序與覆蓋率、connector 對照與 PAT、批次待確認定義。原18項不減，合計29項。
- `NativeParity` 第二個獨立 App／DB／120秒階段，不擴大原測試上限：真 packaged engine→TLS fixture 檢查 current/legacy/雙人、voice/speed/instructions、空文字 multipart、dimensions/top_n、圖片與編輯；批次 exact-account confirm/undo、ABA stale 拒絕、connector 真測試→啟用→intelligence caller→復原、證據支持的知識版本／stale／復原、人工判讀、服務金鑰及 Assistant 安全參照、原對話不變與完整匯出。
- 原25實圖保留，再加13張實際 production View，兩架構總76圖。資料載入畫面等待真 ready marker；不以初始空畫面作驗收。測試資料全為隔離合成資料，不冒充外部服務可用性。
- 首候選 `a01de9c`／Mac39037663731 首輪編譯發現新表單 wrapper 多重宣告與收據按鈕括號錯誤，已修正；`9502b3f`／Mac39037990751 剩 Catalog 詳情大型 ViewBuilder 超過 type-check 時限，拆為明確子 View。這些失敗保留，新 HEAD 重新九項同 SHA；不得稱先前已驗收。
