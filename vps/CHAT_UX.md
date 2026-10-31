# 聊天串流與閱讀體驗優化

2026-10-15，延續 `5c61605` 的六項同 SHA 全綠基線。本片以既有 Go／SQLite／原生 DOM UI 實作，沒有新增 production dependency、背景模型呼叫或資料庫 migration。

## 參考與具體問題

- [assistant-ui Thread](https://www.assistant-ui.com/docs/vue/primitives/thread)：閱讀者在底部時跟隨新內容，往上閱讀時暫停；提供明確回到底部的操作。參考互動行為，以本專案原生 DOM 撰寫實作。
- [Open WebUI Performance & RAM](https://docs.openwebui.com/troubleshooting/performance/) 的 streamed response assembly／chunk batching：減少每個片段對完整內容的反覆複製與處理。此處對應的是瀏覽器 DOM 更新，不能把其他專案的效能數字當成 ModelAtlas 的結果。
- [MDN requestAnimationFrame](https://developer.mozilla.org/en-US/docs/Web/API/Window/requestAnimationFrame)／[CharacterData.appendData](https://developer.mozilla.org/en-US/docs/Web/API/CharacterData/appendData)：使用原生瀏覽器排程與文字節點追加；背景頁面 rAF 暫停時由 timer 或終止 flush 收尾。

以上官方資料於 2026-10-15 查閱。沒有複製第三方程式碼或引入其框架。

舊 UI 每個 delta 讀取再寫回整段 `textContent`，並立即讀取 scrollHeight、寫入 scrollTop；長回應會反覆重建文字與 layout，也會把閱讀者拉回最新位置。完整頁面 refresh 即使訊息未變，仍重建已載入的較早訊息。

## 正式行為

- 串流內容累積到下一個 animation frame 合併追加，只有變動部分寫入文字節點；80ms timer 作排程後備，單批達32K字元即 flush。完成／取消／錯誤都同步 flush，完整內容仍經原有安全 Markdown 呈現／複製與持久化。
- 跟隨底部與往上閱讀分開管理；wheel／touch／鍵盤向上閱讀、展開訊息 details 會暫停跟隨。新回應以「回到最新」及提示點呈現；使用者明確回到最新後恢復。聊天內容區可鍵盤聚焦，串流不逐 token 向螢幕閱讀器播報。
- 初次載入／切換對話顯示最新訊息；較早訊息保留位置。相同 session 的最新訊息 ID／request link／outcome 未變時保留既有 DOM，因此 metadata refresh 不會移除已載入的較早頁面。原訊息本身是不可變紀錄；停止／新增仍重新讀取正式歷史。
- 真請求開始後可在訊息欄草擬下一則，生成中仍只有一個手動請求；不重複送出、不改模型／session，也不把草稿自動送給模型。草稿只在目前頁面記憶體，尚未提供跨重新整理的草稿保存。
- `Ctrl／⌘ + Enter` 在訊息欄傳送；普通 Enter／Shift+Enter 換行。`Ctrl／⌘ + K` 搜尋對話；`Ctrl／⌘ + Shift + O` 開新對話。`Esc` 停止目前對話回應，modal 開啟時先交給 modal 關閉。IME composition／重複 keydown／生成中切換均有 guard。
- 錯誤退出中止目前 reader 的 fetch，解除 lock，沿用真 cancel／telemetry／scope；取消仍不計入 My Uptime。費用、能力、認證與資料永久保留規則不變。

## 驗證與截圖

`chat_experience_test.go`／`e2e/chat-experience.cjs` 使用正式 Go Handler、SQLite、真 TLS 上游及原生 Chromium：

1. 105 則歷史、載入較早頁面；metadata refresh 後驗 DOM identity 和閱讀位置。
2. 三階段共3,000個 Provider delta；第一個2,000 delta burst 量測 DOM writes，要求低於500，完整文字替換為0；不以不穩定的單次耗時作速度保證。
3. 生成中往上讀，再接收1,000 delta，驗閱讀位置不被拉走；明確回最新、200%深色及手機／桌面截圖。
4. 草稿／完整 UTF-8 clipboard／107則完整 JSON export 比對原文，證明 batching 沒有裁掉文字。
5. IME、普通換行、Ctrl／Meta 快捷鍵、busy guard、modal Escape 與實際上游 socket cancel；只允許2次模型HTTP，取消獨立保留為 CANCELLED。

DOM 指標寫到 Browser artifact 的 `chat-stream-metrics.json`；VPS RAM 仍由既有完整 standalone workload 檢查，不能把瀏覽器 DOM writes 減少冒充服務 RSS 下降。

上一批35張功能截圖來自 `5c61605`／VPS run `38680781659`／Browser job `116244605214`，均為合成測試資料。本片新增聊天閱讀／完成畫面與 DOM 指標，依本片正式 Browser artifact 補圖並標明 SHA。本片須同 SHA 六項 CI 全部成功才驗收，確切 SHA／run／job／指標以 PR #20 最新 checks／body 為準，不借用舊截圖或舊 CI；未 merge／未部署。


## Android gate 的必要初始化修復

`979a8a6` 的 VPS 四項及十個 Browser workflows 全過，第一批2,000 Provider deltas實測9次DOM寫入／0次全文替換，3,000 deltas的18,250字元／107則完整匯出正確；服務擴充 RAM profile峰值393,846,784 bytes（375.60 MiB）。這是該code SHA的測量，不外推到未測版本。

Android build通過，但 run `38685239940`／job `116258469234` 的既有 h4 測試暴露真初始化競態：membership collector 在 `Main.immediate` 同步執行 `requestMyModelRefresh`，讀取後方尚未初始化的 `newApiCertifiedOperations` Set。這次必要修復將兩個 observers init 移到所有 instance fields後，原內容與相對順序逐位元核對相同，保留初始 emission／session預算／cancellation。沒有以重跑、delay、drop或吞錯掩蓋。

新增 h5 真裝置情境：已存 membership及certified endpoint，四次在Main建構／清理VM，驗精確operation、無charge、無inference／membership新增。既有h4及其他斷言不改。修復僅在VPS分支的必要相容性範圍，沒有改Android main或宣稱Android產品phase完成；修復後新HEAD另跑完整六項gate。
