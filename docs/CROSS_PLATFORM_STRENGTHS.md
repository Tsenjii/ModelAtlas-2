> 2026-10-26：015R的Mac原生研究恢復已驗收（70f4683、九項與50實圖；verification/20261026/mac-research-baseline.json）。目前015S補VPS研究費用證據與正式比較；Mac同源引擎／既有JSON詳情可取得新欄位，不冒稱新增專用原生費用UI；Android專用研究與PLAY-004仍待完成。自己的九項／54視圖前候選，VPS→Mac→Android與原全部能力／負載保持。

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

> 2026-10-25 接續 REFLECTION-015K：015J來源 b3e27ba04f533e255390124a736e893129e77093／tree0355f4db780559ec7bfa14a785a77e0d3f601b88 已由同源九項及九份實際產物驗收所列範圍，精確證據保存在 Draft PR #20。main cba901d187aa5312cc71f5c2440b23fa04104aee／39536090788 build119016886469、device119016884592本輪重查SUCCESS，FEATURE FREEZE=OFF。目前唯一候選015K：本機公開證據生成具體查詢、精確語言／地區／頁數、明確預覽及有界成對實測、成本與Unknown、多輪評估／試用／監測／持久Undo。新來源自己的全部九項及實際產物前不接受、不接下一片。公開0.8.5-preview.1／發布請求／tag／11附件不變、不merge／部署；完整擷取規則／站點family/version／同步／原生與真站實機缺口保留。

> 2026-10-24接續：015I已由970d566同來源九項、九份實際產物與17實圖驗收所列範圍（verification/20261024/private-schedule-baseline.json）。唯一新片015J逐級研究／候選重驗及有效流量重用，自己的新來源完整九項前候選。D-017要求下一版穩定可用且完成主要正式流程，原完整願望、專用原生缺口與版本凍結不變；詳ACTIVE_TASK。

> 2026-10-24接續：015H已由9ba2905同來源九項、九份實際產物及16實圖驗收所列範圍（verification/20261024/research-pool-baseline.json）。唯一新片PRIVATE-RESEARCH-015I補明確私人排程／來源確認／排程每日預算／中斷不重播與本機確認歷史；Mac同源引擎取得後端，專用原生UI仍待補。自己的完整本機／九項與實際產物前候選；D-016要求片內驗收後連續推進，公開版本與原需求不變。詳ACTIVE_TASK、vps/RESEARCH_PRIVATE_SCHEDULES.md。

> 2026-10-24：015G已由abcffa0同來源九項及PR #20持久證據驗所列範圍；本輪重查九項仍SUCCESS。唯一新片015H共用研究額度池，正式VPS設定／原子用量與恢復；Mac內建同源引擎取得後端，Mac／Android專用原生呈現仍待補。完整本機與自己的九項／產物前候選；公開版本不變，原功能與願望保留。詳ACTIVE_TASK、RESEARCH_QUOTA_POOLS.md。

# 跨平台優點與持續補齊清單

2026-10-23：015F已由1d4fc7f同來源九項及全部產物驗收，Mac原生重排修正與全部90圖保留。015G候選只增加VPS正式token／USD預留及回報結算；Mac同源Go引擎取得後端，Mac／Android專用原生設定仍待對應，不能用網頁或後端取代原生驗收。公開版本保持不變，詳ACTIVE_TASK與research-daily-baseline.json。

015F穩定接續：34814本機33流程全綠仍因Intel Mac原窄窗輸入框驗收失敗而不接受；新增實際TextKit container/frame分開量測與原4秒內穩定baseline，保留全部IME／全文／視窗／sidebar／90圖。原Mac優點與專用每日UI缺口不減，VPS簽到焦點修正亦保留；最終來源自己的九項／實際產物前仍候選。

2026-10-23最新：015E來源ba069a1同來源九項／九份實際產物已驗（verification/20261023/research-admission-baseline.json）。015F候選補VPS正式每日推論／逐憑證設定、用量／歷史及恢復，Mac內建同源引擎取得後端；Mac／Android專用原生每日設定仍待對應。原全部功能／負載／原生及Browser測試保留，新來源獨立九項；不外推token／USD或私人排程已完成。

2026-10-23最新：015D所列範圍已由97bcd81同來源九項及全部產物驗收（verification/20261023/authenticity-baseline-v2.json）；當前015E候選修正VPS正式研究／共用對話管線的原子入場、本機故障不扣額與重試新證據。Mac內建同源引擎取得後端修正；Android專用對應及其他原生缺口保留。原功能、測試、負載與timeout不減，015E完整新來源九項前不接受。以下為既有過程。

2026-10-23 AUTHENTICITY-015D候選：VPS Models歷史可分析既有流量、建立同Canonical跨origin參考Review、明確啟用與跨重啟Undo；Assistant／研究共用零額外HTTP分析。保留既有導航／六分區、scope／Unknown與低資源分段編碼。Mac同源引擎有API，Mac／Android專用原生UI尚未移植；原生／完整願望不提前驗收。

2026-10-22 CONNECTOR-DISCOVERY-015C候選：VPS研究知識正式表單及Assistant共用公開來源辨識／宣告草稿／fixture／真HTTP Review，原connector操作保留並增加跨重啟Undo。Mac內建同源Go引擎取得API，Mac／Android專用原生辨識與Review尚未移植；原功能／tests／負載不減，不能以網頁替代原生驗收。015B已由480bdeac同SHA九項驗所列範圍，本片仍須獨立九項。

2026-10-22 RESEARCH-BRAVE-015B候選：VPS正式研究／排程表單可選搜尋地區及每語言1–10頁、顯示本機共享／目前Key版本每日admission用量與持久恢復紀錄；Mac內建同源Go引擎取得執行能力，Mac／Android專用設定與進度UI尚未移植。原生功能與全部測試／負載保留，不以網頁實圖替代原生驗收。015A已由e384646同SHA九項成功驗所列範圍；本片自己的九項仍待驗，完整研究願望仍PARTIAL。

2026-10-22 NEAR-FULL-015依使用者新要求，下一版須接近完整主要功能；詳NEXT_RELEASE。當前015A候選補本機多輪品質／策略版本固定／可重現排序評估與明確試用／真下一輪監測／持久回復。VPS有正式網頁流程；Mac知識頁有SwiftUI入口，NativeParity原51圖及原測試保留，09圖增加實際評估與新Controller回復；Android仍缺此專用呈現，不借同源後端或網頁當作已移植。新HEAD仍須自己的九項，不單獨發布小修版本；原0.8.5已九項及11附件公開驗收。

2026-10-22 VPS-AA-RECOVERY-014候選：VPS兩個AA更新入口接正式持久任務，每頁checkpoint、限次恢復與原子配額、完整後才更新排名，新增真TLS／Browser8圖。Mac內建同源Go引擎的研究任務受益，但原生直接AA更新按鈕仍同步呼叫；Android原生專用UI未移植。三平台各自原caller／tests保留，新HEAD仍須自己的九項。前0.8.4／e6b0613已九項SUCCESS、公開11附件；不借其結果驗收新片。

2026-10-22 VPS-RESEARCH-ORIGIN-013候選：共用Go引擎在公開來源GET送出前持久檢查共同網站冷卻；網頁新增等待原因／時間及零HTTP用量說明。Mac內建引擎取得同源後端控制，Mac／Android原生專用呈現尚未移植，不以此升級完整爬蟲或願望清單。新schema20保留一致升級備份，本機冷卻不透過同步執行或清除；新HEAD九項與全部原工作量待驗。

2026-10-21 VPS-CONVERSATION-RECOVERY-012候選：VPS網頁新對話ack後即時可用、草稿／附件保留、清單只讀恢復及scope guard；同源引擎可選UUID請求識別，不改DB格式或舊客戶端預設。Mac／Android原生建立入口尚未採用該可選識別，不冒稱新網頁流程已移植；各平台既有caller／tests保留。優先序VPS→Mac→Android，0.8.2前片已公開，新0.8.3仍須本HEAD全部驗收。

2026-10-21 VPS-WORKSPACE-RECOVERY-011候選：VPS網頁新增持續連線提示、last-good／草稿恢復、手機48px刷新、併發合併及GET有界取消。保留Mac中性色／側欄與原生優點；本片網頁行為不冒充已移植Mac／Android原生UI。VPS第一、Mac第二、Android第三；新同SHA九項及三平台0.8.2實際發布前仍候選。

2026-10-21 VPS-RESEARCH-RETRY-009候選：公開GET退避由現有Go引擎持久保存，網頁呈現等待及恢復紀錄；Mac內建引擎同源取得後端能力，原生重試／排程專用呈現仍待對應。Android維持原生能力，不將新網頁UI冒充已移植。0.8.0已公開，controller3219ecf獨立九項成功；新片仍須自己的九項及三平台發布。

2026-10-20 最新 D-014：**VPS 第一、Mac 第二、Android 第三**。VPS-RELEASE-008 將 Mac e408262 的完整原生 App 子樹帶入最新 VPS／Android 交付來源，保留全部平台的原 caller／tests；整合 HEAD 必須重新九項，不借原分支綠燈。Release 同時提供 Linux 兩架構、Mac Universal 與 Android APK，簽署／原生 UI／生命週期及尚未完成差異繼續明示。以下較舊順序為歷史。

2026-10-20 接續 VPS-RESEARCH-SCHEDULE-007：維運 code `034e775` 同 SHA 六項成功；新公開研究排程僅在目前引擎明確啟用，關閉瀏覽器仍由 VPS 服務執行，排程意圖／HTTP 保留量不隨同步帶到另一端。Mac／Android 原生排程管理及 OS 背景能力尚未對應；候選、證據與歷史仍同步，既有各平台優點保留。

2026-10-20 前片指示先 VPS 可用性與後續更新（VPS-OPS-006），原同步核心與各平台優點保留。VPS 增加完整架構套件、schema 前快照、預檢／健康／失敗及中斷復原；此輪只修改 VPS 及維運文件，未把結果外推為 Mac／Android 實機或目標主機部署。較舊優先序為歷史。


2026-10-20同步核心已由VPS／Android `c9d1d33` 六項、Mac `f67cfa4` 九項同SHA驗證；三端各保留本機與離線資料、真目的端vault、持久游標及明確衝突。原生呈現未對應、大型分塊及實機仍PARTIAL；詳細矩陣與證據見`SYNC_CONTRACT.md`及`verification/20261020/sync-ci-baseline.json`。

2026-10-20 較早優先序為 SYNC-001；依 D-012 接續 VPS／Android／Mac 的同步，取代下方歷史片的暫停順序。各平台既有優點和待辦保留；本片新候選同 SHA 九項前不外推全面互通。

2026-10-18，依使用者「其他平台比我們好的留下來繼續搞」建立。這份清單與完整 Master Spec 同時維護；既有功能與各平台優點保留，不能為了外觀一致而刪除。比較必須能指到正式 caller 或真實驗收，不能只用有無 API／選單判斷功能已完成。

依 D-010／D-011，先完成 VPS 的 Mac 0.6.1 正式 UI／操作對照，並保留 VPS 單 binary、持久 SQLite、完整歷史及低配置成本的優點；省資源與免費部署評估見 `vps/RESOURCE_HOSTING.md`。Android 保持暫停。狀態「候選」代表已實作或正在驗證，尚不能當作已交付。

| 優點或落差 | 已核對的來源 | Mac 處理與接續驗收 |
| --- | --- | --- |
| VPS：同頁多表單的草稿、展開與精確選取保留 | `vps/ui_js_provider_forms.go`、`vps/e2e/provider-forms.cjs`、`vps/PROVIDER_FORMS_UI.md` | 012 候選先改善帳戶／Key；表單移出長清單，草稿由 Provider 工作區在記憶體保存，關閉重開仍可接續，成功時不抹等待期間的新稿。端點／Provider 設定及 Review 的全部情境仍須接續 |
| VPS：每份表單有等待、保存成功、失敗及「已保存但刷新失敗」回饋 | 同上，正式 API commit 後刷新失敗的 Browser 驗證 | 012 候選加入局部回饋與只重新讀取清單；成功後不引導重複 POST。驗真 401、真 commit、讀取失敗、重試與關閉後晚完成 |
| Android：清楚標示操作使用的帳戶／Key／群組／模型／端點，不把啟用當成可用性 | `app/src/main/kotlin/dev/modelatlas/app/ui/ProviderDetailScreen.kt` 的 ActionScope／DetailAccountsCard／SelectedScope、`ProviderDetailLoader.kt` | 0.6.0 已改善名稱與 Catalog 操作；012 候選的更新／恢復再次顯示完整帳戶／Key。每把 Key 的 last success／failure、availability／quota／stale 原生摘要仍列高優先，不能由 enabled 推定 healthy |
| Android：busy 時保護互相衝突的動作，未選定 scope 有原因與指引 | 同檔 ProviderModelActions 與 scopeReady／busy guards | 012 候選將新增 Key／逐把啟停／帳戶批次啟停／秘密值更新串成帳戶共用鎖；另一帳戶仍可操作。模型測試／同步／Assistant 跨入口的全部情境仍需分項對照 |
| VPS：Catalog 加入、來源與完整長名稱可辨識，失敗不清草稿 | `vps/CATALOG_UI.md`、`vps/e2e/catalog-experience.cjs`、`macos/Sources/ModelAtlas/CatalogDetailView.swift` | 保留目前精確 Key／Endpoint 加入與既有成員資格；繼續對照長名稱、空結果、缺設定引導與大字，不用 raw UUID 作主要標籤 |
| VPS：永久觀測／請求歷史與完整匯出；大量資料有分頁與記憶體證據 | `vps/PARITY.md`、`vps/receipts.go`、`macos/CHARTS.md`、`ObservationArchiveController.swift` | Mac 已有原生分頁／圖表／收據；保留全量匯出及來源／scope，不為了動畫只保留最近資料。仍需持續驗大資料與各單位／來源的真可讀性 |
| VPS：獨立服務可持續運行；Android 與 Mac 的生命週期不同 | `vps/PLATFORM_DEPLOY.md`、`macos/MASTER_SCOPE.md` | 保留 VPS 部署能力；Mac 休眠不承諾 24/7。背景排程、通知、資格及來源時效按平台實際能力呈現 |
| Mac：原生 Keychain、薄輸入框、IME／Undo、鍵盤選模、分區來源與原生圖表 | `macos/MASTER_SCOPE.md`、`macos/PROVIDER_MOTION.md`、`NativeSmoke.swift`、`NativeParity.swift` | 原有 70 核心／136 實圖與 28 frame 全保留再增加驗證；其他平台改善時也不砍現有 scope、歷史、取消與 last-good |

## 下一步優先序

2026-10-19 接續：`b7444fe` 已同 SHA 六項 SUCCESS／19 Browser／263 PNG 核對，手機對齊與安全區保留。當前 VPS-MODEL-WORKFLOW-005 新增瀏覽器本機常用檢視、可移除篩選條件、最近使用／追蹤快捷鍵及精確連線直達（`vps/MODEL_VIEWS.md`）；屬新候選，不能外推前片驗收。這些流程可作後續 Mac／Android 的操作參考，尚未移植；保留兩平台現有優點與暫停狀態。

1. VPS-MAC-ALIGN-001 已由 3aec657 同 SHA 六項／16 Browser 驗收。本輪 VPS-MAC-PARITY-002 對照全部 Mac caller，補正式表單／完整側欄／模型分區與編輯／來源／比較／服務；新 HEAD 須完整六項／原16 Browser＋新增流程及實圖，不借前片結果。
2. 使用者最新要求本輪留在 VPS；Android 介面未開始，原有優點與待辦保留。
3. 私人站點 adapter 逐站 schema／認證、多 Key availability／quota／stale、研究／Assistant／Reflection 及完整大綱仍追蹤，P1–P10 仍 PARTIAL。

### 原 Mac 接續缺口（保留追蹤）

1. 完成 012 帳戶／Key 的同 SHA 九項驗證及實圖，才更新此片為已交付。
2. 高優先：Provider 設定／端點草稿與晚完成的局部回饋；私人帳戶 adapter 的逐站 schema／認證相容性；多 Key 的 availability、quota、stale 與同名 scope。不能略過版本／role／origin 限制冒充站點已支援。
3. 接續 Catalog 缺設定引導、研究／Assistant／Reflection 的可讀流程及完整大綱 scenario。以 `macos/MASTER_SCOPE.md`、`docs/TRACEABILITY.md` 追蹤完整 P1–P10；仍為 PARTIAL。

每次接續先讀此表與 ACTIVE_TASK，依新驗收證據更新來源、狀態與未驗情境。不得把別的平台未完成規劃當作既有能力；routing／fallback／自動 Key 輪替／Gateway 繼續 future-only。
