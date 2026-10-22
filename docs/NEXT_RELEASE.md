# ModelAtlas 下一版完成條件

更新：2026-10-22。依使用者最新指示與 D-015，下一版須接近完整功能；已公開基線為 0.8.5-preview.1（699d4d57ff8264506c2b2f6e5eed0021327bf602）。本清單是發布條件，不是完成聲明。開發期間維持 Draft PR #20，不提前修改發布請求。

原始範圍：PRODUCT_SPEC 的 P1–P10、ASTRA_MASTER_SPEC 的原文及 Part V 28 條驗收、PROVIDER_MODEL_INTELLIGENCE_CONTRACT、RESEARCH_POLICY、D-012～D-015。未經使用者變更，不刪任何原需求。

| 工作 | 下一版必須可操作的結果 | 目前已驗基礎與主要缺口 |
|---|---|---|
| 研究排程／WISH-CRAWL-001 | 多語新站發現與候選重驗、來源／語言／地區優先序、精確預算與用量、持久分頁與恢復、完整漏斗 | VPS 公開排程、來源冷卻、AA逐頁恢復已驗；015B Brave逐頁／地區與revision用量已同來源九項驗收；重驗策略、scope推論／token／費用限制尚缺 |
| 站點解析／WISH-CRAWL-002 | 來源證據→family/version/入口辨識→宣告式草稿→fixture及有界HTTP測試→共用Review→啟用／回退；Unknown、資格、一次性與recurring分開 | 手動connector、實測與啟用已驗；015C已驗公開來源自動mapping／fixture／真HTTP Review、改版保留與持久回退；私人入口／任意schema及專用原生呈現仍缺 |
| 研究改進／WISH-REFL-001 | 多輪品質／成本→具體版本差異→可重現比較→Review→下一輪使用版本→監測→退化回退 | 既有反思／版本／手動權重與Undo已驗；015A已驗固定策略版本、多輪本機比較、明確試用／退化監測與持久回退；生成query／擷取規則及token／費用的完整比較尚缺 |
| 真實性／WISH-AUTH-001 | 獨立版本化suite、重用實際流量、能力與身份分開、精確scope／成本／基準／衝突及風險證據 | 015D候選補既有流量suite、使用者確認的跨origin參考Review／Undo及Models／Assistant；主動逐級probe／預算、語意品質指紋、真站可信基準與專用原生呈現仍缺，不能以Reply OK證明身份 |
| 同步／D-012 | 持久游標、衝突／撤銷、完整歷史與安全憑證通道、大資料分塊、中斷恢復；三端正式操作可用 | VPS及原生同步基礎已驗；大型分塊與部分原生對應未完 |
| 日常功能／P1–P10 | Playground文字／多媒體／工具、Providers快加／進階／Review、多Account/Key、Models／Compare／證據、Assistant安全工具與Undo全部接正式UI | VPS多項流程已驗；Mac／Android逐項核對正式caller，特別是Android PLAY-004，不以transport當UI完成 |
| 穩定與操作／WISH-ACCEPT-001 | 重啟／離線／限流／換Key／跨日／長跑、全部尺寸／深淺色／200%、跨端歷史／更新恢復與原有資源負載 | 原測試及真systemd、31 Browser／371圖保留；目標VPS及實體iPhone／Android／Mac證據仍須取得 |

同時只接受一個整合片：015A研究評估已驗 → 015B Brave分頁／地區／用量已驗 → 015C站點草稿／Review已驗 → 015D既有流量真實性證據候選（先重用零額外HTTP）→ 研究剩餘排程與預算／完整站點辨識及逐級probe → 大資料同步與原生對應 → 全版本驗收。每片有正式caller、反例、失敗恢復及同SHA九項綠燈，才進下一片；執行時可依實際依賴調整次序並記錄理由。

## 發布檢查

- [ ] 上表各主要功能有正式入口、可完成的成功／失敗／恢復流程；P1–P10與Part V逐條對到程式、測試、實圖、版本／SHA及限制。
- [ ] 不再把固定反思摘要、手動JSON、後端能力或metadata刷新當作完整研究／原生功能。
- [ ] 原全部Go race／vet、發布回歸、Android JVM／device、兩架構Mac核心／native QA、Browser及expanded workload保留並通過；新增情境加入原流程，timeout與負載不降低。
- [ ] 最終同來源九項SUCCESS；三平台11個附件的內外層hash、版本、架構、簽署與公開下載／升級驗證完整。既有tag／附件不覆寫。
- [ ] 真站長跑、實際部署及實機證據分別可查；缺少存取的項目明列未驗，不計作完成。

28條原始補充條件目前不提前勾選。只有逐條符合其原驗收語意，才在TRACEABILITY及原清單同步升級；不得用完成百分比掩蓋缺少的主要流程。
