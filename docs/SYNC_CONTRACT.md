> 2026-10-26 WORKSPACE-CHUNKS-015P 同片修正候選，尚未ACCEPTED。來源3ced4c3c3bada7134e62a3d0ce5b9eb8f832bc5e未通過：VPS四項與Android build成功，但Mac同步頁型別推導編譯失敗，實際Android XML為996案例／990pass／1failure／5原skip；新增近64MiB上傳在512MiB ART中，1.8.0串流JSON仍配置整段字串緩衝而OOM。已重新下載並核對失敗ZIP大小／SHA256／CRC／完整堆疊，且原985個案例結果逐項與015O實際ZIP相同；見verification/20261025/workspace-chunks-initial-candidate.json。固定8192字元與8192位元組緩衝、舊格式精確bytes／近64MiB實際配置量／指紋反例已補，本機594核心成功；Mac拆分View並新增真TLS傳輸時按暫停、實際Keychain及引擎重啟、原ID按恢復的四張原生驗證，仍待自己的CI。舊v1分頁也在每頁前重新核對暫停；新增反例不改原三個模式。新來源預期407 Go入口／604事件（556pass／48opt-in skip）、41Browser／509圖、594核心／423App／996device（5原skip）、兩Mac各85核心／96圖（原92＋4）。原全部測試／負載／timeout／512MiB限制不減；自己的九項、九份實際ZIP與20視圖前不接受、不接新片。完整下一版仍PARTIAL，公開版本／tag／11附件凍結，不merge／部署。

> 2026-10-26（台北）WORKSPACE-CHUNKS-015P 整合候選：首反例來源73baf3b1的同一真TLS既有v1先成功、兩種裝置協商404已確認，原始589事件及job121206460227見verification/20261025/workspace-chunks-counterexample.json。現接上256KiB加密分塊、64MiB紀錄／65MiB請求／72MiB回應上限、持久進度、整體雜湊、產品與不可變回應收據同交易、目的端資料／vault／cursor／CONSUMED同交易、只讀恢復／ACK／暫停與安全重設。VPS／Mac Settings偏好可恢復協定，Android真manager／Room／Keystore使用串流；舊中心僅明確404／不支援時沿原v1，小頁格式及限制不改。大衝突摘要不改完整CAS或歷史。原Browser抓到前輪收尾時會遺失已確認的接續同步，現合併排隊且不改原斷言；兩真Browser race11.246s通過，新增8圖。Android本機591核心／423App／兩APK成功，追加11個真裝置案例已編譯但尚未執行；完整新來源九項／九份實際ZIP／相關實圖前仍未ACCEPTED。保留原586Go結果、40Browser／501圖、985device／5原skip、兩Mac85核心／92圖與所有原負載／timeout；完整下一版PARTIAL，公開版本／tag／11附件凍結，不merge／部署。 同源預期407個Go入口／603事件、41Browser／509圖、591核心／423App／996device（5原skip）；實際CI與Mac新增進度分支介面驗收仍待完成。

> 2026-10-25 唯一新片 WORKSPACE-CHUNKS-015P：015O來源66ec4eb7af3278f49a99a50e1fcc165c3a89c1d2／tree648dfd7bb3b7b989418a81fac3a4da995882d5dc已由同源九項attempt1、九份實際ZIP、額外只讀稽核與18實圖驗收所列公開欄位對照／管理交接範圍，精確證據見verification/20261025/field-mapping-baseline.json及Draft PR #20。main仍cba901d／39536090788 build＋device成功，FEATURE FREEZE=OFF。接續大於原32MiB單筆上限的分塊／持久進度／中斷恢復／完整雜湊／原子結果與三端正式入口；先加入獨立裝置能力協商的真TLS反例，尚未接線，不預稱通過或完整同步已交付。保留原嚴格v1 pair／exchange格式、原全部工作量／timeout、精確scope／秘密／撤銷／衝突／last-good；自己的新來源完整九項／全部實際產物前仍候選。公開版本／11附件凍結，不merge／部署，完整下一版PARTIAL。

# 跨平台同步契約 — SYNC-001

使用者在 2026-10-19 授權完成願望清單，並將跨平台資料同步列為優先。本片仍為整合候選；不能把傳輸完成等同每個原生功能都已對齊。

## 已接線的行為

- 可選的可信任 VPS 作同步中心。各裝置仍有自己的本機資料；中心離線時，既有連線、模型與歷史可繼續使用，修改留在持久佇列。
- 五分鐘、限用一次的高熵配對碼；每台裝置有獨立、可撤銷的同步權杖。權杖不等同管理員權限，也不能用來呼叫推論 API。
- 增量游標、每筆基準版本與 compare-and-set。兩端離線修改相同資料時，保留兩份版本及衝突歷史；選擇前再核對目前本機內容，過期預覽不覆寫後來修改。
- 持久變更日誌與刪除標記。訊息、觀測、研究證據等歷史不能藉同步刪除；憑證來源和歷史 Endpoint 綁定不可被改寫。
- Key 同步由中心逐裝置授權，預設不勾選。秘密只在認證 HTTPS 的獨立通道傳送，並在目的端重新加密；同步 metadata、衝突預覽及日誌不保存明文秘密。
- 中心是受信任的推論裝置，可以使用授權給它的 Key；這個方案不是「中心無法解密」的端對端加密。
- Go 端約每分鐘接續；Android 透過真正的 WorkspaceSyncWorker 在連線後約每十五分鐘接續，也可立即同步。這不會啟動尚未接線的舊 Provider polling worker，亦不會自動呼叫付費模型。

## 平台資料對應

| 資料 | VPS／Mac 共用 Go 引擎 | Android 原生端 |
| --- | --- | --- |
| Provider／Account／Credential | 原有完整作用域與歷史綁定 | 明確對應全域原生 ID，避免不同 Provider 下同名 Account／Key 混在一起 |
| 模型與明確 My Models | 保留 Catalog 與成員資格區隔 | 已知字串群組的選擇可轉為 Offering／Availability；單純 Catalog 不會自動加入 My Models |
| 整數、價格與群組 | 64 位整數、精確 decimal 字串 | 保留原值；null 群組與空字串不互換 |
| 研究、觀測、對話與歷史 | 共享白名單資料表逐筆同步 | 共享紀錄完整保留；尚無同義原生呈現的資料保留原格式，介面顯示未套用數量 |
| Android 特有分類／快照／歷史 | `android:` 名稱空間保存原始資料 | 以原生資料表白名單及依賴檢查套用；其他裝置的測速不冒充本機測速 |
| 大型訊息 | 分頁限制保護記憶體，不截短產品歷史 | 分段讀取 SQLite 文字，避免 CursorWindow 對大型保留紀錄的限制 |
| 秘密與本機系統設定 | 各機主金鑰分開；不複製管理員 Token／執行租約 | Android Keystore；目的端密文和資料更新使用同一 Room 交易 |

Android 的未知 null 群組、複合自訂認證與尚未有同義呈現的功能不能強行降級為 Go 的空字串群組或 Bearer。這些資料保留在原始名稱空間；對應完整前，不宣稱「所有原生功能已互通」。大於單筆傳輸上限的資料仍留在本機，需後續分塊協定，不能靜默截斷。

## 驗證與仍待驗收

2026-10-20最新code：VPS／Android `c9d1d33` 六項、Mac `f67cfa4` 九項同SHA SUCCESS。Mac兩架構各82核心／76靜態圖／14frame、Android973項與21 Browser已通過；新409真生命週期競態已修，等待可取消、不盲目重跑。Mac 0.7.0 build13的Universal／QA artifact與內外層SHA256已核對。完整證據見`docs/verification/20261020/sync-ci-baseline.json`。以下6ec資料為前一code基線，不外推新HEAD；本次文件HEAD仍另跑必要完整CI。

2026-10-20 code `6ec4662` 已由 VPS `39403490771` 四項與 Android `39403490790` 兩項同 SHA SUCCESS。Android 報告 973 項、0 failure／0 error、5 項既有 API skip；真 Go↔原生整合 4.766 秒通過，另驗 Android→中心→第二原生端、原始 Offering／Availability ID、跨帳號 origin、匿名及逐 scope 成員移除。Browser 21 個流程與 288 圖完成，新同步 7 圖已自行檢視；原 workload 保留，單服務峰值 180,740,096 bytes。精確證據見 `vps/verification/20261020/6ec4662-sync-baseline.json`。這是指定 code SHA 的驗收，不外推後續文件 HEAD 或 Mac 新候選。

- Go 真實 TLS 雙向同步、目的端重新加密、Key 版本更新、離線衝突兩種選擇、撤銷、metadata-only、64 位整數、成員刪除與歷史保留已有整合測試。
- Browser 新增真實配對、衝突預覽與選擇、撤銷後保留資料；原有全部流程保留。320／375px、深淺色、200% 字級與 48px 控制項皆有檢查；測試環境安裝繁中文字型。
- Android 以同一 production manager、Room、Keystore，連到 CI 的真正 Go HTTPS 引擎；包含超過 CursorWindow 的保留訊息、雙向編輯、衝突與撤銷。測試 fixture 不隨產品啟動，不連線真實供應商。
- Mac 原生設定入口與真 packaged engine／Keychain／HTTPS重啟接續已由PR #21 code `f67cfa4` 的九項及實圖通過；App／Engine均含arm64和x86_64，原78核心／148靜態圖及28frame全保留。完整產品與實機範圍仍按上列限制接續。
- 所有結果以 ACTIVE_TASK 的精確 SHA 與 Actions 為準。目前沒有目標 VPS 部署、Sharp R8s／iPhone／M2 實機驗收；Part V 的爬蟲排程、未知站點、自我改進及真實性驗證仍各自保留待辦，不能提前勾選完成。
