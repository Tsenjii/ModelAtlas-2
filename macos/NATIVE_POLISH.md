# Mac 原生視覺與並列比較：MAC-NATIVE-009

0.4.0（build9）候選。基線0.3.0 `8eb68087fae76bbf6e04875fd14024e93d4ab510`：Mac39058446721／VPS39058446728／Android39058446846九項同SHA SUCCESS，兩架構各38核心與50張PNG，100圖已檢視並交付。新HEAD須重新九項及110張PNG驗收，不借用基線結果。

## 實際參考

本輪讀取並檢視官方圖片；參考資訊層級與互動，不把圖片、品牌標誌或程式碼搬進安裝檔。

| 官方來源 | 實際讀取 | 採用的原則 |
|---|---|---|
| [Magpie](https://github.com/yetone/magpie) | `site/public/img/agents-light.png`、`picker-light.png`、`usage-light.png` | 中性色、細分隔線、整齊數值欄位、搜尋與選取層級 |
| [Bear](https://bear.app/) | `images/home/hero_mac.jpg` | 側欄與內容對比、內容字級優先、工具列分開、減少裝飾 |
| [Raycast](https://www.raycast.com/)／[Window & Search Bar](https://developers.raycast.com/api-reference/window-and-search-bar) | 官方網頁與鍵盤／搜尋互動文件 | 少量主要操作、清楚鍵盤入口；不宣稱已安裝或操作其App |
| [Apple Buttons](https://developer.apple.com/design/human-interface-guidelines/buttons)／[Color](https://developer.apple.com/design/human-interface-guidelines/color) | 官方HIG與SwiftUI色彩文件 | 主次操作、深淺色與語意、色彩用於重要資訊 |

最低部署仍macOS12，不加入macOS26-only API。

## 外觀與互動

- 使用者明確更新色彩要求（D-007）：Mac預設石墨灰，設定可選跟隨macOS／鳶尾紫／靛藍。主操作、傳送與原生控制接同一非敏感UserDefaults偏好；深色石墨主要按鈕用深色文字保持對比，不改引擎設定／背景工作。
- 共用卡片去除陰影；提高清單主要字級，頁籤改為簡潔文字導覽；側欄保留三工作區，去除重複副標與彩色裝飾。
- 額度／觀測類型與來源先顯示可讀名稱，完整source ID、scope與raw evidence仍可展開。Unknown／單位／來源分離不變。
- 模型選取列固定於底部，長清單也能操作；比較與批次管理分開。隱藏／封存／恢復仍走原preview→review→confirm→undo。

## 正式並列比較

Models→選取→勾選同一Canonical的2–4個可用文字連線→並列比較→短提示詞→明確開始。

`ComparisonController`是View真正使用的runner，送出既有 `/models/compare`；每個連線各一次正常inference，沿用32-output-token上限及telemetry／receipt，沒有fallback、額外billing查詢或同步probe。提交時凍結完整連線；pending立即鎖重複提交，取消／關閉取消URLSession Task與舊revision，已完成請求保留歷史。取消或失敗後的last-good結果明示為上次完成比較。

結果依Provider×Account×Credential×Model×Group×Endpoint對齊，少列／重複／foreign scope拒絕展示，不按模型名或列表位置借值。單次實測不稱長期quality／uptime排名。

原生並列成功／失敗與HTTP、TTFT／TPS／latency、真Token用量、當次PriceState、站方回報扣款原單位、effective／nominal估算；未知為—、顯式0保留，非串流TTFT不適用。精確金額原文保留，credits不換USD。額度before／after依來源／完整scope／unit／version等分池，不推算實際扣款；外來帳戶／Key／group／endpoint排除，合法廣scope保留。收據可直接開啟／下載，比較結果可匯出本機JSON。

## 驗證與界線

保留38核心／50圖每架構；新增6項LiveComparisonTests驗請求資格／UTF8界線、完整scope／順序、foreign／duplicate／missing、unknown／zero／failure／非串流、精確金額／多單位、獨立quota快照。

NativeParity使用正式metadata／ComparisonController→引擎→隔離TLS fixture，兩帳戶共用Key ID，驗只新增2筆COMPARE_LIVE telemetry、精確收據scope、另一帳戶價格未知、重複開始僅一次、執行前取消不發HTTP／新增telemetry，且模型／草稿／對話不變。新增比較準備／深色結果／真收據／紫色設定／石墨模型5圖。預期每架構44核心、25smoke＋30parity，共110圖；原25s串流／120s parity、全文hash、全部既有斷言不變。

這是正式caller／原生視窗及隔離fixture驗證，不等於全部新控制都經XCUI點擊，也不是Kios等外部供應商認證實測。CI與實圖未全通過前仍候選。

完整Master Spec仍PARTIAL：Provider全量inventory／批量多Key／簽到／stale情境、完整Research／Assistant／Reflection、200筆以上原生觀測分頁、站方series_schema互動圖及外部多單位轉換仍須後續。Android PLAY-004／P8–P10未完成狀態保留。

3976a1d首候選在Mac Universal編譯時發現新增ComparisonResult與Foundation既有型別同名；改為LiveComparisonItem，未刪改任何功能或測試。同步外觀source review補系統強調色的相對亮度文字對比，避免亮色按鈕固定白字。新HEAD重新九項／110圖，前候選不能交付。
