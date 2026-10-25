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
| 研究排程／WISH-CRAWL-001 | 多語新站發現與候選重驗、來源／語言／地區優先序、精確預算與用量、持久分頁與恢復、完整漏斗 | VPS 公開排程、來源冷卻、AA逐頁恢復已驗；015B Brave逐頁／地區與revision用量已同來源九項驗收；015E原子入場已驗；015F每日／逐憑證核准已驗；015G token／USD預留及回報結算已驗；015H共用研究pool已驗；015I私人排程opt-in／來源確認與排程每日預算已驗；015J逐級probe／重驗策略已驗 |
| 站點解析／WISH-CRAWL-002 | 來源證據→family/version/入口辨識→宣告式草稿→fixture及有界HTTP測試→共用Review→啟用／回退；Unknown、資格、一次性與recurring分開 | 手動connector、實測與啟用已驗；015C已驗公開來源自動mapping／fixture／真HTTP Review、改版保留與持久回退；015L來源別family/version、可選被動程式參照與整組公開配方實作候選；私人入口／任意schema及專用原生呈現仍缺 |
| 研究改進／WISH-REFL-001 | 多輪品質／成本→具體版本差異→可重現比較→Review→下一輪使用版本→監測→退化回退 | 既有反思／版本／手動權重與Undo已驗；015A已驗固定策略版本、多輪本機比較、明確試用／退化監測與持久回退；015K已驗本機公開證據生成query、真成對實測及下一輪試用／監測／Undo；擷取規則與其他更新類型及token／費用的完整比較尚缺 |
| 真實性／WISH-AUTH-001 | 獨立版本化suite、重用實際流量、能力與身份分開、精確scope／成本／基準／衝突及風險證據 | 015D已驗既有流量v2 suite、使用者確認的跨origin參考Review／Undo及Models／Assistant；015J已驗有界主動逐級probe／預算與能力觀測；完整語意品質指紋、真站可信基準與專用原生呈現仍缺，不能以Reply OK證明身份 |
| 同步／D-012 | 持久游標、衝突／撤銷、完整歷史與安全憑證通道、大資料分塊、中斷恢復；三端正式操作可用 | VPS及原生同步基礎已驗；大型分塊與部分原生對應未完 |
| 日常功能／P1–P10 | Playground文字／多媒體／工具、Providers快加／進階／Review、多Account/Key、Models／Compare／證據、Assistant安全工具與Undo全部接正式UI | VPS多項流程已驗；Mac／Android逐項核對正式caller，特別是Android PLAY-004，不以transport當UI完成 |
| 穩定與操作／WISH-ACCEPT-001 | 重啟／離線／限流／換Key／跨日／長跑、全部尺寸／深淺色／200%、跨端歷史／更新恢復與原有資源負載 | 原測試及真systemd、原36 Browser／443圖全部保留，015L候選新增第37流程／15圖；目標VPS及實體iPhone／Android／Mac證據仍須取得 |

同時只接受一個整合片：015A研究評估已驗 → 015B Brave分頁／地區／用量已驗 → 015C站點草稿／Review已驗 → 015D既有流量真實性證據已驗（先重用零額外HTTP）→ 015E研究推論原子入場與恢復已驗 → 015F每日／逐憑證推論核准已驗 → 015G token／USD預留與結算已驗 → 015H共用研究額度池已驗 → 015I私人研究排程已驗 → 015J逐級probe／候選重驗已驗 → 015K query生成／真成對研究已驗 → 015L公開配方／逐來源線索候選 → 完整站點辨識／Reflection → 大資料同步與原生對應 → 全版本驗收。每片有正式caller、反例、失敗恢復及同SHA九項綠燈，才進下一片；執行時可依實際依賴調整次序並記錄理由。

## 發布檢查

- [ ] 上表各主要功能有正式入口、可完成的成功／失敗／恢復流程；P1–P10與Part V逐條對到程式、測試、實圖、版本／SHA及限制。
- [ ] 不再把固定反思摘要、手動JSON、後端能力或metadata刷新當作完整研究／原生功能。
- [ ] 原全部Go race／vet、發布回歸、Android JVM／device、兩架構Mac核心／native QA、Browser及expanded workload保留並通過；新增情境加入原流程，timeout與負載不降低。
- [ ] 最終同來源九項SUCCESS；三平台11個附件的內外層hash、版本、架構、簽署與公開下載／升級驗證完整。既有tag／附件不覆寫。
- [ ] 真站長跑、實際部署及實機證據分別可查；缺少存取的項目明列未驗，不計作完成。

28條原始補充條件目前不提前勾選。只有逐條符合其原驗收語意，才在TRACEABILITY及原清單同步升級；不得用完成百分比掩蓋缺少的主要流程。
