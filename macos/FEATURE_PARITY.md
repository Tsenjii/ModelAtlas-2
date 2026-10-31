# Mac 功能對齊驗收

2026-10-18最新：011／0.6.0 `48ff43f` 九項SUCCESS、70核心／136靜態圖與28 frame已驗收交付。012接續帳戶／Key工作區，並以 `../docs/CROSS_PLATFORM_STRENGTHS.md` 持續保留其他平台優點；詳 `PROVIDER_MANAGEMENT.md`。新HEAD的九項／78核心／148實圖與28 frame前仍候選，完整Master Spec為PARTIAL。下方為歷史紀錄。

2026-10-18最新：010／0.5.0已由3529736九項SUCCESS／120圖驗收交付；011／0.6.0現正補帳戶簽到、Provider概覽、原生過場與Reduce Motion，詳`PROVIDER_MOTION.md`。新HEAD九項／62核心／64圖每架構與動畫frame前為候選；完整Master Spec仍PARTIAL。

2026-10-18 最新進度：跨平台既有入口對齊006後已逐片驗收至009／0.4.0（52d5bfd，九項SUCCESS／110圖）；以下表格是006當時的原始工作表。010現正增加原生互動圖表與完整觀測分頁，詳 `CHARTS.md`；原始完整需求仍以 `MASTER_SCOPE.md` 為PARTIAL，不因既有API入口存在而宣稱全需求完成。

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
- 原25實圖保留，再加17張實際 production View（含新首頁與設定深淺色），兩架構總84圖。資料載入畫面等待真 ready marker；不以初始空畫面作驗收。測試資料全為隔離合成資料，不冒充外部服務可用性。
- 首候選 `a01de9c`／Mac39037663731 首輪編譯發現新表單 wrapper 多重宣告與收據按鈕括號錯誤，已修正；`9502b3f`／Mac39037990751 剩 Catalog 詳情大型 ViewBuilder 超過 type-check 時限，拆為明確子 View。這些失敗保留，新 HEAD 重新九項同 SHA；不得稱先前已驗收。

`94cd02e`／Mac39038944952 Universal編譯成功；arm64保留原native25圖及核心測試通過，新增parity在seed階段遭端點409阻擋。新增fixture誤以內建操作名稱作自訂端點ID，正式引擎正確拒絕；改為獨立qa-*不可變profile，不放寬引擎或刪斷言。新增對話ALL／archive／完整scope篩選驗證及模型資料保存後reactive詳情；新HEAD重新九項與76圖驗收。

`709d138`／Mac39039811715 Universal再度編譯成功；新增arm64已走完所有媒體wire、批次confirm/undo及stale guards，connector建立時422。fixture把需ASCII識別ID的規則填成人類中文名稱；改為quota-fixture，原生表單明確標示ID並提前給對應驗證訊息。補上深色細字對比、效能來源列與篩選一致，以及已保存／下一稿狀態；不移除測試。新HEAD重新全九項及76圖。


2026-10-17 最新 UI 指示：使用者明確要求接近 Magpie 與現代原生 Mac App。保留三工作區與 teal，重整原生側欄／對話篩選選單、文字層級與列表密度、搜尋與頁籤、透明 AppKit composer、起手提示、鍵盤選模高亮、側欄式設定及表單 chrome。官方 panel／picker／add 圖只作視覺參考，不引入 routing／gateway。所有既有 API caller 與 guards 保留，新增首頁與設定的深淺色實圖，合計42圖／架構、84圖。

`974886a`／Mac39040938623 arm64已通過29核心、原25圖與完整新增API流程；最後截圖數量檢查把fixture輸入input.png一起算入而失敗。將輸入附件放入fixture-inputs子目錄，原13圖全部保留再加4圖；不調低斷言／放寬時間。CI artifact補上parity原生圖、完整report、footprint與app log，避免只上傳舊25圖。VPS39040938617四項及Android39040938643兩項SUCCESS不能代替新HEAD。


`8df5a0d`／Mac39042204093 新原生composer在NSSize的Double／CGFloat多載間發生編譯歧義，改明確CGFloat。另補實際AppKit輸入區的多行中文輸入／marked-text不被外部更新取代／重設草稿清除舊Undo驗證；不靠store直寫冒充輸入。新HEAD仍須全部九項與84實圖。


`907622b`／Mac39042411074 Universal與arm64／Intel29核心＋42圖全PASS；VPS39042411104四項SUCCESS，Android39042411059當時仍執行。首輪實圖審查後：原生下拉選單與sheet未完整跟隨深色及teal，改同步NSApplication外觀並將tint套用至sheet；側欄採更淡原生中性色，聊天輸入區縮短，主清單以人類端點摘要取代內部操作常數，精確identity及help全文不改。Capture同時同步外觀偏好，避免設定圖顯示深色選項卻是淺色畫面，另驗實際NSApp appearance。原全部84圖／guards保留，新HEAD重新九項。


原生Review實圖另修：MODEL_BATCH原先只看到收起的items，雖保留完整快照但不方便確認。現在直接列出每個完整scope的模型、帳戶／Key／群組與前後狀態，收藏／追蹤變更也可見；原始快照仍可展開。不完整或對不上scope的批次禁止確認，真API驗證畫面使用的scope集合及ACTIVE→HIDDEN內容。原84圖／confirm／undo／ABA stale與跨帳戶guard全部保留。


深色模型清單實圖仍見macOS小型自動Picker以淺色field繪製；高頻來源／排序改為原生Menu內的單選Picker，保留全部選項與Binding，按鈕底色跟隨工作區。批次Review同時快取本次rows供顯示，避免每列重建整份scope配對。原84圖及所有功能驗證不減。


`c390957`／Mac39044507432 Universal與雙架構29核心／42圖均PASS。首輪最終實圖發現新Menu標籤繼承深teal，在深色小字的對比仍不足；標籤及Menu tint明確採原生primary。只修可讀性，全部scope／狀態Binding／84圖不變；新HEAD仍重新九項驗收。
