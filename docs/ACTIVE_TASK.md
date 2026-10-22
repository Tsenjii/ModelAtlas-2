# ModelAtlas Public — ACTIVE TASK

## 2026-10-22 未知站點擷取草稿（CONNECTOR-DISCOVERY-015C）

- 使用者持續要求完成主要功能。依D-015，本輪不變更公開版本、release/request.json、tag／附件；PR #20維持Draft、不merge、不部署。
- 前片015B所列範圍已驗收：來源480bdeac4a468754315747b8e0b0593f4cf67709／tree2cedcdf0a3879532dc61a19caaf22c32ac005b5b。同來源VPS39789581672 attempt2四項、Android39789581690兩項、Mac39789581662三項SUCCESS；精確job見PR #20。Go race344.531s／vet、真systemd41.406s、原expanded workload24.650s；30 Browser249.331s／363PNG／0 failure圖，成功producer119870565453、附件12432556534／SHA256 e8dd0b5c4a57318e0d3d12c33a127332764ce9197a29b6b9c8f1286297e530e6已獨立核對。Android579 JVM／976 device（5原skip）、Mac各82核心／90圖保留。首Browser因下載依賴逾15min取消、慢body退避修前反例與本機300.027s逾時均保留，不能抹掉失敗。
- 開工重查main仍cba901d187aa5312cc71f5c2440b23fa04104aee，39536090788 attempt2 build119016886469／device119016884592 SUCCESS，FEATURE FREEZE=OFF。
- 當前唯一整合片015C：有界公開來源辨識→具來源hash／scope／單位與Unknown的宣告式草稿→本機fixture→既有真HTTP實測／Review→明確啟用與跨重啟回退。先補現有手動JSON落差，因此從015B後接此片；排程剩餘預算與全願望不刪除。只接受實際明確支援的資料形狀，不宣稱所有站點／私人入口都自動相容。
- 本片仍開發候選，須正式caller、反例、完整本機與同來源九項後才接受。原30 Browser／363圖、全部原生測試、負載、5min測試及15min job上限保留。
- 本機新Browser5.69s／8圖及原Knowledge4.29s成功；最初完整Go race372.366s成功後，再以1.136s FAIL重現同步改動已啟用version row可借舊active執行。已補本機啟用設定綁定與升級保留，16個後端函式／原Connector聚焦26.599s成功；最後來源完整Go／Browser及九項重新驗，不借修前結果。
- 6e25c55候選本機完整Go race303.798s／vet／27發布回歸0.027s成功，但不能接受：CI Browser119899276577的舊策略監測流程34.27s失敗，畫面顯示啟用後0輪，之後全套在300.022s逾時；失敗附件12434849226（354PNG，SHA256 53c13420e1aeab163d7d78fbe83eb727c6c341e29dff4ee312a88ba06ac007f8）已獨立下載核對並檢視失敗原圖。本機同候選監測5.48s通過，但完整全套仍於30流程後300.022s逾時，原上限保留。
- 已以延後GET的既有Browser流程確定重現5.914s FAIL：按更新後舊版本卡片仍可開啟，Modal捕捉舊0輪，完成讀取不更新該Modal。修為只在最新讀取期間停用四個研究清單的舊卡片，標示aria-busy；成功後新卡片可用，失敗則原last-good卡片恢復。既有14HTTP／3輪退步／Undo斷言與timeout完全保留，追加明確pending gate反例。修後原Knowledge3.46s、新辨識4.92s、原策略監測5.37s（合計14.776s）成功；新來源重新完整Browser及全部九項，不借6e25結果。


## 2026-10-22 Brave 持久分頁（RESEARCH-BRAVE-015B）

- 最新要求繼續直到下一版主要功能齊全，依 D-015 不發單片小版本；release/request.json、版本、公開 tag／附件保持原值，PR #20 保持 Draft、不 merge、不部署。
- 開工重新查 main `cba901d187aa5312cc71f5c2440b23fa04104aee`／39536090788 attempt2：build119016886469、device119016884592 SUCCESS，FEATURE FREEZE=OFF。
- 前片015A所列範圍已驗收：最終來源 `e384646a1435ce72fad2661070dd73a34d44a3d1`／tree `4f9219579a49d0d7ea378ec5832a32de78dd19b0`；VPS39763537520 attempt2四項、Android39763537488兩項、Mac39763537343三項SUCCESS。Browser首工作因下載依賴耗9m22s觸及原15min job上限而CANCELLED，保留歷史；成功重跑119780617449為29流程269.426s／355圖。Go race237.339s／vet、真systemd40.42s及原expanded workload、Android579 JVM／976 device（5原skip）、Mac各82核心／90圖全保留。全部精確job、附件hash及實圖見PR #20；015A不外推完整WISH-REFL-001完成。
- 當前唯一整合片015B：正式研究／排程入口的Brave頁數與地區、逐頁checkpoint／重啟／限次恢復、共同冷卻與原子HTTP／每日／憑證revision用量、同步只讀隔離。先完成本片與同來源九項，再進未知站點解析等後續功能。
- 真production caller＋TLS先重現0.065s失敗：429直接FAILED而未保存等待。首新多頁測試也發現測試誤用POST runs／202（舊API應start／201），此測試錯誤已改正，不當作產品修復。當前開發候選，不能把新增schema或本機聚焦通過當作驗收。
- 初候選9e75ff0（tree79cd54d）完整30 Browser289.058s成功；最後審查真TLS重現慢回應中斷已消耗退避的1.227s FAIL，故同片改成失敗時起算30／60秒，timeout保留journal；Brave／多語／評估聚焦41.063s及vet成功。最終新來源九項與完整Browser重新驗，不借9e75ff0候選結果；原失敗與候選留存。

以下015A候選過程保留為歷史；最終接受證據以上節與PR #20為準。

## 2026-10-22 下一版完整功能週期（NEAR-FULL-015）

- 最新使用者指示為「目標是全部做完，下一版要完成幾乎所有功能」，依 D-015 調高下一個公開版本的完成門檻。開發／驗證持續進行，暫不再因單一小片更新應用程式版本；release/request.json 與所有公開 tag／附件保持原值。總驗收清單見 `NEXT_RELEASE.md`。
- 0.8.5-preview.1 **已公開驗收**，來源 `699d4d57ff8264506c2b2f6e5eed0021327bf602`：VPS39746388040四項、Android39746387862兩項、Mac39746387882三項首次 SUCCESS；發布39746376859／119715783372成功。11個公開附件、manifest `1624476118fe9aa318cd8100b7fda7cc76cf156e1f66b7fe56c9c6435b505e44`、正式下載器與實際舊版→schema21升級均已驗。以下0.8.5候選與失敗留作歷史。
- 開工重新查 main `cba901d187aa5312cc71f5c2440b23fa04104aee`／39536090788 attempt2，build119016886469及device119016884592 SUCCESS，FEATURE FREEZE=OFF。PR #20維持Draft、不merge、不部署目標主機。
- 當前唯一整合片 **RESEARCH-EVALUATION-015A**：持久保存實際使用的策略版本、多輪品質與成本觀察、提案相對基線的本機評估、明確 Review／啟用、後續實際研究監測與跨重啟回退。先補完整流程，再接下一片；當前尚未驗收，不能宣稱 WISH-REFL-001 全部完成。
- 已落地schema22／23、本機不可變證據／admission receipt、最多32輪／30日有界比較與stale gate、版本固定含排程接續、3輪下降及跨重啟回復；VPS網頁與Mac原生評估入口。完整Go race285.057s／vet、27發布回歸、含原排程與AA聚焦36.156s及追加時效／接續16.650s成功。新Browser首readonly按鈕bug與測試locator／Promise等待失敗原樣記於vps/RESEARCH_EVALUATION.md，修後3次聚焦成功；首完整29 Browser在原五分鐘上限271.965s成功；負面效益政策收斂後另加真TLS反例，第二次完整Go race220.603s成功；9ea0085九項仍在跑時，審查追加每次啟用／Undo還原獨立時期，避免舊佇列／前次成績混入，時期隔離含舊佇列／Undo／內部v22升級的聚焦race42.406s、原知識與新Browser5.510s及vet成功；另補跨來源新候選只歸功首次任務、回傳重複與重查分開、v2評估／品質政策隔離；最終v2與Unknown分母／舊政策隔離race46.001s／vet、原知識與新Browser6.091s成功；最終新SHA獨立九項，不借9ea／8d候選結果。貨幣／token成本未知，完整改進願望仍PARTIAL。

以下保留先前已交付切片與失敗證據。

## 2026-10-22 AA 逐頁持久恢復（VPS-AA-RECOVERY-014）

- 使用者要求繼續開發直到穩定與完整。維持 VPS → Mac → Android，唯一新片為 WISH-CRAWL-001 的 AA 分頁 checkpoint／限次恢復。前片 **0.8.4-preview.1 已公開驗收**，來源 `e6b06135510c63e6ccd65a2ce8928d2396ffcb19`：VPS39719294100四項、Android39719294101兩項、Mac39719294093三項均首次 SUCCESS；發布39719289283／119628801054成功，11附件、公開下載器／實際AMD64服務重驗，manifest SHA256 `5d80c556887602d16df3d5f1d752cf0062b4c520a7b942798d8d066bbbf60248`。前節候選與失敗原樣作歷史保留。
- 本輪重新查 main `cba901d187aa5312cc71f5c2440b23fa04104aee`／39536090788 attempt2，build119016886469及device119016884592 SUCCESS，FEATURE FREEZE=OFF。PR #20維持Draft、不merge；未有目標VPS資料，未遠端部署。
- 真production caller＋TLS先重現0.177s失敗：第1頁200、第2頁429，AA任務直接FAILED並丟失已完成頁面。新增schema21本機 `research_aa_checkpoints`，每頁驗證後提交；研究run／排程接續／程序重啟均從未完成頁繼續。每頁最多3次已核准連線（初次＋2次自動恢復），429／502／503／504遵守Retry-After及30／60秒分散等待，無阻塞sleep；已送出但程序中斷的頁面仍計入此上限。
- AA固定window、每輪HTTP、排程每日HTTP及page attempt在同一SQLite transaction保留；任何本機拒絕不消耗其他meter。暫停／取消／來源確認、原排程intent、同步不執行仍保留；同步只帶公開進度／歷史，不帶部分頁面、憑證revision或本機執行控制。較舊直接AA sync API保留明確同步語意，私人catalog／推論／Brave沒有新增隱式重試。
- 完整榜單才原子寫入有效snapshot與完成checkpoint。版本／總頁數／has_more／重複ID／schema不符、換Key或首頁24h期限已過均停止，保留last-good原有效期；即使最後一頁飛行中換Key也不發布。指標版本與頁數一致不是站方提供的跨頁資料庫snapshot isolation，不額外宣稱同一瞬間資料。完整commit後、worker寫terminal狀態前重啟不再發HTTP。
- VPS全球研究與設定→服務的兩個更新按鈕均進入此正式runner；紀錄顯示已保存頁數、下一頁、等待／預算／暫停／停止與完整歷史。沿用原dialog、48px操作與同一poller。Mac內建同源研究引擎取得後端能力；Mac原生直接更新按鈕與Android專用逐頁UI仍待對應。
- 本機AA後端race21.253s成功：8個新函式含10種終止情境、原AA測試、真TLS／配額兩向回滾／逐頁與排程續鏈／重啟／取消飛行／換Key晚回應／完整commit重播／同步隔離／v20升級快照。新Browser13.50s成功、8圖已檢查，涵蓋320／375／1280深淺色與200%。首輪瀏覽器缺固定Chromium及字型已補齊；另首次測試未展開AA榜單而等待隱藏內容，修為真實展開操作，不當成產品修復或成功驗收。
- 首完整Go race263.485s失敗：原TestResearchScheduleIntegrationAdmissionWaitDoesNotSpin要求保留一次未完成AA更新歷史，新checkpoint流程漏存。已恢復每次未完成execution的獨立FAILED archive（包含本機配額拒絕／取消），保留checkpoint未來VALID的ID與last-good，原斷言／timeout不改；新增測試也核對每次失敗不消失。修後聚焦與完整Go／Browser仍重新驗證，不把首失敗當通過。
- 修後聚焦AA／原IntegrationWait／v19升級race27.542s成功；完整Go race214.055s及vet成功，27發布回歸成功。完整Browser與本來源CI仍獨立重新驗證。
- 原27 Browser／339 PNG、全部Go／原生測試／負載與原timeout保留，追加第28流程及8圖。完整本機／新HEAD自己的九項與實際發布前仍 **CANDIDATE**，精確最終SHA／run／job／附件見PR #20與manifest；不得借前片綠燈。
- Brave逐頁恢復、逐憑證推論／token／費用預算、未知站點自動解析、可量測Reflection、authenticity、大型分塊同步／部分原生呈現、目標VPS與實機／真站長跑仍未完成；完整原願望／P8–P10仍PARTIAL。

- 準備0.8.5-preview.1三平台同源預覽版（Mac19／Android8006）；只有本來源九項、27發布回歸、全部附件與實際公開下載驗證後才接受，不覆寫0.8.4或舊tag。

以下保留已交付切片與原始失敗證據。

## 2026-10-22 公開研究共同來源冷卻（VPS-RESEARCH-ORIGIN-013）

- 最新公開基線為 `0.8.3-preview.1`／`74eccabc2e3d8e3ee24e9a7e053327948143c3d4`；PR #20 所列 VPS39589656964、Android39589656970、Mac39589656938 的九項均 SUCCESS。開工另查 main `cba901d187aa5312cc71f5c2440b23fa04104aee`／39536090788 attempt2，build119016886469 與 device119016884592 SUCCESS，FEATURE FREEZE=OFF。
- 使用者要求持續開發直到穩定與功能齊全。維持 VPS → Mac → Android；本片補 WISH-CRAWL-001 的跨任務／跨輪次共同 origin 冷卻，不把完整爬蟲、未知站點、Reflection 或原願望清單提前驗收。
- 已用原 production caller／真 TLS 重現：同站第一任務收到429／Retry-After120秒，第二任務仍立刻連線。新增本機 `research_origin_cooldowns`，在同一個 SQLite transaction 先查共同等待，再保留每輪／排程每日 HTTP 用量。429／502／503／504 的等待會跨新輪、程序重啟及日期保存；同站後續任務不發 HTTP、不扣額、不增加 attempts 或來源失敗證據，其他 origin 繼續執行。
- schema20 是 additive migration；啟動前一致快照、升級／回復流程保留。共同冷卻不進 metadata sync／portable backup；同步任務紀錄不能在目的端取得執行意圖。超過24h仍停止該任務自動重試，但其他任務不能把共同等待截短為24h；極端數值不溢位或提前重試。晚成功回應不能清掉較新的冷卻。
- 正式研究清單與紀錄新增「等待網站恢復」、下次可連線時間、零 HTTP 用量與暫停說明。沿用原生 dialog、48px按鈕及同一個輪詢，不新增 poller；Mac 內建同源引擎取得後端控制，Mac／Android 原生專用呈現仍未移植。
- 本機新增7個後端情境與原 Retry／Schedule 完整 race 測試40.516s成功，27個發布回歸成功；新增真 Browser14.529s成功。首截圖發現環境缺CJK字型，已補字型，完整Browser／實圖重新驗證；不把缺字截圖當視覺驗收。首完整Go race228.247s／vet已通過；第7個真TLS並行晚成功1.689s成功。完整27 Browser227.077s／339PNG／零failure圖，補CJK後8張新增圖及375／200%原圖已檢查。原expanded workload21.100s成功，PID峰值137,555,968 bytes、待機73,404,416 bytes；非目標VPS或整機成本，不將波動冒稱RAM優化。
- 當前 CANDIDATE；精確最終SHA／CI／實圖見 PR #20。原26 Browser／331圖、全部原生測試、負載與timeout保留，追加第27流程及8圖。原九項候選cd5ac20的CI仍在跑時，審查補上numeric port正規化（00443同443、08443同8443）與無效port拒絕，最終HEAD必須重新九項，不借cd5結果。PR維持Draft、不merge；沒有目標VPS存取資料，未遠端部署。完整願望／P8–P10仍PARTIAL。

- 真匿名Kilo測試19.386s成功：實際0價格catalog檢查、聊天、工具呼叫／結果接續、Assistant pending→共用Review→持久membership，6次HTTP；只測試可用性，不證明身份／台灣資格／recurring quota。

- 審查後本機Retry／Schedule及7個origin情境37.872s成功；準備0.8.4-preview.1（Mac18／Android8005），最終來源九項與實際三平台發布仍須獨立驗證。c77bcb9只是port候選；新版本不覆寫既有0.8.3。真公開研究本機28.028s失敗：DNS-pinned public client在此環境DNS不可用，7次public admission僅失敗線索／evidence、無成功來源；保留診斷，不改SSRF／proxy政策換通過，需另在原生網路驗證。

以下保留已交付切片與原始失敗證據。

## 2026-10-21 新對話確認與防重複（VPS-CONVERSATION-RECOVERY-012）

- 使用者繼續要求VPS穩定／好用／UI好看。前片0.8.2-preview.1已公開：來源3d7629a776634108fcfb24205546a89f18c4236b，release419422579／11附件；發布39583272110／119165885169 SUCCESS。VPS39583278053四項、Android39583277869兩項、Mac39583277935三項首次同SHA全SUCCESS；真公開11檔／兩Linux各28內檔／實際AMD64 CLI與服務、schema19／vault／認證／手機UI及正式下載器已重驗。manifest SHA256 5c28116dbc1759cb8897571c7ba831ca17028b88c44d0a049ed91aa1f25cafdc，舊版本不變。
- 唯一新片VPS-CONVERSATION-RECOVERY-012：新對話確認後立即可用、清單只讀恢復、失敗草稿／附件／工具保留、晚回應scope guard與HTTP傳輸重送防重複。維持VPS第一、Mac第二、Android第三；原28項完整願望／P8–P10不提前勾選。PR #20維持Draft、不merge，未有目標VPS存取資料、未遠端部署。
- 本機先以原production caller重現2.083s失敗：POST實際commit後GET503，UI仍指向舊對話。再捕捉到Chrome在回應header前斷線時自行重送POST，原無請求識別會重複寫入。此為實際產品缺口，不把測試假設或文件當修復。
- addSession接受可選UUIDv4 client_request_id，映射獨立r-主鍵；SQLite原唯一鍵及ON CONFLICT使相同請求跨並發／重啟只寫一筆，scope／title不符或已封存拒絕409，原歷史／metadata不覆寫。無新表或遷移，舊客戶端不傳識別仍各建立新ID；此保護不延伸為自動推論重試。
- 網頁每個明確操作產生一次識別，已ack的Session先呈現，之後只讀清單；未ack不宣稱未commit。錯誤提示按scope保存，重試只有GET；新建立不再重讀無關工作區metadata。使用者選擇對話會使舊清單ticket失效，晚到A建立也不清B草稿。
- 新6項後端race測試含8路並行、跨scope／title拒絕、重開DB／歷史／pin與封存、非法UUID及舊客戶端；6項5.908s PASS。新真Browser流程14.811s通過：503仍能一次明確TLS推論、草稿／PDF／工具／設定、同scope選擇／跨帳戶晚讀、部分body／header前斷線、busy重複呼叫及真模型隱藏後的晚A commit；5次明確建立意圖僅4筆新對話（另1次拒絕），9實圖。
- 完整本機26 Browser在原五分鐘上限229.230s成功，331 PNG／零failure圖；27發布回歸PASS。新增9圖已檢查手機／深色／200%與輸入可達性；完整Go race及新HEAD九項另跑，不把尚未完成當成功。
- 完整Go race164.450s／vet通過。實圖另發現錯誤提示時桌面flex子項縮窄，補明確寬度及320／375／1280的提示卡與聊天卡左右邊緣回歸；修後新增流程14.603s成功。最終完整Browser及新HEAD CI仍重新驗，保留修前實圖／失敗紀錄，不把前一版面全套結果冒充本修正。
- 準備0.8.3-preview.1（Mac17／Android8004）；保留原25 Browser／322圖，預期26／331及原五分鐘上限、27發布回歸、全部工作量。完整本機／新SHA自己的九項與實際發布前仍CANDIDATE，最終SHA／run／job／附件見PR #20／manifest，不借前片綠燈。

以下保留前片及歷史證據。

## 2026-10-21 VPS 日常工作區恢復（VPS-WORKSPACE-RECOVERY-011）

- 使用者要求VPS穩定能用、好用且UI好看；依D-014維持VPS第一、Mac第二、Android第三。唯一新片是工作區載入／離線／讀取失敗恢復及手機操作，不疊研究或原生UI新功能。PR #20保持Draft、不merge，尚無目標VPS存取資料，未部署主機。
- 基線8871f542e83a721f03b4e563df70de9962372eb4已自己的九項SUCCESS：VPS39542238271、Android39542238339、Mac39542238333 attempt2；27發布回歸及真套件重驗成功。Intel首輪既有縮窗失敗仍保留，不假稱根因已修。開工重查main cba901d187aa5312cc71f5c2440b23fa04104aee／39536090788 attempt2兩項SUCCESS，FEATURE FREEZE=OFF。
- 正式caller是瀏覽器api／refreshWorkspaceSnapshot／refresh與工作區狀態卡：JSON GET涵蓋body的20秒期限；並行刷新合併、期間變更要求下一個新快照、失敗取消同批GET；資料／草稿／scope保留，online／visibility／pageshow只讀恢復。POST／推論串流無新增期限、不自動重送，無新poller、無schema變更。
- 首次資料未到前保留導航／外觀操作，避免空白composer；核心資料取得即呈現，設定未取得前避免預設值誤寫。新真production Browser驗證503／部分body中斷、提交中的POST、併發後新快照、離線／恢復及草稿／焦點，新增11圖；保留全部原24流程／311圖，預期25流程／322圖及原五分鐘上限。
- 上輪本機完整Browser287.442s有24／25通過，原Workspace在重新載入後Settings→返回遇初始pane被隱藏，屬真回歸；改只隱藏空chat-card並即時呈現核心資料，保留原測試／斷言／timeout。環境重建後從8871還原本片，完整測試重新執行，未把失敗或消失的本機輸出當作成功。
- 環境還原後完整Go race 220.716s／vet、27發布回歸、兩項Workspace焦點回歸10.817s通過；完整25個Browser在原五分鐘上限218.933s成功，322 PNG、零failure圖，新增11實圖已逐張檢視。原載入回歸與首輪失敗仍照上節保留，不改原測試或降低門檻。
- 準備三平台0.8.2-preview.1（Mac16／Android8003）；新HEAD須自己的九項、27發布回歸、全部套件與實際公開下載後才驗收。當前CANDIDATE，精確SHA／run／job／附件與最終本機結果記於PR #20及manifest。前0.8.1與更舊tag／附件不修改；完整願望／P8–P10仍PARTIAL，不提前勾選原28項。

以下保留前片與歷史證據。

## 2026-10-21 VPS 更新來源驗證（VPS-UPDATE-PROVENANCE-010）

- 使用者續要求優化 VPS。本片只補後續三平台更新發布的產物來源驗證；0.8.1-preview.1 及其 tag／11附件保持原值，不重發為另一個來源、不修改應用程式／DB／排程。原願望與已驗功能保留，PR #20 維持 Draft、不 merge、未部署目標主機。
- 開工重查 main cba901d187aa5312cc71f5c2440b23fa04104aee、39536090788 attempt2 SUCCESS，FEATURE FREEZE=OFF。前 controller ffbb697 已自己的 VPS39536170156／Android39536170293／Mac39536170097 attempt2 九項成功；Mac 首次 Intel composer 縮窗失敗仍追蹤，未假稱根因已修。
- 真 GitHub metadata 顯示 Mac39536170097 同時留有失敗 QA12318516718 及成功重跑 QA12317604113；失敗檔 ID 反而較大。舊 dictionary 選取在24種回傳排序中12種誤選。本片依固定 workflow producer、成功 job 的原始執行時間窗、run ID／head SHA，要求每種產物唯一符合；保留未重跑成功 build 的原始時間。缺失／歧義／錯來源／過期／缺時間／digest 停止，不猜最新，也不刪失敗證據。
- 正式 caller release/prepare.py → collect／select_artifacts，manifest 新增 job source／time 與 artifact producer／created_at；原9項成功、全部套件內外hash／實際binary／版本／簽署及11附件 gate 保留。GitHub 未直接回傳 artifact job ID，時間窗為固定 workflow 宣告之驗證約束，不宣稱額外的簽章證明。
- 新增9項真公開 metadata 與拒絕路徑回歸，連原18項共27項本機通過；新 HEAD 仍須自己的九項與 release-checks，全綠前 CANDIDATE。最終 SHA／run／job／實際套件重驗記於 PR #20，不用前片綠燈或文件快照代替。沒有 release/request.json 變更，本片不自動另發應用程式版本。

以下為先前已交付切片與歷史證據。

## 2026-10-21 發布讀回修正與已驗應用程式（同一 VPS-RESEARCH-RETRY-009）

- **0.8.1-preview.1已公開**：release419090377，tag固定d12ee55，11附件完整校驗。39533824607 attempt2的publish119011175699／release-checks119011177148 SUCCESS，真公開VPS下載／27檔hash／CLI bundle-check成功；本機另在受限root環境以新下載器重驗成功。RELEASE-MANIFEST.json SHA256 4b63b3ee10e8ec63fdb280f8768ddc397bf3140f7c4574a374c9ebd8d2f89a38。首attempt失敗保留，不當成功。README／發布與Mac說明更新為此實際公開版本；手動解壓指令也不還原CI uid/gid。

- 應用程式來源 `d12ee55b660979dbc84557cd5f6fe532313094ea` 已同SHA九項SUCCESS：VPS39533829322（119005709936／119005710209／119005710289／119005710317）、Android39533829316（119005709994／119005709673）、Mac39533829348（119005710026／119007434866／119007434965）。完整race238.007s／vet、真systemd40.364s、24 Browser218.477s／311 PNG通過；8張新CI實圖已下載核對artifact digest並逐張檢視。Android真XML976／0 failure／0 error／5原skip；Mac82核心／每架構76靜態圖與14frame的全部原門檻保留。
- 本機重試原五分鐘Browser全集，無其他測試同時競爭後24個流程270.858s成功；先前300s逾時仍保留，不刪測試、不放寬上限。CI原expanded workload不減，PID待機23,515,136／峰值204,873,728 bytes；排除OS／proxy／browser，非目標VPS實測。main89e84c83b5ad5d9ee893f451041df6c712efd705僅RELEASES.md的一行版本線，39533551770兩項成功。
- 完整三平台產物校驗已在發布39533824607成功；首publish119006459484在建立新draft後的首次collection讀回拒絕（created draft identity mismatch），沒有公開、不當成功。稍後真API確認draft419090377仍未公開、0附件、prerelease=true、target與實際tag均固定上述d12ee55。這支持「新draft讀回尚未穩定」的判斷，未證明當時哪個欄位暫時不可見。
- 本片只補發布控制：新draft建立後最多6次唯讀查詢，額外backoff合計最多23秒；絕不重複建立、不放寬draft／來源／9 CI／全部hash檢查。既有draft沿用已驗release ID，仍在上傳後重驗。新增延遲可見／上限、完整fresh-draft生命周期、錯來源零upload三項回歸，共18項PASS；應用程式Go／Swift／Kotlin及已驗套件來源不變。
- 原D12發布只重跑失敗job，在同一來源／九項及原完整產物重驗後續行，不改tag或假借controller SHA。修正後controller HEAD仍獨立重跑自己的九項與18發布回歸，結果見PR #20；其綠燈不能由D12挪用。未修改release/request.json，因此不會把同tag重發為另一個應用程式來源。詳細可見verification/20261021/release-publication-readback.md。
- 公開0.8.0來源44c8cd1及11個附件保持原hash。完整願望／P8–P10與目標VPS部署／實機限制仍照下節，不因此勾選全部功能。保持VPS第一、Mac第二、Android第三；PR未merge。

以下保留應用程式切片與過程證據。


## 2026-10-21 VPS 公開來源延後重試（VPS-RESEARCH-RETRY-009）

- 前片三平台發布已完成：`v0.8.0-preview.1` 固定應用程式來源 `44c8cd1003a286ccecc47fb654a44414311065f7`、11個附件。恢復發布39466069760／118777690886成功，重驗全部原雜湊後公開並通過真HTTPS下載。原失敗publish保留為失敗，不覆寫tag／附件。發布控制修正 `3219ecfaa5d72926f97056f630aa7682c0768976` 另獨立九項SUCCESS：VPS39465895100（118777082597／118777082921／118777082682／118777082863）、Android39465895070（118777079841／118777080150）、Mac39465895102（118777079519／118778946308／118778946358）；15發布回歸成功。兩個SHA用途不同，不互借結果。
- 使用者再次續行，按 VPS 第一、Mac 第二、Android 第三接唯一新片 **VPS-RESEARCH-RETRY-009**：公開來源GET遇429／502／503／504時最多兩次自動延後重試，尊重Retry-After、持久等待／紀錄、每日／每輪HTTP admission、暫停／取消／來源確認及同步不執行邊界。既有全部歷史／工作量／測試／timeout不減；完整WISH-CRAWL-001仍PARTIAL。
- 重試以單一公開任務及其預算接續鏈為單位；30／60秒加固定0–5秒分散，不阻塞worker。Retry-After秒數／HTTP-date為等待下限，超過24h停止自動重試、不截短後提前請求。等待／成功後保留真HTTP狀態與時間，不保存遠端錯誤body／原始header。使用既有result_json持久紀錄，schema仍19，不放寬同步或執行意圖。
- 正式研究UI顯示等待、恢復／停止原因、兩次失敗的完整紀錄及手動更新；新增真TLS 429→502→200 Browser流程與320／375／1280深淺色／200%實圖。既有23個Browser流程與303圖全部保留。候選需新HEAD自己的四VPS＋兩Android＋三Mac及完整三平台產物檢查，不借3219ecf綠燈；PR #20維持Draft，不merge、不部署。
- 真公開0.8.0套件下載在目前root user namespace遇tar還原CI uid1001失敗；改為以執行者解壓，不沿用建置者uid/gid。修後真HTTPS下載、外層與27檔內層hash及amd64 CLI bundle-check成功，原0.8.0附件雜湊未變。此為前片已交付下載器的必要相容性修復，同本次維護版本交付。
- 尚缺跨任務／跨新週期的共同origin冷卻、Brave／AA分頁的逐頁持久重試、推論／費用／逐憑證quota、地區／並行控制與更完整重驗策略。Key推論／私人catalog不隱式重放；未知站點、可量測Reflection、authenticity、原生排程UI、目標主機與實機／真站長跑保留，不提前勾選原28項。沒有實際目標VPS存取資料，未遠端部署。
- 本機完整Go race 326.070s、vet、15發布回歸、9個新後端測試（含真TLS／重啟／同步及中斷上限保留歷史）及新增Browser流程已通過；Browser另驗刷新503保留last-good、關閉後晚回應不重開，以及成功任務不顯示無效恢復表單。完整Browser／本片同SHA CI仍待完成；本機曾先因缺Chromium而不能啟動，已安裝固定1194及中文字型，未當成產品測試通過。
- 首候選974104b因README與main首頁更新衝突而沒有PR必要CI；依GitHub正式規則先整合main文件並保留本片README內容，不合併PR。新候選重新要求自己的九項。末次本機完整Browser在原5分鐘上限於既有SystemStatus開DB階段逾時；新增流程及前面全部測試通過，但不當作全套成功、不延長timeout／刪測試，待同SHA完整CI驗證。\n- 本片準備0.8.1-preview.1三平台維護版；只有同SHA九項、15發布回歸、實際附件完整性及公開下載成功後才算發布，精確SHA／run／job見PR #20及公開manifest。首版本0.8.0持續可下載。

以下為前片歷史，最新驗收與唯一切片以本節為準。


## 2026-10-20 新優先序與三平台交付（VPS-RELEASE-008）

使用者最新指示 **VPS 第一、Mac 第二、Android 第三**，更新公開介紹，Release 要涵蓋三平台（D-014）。本片整合已驗 Mac `e408262817b7f4d9d70550cb15f828f9a0e21eb0` 的完整 macos/ 子樹與其兩份相容性文件到 VPS `50a08a95038f2424f2d6791c9d4acf225fb0b080` 基線，保留較新的 VPS／Android source、所有歷史與原測試，不以較舊 Mac 分支覆蓋 VPS 改善。兩個舊分支的原 CI 不代替整合 HEAD 的九項。

- 首整合候選 `9ead23760dd91afd9c1994301678a48caf8c77fc` 未驗收：Android build job118750127913 的 Gradle／unit／APKs 成功，新增簽章 metadata 假定舊 Signer #1 格式，未涵蓋官方 v3.1 的 minSdkVersion／maxSdkVersion 標籤，故保守拒絕並停止發布。修正解析，仍須單一實際簽署身分、全 SDK 區段同一 cert SHA256，拒絕多憑證／stamp-only／截斷 hash；新增兩項回歸，新 HEAD 重新全部九項，不略過 APK／簽章檢查。README 與公開 main 介紹一致，避免未合併碼線與下載的混淆。
- 第二候選 `be2605f61806a9db8b35e9bdc85f1950bcc54da2` 仍未驗收。真已產出 APK artifact12283255255 的診斷39460480723／118758343958 確認 SDK Build Tools37.0.0 使用 **V2 Signer:**，APK 本身簽章有效；僅補 v3.1 標籤不足。現在改為 `--print-certs-pem` 的標準公開憑證 DER SHA256，與工具指紋交叉比對、仍要求單一簽署身分，拒絕 stamp-only／多憑證／不符 hash。真 APK 39460871203／118759687429 已 PASS，13項回歸保留；39461263594 再用 production verifier 驗真兩個 Linux bundle（各27檔、真 amd64 CLI）及 Universal Mac 內外雜湊／來源／0.8.0 build14。完整診斷及界線見 verification/20261020/three-platform-release-preflight.json；新候選仍重新九項及實際發布，不借此診斷當正式驗收。
- 第三候選 `8d9c68ee11c43ae90db44867e31c57416d144c07` 的真 APK 簽章／DER 指紋已成功，job118762618902 在最低 SDK 資訊拒絕；SDK37 同時把 `sdkVersion` 改為 `minSdkVersion`。修正只接受兩個已知欄位名稱，最低 API26／版本／package／source／APK SHA 全部精確條件不變。39462620307／118765711221 已使用原 APK 的真 source checkout 完整執行 production android-metadata.py 並核對寫出的 JSON，FULL ACTUAL APK METADATA PASS；13回歸與Linux／Mac實套件校驗亦再次通過。新 HEAD 必須再獨立九項與發布，不借舊候選結果。
- 應用程式交付 code `44c8cd1003a286ccecc47fb654a44414311065f7` 已同SHA九項成功：VPS39462900675（118766690848／118766690354／118766690722／118766690752）、Android39462900740（118767714227／118767713685）、Mac39462900663（118767245408／118771100915／118771101006），13發布回歸成功。三平台全部附件及manifest已校驗並上傳到draft418668468，tag固定44c8cd1；原publish job118767677267 在tag查詢入口讀未公開draft時拒絕（404），尚未公開，不當成功。改以包含草稿的releases collection及固定release ID處理；增加草稿／分頁／同tag重複的回歸。恢復發布只允許上述已驗44c8cd1與原tag／全部既有hash，不將發布控制修正當另一個應用程式版本。新controller HEAD仍重新九項，之後新增版本用修正後流程；不改已公開tag或跳過驗證。
- 公開首頁 main `8ad2633360b138cc584d04210b2166c9b54502aa` 僅 README／RELEASES.md，程式未改；39458441574 attempt2 build118755743129／device118755741309 同 SHA SUCCESS。首輪既有 rapidDoubleToggle 15s 逾時，保留原測試／斷言／timeout，重跑同SHA成功；未假稱已修復此既有間歇性問題。
- README 與 docs/RELEASES.md 改為目前三平台的能力／安裝／更新／簽署與完整功能界線。原 main SHA `5009e0c8` 的 Android run38408176782 build／device 已重查 SUCCESS，FEATURE FREEZE=OFF；未合併 PR。
- 新增單一 release/version.properties；Android 真 APK version／minSdk／apksigner metadata、Mac Info.plist／BuildInfo／內建引擎 commit、VPS RELEASE.json 對齊同一 tag。Android／Mac workflow checkout 明確 PR head，不借 merge-ref。
- Three-platform Release 只在受信任本庫分支 push／手動發布，PR 只檢查。等同 SHA 四 VPS＋兩 Android＋三 Mac 全 SUCCESS，下載固定 run 產物並核對原始 digest、Linux 每檔 hash／ELF／真 CLI、Mac 版本／來源、Android 實際簽章／APK，先 draft 完整上傳並核對 GitHub digest，才公開三平台 prerelease。已公開 tag 不移動／不覆寫。發布完整性回歸另跑，原 workload／測試／timeout 全保留。
- VPS deploy/download.sh 指定 tag、自動選架構，僅下載到全新目錄、外層／內層校驗和 bundle-check；不停止／安裝服務，不執行 shell 載入秘密。發布後另驗真公開 HTTPS 下載。原 update 的備份／預檢／健康／失敗及中斷復原保留。
- 發布與整合的即時驗收以 PR #20 最新 head 九項、Three-platform Release 及公開 RELEASE-MANIFEST.json 為準；未成功時仍候選，不把預期當結果。PR #21 的原 Mac 分支保留，之後從已驗統一來源接續。
- Android 為 debug 簽署預覽 APK，固定正式簽署金鑰尚未設定；Mac 為 ad-hoc 未公證。沒有目標主機存取資料，未部署 VPS；公開排程、研究、自我改進、authenticity、同步分塊／部分原生呈現及原 Part V 28 項仍按原狀態追蹤，不因 Release 全平台就標全部完成。

以下為較舊切片與證據，優先序以本節／D-014 為準。


## 2026-10-20 接續：VPS 公開研究排程（VPS-RESEARCH-SCHEDULE-007）

- 本片 code `b1e0c669ffea48575875e0c16b7da6980aca5552` 已同 SHA 六項 SUCCESS：VPS `39448158004` test `118715496855`／browser `118715496992`／amd64 `118715497341`／arm64 `118715496849`；Android `39448157661` build `118715495693`／device `118715494503`。完整 Go race 231.476s／vet、14 個新增後端情境、真 systemd 41.80s、23 Browser／303圖全部通過；Android 976／0 failure／0 error／5 既有 skip。兩架構 25 檔／18份操作文件／版本／全部內外雜湊與真 amd64 CLI 已核對。服務待機 24,244,224 bytes／峰值 207,597,568 bytes（23.12／197.98 MiB），原完整 workload 未減，排除 OS／proxy／browser；不是目標主機量測。原始 job／artifact／SHA256／完整量測及界線見 `verification/20261020/vps-scheduler-ci-baseline.json`。
- ACCEPTED 限此 VPS 公開研究第一階段的 caller／測試；WISH-CRAWL-001 及完整 P8–P10 仍 PARTIAL。以下新 HEAD／驗收文件提交仍須自己的六項 gate，結果以 PR #20 最新 checks 為準；原 28 個完整驗收 checkbox 未提前勾選。截圖等待成功提示消失，200% 改存完整頁面，避免固定提示／導航在元素裁切裡遮住內容；全部斷言、8圖與原有測試／timeout 保留。
- 前片 VPS-OPS-006 code `034e775fafc5a51af0f62753389508dfed24c739` 已同 SHA 六項 SUCCESS：VPS `39420196524` test `118618941602`／browser `118618940875`／amd64 `118618941243`／arm64 `118618941309`；Android `39420196530` build `118618951927`／device `118618952519`。Android 976 項、0 failure／0 error、5 項既有 skip；完整 Go race／vet、真 systemd 安裝→TLS inference→更新→失敗回復→SIGKILL 接續、22 Browser／295 PNG 與原 expanded workload 均通過。服務程序待機 24,072,192 bytes／峰值 180,957,184 bytes，不含 OS／代理／瀏覽器。兩架構套件內外雜湊／24 個檔案、真 amd64 version／bundle-check 已獨立核對；前片驗收範圍限程式與 CI，尚無目標 VPS，未遠端部署。
- 使用者續行既定完整 VPS 目標；本輪唯一切片為 VPS-RESEARCH-SCHEDULE-007：預設關閉的持久公開資料探索排程、每輪／每日 HTTP 上限、去重、重啟接續、明確停用及正式 UI／紀錄。來源變更需本機確認，遠端研究歷史不能成為可執行工作。metadata sync 保持獨立；不自動推論或花用模型 Key。
- WISH-CRAWL-001 仍 PARTIAL；推論／費用／逐憑證 quota、429／502 自動退避、地區／並行控制及原生生命週期仍待後續完整落地。未知站點 Connector／可量測 Reflection／authenticity 與原願望清單全部保留，不以第一階段提前勾選。
- main 重查仍 `5009e0c8a733f1863adcf9a5b8ef8c450da76d3d`，`38408176782` build／device SUCCESS，FEATURE FREEZE=OFF。新 HEAD 必須重新六項 gate、保留原22 Browser／295圖及完整 workload，再加本片真 TLS／重啟／上限／同步邊界／手機操作。PR #20 保持 Draft，不 merge，沒有主機存取資料不冒充完成部署。

## 前片：VPS 可用性與後續更新（VPS-OPS-006，已驗 code）

- 使用者明確要求「先把vps全部弄好優化好到能夠使用，之後更新也要弄好」。本輪唯一整合切片改為 VPS-OPS-006；原 SYNC-001 與全部願望清單保留，暫不疊加其他平台 UI 或研究功能。
- 前片 code `f83d4a59c36f4c394111bce721ae7c29e4b2fc4d`：VPS `39411965192` 四項、Android `39411965153` build／device 同 SHA SUCCESS；976 項、0 failure／0 error、5 項既有 skip。Mac `e408262817b7f4d9d70550cb15f828f9a0e21eb0` 九項成功保持原狀，不以其結果冒充本片候選。main 重查仍 `5009e0c8a733f1863adcf9a5b8ef8c450da76d3d`／`38408176782` build／device SUCCESS，FEATURE FREEZE=OFF。
- 新增架構完整套件、一次產生且永不暗中替換的私密環境、獨立 systemd 使用者、部署鎖、原子 binary 切換、完整一致 DB 備份、校驗／FK／vault 預檢與只在副本試跑遷移、真認證／版本／儲存健康驗證及自動回復。持久階段日誌處理 SIGKILL／斷電；已確認成功或已還原階段不重播舊快照。更新期間管理／裝置同步寫入與背景工作暫停，失敗 DB／WAL／SHM 全保留。
- Git 平台啟動於 schema 寫入前保存原版快照／manifest，錯 master key、未能備份或較新 schema 提前拒絕；不宣稱控制平台 binary 回退或磁碟持久性。Settings→資料新增版本／狀態、last-good／重試；正式 caller 與指令見 `vps/UPDATE.md`。
- 本機原完整 Go race 已有一輪 PASS（202.362s），新離線操作 race 回歸 PASS；新增真 systemd 安裝／TLS inference／更新／啟動失敗回復／SIGKILL 復原／損壞套件拒絕，須在真正 systemd CI 執行，不能以本機無 systemd 當通過。新 HEAD 六項 gate、原21 Browser／288圖＋新狀態流程／7圖與原 expanded workload 重新驗，未驗前維持 CANDIDATE。
- 尚無目標主機 IP／已部署 URL／登入方式，未遠端部署；不 merge PR，PR #20 維持 Draft。爬蟲排程／未知站點／可量測 Reflection／authenticity 與 Part V 28 項仍 OPEN，不宣稱全部功能完成。

- 首個完整維運候選 `af6184e64510bf12eab1dd22a90cbe0290620cc1`：VPS `39418940266` 的原完整 Go race213.076s／vet及22個Browser／兩架構成功，但真systemd首次安裝失敗（118614980832），因此不是六項全綠。自訂root包含 `/opt/modelatlas` 時連續sed替換會重寫已展開路徑，改為分離marker再展開；保留完整systemd驗證與所有硬化設定。新候選並補已committed主機重啟恢復／不重播新寫入、缺DB拒絕空初始化，以及SIGKILL後子程序釋放部署鎖的真等待；完整六項重新驗，不以修前綠燈借驗。


## 2026-10-20 認證角色補強（SYNC-001 同片新候選）

- 交付前稽核發現原生Onboarding的CredentialEntity預設authMode為UNKNOWN，真實PAT／自訂Header角色存在已commit的ProviderManualConfig。只看row.authMode會錯誤匯出INFERENCE／Bearer。新候選改讀完整typed配置，ACCOUNT固定對應ACCOUNT_PAT；複合／自訂認證完整保留原格式，不部分降級。舊候選錯誤的共用Bearer投影會停用，保留來源原生配置與vault；不再傳送該不完整投影的秘密。
- 加入三項原生回歸（角色／配置編輯、舊投影停用與來源保留、mapper升級只重送一次），並擴充真Go↔兩原生端驗證PAT秘密重新加密／ACCOUNT角色／真API拒絕用PAT建立推論連線。原973項與全部斷言保留，預期976項；新HEAD各自完整6／9項與報告才驗收。上述c9d1d33／f67cfa4仍只是前一code基線，不借作本候選結果。

## 2026-10-20 最新已驗：跨平台同步核心（整體需求仍 PARTIAL）

- VPS／Android code `c9d1d33a80dda1d7fac4c63400f3dd2c1752960e`：VPS `39406475102` 四項、Android `39406475172` build／device 同 SHA SUCCESS。Mac code `f67cfa42ed6ffc0fa810d41a197edd5ad98b8d80`：Mac `39406793091` 三項、VPS `39406793037` 四項、Android `39406793040` 兩項同 SHA **九項 SUCCESS**。精確 job、artifact、SHA256、已驗與限制見 `docs/verification/20261020/sync-ci-baseline.json`。
- Android 973 項、0 failure／0 error、5 項既有 API skip；Mac 0.7.0 build13，兩架構各82核心／76靜態圖／14frame；152靜態圖已以contact sheets檢視，新增同步4圖以原解析度核對。21 Browser／288圖與新同步7圖保留。Mac實際CI為macOS15，不能外推實體M2／最低系統的完整互動。
- 已驗一次性配對／逐裝置撤銷、目的端 vault／Keychain 重新加密、離線增量與雙版本衝突、原始原生ID、帳號／origin隔離、匿名／成員移除、4MiB歷史／Unicode與重啟接續。真App背景同步與手動操作的409競態已有先失敗再成功的真TLS回歸，取消不遺留等待工作。
- 同步核心不等於全部原生功能互通：尚無同義原生呈現的資料完整保存；超過單筆32MiB仍需分塊；目標VPS／SharpR8s／iPhoneSafari／實體M2未驗。唯一追蹤切片仍為SYNC-001，先收斂這些對應與實際部署界線；原Part V的28項仍OPEN，不能將爬蟲排程／未知站點／Reflection／authenticity提前勾選完成。
- main本輪重查仍`5009e0c8a733f1863adcf9a5b8ef8c450da76d3d`，既有`38408176782` build／device SUCCESS；不合併PR、不部署。本次僅整理驗收文件，新的文件HEAD另跑完整必要CI，最新結果見PR #20／#21，不借code基線冒充新HEAD。

以下保留本片修復與前片歷史，不能取代以上最新範圍。

## 2026-10-20 同步背景／手動競態修復（同片候選）

- Mac `f0f6e802` 的 Universal build SUCCESS；`39404935751` arm64 `118570387444`／Intel `118570387783` 在新增真 packaged engine 同步測試都回 HTTP 409，因此不是九項全綠、未驗收。實際 App 的配對會 wake 真背景同步，緊接的手動同步因 TryLock 直接失敗；原 Go 測試未啟動該生命週期，不能以其綠燈掩蓋。
- 補真 TLS 背景傳輸 barrier 的回歸測試，原程式確實失敗（手動同步在背景未完成時回 409）；改成可取消、有界等待的互斥，手動配對／同步／解除連線／衝突選擇會接續正在執行的工作。背景仍不重入；取消等待不消耗鎖。修後本機 Workspace race 全套 11.850s PASS、vet PASS，原測試／時間／工作量全部保留。兩分支新 HEAD 各自重新完整 gate，Mac 仍待九項與152圖＋28 frame，不稱已完成。

## 最新使用者指示：完成願望清單並優先解決跨平台同步（2026-10-19）

- 2026-10-20 code `6ec46624f51e4bc4562e9decdff985eb876ead60` 已六項同 SHA SUCCESS：VPS `39403490771` 四項、Android `39403490790` build `118563699088`／device `118563698831`；973 項、0 failure／0 error、5 項既有 API skip。新增第二台原生端、精確原生 Offering／Availability ID、跨帳號 origin、匿名與 scoped 移除全部通過。21 Browser／288 圖成功，新同步 7 圖已檢查，原 workload 峰值 180,740,096 bytes。原始證據見 `vps/verification/20261020/6ec4662-sync-baseline.json`；新文件 HEAD 另驗。Mac `f0f6e802`／PR #21 正在九項整合驗證，不借本基線，完整願望清單仍未完成。
- `2220229f4b8c6102dc705ab4587e46e2ad9ee58e` 已六項同 SHA SUCCESS：VPS `39402144371` 四項、Android `39402144405` build `118559196703`／device-tests `118559196220`。正式 Go HTTPS↔原生 Room／Keystore 的雙向編輯、4 MiB 歷史、衝突與撤銷已通過。接續同片補原生→中心→第二原生裝置、同名 Key 的不同帳號／origin、匿名連線與 scoped membership 移除；新候選仍重新完整 gate，不借用本基線。
- 2026-10-20 接續核對：`fede52769a7cd76ae579d1097af3dd9739ffa7c7` 的 VPS `39356955335` 四項 SUCCESS、Android `39356955363` build SUCCESS／device-tests FAILURE（971 項、5 項既有 API skip、1 項真 Go↔Android 同步失敗）。Room migration 全部通過；憑證投影因 framework UPDATE 未引用 SQL 保留欄位 `group` 而失敗。本次修正引用欄位、補原生 insert/update/null/空字串回歸，投影失敗只保留不含參數／例外訊息的程式位置。新 SHA 必須重新六項 gate，仍不稱 SYNC-001 已驗收。
- 使用者已授權實作 Part V 全部需求，並明確要求解決 Mac／VPS／Android 資料同步。先處理唯一整合切片 **SYNC-001**，其後依序接研究排程、未知站點解析、可量測 Reflection 與 authenticity；不在只寫文件後結束。
- 同步採可選 VPS 中心＋各端本機離線資料、版本／游標增量、持久變更與明確衝突處理；裝置獨立配對／撤銷，Key 經認證 HTTPS 傳送並在目的端 vault 重新加密。秘密不進模型、log、同步 metadata 或 Git。
- 基準 `2f0af8296f3996bc83b1fa42b9596a61a2e79410`：VPS `39266118313` 四項、Android `39266118493` build／device 同 SHA SUCCESS。main 已重驗仍 `5009e0c8a733f1863adcf9a5b8ef8c450da76d3d`，`38408176782` build／device SUCCESS；FEATURE FREEZE=OFF。
- 最新授權允許同步所需的 Android／Mac 功能接線；保留原 UI／功能優點及兩分支既有修改，不合併／部署。新切片有 production caller、測試與同 SHA 必要 CI 前不升 ACCEPTED；實體裝置／目標主機未取得的驗收範圍如實保留。

## 最新使用者指示：補回原願望清單（2026-10-19）

- 本輪為文件整理：將完整功能盤點後的持續發現排程、未知站點解析／Connector、研究改進閉環、模型真實性與真站／跨平台／手機完整驗收，加入原 `ASTRA_MASTER_SPEC.md` Part V，保留全部原文與既有需求。
- 新增 WISH-CRAWL-001／002、WISH-REFL-001、WISH-AUTH-001、WISH-ACCEPT-001；全部 **OPEN：待完成／待驗收**，`PRODUCT_SPEC.md` 已接引用，`TRACEABILITY.md` 登錄正式缺口。未開新的 production 功能切片。
- 盤點及前片已驗基準 code `ae0849294fdcf4f48c0cf549de797e59afd53ecb`：VPS `39258806970` 四項、Android `39258806978` build／device-tests 同 SHA SUCCESS，20 Browser／281 PNG 已自行檢查；main 仍 `5009e0c8a733f1863adcf9a5b8ef8c450da76d3d`。本次文件 HEAD CI 另查 PR #20，不借用 code 基準宣稱新 HEAD 全綠。
- 完整 Master Spec 及新增五項均未整體驗收；PR #20 維持 Draft，Mac／Android 原狀態與暫停順序不變。後續實作依本檔單一切片與同 SHA gate 接續；本輪不 merge／部署。

## 前片 VPS-MODEL-WORKFLOW-005

更新：2026-10-19；唯一 live 切片 **VPS-MODEL-WORKFLOW-005**。使用者要求「新增一些功能以及功能優化及 UI 優化」；接續 VPS 的模型查找→比較→使用流程，不轉 Android。

- 前片 code `b7444fe9be8f6a397521422aa40e340a908807ca` 已確認 [VPS 39253363969](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/39253363969) 四項與 [Android 39253363984](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/39253363984) 兩項同 SHA SUCCESS。19 Browser／263 PNG 已自行檢視；原 expanded workload 服務峰值 208,445,440 bytes、待機 26,345,472 bytes，不含 OS／代理／瀏覽器。原始證據見 `vps/verification/20261019/b7444fe-baseline.json`，只代表該 code SHA。
- 本輪再確認 main `5009e0c8a733f1863adcf9a5b8ef8c450da76d3d` 的 build／device-tests SUCCESS；FEATURE FREEZE=OFF。
- 本片新增明確儲存／更新／刪除的瀏覽器本機模型檢視、可逐項移除的篩選條件、最近使用／追蹤快捷鍵，以及完整 scope 的連線／比較結果直達 Playground；沿用原 picker change path，切換不自動 inference／不新增對話，保留各對話草稿與歷史。正式 caller、限制與驗證見 `vps/MODEL_VIEWS.md`。
- 新 HEAD 仍為候選，須六項同 SHA SUCCESS、原十九 Browser 加新增第二十流程／實圖及原 expanded workload 才驗收；自行 review，無獨立外部 reviewer。PR #20 保持 Draft，不 merge、不冒充已遠端部署。Mac／Android 與完整 Master Spec 的 PARTIAL 狀態不變。

## 前片 VPS-MOBILE-ALIGN-004（已驗 code，較新 HEAD 另驗）

- `b7444fe9be8f6a397521422aa40e340a908807ca` 同 SHA 六項 SUCCESS／十九 Browser／263 PNG 已驗；手機頁面／分頁／表單與五種 dialog 的共用對齊、48px 操作、200% 大字與直橫安全區見 `vps/MOBILE_ALIGNMENT.md`。
- 驗證是 Chromium mobile／touch／safe-area override，不代表實體 iPhone／Safari／軟體鍵盤測試；不 merge、不遠端部署。

## 前片 VPS-MANAGEMENT-UX-003（已驗 code，較新 HEAD 另驗）

- 前片 code `b3c16e95b29a61d93d53a945fea78aa80a7dc31f` 已確認 [VPS 39218809855](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/39218809855) 四項與 [Android 39218809822](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/39218809822) 兩項同 SHA SUCCESS。17 Browser／134 PNG 已自行檢視，原 expanded workload 服務峰值 208,769,024 bytes、待機 26,451,968 bytes；不含 OS／瀏覽器，沒有獨立外部 reviewer。原始證據見 `vps/verification/20261019/b3c16-baseline.json`，只代表該 code SHA。
- 本片正式流程及界線見 `vps/MODEL_CONNECTIONS.md`。模型概覽直接列同 Canonical Model 各家完整 scope 的本機速度／上線率／樣本與明確比較；Providers 新增今日簽到進度、last-good／重試、晚 GET 不撤銷完成回條及跨日保護。
- 新 HEAD 仍為候選，須六項同 SHA SUCCESS、原十七 Browser 加新增第十八流程／實圖及原 expanded workload 才驗收；PR #20 保持 Draft，不 merge、不冒充已遠端部署。Mac／Android 與完整 Master Spec 的 PARTIAL 狀態不變。

## 前片 VPS-MAC-PARITY-002（已驗 code，較新 HEAD 另驗）

- 已交付 VPS `3aec65729b9daf5db7ac5629ecca69df4ef94afc`，VPS `39188438480` 四項及 Android `39188438452` 兩項同 SHA SUCCESS，16 Browser／124 PNG 已驗。這是第一輪對齊，不能宣稱完整 Mac parity。
- 固定 Mac 參考 `74e2c378c1117fda3bebdb9f8d50ead872aa5af7`／0.6.1 build12，使用獨立唯讀工作樹；原始五份 staged Mac 檔與暫停 Android UI 不動。
- 本輪重新確認 main `5009e0c8a733f1863adcf9a5b8ef8c450da76d3d`／`38408176782`，build `115333198754`、device `115333198701` SUCCESS；FEATURE FREEZE=OFF。
- 本片補導航／完整連線對話搜尋與草稿、帳戶／Key 獨立編輯、Provider Catalog／可讀設定、模型六分區／三來源效能／額度／完整資訊編輯與圖表控制；差異與 caller 見 `vps/MAC_PARITY.md`。
- 使用者追加資源效率與 CF／GitHub 免費部署評估；同片補完整 context 分段 JSON、5 筆輪詢／不重疊及通知 OFF 節流，保留原 expanded workload／六項 gate。架構、受控編碼對照與官方部署限制見 `vps/RESOURCE_HOSTING.md`；不假稱現有 Go／SQLite 可直接在免費 Pages／Workers 跑完整後端。
- 原 16 Browser 語意斷言、全量匯出與 expanded workload 保留；畫面改動只調整真實入口與幾何。新 HEAD 仍須六項同 SHA SUCCESS／實圖檢查才驗收。PR #20 保持 Draft；不 merge、不改 Mac PR base、不冒充已遠端部署。完整 Master Spec 仍 PARTIAL。

## 舊切片歷史（不代表目前優先序與新 HEAD 驗收）

# ModelAtlas Public — ACTIVE TASK

更新：2026-10-15（VPS 獨立分支），公開 repo `Tsenjii/ModelAtlas-2`。

## Live state
- **VPS 當前唯一切片：Git 平台部署相容性。** 使用者部署 Infrlo 並要求自行查核；以 `98bedbf0bd9689217c34f433a61d4be1bc437acd` 同 SHA 六項 SUCCESS 為基線（VPS `38753924366`、Android `38753924418`，十四 Browser／99 實圖已驗收），本輪重新查核 main `5009e0c8`／`38408176782` build/device SUCCESS。新增可直接使用的 build/run 腳本、明確 opt-in 平台 `PORT`／HTTPS origin／固定 master key／絕對 DB 設定與入口 guard、啟動失敗非零退出及 SIGTERM 收尾；真獨立程序＋HTTPS proxy＋SQLite/vault reopen 驗證。官方 Infrlo README 未提供 runtime／ingress／volume 契約，控制台在本環境顯示 Site Unavailable，不能冒充平台已確認／已遠端部署。詳見 `vps/PLATFORM_DEPLOY.md`；新 HEAD 六項 CI 全綠前為候選，下方 Provider 候選列為歷史。
- **Provider 錯誤遮擋實圖修復：** `fe65c24`／VPS `38753014920` 四項與十四Browser成功（含設定固定競態回歸）；99張實圖人工檢查發現Key JSON錯誤可能被sticky dialog-bar遮住。錯誤改捲到可見中央，新增account／Key JSON／Key503／config全部須在sticky標題下且dialog內的幾何斷言；375／1280／200%直接透過真invalid JSON submit拍錯誤，不用測試捲動代替產品行為。原斷言與workload不減，新HEAD重新六項與實圖；不以fe65的部分結果驗收最新HEAD。
- **設定草稿競態（本片必要回歸修復）：** `db21a33`／VPS `38751952139` Provider第十四流程與其他十二流程全過，既有workspace抓到timezone草稿UTC被慢`loadStatus`改回Asia/Taipei。改正式設定表單逐欄dirty guard、狀態讀取不再晚寫form、保存revision保護下一份草稿；同workflow加入真status回應／真settings commit後延遲barrier，原草稿斷言保留，再驗換頁／重開／保存中編輯、SQLite仍只存當次UTC與OFF預設。新HEAD仍六項同SHAgate，沒有以重跑掩蓋競態。
- **Provider 選單 a11y 回歸修復：** `cdc7b0b`／VPS `38750457348` 原十三Browser全過、Go／footprint／雙架構成功，新第十四流程精確選單label查找失敗；`705883d`／`38751411611`的更嚴格完整陣列差異證實空locator，實圖則有兩個選項。原因為包住select的label混入option文字；為帳戶／認證／用途／rotation補固定aria-label，原嚴格完整選項與scope斷言保留。晚回應測試另等待原真form完成finally後才驗證Q未被替換。不是加等待或force click掩蓋失敗；新HEAD重新六項gate。
- **VPS 當前唯一切片：Provider 表單與保存回饋。** 使用者「可以」授權繼續；以 `03307f1e9046f45af2ab52458a8762cd9f987079` 同SHA六項SUCCESS為基線（VPS `38735387218`／Android `38735387179`，十三Browser與實圖已驗收）。main仍`5009e0c8`／`38408176782` SUCCESS。帳戶／Key／端點／rotate／config原地更新、跨表單草稿與展開保留、form內JSON／HTTP錯誤、pending重複提交guard、同名Key帳戶scope、晚完成mutation guard；新增第十四個真Browser，不改schema／Android／API語意。詳見`vps/PROVIDER_FORMS_UI.md`；新HEAD六項同SHA與實圖通過前PR #20維持Draft，下方Catalog候選為歷史紀錄。
- **Catalog 實圖收尾：** `f8a7370`／VPS `38734782608` 十三Browser全過（含原management及真封存fixture）。實圖確認200%原生select會截短網址；補可展開完整帳戶／Key／Group／用途／URL，真文字Range不相交檢查，Catalog總覽截圖收合已驗證篩選區。仍為本片修復，最新HEAD重新六項CI；未借f8的部分綠燈驗收。
- **Catalog 首輪回歸修正：** `4eaf16e`／VPS `38734249142` Go race／vet／footprint／雙架構成功，原11個Browser成功；management因新舊分區同名「篩選模型」產生strict locator歧義，新第13流程fixture的UPDATE未建立封存state而初始化不符。改Catalog標題「篩選 Catalog」，fixture改走真`/models/lifecycle`建立封存；保留原1個active target及全部斷言，不加等待或force click。新HEAD仍重跑六項。
- **VPS 當前唯一切片：Catalog 探索與連線加入。** 基線 `62ac7908e58f6e66daddb5b5fb241d42cd054d7a` 已由 VPS `38711778298` 四項及 Android `38711778305` build/device 同SHA SUCCESS 驗收，十二Browser與實圖已通過。main仍`5009e0c8`／`38408176782`全綠。本片改善型錄搜尋／篩選／已加入狀態、Key／origin-bound endpoint選擇、缺設定引導、可見錯誤重試、manual draft與晚回應guard；新增第十三個真Browser。詳見`vps/CATALOG_UI.md`。新HEAD六項CI與實圖通過前為候選，PR #20維持Draft；下方詳情片候選紀錄為歷史。
- **錯誤可見性修復：** `4e48a09`／VPS `38710997731` 四項及十二Browser全過，standalone idle25.52／peak408.11MiB。實圖抓到初次讀取失敗訊息被append在概覽內容末尾；改prepend到各分區頂端，日期驗證也在表單內用role=alert顯示並捲到可見處，不倚賴dialog外的notice。新增兩個實際viewport可見斷言，新HEAD六項重驗；4e的Android未借作新HEAD驗收。
- **詳情反應性收尾：** 能力／分類保存後原概覽仍持有舊 snapshot；改用真 mutation 回傳值只更新識別／能力區，不重建或清除其他表單。Browser 直接確認 VISION、canonical／category 即時更新與 notes 草稿仍保留。屬本片 UI correctness repair，新 HEAD 重新六項 gate。
- **詳情大字實圖修復：** `cdbb36d`／VPS `38709934831` 四項及十二 Browser 全部 SUCCESS；人工檢視200%供應商欄位時抓到長 label 超出固定窄欄，與值相擠。改用隨字級／空間自動換行成上下排列的 intrinsic grid、繁中推論 Key label，新增每個可見 dt／dd 真文字 Range 相交檢查。不是下個功能 slice，為本片視覺／a11y修復；新HEAD仍六項gate。
- **本片回歸／視覺收尾：** `a7e9273`／VPS `38709192566` Go race／vet／footprint／雙架構 SUCCESS，新增第十二個詳情 Browser 與其他十流程成功；舊 management flow 在概覽尋找已移至設定的備註，補走真分區入口，保留原 notes Review／confirm／Undo 全部斷言。實圖也將主要操作只留概覽、其他分區少一整段重複控制、縮短價格標題，raw endpoint ID 收進識別資料。新 HEAD 仍重新六項 gate，不借部分綠燈。
- **VPS 當前唯一切片：詳情工作區與非同步操作正確性。** 使用者「繼續搞」；以已六項全綠 `c609cf2d65d7c598b5806dd2011ca8e75a45b819` 為基線（VPS `38695626679` 四項；Android `38695626675` build/device SUCCESS）。重新確認 main 仍 `5009e0c8`／`38408176782` SUCCESS。模型與 Provider 詳情分區、可讀價格／來源／分位數、原地時間窗更新與草稿保留、錯誤重試、晚回應／已關閉詳情 guard；原 schema、HTTP scope、Review／Undo、歷史與全部 V1 不變。新增第十二個真 Browser flow；本機 race／vet／JS syntax 已過，**新 HEAD 仍須六項同 SHA CI＋實圖檢查**；PR #20 期間保持 Draft。詳見 `vps/DETAIL_UI.md`；下列較早 slice 失敗與候選為歷史紀錄。
- **前片已驗收：** `c609cf2` 的工作區／聊天／清單／外觀及新對話同步 reset，十一 Browser 全過；Android gate 兩個必要 regression repair 亦由同 SHA 全套驗證，無需重做。前片 standalone idle 25.21 MiB／peak 376.48 MiB，不含 OS；新片 RAM 仍取本片 Actions workload，不把樣式改動推論為 RSS 降低。沒有 merge／目標 VPS 部署。
- **新對話 reset 時序回歸：** `5613943`／VPS `38694380170` 的 chat-experience 在 Meta+Shift+O 後，session ID 已換但舊「回到最新」仍短暫可見；其他十 Browser 通過。修正式 newConversation 成功後同步清 UI／新標題與閱讀狀態，force follow 立即隱藏按鈕，新段立即採空 system／512／streaming 預設；歷史與舊設定不刪。保留原 immediate hidden 斷言，新增真舊設定保存／立即重設／保留檢查，不改長等待或重跑。Android harness `5613943` 已由 `38694380099` build `116287408298`＋device `116287407961` 同 SHA SUCCESS 驗證；新 UI HEAD 仍須六項 gate，不借前片綠燈。
- **Android gate harness repair（本片必要回歸）：** `0e7acf6` VPS `38692621075` 四項／十一 Browser 全綠，Android `38692621025` build綠、device `116281827382` 的 `CredentialRollbackFlowTest.enablePathUnchanged` 15秒逾時（其餘無失敗、5個既有API29 skip）。保留該失敗報告；查到 harness 在非Main建立／清除VM與呼叫UI操作，且反覆 busy-loop `waitForIdleSync`。改真Main caller／單純 `state.first` 掛起等待、原15秒上限／原T1–T5全部斷言，IO測試旗標用AtomicBoolean，新增四輪真Room＋vault＋blocked/projection對照。只改 instrumentation harness，不改Android production／產品phase；新HEAD仍須六項CI，不將0e部分綠燈或重跑當完整驗收。
- **大字導覽幾何回歸：** `f4b9194`／VPS `38692212749` 前十 Browser 全過，新增第十一 flow 精確抓到 375px／200% 英文 nav label 超過各自 button；保留單字完整，改用有界 responsive 字級與比例 flex／緊湊間距，48px touch 不減；原幾何斷言維持。新 HEAD 重新跑六項。
- **本片視覺 QA 收尾：** `87df330`／VPS `38691391871` 四項與十一 Browser 全綠，真 screenshot 發現 desktop 不同內容卡片高度置中導致頂端不齊、200% 品牌／底 nav 文字拆行，及啟動成功 toast 遮住內容；只作本片樣式／啟動提示修整，新增幾何回歸斷言，仍需新 HEAD 六項全綠，不拿 `87df330` 部分驗收代替。
- **本片回歸處理：** 候選 `6b2e74e`／VPS `38690238103` 發現空 DB 沒 target 時 `loadChat` 提早返回，第一個 Provider CTA 沒顯示；工具／對話設定選單會遮傳送。補空態正式入口、選單完成／focusout／可用空間定位與大字頁面 reflow。Go connector 原測試以整份 facts JSON 搜尋 `999`，會誤判 provenance URL／隨機 TLS port；以真 `/v999/v1` TLS 路徑已穩定重現原斷言 false-positive，改為 exact scope SQL＋JSON rpd=0／tpd=1000000／origin／scope_kind，原 last-good／failed／Undo／Catalog 斷言全保留，不重跑掩蓋。新 HEAD 仍重驗六項。
- **VPS 2026-10-15 頁面重整切片（使用者明確要求改善外觀與功能）：** 基線 `5522c7f67545f7ee8ec11eac4fa77e26519540bf` 同 SHA 六項 SUCCESS：VPS `38686585333`／Android `38686585247`；main `5009e0c8`／`38408176782` 重新確認成功。保留 teal、三主分頁與全部 V1，以原生 HTML/CSS/SVG 重整工作區、卡片、聊天 composer／對話工具、設定分類。新增可鍵盤操作的互斥 tabs、模型快速篩選／完整重設／選取後批次操作、空狀態真入口、Catalog 同 provider/id 顯示名稱、瀏覽器外觀偏好。切換工作區不改 scope／不丟草稿／不自動推論，詳見 `vps/WORKSPACE_UI.md`。目前為本輪候選，必須最新 PR HEAD 六項 checks＋十一個真 Browser 全過並檢查實圖後驗收；下方是已完成歷史，不代表本片已通過。
- **VPS CI 必要相容性修復：** `979a8a6` 的 VPS 四項（含十個 Browser、2,000 deltas僅9次DOM寫入、完整18,250字元）全綠，Android build綠，但 `38685239940`／device `116258469234` 在 h4 測試抓到既有 `AtlasViewModel.requestMyModelRefresh:3167` 的 certified-operations Set 尚未初始化而 NPE（Main.immediate／constructor callback）。不以重跑掩蓋；將兩個 observers init 保持原內容／相對順序移至全部欄位後，新增 h5 真裝置反覆建構／certified endpoint／membership與零inference guard。這是本片 gate 的必要相容性 repair，Android main與產品phase不變；新HEAD仍須完整六項，不借 `979a8a6` 的部分綠燈。
- **VPS 2026-10-15 串流體驗切片：** 使用者授權繼續優化並參考其他專案；以已同 SHA 六項全綠的 `5c616053496f37e1a0af44d3b9614bc6432c3800` 為基線（VPS `38680781659`／Android `38680781661`）。參考 assistant-ui 閱讀位置與 Open WebUI 串流效能，實作原生 DOM batching／閱讀暫停跟隨與回最新／保留未變歷史 DOM／生成中草稿／IME-safe 快捷鍵與真 cancel；無新 production dependency／migration。`vps/CHAT_UX.md` 保存目標、caller、參考及驗收；新增第十個正式 Browser flow，以3,000 Provider deltas＋真 TLS／SQLite 檢查完整文字、DOM writes、閱讀位置、draft、clipboard／107則匯出、鍵盤與 socket cancel。此片驗收需 PR #20 本片 HEAD 的六項 checks 全綠，確切 SHA／run／指標以 PR body 為準；不能借下方舊基線冒充。
- **VPS 較早已驗收 code：** `57ef23e96c335413003b497b06cd99b7b8838b6e`，完整 V1＋對話管理／安全 Markdown／請求收據／明確重試／完整匯出。VPS `38631190515` 四項與 Android `38631190578` 兩項同 SHA SUCCESS；九個正式 Browser workflows 通過。擴充 standalone workload 峰值 **406.36 MiB**，待機 **25.36 MiB**；Kilo 真研究 `38629907160` 成功。交付見 `vps/DELIVERY.md`；最後文件 HEAD 仍查 PR #20 六項 checks。下方較早 VPS 進度為歷史紀錄，不能代替本條。未 merge／未部署，Android source 未改。
- **Android MODE：PLAY-004 繼續剩餘多模態切片**；Audio Input 已由 PR #18 合併並驗收。**VPS MODE：獨立 branch `feature/vps-rebuild-20261014` / PR #20**，不得誤當 Android 的 full-green slice；VPS 仍須另行全部測試。
- **最新 VERIFIED main FULL GREEN：** `5009e0c8a733f1863adcf9a5b8ef8c450da76d3d`，來自已合併 [PR #18](https://github.com/Tsenjii/ModelAtlas-2/pull/18) Audio Input；[main CI 38408176782](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38408176782) **build SUCCESS + device-tests SUCCESS（同 HEAD SHA）**。此 main HEAD 的 FEATURE FREEZE=OFF；下一次任何 main 紅燈即再上鎖。
- **PLAY-004 新對話 [#14](https://github.com/Tsenjii/ModelAtlas-2/issues/14) ACCEPTED：** [PR #16](https://github.com/Tsenjii/ModelAtlas-2/pull/16)／main `069b541a` [CI 38397137563](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38397137563) build/device 全綠。只清當前選定 scope 的 UI/wire history、draft/picker；telemetry、samples、quota、他連線不可刪。
- **PLAY-004 PDF 第一切片 ACCEPTED：** [PR #17](https://github.com/Tsenjii/ModelAtlas-2/pull/17) 候選 `f8984e1` [CI 38401105133](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38401105133) build/device SUCCESS，合併 `d7dd460a` [main 38403442111](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38403442111) build/device SUCCESS。原失敗 [38399650161](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38399650161) 不冒充綠燈。production PDF picker→LocalPdfPayload→VM FILES gate→InferenceEngine→OpenAiTransport 已驗。
- **已驗收音訊輸入片：** 延續現有 `AudioInputPart` 真實 Chat Completions wire，使用 Android `GetContent(audio/*)`，不請求麥克風權限、不錄音；本機 WAV／MP3 signature、4 MiB cap、safe filename、直接 VM 強制再驗，僅同選定 provider/account/credential/model/group/endpoint/operation 已確認的 AUDIO_INPUT 可發。歷史含音訊時也必須重新驗能力；撤銷後 HTTP 前拒發，new conversation 不重播；實際 Room→VM→Engine→HTTP 與 Compose/裝置測試已隨 PR #18/main 同 SHA CI 驗收。
- **PLAY-004 整體仍 PARTIAL**：Audio Input 之外仍缺 Audio Output／轉寫結果、Tool calls/工具結果 UI、Image Generation/Edit 結果展示。之後順序依單一 slice 實際驗收；再進入 P8/P9/P10。
- PK27／MyModels 問題 [PR #15](https://github.com/Tsenjii/ModelAtlas-2/pull/15) 已在 `3925ff9d` [main CI 38392145254](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38392145254) 同 SHA build/device 全綠；本輪後續 main 綠燈亦涵蓋原回歸。
- Public repo 不帶 private repo Git history／任何 raw key/PAT；old private CI 不得當作 public main 的驗收。

## 下一片（唯一生產功能入口）
- **PLAY-004：圖片 Vision [PR #8](https://github.com/Tsenjii/ModelAtlas-2/pull/8)、安全邊界 [PR #9](https://github.com/Tsenjii/ModelAtlas-2/pull/9)、多輪歷史 [PR #13](https://github.com/Tsenjii/ModelAtlas-2/pull/13) 已合併**。修正針對整批待送歷史檢查 Vision 並保留同 scope 能力，若撤銷則 HTTP 前拒絕；主線 PK27 observer crash 已由 PR #15 修復且全綠；新對話 [#14](https://github.com/Tsenjii/ModelAtlas-2/issues/14) 已合併並 main 同 SHA 全綠；PDF Files 已通過 PR #17/main CI，Audio Input 已驗收；後續另片處理音訊輸出／工具／結果 UI，PLAY-004 整體仍 PARTIAL。
- 必須先讀現有 `PlaygroundScreen.kt`、`AtlasViewModel.kt`、`InferenceEngine`、`MultimodalBlocks.kt`、`OpenAiMediaTransports.kt`，確認已存在 transport / state 與實際欠缺。
- 依現有 `AtlasTheme.kt` UI 節奏做增量 Compose，更動不能破壞 text streaming、selected credential、provider scoped telemetry。
- 先設計最小可驗收的單一 multimodal flow；支援輸入/回應真實 wiring、permission 與 size/security guards、取消與錯誤語意、UI+core tests。
- 完整 same-SHA build+device-tests 全綠，獨立 diff review 與 traceability 更新後才能標 PLAY-004 ACCEPTED；此前維持 PARTIAL。

## 後續順序
1. 下一個產品 UI/Intelligence correctness 與已知缺口（包含 NINT-005B，待契約整合）。
2. P8 Global Crawler 的真實 candidate→evidence→verify，quota gate/Taiwan/AA Top4 嚴守。
3. P9 App Assistant 的安全結構化工具、真實 UI 管理能力與 confirmation/undo。
4. P10 版本化、可回退的 provider recipe / research reflection。
5. 所有 phase 逐片保持同 SHA full-green，勿同時把大階段混成一片。

## 未驗收與限制
- 未驗收：PLAY-004 full UI、P8/P9/P10 的全部 production flow。
- X-011 週期背景同步仍停用，因真實 sync runner 尚未接線；不得直接改 `isPeriodicSchedulingEnabled` 為 true。
- 舊 private source 的實際憑證曾入 git 歷史：舊 PAT 仍需由持有人撤銷；不可將舊 Git 歷史推進新 public。


## VPS 獨立維護基線（2026-10-14）

- 舊 PR #19 已關閉，VPS 在 `feature/vps-rebuild-20261014`／[PR #20](https://github.com/Tsenjii/ModelAtlas-2/pull/20) 維護；不合併私人 Git 歷史，也不改 Android main。
- 前一片已驗收 code SHA `9a46804cde0052ae2b65710fba252fe1ee6a2d5f`：[VPS 38508548084](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38508548084) Go race／HTTP／vet、AMD64、ARM64、Browser 四項 SUCCESS；[Android 38508548193](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38508548193) build、device-tests SUCCESS，六項為同一 SHA。PR 曾轉為 Ready。
- 該片 production 入口與修復以 `vps/PARITY.md` 為準；原本缺少的 onboarding、多 endpoint、Vision/PDF/Audio Input、New API／Dedicated／LMSpeed 已有實際 caller 與驗證，舊缺口快照不再代表最新 VPS 狀態。
- 未驗收：Android 整體 PLAY-004、完整 P8／P9／P10；未取得使用者主機存取，沒有真 VPS 遠端部署證據。

## VPS 最新續作（2026-10-14）

使用者本輪指示：檢查既有 VPS 全部程式，修問題並追上目前 Android main。維持 `feature/vps-rebuild-20261014`／PR #20，對照 Android `5009e0c8`；不跳去未合併的 audio-output draft。具體 production 入口、修復與測試及限制以 `vps/PARITY.md` 為新版 VPS 交接，取代上方舊 VPS「仍缺 Vision/PDF/Audio 等」快照。Android 本身的 PLAY-004／P8–P10 尚未驗收狀態不變。

本機 Go race/HTTP + vet 已通過，Browser 交給 `vps-browser` Actions。依最新 PR HEAD 同 SHA build/device＋VPS 四工作結果驗收；未全部成功不合併，任何 regression 直接修復。

Browser 初次回歸已定位並修復（new conversation 前 composer lock），`a938fef` 的 VPS 四 job 全綠。最後 code-audit 已把 Android root 已註冊 media transport、interrupted recovery、資料庫單寫者、exact stats／custom window、stale Undo guard 接線；Final gate 仍查 PR 最新 HEAD，完整結果由 PR body 保存。

## VPS 本次繼續：媒體 Playground 結果切片

2026-10-14 再確認 Android main 仍 `5009e0c8`；使用者指示「繼續」。只新增 VPS 媒體 Playground，不改 Android 已驗收狀態，不疊 P8/P9/P10。

- `ui_js_media.go`：依 immutable endpoint 的 operation 顯示圖片生成／圖片編輯／音訊轉寫；獨立輸入表單、預覽／下載、繁中錯誤、停止；切換 scope 清草稿／附件／結果／Blob。Chat 不接收媒體 operation。
- `media.go`：共用最多四 flight 與 explicit request-id cancel；多行 transcript；完整 PNG/JPEG/WebP 解碼前先查像素，結果全體 4 MiB／4 MiPixels、HTTP JSON 8 MiB；不回傳自訂 secret echo；無效內容不記 SUCCESS。
- 原始媒體／轉寫內容不進 DB／telemetry，結果在頁面記憶體；URL-only 由使用者明確開啟，不自動向外部圖片站發請求或轉送 Key。
- `media_test.go`／Browser production flow 驗證真 TLS caller、三操作、能力撤銷、multipart MIME、零 usage、429、壞／截斷圖片、custom secret、取消與 shared budget。Browser 驗窄屏／深淺色／200% 大字。
- 本片先維持 Draft；新的 HEAD 必須重新通過 Android 兩項與 VPS 四項同 SHA gate；最終 SHA、Actions run、結論由 PR #20 最新 body 保存，不挪用 `9a46804` 結果。
- 下一個缺口仍是音訊輸出與工具互動 UI；本片不宣稱全部 PLAY-004／V1 已完成。

本片首輪 Browser `da00c79`／VPS run `38523911620`：Go test、AMD64、ARM64 已 SUCCESS；Browser 定位動態端點 select 的 label 含 options，無法以精確名稱操作。修正 production `aria-label` 與繁中端點類型名稱，不降低 Browser 精確選擇斷言；新 HEAD 重新跑六項 gate。

第二輪 `d1e2a3b` 的 Browser 已通過新端點操作，定位測試的「加入 My Models」同名按鈕 strict ambiguity；Catalog modal action 改為 `#detail` 範圍內精確選取，保留 strict/exact 斷言，未修改產品預期。仍以新 HEAD 六項 CI gate 驗收。

媒體切片 `36751cf`／VPS run `38524721426` 的四項皆 SUCCESS，Browser 三操作與取消全過；視覺 QA 發現 target 下拉仍顯示內部 Key ID，改用同 provider/account scope 的帳戶與 Key 名稱。補上實際下載 PNG bytes／多行 UTF-8 transcript 的檔案回讀，CSS snapshot 關閉 transition 以取得真正的深淺色定態。新 HEAD 六項仍須重驗。

最後操作 review：媒體「清除結果」改為只釋放結果 Blob，不會一起刪除已輸入描述或本機附件；只有切換 scope 才清全部草稿。Browser 保留輸入的斷言加入同一流程，下載內容與既有三操作已在 `179389e` 的 VPS 四項綠燈驗證，新 HEAD 仍查完整六項 gate。

## VPS 媒體切片最新已驗收基線（2026-10-14）

- Production code SHA：`00f3dc205e4a59057edcf15e2de4a2135b49d4d0`。
- [VPS CI 38526114795](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38526114795)：Go race／HTTP／vet／runtime smoke、AMD64、ARM64、Browser 四項 SUCCESS。
- [Android CI 38526114819](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38526114819)：build、device-tests 兩項 SUCCESS，與上述 VPS 為同一 SHA。裝置共 964 tests、0 failed，5 個通知權限測試依 API 29 條件 skip；不是刪除測試。
- **VPS-MEDIA-UI／SAFE／CANCEL 切片 ACCEPTED**：實際 UI 三操作、PNG／UTF-8 下載回讀、scope reset、結果清除保留輸入、阻塞取消、四 flight、影像驗證／secret echo 已驗。
- Browser 最終 artifact `11875811832`／SHA256 `b65041539532db6018fbe94ae43f1d527731ef92f41948bd503aac247ea32c7e`；已檢查圖片、轉寫、375／1024px、深淺色及 200% 大字畫面。
- 本次末尾提交僅補驗收與交接文件；新 doc HEAD 的完整六項 CI 結果仍以 PR #20 最新 body／GitHub checks 為準，不把 code 基線誤寫成任意較新 HEAD 的結果。
- 下一個 VPS 功能切片：音訊輸出或 tools 互動 UI，依單片新增真 caller／UI／測試後驗收；Android 全體 PLAY-004、完整 P8/P9/P10 仍未 ACCEPTED。VPS 獨立分支維護，無使用者主機遠端部署證據。


## 2026-10-14 使用者最新續作（VPS 全部 V1）

最新明確指示覆蓋之前只追 Android 当前片的範圍：全部完成，可以超過 Android；輕量從單程序／依賴／有界執行處理，不能裁功能。維持 VPS 獨立 branch / PR #20，不直接更動 Android main。

完整差距及 caller／驗收清單：`vps/V1_COMPLETION.md`。進行中的整合片為 VPS Playground 完整 protocol / tool calls / audio output / embedding / rerank。本機 TLS / Go race 與 JS syntax 通過；PR 保持 Draft，最新候選的 Browser／雙架構／Android build+device 同 SHA gate 尚待執行。第一片全綠後繼續 real Research Runner / AA、完整 Assistant / Reflection，再做管理／通知／資源驗收。不要在第一片完成就結束本輪。


VPS Playground 完整片 `29eff56` 已取得 [VPS 38550451082](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38550451082) 四項與 [Android 38550450921](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38550450921) 兩項同 SHA SUCCESS。當前整合片改為 Research runner／sources／funnel／budget／resume／AA V2。code 與本機 race 已落地，新增 Browser 真流程；以新 SHA 六項 gate 為準。後續仍須完整 Assistant／Reflection／管理／資源驗收，不在 Research 片全綠就結束本輪。

研究片 `fa7c291`：[VPS 38558907867](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38558907867) 四項 SUCCESS；[Android 38558907976 attempt 2](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38558907976) build/device SUCCESS。首輪既有 Android rapidDoubleToggle 15s 逾時，同 SHA 保留斷言重跑通過。當前整合為完整 App Assistant；本機 race/vet/JS syntax 已過，新增真 Browser，仍待新 SHA 六項 CI。下一片 Reflection／宣告式 connector，其後完整管理與資源驗收。

Assistant code `69652c9` 同 SHA 全綠：VPS `38567589731` 四項＋Android `38567589525` build/device SUCCESS。當前 Reflection／宣告式資料擷取與 credential-version 保護已本機 race/vet/syntax/TLS 回歸，新增 Browser；待新 SHA 六項 gate。20:56 使用者另授權 Kilo 免 Key 實測，live conversation/native tools/App Assistant 真實流程已通過（6 HTTP／26.31s，僅合成一次性資料），證據與限制在 `vps/V1_COMPLETION.md`。完整管理／filter-ranking／notifications／backup／multi-origin／CUSTOM／native speech／資源最終驗收仍要接續，不在本片全綠就結束。

Reflection code `293bd516` 同 SHA 六項全綠（VPS 38572762140、Android 38572762176）；本輪為管理/filter-ranking／背景規則通知／portable backup v2／SQL 全量統計與輕量 runtime 候選。測試與正式 UI 已接線，新增 Browser、standalone RSS workload；待新 SHA 六項 gate。Main 遠端 2026-10-14 再驗仍為 `5009e0c8`，未改 Android source。multi-origin／CUSTOM／native Gemini speech／最終交付仍接續。

管理／資源 code `8f069625` 同 SHA 六項全綠（VPS 38582129810，Android 38582129791）。22:37 新需求 Kilo 真 Provider discovery＋全程式 RAM＋續 UI；Kilo 已執行真 App tools，但本機 public DNS unavailable，實驗輸出保留，改獨立 opt-in Actions Linux，不放寬 SSRF 或冒充已找到證據。後續同一 V1 目標不變：multi-origin／CUSTOM／Gemini native speech／媒體 RAM／完整 UI 回歸。

`b7ecdaa` 同 SHA 六项 SUCCESS（VPS 38584571842、Android 38584572181）。Kilo opt-in Actions 38584572569 真實 Provider research 33.54s 通過：2 leads/3 evidence，Groq/OpenRouter讀取成功、Cerebras307失敗如實記錄；沒有現成credentials借用/eligibility推定。PR已還原一般標題，之後不默認耗用Kilo。當前 origin/scoped auth/Group/PAT/connector/backup＋App public source/匿名 onboarding＋Provider UI收合候選，本機race/vet/syntax/TLS與新Browser驗證；待新SHA六項gate。最終custom/native speech/session settings/媒體RAM與交付接續，不在這個slice結束。


多網址／Group epoch／Agent public source 片 `d3da38c50a90976de5eac95c764b7cac66f80ddb` 同 SHA 六項 SUCCESS：VPS `38590860925` test `115945054314`、amd64 `115945054916`、browser `115945054937`、arm64 `115945055188`；Android `38590861082` build `115945620723`、device `115945553268`。現在整合剩餘 Playground：原生 Gemini 語音 CURRENT／LEGACY／雙人 metadata、宣告式 CUSTOM 對話、逐段 system/max_tokens/streaming 設定；共用 scope／工具／取消／telemetry，Assistant 固定 system 不受手動設定覆寫。新增 TLS／Browser／safe backup 與全服務 heavy media RAM profile；本片待新 SHA CI，不把前片或未執行的 footprint 當驗收。


## VPS 完整 V1 code 驗收（2026-10-15）

`ce2ca91a8ff21606bcc335c76d3e6dd47bc0ada8` 同 SHA 六項 SUCCESS：VPS `38612813568` test `116020220644`、amd64 `116020220452`、arm64 `116020220414`、browser `116020220318`；Android `38612813557` build `116020220795`、device `116020219982`。七個 Browser workflows 均成功。剩餘 native speech／CUSTOM／manual session settings、Catalog exact-response 與 security／reflection recovery 均已接正式 caller 並驗收。完整功能、實測 RAM/Kilo、限制與安裝見 `vps/DELIVERY.md`；這不改 Android 本身 PLAY-004／P8–P10 的状态。無獨立外部 reviewer／無 merge／無目標 VPS 部署證據。

Standalone：idle 26,714,112、chat 30,040,064、research 30,273,536、images 97,619,968、peak 162,525,184 bytes（155 MiB），binary 13,439,138；完整保留10萬samples，4 chats／65 Catalog／4 simultaneous 4MiPixels images／4 simultaneous 4MiB audio。Live Kilo `38612880443` 真成功 41.19s、6 inference HTTP＋7 public HTTP、3 candidates/4 evidence/3 SUCCESS tasks；Groq/OpenRouter200，Cerebras307 FAILED線索也保留，不當eligibility。

末尾交付提交含資料與文件、200% decorative mark overflow 修整及 screenshot transition 固定；必須再查此新 HEAD 的六項 gate，不以 code 基線代替文件 HEAD。


## VPS V1 後續完整體驗（2026-10-15）

使用者最新授權：持續完善所有功能／UI，自行找有用的擴充；RAM 優先 ≤512 MiB，≤1 GiB 可接受。保持單 Go／SQLite，不裁功能，也不改 Android。Main 再確認 `5009e0c8`／38408176782 build/device SUCCESS。第一片為對話管理／全文搜尋／置頂／可恢復封存／完整 JSON 和 Markdown 串流匯出／安全 Markdown、程式碼複製和表格；schema14 保留所有歷史，過期編輯拒絕覆寫。正式 UI、TLS caller、1100 則完整匯出與安全回歸已落地，等待新 HEAD 六項 gate。後續完善每次請求的用量／價格／額度收據與 Detail，完成新的 standalone RAM profile；不以 aa7f001 的綠燈冒充新候選。


對話擴充 code `d3e1c84140306e80644a9f9acbf6d159035cd6c9` 已同 SHA 六項 SUCCESS：VPS 38621092030 test116048490678、arm64116048491082、amd64116048491368、browser116048491381；Android38621091550 build116049206203/device116049206550。八個正式 Browser workflows 均過，standalone 原媒體 profile 峰值164,564,992 bytes（156.94MiB）。

請求續片已驗收：schema16 請求／訊息連結、immutable 當次 pricing/quota before/after、完整用量與 actual/nominal/effective estimate 分離、scoped request detail／50列 UI／完整 NDJSON 匯出、手動 stale-safe retry 不重複 user message；修正 Catalog nominal 0 不推成 Key FREE，人工明確確認及經測試／Review 的 per-key/group connector 才可提供 effective pricing；通知使用新 proof basis。Raw attachment 與 header-name secrets guards、長 JSON 不逐 Key 重建、export 每批讀取後釋放 DB、快照選定 rowset 避免新增／狀態變更造成重複漏列。完整匯出由 sequence index／rowid 前進，修復 11,000 訊息 OFFSET 重掃逾時，未放寬 timeout 或縮減工作量。

`57ef23e` 同 SHA 六項 SUCCESS：VPS `38631190515` test `116082947267`、browser `116082947583`、arm64 `116082947595`、amd64 `116082947725`；Android `38631190578` build `116083047901`、device `116083048486`。實測 31 MiB×4 完整 wire、11,000 訊息／100,008 請求完整匯出，加原四 chats／研究65 models／四圖片／四語音，服務峰值 426,102,784 bytes（406.36 MiB）、待機 26,591,232（25.36 MiB），192 MiB 僅 Go soft limit；OS 另計。已自行 diff／實際截圖 review；證據見 `vps/verification/20261015/`。Kilo 含新 engine 的真研究 `a39fa2a`／`38629907160`／`116078568504` 20.06s 成功，6 inference＋7 public HTTP、3 candidates／4 evidence，非 eligibility 證明。最後文件 HEAD 以 PR #20 最新六項 checks／body 驗收後恢復 Ready；本輪不擴新功能片，不合併／部署。
