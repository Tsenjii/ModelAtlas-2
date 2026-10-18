# Mac 圖表與完整觀測歷史：MAC-CHARTS-010

0.5.0（build 10）候選。已交付基線為 0.4.0 `52d5bfda49876c27c639842ca6605d11553c3c68`：Mac39090603877／VPS39090603936／Android39090603909 九項同 SHA SUCCESS，雙架構各 44 核心／55 PNG，110 張已檢視。新 HEAD 須重新九項、54 核心與59圖每架構／118圖，不借基線結果；完整 Master Spec 仍 PARTIAL。

## 原生操作

Models → 模型詳情 → 價格／效能／歷史。

- 價格圖移至價格頁上方，保留目前價格狀態，最新已確認值／來源另外展開。可選 1h／24h／7d／30d／全部／自訂、來源計價軌與輸入／輸出／固定請求／Cache；較早價格按需載入。
- 圖表顯示真正時間間距、Y 軸與原始單位、觀測數量。滑鼠查看、點擊固定；←／→或有標籤的前後按鈕逐筆選取，Home／End至首尾，Esc清除選取。缺值保留空段；0正常繪製，非串流TTFT不變成延遲或0。
- 圖點顯示精確原文、時間與狀態；本機效能點保留真sample ID，可開啟相同請求收據。匯出目前載入的單一圖表資料為JSON，包含scope／來源／單位／時間與精確值，明確標示 `LOADED_OBSERVATIONS_ONLY`，不冒充完整歷史匯出。
- 歷史按來源、類型、時間在完整永久資料上查詢，可載入超過200筆；顯示載入數／本次查詢總數。新查詢取消舊Task，revision拒絕舊回應；錯誤保留最後成功頁並提供重試，不把舊查詢冒充新篩選。離開面板停止未完成讀取。

沿用石墨灰／可選accent、SwiftUI／AppKit、最低macOS12；不新增圖表依賴或WebView。參考 [Apple Charts HIG](https://developer.apple.com/design/human-interface-guidelines/charts) 的文字層級、座標／單位、無障礙替代操作，以及 [Activity Monitor](https://support.apple.com/guide/activity-monitor/view-cpu-activity-actmntr43452/mac) 的主資料與圖表分區；未宣稱操作過外部App。

## 資料契約

`GET /models/observations` 是新只讀分頁入口；完整scope必填，來源／類型／PRICE分類與半開時間範圍均使用參數化SQL。每頁最多200，首頁固定rowid snapshot，後續keyset cursor綁定相同scope與查詢；同timestamp、多帳戶同Key ID、新紀錄插入不產生重複或漏頁。cursor是查詢續接資料，不是授權憑證。失敗仍是失敗，last-good查詢不變，不新增migration／retention／purge。

`ObservationPage`／`ObservationArchiveState` 拒絕foreign scope、錯誤filter、重複ID、cursor循環或不一致total；整頁檢查後才更新最後成功資料。`TrendData`只投影同一軌，精確decimal原文保留，Double僅作座標。標價／已確認扣款、來源、單位、origin／憑證版本維持原有獨立分軌；不創造FREE、換匯、額度總量或跨來源平均。

本機效能圖仍最多最近400筆，統計使用所選時間窗全部retained samples；`performanceSQL`只追加原有sample ID供收據定位，所有scope／取消排除／數值條件維持。既有完整請求歷史與匯出保留。圖表選取與歷史瀏覽不發送上游inference，也不啟動額外probe。

## 驗證

- 原44核心全保留；新增 `TrendDataTests` 五項與 `ObservationArchiveTests` 五項，覆蓋真時間間距／同時刻、零／未知／缺段、精確JSON匯出、鍵盤移動、真收據ID、全scope／filter／單位、205筆與原子拒絕／重複continuation。
- Go四項 `observation_archive_test.go`：237筆跨頁／六種foreign scope、翻頁中新插入／同timestamp、全量篩選／facets／半開範圍、invalid cursor／limit／scope、超過200次失敗後最後成功值保留，並驗只讀不發上游HTTP。全部Go測試與vet於本機通過；新HEAD仍須CI。
- NativeParity保留原25 smoke＋30 parity／110圖與全部guard；正式API寫205筆合成價格，再由正式ArchiveController查詢／翻頁，檢查精確scope、無重複、取消／重複點擊、原草稿／模型／對話不變與零額外fixture inference。實際NSView滑鼠事件＋first responder鍵盤事件，要求選取ID與真資料一致；效能點ID開啟相同scope收據，另驗JSON、空時間範圍。
- 新增四張實際production ModelDetailView：淺色價格圖選取、深色效能圖選取、深色完整歷史、淺色空時間範圍。共59 PNG／架構、118張，原25秒rich-stream／完整1249字SHA256／閱讀位置與120秒parity保持。

以上包含真正式caller與原生事件，但不等同全部控制的XCUI點擊或任何Kios外部認證實測。

## 其餘範圍

站方專用series_schema全量互動圖、外部多單位轉換、Provider完整inventory／多Key／簽到／stale與Research／Assistant／Reflection全部情境仍未宣稱完成。Android／VPS產品介面不改；新增只讀API可供後續共用。自動路由／fallback／Key輪替／對外Gateway仍future-only。
