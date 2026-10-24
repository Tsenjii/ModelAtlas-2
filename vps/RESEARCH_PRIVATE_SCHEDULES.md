# 私人模型研究排程

2026-10-24，PRIVATE-RESEARCH-015I。前片015H已驗收；本片須自己的同來源九項與全部實際產物，不能借用前片綠燈。公開版本保持0.8.5-preview.1，PR #20仍Draft。

## 正式操作與授權

VPS「全球研究」→排程→明確勾選私人模型→選擇My Models中的確切Provider／Account／Credential／Model／Group／Endpoint→儲存停用草稿→讀取當前來源確認→明確確認並啟用。私人catalog與最小推論分開選擇；最小推論另需每輪1–10次上限。預設公開排程仍不能使用私人憑證或發推論。

確認介面列出確切選取、有效origin、operation及Key版本，並顯示每日推論次數／token／USD與每輪HTTP／probe。GET review只讀、不發HTTP；ENABLE帶目前revision與當前scope指紋，在同一transaction重新核對。讀取後換Key／來源／設定會拒絕409，須重讀來源並重新確認；不能僅傳confirm=true略過實際來源確認。編輯重新停用、清除舊approval，須再次確認。讀取失敗／CAS衝突保留草稿與精確選取。

不可變receipt綁定完整定義與確切scope、有效origin／operation、endpoint／設定雜湊、Key版本及加密材料revision。catalog一般metadata更新不撤銷未改變的憑證授權；實際scope／來源／憑證改變會停止排程並要求重新確認。私人CATALOG的adapter context只能使用已確認的推論Key，不能借同帳戶管理PAT、其他Key或別帳戶憑證。New API公開pricing等仍可匿名讀取；管理資料沒有隱含授權。

## 預算與恢復

每日排程次數0–2000；token／USD可明確限制，空值表示此排程不另外設限，0則禁止相應新核准。仍須同時通過原每輪HTTP／probe、每日HTTP、全域／逐憑證次數、token／USD與全部適用共用池。私人排程、原用量與對話／工具紀錄同一transaction核准；任何本機拒絕回滾全部，不發HTTP、不扣其他meter。

HTTP、每日推論與資源預算以同一UTC入場時間分日。token／USD沿用015G的精確預留與同request回報結算；Unknown、取消、失敗、中斷不當零、不退款。不同approval、調高限制、換Key、重啟都保留該排程原本今日用量。這是本機核准政策，不是Provider官方quota或帳單硬上限。

每日推論／資源拒絕預設暫停，必須明確恢復。可另外勾選「推論預算於UTC重設後自動接續」；此選項與原HTTP重設恢復分開。政策讀取失敗或輸出上限超過預留仍要求手動處理，不會每30秒空轉。每輪probe耗盡只在下一個原排程間隔接續未完成工作，不加額、不回填錯過時段。排程所屬run不能用一般RESUME繞過已確認的每輪HTTP／probe上限。

已入場且程序中斷的私人推論保存原用量，標記FAILED，永不自動重送該推論。其他原本可恢復的公開GET／分頁仍依原限次策略處理。停用會取消飛行中請求；晚回應不退額、不恢復排程。新一輪明確計畫與中斷請求重播是不同操作。

## 歷史與本機權限

schema30增加不可變私人approval與schedule inference admission兩表，升級前保留一致快照。確認歷史每頁50筆、持久revision游標；正式排程卡提供歷史入口。workspace／portable既有allowlist只傳原公開metadata，不傳本機私人授權、執行意圖或用量權限；另一端收到metadata不會取得執行權。Mac內建同源引擎取得後端，Mac／Android專用原生介面仍待補，不能用網頁替代原生驗收。

## 反例與驗證紀錄

- 正式私人排程入口初始400／0.058s失敗保留，新增明確policy及正式caller後通過；原公開排程所有guard保留。
- 首聚焦34.887s失敗指出worker仍有公開排程的全面私人拒絕、fixture另有未結束手動工作；改為精確本機approval核對，fixture明確取消舊工作並清空原metadata enrichment後再計量。修後原七個私人函式15.024s成功。
- 首Browser錯誤把初始Key版本假定1，實際契約為0、兩次rotation後2；修正fixture斷言，保留全部旋轉、409、次數與UI操作。私人流程9.04s成功；含正式確認歷史及原公開排程的聚焦25.702s成功。
- 午夜真反例1.178s FAIL：HTTP在昨日而推論在今日入場，使額外請求通過；同一入場時刻修正後，新案例及015G原午夜回歸3.094s成功。
- 取消案例初始卡在測試TLS handler清理：handler等人工release且未讀body，無法及時看到server disconnect。保留client的5秒取消斷言、用量及late reply檢查，只在其後釋放fixture handler。53筆確認分頁／交易故障與取消聚焦5.305s成功。
- 真TLS New API同帳戶有唯一PAT的情境，私人catalog＋推論不借PAT或其他Key；與原憑證角色／provenance回歸3.343s成功。

- 首完整Browser的共用程序連線實際重現原download.path不支援遠端協定，原Production／Conversations／Management／Protocol／MacParity失敗保留；該輪明確中止（exit130），不當完整成功。新增readDownload以Playwright正式createReadStream讀取每一筆實際下載的全部bytes，原12處JSON／音訊／圖片／匯出斷言不變；完整全套另跑。

後端私人＋原排程完整聚焦race43.541s、vet及27發布回歸0.027s成功。

完整Go race443.316s成功。下載相容後第二次完整Browser在原ModelConnections的未界定事件等待卡住，300.596s FAIL，未當通過。追加一筆真GET在安裝下一個延遲前已入場的情境；原方式6.915s FAIL（未完成route在清理時報already handled）。改在既有flight結束後才核准新的真GET，5秒內必須看到攔截，不重寫production狀態；原焦點、跨日拒絕、一次寫入及兩次真TLS推論全部斷言保留。含新私人流程的聚焦17.531s成功，完整全套及新來源CI重新驗。

完整Browser追加第34流程與11圖，保留原33流程／404圖的全部檔名、情境、操作與HTTP斷言。測試共用一個實際Chromium程序，每個原腳本仍各自連接、建立全新隔離context／page與獨立真HTTP fixture，結束斷開；不mock頁面／API、不減原5min測試與15min job上限。額外實測cookie／page隔離，並檢查非預期launch設定。原全部Go race／vet、Android、Mac、systemd與expanded workload仍必須通過。

完整逐級probe／重驗、語意身份、任意站點、完整Reflection與大資料同步及專用原生流程仍是下一版待辦；本片不把完整願望提前勾選。精確最終來源、CI與實際產物見PR #20與後續verification紀錄。
