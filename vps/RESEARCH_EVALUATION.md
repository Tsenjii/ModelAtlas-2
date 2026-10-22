# 研究策略評估與回復

2026-10-22，RESEARCH-EVALUATION-015A。下一版內部開發候選，未單獨發布新應用程式版本；完整願望清單仍見 docs/NEXT_RELEASE.md。

## 正式操作

Models → 我的模型 → 研究知識，開啟來源優先序或搜尋策略提案，選「評估這個提案」。畫面顯示目前策略與提案的差異、比較範圍、請求量、歷史排序結果與限制。符合門檻時才能明確確認試用自動產生的版本；沒有自動啟用。

研究建立時即固定實際使用的版本、完整來源設定或精確語言／查詢，以及權重／優先序。舊佇列、重啟恢復與排程預算接續不借用稍後才啟用的版本；新輪才讀取新的已啟用策略。來源設定改變時，舊版本的權重不套用到新範圍，任務記錄與啟用後監測顯示原因。

啟用後記錄真正使用該版本的研究結果。至少3輪同範圍完整研究後，若每HTTP觀測效益低於試用前50%，畫面標示下降，讓使用者Review並回復啟用前版本。這是研究產出警示，不證明網站變差或策略有因果影響。回復使用原 action ledger／Undo，跨重新整理與引擎重啟仍可操作；較新版本已取代時拒絕過期回復。

Mac 知識頁亦有原生評估、差異／限制、監測與持久回復入口，保留 SwiftUI／Keychain，不嵌 WebView。Android 尚無此專用原生介面，不能將同源Go引擎或新網頁驗收外推為Android已完成。

## 比較的實際語意

- HTTP 200本身的品質分數為零。分開保存新候選、重複命中、未解決線索、有來源的recurring結構化主張、一次性／非免費／使用限制等反例及取得失敗。負面結果不刪掉。
- 正向recurring計數只取本機實際取得且標為官方來源的結構化主張，同時具備free=true、已知reset、RPD≥100與TPD≥1,000,000。這個計數不是ELIGIBLE／VERIFIED，也不替代台灣資格、強度、AA動態特例或模型身份驗證。衝突的負面主張優先保留，不算正向產出。
- 研究效益估計：尚未解決的新線索×0.5＋recurring主張×2；已確認的負面／付費／試用結果保留為反例，不增加recurring發現效益；取得失敗與截斷樣本的效益為零。它是可重現的研究排序政策research-yield-v1，不是可信度機率。
- 自動提案參考最近30日、最多32輪同範圍樣本，以2×(1＋效益)/(2＋已核准HTTP)平滑，限制weight在0.25～4；仍為TENTATIVE。語言提案保留實際查詢，不憑空聲稱新查詢已驗。
- research-priority-replay-v1比較已完成擷取作為原子工作，在其已核准HTTP總數一半的預算下，以現行與提案優先序重播排序；放不下的工作不部分推測。至少3輪含對照來源／查詢、且提案的觀測效益不下降、HTTP不增加，才是READY_FOR_TRIAL。此為明示的排序模型，不預測未見HTTP回應；仍需要下一輪有界實際研究監測。
- 比較只取本機plan、用量與證據receipt；取消、未完成、零HTTP、過期／未來時間、截斷及同步歷史不作獨立樣本。每輪／每metric只取最新觀測，保留較早部分紀錄，續跑不灌成多輪。
- HTTP欄位是送出前核准保留量，不冒稱每筆已抵達遠端。來源／搜尋計費、token與貨幣成本尚未完整量測，維持Unknown。這仍是完整WISH-REFL-001所需後續工作之一。

## 持久化與安全

schema22以additive migration新增本機plan bindings、task usage、實際證據receipt、品質觀測、評估及啟用比較關聯；升級前保留一致快照。資料永久保存，查詢使用有界近期視窗，不做歷史清除。原AA schema21恢復與所有歷史保留。

HTTP總量、排程每日量、AA window等原子admission連同task usage一起提交或回滾。收到回應時保存不可變的品質證據receipt；後續候選review、metadata或同步不能回寫這份觀測。以上本機控制／評估表不在workspaceTables及portable config backup中；同步只帶公開提案／反思歷史，不授予目的端執行或本機評估權。完整SQLite備份保留全部資料。

評估保存policy、提案hash、資料集hash、完整啟用策略plan hash、scope、比較基線、Run IDs與限制。啟用在同一transaction重新核對所有條件；新研究、基線或來源變更後拒絕舊評估。既有手動優先序可明確試用，沒有比較基線時明示NO_BASELINE，不將手動確認當作已驗改善。原本的alias／recipe／connector安全門檻不放寬，沒有改system prompt、source、secret或destructive policy。

## 驗證與已知界線

knowledge_evaluation_test.go使用真Handler／SQLite／TLS，覆蓋空200反例、多輪比較、精確版本固定、評估／基線／來源變更、退步拒絕、3輪實際下降、重啟回復、原子配額回滾、同步隔離、v21一致升級、時效與排程接續。knowledge_evaluation_browser_test.go → e2e/knowledge-evaluation.cjs跑相同production caller，14次有界TLS、503讀取失敗後不重寫、重整後回復入口、8張窄屏／深淺色／200%實圖。TLS fixture明示為合成資料，未冒充公開真站長跑。

保留原28 Browser／347PNG與原五分鐘上限，新增第29流程／8圖；所有原生測試及expanded workload不減。首空200回歸0.070s真失敗後才修正。首新Browser2.628s抓到共用action finally錯誤解鎖「已保存但讀取失敗」操作，已用局部guard修復，沒有改低斷言。另一次33.834s因測試誤用「關閉」而非真aria-label「關閉詳情」失敗，已改實際入口。後續新測試的async waitForFunction將Promise視為truthy而提前返回，已核對固定1.56.1本機實作，改有界狀態輪詢；原成功斷言與timeout保留，修後3次4.05／3.92／3.66s成功。這些失敗不當成產品或完整驗收通過。

本機首完整Go race285.057s／vet、27發布回歸成功；新增與排程／AA原有聚焦race36.156s成功，追加時效／接續後16.650s成功。首完整29 Browser在原五分鐘上限271.965s成功；之後新增付費／試用／衝突去重反例，正向效益不再獎勵負面結果。最終同來源九項及Mac原生結果仍待本候選CI，精確SHA與job記於PR #20。

尚缺跨地區／新query／extraction等提案的有界真站比較、token／貨幣預算與完整原生研究對應，WISH-REFL-001不提前升為完整ACCEPTED。
