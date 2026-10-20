# Mac 站方接入修復、帳戶簽到與動態回饋：MAC-PROVIDER-MOTION-011

使用者安裝後回報三張實圖問題，優先修正站方422／502原因、公開資料自動讀取、實際站方曲線與Catalog連線操作。詳 [實際站點核對](../docs/HJMAPI_COMPATIBILITY.md)。保留本片簽到與動畫改動；最終驗收提高為70核心／68靜態圖每架構，共136靜態圖與28動畫frame，所有原斷言／時間上限不減。

0.6.0（build 11）候選。使用者已可安裝0.5.0，這一片繼續補完整管理功能與原生動畫。已驗收基線為 `3529736739ba219fe37073125688abb039fdc02c`／tree `b38e7a2eee5b0e3b4af556a7e15c08b7892819a3`：Mac39105128601／VPS39105128591／Android39105128634九項SUCCESS，雙架構各54核心／60圖，120張實圖已檢視。新HEAD須重新九項全綠、70核心／68圖每架構、136圖與28張動畫frame review後才可交付；不借基線綠燈。完整Master Spec仍PARTIAL。

## 帳戶與每日簽到

Providers增加「待簽到」篩選與每個供應商的待簽到帳戶數；詳情先顯示概覽，逐帳戶列啟用／全部Key與模型連線數，可進入既有完整Key管理。已設定每日簽到的供應商另顯示逐帳戶狀態、日期／時區、外部簽到頁與手動完成操作。完成A帳戶不代表B帳戶或另一個Provider的同ID帳戶完成，也不宣稱網站已驗證、領到額度或模型免費。

新 `GET /checkins/accounts?timezone=...` 回傳當地日期及所有帳戶狀態；`POST /checkins/accounts` 必填provider／account／date／timezone，嚴格驗證真帳戶與已設定簽到。日期已切換回409，重新載入後才可再次操作。同一帳戶／日期重複提交保留第一份手動收據。接受請求時以同一時間點決定當地日期與completed_at，避免午夜前後的不一致。

SQLite由v16增加到v17，只新增 `account_checkins` 與索引、複合FK；原provider級 `checkins`、對話、加密Key、價格／觀測與設定保留。舊provider級紀錄仍可讀，但不能推定其下任何帳戶已完成。現有VPS provider級API仍保留；原生Mac採新帳戶API。背景待簽到通知按provider／account／date查詢及去重，資料庫失敗不產生假待簽到事件。

`AccountCheckinController`以revision拒絕取消後／舊讀取結果，錯誤保留最後成功資料並可重試；過期日期／時區資料不能冒充今天。完成收據只更新同帳戶，並保留其他同時完成的帳戶。啟動／重新整理、回到App、換日與系統時區變更會更新狀態；日期依這台Mac的實際時區，不用24小時計時器冒充午夜。沒有自動登入、代簽到或額外推論。

## 原生動態

沿用既有SwiftUI／AppKit、石墨／可選accent。短過場用於使用者明確操作：側欄開關220ms、導航選取180ms、面板140ms、按壓／hover／圖表固定選取回饋120ms。可被下一次操作中斷，不延後資料請求、鍵盤與按鈕操作；串流文字、價格／效能數值與背景資料更新不作插值動畫。

設定的「減少介面動態效果」立即保存本機偏好；有效策略是macOS Reduce Motion **或** 本機減少偏好任一啟用即停止自訂動態，使用者不能用本機開關抵銷系統偏好。預設尊重系統。仍使用macOS12既有API，不加入新動畫依賴或WebView。

參考 [Apple Motion HIG](https://developer.apple.com/design/human-interface-guidelines/motion) 與 [SwiftUI accessibilityReduceMotion](https://developer.apple.com/documentation/swiftui/environmentvalues/accessibilityreducemotion)，以清楚狀態與方向為目的。

## 驗證與更新

- 原54核心與120圖保留；新增8項AccountCheckinTests：跨Provider同帳戶ID、未知／非必要、嚴格型別與source、精確提交、拒絕foreign／錯日收據、並行帳戶不互抹、午夜／DST、空結果與查詢契約。
- 六項Go HTTP／SQLite測試：A/B與跨Provider、legacy不借用、重複提交／零上游HTTP、缺scope／壞日期／timezone／401、真v16→17升級保留加密Key／對話／偏好／精確價格歷史、通知精確帳戶與去重、桌面依系統日期而VPS維持設定時區。
- NativeParity用正式provider config、legacy與新帳戶API、真Controller驗證部分／全部完成、401後last-good／重試與取消；不更動原模型／對話／草稿或fixture推論計數。新增待簽到清單、帳戶概覽、減少動態設定、全部完成空篩選四張production畫面。加上站方修復四張，總68圖／架構、136張。
- 實際主視窗各擷取側欄關閉／開啟6張連續frame，記錄實際時間與SHA256；系統未減少動態時每段須至少3種不同frame。減少動態後兩張間隔160ms的穩定畫面必須相同。共14frame／架構，不以靜態示意圖假裝動畫，也不等同60fps影片或全XCUI覆蓋。
- 原25秒rich-stream、完整1249字SHA256／閱讀位置、120秒parity與全部guard不減。新HEAD的同一Universal artifact須在arm64／Intel驗證。

更新仍為手動：⌘Q完全退出→下載新版DMG→把ModelAtlas.app拖到應用程式並取代→重新開啟。資料與Keychain沿用；本次啟動有加法式資料庫升級，舊版引擎會拒絕較新的schema，不能把安裝舊App當作資料回復方式。完整可攜設定匯出仍不等於跨機完整加密備份。最低macOS12，CI仍是macOS15兩架構；ad-hoc簽章尚未Developer ID／公證。

## 尚未全部完成

本片完成不等於多Key availability／quota／stale每個管理情境或完整Research／Assistant／Reflection驗收。已核對的New API公開series_schema接入原生圖表，其他站方schema與外部多單位轉換仍保留；Android PLAY-004／P8–P10未完成狀態不改。routing／fallback／自動Key輪替／Gateway仍future-only。不merge、不改base、不部署。
