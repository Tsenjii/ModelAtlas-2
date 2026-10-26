> 目前唯一候選RESEARCH-EXECUTION-015T：VPS本機研究輪數1～4、不同輪有界並行／同輪每次一項、排程slot／每日入場與取消／重啟；預設1且不新增來源／憑證／預算授權。原11個Browser腳本19個async條件改為真正await，保留全部條件／斷言／負載／timeout；新增8個實際Chromium等待回歸。015S費用證據已由246ea08同源九項、九份實際ZIP及54視圖驗收，見verification/20261026/research-cost-baseline.json。新片自己的完整回歸與九項／實際產物／58視圖前仍候選；完整下一版PARTIAL、VPS→Mac→Android及公開版本／tag／11附件凍結保持。

> 2026-10-26 目前唯一候選 RESEARCH-COST-015S：補 VPS 反思、來源／查詢排序重播與兩臂真查詢的精確費用快照與正式比較 UI。Token、站方 USD 回報、本機已核算／預留及未知公開 HTTP 分開；每個工作只取原 task／run 收據，舊紀錄無費用證據維持 Unknown。自己的同源九項與實際產物／54相關視圖前仍未驗收。保留原420 Go入口／637事件，新增4例後預期424入口／641事件（591pass＋50原skip）、42Browser／527PNG，Android594核心／423App／996device與兩Mac89核心／104PNG，以及所有原負載／timeout保持；整版與真站／目標VPS／實機／簽署仍PARTIAL，公開版本／tag／11附件凍結、PR #20仍Draft、不merge／部署／發布。
>
> MAC-RESEARCH-015R所列範圍已ACCEPTED：來源70f4683297955c5ba434e2b8139fbd73bcb5081d／tree c1c15a828a5d27a09e8bec0ce9caa786881c0e21；VPS40300264998、Android40300264842、Mac40300264985九項全SUCCESS，稽核40300264890／121496116554實際九ZIP與50視圖已核對。真產物12641141621為817665bytes、SHA256 9e597069cf445c2e34fed17cd2f577ee9652916a152cf762727ad97d3620297b。完整baseline見verification/20261026/mac-research-baseline.json；以下較早候選保留為歷史，不重做已驗收片。main本輪仍cba901d／39536090788 build＋device SUCCESS、FEATURE FREEZE=OFF；VPS→Mac→Android與原全部願望保持。

> MAC-RESEARCH-015R 本機候選：精確舊紀錄反例原本0.136s失敗（指定ID仍回100筆他輪），修後race2.502s與AA擴大回歸30.157s成功。41發布回歸、vet與真TLS fixture協定成功：3次建立／3次控制、頁1三次／頁2四次、總HTTP7、零推論；尚未執行本來源Mac原生UI／九項CI，不外推為已完成。原600s／300s、85核心／96圖及原全部負載保持；新增4核心與獨立第三原生程序8圖，原smoke／parity的120s不變。詳verification/20261026/mac-research-candidate.json。

> 2026-10-26 PRIVATE-MAPPING-015Q 所列私人欄位對照範圍已驗收：來源 d261134b0b912bad661efbe6f6d28f100ce733f6／tree bcde49d6a3190a0abd4b6eeeb4052589f8ce7ae8，同源 VPS 40290236392、Android 40290236329、Mac 40290236304 九項原必要 job 全 SUCCESS；只讀稽核 40290236359／121463303919 實際核對九份 ZIP、完整原工作量、報告、套件與公開凍結，34 個相關視圖已自行檢視 PASS。Go race 583.141s／原600s、635事件、42 Browser／523圖、594核心＋423App／996device（0failure、5原skip）、兩Mac各85核心／96圖，原負載 peak RSS 211275776 bytes。詳細證據見 verification/20261026/private-mapping-baseline.json。前次 AA 失敗根因仍 UNKNOWN，本來源成功不抹除原反例。完整下一版仍 PARTIAL，公開版本／發布請求／tag／11附件凍結，PR #20 維持 Draft，不merge／部署／發布。

> 目前唯一接續片 MAC-RESEARCH-015R：補 Mac 研究紀錄的即時狀態、精確 ID 讀取、暫停／恢復與保存後只讀恢復，將兩個 AA 更新入口接上既有持久分頁 runner。先保留原全部測試、負載、timeout，再以正式控制器、實際原生操作及真 TLS fixture 驗證；本片尚未驗收。VPS→Mac→Android 與完整原願望不變。

> 015Q第三候選7d612a40未接受：Go418入口／635事件（586pass／49skip，原604逐項不變）、其餘七項原job與私人Browser成功，但原AA更新流程只有6次TLS（3／3，原要求3／4）。實際失敗ZIP12625684013的40443180bytes／SHA256／CRC及523張圖解碼已核對，原8張AA與14張私人圖完整；原始task失敗原因未保存，仍UNKNOWN，不能聲稱已修好。五次獨立AA診斷70.899s均通過；完整42Browser診斷214.763s亦通過，但執行中script有修改，不能當最後來源驗收。現補每筆AA任務SUCCESS／兩頁完整與精確[2,2,3]預算斷言、失敗實際result及額外五次診斷；原七次請求、全部套件／負載／timeout不改。修後兩流程16.010s與375px／200%真保存8.887s成功；41發布回歸及vet成功。詳verification/20261026/private-mapping-aa-counterexample.json；自己的九項、九ZIP及34視圖前仍候選，整版PARTIAL、公開凍結不變。

> 015Q第二候選d0bf0d89未接受：原41Browser全PASS，但新Browser在正式Intelligence時實際多1次讀取（4≠3），fixture拒絕該路徑。已由adapters.go確認enrich先discover再enrichConnectors；前次把額外讀取分類成匿名/api/status是錯誤判斷，現明確更正。保留原目錄刷新，以精確/v1/models＋所選Key另計，私人原8次與目錄3次＝11次實際HTTP；私人Key／PAT仍只用於其指定資料端點，其他路徑拒絕。原Go完整job尚在跑時讀到此Browser錯誤，不能編造其終值。完整反例見verification/20261026/private-mapping-catalog-counterexample.json。新增額外聚焦診斷job以提早讀到真TLS／UI結果，原九項／全部套件及timeout保留；修後自己的全項與實際ZIP／34圖前不接受，整版PARTIAL及公開凍結不變。

> 015Q首候選87b3a5fb未接受：Go635事件中原604結果逐項相同，新2例因把正式Intelligence既有匿名/api/status探測併入私人計數而失敗（最後package578.044s，原600秒不變）。現fixture對該匿名路徑明確404、另計並驗不帶Key／Cookie，私人原7／6次及範圍／last-good斷言不改。Browser原41中40成功；新折疊選單未展開與原input廣泛selector歧義兩例失敗。改用真實disclosure與精確source_path，不加重試／延長timeout／改斷言。原Go格式已依CI標準化，另驗實際PAT275.25 CREDITS帳戶事實；完整失敗、metadata與未實際核對的archive界線保留於verification/20261026/private-mapping-initial-candidate.json。修後自己的九項／實際ZIP／34視圖前仍候選，完整下一版PARTIAL、公開凍結。

> 2026-10-26 PRIVATE-MAPPING-015Q 候選，尚未ACCEPTED：新增明確既有Key／帳戶PAT的單次唯讀JSON取得與精確model／group或account欄位對照；匿名預設／公開hash保持，私人授權不由Assistant自行聲明。來源樣本、role／PAT及material／origin／完整selection綁定，fixture＋真HTTP後明確啟用，正式Intelligence在讀取前後重驗私人權限，持久Undo與原ID只讀恢復。原真TLS反例47fc來源400 invalid JSON及未提交原型實測歷史保存於verification/20261026/private-mapping-counterexample.json；互動環境離線後由相同來源的GitHub CI重新驗證，不能借原型或015P綠燈。預期418入口／635 Go事件（原604逐項保留）、42Browser／523圖（原41／509完整＋私人14圖），594核心／423App／996device及兩Mac85／96不減；原全部負載／timeout／512MiB與九項及實際ZIP／34相關視圖門檻保留。完整下一版仍PARTIAL，公開版本／發布請求／tag／11附件凍結，不merge／部署／發布。

> 2026-10-26 WORKSPACE-CHUNKS-015P所列分塊／恢復／原生同步範圍已ACCEPTED：來源bc4a735e9144564c5d78433b196038d2452c8def／tree1b8be8b196f06deef7f937de63f1babded3339df，同源VPS40255340659四項、Android40255340678兩項、Mac40255340630三項首次全SUCCESS。只讀稽核40255340606／121355872785實際下載九ZIP並驗bytes／SHA256／CRC／producer／完整報告，額外實際ZIP12622916510（290457bytes，SHA256 171a9f3b151f7f63ab62744c3b404e95c7037ede630531e1e9243b5f10360754）及20視圖已自行檢視PASS；沒有假稱第二位獨立reviewer。Go race543.292s／407入口／604事件、41Browser／509圖、594核心＋423App／996device（0fail／5原skip，原985結果完全保留）、兩Mac各85核心／96圖。近64MiB雙向真模擬器案例47.893s／135.345s、同ART PID4697的start／finish均536870912bytes，zygote1665→3092；未冒充實體手機。原全部負載／timeout保留，原資源負載peak211202048bytes。完整精確證據見verification/20261026/workspace-chunks-baseline.json與Draft PR #20；以下候選／失敗敘述保留歷史。
>
> main開工重讀仍cba901d／39536090788 attempt2 build119016886469與device119016884592 SUCCESS，FEATURE FREEZE=OFF。依D-016直接接續唯一新片PRIVATE-MAPPING-015Q：把既有已選Key或明確PAT的唯讀JSON資料取得、精確scope／單位欄位對照、fixture／真HTTP實測、Review／正式使用及持久Undo接上VPS。先用真TLS端點重現目前只能匿名取得、私人資料無對照入口的缺口。預設公開讀取不帶憑證，不自動嘗試Key／PAT、不取登入、不猜scope、秘密不進樣本／prompt／log；私人授權與context需明確選擇、版本綁定及真正使用前重驗。新片尚未實作／驗收，不借015P綠燈；保留原全部604Go結果、41Browser／509圖、594核心／423App／996device與兩Mac85／96及原負載／timeout。自己的正式caller、反例／恢復、同源九項／實際產物前不接受。完整下一版仍PARTIAL，公開版本／發布請求／tag／11附件凍結，不merge／部署／發布；Reflection、PLAY-004、專用原生mapping與真站／主機／實體裝置／簽署缺口保留。

> 2026-10-26 015P預檢修正：3691e4e來源的device job121352711829在任何instrumentation之前失敗，原錯誤未保存PID，不能猜測是缺少或多PID，更不能當成大資料測試結果。相同時間的device unavailable與原始錯誤保留於verification/20261025/workspace-chunks-emulator-preflight.json。現在同一原90秒deadline內先等唯一初始zygote，再只重啟framework一次，保留新PID／512MiB配置／package manager及真ART start+finish gate；沒有重跑案例或改heap，失敗亦輸出FAIL附件。兩個新可控預檢反例在舊程式失敗，修後41項發布回歸（原39完整＋2）成功。Android大資料記憶體修正及Mac四張原生續傳圖仍待修後來源自己的九項／實際ZIP與20視圖；完整下一版仍PARTIAL，公開版本凍結。

> 2026-10-26 WORKSPACE-CHUNKS-015P 同片修正候選，尚未ACCEPTED。來源3ced4c3c3bada7134e62a3d0ce5b9eb8f832bc5e未通過：VPS四項與Android build成功，但Mac同步頁型別推導編譯失敗，實際Android XML為996案例／990pass／1failure／5原skip；新增近64MiB上傳在512MiB ART中，1.8.0串流JSON仍配置整段字串緩衝而OOM。已重新下載並核對失敗ZIP大小／SHA256／CRC／完整堆疊，且原985個案例結果逐項與015O實際ZIP相同；見verification/20261025/workspace-chunks-initial-candidate.json。固定8192字元與8192位元組緩衝、舊格式精確bytes／近64MiB實際配置量／指紋反例已補，本機594核心成功；Mac拆分View並新增真TLS傳輸時按暫停、實際Keychain及引擎重啟、原ID按恢復的四張原生驗證，仍待自己的CI。舊v1分頁也在每頁前重新核對暫停；新增反例不改原三個模式。新來源預期407 Go入口／604事件（556pass／48opt-in skip）、41Browser／509圖、594核心／423App／996device（5原skip）、兩Mac各85核心／96圖（原92＋4）。原全部測試／負載／timeout／512MiB限制不減；自己的九項、九份實際ZIP與20視圖前不接受、不接新片。完整下一版仍PARTIAL，公開版本／tag／11附件凍結，不merge／部署。

> 2026-10-26（台北）WORKSPACE-CHUNKS-015P 整合候選：首反例來源73baf3b1的同一真TLS既有v1先成功、兩種裝置協商404已確認，原始589事件及job121206460227見verification/20261025/workspace-chunks-counterexample.json。現接上256KiB加密分塊、64MiB紀錄／65MiB請求／72MiB回應上限、持久進度、整體雜湊、產品與不可變回應收據同交易、目的端資料／vault／cursor／CONSUMED同交易、只讀恢復／ACK／暫停與安全重設。VPS／Mac Settings偏好可恢復協定，Android真manager／Room／Keystore使用串流；舊中心僅明確404／不支援時沿原v1，小頁格式及限制不改。大衝突摘要不改完整CAS或歷史。原Browser抓到前輪收尾時會遺失已確認的接續同步，現合併排隊且不改原斷言；兩真Browser race11.246s通過，新增8圖。Android本機591核心／423App／兩APK成功，追加11個真裝置案例已編譯但尚未執行；完整新來源九項／九份實際ZIP／相關實圖前仍未ACCEPTED。保留原586Go結果、40Browser／501圖、985device／5原skip、兩Mac85核心／92圖與所有原負載／timeout；完整下一版PARTIAL，公開版本／tag／11附件凍結，不merge／部署。 同源預期407個Go入口／603事件、41Browser／509圖、591核心／423App／996device（5原skip）；實際CI與Mac新增進度分支介面驗收仍待完成。

> 2026-10-25 唯一新片 WORKSPACE-CHUNKS-015P：015O來源66ec4eb7af3278f49a99a50e1fcc165c3a89c1d2／tree648dfd7bb3b7b989418a81fac3a4da995882d5dc已由同源九項attempt1、九份實際ZIP、額外只讀稽核與18實圖驗收所列公開欄位對照／管理交接範圍，精確證據見verification/20261025/field-mapping-baseline.json及Draft PR #20。main仍cba901d／39536090788 build＋device成功，FEATURE FREEZE=OFF。接續大於原32MiB單筆上限的分塊／持久進度／中斷恢復／完整雜湊／原子結果與三端正式入口；先加入獨立裝置能力協商的真TLS反例，尚未接線，不預稱通過或完整同步已交付。保留原嚴格v1 pair／exchange格式、原全部工作量／timeout、精確scope／秘密／撤銷／衝突／last-good；自己的新來源完整九項／全部實際產物前仍候選。公開版本／11附件凍結，不merge／部署，完整下一版PARTIAL。

> 015O候選3ef96c39未接受：新立即啟用反例通過，但原PendingConnectionHandoffTest.disabledPendingIsEvictedAndNeverResurrectsAsUsable失敗，確認停用必須移除active pending。現改為獨立managementHandoffById，僅供管理列／管理操作，恢復原pending AND與停用eviction；成功寫入才交接，真management到達即退休，不加入active／send／選取。原984案例及完整原PendingConnectionHandoffTest保持，追加反例保留所有UI／Room／vault斷言並加管理交接不冒充active；總985／5原skip／timeout不減。互動環境中斷後加只讀產物稽核與四個XML反例，待新來源自己的九項／九ZIP／18實圖才接受，不能借3ef八項綠燈；證據見verification/20261025/field-mapping-management-handoff-candidate.json。完整下一版PARTIAL，公開版本／11附件凍結。

> 015O同片穩定性修正候選：25588f93因原Android啟用逾時未接受；37c7c34d的真Room／vault／VM確定性反例於985案例中唯一失敗，確認停用成功後active先清空、management尚無列會掉失管理入口。現只在真寫入成功後交接精確credential的已知列；啟用保留原AND、停用待management接手即退休。另真Browser33.60s確認來源會員變更409會擋住已提交結果恢復，現先提供原ID只讀恢復且不曝空表單；聚焦5.37s、含原辨識兩Browser7.946s及後端35.733s成功。原984／5skip完整＋1確定性案例、全部工作量／timeout保留；修後自己的九項／全部實際產物前仍候選。證據見verification/20261025/field-mapping-regression-counterexamples.json，完整下一版PARTIAL。

> 2026-10-25 唯一新片 FIELD-MAPPING-015O：015N來源faf614f84265a91d85063e5f1e16d91b88266e51／treeb94cbb031dcb128794fa5212244fe8ff94e66046已同源九項、九份實際ZIP與18實圖驗收所列擷取比較範圍，持久證據見verification/20261025/extraction-baseline.json與Draft PR #20。main仍cba901d／39536090788兩項SUCCESS，FEATURE FREEZE=OFF。接續已保存公開JSON的未知欄位對照、來源樣本／scope／單位證據、fixture與真HTTP實測、明確Review／正式使用及持久Undo。自己的完整回歸及九項／全部實際產物前仍候選；不借015N綠燈。原全部需求／測試／負載／timeout、公開0.8.5-preview.1／11附件凍結保留，不merge／部署，完整下一版PARTIAL。

> 2026-10-25 唯一新片 EXTRACTION-015N：015M來源5bc886f98c2646c7e4f43948167b23b71c7e0613／tree8f51b1be90cf092fb84bc40ef0ee281bf098d3d1已由同源九項attempt1、九份實際ZIP及20實圖驗收所列Kilo出廠預設範圍，精確證據見verification/20261025/kilo-defaults-baseline.json及Draft PR #20。main仍cba901d／39536090788兩項SUCCESS，FEATURE FREEZE=OFF。接續擷取效率：精確公開規則的版本化合併讀取提案、三輪有界實際對照與Unknown成本、明確Review／試用、正式Intelligence使用與退化監測／共用持久Undo。原全部測試／負載／timeout與已驗Kilo功能保留；不改公開0.8.5-preview.1／11附件、不merge／部署；本片自己的完整回歸及同源九項／實際產物前仍候選，完整下一版仍PARTIAL。

> 015M首輪歡迎卡修後完整38 Browser race186.450s成功，473圖保留原458＋15；首輪訊息的native-welcome必須為0，實際375px對話已重新檢視。原369 Go／557結果等價與全負載、全部原生情境保留；新來源自己的九項與實際產物前仍候選。

> 015M實圖追加反例：f7e2af0f的九項CI已SUCCESS，但實際Browser首輪聊天仍殘留native welcome，375px標題被截斷；新增正式操作斷言2.055s重現，沒有把CI綠燈當完整UI驗收。bubble移除真正空白歡迎卡，保留原訊息／草稿與scope；含Kilo／原Production／ChatExperience／Conversations四流程race15.003s成功，新來源重跑完整38 Browser與自己的九項／實際產物。

> 015M完整兩程序race240.844s成功：369個註冊案例185／184各執行一次，與原單程序463.795s的所有case／subcase結果逐項完全相同；共同600秒上限、每個原fixture／migration／HTTP／負載／斷言皆保留，覆蓋checker四項亦成功。新的來源尚待自己的九項與實際產物，仍不借0f的8項成功。

> 015M首0f60e209未接受：8項CI成功，vps-test121064231573在原600秒上限600.032s逾時；原stack保留。另以正式caller重現Kilo目錄budget原因誤分類0.068s及probe漏列目錄HTTP0.133s，已修正等待／明確resume、取消與每次實際HTTP報告。擴大聚焦47.992s及原完整單程序race463.795s成功（369個top-level案例、324pass／45明確opt-in skip）；完整案例改以兩個獨立程序共用原600秒上限，沒有減測試／migration／負載，待此來源自己的九項與全部實際產物。

> 015M本機：完整Go race580.494s、vet及原27發布回歸0.037s成功；核心JVM585項成功。完整38 Browser先147.434s／162.741s成功；實圖修正Kilo拒絕提示遮擋、來源說明過高與scope提示後再跑clean完整473圖，保留原458。原生Mac追加3項／2圖、Android追加8項與fresh PENDING／既有空工作區隔離；自己的同源九項與全部實際產物前仍候選。

> 2026-10-25 唯一新片 KILO-DEFAULTS-015M：依使用者「出廠預設放入kilo的模型、全部弄好」優先補三平台首次啟動的免Key Kilo模型、正式選用／價格檢查、既有資料升級與不覆寫選擇。015L來源d51cda7587dff8fb5821e9d85882ae262b405b2b／treea58808e93fedb1f1e3db437580a5247a0da4492a所列範圍已經同源九項、九份實際ZIP與21實圖驗收，證據見verification/20261025/site-recipe-baseline.json及Draft PR #20。main仍cba901d／39536090788兩項SUCCESS，FEATURE FREEZE=OFF。015M自己的正式caller、回歸與同源九項／實際產物前仍候選；原擷取效率閉環接續於本片後，完整願望及全部測試／負載／timeout保留。公開0.8.5-preview.1、發布請求／tag／11附件不變、不merge／部署。

> 015L本機候選進度：具體公開配方與來源別站型／版本證據、正式逐項實測／差異確認／原子整組啟用／持久共用Undo已接正式caller。15個後端race12.896s、含原Connector聚焦37.072s、三個Browser14.652s與15新實圖已檢視；完整Go race466.545s成功、vet與原27發布回歸0.030s成功；完整37 Browser187.592s／458PNG（原443+新增15）成功，自己的九項／全部產物仍待驗。新增真TLS反例與限制詳[vps/SITE_RECIPES.md](../vps/SITE_RECIPES.md)，原全部測試／負載／timeout及完整願望保留。

> 2026-10-25 唯一新片 SITE-RECIPE-015L：015K來源b8c2080b3e0063accdbc5974043825b61c661ce7／treec27298d91ded21d2d28a49ba72616c93b06183b2已由同源九項attempt1及九份實際產物／20實圖驗收所列範圍，精確證據見verification/20261025/reflection-query-baseline.json。完整Go本機511.884s／CI578.042s、36 Browser183.268s／203.252s及443PNG、systemd41.979s與原負載24.552s均保留。main仍cba901d187aa5312cc71f5c2440b23fa04104aee／39536090788兩項SUCCESS，FEATURE FREEZE=OFF。015L先補本機配方執行綁定、逐來源family/version證據、具體草稿／正式驗證／確認及持久回退；自己的新來源全九項與實際產物前候選。公開版本與11附件不變、PR #20仍Draft、不merge／部署，原全部願望與原生／真站實機缺口保留。

> 2026-10-25：015J逐級驗證／候選重驗及015K查詢生成／成對實測所列範圍已驗收；015L公開配方正做完整驗收。完整下一版仍PARTIAL，以下所有發布條件與原願望保留。

# ModelAtlas 下一版完成條件

更新：2026-10-24。依使用者最新指示與 D-017，下一版必須穩定可用且完成主要正式流程；已公開基線為 0.8.5-preview.1（699d4d57ff8264506c2b2f6e5eed0021327bf602）。本清單是發布條件，不是完成聲明。開發期間維持 Draft PR #20，不提前修改發布請求。

原始範圍：PRODUCT_SPEC 的 P1–P10、ASTRA_MASTER_SPEC 的原文及 Part V 28 條驗收、PROVIDER_MODEL_INTELLIGENCE_CONTRACT、RESEARCH_POLICY、D-012～D-015。未經使用者變更，不刪任何原需求。

| 工作 | 下一版必須可操作的結果 | 目前已驗基礎與主要缺口 |
|---|---|---|
| 研究排程／WISH-CRAWL-001 | 多語新站發現與候選重驗、來源／語言／地區優先序、精確預算與用量、持久分頁與恢復、完整漏斗 | VPS 公開排程、來源冷卻、AA逐頁恢復已驗；015B Brave逐頁／地區與revision用量已同來源九項驗收；015E原子入場已驗；015F每日／逐憑證核准已驗；015G token／USD預留及回報結算已驗；015H共用研究pool已驗；015I私人排程opt-in／來源確認與排程每日預算已驗；015J逐級probe／重驗策略已驗；015T補明確並行上限與執行穩定性，目前候選 |
| 站點解析／WISH-CRAWL-002 | 來源證據→family/version/入口辨識→宣告式草稿→fixture及有界HTTP測試→共用Review→啟用／回退；Unknown、資格、一次性與recurring分開 | 手動connector、實測與啟用已驗；015C已驗公開來源自動mapping／fixture／真HTTP Review、改版保留與持久回退；015L來源別family/version、可選被動程式參照與整組公開配方所列範圍已驗；015O已驗公開未知JSON欄位對照與來源變更只讀恢復；015Q已驗明確私人JSON唯讀授權及欄位對照；私人入口／任意schema及專用原生呈現仍缺 |
| 研究改進／WISH-REFL-001 | 多輪品質／成本→具體版本差異→可重現比較→Review→下一輪使用版本→監測→退化回退 | 既有反思／版本／手動權重與Undo已驗；015A已驗固定策略版本、多輪本機比較、明確試用／退化監測與持久回退；015K已驗本機公開證據生成query、真成對實測及下一輪試用／監測／Undo；015N擷取規則比較所列範圍已驗；015S已驗精確token／USD費用快照與兩種比較基礎；其他更新類型及完整語意／成本改進仍待補 |
| 真實性／WISH-AUTH-001 | 獨立版本化suite、重用實際流量、能力與身份分開、精確scope／成本／基準／衝突及風險證據 | 015D已驗既有流量v2 suite、使用者確認的跨origin參考Review／Undo及Models／Assistant；015J已驗有界主動逐級probe／預算與能力觀測；完整語意品質指紋、真站可信基準與專用原生呈現仍缺，不能以Reply OK證明身份 |
| 同步／D-012 | 持久游標、衝突／撤銷、完整歷史與安全憑證通道、大資料分塊、中斷恢復；三端正式操作可用 | VPS及原生同步基礎已驗；015P已驗超過原32MiB單筆上限、接近64MiB雙向分塊與中斷恢復、暫停／重啟／原ID與原子收據；所列範圍已同源九項／九ZIP／20圖驗收；部分原生對應仍未完 |
| 日常功能／P1–P10 | Playground文字／多媒體／工具、Providers快加／進階／Review、多Account/Key、Models／Compare／證據、Assistant安全工具與Undo全部接正式UI | VPS多項流程已驗；Mac015R已驗AA持久任務與原ID恢復，其他Mac／Android逐項核對正式caller，特別是Android PLAY-004，不以transport當UI完成 |
| 穩定與操作／WISH-ACCEPT-001 | 重啟／離線／限流／換Key／跨日／長跑、全部尺寸／深淺色／200%、跨端歷史／更新恢復與原有資源負載 | 原測試及真systemd、原42 Browser／523圖全部保留，015S候選新增4個費用視圖／共527圖；目標VPS及實體iPhone／Android／Mac證據仍須取得 |

同時只接受一個整合片：015A研究評估已驗 → 015B Brave分頁／地區／用量已驗 → 015C站點草稿／Review已驗 → 015D既有流量真實性證據已驗（先重用零額外HTTP）→ 015E研究推論原子入場與恢復已驗 → 015F每日／逐憑證推論核准已驗 → 015G token／USD預留與結算已驗 → 015H共用研究額度池已驗 → 015I私人研究排程已驗 → 015J逐級probe／候選重驗已驗 → 015K query生成／真成對研究已驗 → 015L公開配方／逐來源線索已驗 → 015N擷取比較已驗 → 015O公開欄位已驗 → 015P分塊同步已驗 → 015Q私人欄位已驗 → 015R Mac研究恢復已驗 → 015S費用證據已驗 → 015T研究執行候選 → 完整站點辨識／Reflection與原生正式流程 → 全版本驗收。每片有正式caller、反例、失敗恢復及同SHA九項綠燈，才進下一片；執行時可依實際依賴調整次序並記錄理由。

## 發布檢查

- [ ] 上表各主要功能有正式入口、可完成的成功／失敗／恢復流程；P1–P10與Part V逐條對到程式、測試、實圖、版本／SHA及限制。
- [ ] 不再把固定反思摘要、手動JSON、後端能力或metadata刷新當作完整研究／原生功能。
- [ ] 原全部Go race／vet、發布回歸、Android JVM／device、兩架構Mac核心／native QA、Browser及expanded workload保留並通過；新增情境加入原流程，timeout與負載不降低。
- [ ] 最終同來源九項SUCCESS；三平台11個附件的內外層hash、版本、架構、簽署與公開下載／升級驗證完整。既有tag／附件不覆寫。
- [ ] 真站長跑、實際部署及實機證據分別可查；缺少存取的項目明列未驗，不計作完成。

28條原始補充條件目前不提前勾選。只有逐條符合其原驗收語意，才在TRACEABILITY及原清單同步升級；不得用完成百分比掩蓋缺少的主要流程。
