# 研究共用額度池（RESEARCH-POOL-015H）

本片承接015G已驗來源abcffa0；完整下一版與WISH-CRAWL-001仍PARTIAL。公開版本維持0.8.5-preview.1，PR #20維持Draft，不merge、不部署。即時驗收見ACTIVE_TASK與PR；候選不借前片綠燈。

## 正式操作

Models → 全球研究，或Settings → 研究的同一工作區 →「研究共用額度池」。從現有Provider／帳戶／憑證清單選擇範圍，設定每日研究推論次數、token、USD上限；留空未啟用該項限制，0停止新核准。每次預留token／USD沿用015G政策，全部研究、逐憑證與所有適用共用池同時成立。

| 範圍 | 明確成員語意 |
|---|---|
| Provider | 此Provider全部Account與Inference Credential，包括之後新增的Key |
| 帳戶 | 精確Provider×Account下全部Inference Credential，包括之後新增的Key |
| 專案 | 使用者確認的Provider、專案識別及逐份選取的Provider×Account×Credential；不從Key名稱或模型推測Project |
| 自選憑證 | 使用者明確選取的完整憑證scope，可跨帳戶；同ID不同Account不混用 |

池ID／種類／Provider／Account／Project建立後固定，名稱、上限、啟停與專案／自選成員可編輯。停用是明確本機政策變更，不刪除紀錄。最多100池、每池200份明確成員；清單20池／歷史50筆分頁，不做假平均或不明總量百分比。

這是目前引擎的**本機研究核准政策**，不是官方quota、Provider帳單硬上限或實際剩餘額度。其他裝置／客戶端／網頁耗用未計入，不因設定池而宣稱免費或已證明官方共用關係。日常聊天與重用有效既有流量的研究不另扣此預算；沒有新增路由、fallback、Key輪換或私人排程授權。

## 用量與永久紀錄

每日UTC 00:00（台灣08:00）另起一天，舊日不刪。池用量為今日目前成員與該池永久關聯請求的聯集；同一請求在同一池只算一次，在不同適用池各自檢查。

新增／修改池時，在同一transaction將目前日的既有研究關聯為EXISTING_USAGE，修改前的成員用量也先保存。這是既有耗用歸屬，**不是追溯授權**。正式新請求入場保存ADMISSION及當時版本／定義；兩者分開。移除成員、停用再啟用、換Key版本、改上限、重建研究輪次或重啟，均不能退款或消除已關聯用量。

token／USD從同一請求的015G不可變結算讀取，保留原精確整數與十進位；未知、失敗、取消、中斷仍保留預留，超出預留完整計入。015G之前沒有token／USD預留的舊請求仍計研究次數，另顯示未追溯筆數；啟用其token／USD池限制時拒絕新核准，不把舊未知當零。

## 原子核准與恢復

正式MINIMAL_INFERENCE → 共用execute → 既有內容／scope檢查 → 同transaction的HTTP／probe、每日次數、token／USD預留、全部適用池、對話／工具／request收據 → 真HTTP。任何池拒絕、DB寫入或commit失敗，全部回滾，不留下其他池部分扣額、訊息或phantom sample。飛行中尚未回報的預留也擋住同池其他Key。

任務保存BUDGET_EXHAUSTED、池ID／名稱、受限項目、用量／預留／上限與UTC reset。尚未發送不消耗原三次網路失敗機會；調高上限、修改成員或跨日都不自動恢復，仍需正式「恢復研究」。原公開排程禁止私人推論與原4-flight上限保持。

## 設定、安全與升級

GET／POST `/api/v1/research/quota-pools`；GET的after是清單游標，history=1／before讀不可變變更紀錄。設定用整體池revision作CAS；409不重送、不偷偷更新草稿版本。讀取失敗保留last-good與編輯稿，重讀與「捨棄草稿並載入最新設定」分開，保存ack後只重讀。

scope與每份成員都明確校驗；新成員必須是實際Inference Credential，已保存歷史成員可保留。秘密值、控制字元、未支援欄位／scope、重複成員及未知單位拒絕；UI顯示Provider／帳戶名稱與必要scope，不要求手寫JSON。

schema28→29先保存並驗證原一致快照，再新增四份本機policy／definition／association／history表。完整SQLite快照保留，workspace同步及portable allowlist排除所有池授權；同步歷史不會使目的端取得新執行權。Mac內建同源引擎取得後端，Mac／Android專用原生設定仍待移植，不以網頁代替原生驗收。

## 測試與失敗歷史

- 首次正式Handler反例0.064s FAIL404，確認原本沒有共用池入口；不是既有功能已完成。
- 首輪新增測試兩處fixture錯誤保留：重啟未經原RESUME就期待重新入場；並行fixture給超出原10次probe限制的40。修為正式明確恢復、合法20／10及原4-flight，不放寬產品政策或減少20份工作。
- 聚焦九個後端函式含真TLS跨Key／Account／Project／Custom、換Key／重啟／UTC日界、既有耗用與移除成員、重疊池原子回滾、未知預留、精確0.1+0.2、20份工作／4-flight飛行中限制、DB故障／CAS／秘密／失敗封閉、舊未知／輸出預留、HTTPS同步／portable隔離、28→29一致升級與22池／53筆歷史分頁。
- 在原第33個Browser流程追加共用池設定、讀取失敗／CAS草稿恢復、獨立池拒絕無扣額／明確恢復、專案逐份成員與停用、跨日／變更歷史及320／375／1280深淺色／200%，增加9圖。原33流程、395圖、4 catalog／2 inference與原timeout／負載全部保留。

最終本機完整Go race／vet、原33 Browser／404圖、發布回歸、自己的同來源九項與全部產物仍須獨立驗證。未取得的真站／目標VPS／實體裝置證據仍未驗；完整願望、私人排程、逐級probe、完整站點辨識與Reflection、大資料同步與原生流程均不提前勾完。
