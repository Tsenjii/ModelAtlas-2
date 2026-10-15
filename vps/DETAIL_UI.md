# VPS 模型與供應商詳情

2026-10-15 使用者授權繼續改善功能與介面。本片基線 `c609cf2d65d7c598b5806dd2011ca8e75a45b819` 已由 VPS `38695626679` 四項與 Android `38695626675` build/device 同 SHA SUCCESS 驗證；main 重新確認仍 `5009e0c8`，`38408176782` SUCCESS。此片僅改 VPS UI／真 Browser 測試與文件，不改 Android 或資料 schema。

## 操作與資料

- 模型詳情分「概覽／實測紀錄／價格與來源／設定」。主要使用／測試／同步入口保留，最愛、追蹤及請求紀錄在概覽，分類／別名／備註／能力與可恢復的隱藏封存在設定。以 Catalog 同 Provider×model ID 的顯示名稱呈現，原 ID、Key／Account／Group／Endpoint 仍可辨識。
- Provider 分「概覽／帳戶與 Key／端點／設定」。概覽直接進入該 Provider 的已加入模型，不把 Catalog 候選當成收藏；Key／PAT／多 origin／新增帳戶、immutable endpoints／CUSTOM、進階設定與 Provider Review 全保留。端點新增後原地更新已保存清單，不清其他草稿；Key rotate 後刷新投影以遵守 credential epoch。
- 原生 tablist 單選與 roving tabindex，左右／Home／End 切換，非活動 panel hidden；切換不重新建立表單。Native dialog 有可讀名稱、48px 關閉與焦點圈；Teal、三主導航、右上設定不變。沒有外部圖片／字型／新 production dependencies。
- 實測顯示同 scope 的樣本數、時間窗、取消排除、少量樣本提示、TTFT／TPS／latency p50，分位數及最近60秒觀測在展開區；後者不冒充官方限額。不同模型／來源不借用資料。
- nominal 與已確認 effective 費率各有可讀欄位、單位、來源與時間，精確 decimal 字串不改成浮點貨幣；缺資料明示。零固定費只在 `fixed_absent` 明確成立時顯示。last-good／latest／站方／帳戶／外部／resource pools 各自保留來源及可展開原始資料，多個 pool 不平均。

## 非同步正確性

- 初次詳情載入在每個分區有 loading；讀取失敗有明確 error 與真重試，不留下空白視窗。
- 切換效能時間窗只讀取並更新實測區，notes／metadata／capabilities 草稿和自訂日期不重設。舊請求晚返回時，需同 dialog generation＋最新讀取序號才能更新。讀取失敗保留上次有效結果並明說，使用者可重試。
- 自訂範圍驗證完整時間、開始早於結束、結束不晚於現在；非法範圍在 HTTP 前拒絕，server 原驗證仍保留。
- API mutation 照常只提交原 selection；完成後只在原詳情仍開啟且 generation 相同時重新顯示。使用者已關閉或換模型，舊 mutation／preview 不搶回或關閉新視窗。沒有自動推論／重試／路由。

## 驗證與限制

`detail_workspace_test.go` 啟動真 Handler／vault／SQLite、兩個已加入模型及一個 Catalog-only 候選。以測試外層故障／延遲包裝真 API response，**不替換產品 API 或預造成功 JSON**：首讀503後真重試、1h真結果延遲到7d之後、lifecycle實際commit後延遲回應，驗新內容不被舊結果覆蓋。

第十二個 `e2e/detail-workspace.cjs` 同時驗證：分區／鍵盤、未儲存 notes／alias／capability 跨時間窗保留、非法日期零讀取、真自訂查詢、3有效＋1取消樣本的66.7%／50.0%、另模型無借用樣本、nominal/effective零價格與failed不抹last-good、分類／能力真保存、Key真停用／啟用、Provider只列My Models、375／1280px、深淺色／200%大字／detail內部與標題幾何。最後斷言零Provider HTTP、兩個members及四筆telemetry完整保留。測試輸入皆合成資料，不拿fixture價格或健康當真Provider事實。

原十一 Browser workflows 保留原斷言，只改真分區入口。Go race／vet／JS syntax 本機已過；每個新 HEAD 仍必須 VPS test／browser／amd64／arm64 及 Android build／device-tests 六項同 SHA SUCCESS，並檢查實際截圖。Exact SHA／runs／jobs／artifacts／standalone RAM 以 PR #20 最新 body 為準；未完成之前維持候選，不挪用基線的綠燈或RAM。原 expanded workload 不縮減，沒有新 Kilo 消耗、merge 或目標 VPS 部署證據。
