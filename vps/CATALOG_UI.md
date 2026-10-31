# Catalog 探索與連線加入

本片延續原生 Go／HTML／CSS／JavaScript，沿用 teal、三主導航、48px 操作區與深淺色；不新增 production dependency、migration 或背景推論。Catalog 仍與 My Models 分離。

## 操作與資料

- 搜尋名稱、ID、Canonical ID、別名、Vendor 與供應商名稱，採既有正規化與有界近似搜尋。索引、排序與 membership 索引每次正式 refresh 更新；輸入不發 API，不重排全部資料。單次呈現100列，有正確符合總數與顯示更多；改篩選回到100列。
- 供應商、型錄類別、型錄標示能力與加入狀態各自篩選；能力不冒充 Key 認證。加入計數來自真 My Models，含 hidden／archived，但不表示可用或免費。清除 Catalog 不改我的模型篩選、聊天草稿或手動表單。
- 加入前明確選 Provider×Account×Credential×Group×Model×Endpoint。Key ID 以三元組穩定辨識，刷新不因 metadata JSON 改變而跳回第一把。端點必須同 Provider、同 Key base URL origin 且為推論 operation；保留 server 的 immutable endpoint／origin／epoch 防線。
- 新增可展開的完整連線摘要，帳戶／Key／Group／用途與完整URL使用既有可換行欄位，避免200%大字的原生select截字隱藏scope。缺 Key 或匹配端點時停用加入，有直達帳戶／Key 或端點設定的入口。已加入的**同一完整 scope**改為開啟真模型詳情；其他 Key 的 membership 不阻止明確加入新連線。封存／隱藏不自動恢復，不重建或刪歷史。
- 手動加入收在原生 disclosure，提供真端點選擇；在 `/models` 寫入前驗完整選擇，再明確 `/my-models`。已有 scope 不重複 POST；跨刷新保留選擇與輸入。若型錄已存而 membership 失敗，如實顯示部分完成。

## 非同步與錯誤

加入失敗在 dialog 內頂端以 role=alert 顯示並捲到可見處，保留 Key／endpoint 供手動重試；處理中鎖定兩個選擇，避免加入與畫面顯示 scope 分離。同一提交防重入。成功提交先記錄已加入狀態；清單重讀失敗不誤說寫入失敗。只有原 dialog generation 仍開啟時才可關閉，晚回應不關閉另一個詳情。未建立自動重試、路由或 fallback。

## 真實驗證

第十三個 `TestBrowserCatalogExperienceWorkflow`／`e2e/catalog-experience.cjs` 使用真 Handler、vault、SQLite、兩個 TLS origins、108個Catalog候選、兩個原有members（含封存）及一筆歷史。只以外層HTTP故障／commit後延遲回應模擬中斷；不替換前端API或製造成功回應。

驗證100／108分頁、別名／canonical／近似／vendor／Provider及metadata篩選、跨Provider相同model ID不借membership、disabled／missing Key、無匹配端點的真設定入口、B端點真建立、503可見重試、B實際commit後換dialog再放回應、A/B分別加入、手動B端點及草稿保留、重複／封存只讀、375／1280px與200%深淺色截圖。

最後SQL與HTTP counters要求：5個members、原封存與telemetry完整、僅3個新enrichment、兩個B-origin/group範圍、4次membership POST（含一次503）、1次手動Catalog POST、1次明確endpoint POST、零Provider HTTP／inference。原12個Browser流程保留斷言，媒體測試只依新繁中文案選擇同一operation／URL。

每個候選仍須同SHA的六項CI（VPS test／browser／amd64／arm64、Android build／device-tests）全綠並人工檢查Actions實圖才驗收。確切SHA、runs、RAM與截圖以PR #20本片驗收為準；未部署。
