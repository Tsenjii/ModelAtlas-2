# 大資料同步分塊與恢復

WORKSPACE-CHUNKS-015P 所列範圍已驗收，來源bc4a735e9144564c5d78433b196038d2452c8def，同源九項／九份實際ZIP／20視圖均完成；精確證據見docs/verification/20261026/workspace-chunks-baseline.json。以下候選與失敗敘述保留歷史。015O所列範圍已由來源66ec4eb7af3278f49a99a50e1fcc165c3a89c1d2同源九項與實際產物／18視圖驗收；完整下一版仍PARTIAL，公開版本／tag／11附件凍結。此文件是實作契約與待驗條件，不能當作已完成功能。

原SYNC_CONTRACT明列：大於單筆傳輸上限的資料保留本機、需後續分塊協定。本片必須支援超過原32MiB的單一保留紀錄；只把原小頁切成數個HTTP請求不足驗收。新lane明確協商64MiB序列化record、65MiB請求／72MiB回應、256KiB片段；仍有界，超過界線明確保留資料，不截斷。嚴格v1 pair／exchange欄位與原限制保留，不讓舊Kotlin ignoreUnknownKeys=false誤收新欄位。

新能力協商是獨立、只讀且需目前有效裝置token的端點。裝置既有v1 exchange先以真TLS成功，才驗新協商；metadata-only與授權秘密兩種裝置都不能藉協商執行同步、推論或增加歷史；撤銷後拒絕。首反例已由來源73baf3b1／VPS run40205841248／job121206460227實際確認：既有v1先成功，兩種裝置獨立協商皆404。完整589事件與原始時間見docs/verification/20261025/workspace-chunks-counterexample.json。

待實作閉環：完整來源／總長／SHA256與穩定transfer ID綁定device／workspace／origin／授權；加密暫存每個片段、精確offset／digest與可重送的相同內容；讀回持久進度後續傳；全部片段完整且雜湊正確才解析與套用；正式CAS／衝突／tombstone／秘密分流規則共用。產品變更與不可變回應收據須在同一交易；丟失完成回應先讀結果，不能重做已完成變更。回應亦分塊、加密暫存並核對後才套用，目的端資料／vault／cursor／consumed收據原子更新。

中斷、取消、程序重啟、片段損壞、亂序、重複、同ID不同內容、撤銷、換配對、資料同時被編輯及回應遺失均須保留本機修改與last-good。秘密不能明文落入staging、metadata、log、prompt或telemetry；沒有Key授權不能沿片段通道繞過原界線，撤銷須在實際使用前重驗。暫存及執行授權只屬本機，不經Workspace或portable移植。

正式caller須涵蓋VPS設定、Mac原生設定／共用引擎、Android原生manager／Room／Keystore；提供可讀進度與明確恢復，不要求手寫JSON。先完成所有已授權實作，再以真TLS、真DB／vault、真Browser／原生caller、實際包／兩架構及新來源九項驗證。保留全部原40Browser／501圖、585核心／423App／985device（5原skip）、兩Mac85核心／92圖與原395Go函式／586結果、systemd及完整資源負載；新增案例只增加、不替代原案例，600秒／5分鐘等原上限不放寬。

目前已接線下列整合候選，但尚未經自己來源九項／實際產物／真裝置完整驗收，不宣稱ACCEPTED。完成後還須接續其餘Reflection／私人schema／PLAY-004與P1–P10／28條發布條件。


## 候選端點與正式入口

既有v1 pair／exchange完全保留，新裝置用獨立POST capabilities協商；POST transfers/begin、status、part、finish、reply、ack、abort都受目前device token／share_secrets與origin約束。每裝置一個未結束傳輸，穩定ID／完整SHA256／大小及來源綁定；錯片、亂序、範圍變更與撤銷拒絕，未知完成結果只讀原ID。收到完整且雜湊正確的請求後才解析，產品更新與加密回應片段／DONE收據同一SQLite交易。finish在同一同步槽內雜湊及解碼，並在真正套用前重新驗證裝置。

VPS與Mac由Settings的配對／立即同步／恢復同步選擇可恢復傳輸偏好，該本機偏好持久化於schema37；背景與手動共用相同鎖與journal。舊呼叫者保留v1。只有明確協商404或version0才可用舊小頁，撤銷／不符scope／不符上限不得暗中fallback。大於32MiB仍必須協商新lane；超過64MiB保留本機並報錯。

Android沿原Room16本機state／Keystore vault保存收據與加密片段，沒有可攜的執行授權或明文整份envelope。固定8192字元／8192位元組緩衝編碼與decodeFromStream配合有界SQLite UTF-8片段，傳輸只留精簡請求指紋供CAS；完整record仍保留在產品journal。產品資料、vault密文、cursor與CONSUMED在同一Room交易，ACK只清除傳輸快取。金鑰作業在Room交易外完成，維持vault→database鎖順序。record摘要快取由SQLite真INSERT／UPDATE／DELETE失效，包含相同長度的後續修改。

三端有可讀上傳／接收進度與暫停／恢復。暫停不等待整段傳輸鎖，下一片及目的端交易前再檢查；已在途的一片可能完成。使用者明確要求重新準備時，先向中心確認未DONE、再abort及清理傳輸快取；已提交結果必須恢復讀取，不能改用新ID重做。中斷配對只清理本機傳輸狀態，產品與完整歷史保留。

GET /workspace/conflict?preview=1為有界呈現；明確揭露長內容摘要，local_hash仍是完整現況的原canonical bytes雜湊，選擇版本再核對。原預設完整讀取保留；兩份完整版本不因摘要而刪改。

## 當前證據與待驗

本機第一版402個Go註冊入口／595事件在原共同600秒內341.430s通過，逐項保持015O原586結果；後續偏好、重設、遷移與大衝突擴大45.835s通過，最終407入口／603事件仍待同源完整重跑。實際33MiB單一紀錄／重開DB續傳／遺失finish／遺失ACK／秘密加密／原子回滾／本機新修改均已由真TLS與SQLite聚焦測試確認。

兩真Browser11.246s涵蓋正式Settings從已保存的1MiB永久訊息傳輸恢復、第一256KiB後暫停與重載、原ID讀進度再完成、精確雜湊／一筆永久變更／0推論／模型保留，以及原衝突選擇流程。新增8個320／375／1280、明暗與200%畫面；不可據此宣稱Browser實際傳了64MiB。

本機Android591核心／423App、debug及androidTest APK建置成功。新增11個instrumentation案例：接近64MiB的Go永久歷史接收、接近64MiB的native external observation上傳、Room實際重開後lost-finish／lost-ACK恢復、真暫停與快取重設、舊v1相容、UTF-8跨CursorWindow、同長度修改失效，以及Keystore domain／origin拒絕；另加真損壞回應保留last-good／原ID恢復、換配對清除舊fetch意圖但保留產品與vault。11個案例皆已通過androidTest APK編譯，但原512MiB／API29真裝置尚未執行，不能填入成功。

兩架構Mac原真Keychain／兩packaged engine的TLS、歷史、衝突、程序重啟、撤銷及目的端金鑰測試保留，追加可恢復偏好與ACK收據持久斷言；新Settings進度／暫停／恢復／重設仍待實際Mac編譯與介面驗收。全部原資源負載、600秒Go／5分鐘Browser／35分鐘device等上限不放寬。完整下一版仍PARTIAL，後續Reflection／私人schema／PLAY-004及P1–P10／28條發布條件尚待完成。


## 首整合候選的真失敗與同片修正

3ced4c3來源沒有通過，不能沿用其VPS／Android build綠燈接受。實際Android ZIP12612140025為1267255 bytes，SHA256 `2018026e96c807cccbfbda6c6f72ff979695c0fde82db47acb4a8def0c51db31`；996案例中原985結果逐項不變，新增10pass／1failure。失敗是接近64MiB的原生external observation上傳：512MiB ART中JsonToJavaStreamWriter.writeQuoted／appendStringSlowPath／ensureTotalCapacity嘗試配置268402776 bytes。不是縮小fixture或提高heap來處理；完整trace、case inventory、來源與各job見verification/20261025/workspace-chunks-initial-candidate.json。

大字串改由WorkspaceBoundedJson固定緩衝輸出，逐段轉UTF-8，控制字元／引號／反斜線與跨邊界emoji由精確legacy bytes測試核對；record／exchange／reply的欄位順序與defaults完全保留。有界小數值沿用1.8.0的原序列化語意。原生CAS指紋另外保存數值literal與無效UTF-16差異，不把1e1／10.0或不同未配對surrogate當同一資料；SQLite指紋則對準實際保存的wire表示，避免暫存未落盤的原字面。大native SQL讀取以UTF-8 bytes／code point上界預留UTF-16容量，避免尾端emoji使近64MiB builder整倍擴充。

新增核心配置量反例實際編碼接近64MiB的escaped String，逐執行緒量測額外配置須小於8MiB、每次UTF-8 write須不超8192 bytes；不是只數小fixture。原6個chunk與585舊核心案例保留，合計594。Mac原25 smoke／53 parity／14motion保留，另4個真傳輸實圖及狀態／Keychain重啟收據；新增中心與兩個前後peer程序全部納入RSS與退出檢查，原120秒上限不變。新來源預期407 Go／604事件、41 Browser／509PNG、594核心／423App／996device、兩Mac各85核心／96PNG；仍須同源九項／全部實際bytes與20視圖核對，不能因本機通過預稱交付。


3691e4e的Android job121352711829在預檢讀初始zygote64時失敗，尚無任何instrumentation案例；不能把其當作記憶體修正通過或失敗。原錯誤缺少PID實值，只能記錄讀取不符合唯一PID及同時device unavailable。現在同一原90秒預檢deadline內等待唯一初始PID，再依原規格一次stop/start與新PID／package manager／512MiB設定；真ART start+finish仍必須驗證。兩個可控預檢反例在舊版失敗，修後41項回歸全過；原所有工作量／deadline／heap保持，不重跑裝置案例掩蓋失敗。精確證據見verification/20261025/workspace-chunks-emulator-preflight.json。
