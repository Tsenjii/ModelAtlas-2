# 三平台批次模型實測

此片為 MODEL-BATCH-020 候選。正式來源與完整 CI 結果以 PR #20 為準，不能把聚焦測試當作整版完成。

- VPS：Models → 選取 → 勾選 1–100 個已啟用文字對話連線 → 測試勾選的模型 → 開始。即時顯示逐筆結果、首字延遲／TPS／總延遲與既有請求詳情，可停止、重開同一頁面的進度及匯出 JSON。
- Mac：模型 → 選取 → 批次管理 → 測試勾選的模型。不同模型可一起測試，每筆仍固定完整 scope。原生結果與請求詳情可檢視／匯出。
- Android：Provider Detail → Models，在上方明確選定連線後勾選模型 → 開始批次測試。每筆沿用原本 VM／Room／安全憑證／InferenceEngine，保持原單筆驗證與取消路徑，不從另一把 Key 借用結果。

每個連線只做正常的最小手動測試，不是最大 RPM 壓力測試。VPS 每筆 16 個最大輸出 token；Android 原單筆測試的 32-token 上限保持。結果寫入原 `MANUAL_TEST` telemetry 與 My Uptime；使用者取消仍排除分母，未知數值不變成零。

遇 429／402 停止後續項目，未執行的模型明示未測試。取消或連線中斷不自動續跑／重送；已發出的真請求及取消保留於歷史。網頁關閉或服務重啟不自動恢復這項手動批次；它與明確啟用的背景研究排程不同。頁面內重開進度不會新增請求。

VPS 的 API 為 `POST /api/v1/models/test-batch`（id 與明確 selections，NDJSON 的 start/result/done），以及 `POST /api/v1/models/test-batch/cancel`。整批先拒絕重複／不可用／非文字 scope，送每筆前再重查可用性；同時最多兩個手動批次，仍受原推論 flight 限制。Mac 沿原單筆 API 逐一呼叫；Android 沿原引擎，兩者保留各自原生生命週期，不把網頁能力當作原生驗收。

聚焦檢查已涵蓋真 TLS、精確連線／來源／收據、無效批次零請求、限流停止、取消 active socket、沒有重送、匯出 bytes、320／375／1280px 與 200%。第一輪新增取消 fixture 未讀完 request body，導致測試 TLS server 的 Close 等待；修正 fixture 先消費 body，未放寬產品取消或原 timeout。第一輪 Browser 的關閉按鈕選取忽略既有 aria-label，改用原 `detail-close`；原功能斷言保留。新增原生驗證另列於同來源 CI，尚未跑過前不稱已完成。

來源 `8b90eb29df678dc225a72037ce69706c78d8a063` 的 VPS 六項、Mac 三項、Android 兩項必要 CI 均成功，Mac 實際原生畫面與兩次精確連線收據亦核對；產物稽核 `41327805052` 仍拒絕，因 Browser 驗證器沿用原 46 的寫死數量，新增流程的完整 47 項不能通過該舊條件。修正後保留已驗收的 46 個精確名稱，再要求唯一新增批次流程；缺少、重複及同數量替換均拒絕。這是檢查器修正，不調低產品測試、原 workload 或 timeout；修後來源仍須自己的完整十一項與產物稽核。
