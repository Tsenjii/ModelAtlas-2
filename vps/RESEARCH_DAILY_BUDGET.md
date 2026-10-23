# 研究每日推論核准（RESEARCH-DAILY-015F）

本片在015E原子入場上加入每日總／逐憑證核准上限與正式VPS操作。完整WISH-CRAWL-001及下一版仍PARTIAL；token／USD、共用Account／Project等quota pool、私人排程明確opt-in、逐級probe、各端專用UI與真站／實機／目標VPS仍待補。公開版本保持0.8.5-preview.1，PR #20維持Draft，不merge、不部署。

## 正式流程

Models → 全球研究，或Settings → 研究的同一工作區 →「研究推論每日上限」。可設定全部研究每日上限、每份憑證預設上限、個別Provider／Account／Credential覆寫，以及「改用預設上限」。初值100／20次；允許0–2000，0明確停止核准，缺少欄位不當作0。上限變更有不可變版本／時間／精確scope，50筆分頁持續讀取；憑證用量100筆分頁，包含今日已使用或有覆寫的歷史憑證，不因目前不可用而隱藏今日用量。

此數字是這個引擎新核准的研究推論次數。每日UTC 00:00（台灣08:00）另起一天，舊日紀錄不刪；不是Provider官方額度、剩餘quota或精確收費。記錄從schema27啟用開始，介面保存並顯示開始時間；升級前、其他裝置、網頁或其他客戶端耗用不在此計數內。Token、貨幣與站方remaining保持null，不以模型名稱、免費字樣、回應OK或本機次數推算。

已存在的每輪HTTP與probe、排程／來源確認與原4-flight限制繼續同時成立。每天的穩定scope為Provider×Account×Credential；同一scope的模型、Group、端點與Key版本共用次數。輪次重建、Key輪換、重啟、修改上限、改用預設上限都不清掉原次數。不同憑證仍受每日總上限約束；不宣稱這已涵蓋任意共用帳戶／Project官方quota。

MINIMAL_INFERENCE優先重用原有同scope／來源／憑證版本的有效觀測，不另核准或發送推論；日常Playground／Assistant／Compare保有各自明確操作，不受這份研究次數上限阻擋，也不借其名稱增加研究授權。沒有新增自動重試、Key輪換或供應商路由。

## 原子入場與恢復

在execute內容／能力／協定檢查後，同一SQLite transaction檢查原run／task的HTTP／probe及排程限制、讀取本機每日政策、計算全域及精確憑證當日次數、保存新的admission與對話／工具／請求收據。任何拒絕、寫入或commit失敗全部回滾；不留下phantom sample、部分訊息或扣額。缺政策／用量查詢失敗採拒絕，不回退為無限或零用量。

入場紀錄綁定實際request／run／task、UTC日與核准時間、穩定scope、實際憑證版本、完整Selection及當時政策版本／兩個上限。核准後的503、取消、中斷與重啟仍留帳，不冒充Provider已完成或已付費。原取消不進My Uptime分母的規則保持。

每日拒絕的正式worker保存BUDGET_EXHAUSTED、實際限制scope、用量／上限與重設時間。沒有發送推論，因此不消耗原網路失敗的三次嘗試機會。調高上限或跨日不自動恢復手動研究；仍須原「恢復研究」明確操作，原每輪預算與私人排程禁令繼續生效。普通公開排程不因新增預算而取得私人成本或憑證權限。

設定與入場表只屬於目前引擎，不在workspace同步／portable備份allowlist；完整SQLite一致快照仍包含。schema26→27先保存已驗證升級前快照，再新增四表，保留原AA／歷史／原資料；不能以同步歷史、遠端政策或缺失的本機表重建授權。

## 表單與可查紀錄

保存以政策revision作CAS，舊草稿409拒絕覆寫，不自動重試。讀取期間停用舊卡片與寫入，讀取失敗保留last-good及未保存草稿；再次更新不偷偷更新草稿的CAS基線，使用者可明確捨棄並重新讀取。成功保存後只重讀，不重送寫入；重讀失敗仍可恢復。每次修改原子保存變更紀錄，紀錄失敗便不提交上限。

正式研究紀錄呈現尚未發送的每日限制與恢復規則；設定頁呈現今日實際核准、穩定scope與個別／預設上限。長scope、320／375／1280深淺色、200%及至少48px操作延用原CSS／原生元件。

## 驗證與失敗保留

- 初始正式設定入口0.827s FAIL：GET 404，沒有跨輪每日設定／入場功能；不把它敘述為既有每日計數bug。
- 首次修後1.298s FAIL為新fixture重複加入相同My Models（409），改為原正式測試session建立，不刪會員或放寬membership guard。
- 擴充18.244s FAIL為新fixture未明確加入另一模型／端點與不同Account／Key，以及誤認原portable restore的201為200。改由真My Models操作、保持完整scope驗證並使用原201契約，不改產品保護。修後8個後端函式race21.489s成功，涵蓋兩組各20工作／原4-flight及三個TLS飛行中barrier。
- 新Browser的全部JS操作成功後6.627s FAIL：Go fixture只預期兩次研究catalog，漏算原加入兩個模型自動enrichment的兩次。補獨立初始catalog=2／inference=0及最終catalog=4／inference=2精確斷言；不減自動enrichment、操作或工作量。
- 追加初始基準0.869s FAIL確認enrichment仍在待執行佇列，不能把加入操作已返回當作HTTP已完成。fixture改先由原runPending正式執行既有兩項enrichment，再驗初始與最終兩個精確基準；不偽造完成或刪待辦。
- ae57b1f候選補查5.914s真Browser反例FAIL：服務跨UTC日後，重新收合／展開每日面板仍顯示前一日1次，不發新GET。已補展開與回到前景時重讀，沿用既有15秒研究排程檢查在面板可見且UTC重設已到時更新，不新增高頻計時器或Provider HTTP。原次數／暫停／reload／實際請求斷言、9圖及整體timeout保持；修後新來源獨立完整回歸／九項，ae來源不接受、不借綠燈。
- 後端另驗本機DB入場與政策journal失敗全回滾、讀取失敗不授權、503／取消跨重啟不退款、Key版本／模型／Group／端點不重置、Account／Key隔離、UTC跨日、正式worker多次等待不耗retry／明確恢復／零額重用流量、真HTTPS同步／portable隔離、v26一致升級與原AA保留、102憑證及55筆歷史分頁。

跨日重新展開反例修後，新Browser7.494s（流程6.48s）成功，原所有情境與9圖不減。

2015e5a候選完整Go race359.498s成功，但完整33 Browser不能接受：原模型連線的簽到跨日鍵盤操作等待30秒失敗（36.36s），全套在28流程成功後300.021s觸及原上限。失敗原圖顯示舊日按鈕仍在但焦點已丟失；同來源單跑8.415s成功，不能抹去全套反例。追加真GET延遲後6.388s確定重現同日讀取重建按鈕而丟焦點；production只在原焦點位於本帳戶操作時，恢復同一帳戶／同種新操作。已完成／消失／過日的操作不恢復成可寫舊按鈕，改保留資訊焦點。原兩次推論、一次簽到POST、stale day不POST與所有選取／排序／scope／圖／timeout斷言不減。原MacAlignment、強化ModelConnections與新每日Browser合計24.604s成功（11.17／6.61／5.80s）。

2015來源CI的Browser120275350443／run39911458705在原15min上限取消，沒有執行測試或上傳圖；紀錄顯示Ubuntu依賴下載先耗6m40s，61.2MB CJK字型再卡8min。此片將CI內已觀測的Azure HTTP Ubuntu鏡像換為官方HTTPS archive，APT連線／資料等待30秒、最多1次額外下載重試；保留原npm lock、完整Chromium／headless shell、CJK字型、全部工作量、5min測試及15min job上限。不改應用程式網路政策。YAML／bash -n／實際APT選項解析、vet及27發布回歸0.036s成功；參考[APT官方設定](https://manpages.debian.org/stretch/apt/apt.conf.5.en.html)與[Ubuntu HTTPS archive](https://archive.ubuntu.com/ubuntu/)。修後新來源必須完整本機與同來源九項，不能借2015其他八項或先前任何候選綠燈。

34814ce候選的本機完整Go357.458s與33 Browser288.143s／389PNG／零failure圖成功，但仍不能接受：CI Intel Mac120285741828／39914109890的82核心通過後，在原native-composer narrow-resize失敗；原始41PNG附件12484881303（SHA256 07bca8255c506554838516ceba8059fb5f2e544346895f024d709a8e6e7bf6b2）已核對、讀取FAILURE／composer診斷並檢視實圖。診斷editor651／container643／height78、草稿與editor均192字且IME未組字，舊報告沒有寬窗baseline，不能直接當作基礎設施重跑後忽略。

同片新增穩定寬窗量測：在原4秒內要求實際view／container寬度與高度連續穩定150ms，再保留原縮至920／回1200的>8及<2高度差斷言。新增真AppKit容器先變寬度、view frame不變的隔離案例，用overlay避免捲軸改變寬度干擾，完成後恢復原scroll style，再跑原視窗／IME／20行全文／捲動／sidebar與歷史保留。Production快取同時比較實際TextKit container width，量測後才保存key；沒有取消原文字／寬度快取或減低原場景。舊production的獨立診斷來源a9ddd4c341ab6ed6a87ae6e71c478bd38693f978／verify/native-container-015f只改QA與該分支Mac push入口，用真Mac runner判別新容器案例，結果另記，不作產品驗收來源。最終產品來源仍須自己的全部九項；82核心／90圖／14 motion／原4秒與原生timeout全部保留。TextKit契約見[Apple](https://developer.apple.com/documentation/appkit/nstextcontainer/widthtrackstextview)。

34814 CI Browser120283619447仍在15min安裝期取消、未執行測試；實際log證明Runner用mirror+file:/etc/apt/apt-mirrors.txt，前次只改sources檔沒有覆蓋該鏡像清單，實際GET仍為Azure HTTP。現同時處理實際mirror+file指向與/etc/apt/*mirrors*.txt，改官方HTTPS archive；保留完整依賴／字型／Chromium、原測試與job上限。不能把語法通過當成套件實際走HTTPS，最終CI log與全部測試仍須獨立確認。[APT鏡像清單契約](https://manpages.debian.org/testing/apt/apt-transport-mirror.1.en.html)。

最終聚焦含原015E、原生協定／工具接續與原研究真實性caller的race42.758s成功；vet及27發布回歸0.030s成功。新Browser最終6.973s（流程5.96s）成功，9張實圖已檢查；原32流程／380圖保持，完整新來源另驗。

完整Go race／vet、發布27回歸、原32 Browser／380圖加新流程、Android JVM／device、Mac兩架構、真systemd與原expanded workload都保留。原Browser Go timeout5min及job15min不變；最終來源獨立九項與全部實際產物前仍候選，不借015E或任何修前來源的綠燈。
