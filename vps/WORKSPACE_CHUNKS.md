# 大資料同步分塊與恢復

WORKSPACE-CHUNKS-015P 開發候選。015O所列範圍已由來源66ec4eb7af3278f49a99a50e1fcc165c3a89c1d2同源九項與實際產物／18視圖驗收；完整下一版仍PARTIAL，公開版本／tag／11附件凍結。此文件是實作契約與待驗條件，不能當作已完成功能。

原SYNC_CONTRACT明列：大於單筆傳輸上限的資料保留本機、需後續分塊協定。本片必須支援超過原32MiB的單一保留紀錄；只把原小頁切成數個HTTP請求不足驗收。新lane明確協商64MiB序列化record、65MiB請求／72MiB回應、256KiB片段；仍有界，超過界線明確保留資料，不截斷。嚴格v1 pair／exchange欄位與原限制保留，不讓舊Kotlin ignoreUnknownKeys=false誤收新欄位。

新能力協商是獨立、只讀且需目前有效裝置token的端點。裝置既有v1 exchange先以真TLS成功，才驗新協商；metadata-only與授權秘密兩種裝置都不能藉協商執行同步、推論或增加歷史；撤銷後拒絕。首反例尚未執行，不預填404／401或成功。

待實作閉環：完整來源／總長／SHA256與穩定transfer ID綁定device／workspace／origin／授權；加密暫存每個片段、精確offset／digest與可重送的相同內容；讀回持久進度後續傳；全部片段完整且雜湊正確才解析與套用；正式CAS／衝突／tombstone／秘密分流規則共用。產品變更與不可變回應收據須在同一交易；丟失完成回應先讀結果，不能重做已完成變更。回應亦分塊、加密暫存並核對後才套用，目的端資料／vault／cursor／consumed收據原子更新。

中斷、取消、程序重啟、片段損壞、亂序、重複、同ID不同內容、撤銷、換配對、資料同時被編輯及回應遺失均須保留本機修改與last-good。秘密不能明文落入staging、metadata、log、prompt或telemetry；沒有Key授權不能沿片段通道繞過原界線，撤銷須在實際使用前重驗。暫存及執行授權只屬本機，不經Workspace或portable移植。

正式caller須涵蓋VPS設定、Mac原生設定／共用引擎、Android原生manager／Room／Keystore；提供可讀進度與明確恢復，不要求手寫JSON。先完成所有已授權實作，再以真TLS、真DB／vault、真Browser／原生caller、實際包／兩架構及新來源九項驗證。保留全部原40Browser／501圖、585核心／423App／985device（5原skip）、兩Mac85核心／92圖與原395Go函式／586結果、systemd及完整資源負載；新增案例只增加、不替代原案例，600秒／5分鐘等原上限不放寬。

目前只有協商反例與本契約，尚未宣稱分塊／原子結果／原生入口可用。完成後還須接續其餘Reflection／私人schema／PLAY-004與P1–P10／28條發布條件。
