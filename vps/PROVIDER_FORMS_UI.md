# VPS Provider 表單與保存回饋

2026-10-15 使用者同意繼續優化。基線 `03307f1e9046f45af2ab52458a8762cd9f987079`：VPS `38735387218` 四項、Android `38735387179` build/device 同 SHA SUCCESS，十三個 Browser 與 Catalog 實圖已驗收。main 重新確認為 `5009e0c8`／`38408176782` SUCCESS。本片只改 VPS Provider UI、真 Browser 與文件，不改 Android／schema／API 語意。

## 保存時的操作

- 新增帳戶／保存 Key／啟停 Key／新增端點／更新與還原 Key／進階設定，使用原正式 API，僅原地更新清單、計數與選項；六個表單不重建，跨分區草稿、展開狀態與選取保留。設定原始資料在保存後更新，未送出的設定與編輯草稿保留。
- 成功後只清已送出的帳戶名稱，或該表單的密碼／額外認證 JSON；失敗保留原稿，讓使用者自行修正或重試。Key 原 secret／版本／epoch 規則完全沿用後端，沒有自動推論或同步。
- 每個表單內有 `role=status`／`role=alert`。錯誤捲到可見處，不依賴 dialog 外 toast。JSON 格式錯誤使用固定繁中提示，不顯示原生 parser 可能帶出的認證片段。提交已成功但後續刷新失敗時，訊息明說已保存。
- 處理中的表單停用自身控制項並標示 `aria-busy`，WeakSet 防止程式化 `requestSubmit()` 重複送出。其他表單仍能編輯。Key 啟停暫時鎖定同清單，回饋保存在不會被替換的 section。
- 更新／還原 Key 以 Provider×Account×Credential 穩定識別值選取，顯示 Provider／帳戶／Key／Group，避免同名或同 ID Key 選錯帳戶。新增帳戶後更新選項但保留原選擇。
- 帳戶／認證／用途／rotation select 有固定 `aria-label`，與可見欄名一致；不讓包住控制項的 label 連帶加入所有 option 文字。參考原生 label／aria-label 對應的 [Playwright 官方定位規則](https://playwright.dev/docs/locators#locate-by-label)。
- 所有回饋及局部更新都檢查 dialog generation；晚完成的真 commit 仍刷新全域資料，但不重開舊 Provider、不關閉或污染新視窗。Provider 編輯的 Review／confirm／Undo 沿用原路徑。

## 驗證

新增第十四個 `TestBrowserProviderFormsWorkflow`／`e2e/provider-forms.cjs`：真 Handler／SQLite／vault，兩個帳戶刻意使用同樣的 Key ID 與 label。外層只在真 API 前產生一次503，或在真 SQLite commit 後延遲其原回應，不替換產品成功資料。

流程檢查六個原始 form node／展開狀態與其他草稿跨操作保留、原帳戶選取、帳戶與 Key 503 手動重試、非法 auth/config JSON 零 POST 且沒有認證片段、處理中重複 submit 零額外寫入與其他欄位可編輯、只清該表單敏感欄位、B帳戶 rotate 不改 A 的 ciphertext／版本、保存端點即更新清單、晚完成設定不能重開已換到的 Q。SQLite 最後需三帳戶／三Key／一membership／保留一telemetry、A零新增版本與B兩版本、零 Provider HTTP。375／1280px、深淺色／200% 實圖檢查 page/dialog overflow 與 dt/dd 文字 Range 不相交。原十三 Browser 的全部斷言保留。

最新 HEAD 仍須 Go race／vet、VPS 四項與 Android 兩項同 SHA 全成功及實圖 review 才驗收。exact SHA／runs／jobs／artifact／expanded standalone RAM 以 PR #20 最新 body 為準；不借基線綠燈或其 RAM。測試全部是合成資料，不是外部 Provider 可用性證明。沒有 merge／目標 VPS 部署。

首輪 `cdc7b0b`／`38750457348` 原十三Browser成功，新流程的label locator未命中；診斷 `705883d`／`38751411611`保留完整精確陣列比對，actual=[]而實圖有選項，定位至select的label text。修production固定可存取名稱，維持完整選項、scope與原十三斷言，不增加timeout／force click。最新候選仍完整重新驗收。

`db21a33`／VPS `38751952139` 新Provider流程4.87秒全過，舊workspace則抓到慢`loadStatus`把未保存UTC覆成Asia/Taipei。這是本片gate的必要回歸修復：狀態載入不再於非同步結束後重填form，正式`refresh`只更新未編輯欄位；保存以revision保護等待期間的新稿。workspace加入真status response與真settings commit後延遲，固定檢查跨tab／重開／晚回應仍保留、下一稿Asia/Tokyo不被保存完成覆蓋、DB僅當次UTC／一次POST，背景與通知仍OFF。原流程斷言與timeout不變，最新HEAD仍完整六項及實圖驗收。
