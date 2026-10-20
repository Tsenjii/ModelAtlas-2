# 模型查找、比較與使用

2026-10-19，VPS-MODEL-WORKFLOW-005 候選。接續已驗手機對齊，不改既有三主導覽、單 Go／SQLite、正式資料來源或歷史保存。此檔說明正式接線；本片必須由最新 HEAD 六項同 SHA CI、原十九加新增第二十 Browser、實圖及原 expanded workload 驗收，精確 SHA／run 結果由 PR #20 保存。

## 常用檢視

- Models 的「儲存檢視」明確保存當前搜尋、篩選、排序與效能來源。下次進站仍採原預設；使用者選取檢視後才套用，不自動切推論 Key。
- 檢視名稱最多 48 字元、同一瀏覽器最多 12 份。選取同名檢視後明確「更新檢視」；換名稱為另存新份，其他同名新建會提示而不覆蓋。
- `ui_js_model_views.go` 只允許 `modelFilterIDs` 中的顯示偏好。以 version=1 存到該 origin 的 browser localStorage；沒有雲端同步、server schema 或備份變更。不保存對話草稿、附件、訊息、raw Key／PAT，也不更改 membership、排序權重或推論設定。
- 選項已不存在時整份拒絕套用，保留原篩選，避免原精確 Key／Group 被放寬成全部。已隱藏檢視會切到正確工作區；Global Catalog 仍與 My Models 分開。
- storage 不可用／格式無效／空間不足皆在本區顯示錯誤，不顯示成功，不影響現有清單。刪除檢視只刪本機偏好，當前篩選仍保留；其他分頁的變更由 storage event 更新選單。

## 查找與對齊

- 最近使用直接沿用同 scope 本機 `last_request_at` 的 7 天條件；追蹤沿用既有 watchlist。不以站方成功率或 External 補作本機使用紀錄。
- 目前作用中的篩選以可移除 chips 顯示，含來源／排序／數值與精確 Key／Group。移除一項保留其餘條件，重設保留目前工作區的 ACTIVE／RETAINED 預設。
- 手機快捷鍵單列橫捲，檢視選單與儲存表單同頁對齊；320px、直橫向及 200% 大字仍可點按／捲動。延用前片 16px gutters、48px touch controls、深淺色及安全區；手機原生捲動預留底部導覽區，另驗儲存按鈕實際可捲至導覽上方。

## 精確連線直達

各家速度卡片與實際比較結果新增「切換到這個連線」。`openModelConnection` 先驗 busy、picker readiness 與目前已加入／啟用的完整 scope，再走原 `choosePickerScope`／target change handler，帶完整 Provider × Account × Credential × Model × Group × Endpoint／Operation。

切換只前往 Playground 與讀取該 scope 已有對話，不隱性 inference、不自動建立對話；原 session／draft owner、附件和歷史仍由原路徑管理，並在此分頁記住每個完整 scope 上次選取的對話；比較所新增的測試對話不會奪走原對話與草稿。沒有既有對話時沿用「建立新對話」入口。按鈕在目前不具備可選 scope 時停用，操作時再驗一次，避免舊詳情或比較回條選中失效連線。原「使用此模型」的新對話入口仍保留。

## 真實驗證與界線

`model_views_test.go` 建立正式 Handler、SQLite／vault、TLS 上游與兩個同名 credential ID 的不同帳戶／Group；保留兩段既有對話及訊息、三個 active／一個 hidden membership，另有未加入 Catalog。`e2e/model-views.cjs` 由正式 UI 保存／更新／重載／套用／移除偏好，驗 Unknown 不補零、站方速度不冒充本機、HTML-safe 名稱、同名錯誤、storage denied、過期 Key option 拒絕套用；再由連線與比較結果切換、確認兩份草稿和歷史、最後明確發送。

新增流程只允許比較兩次及最後明確聊天一次的三次 TLS inference。瀏覽、排序、儲存、套用及導航均不 inference／不新增 session。原十九流程及所有 workload／timeout／資料量保留；無外部依賴或 schema migration。驗證為 Chromium 手機觸控／大字模擬，未代表實體 iPhone／Safari 已驗；沒有獨立外部 reviewer，不 merge、不遠端部署，完整 Master Spec 仍 PARTIAL。
