# Mac 實際站點問題修復：HJMAPI 公開介面

2026-10-18 使用者提供三張原生App實圖，回報502、站方資料422、連線操作不清楚與效能空圖。這是MAC-PROVIDER-MOTION-011同片的必要修復，優先於繼續增加視覺效果；0.6.0 build11仍為候選。

## 實際核對

於2026-10-18 07:42 UTC匿名讀取使用者指定站點；沒有登入、使用私人Key、提交推論或切換到公告中的其他網域。各回應保留擷取時間與SHA256供本輪重播核對，不將真實站點當CI依賴。

| 站點公開介面 | 本輪結果 | 程式中的問題與修正 |
| --- | --- | --- |
| [狀態](https://hjmapi.923578.xyz/api/status) | HTTP200；版本v0.0.0；New API公開欄位 | 舊程式只接受v1.0.0-rc.25；公開端點改依實際回應schema驗證，不因build版本字串不同就整頁失敗 |
| [公告](https://hjmapi.923578.xyz/api/notice) | HTTP200；多行文字 | 原通用字串清理將換行公告丟棄，且僅保存present；允許普通換行／tab，仍拒絕秘密回顯及其他控制字元，保存可讀公告 |
| [價格](https://hjmapi.923578.xyz/api/pricing) | HTTP200；模型、群組、倍率及tiered公式 | HeaderNavModules空字串不等於禁止匿名查詢；公開讀取不攜帶推論Key。未驗證幣別／計價版本只呈現站方倍率與公式，不猜成USD或Free |
| [全站效能](https://hjmapi.923578.xyz/api/perf-metrics/summary?hours=168) | HTTP200；模型摘要與時間窗 | 保留summary與window_start／window_end，進入站方資料頁自動讀取 |
| [模型效能](https://hjmapi.923578.xyz/api/perf-metrics?model=glm-5.3-flash&hours=168) | HTTP200；model_name、series_schema、summary、series、groups | 原解析器丟失根層曲線與時間窗；現在保存、驗證並以原生圖表呈現 |

本輪核對的效能時間戳是Unix秒，success_rate是百分比，低於1的值仍是百分點。不可依數值大小猜比例、補不存在的樣本數、重算群組加權或把站方統計當成本機Key的上線率。模型回應需與明確查詢model_id相同；缺少所選群組不得借用其他群組。

## 失敗來源與畫面

原聊天引擎將上游401／429／5xx包成同一個本機502；現在固定格式只回傳錯誤代碼、實際上游HTTP狀態及操作，保留原請求收據。不傳上游body、秘密或原始動態錯誤文字。原生client從JSON與SSE保留原因，401／403、404／405、429、5xx、網路、逾時與格式不相容各有說明。

站方資料頁移除七顆無上下文按鈕、共用模型ID／時間欄位及預設JSON樹，改為自動載入的概況、公告、效能與計費規則；每項獨立loading／失敗／重試，失敗保留last-good。帳戶用量另選帳戶PAT，Key額度只選推論Key，不因公開查詢而送出私人憑證。未知版本的私人管理介面仍需相容性驗證，會說明限制，不能將它宣稱已驗證。

效能的來源控制固定在模型詳情上方；沒有本機樣本時先讀站方效能。已存在本機樣本時保留本機來源，提供可切換的站方曲線、參考群組與時間範圍。頁籤或來源切換會重建捲動區，避免沿用舊偏移導致上方操作被截掉。

Catalog連線選擇顯示精確Provider下的帳戶名稱，不顯示內部帳戶UUID；已啟用顯示「查看這個連線」且不重複寫入membership，隱藏／封存分別提供重新顯示／取消封存。狀態及操作名稱改為可讀文字。

## 驗證範圍

- Go正式HTTP路徑覆蓋v0公開相容、零憑證外送、多行公告、百分點／秒／完整曲線、錯模型與壞格式拒絕、last-good、真上游狀態與收據、敏感回應不外露。另重播本輪五份實際公開回應；不呼叫真實推論。
- 增加8項原生核心測試，保留原62項：公開request allowlist、Provider／Model／Role隔離、秒與百分點、0與missing、群組隔離、未知單位、固定錯誤與可讀帳戶／狀態操作。
- NativeParity經真TLS fixture和正式engine／Controller讀取v0形狀的資料，測公告502後保留last-good、重試／取消、零認證外送／零推論，以及站方曲線和已啟用Catalog連線。新增40–43四張原生畫面。保留原39張，合計43 parity＋25 smoke＝68圖／架構，共136圖；動畫14frame／架構仍保留。
- 8c1824候選的兩架構62核心及原60圖＋三張簽到圖曾執行，最後checkin invariant失敗：測試在變更requires_daily_checkin之前凍結完整模型，卻以之比較合法改變的offer metadata。現在先驗setup前後的scope／對話／草稿，再於setup後凍結完整模型；read／complete／retry流程仍要求完整值不變。沒有放寬原斷言、數量、時間上限或跳過新HEAD驗證。

本修復不代表已使用使用者的Key驗證聊天，也不能保證外部站點的502不再發生。完整Master Spec仍PARTIAL；驗收以修復HEAD的九項CI與雙架構實圖為準。
