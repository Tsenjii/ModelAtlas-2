# ModelAtlas Public — TRACEABILITY

## RESEARCH-DAILY-015F（2026-10-23 候選）

同片審查：ae57b1f候選5.914s真Browser確認跨日重新展開仍留昨天用量；已補展開／前景與原15秒檢查的重讀。原9圖、全部情境／次數與timeout保持，ae候選不接受，修後來源獨立九項。

同片穩定審查：2015e5a完整Go359.498s成功，但全Browser原ModelConnections丟簽到焦點／36.36s FAIL後，28流程成功仍於300.021s逾時；單跑8.415s不能取代全套失敗。追加真GET延遲6.388s確定重現，production保留原同帳戶操作焦點、stale／已完成不恢復舊寫入，原全部操作／請求數／timeout保留；含原MacAlignment與新每日聚焦24.604s成功。2015 CI Browser因Ubuntu套件／CJK下載15min取消、未開始測試；新增CI官方HTTPS鏡像與有界APT下載等待，完整依賴與原job／測試上限不變。詳vps/RESEARCH_DAILY_BUDGET.md；2015不接受，最終新來源獨立全部九項／實際產物。


正式caller：全球研究／Settings研究 → 每日上限／逐憑證設定／變更紀錄API → 原MINIMAL_INFERENCE → execute本機檢查 → 同transaction原run／task HTTP與probe、每日本機ledger、訊息／工具／receipt → 真HTTP。超額保存具scope／用量／UTC reset的BUDGET_EXHAUSTED，尚未發送不扣次數、不消耗網路重試機會；跨日或加上限仍須明確恢復。重用既有流量不重扣，日常聊天不受此研究上限影響。

程式：research_inference_budget.go、conversation_admission.go、playground.go、research_jobs.go／execute、ui_js_research_inference_budget.go與原UI／server；8個後端函式涵蓋跨輪、40個真請求工作／原4-flight、飛行中全域及憑證原子限制、Key版本／模型／Group／端點、不同Account／Key、UTC跨日、DB故障回滾／讀取失敗不授權、503／取消跨重啟不退款、正式worker等待／明確恢復／正常流量重用、真正HTTPS同步／portable與v26一致升級、102憑證分頁／55筆政策變更歷史。新Browser走正式設定→真TLS→拒絕／恢復、409草稿、讀取失敗、跨日reload與原生控制尺寸。失敗與限制見../vps/RESEARCH_DAILY_BUDGET.md；新來源九項及全部產物前不能接受或接下一片。

## RESEARCH-ADMISSION-015E（所列範圍已驗）

來源ba069a1dea4e904d8be4e55b2f8f353c0079f1e9／tree86cd35227d55594a90fa1cf4619e9424f3ed5565，首次九項SUCCESS及全部九份實際產物見verification/20261023/research-admission-baseline.json與PR #20。32 Browser／380PNG、本機Go368.990s／CI432.357s、systemd及原負載均已驗；以下原候選敘述只作歷史保留。

## RESEARCH-ADMISSION-015E（2026-10-23 候選）

正式caller：研究/start → 既有MINIMAL_INFERENCE → 共用execute → 本機內容檢查 → 對話／工具／HTTP與probe reservation／request record原子提交 → 真HTTP與原結果保存。真TLS0.062s與恢復重現0.057s均確認：本機秘密內容拒絕、HTTP=0，卻已扣HTTP／probe各1。修正與必要故障／並行回歸仍待自己的完整同來源九項。完整token／USD與私人排程未完成。

程式：conversation_admission.go、playground.go、tools.go、receipts.go、attachments.go、research_execute.go；5個新函式涵蓋六處DB故障、原20工作／4並行的飛行中預算barrier、503／取消保留量、跨重啟明確恢復與正式worker成功重試新報告。追加worker真反例11.495s原用空報告，修後強化barrier的聚焦race13.795s、vet／27發布回歸成功；規則與失敗歷史見../vps/RESEARCH_ADMISSION.md。原32 Browser／380圖、全部原生與負載保留，新來源獨立驗。

## AUTHENTICITY-015D（2026-10-23 所列範圍已驗）

已驗來源97bcd81c572def925583ce524682bae07ab3eccb／tree64f0f05c96e396b2cdb3d1b56252d74513cc4fbd；同來源九項首次SUCCESS與全部九份產物核對見verification/20261023/authenticity-baseline-v2.json。完整Go race本機332.338s／CI409.646s；32 Browser本機293.688s／CI236.334s、380圖／9新圖，全部原生測試與原負載保留。以下為候選歷史，不外推完整WISH-AUTH-001或原生專用UI完成。

正式caller：Models詳情／歷史 → 共用既有流量分析／參考Review API → 完整scope與本機來源憑據 → traffic-fingerprint-v2 → 不可變報告／參考版本 → 明確CAS啟用／ledger持久Undo。Assistant及原研究最小推論亦用相同零額外HTTP分析。程式為authenticity.go／capture／reference、playground.go、ui_js_authenticity.go；完整規則、失敗歷史與限制見`../vps/AUTHENTICITY.md`。

v2追加3個真TLS管線函式（合計21）：0.221s確定重現Gemini不同wire stream模式／Anthropic不同有效版本被v1誤配，修為授權完成後雜湊實際條件；同實際版本仍可比。v1樣本／參考不升格，跨重啟保留原報告、JSON、request ID重播與Undo，舊參考不能重新啟用。09d本機32 Browser296.678s／CI291.223s只作舊候選記錄，最終新來源仍獨立九項。

18個後端函式含追加綁定origin反例，前17函式race33.416s／含原生協定與舊研究重用45.531s成功；4個新舊Browser13.831s成功，9新圖已檢查含200%確認。資料不足／一致／矛盾與UNPROVEN身份分開；Unknown費用不為0，同步／舊版／取消／換Key不借來源憑據。schema26本機表、v25一致升級、精確USD與真0、跨重啟Undo及輔助DB失敗保留答案已測。原31 Browser／371圖保留，新增後32／380，仍須本來源完整回歸及同SHA九項才接受。

Mac／Android專用原生UI、主動深度probe／預算、語意品質指紋與真站可信基準長跑仍待補，整體WISH-AUTH-001與下一版近完整門檻仍PARTIAL。PR #20保持Draft，發布請求／版本／tag／附件不變。

- b9e8dd3候選已同來源九項、完整32 Browser288.378s／380圖及全部產物核對；仍因追加圖片模型入口7.174s真反例不接受。文字套件現在對非對話操作呈現限制、不顯示分析操作或發清單GET；修後4.000s成功。原全部情境保留，最後新來源獨立九項；詳vps/AUTHENTICITY.md及verification/20261023/authenticity-candidate-b9.json。

- 951候選八項成功，但Browser首次依賴安裝逾時、attempt2的原MacAlignment等待Key回饋失敗（全套31過／261.041s FAIL）。已核對失敗附件12451301729／SHA256 91067710c41f8b0bcd45b5f8532f3d6fc9d7c1830d6e90d612c5a688deff7dad與原圖，並以晚POST回覆及真關閉／刷新／重開32.810s確定重現：DB新值visible不代表帳戶寫入已完成。保留互斥並強化斷言，等按鈕enabled後再送原兩筆rotation；修後10.256s成功，原timeout／所有情境與寫入次數不減。最後新來源另驗，不借951的八項或b9的九項。

## CONNECTOR-DISCOVERY-015C（2026-10-22 所列範圍已驗）

已驗來源294f6aba62c0cf6c27e31a5894476e8dd1116d12／tree681f784d53ca5f00f06f4c0a4a69955b5a2e6f92；同來源九項及實際產物核對見verification/20261023/connector-discovery-baseline.json與PR #20。下列候選過程／失敗保留，不外推全部WISH-CRAWL-002完成。

正式caller：Models／研究知識辨識表單或Assistant discover_connectors → generateConnectorDrafts → publicFetch／共站冷卻 → 確定性scope／單位映射 → 原子版本／來源proof／fixture → 既有connectors/test與Review → 本機receipt／CAS啟用 → enrichConnectors實際Intelligence → ledger持久Undo。詳`../vps/CONNECTOR_DISCOVERY.md`。

| 面向 | 程式與證據 | 狀態 |
|---|---|---|
| 來源辨識／宣告草稿 | connector_discovery.go／connector_schema.go；有界同站HTML／字面線索／OpenAPI GET，family／version僅線索；缺單位／scope不猜 | 本機候選 |
| fixture／真HTTP／失敗保留 | 17個後端函式含TLS與DB故障、精確數值、錯單位／缺欄／認證、衝突／新版本、換Key／origin／取消、request去重與共站冷卻 | 聚焦成功；完整回歸待完成 |
| Review／啟用／回退 | connectors.go、ui_js_connector_discovery.go；本機設定／context／TTL receipt與每次執行前的啟用設定綁定，現有applyVersion／Undo，GET失敗只讀恢復 | 新Browser5.69s與原Knowledge4.29s成功；8新圖已檢查 |
| 保存／同步／升級 | schema25 additive與一致快照；本機reservation／fixture／receipt不移植執行權，原定義同步保留 | 本機候選 |
| 跨平台與界線 | Mac內建同源後端取得API；Mac／Android專用原生介面未移植。私人auth／任意schema／外站source、資格／recurring／身份仍待補 | 整體WISH-CRAWL-002 PARTIAL；同來源九項前不接受本片 |

原30 Browser／363圖、全部原生／Go／負載與timeout不減；新31／371及完整九項獨立驗證，不變更公開版本／request／tag／附件。

- 6e25c55候選本機完整Go race303.798s／vet／27發布回歸0.027s成功，但不能接受：CI Browser119899276577的舊策略監測流程34.27s失敗，畫面顯示啟用後0輪，之後全套在300.022s逾時；失敗附件12434849226（354PNG，SHA256 53c13420e1aeab163d7d78fbe83eb727c6c341e29dff4ee312a88ba06ac007f8）已獨立下載核對並檢視失敗原圖。本機同候選監測5.48s通過，但完整全套仍於30流程後300.022s逾時，原上限保留。
- 已以延後GET的既有Browser流程確定重現5.914s FAIL：按更新後舊版本卡片仍可開啟，Modal捕捉舊0輪，完成讀取不更新該Modal。修為只在最新讀取期間停用四個研究清單的舊卡片，標示aria-busy；成功後新卡片可用，失敗則原last-good卡片恢復。既有14HTTP／3輪退步／Undo斷言與timeout完全保留，追加明確pending gate反例。修後原Knowledge3.46s、新辨識4.92s、原策略監測5.37s（合計14.776s）成功；新來源重新完整Browser及全部九項，不借6e25結果。

- 5f42580候選的完整Browser CI為31流程294.230s／371PNG／零failure圖，成功附件12437525019（24,261,810 bytes，SHA256 5bdc074a130059533d21e2202f03dc78daf3a261518b37ff916d422b217fdc08）已下載核對與檢視新8圖、原三輪下降實圖；本機完整Go322.929s成功。UI修正後的本機全套在27流程通過後300.040s逾時，沒有assertion failure；原timeout與歷史保留，不宣稱本機全套通過。
- 單位審查追加0.070s真TLS反例FAIL：pricing.fixed未明示單位時仍被當USD/request，可能把全0但固定費範圍未知的來源當已知零價格。自動生成改為只在pricing.fixed_unit明示USD/request時映射fixed，並保存literal檢查；缺單位保持Unknown，改成USD/month後實測FAILED。17個後端函式與原Connector聚焦26.198s成功。新來源重新九項；5f42580不是最終驗收，不借其綠燈。


## RESEARCH-BRAVE-015B（2026-10-22 所列範圍已驗）

最終來源480bdeac4a468754315747b8e0b0593f4cf67709已同SHA九項SUCCESS：VPS39789581672 attempt2、Android39789581690、Mac39789581662；30 Browser249.331s／363圖、Android579／976（5原skip）、Mac各82／90圖與完整workload均保留。精確job、成功附件hash與首輪infra取消歷史見ACTIVE_TASK及PR #20。以下本機階段紀錄保留；不外推完整WISH-CRAWL-001完成。

正式 caller：全球研究／持久排程表單 → research/start／schedules → planResearch 凍結語言、query、地區與頁數 → runResearchPending → executeBraveResearchTask → 同 transaction 核准研究／排程每日／Brave共享UTC日／憑證revision用量及page attempt → 真POST → candidate／evidence／本機品質receipt／inspect任務／checkpoint整頁原子提交。詳 `../vps/RESEARCH_BRAVE.md`。

| 面向 | 程式與驗證 | 狀態 |
|---|---|---|
| 逐頁、重啟與排程接續 | research_brave.go／jobs／schedule；SEARCH checkpoint與原retry／AA機制共存，固定頁號與24h期限、每頁3次 | 本機聚焦成功；整片仍候選 |
| 原子用量與失敗 | schema24、chargeBraveRequest／chargeResearchNetworkTx；兩向額度與DB故障回滾、共站冷卻、429／503／body中斷、跨日／換Key共享cap | 本機33.910s與storage3.034s成功；完整驗證待記 |
| 信任與scope | 同步只帶redacted history；本機checkpoint與revision counter不執行同步權；地區／頁數加入策略scope及提案、HTTP200不驗證資格／身份 | 本機真TLS、同步與v23一致升級成功 |
| VPS正式操作 | ui_js_research.go／schedules與dashboard用量；research_brave_browser_test.go→e2e/research-brave.cjs，正式排程設定→429→預算／暫停→reload→只補第二頁 | 聚焦9.55s／8實圖已檢查，完整30流程／363圖待驗 |
| 跨平台／整體 | Mac內建Go引擎同源能力；Mac／Android專用地區與分頁UI仍待對應。原全部測試、負載及timeout保留 | 新來源九項待驗，WISH-CRAWL-001與完整願望仍PARTIAL |

不變更公開版本、release/request.json、tag或附件；PR #20仍Draft、不merge、不部署。未量測token／貨幣／站方剩餘quota，不能把本機1000次保護上限宣稱Brave帳戶額度。來源頁面不提供跨頁快照隔離；只聲明保留實際已完成頁面的證據。

## RESEARCH-EVALUATION-015A（2026-10-22 所列範圍已驗）

依D-015先完成接近完整版本的主要功能，暫不另發小修版本。公開基線0.8.5／699d4d5已九項SUCCESS與11附件驗收。當前片正式caller為網頁研究知識／Mac原生VersionController → POST research/knowledge/evaluate → 本機真HTTP品質receipt與歷史排序重播 → 明確試用／原子重新核對 → 新研究plan固定版本 → 真正使用版本的監測 → 原Undo跨重啟回復。詳細語意、公式、限制與失敗歷史見 `../vps/RESEARCH_EVALUATION.md`。

| 面向 | 程式與測試 | 狀態 |
|---|---|---|
| 具體多輪提案與反例 | reflection.go／research_quality.go／research_knowledge.go；knowledge_evaluation_test.go 空200、3輪recurring／負面／未知與去重 | ACCEPTED（所列範圍） |
| 比較、Review與過期拒絕 | knowledge_evaluation.go／knowledge.go；提案／scope／dataset／active-plan hash，同transaction啟用；不足／regression／新資料／來源變更拒絕 | 本機成功 |
| 實際使用、監測與持久回復 | research_jobs.go／research_schedule.go；舊佇列與排程接續版本／啟用時期固定、新輪使用、3輪下降、重啟Undo、scope改變與30日時效 | 本機成功 |
| 保存與安全 | schema22／23、原子usage回滾、不可變本機證據receipt、同步不複製執行／評估權、v21一致升級／全部歷史與vault保留 | 本機成功 |
| VPS正式介面 | ui_js_knowledge.go → knowledge_evaluation_browser_test.go／e2e/knowledge-evaluation.cjs；503後只讀、跨重整Review／回復、14實際TLS、8圖 | 最終CI29流程269.426s／355圖成功，新8圖已檢查 |
| Mac原生 | KnowledgeView.swift評估／差異與監測／持久Undo；NativeParity保留全部51圖，09圖加入真零樣本評估與新Controller回復 | 最終Mac兩架構各82核心／90圖成功 |
| Android／完整願望 | 原JVM／device及所有既有原生功能保留；新增研究評估專用UI尚未接Android | 不外推完整WISH-REFL-001或P8–P10驗收 |

原28 Browser／347圖、原timeout、全部原生測試與expanded workload保留，新增後預期29／355；最終同SHA `e384646a1435ce72fad2661070dd73a34d44a3d1` 九項已成功：VPS39763537520 attempt2、Android39763537488、Mac39763537343；release-checks39763537367／27回歸成功、publish SKIPPED。首Browser job因依賴下載9m22s觸及原15min而CANCELLED，保留歷史；成功重跑119780617449與成功附件12419494946已獨立驗SHA256。完整job、artifact與原負載見PR #20。維持Draft、不merge、不部署、不更動release/request.json或公開tag／附件。

## VPS-AA-RECOVERY-014（2026-10-22 候選）

正式caller：全球研究／服務更新榜單 → POST research/start → runResearchPending → executeAAResearchTask → getJSON／chargeAARequest → 單transaction研究HTTP／排程每日／AA window／頁次attempt → 真GET → 每頁checkpoint → 完整commitAARanking。直接AA sync API仍為明確同步相容入口；沒有將私人catalog或推論改成自動重放。

| 驗收面向 | 程式與實際測試 | 狀態 |
| --- | --- | --- |
| 部分頁面／重啟／排程接續 | research_aa.go、research_jobs.go、research_schedule.go；research_aa_test.go真TLS、SQLite及Handler | 本機race成功，最終同SHA九項待驗 |
| 用量與有界恢復 | chargeAARequest＋chargeResearchNetworkTx＋chargeIntegrationWindowTx；run／window／每日拒絕全部回滾其他meter，429／503、每頁3次、飛行中斷重啟不清上限 | 本機race成功 |
| 有效排名／時效／安全 | 版本／頁數／duplicate／secret echo／無效JSON／401、Key變更含最後一頁飛行、24h過期、超長Retry-After、完整commit後重播零HTTP；last-good及原期限保留 | 本機race成功 |
| 升級／同步與生命週期 | schema20→21一致快照，公開歷史同步不帶checkpoint／credential revision或intent，儲存缺失不重抓第1頁，direct sync與worker互斥 | 本機race成功 |
| 真實介面與視覺 | ui_js_research.go／ui_js_mac_parity.go兩個更新入口、research_aa_browser_test.go→e2e/research-aa.cjs；預算恢復、暫停過deadline、只重試第2頁、完整排名更新 | 新Browser13.50s，8圖已檢查 |

前片0.8.4來源e6b0613已同SHA九項SUCCESS並公開11附件，詳ACTIVE_TASK與PR #20。原27 Browser／339圖及五分鐘上限、全部原生與擴大負載保留；新預期28／347。仍不將AA分頁驗收外推為完整WISH-CRAWL-001、Brave、未知站點或完整願望已完成；新片CANDIDATE，不merge／不部署。

## VPS-RESEARCH-ORIGIN-013（2026-10-22 候選）

正式 caller：全球研究／排程 → runResearchPending → sourceTask／inspect → publicFetch → chargeResearchNetwork → checkResearchOrigin → SQLite用量保留。真429／502／503／504 → recordResearchOriginFailure持久保存共同下限 → 後續任務origin_wait → 原worker到期再檢查。來源失敗與本機等待分開；等待不生成HTTP、扣額、attempts或失敗evidence。

| 驗收面向 | 程式／測試與真實範圍 | 狀態 |
| --- | --- | --- |
| 跨任務／輪次／重啟／不同網站 | research_origin.go、research_origin_test.go 真TLS／Handler／SQLite；120秒下限、新輪、手動接續、其他origin繼續 | 本機race成功，新HEAD九項待驗 |
| 超長等待／晚回應／scope | 25h與極端數值、相同origin大小寫／443／尾點、不同port隔離、晚成功不清新失敗 | 本機race成功 |
| 排程與同步 | 0 HTTP／attempts、真暫停重啟／明確恢復、同步只歷史與本機冷卻隔離 | 本機race成功 |
| 升級與儲存錯誤 | schema19→20一致快照／歷史保留／無自動啟用；控制資料缺失時不發HTTP | 本機race成功 |
| 研究UI | ui_js_research.go／ui_css.go → 等待網站恢復、時限、零用量、取消／暫停、恢復清除過期提示；research_origin_browser_test.go → e2e/research-origin.cjs，真UI與TLS | 新增Browser成功；補CJK後完整27流程／339PNG與實圖待驗 |

這是 WISH-CRAWL-001 的增量控制，完整項目仍 PARTIAL；Brave／AA逐頁checkpoint與retry、逐憑證quota／推論費用、未知站點、可量測Reflection、authenticity和真站／目標主機／實機仍待完成。原有26 Browser／331PNG、所有原生與資源工作量不減，PR仍Draft、未部署。

## VPS-CONVERSATION-RECOVERY-012（2026-10-21 候選）

正式caller：新對話按鈕／快捷鍵／模型入口 → ui_js_conversation_creation.go newConversation → POST /sessions client_request_id → playground.go addSession／conversationRequestID → 原SQLite sessions主鍵；ack後applySessionRows即時呈現，再以sessionSelectRefresh只讀清單。rereadConversationCreation／持續提示不重播寫入；sessionListVersion與scope guard保留使用者選擇／草稿。無schema變更，舊客戶端保持原新ID語意。

conversation_creation_test.go六項race覆蓋同請求／8路並發、不同scope／title與archive拒絕、重開DB保留history／metadata、非法UUID及legacy caller。conversation_creation_browser_test.go＋e2e/conversation-creation.cjs使用真Handler／SQLite／TLS與測試外層故障，驗確認commit後503、部分body未知結果、header前斷線與傳輸層重送去重、只讀重試、late session／account guard及晚A commit／B草稿。原25／322全部保留，新增1流程／9圖，預期26／331。新HEAD自己的九項＋27發布回歸與三平台實際套件／公開下載前CANDIDATE；完整願望及未部署界線不變。


## VPS-WORKSPACE-RECOVERY-011（2026-10-21 候選）

正式caller `vps/ui_browser.go` api／apiPages → `ui_js_workspace_recovery.go` refresh／workspaceReadStatus → `ui_js_views.go` refreshWorkspaceSnapshot，配合home狀態卡及手機／桌面CSS。只重讀工作區、不重放寫入／推論；核心資料未完整前不覆蓋last-good。`workspace_recovery_browser_test.go`＋`e2e/workspace-recovery.cjs`使用真production handler與測試外層fault barrier，驗503／併發快照／離線／20秒部分JSON取消／提交POST保留與零自動推論，11實圖。原Workspace回歸不改斷言，修正Settings→返回時初始pane可見性；全部原24／311保留，預期25／322。新SHA九項＋27發布回歸／實套件與公開下載前CANDIDATE，驗收見PR #20。schema仍19，完整願望及未部署界線保留。


## VPS-UPDATE-PROVENANCE-010（2026-10-21 候選）

正式發布 caller `release/prepare.py` → `verified_jobs`／`select_artifacts`／`collect` → 完整 Linux／Mac／Android 資產與 manifest。固定 producer、同 run／source 及成功 job 時間窗的唯一產物；保留重跑後未重新執行的成功 build，失敗舊 QA 不能補新 QA 缺失，歧義直接停止。`release/test_artifact_provenance.py` 九項含真 GitHub39536170097重跑／24排序、9個工作與9產物、來源／時間／過期／歧義拒絕及下載前拒絕；原18項完整保留。新 HEAD 的九項＋27發布回歸及真套件重驗前 CANDIDATE，實際驗收 SHA／run／job 見 PR #20。0.8.1 tag／附件不改；不等於已部署主機／完整願望完成。

## MAC-PROVIDER-012（2026-10-18 候選）

Providers → ProviderDetailView → ProviderAccountsView／新增帳戶與Key表單／CredentialRow → ProviderWriteController → 正式 `/accounts`、`/credentials`、`/credentials/toggle`、`/credentials/rotate`。AtlasStore按Provider保留工作區，跨分區／關閉重開仍共用帳戶lease與草稿；只在記憶體。ProviderManagement核心持有完整identity、已知enabled的精確bulk、快照、正確role／固定origin與分開的秘密值／版本payload，保存後的新稿不被清除或重用已提交ID。

保留原70核心與136靜態圖／28frame，新增8核心與6實際畫面；預期78核心／74圖每架構、148靜態圖，原25秒串流／1249字SHA256／閱讀位置／120秒parity不減。NativeParity驗真401、實際SQLite commit／版本、跨Provider及帳戶同Key ID、重複提交與另一帳戶操作、關閉後晚完成、新稿保留、刷新失敗後只讀重試及零Provider HTTP。新HEAD九項與實圖前PENDING。011已由48ff43f／Mac39112956833、VPS39112956835、Android39112956891九項及136實圖／28frame交付0.6.0。

跨平台優點持續清單見 `CROSS_PLATFORM_STRENGTHS.md`；本片細節 `macos/PROVIDER_MANAGEMENT.md`。其餘管理／Research／Assistant／Reflection及完整Master Spec仍PARTIAL。以下候選狀態為歷史。

- **MAC-PROVIDER-MOTION-011 實際站點修復候選：** HJMAPI五個公開介面已匿名核對200；v0公開schema／換行公告／站方根層series與時間窗／真上游狀態／公開與私人查詢分離／Catalog語意／捲動重置接入正式caller。新增8核心／4原生圖，最終70核心與68圖每架構；136靜態圖＋28frame與九項CI尚待修復HEAD驗收，不能用8c1824失敗候選或0.5綠燈代替。詳HJMAPI_COMPATIBILITY.md。

## MAC-PROVIDER-MOTION-011（2026-10-18 候選）

ProvidersView → ProviderDetailView概覽 → AccountCheckinPanel／AccountCheckinController → 正式GET／POST `/checkins/accounts`；來源MANUAL_COMPLETION、完整provider/account/date/timezone與v16→17加法式升級，legacy不借給帳戶。RootView／SettingsView／AtlasTabs／原生ButtonStyle／TrendChartView → AtlasMotion與有效Reduce Motion。

新增AccountCheckinTests八項與Go六項HTTP／SQLite／通知測試；NativeParity正式caller驗A/B隔離、部分／全部完成、401 last-good與取消／重試、模型／對話／草稿與零額外fixture inference。四張新production畫面＋每架構14實際動畫frame，原54核心／120圖全保留，共62核心／64圖每架構，128圖＋28frame；新HEAD九項與圖審前PENDING。已交付010為3529736／Mac39105128601／VPS39105128591／Android39105128634九項SUCCESS、120圖。完整Master Spec仍PARTIAL。詳macos/PROVIDER_MOTION.md。

## MAC-CHARTS-010（2026-10-18 候選）

Models → ModelDetailView → ModelPricingPanel／ModelPerformancePanel／ModelObservationHistory → TrendChartView／TrendPointer／ObservationArchiveController → 正式 `/models/observations`、`/models/detail`、`/telemetry/detail`。價格圖置頂，真時間／單位／零與缺值、滑鼠固定／鍵盤逐點、精確JSON與真收據；永久價格／觀測以固定snapshot的keyset分頁及全scope／來源／類型／時間查詢，不再把最近200筆當全部。詳細界線見 `macos/CHARTS.md`。

Go四項新HTTP／SQLite測試與全測試、vet本機通過；新增10核心、5張真production畫面及NSView滑鼠／鍵盤事件，保留原44核心／55圖及全部25秒串流／120秒parity guards。預期54核心／60PNG每架構、120圖；新HEAD九項與實圖前PENDING。已交付009為52d5bfd，Mac39090603877／VPS39090603936／Android39090603909九項SUCCESS、110圖已檢視。完整Master Spec仍PARTIAL。

## MAC-NATIVE-009（2026-10-18 候選）

使用者明確更新Mac視覺／主色要求見D-007。正式入口為共用SwiftUI元件與Appearance→Root／Settings／各native sheets、Models選取→ComparisonView／ComparisonController→既有 `/models/compare` →正式inference／telemetry／receipt。原始JSON比較改為按完整scope對齊的並列數值、失敗／未知與非串流、當次價格／各單位扣款／獨立額度快照、收據及匯出；取消／last-good／重複提交guard不改上游路由。

新增6核心與5實際native captures，預期每架構44核心／55圖、共110圖；保留全部38核心／原50圖及25s串流／120s parity。新HEAD九項與實圖前PENDING。基線008為8eb68087，Mac39058446721／VPS39058446728／Android39058446846九項SUCCESS／100圖已檢視並交付。完整Master Spec仍PARTIAL；Provider與完整Research／Assistant／Reflection等缺口見 `macos/MASTER_SCOPE.md`，本片細節見 `macos/NATIVE_POLISH.md`。

## Mac 原生入口（2026-10-17 候選，獨立於 Android／VPS 驗收）

| ID | 正式 caller／測試 | 驗收條件 |
|---|---|---|
| MAC-NATIVE-001 | SwiftUI `ModelAtlasApp` → `RootView`／Playground／Providers／Models／Settings／Research／Assistant → `AtlasStore` → `AtlasClient` → bundled production engine；原生視窗smoke與十一實圖 | Universal arm64／x86_64，macOS12部署目標；同一artifact在兩種Mac架構執行，不用WebView／mock transport，不能以Linux交叉編譯稱Mac執行 |
| MAC-UI-002 | `ModelPickerView`／⌘K／緊湊三工作區 → `ModelSearchEntry`／`AtlasStore.chooseModel` → 原 full-scope API；真單元＋native store caller／十一實圖 | 搜尋全部詞、全半形／大小寫／別名；AppKit ↑↓／Enter／Esc、完整 scope cursor、空結果拒選及 marked-text guard（真 field-editor key events＋單元測試）；同 model/key 跨帳戶／provider 不合併；未送草稿／附件保留，舊對話草稿恢復；非法未加入 scope 拒選。雙架構同 artifact／全部九項同 SHA gate 與 22 PNG review 前 PENDING，詳 `macos/UI_REVIEW.md` |
| MAC-CHAT-003 | `ChatTranscriptView`／`ChatScrollProbe` → `ChatReadingState`；`ChatMarkdown` → `MessageMarkup`；file picker／`ComposerFileDrop` → `AtlasStore.addAttachments` → `LocalAttachment`；訊息 sample_id → `RequestReceiptView` → `/telemetry/detail`；核心回歸＋正式TLS串流／真NSScrollView／附件批次／same-scope收據／18 PNG | 本輪候選 PENDING；新 HEAD 同 SHA 九項 CI與雙架構36圖 review 前不得 ACCEPTED；保留舊11圖、全部scope／keyboard／cancel／lifetime／Keychain斷言，詳 `macos/CHAT_EXPERIENCE.md` |
| MAC-LIFETIME-001 | `EngineController` → `desktop` config → OS動態loopback listener／0600 readiness／stdin EOF → HTTP／worker／SQLite收尾 | 真Go程序測試＋兩Mac程序restart／writer拒絕；App無常駐daemon，不繼承PORT對外開埠；保持原平台／本機模式認證 |
| MAC-SECRET-SCOPE-001 | `KeychainVault` → Security.framework／SecRandom、完整`ModelScope`、禁redirect/cookie/cache/proxy的native client | 真Keychain roundtrip／missing-vault拒絕重生、A/B同KeyID隔離、SSE與實際cancel／history／vault reopen、Unknown不當0／Free、預設背景通知OFF |

候選須同SHA九項CI（Mac build／arm64 test／Intel test＋原VPS四項＋Android兩項），實際PNG人工review後才驗收；exact SHA／run／artifact由Mac PR body保存。Apple Developer ID／公證、macOS12實機與部分進階專用表單不在本片已驗範圍，詳`macos/README.md`。

> 此檔是公開 repo 新起點的粗粒度驗收銜接；**不是**原 private repo 85 項逐項驗收 ledger 的完整轉儲。原始完整 ledger 保留於 private repo。後續 public slices 的 acceptance 逐項追加，未經新 repo 完整 CI 不得標 accepted。

| Phase | 來源 Private Ledger（僅歷史參考） | 公開版驗收 |
| --- | --- | --- |
| P1 Foundation | 7 / 7 ACCEPTED | Migrated baseline full-green `44c45ec` |
| P2 Playground | 4 / 5 ACCEPTED；PLAY-004 PARTIAL | PLAY-004 **OPEN** |
| P3 Providers | 10 / 10 ACCEPTED | Migrated baseline full-green `44c45ec` |
| P4 New API | 10 / 10 ACCEPTED | Migrated baseline full-green `44c45ec` |
| P5 Dedicated Adapters | 7 / 7 ACCEPTED | Migrated baseline full-green `44c45ec` |
| P6 Models | 9 / 9 ACCEPTED | Migrated baseline full-green `44c45ec` |
| P7 LMSpeed | 5 / 5 ACCEPTED | Migrated baseline full-green `44c45ec` |
| P8 Global Crawler | 14 entries未標 ACCEPTED | NOT ACCEPTED |
| P9 App Assistant | 10 entries未標 ACCEPTED | NOT ACCEPTED |
| P10 Self-Improvement | 8 entries未標 ACCEPTED | NOT ACCEPTED |

## Verified same-SHA run

- Public initial snapshot: `44c45ecb1842e1ec866e056e4b4447028411dd1e`.
- Actions [run 38238187606](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38238187606)：build SUCCESS；device-tests SUCCESS。
- 功能一律以 production caller + executable tests + same-SHA complete green + code review 才能承認 ACCEPTED。

## 已驗收的公開版 correctness repair

| Requirement | Production caller / 修復 | 主線 SHA / 完整 CI | 結果 |
| --- | --- | --- | --- |
| SEC-001 / selected auth | `AtlasViewModel.sendWithImage/send` → `loadProviderModelAuth`；ACCOUNT PAT/其他帳號 ref 在 HTTP 前拒發；`AtlasAnonymousTargetTest`。 | `8f8fba6` · [Run 38246838016](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38246838016) build+device success | ACCEPTED（權限邊界） |
| PLAY-002 / selected group | Playground 真實 `InferenceTarget` 帶 credential/group/canonical/endpoint/operation，`PlaygroundInferenceTargetTest`、`AtlasAnonymousTargetTest`。 | `f0e7bc6` · [Run 38242610860](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38242610860) build+device success | ACCEPTED（scope mapping） |
| PLAY-002 / chat scope isolation | `AtlasViewModel.applySelectedContext` 清 conversation + cancel in-flight，`AtlasAnonymousTargetTest` group A→B 正反流程。 | `b0286b0` · [Run 38250063202](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38250063202) build+device success | ACCEPTED（聊天隔離） |
| PLAY-004 / Vision first slice | `PlaygroundComposer` local content picker → `AtlasViewModel.sendWithImage` → `InferenceEngine` → `OpenAiTransport`；`VisionImagePayloadTest`、`PlaygroundVisionGateTest`、`PlaygroundVisionPickerUiTest`、`PlaygroundVisionFlowDeviceTest`。 | [PR #8](https://github.com/Tsenjii/ModelAtlas-2/pull/8) 已合併 `cd31394`；[候選 SHA 6a4f173 CI](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38261796603) build + device 成功；[主線 CI 38269841531](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38269841531) build + device SUCCESS | Vision UI 已合併；整體 PLAY-004 PARTIAL（檔案／音訊／工具／結果 UI 未完成） |

| PLAY-004 / Vision local payload guard | `VisionImagePayload.isValidForSend` 與 VM 前置拒絕非法圖片／遠端 URL；`VisionImagePayloadTest`、`PlaygroundVisionFlowDeviceTest`。 | `f32522c` · [Run 38273286105](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38273286105) build+device success | ACCEPTED（邊界防護）；PLAY-004 整體仍 PARTIAL |
| PLAY-004 / Vision chat follow-up | `AtlasViewModel.sendWithImage/send` 對全部對話歷史重算 Vision 需求；同 scope Vision 接續文字可正常傳輸，撤銷能力即拒絕；`PlaygroundVisionFlowDeviceTest`。 | [PR #13](https://github.com/Tsenjii/ModelAtlas-2/pull/13) 候選 `767fdbd` · [CI 38323252180](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38323252180) build+device SUCCESS；main `ec0464e` 的 [CI 38324128166](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38324128166) 因另一個 PK27 測試多執行緒崩潰為 RED | 主線已由 PR #15 修復並在 `3925ff9d`／[38392145254](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38392145254) 與後續 `069b541a`／[38397137563](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38397137563) build/device 全綠 |
| PK27 / Compose observer thread failure | `Pk27CheckinBindingTest` 改用 Compose Test rule 原生 `waitUntilAtLeastOneExists/waitUntilDoesNotExist`，避免 instrumentation thread 直接輪詢 semantics tree；保留全部 checkin A/B scope 斷言。 | 本輪主線紅燈修復 PR 候選；必須同 SHA build/device 全綠 | ACCEPTED：`3925ff9d` [main run 38392145254](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38392145254) build + device SUCCESS |

| MMR / refresh follower join race | `AtlasViewModel.requestMyModelRefresh` attach existing flight 即發 Joined；Android `MyModelsMetadataRetryStateTest` C1/C2/C3/D2/D4 確保 leader release 前已完成 join，同時保留零額外 fetch/扣額 assertion。 | [PR #15](https://github.com/Tsenjii/ModelAtlas-2/pull/15) 初輪 [38324922179](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38324922179) build 綠/device 紅；後續 [38386332526](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38386332526) 最新候選待驗 | ACCEPTED：`3925ff9d` [main run 38392145254](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38392145254) build + device SUCCESS |

| PLAY-004 / #14 new scoped conversation | `AtlasViewModel.startNewConversation`、`PlaygroundTab`／Composer 48dp action、`AtlasScreen` callback、Android Vision→text HTTP 回歸、Compose 草稿/Busy 驗證；僅清選定連線 history 與 UI，刷新草稿 generation，不碰 telemetry。 | [PR #16](https://github.com/Tsenjii/ModelAtlas-2/pull/16) `495cf315` [PR CI 38394507003](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38394507003) build/device SUCCESS；main `069b541a` [CI 38397137563](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38397137563) build/device SUCCESS | ACCEPTED（新對話切片）；PLAY-004 整體 PARTIAL |

| PLAY-004 / Files PDF local first slice | `LocalPdfPayload.prepare/isValidForSend`（4 MiB、PDF magic、安全檔名、無 remote fileId）；`PlaygroundComposer` content picker／48dp PDF、`AtlasViewModel.sendWithFile` exact FILES gate → `InferenceEngine` → OpenAI wire，保留 scope & telemetry；`LocalPdfPayloadTest`／`PlaygroundVisionGateTest`／`PlaygroundVisionPickerUiTest`／`PlaygroundVisionFlowDeviceTest.localPdfRoomVmEngineHttpAndRevokedFileCapability`。 | [PR #17](https://github.com/Tsenjii/ModelAtlas-2/pull/17) `f8984e1` [PR CI 38401105133](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38401105133) build/device SUCCESS；main `d7dd460a` [CI 38403442111](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38403442111) build/device SUCCESS | ACCEPTED（PDF 第一切片）；PLAY-004 整體 PARTIAL |

| PLAY-004 / Audio Input local WAV/MP3 | `LocalAudioPayload.prepare/isValidForSend`（WAV/MP3 header、4 MiB cap、Base64＋路徑/filename guard）；`PlaygroundComposer` Android GetContent audio/*（不用 RECORD_AUDIO）＋窄屏自適應 media row、可取消/失敗狀態；`AtlasScreen` exact AUDIO_INPUT，`AtlasViewModel.sendWithAudio` 保留歷史音訊能力 gate／scope auth，透過正式 `InferenceEngine`/OpenAiTransport；`LocalAudioPayloadTest`／`PlaygroundVisionGateTest`／`PlaygroundVisionPickerUiTest`／`PlaygroundVisionFlowDeviceTest.localAudioRoomVmEngineHttpAndRevokedAudioCapability`。 | 分支 `feature/play-004-audio-input-20261013`，PR/head build/device 與 merge 後 main CI 尚未驗收 | PENDING；Audio Output、Tool UI、Generated Result UI 仍開放 |

## 公開版新增 requirement 記錄規則

每片新增一行包含：Requirement ID、相關 Spec、main SHA、實際變更檔、確認的 production caller、測試 case、同 SHA Actions run、review finding、結果（PARTIAL/ACCEPTED/OPEN）。


## VPS 重建版 2026-10-14（**不得與 Android 已驗收 P1–P7 混算**）

- 舊 MVP [PR #19](https://github.com/Tsenjii/ModelAtlas-2/pull/19) 已關閉且工作分支重置（沒有合併）；全新 [PR #20](https://github.com/Tsenjii/ModelAtlas-2/pull/20) 從已驗證的 main `5009e0c8a733f1863adcf9a5b8ef8c450da76d3d` 開始。
- `vps/store.go`、`schema.go`：Go/SQLite WAL、FK、durable migration schema；`server_test.go` SQLite reopen persistence。
- `vps/server.go`、`catalog.go`：auth/loopback/AES-GCM vault、Provider/Account/Credential Scope、Catalog != My Models、真 provider models sync；`server_test.go` auth/secret isolation/scope test。
- `vps/playground.go`：真實上游 HTTP chat SSE streaming、history、telemetry、TTFB/TTFT、user cancellation/parse failure classification、per-session in-flight race lock；`integration_test.go` 實際 TLS HTTP protocol fixture，非假的 production caller。
- `vps/research.go`、`research_gate.go`：candidate/evidence/run 與真 provider catalog probe；Unknown ≠ Free，實際 quota 類 evidence 不足不宣稱 main eligible；AA ingest / automatic crawler **NOT ACCEPTED**。
- `vps/assistant.go`：可逆的 My Models explicit preview/confirm/undo；完整 LLM Assistant **NOT ACCEPTED**。
- `vps/recipe.go`：allowlisted recipe version / explicit rollback + action ledger；自主 Reflection **NOT ACCEPTED**。
- `vps/ui.go`、`ui_css.go`、`ui_browser.go`、`ui_js_views.go`、`ui_js_chat.go`：teal 繁中輕量 Web UI，三分頁、深淺色、窄屏、48px、安全文字渲染。
- `vps/deploy/`：systemd unit + installer，未具使用者 VPS 遠端存取，不標「已部署」。
- 中間候選 `9b55edb41a84ac0ae5b120b2b0e8c20a06ed20eb` [CI 38444562106](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38444562106)：**vps-test + vps-linux-amd64 + vps-linux-arm64 success**；新回歸 commits 必須由新 SHA 同等測試並另外驗 Android build/device。
- **整體 VPS Android parity = PARTIAL**。VPS 分支不可因已有 schema/mock/form 就當成 Android 功能全追平，PR 先維持 Draft，只有同 SHA 雙平台全綠且 product slice acceptance 後才可合併。

| VPS-NEWAPI-001 | `vps/newapi.go` version-gated rc.25 read-only HTTP with separate account credential and inference credential, allowlisted persisted facts; `vps/newapi_test.go` TLS integration | CANDIDATE; await exact HEAD CI, further New API coverage pending |

## VPS 續作 2026-10-14

| Requirement | Production caller / tests | 狀態 |
|---|---|---|
| VPS-PARITY-001 | `vps/playground.go`、`attachments.go` → `/infer`；`parity_test.go` 的 media-history/cancel/104-message/zero-usage tests | IMPLEMENTED；latest PR HEAD CI gate |
| VPS-PARITY-002 | `onboarding.go`、`credentials.go`、`config.go` → Preview/Confirm/Batch/custom-auth/rollback；TLS / SQLite tests | IMPLEMENTED；latest PR HEAD CI gate |
| VPS-PARITY-003 | `newapi.go` → rc.25 exact pricing + group + perf；nil-map regression / PAT role tests | IMPLEMENTED；latest PR HEAD CI gate |
| VPS-PARITY-004 | `adapters.go`、`intelligence.go`、`lmspeed.go` → durable enrichment / scoped sources；real TLS caller / budget tests | IMPLEMENTED；latest PR HEAD CI gate |
| VPS-PARITY-005 | `manage.go`、`backup.go` → check-in / settings / safe export + atomic disabled-ref restore | IMPLEMENTED；latest PR HEAD CI gate |
| VPS-PARITY-006 | `ui*.go` → real Browser API flow；`browser_test.go`、`e2e/browser.cjs` → onboarding/chat/lifecycle/narrow/dark | CANDIDATE；必須 `vps-browser` 成功，禁止將本機 Chrome 限制失敗說成已驗收 |

目前 Android 主線本身尚未 production ACCEPTED 的 PLAY-004 剩餘結果 UI / P8 / P9 / P10 不在這些 entry 內偽升格。詳情 `vps/PARITY.md`；同 SHA Actions evidence 由 PR #20 最新 body 保存。

VPS 本片回歸補充：Browser `38503882118` 發現 new-session/composer race，修復 `a938fef` 後 `38504248117` 的 Go test／amd64／arm64／browser 全 SUCCESS。其後源碼核對補上 Android 已註冊 media transport、RUNNING→INTERRUPTED、single writer、Unknown empty traffic／相同分位／custom window、last-good>200 failures、stale assistant undo；**最終 HEAD 仍必須重新取得雙平台同 SHA CI，不能沿用 `a938fef` 綠燈。**


## VPS 媒體結果 UI 續片（2026-10-14）

上一個 parity 基線 `9a46804` 已由 VPS run `38508548084` 四項與 Android run `38508548193` 兩項同 SHA 全綠；本片由使用者「繼續」授權，僅 VPS。

| Requirement | Production caller／回歸 | 狀態 |
|---|---|---|
| VPS-MEDIA-UI-001 | Models endpoint／能力確認 → `ui_js_media.go` → `/infer/media` → PNG/JPEG/WebP 預覽與下載、multipart edit、multiline transcript 下載；Browser 三操作真 TLS／375/1024／dark/light/200% | IMPLEMENTED；最新 HEAD 六項 gate 由 PR #20 body 保存 |
| VPS-MEDIA-SAFE-001 | `media.go` 完整 raster 驗證／先限像素與 byte、所有選定 secret echo、operation-file guard；`media_test.go` 壞／截斷／SVG／custom secret／revoked-cap tests | IMPLEMENTED；不借舊 SHA 綠燈 |
| VPS-MEDIA-CANCEL-001 | request-id → shared flight／cancel registry → upstream socket close → durable CANCELLED；真 TLS 阻塞／四 flight／Chat 共用／uptime 排除回歸 | IMPLEMENTED；不借舊 SHA 綠燈 |

原始媒體與轉寫結果僅在記憶體；URL-only 不自動抓取、不轉送 Key。Android 整體 PLAY-004 與 P8/P9/P10 acceptance 不變。下一片為音訊輸出或 tools，需單獨接 caller/UI 與新 SHA 完整驗證。


VPS-MEDIA-UI-001／SAFE-001／CANCEL-001 **ACCEPTED（僅 VPS 切片）**：code `00f3dc205e4a59057edcf15e2de4a2135b49d4d0`，[VPS run 38526114795](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38526114795) 四項 SUCCESS＋[Android run 38526114819](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38526114819) build/device SUCCESS。Media UI、實際下載 bytes、多行 transcript、input-preserving clear、strict modal／a11y selectors、narrow/dark/200%、selected-scope／cancel／flight budget 全部覆蓋。末尾 doc-only HEAD 仍查 PR #20 當前同 SHA checks，不能借 code 基線結果冒充它。


| VPS-PLAY-FULL-001 | `protocols.go` / `tools.go` / `audio.go` / `vectors.go` → existing `/infer` / `/infer/media` → 4 conversation protocols / tool-result continuation / speech / embedding / rerank；`protocols_test.go` 真 TLS / exact scope / zero usage / truncation / signature / secret guard，Browser 實際新入口 | IMPLEMENTED，待本候選同 SHA 六項 CI；不能借 Android 或前片驗收 |


VPS-PLAY-FULL-001 **同 SHA CI 已驗**：`29eff56`／VPS `38550451082` 四項＋Android `38550450921` 兩項 SUCCESS；TLS native/tool、多模態結果及真 Browser 入口已驗。

| VPS-RESEARCH-RUN-001 | `research_jobs.go` / `research_execute.go` / `research_runtime.go` / `public_fetch.go` → `/research/start,control,sources,funnel,review` → 真 worker / bounded requests / complete funnel / sources / evidence / manual review / scoped traffic reuse；`research_runtime_test.go` 真 TLS / budget / socket pause / durable restart / no implicit membership；`e2e/research.cjs` 正式 UI | IMPLEMENTED，待新 SHA CI |
| VPS-RESEARCH-AA-001 | `aa.go` / `integration_budget.go` → AA V2 free API、完整同版本分頁、dynamic top4／ordinary regression／expiry、fixed-window budget、last-good；AA TLS tests | IMPLEMENTED，待新 SHA CI；不硬編 family，不借舊榜排名 |

VPS-RESEARCH-RUN-001／AA-001 同 SHA CI 已驗：`fa7c291`／VPS `38558907867` 四項、Android `38558907976` attempt 2 兩項 SUCCESS；未聲稱 Android P8 完整驗收。

| VPS-ASSISTANT-FULL-001 | `/assistant/config,run,secrets,actions` → `assistant_runtime.go` / allowlisted tools / common engine / shared onboarding Review / stale-safe reversible metadata與憑證參照操作；`assistant_runtime_test.go` 真 TLS／多工具／vault boundary／確認與 Undo／不中斷重播／真 socket cancel，`e2e/assistant.cjs` 正式 UI | IMPLEMENTED，待新 SHA 六項 CI |

VPS-ASSISTANT-FULL-001 同 SHA CI 已驗：`69652c9`／VPS `38567589731` 四項、Android `38567589525` 兩項 SUCCESS；最新候選仍查新 SHA。

| VPS-REFLECTION-001 | `reflection.go` / `knowledge.go` → durable per-run report / source snapshots / bounded learned priorities / version activation / stale-safe Undo / exact alias rules / source-as-data；`reflection_test.go` / 真 Browser | IMPLEMENTED；待新 SHA 六項 CI |
| VPS-CONNECTOR-001 | `connectors.go` → safe GET / bounded JSON Pointer / role + exact model/group / draft / real test / approved activation / official-data source namespace / common Catalog & enrichment / failure history / Undo；TLS＋Browser | IMPLEMENTED；待新 SHA 六項 CI |
| VPS-CREDENTIAL-EPOCH-001 | `endpointAuth` 同一查詢取得 cipher/custom fields/version，shared inference/media telemetry；research reuse 限當前 credential version，rotation TLS 回歸 | IMPLEMENTED；待新 SHA CI |
| VPS-LIVE-KILO-001 | 明確 opt-in 的 anonymous real catalog + common engine + native tools + shared App Assistant action Review；合成資料 6 次 HTTP／26.31s 已通過 | LIVE WORKTREE VERIFIED；不以外部結果取代 deterministic 回歸或冒充 recurring-free／Taiwan／identity proof |

VPS-REFLECTION-001／CONNECTOR-001／CREDENTIAL-EPOCH-001 同 SHA CI 已驗：`293bd516`／VPS `38572762140` 四項、Android `38572762176` 兩項 SUCCESS。

| VPS-MANAGE-FULL-001 | `ui_js_models.go` + `offer_summary.go` + settings／control actions → 真 filter/ranking/notes/tags/Provider 編輯，scope/external unit guard；`management_test.go` 與 `e2e/management.cjs` | IMPLEMENTED；待新 SHA CI |
| VPS-BACKGROUND-001 | `background.go` → metadata queue／bounded period／全 OFF 規則／持久 transition events／本機 notifications caller；TLS queue 與 epoch/dedupe tests | IMPLEMENTED；待新 SHA CI |
| VPS-BACKUP-V2-001 | `backup.go` / `backup_extra.go` / export → safe v1/v2 definitions、完整 transaction／invalid rollback／停用 credentials & network／重新验证 imported rules；roundtrip 與 Browser 真下載 | IMPLEMENTED；待新 SHA CI |
| VPS-RESOURCE-001 | `performance_sql.go` 全量保留統計／bounded chart、SQLite cache/temp／Go soft memory／flight limits／paging／gzip，1000-history invariants＋`footprint_test.go` 100000 samples + standalone TLS inference real RSS | IMPLEMENTED；CI 實測待驗，不以軟限制冒充 hard RSS 保證 |

VPS-MANAGE-FULL-001／BACKGROUND-001／BACKUP-V2-001／RESOURCE-001 同 SHA CI 已驗：`8f069625`／VPS `38582129810` 四項＋Android `38582129791` 两項 SUCCESS。SSR 单一執行檔十萬歷史＋四推論 profile peak 29,134,848 bytes，媒體負載仍待最終量測。

| VPS-LIVE-RESEARCH-001 | opt-in `live_research_test.go` / isolated `vps-live.yml` → actual anonymous Kilo model→App tools→public official sources→durable worker/evidence，fresh catalog price/tools guard，10 model HTTP/12 source HTTP budgets，no signup/credentials/implicit My Models | CANDIDATE；本機模型工具已跑，但 direct DNS fail，Actions 待驗 |

VPS-LIVE-RESEARCH-001 真實 opt-in已驗：`b7ecdaa`／Actions `38584572569`／job `115923745767`，2 candidates/3 evidence/3 SUCCESS tasks，未宣稱自動通過eligibility；普通同SHA六項全綠。

| VPS-MULTI-ORIGIN-001 | `origins.go` / schema11 / shared credential auth / immutable endpoint bindings / selected PAT / connector snapshot / safe backup → sameProvider多URL、shared Review/Undo不搬Key；`origins_test.go` realTLS與`e2e/origins.cjs` | IMPLEMENTED；待新SHA CI |
| VPS-AGENT-SOURCE-001 | static `save_research_source` / anonymous `onboard_provider` → public definition/durable runner/shared Review，public DNS-pinned client、noRawSecrets/noImplicitMembers；TLS/public boundary/registry regression | IMPLEMENTED；待新SHA CI |
| VPS-GROUP-EPOCH-001 | certified explicit token group（origin＋currentmaterialversion＋freshness），matchingGroup／inference／offerguard、失敗來源留evidence/lead | IMPLEMENTED；待新SHA CI |


多網址／Group epoch／Agent public source 片 `d3da38c50a90976de5eac95c764b7cac66f80ddb` 同 SHA 六項 SUCCESS：VPS `38590860925` test `115945054314`、amd64 `115945054916`、browser `115945054937`、arm64 `115945055188`；Android `38590861082` build `115945620723`、device `115945553268`。現在整合剩餘 Playground：原生 Gemini 語音 CURRENT／LEGACY／雙人 metadata、宣告式 CUSTOM 對話、逐段 system/max_tokens/streaming 設定；共用 scope／工具／取消／telemetry，Assistant 固定 system 不受手動設定覆寫。新增 TLS／Browser／safe backup 與全服務 heavy media RAM profile；本片待新 SHA CI，不把前片或未執行的 footprint 當驗收。


## VPS 完整 V1 code 驗收（2026-10-15）

`ce2ca91a8ff21606bcc335c76d3e6dd47bc0ada8` 同 SHA 六項 SUCCESS：VPS `38612813568` test `116020220644`、amd64 `116020220452`、arm64 `116020220414`、browser `116020220318`；Android `38612813557` build `116020220795`、device `116020219982`。七個 Browser workflows 均成功。剩餘 native speech／CUSTOM／manual session settings、Catalog exact-response 與 security／reflection recovery 均已接正式 caller 並驗收。完整功能、實測 RAM/Kilo、限制與安裝見 `vps/DELIVERY.md`；這不改 Android 本身 PLAY-004／P8–P10 的状态。無獨立外部 reviewer／無 merge／無目標 VPS 部署證據。

Standalone：idle 26,714,112、chat 30,040,064、research 30,273,536、images 97,619,968、peak 162,525,184 bytes（155 MiB），binary 13,439,138；完整保留10萬samples，4 chats／65 Catalog／4 simultaneous 4MiPixels images／4 simultaneous 4MiB audio。Live Kilo `38612880443` 真成功 41.19s、6 inference HTTP＋7 public HTTP、3 candidates/4 evidence/3 SUCCESS tasks；Groq/OpenRouter200，Cerebras307 FAILED線索也保留，不當eligibility。

末尾交付提交含資料與文件、200% decorative mark overflow 修整及 screenshot transition 固定；必須再查此新 HEAD 的六項 gate，不以 code 基線代替文件 HEAD。


## VPS 對話／請求完整體驗驗收（2026-10-15）

以下僅屬 VPS；Android source 仍 `5009e0c8`，Android PLAY-004／P8–P10 的驗收狀態不變。最新 code **`57ef23e96c335413003b497b06cd99b7b8838b6e`** 同 SHA 六項 SUCCESS：[VPS 38631190515](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38631190515) test `116082947267`、browser `116082947583`、amd64 `116082947725`、arm64 `116082947595`；[Android 38631190578](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38631190578) build `116083047901`、device `116083048486`。

| Requirement | Production caller／測試 | Review 與結果 |
|---|---|---|
| VPS-CONVERSATION-001 | `conversations.go`／`history_export.go` → `/sessions,update,export` → `ui_js_conversations.go`；`conversations_test.go` 1,100 則完整歷史、revision／busy guard、archive／restore／secrets；正式 Browser 真搜尋／管理／下載 | ACCEPTED；只查手動對話、不自動加入 My Models、不刪資料；完整匯出採 sequence index／rowid cursor、每批釋放 DB，修復大量 OFFSET 重掃逾時 |
| VPS-MARKDOWN-001 | DOM-only renderer → chat／older／read-only；Browser 真 TLS Markdown、整段訊息與程式碼 clipboard、HTML／危險 URL／remote requests guards，375／1024／深淺／200% | ACCEPTED；解析有界，超限保留純文字，不使用 innerHTML／執行腳本／抓遠端圖 |
| VPS-REQUEST-RECEIPT-001 | common chat／Test／Compare／Assistant／media → `startRequestRecord`／`completeReceipt`／message links → `/telemetry/detail` 與 `ui_js_receipts.go`；真 TLS／Browser actual vs estimate、零／未知、rotation／before-after／cache | ACCEPTED；價格依當次 material epoch／origin／scope／時間凍結，站方實際／nominal／effective estimate 分開，缺值不杜撰，不額外發 billing HTTP |
| VPS-EFFECTIVE-PRICE-001 | `offer_summary.go`／`pricing.go`／`intelligence.go`／`connectors.go`／`background.go` → Models FREE／manual price／connector Review／notifications；TLS＋Browser zero 未確認／明確確認的 reactive regression | ACCEPTED；Catalog nominal zero 不轉 Key FREE；effective 只收同 Key／Group 的確認證據，connector 需 INFERENCE／真測試／Review；通知全 OFF 預設不變 |
| VPS-REQUEST-RETRY-001 | explicit `/infer retry_id` → latest failed same manual session／scope／lineage → shared engine；TLS 兩次失敗後成功、stale／success 零 HTTP；Browser 429→明確重試→成功／reload | ACCEPTED；只一次 user input、三個獨立 attempts、不可隱式 fallback；archived／busy／撤銷能力仍拒絕 |
| VPS-REQUEST-EXPORT-001 | `/telemetry` scoped filters／50-row UI → `/telemetry/export` full NDJSON；SQLite TEMP rowset snapshot／rowid pages；501+／concurrent mutation tests＋100,008 standalone export | ACCEPTED；固定選取集合、不漏／不重複、結尾件數完整；只清暫存索引，永久 raw history 不動；串流錯誤中止下載 |
| VPS-RESOURCE-EXPANDED-001 | `footprint_test.go` 真 stripped binary／PID RSS：100k samples、4 chats、4×31 MiB 完整 TLS context、11k 訊息／100,008 請求匯出、65 Catalog、4×4 MiPixels 圖片、4×4 MiB PCM→WAV | ACCEPTED；保持原 workload／timeout，峰值 426,102,784 bytes（406.36 MiB）≤512 MiB，待機 25.36 MiB；192 MiB 只是 soft limit，OS／target VPS 未量測 |

本輪自行 diff review 與實際截圖 review 已完成；沒有獨立外部 reviewer。九個 Browser workflows 全過，artifact `11925724015` digest `7067477b6d9489978eba1ded1d2cc25b1320f9980e8fcdd28032295a8a41a4ca`；expanded footprint artifact `11926546113` digest `050c01c61e50ed7b365d83f871551d72987719f018a7b505206b31dc36f4d933`。精簡數據／job／已檢視畫面索引在 `vps/verification/20261015/`。

含新收據 engine 的真匿名 Kilo Provider research：code `a39fa2a`、Actions `38629907160`／job `116078568504`，20.06s SUCCESS，6 Kilo HTTP＋7 public HTTP、3 candidates／4 evidence／3 SUCCESS tasks。Groq／OpenRouter 200，Cerebras 307 不跟隨並保留 FAILED；不是 recurring-free／Taiwan／strength／identity eligibility 證明。Marker 已關閉。最後文件 HEAD 的六項 CI 另依 PR #20 最新 checks／body，不把 code SHA 結果外推；無 merge／無使用者主機部署。


## VPS 串流／閱讀與快捷操作切片（2026-10-15）

基線 `5c616053496f37e1a0af44d3b9614bc6432c3800` 已同 SHA 六項全綠（VPS `38680781659`、Android `38680781661`）。本片獨立驗收，不借基線結果；最新 HEAD／run／job／Browser DOM 指標以 PR #20 checks／body 為準，同 SHA 六項全成功才 ACCEPTED。Android 僅含下述必要初始化修復，main／產品phase不變，無 merge／無目標主機部署。

| Requirement | 正式 caller／驗證 | 驗收條件／review |
|---|---|---|
| VPS-STREAM-DOM-001 | `sendChat`／`streamText` → common `/infer` SSE；原生 frame／timer batching、appendData、終止 flush／reader cleanup；`chat_experience_test.go`／`e2e/chat-experience.cjs` 3,000 真 TLS deltas | 第一個2,000 delta burst DOM writes <500、完整文字替換=0；clipboard／107則 JSON export 與完整原文相等；不裁字、不加入 framework、不增加模型 HTTP |
| VPS-CHAT-READING-001 | `bindChatExperience`／`chatChanged`／`olderMessages`／`loadChat` → 調整 scroll follow、明確回最新、ResizeObserver／keyboard／touch；真 Browser 上讀後再接1,000 deltas | 閱讀位置不變；初次／換對話到最新；未變 metadata refresh 保留原訊息 node／較早頁面；375／1024／深色／200% screenshot，隱藏頁面有 timer／最終 flush |
| VPS-CHAT-COMPOSER-001 | 真 start 後可草擬下一則；global shortcuts／IME／repeat／busy／modal guards、Escape → existing explicit cancel API／fetch abort | 生成中不重複送出、不切 scope；draft 完成後仍保留；IME／Shift+Enter／Ctrl／Meta 測試，modal Escape 不停模型，下一次 Escape 真關上游並保留 CANCELLED；共2次模型 HTTP |

設計參考與原生 API 官方連結在 `vps/CHAT_UX.md`；實作自行撰寫，無第三方 source 複製／新 production dependency。測試 fixture 只提供合成資料與分階段 barrier，全部 inference 經正式 Handler／engine／vault scope／SQLite；不是攔截假前端回應。服務 RAM 仍跑原有擴充 standalone profile，不把 DOM 指標當成 RSS 改善。


| ANDROID-VM-INIT-001 | `AtlasViewModel` observers 在全部 instance fields 完成後才啟動，兩個原 init body／相對順序完整保留；h5 四次 Main.immediate 建構、已存 membership／certified endpoint、exact operation／不charge／不新增inference或membership | 修復 `979a8a6`／Android `38685239940` device `116258469234` 的真 NPE；不加delay／drop emission／catch吞錯，h4 原斷言保留。新 HEAD 需完整同 SHA 六項 CI |


## VPS 工作區與頁面重整（2026-10-15）

基線 `5522c7f`／VPS `38686585333`＋Android `38686585247` 同 SHA 六項全綠。本片 production／review／畫面證據見 `vps/WORKSPACE_UI.md`，最新 HEAD 六項 gate 以 PR #20 checks／body 為準，不能借基線全綠。

| Requirement | 正式 caller／回歸 | 驗收條件 |
|---|---|---|
| VPS-WORKSPACE-UI-001 | `ui.go`／`ui_css.go`／`ui_js_workspace.go` → 三主分頁內的互斥 tabs、原生 icon／dialog／popover、清單與設定分類；`workspace_test.go`／`e2e/workspace.cjs`＋原十 Browser | IMPLEMENTED，待新 SHA 全六 CI／十一 Browser；375／1280／dark／200% 真截圖；arrow/Home/End、aria selected、native Esc、草稿與 session 保留 |
| VPS-COLLECTION-UX-001 | 真 My Models／Catalog／provider data → 名稱 index、圖示、同來源已知 metrics、快速 FREE／最愛、完整 filter reset、選取時批次工具列、Global 精確匹配分頁與真空狀態入口 | IMPLEMENTED；Unknown 不當零／Free，My Models 與 Catalog 不混；新 Browser 選取／清除／filter／source guard，原 compare／Review／confirm／Undo 全保留 |
| VPS-APPEARANCE-001 | `appearance` → 原有 teal tokens、系統／淺／深 theme，localStorage 僅非敏感外觀偏好，storage 不可用仍可切換 | IMPLEMENTED；瀏覽器 reload 保留 explicit theme／恢復 system，無 server setting／排程／通知變更，原 OFF defaults 與 safe backup 測試保留 |

不增加 production dependency／migration／模型 HTTP；新增 Browser 使用合成名稱與 samples，絕不冒充真 Provider 能力與服務績效。Android 僅沿用基線的 init-order 修復，本片不改 Android source／phase；未 merge／未部署。

| ANDROID-ROLLBACK-HARNESS-001 | `CredentialRollbackFlowTest` 真Main建構／清除／操作、state.first掛起等待取代busy idle loop、AtomicBoolean fixture flag、四輪enable/disable的Room／vault／blocked／兩個投影一致性、safe timeout階段診斷 | 修復本片gate遇到的 `0e7acf6`／Android38692621025 device116281827382 15秒timeout；不改production、不增加timeout／skip／重試，不刪T1–T5斷言；新HEAD完整六項待驗 |

| VPS-CHAT-SESSION-RESET-001 | `newConversation` 成功時同步清 UI／新標題／新段預設與閱讀狀態，`chatChanged(force)` 立即隱藏舊 jump；chat-experience 真保存舊system/777→立即new→空system/512/true、舊設定完整保留，原 immediate hidden 斷言不改 | 修復5613943／38694380170 的跨session舊閱讀控制瞬現；history DB不刪／不加模型HTTP；新HEAD同SHA六項gate |

Android rollback harness `5613943`／[38694380099](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38694380099) build `116287408298`＋完整device `116287407961` 同SHA SUCCESS；新UI HEAD仍需獨立驗收，不能以舊綠燈代替。


## VPS 詳情工作區與非同步操作（2026-10-15，候選）

以 `c609cf2` 同 SHA 六項全綠為起點；此表不改 Android phase 的驗收。

| ID | 正式 caller／行為 | 驗證與狀態 |
|---|---|---|
| VPS-DETAIL-NAV | `ui_js_detail_workspace.go`／`ui_js_details.go`，模型及 Provider 各四分區，真 aria tablist／左右／Home／End；Provider 僅列該站已加入模型，端點保存後即更新清單 | 第十二個 `TestBrowserDetailWorkspaceWorkflow`；待新 HEAD 六項 CI＋實圖 |
| VPS-DETAIL-READ | 明確 loading／error／retry；同模型時間窗只更新實測區，保留 metadata／notes／capability 草稿；每次讀取有本視窗及 request 序號 guard | 真 Handler／SQLite，首讀503後重試、阻塞1h後7d先返回、有效自訂範圍、無效範圍HTTP前拒絕 |
| VPS-DETAIL-SOURCE | nominal／effective 明確分開且精確字串；last-good／latest／Provider／Account／external／pools 來源獨立；My Uptime 樣本與視窗、p50/p90/p95／60s observed獨立，不假造Unknown | 三有效＋一取消樣本，7d66.7%、自訂50.0%、他模型無樣本；失敗不清有效價格，保留全部四紀錄 |
| VPS-DETAIL-ACTION | 同scope metadata／caps／Key啟停仍真API；晚完成 lifecycle、preview 或 Catalog add 不重開／關掉新的詳情；Key rotate 後刷新當前投影 | 真 mutation 先提交後阻塞回應，換模型後釋放；原十一 Browser assertion 全保留，只調整新分區入口；待同SHA全部綠燈 |

產品接線、截圖來源及限制見 `vps/DETAIL_UI.md`。Exact SHA／Actions runs／jobs／footprint 由 PR #20 最新 body 記錄；沒有最新六項 SUCCESS 不標 ACCEPTED。

## VPS Catalog 探索與加入（2026-10-15候選）

- Caller：`ui_js_catalog.go`由正式`refresh`／`bindCatalog`接線；Provider空態直達同Provider Catalog；manual form與Catalog共用安全Key／origin-bound endpoint選擇。
- 真實驗證：`catalog_experience_test.go`／`e2e/catalog-experience.cjs`，第十三Browser；108候選、100列分頁、metadata-only篩選、精確membership、缺Key／端點真CTA、503重試、真commit晚回應、手動B-origin與草稿、歷史不刪、零推論。
- 本機race／vet及同SHA六項CI、實圖驗收與RAM以本片PR #20證據為準；不把外部模型派工或舊基線當CI。外部DeepSeek匿名401、Muse官方OpenCode CLI403均無任務成果，沒有將其標為審查通過。

## VPS Provider 表單與保存回饋（2026-10-15 候選）

| ID | 正式 caller／驗證 | 驗收條件 |
|---|---|---|
| VPS-PROVIDER-DRAFT-001 | `ui_js_details.go` 原六表單 → 帳戶／Key／toggle／endpoint／rotate／config 真 API → 局部 projection 更新；第十四 Browser 保留六原始 form nodes／草稿／disclosure／選取 | 不重建 dialog；只清自己已保存的敏感欄位；新帳戶更新選項，其他草稿可編輯；舊十三 Browser 全斷言保留 |
| VPS-PROVIDER-FEEDBACK-001 | `ui_js_provider_forms.go` → inline status／alert、generic JSON error、pending controls／WeakSet；真503重試與SQLite commit後延遲 | 錯誤 viewport 可見；非法JSON零POST／無secret片段；重複requestSubmit零額外寫入；stale config不能重開Q |
| VPS-PROVIDER-KEY-SCOPE-001 | rotation selector → 穩定Provider×Account×Credential值＋完整帳戶／Key／Group label；A/B同Key ID fixture → 真vaultrotate | 只改B ciphertext與兩版本，A ciphertext／零版本不變；membership／telemetry完整、零Provider HTTP；375／1280／dark／200%幾何與實圖 |

基線03307f1六項已綠；本片仍以 PR #20 最新 HEAD 的完整六項／十四Browser／實圖與原expanded workload為準。無merge／目標VPS部署，產品語意及Android phase不變。

| VPS-SETTINGS-DRAFT-RACE-001 | `refresh`／`loadStatus` → 逐欄dirty guard；`settings-form` save以revision確認是否仍同一稿；workspace真Handler回應／settings commit後延遲 | 修復db21a33／38751952139的舊workspace UTC→Asia/Taipei競態，原斷言保留；固定barrier驗晚status／重開保留UTC，保存途中下一稿Asia/Tokyo仍留UI，SQLite僅UTC、一次POST、背景／通知仍OFF。新HEAD全部六項gate |

## VPS Git 平台部署相容性（2026-10-15 候選）

| ID | 正式 caller／驗證 | 驗收條件 |
|---|---|---|
| VPS-DEPLOY-001 | repository-root `deploy/build.sh` → pure-Go binary → `deploy/run.sh` exec → `runApplication`；`deployment_test.go` 實際執行兩命令 | Go 1.26+；平台 opt-in 才讀 PORT／public bind；一般模式保留 loopback，非法設定與 bind 失敗非零退出；SIGTERM 正常關閉 HTTP／worker／SQLite |
| VPS-DEPLOY-HTTPS-001 | `deploymentConfig.handler` 包住正式 `Server.Handler`；真 TLS reverse proxy 保留 Host／覆寫 X-Forwarded-Proto | Host／Origin／HTTPS 前置拒絕仍保留原管理認證；healthz 只曝既有健康資料；固定 master key、絕對 DB，真 API mutation 後兩程序 reopen／vault decrypt，無 secret log／GET |

新 HEAD 須完整同 SHA 六項 CI；具體證據由 PR #20 body 保存。Infrlo 控制台在此環境不可讀，官方公開 README 無 Go／ingress／persistent volume 契約；不將本機代理測試、512 MB／2 GB 宣傳數字當作目標平台驗收，不標已部署。



## MAC-USABILITY-004 — 2026-10-17

| Requirement | Production caller / tests | 狀態 |
| --- | --- | --- |
| MAC-USABILITY-004 / conversation search | `RootView` task(id: query/state) → `AtlasStore.searchSessions` debounce/cancellation → real `/sessions`；`NativeSmoke.verifySessionSearch` 無 Enter 即出結果、archive/clear、current scope/history/draft 不變 | CANDIDATE，新 HEAD 九項同 SHA + 原生42圖 |
| MAC-USABILITY-004 / model picker | `ModelPickerView` reset/count/eligible providers/duplicate name IDs + revalidate exact active scope；原鍵盤/IME與新增filtered/empty實圖 | CANDIDATE；原18圖保留，兩架構各21圖 |

基線 `a00b038` 九項與36圖 ACCEPTED；Android/VPS新UI已依使用者指示暫存，沒有整合。詳 `macos/USABILITY.md`，最終驗收由最新PR #21 body記錄。


## MAC-FEEDBACK-005 — 2026-10-17

| Requirement | Production caller / tests | 狀態 |
| --- | --- | --- |
| MAC-FEEDBACK-005 / settings | Chat menu → `AtlasStore.openChatSettings` → `ChatSettingsView/Controller` → 真sessions settings/update；`ChatSettingsDraft` / `ChatSettingsTests`；NativeSmoke真401/retry/invalid前後API相同/A與B隔離/下一稿與history保留 | CANDIDATE，新HEAD九項與50圖驗收 |
| MAC-FEEDBACK-005 / modal actions | `ModelAtlasApp` commands及composer以`hasPresentedSheet`停用會切換context／傳送的快捷鍵；Store原scope與engine不變 | 本片原生code review＋原全部keyboard/IME regression |

詳 `macos/SETTINGS_FEEDBACK.md`；0.1.3基線d719bb9已ACCEPTED，其他平台UI仍暫存，最終evidence由PR #21最新body保存。


## MAC-PARITY-006 — 2026-10-17

| 範圍 | 正式 caller → 驗證 | 驗收 |
|---|---|---|
| 媒體完整選項與結果 | MediaOptionsView／MediaDraft／AtlasStore.send → infer/media → 真TLS fixture；空文字音訊、current/legacy/雙人、speech speed/format/instructions、dimensions/top_n、圖片生成與multipart編輯、完整下載 | 新HEAD候選；原18核心＋11新核心與原25圖＋17新圖／架構，九項同SHA及84圖review後接受 |
| 管理與精確來源 | ModelFilters／CatalogDetail／原生ModelEditors／BatchImport／ProviderEditors → 既有正式API；NativeParity exact-account批次confirm/undo、ABA stale、原對話、ALL/archive/scope清單及匯出；原history/IME/keychain/lifetime全部保留 | 不把Catalog當My Models、Unknown當零、不同origin或account的同Key ID合併 |
| 研究／知識／外部服務 | ResearchRequest／ResearchDetails／KnowledgeView／VersionController／Services → 正式來源、候選／證據、判讀、預算／控制、versions、connectors、integrations API；NativeParity真擷取測試→啟用→intelligence caller→復原、證據知識版本／stale／復原、實際佇列pause/resume/cancel及無推論預算、加密Key及安全Assistant參照 | caller不是只讀JSON；高階知識／CUSTOM資料保留與VPS同範圍的JSON定義。驗收查PR #21最新exact SHA/run/artifact |

對照基線 Android `5009e0c8`、VPS `026f58ba`；不據此聲稱 Android 本身 P8–P10／PLAY-004 整體已驗收，不 merge、不啟用未經使用者選擇的背景或外部推論。歷史失敗與修復均保留於 `macos/FEATURE_PARITY.md`。


## MAC-DESKTOP-007 — 2026-10-18 候選

完整產品定位與原文Part I–IV對照見 `macos/MASTER_SCOPE.md`；MAC-PARITY-006的既有功能聯集驗收不代表整份Master Spec已完成。已確認原生缺口包含完整可讀quota pools／價格與效能history charts、Compare並列結果與部分管理摘要，保持PARTIAL。

`ComposerTextEditor`依AppKit排版30–144pt、原生捲動／clear shrink／IME／Undo；`AtlasStore.focusComposer`→⌘L、⌘O選檔、側欄toggle保留scope/draft/history；`ModelsView`顯示引擎已知price_state與cache另計、Provider依已知免費連線篩選。保留29核心／25原smoke／17parity圖，增3圖及真AppKit adaptive/focus/sidebar／exact-account effective-price guard，合計90圖。新HEAD九項同SHA／圖審前不ACCEPTED，不改Android P8–P10狀態。


## MAC-INTELLIGENCE-008：原生模型 Intelligence 功能

| 需求 | Production caller 與驗證 | 狀態 |
|---|---|---|
| 模型價格／額度／來源／歷史 | Models → ModelDetailView → ModelIntelligence → ModelPricingPanel／ModelQuotaPanel／ModelObservationHistory；同一正式detail API、可展開原始evidence；6項核心含跨帳戶／群組／端點／單位／版本／Unknown／last-good | CANDIDATE；新HEAD九項與100實圖待驗收 |
| 個人效能趨勢 | performanceSQL bounded400個原樣本指標 → ModelPerformancePanel原生Path／資料表；全量統計、rolling60s、cancel與missing值不變；Go新scoped-window/non-streaming guard＋原1000樣本保留guard；NativeParity真TLS歷史／custom窗／無額外inference | CANDIDATE |
| Kios案例研究 | docs/KIOSAPI_RESEARCH.md 保存一手來源與驗證缺口；不因免費群組公告、第三方標籤或模型名稱宣稱selected-key FREE／主結果資格 | RESEARCH ONLY；無已認證連線或台灣實測結論 |

這片功能不代表完整Master Spec已完成；Compare並列、Provider批量管理／簽到摘要、完整研究／Assistant／Reflection情境仍需後續完成與驗收。

MAC-INTELLIGENCE-008同片回歸：5cd93e7 Intel長串流25秒失敗，上游1249字finished而App1083字、offset0。AtlasClient串流改bounded StreamDelivery（128 wire events／2MiB）、相鄰純text合併／metadata與control保序、finish／cancel drain；新增3 StreamDeliveryTests，核心合計38每架構，原100圖與時間／hash／reading斷言不減。新HEAD重新九項，舊八綠不代替。

34cab84未交付：Mac三項／38核心每架構／100實圖檢視、VPS四項與Android build成功，device的PK27 choose(B)在selectedId已更新但Dispatchers.IO detail scope未回來時立即assertNotEnabled失敗。改用既有Compose harness waitUntilAtLeastOneExists(choice and isNotEnabled)，原15s、不改assert／B→A／checkin帳戶時間戳／DB斷言；Android production未改。新HEAD重新九項，不盲重跑。

## VPS-MAC-PARITY-002（2026-10-19 候選）

完整 Mac production UI/caller 對照見 `vps/MAC_PARITY.md`。以已驗 VPS 3aec657、Mac 74e2c37 為參考，補全域對話搜尋／分頁與記憶體草稿、獨立 Provider 編輯、六分區模型／來源切換／完整模型與 quota 欄位、精確圖表匯出、私人用量／Recipe／服務／並列比較。原 16 Browser 保留語意、fixtures、timeout 與完整 workload；UI 重排只更換真實入口，不以 force click／sleep／刪斷言通過。新增 `TestBrowserMacParityWorkflow` 使用正式 Handler／SQLite／vault／TLS 驗跨帳戶歷史、205 價格點、額度零／未知、私人用途隔離與真比較。新 HEAD 六項／實圖前仍 CANDIDATE；未 merge／未部署，完整 Master Spec 仍 PARTIAL。

| VPS-MAC-PARITY-002 資源與部署接續 | 使用者追加省資源／CF／GitHub；`request_json.go` 完整 Unicode context 分段編碼、HTTP Content-Length／replay／秘密邊界；`syncHistory` SQL 分頁與 UI 單次輪詢；原子附件與重試草稿。官方方案見 `vps/RESOURCE_HOSTING.md`。 | 原 expanded workload 與六項同 SHA gate 保留；新增 wire／secret／replay／encoded-size／持久 history pagination 測試及 31 MiB 受控編碼 benchmark。最新驗收以 PR #20／套件驗證檔為準；完整 Master Spec 仍 PARTIAL。 |

## VPS-MANAGEMENT-UX-003（2026-10-19 候選）

前片 `b3c16e9` 的六項同 SHA SUCCESS、17 Browser／134 PNG 及原 expanded workload 已核對，證據見 `vps/verification/20261019/b3c16-baseline.json`。自行 review，無獨立外部 reviewer；本片不能借用前片結果。

| Requirement | 正式 caller／驗證 | 驗收狀態 |
|---|---|---|
| VPS-MODEL-CONNECTIONS-003 | `modelDetail` → `ui_js_model_connections.go` → 既有 My Models 完整 scope 摘要 → 原 `openComparison`／`models/compare`；真 TLS 五站／32 scope／分頁／零未知／取消／相近模型隔離／四列上限／完整匯出 | 已由 `14d0769` 六項／18 Browser／142 PNG 驗收；原批次選取與原十七流程保留，新 HEAD 仍另驗 |
| VPS-CHECKIN-RESILIENCE-003 | Providers 今日進度／逐帳戶完成 → 既有簽到 API；請求合併、last-good、跨日／時區 guard、完成後 invalidation；被阻塞的真 GET／真 commit／503／瀏覽器跨日 | CANDIDATE；錯誤不清同日有效狀態，過期日期零 POST，不執行站方簽到、不刪歷史、不改 schema／OFF 通知預設 |

## VPS-MOBILE-ALIGN-004（2026-10-19 候選）

前片 `14d0769` 六項同 SHA SUCCESS、18 Browser／142 PNG 及原 expanded workload 已核對，證據見 `vps/verification/20261019/14d076-baseline.json`。本片依使用者「所有介面都要對齊」，新增共用手機版樣式與真實全頁面幾何驗收；不能借用前片結果。

| Requirement | 正式 caller／驗證 | 驗收狀態 |
|---|---|---|
| VPS-MOBILE-SPACING-004 | `appCSS` → `mobileAlignmentCSS` → 三主頁、設定六區、Catalog／研究／知識／Assistant、詳情／選模／編輯／Review／收據；第十九 Browser 的十一種尺寸／200%／深淺色／直橫向瀏海與手勢安全區 | CANDIDATE；頁面／欄位／卡片／dialog 邊緣、48px 控制、標題與關閉無重疊；原十八流程保留 |
| VPS-MOBILE-NAVIGATION-004 | `workspace`／`detailTabs` → `revealWorkspaceTab`；ResizeObserver／方向鍵／Home／End；窄版橫向單列 tabs | CANDIDATE；選中分頁可見、零整頁橫向溢出、原草稿／資料 scope 不變 |
| VPS-MOBILE-FORMS-004 | 五種原生 dialog 共用標題／內容框架；短視窗單一 scroller；真 onboarding 預覽、兩連線 TLS 比較與第二層 receipt | CANDIDATE；保存／確認／傳送可到達，成員及歷史保留；非實體手機／鍵盤裝置驗收，未 merge／部署 |


## VPS 模型查找→比較→使用（2026-10-19）

前片 VPS-MOBILE-ALIGN-004 code `b7444fe9be8f6a397521422aa40e340a908807ca` 已由 VPS `39253363969` 四項及 Android `39253363984` 兩項同 SHA SUCCESS，19 Browser／263 PNG 與原 expanded workload 已自行檢查，無獨立外部 reviewer。精確證據見 `vps/verification/20261019/b7444fe-baseline.json`；只代表該 code SHA。

| Requirement | 正式 caller／驗證 | 狀態 |
| --- | --- | --- |
| VPS-MODEL-WORKFLOW-005 | `ui_js_model_views.go` → explicit localStorage view save/update/delete/apply、whitelisted display filters、active chips／recent／watchlist；`ui_js_model_connections.go`／`ui_js_mac_parity.go` → `openModelConnection` → original picker／change path → scoped sessions／drafts；`model_views_test.go`＋`e2e/model-views.cjs` 真 Handler／SQLite／vault／TLS、精確 Key／Group、storage error／stale option、no implicit inference/session、existing history／drafts／membership、320–1280／200% 實圖 | CANDIDATE；新 HEAD 六項同 SHA、原十九＋新增第二十 Browser／實圖及原 expanded workload 待驗；Mac／Android／完整 Master Spec 不升級 |

## 原願望清單補充（2026-10-19，需求登錄）

使用者要求把完整功能盤點的缺口補回原文檔；完整需求與逐項驗收在 [`ASTRA_MASTER_SPEC.md`](ASTRA_MASTER_SPEC.md) Part V。以下只登錄需求，不代表新增 production 功能或驗收通過；原有 requirement、P8／P9／P10 及平台狀態全部保留。

| Requirement | 既有基礎與待補正式流程 | 必要驗收 | 狀態 |
| --- | --- | --- | --- |
| WISH-CRAWL-001 | VPS `research_schedule.go` 持久公開 discovery／HTTP 上限／重啟／來源確認；`ui_js_research_schedules.go` 正式啟停／紀錄；metadata sync 獨立；詳見 `vps/RESEARCH_SCHEDULES.md` | 本片真 TLS／跨日／重啟／來源與工作同步／UI 候選；完整推論／費用／逐憑證 quota、地區／並行／退避及原生生命週期仍待完成 | PARTIAL：VPS 公開第一階段 code `b1e0c66` 六項／23 Browser／303圖已驗；完整原驗收條件保留 |
| WISH-CRAWL-002 | `research_execute.go`、`assistant_tools.go`、`connectors.go` 已有來源與提案基礎；待補未知站點識別到有效 mapping 的端到端流程 | fixture＋有界真 HTTP、scope／單位／auth、Review／啟用／失敗保留／回退 | OPEN：待完成／待驗收 |
| WISH-REFL-001 | `reflection.go`、`knowledge.go` 已有報告與版本；待補跨輪具體策略生成、比較評估、啟用及監測閉環 | 品質／成本基線、下一輪實際採用版本、失敗提案拒絕與退化回退 | OPEN：待完成／待驗收 |
| WISH-AUTH-001 | `research_execute.go` 最小 inference 與 metadata；待補獨立 fingerprint suite 及可信跨 Provider 對照 | 正反例／真站對照、版本／成本／confidence、矛盾與不足判定 | OPEN：待完成／待驗收 |
| WISH-ACCEPT-001 | 現有 CI／Browser／footprint 只涵蓋已執行範圍；待補真站長跑、實際部署及各平台／實機完整矩陣 | Requirement→正式 caller→測試／實圖／真站證據→同 SHA 必要 CI／review；保留所有未驗情境 | OPEN：待完成／待驗收 |

盤點 code 基準 `ae0849294fdcf4f48c0cf549de797e59afd53ecb`：VPS [39258806970](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/39258806970) 四項及 Android [39258806978](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/39258806978) build／device-tests 同 SHA SUCCESS，僅支持既有切片；不作上述五項的 ACCEPTED 證據。本次文件提交的 CI 另查 PR #20 最新 HEAD，不外推前一 SHA。

## SYNC-001 跨平台同步核心（2026-10-20 已驗範圍）

| Requirement | 正式 caller／驗證 | 狀態 |
| --- | --- | --- |
| SYNC-001 | VPS Settings→workspace API／持久journal；Android Settings→DeviceWorkspaceSync→Room／Keystore→WorkspaceSyncWorker；Mac Settings資料→WorkspaceSyncView→loopback AtlasClient→共用Go HTTPS／Keychain。真TLS雙向／第二原生端／精確ID與origin／匿名與scoped移除／4MiB歷史／雙方衝突／真背景等待與取消／重啟／撤銷；21 Browser、Android973、Mac各82核心及152靜態圖＋28frame | 所列核心由VPS／Android `c9d1d33` 六項與Mac `f67cfa4` 九項同SHA成功；整體仍PARTIAL，未對應的原生呈現、大型分塊與部署／實機尚未驗收。Part V二十八項不因此完成。 |

精確job／artifact／內外層SHA256、量測界線與限制見`docs/verification/20261020/sync-ci-baseline.json`。這些是code基線，不代替後續文件HEAD的完整CI。保留所有原測試、timeout、完整context、歷史與workload；本片未有獨立外部reviewer、未合併PR／部署。

## VPS-OPS-006 日常部署與可回復更新（2026-10-20 已驗 code）

| Requirement | Production caller / evidence | 狀態／邊界 |
| --- | --- | --- |
| VPS-OPS-006 | `deploy/package.sh`→同 commit／架構 VERSION／SHA256→`install.sh`／`update.sh`→`manage.sh`；`operations.go` env／完整 snapshot／副本 migration／restore／authenticated readiness；`store.go` schema 寫入前自動快照與版本／vault guard；`system_status.go` maintenance gate＋worker；Settings 資料狀態 last-good／retry | ACCEPTED（所列 code／CI 範圍）：`034e775fafc5a51af0f62753389508dfed24c739` VPS 39420196524 四項＋Android 39420196530 兩項同 SHA SUCCESS；976／0 failure／0 error／5 原 skip，22 Browser／295圖、完整 race／vet／真 systemd 40.84s 與原 workload。證據 `verification/20261020/vps-ops-ci-baseline.json`；目標主機／平台部署及完整 P1–P10 未驗，無自動安裝未驗證 HEAD |


## VPS-RESEARCH-SCHEDULE-007 持續公開研究排程（2026-10-20 已驗 code）

| Requirement | 正式 caller／測試 | 狀態與界線 |
| --- | --- | --- |
| VPS-RESEARCH-SCHEDULE-007 | Models／Settings 研究 → `ui_js_research_schedules.go` → schedules APIs → `backgroundTick` → `researchScheduleTick` → 同 transaction enqueue／去重 → 原 `runResearchPending` → 真 HTTPS／每日保留 → candidate／evidence／summary。`research_schedule_test.go`／`research_schedule_browser_test.go` 包含跨日、重啟、人為暫停、取消真 HTTP、兩端同步及自己 ACK、定義 revision／來源確認、每輪接續、正式策略啟用和手機實圖 | ACCEPTED（所列第一階段）：code `b1e0c669ffea48575875e0c16b7da6980aca5552`／VPS 39448158004 四項＋Android 39448157661 兩項，同 SHA SUCCESS；14 情境／23 Browser／303圖／976項原生／原 workload／真 systemd，證據 `verification/20261020/vps-scheduler-ci-baseline.json`。禁止自動推論，不外推整體 WISH-CRAWL-001 或 P8–P10 完成；目標主機／實機未部署驗收。後續 HEAD 各自重新六項 gate |

## VPS-RELEASE-008 三平台完整交付（2026-10-20 候選）

| Requirement | 正式 caller／必要驗證 | 驗收界線 |
| --- | --- | --- |
| VPS-RELEASE-008 | release/version.properties → 各平台 build／實際 Android aapt2＋apksigner、Mac plist／BuildInfo／同源引擎、Linux RELEASE.json；release.yml → prepare.py 等同 SHA 九項 → 固定 CI artifact digest／全部套件 → publish.py draft／完整 upload digest／公開 prerelease → 真 HTTPS download.sh／bundle-check；release/test_release.py 保護 skipped／舊 SHA／舊綠燈／外站 token／缺平台／雜湊／路徑／tag／附件 | 新整合 HEAD 各自九項＋release-checks／實際發布成功後才能接受；即時精確 SHA／run／job 見 PR #20／RELEASE-MANIFEST.json。原測試／timeout／全部歷史與 expanded workload 保留；未合併 PR／未遠端部署。Android 固定正式簽署與 Mac 公證未設定，不外推完整願望清單完成。 |

本片 APK 資訊解析的真產物診斷：SDK37 改用 V2 Signer:，舊文字解析的兩個候選未驗收。正式 parser 改用已驗 APK 的公開 PEM／DER 指紋及工具交叉比對；真 APK 和兩 Linux／Universal Mac 產物預檢已通過，精確 run／job／artifact／限制在 verification/20261020/three-platform-release-preflight.json。這只支持解析與套件完整性診斷，最終整合 HEAD／三平台公開 Release 仍各自需要完整 gate；不將舊候選的診斷當正式接受版本。

## VPS-RESEARCH-RETRY-009 公開來源恢復（2026-10-21 候選）

| ID | 正式 caller 與回歸 | 狀態／界線 |
| --- | --- | --- |
| VPS-RESEARCH-RETRY-009 | publicFetch→安全typed HTTP failure→runResearchPending→research_retry.go→result_json延後／歷史；carryScheduledTasks跨預算輪保留；ui_js_research.go等待／結果／局部更新。research_retry_test.go真TLS、額度、可執行其他任務、Restart／Pause／Cancel、接續／3次上限、長Retry-After與同步不執行；research_retry_browser_test.go→research-retry.cjs正式UI／真TLS及8新實圖。download.sh修正受限root環境不還原CI uid | CANDIDATE：本片新HEAD須獨立九項與三平台產物／實際發布。0.8.0已公開、3219ecf controller九項成功的精確證據在ACTIVE_TASK／PR #20。只適用公開GET任務及其接續鏈，非完整WISH-CRAWL-001；共同origin冷卻／Brave-AA逐頁retry／推論費用quota／真站及實機仍待辦。保留原23 Browser、303圖與全部原生／完整負載，未merge／未部署。 |
