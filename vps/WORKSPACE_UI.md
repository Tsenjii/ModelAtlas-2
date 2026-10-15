# VPS 工作區與頁面重整

使用者 2026-10-15 明確要求繼續改善外觀、頁面與功能。以同 SHA 六項全綠 `5522c7f` 為基線；此片不改已驗收 V1 的後端規則、schema 或 Android production；末尾必要 gate repair 僅修 instrumentation harness。

## 產品行為

- 仍為 Playground／Providers／Models 三個主分頁，設定右上；延用 teal，換成中性分層底色、清楚字級、48px 控制、原生 SVG，無遠端字型或圖片請求。
- Playground 分對話與創作／Assistant。連線選擇、聊天閱讀、composer 放在主區，對話工具／先前對話／設定收在原生 disclosure。Esc 先關工具選單；原 dialog Escape、busy／IME／scope guard、真取消、完整串流與歷史保持。空對話的建議只填草稿，不自動送出。
- Models 分我的模型／Catalog／全球研究／研究知識。預設清單直接顯示我的模型；Catalog 不擅自加入。名稱從既有 Catalog 依 Provider×model ID 取，建立一次 Map，避免每筆搜尋反覆全表掃描；模型原 ID 仍保留。
- 卡片使用角色正確的本機圖示、整卡詳情；只顯示已知同來源 metrics，My Uptime 帶樣本次數，Unknown 不造 0／Free。Provider Key 數僅說已啟用，不冒充已驗可用或健康。
- 快速最愛／FREE 篩選操作既有欄位；搜尋與完整 filter reset 接同一 renderer。Global Catalog 的顯示更多依真正匹配總數。選取模式才顯示 checkbox，至少兩選才能比較，選取後才出現批次工具列；Preview／confirm／Undo 與 server scope validation 不變。
- Provider 供應商清單與新增／匯入分開，主要新增按鈕直接開真 onboarding／Review。空清單與無搜尋結果分清，提供真加入／探索／清除操作。
- Settings 分偏好／研究服務／資料紀錄；背景與通知仍由原設定表單提交，defaults OFF。切換工作區不重新載入表單而丟未送出的值。返回按鈕回原主分頁。
- 手動跟隨系統／淺色／深色套用同一組色彩 tokens；localStorage 只存 `modelatlas.appearance`（非敏感值）。不可寫 storage 時仍可當次使用；沒有新 server setting，safe backup 不包含這個瀏覽器偏好。

## 無障礙與保留狀態

Tablist／tab／tabpanel 一一 aria-controls／labelledby，單一 selected／roving tabindex，左右／Home／End 可切；非活動 panel 真 hidden，表單 DOM 與訊息不重建。保留未發送 Playground／onboarding 草稿、目前 session 與上讀 anchor；切換不自動模型推論。Native dialog 保留焦點圈／Esc，原生表單 label 保留，SVG 不搶讀屏。375px、1280px、深淺色、200% 大字與 reduced-motion 需真 Browser 驗證，不能用 CSS 字串檢查冒充。

## 驗證與限制

`workspace_test.go` 啟動真 Handler／vault／SQLite，另啟空 DB；合成三個 Provider、三個我的模型與一個只在 Catalog 的候選，手動確認零 effective-charge 與合成 samples。正式 Chromium 操作空入口、tab keyboard、filters／Unknown、selection／clear、草稿／session、menu Escape、theme reload／system、設定分類／返回，最後斷言零 Provider HTTP、membership 不變。沒有攔截替換產品 API。

原十個 Browser workflows 保留全部 assertion，只按新的真入口操作；圖片／語音 bytes、tools、原生協定、Research、Assistant Review／Undo、Reflection、跨 origin、完整匯出、收據／重試、3000 deltas／閱讀位置／取消仍必須通過。服務 RAM 繼續原 expanded standalone workload，不能把 CSS 調整說成服務 RSS 降低。

最新候選必須 VPS test／browser／amd64／arm64 與 Android build／device-tests 六項同 SHA SUCCESS。Exact SHA／runs／jobs／實測數據與截圖 artifact 由 PR #20 最新 body 保存；未通過之前只稱候選。無 merge／無目標 VPS 部署證據。

第一候選 `6b2e74e`／VPS `38690238103` 真 Browser 有三個失敗：空 DB 的早退與選單遮住傳送（工具／自訂協定）。修正式 caller，選單有完成／focusout、依 nav 前的空間自動限高／翻向；不使用 force click。Go connector false-positive 以 `/v999/v1` 真 TLS 穩定重現，JSON 正確仍被全字串 `999` 誤殺；修正為更嚴格的 exact scope 查詢與 decoded rpd／tpd／origin／scope_kind。原失敗證據保留，新 HEAD 全六項 gate。

`87df330` 的 VPS 四項與十一 Browser 全部成功後，視覺 QA 補正桌面卡片等高頂齊、200% header wrap／底 nav 完整單字、初始成功 toast 不遮內容，Provider adapter 用可理解名稱；新增 brand／nav／desktop card 幾何斷言。為同片視覺收尾，最新 code 仍須完整六項 gate。

`f4b9194`／38692212749 原十 Browser 通過，新大字 nav 幾何斷言抓到單字仍超出 button；375px 的三個英文標籤採有界 responsive 字級與比例空間，仍保留完整單字、至少48px target，原幾何斷言不調低。

`0e7acf6` 的 VPS 四項／十一 Browser 全綠，53張實圖已檢視；Android同SHA build綠但 CredentialRollbackFlowTest.enablePathUnchanged 的15秒等待失敗。harness 原非Main caller與busy idle-loop 改成真Main及單純StateFlow等待，保持15秒與T1–T5全部斷言，補四輪Room／vault／投影對照與不洩漏secret的phase診斷。Android production不變；不重跑冒充、最新HEAD仍須完整六項。
