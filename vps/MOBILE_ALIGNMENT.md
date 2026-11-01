# VPS 手機版介面對齊

2026-10-19，VPS-MOBILE-ALIGN-004。依使用者「再優化手機介面，所有的介面都要對齊」，在既有 VPS Mac 風格上統一手機版，不改 Mac／Android 的 UI 進度。

## 正式接線

`appCSS` 最後載入 `ui_css_mobile_alignment.go`。959px 以下共用頁面外距 16px、卡片／彈窗內距 16px、欄位間距 16px、欄間距 12px、按鈕組間距 8px 與卡片圓角 12px；頁面標題、搜尋、排序與列表沿用同一條左右對齊線。寬度較大的平板彈窗最多 720px，置中並維持相同內距。

Providers、Models、Settings 及詳情分頁皆為單列橫向捲動。`workspace`／`detailTabs` 共用 `revealWorkspaceTab`；鍵盤 Home／End／方向鍵切換及視窗尺寸改變後，選中分頁仍可見，不改資料範圍或草稿擁有者。

搜尋與排序在窄版分列；設定、研究、Assistant、Catalog、帳戶／Key、額度、圖表與比較卡片共用內距。手機標頭採 48px 圖示按鈕，保留原有可存取名稱。輸入欄至少 16px 字級，聊天輸入區與互動控制至少 48px；200% 文字可換行，只有分頁與需保留單行的選模名稱使用局部捲動／省略。

五個原生 dialog（詳情、選模、Provider 編輯、收據、確認新增）共用外框、標題列與內容內距。詳情的內容區可獨立捲動；560px 以下短視窗改為單一捲動區與置頂標題，讓橫向／縮短的可視區仍可到達表單底部。確認及保存按鈕留在可操作的內容區，底部導覽保留安全區。選模標題與完成按鈕同列，說明另佔整列；重開時回到搜尋起點，避免保留上次捲到底部的位置。

## 驗證與界線

`mobile_alignment_test.go`／`e2e/mobile-alignment.cjs` 使用正式 Handler、SQLite、vault 及 TLS 上游：

- 320×740、375×812、430×932、768×1024、959×900、375×900／200%、320×740／200%、812×375、812×375／200%，含淺／深色，另加直向 top=44／bottom=34 與橫向 left=right=44／bottom=21 的安全區覆寫，共十一種情境。
- 全部主頁；Providers／Models／Settings 各分頁；Catalog、研究知識、Assistant；帳戶／Key 編輯；真 onboarding 預覽；真兩連線比較及第二層收據。
- 頁面左右邊緣、彈窗內容邊緣與內距、無整頁橫向溢出、標題不壓關閉、48px 控制、選中分頁可見、保存／確認／聊天按鈕可到達。
- 分頁與編輯視窗切換保留未送出草稿；不確認新增、不刪成員或歷史。只有明確按下「開始比較」發送兩次推論；預覽只讀一次 Catalog，站方分頁保留原本公開唯讀資料取得。

原十八個 Browser 流程、race／vet、雙 Linux binary 及 standalone expanded workload 均保留，新 HEAD 必須重新通過六項同 SHA CI。此處為 Chromium 的手機觸控、安全區覆寫、尺寸與大字驗證；未宣稱實體 iPhone／Android 或軟體鍵盤裝置驗收，也未 merge 或操作遠端 VPS 部署。最新 SHA、實圖與資源結果以 PR #20 為準，完整 Master Spec 仍 PARTIAL。
