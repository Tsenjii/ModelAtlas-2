# 公開資料擷取效率、比較與試用

EXTRACTION-015N候選，承接已驗收的KILO-DEFAULTS-015M來源`5bc886f98c2646c7e4f43948167b23b71c7e0613`。本片自己的完整回歸、同來源九項與全部實際產物前仍未接受；公開版本、發布請求與既有11附件保持不變。完整下一版仍PARTIAL。

## 正式入口

Models → 研究知識 → 擷取效率與比較。選取已加入My Models的精確模型連線，以及二至六個已在本機實測、明確啟用的公開資料規則。只接受既有固定宣告式GET語法、匿名NONE、明確origin、欄位／單位與scope；不生成私人認證、指令、程式碼、模型路由或fallback。

同一來源至少有重複GET時，才會產生版本化提案；生成本身0次HTTP／0次模型推論。定義保存完整Provider／Account／Credential／Model／Group／Endpoint、目前Key版本與設定的指紋、每個精確active規則、原版與提案的最多讀取次數，以及預期的舊active版本。原規則不被更改、沒有自動啟用。每份建立意圖有固定ID，回應未收到可只讀取已保存結果。

## 三輪實際比較

「開始三輪實際比較」會在60秒共同上限內，三次各執行原本逐項讀取，再執行同一批次的合併讀取。每輪最多各六個GET，實際上限為三倍的原版與提案次數總和；整輪不呼叫模型。只有三輪全部完成、每項精確facts相同，且每輪實際入場的HTTP都按定義減少，才可預覽試用。相同request可能同時供應價格、額度等不同欄位，各規則仍各自驗證root、model／group、欄位、單位、缺值與時效，不混合成跨scope事實。

計數沿用正式HTTP入場位置，不用欄位數、成功回覆或計畫值冒充已發送。記錄每輪實際次數、延遲、facts hash與是否相同；完整貨幣成本Unknown，不因匿名、0模型推論或少發GET就宣稱免費。比較只涵蓋這組公開metadata，其他獨立同步讀取不計入提案的改善。

實測前先保存RUNNING意圖，每輪保存checkpoint。停止會取消飛行請求，保存已入場次數與已完成輪次；不退款、不重試。重啟將未完成意圖標INTERRUPTED，已完成輪次保持，飛行HTTP為Unknown而不是0；原ID只讀回結果，不自動重播。另一次明確比較是新意圖，舊輪次與請求歷史永久保留。

較晚的失敗、取消或不同facts比較不能借用較早綠色收據。試用期間新出現的真讀取失敗亦要求新的三輪比較，才可再次啟用；比較前後確認失敗歷史指紋，避免飛行中的舊測量取得新的啟用權。

## Review、正式使用與回復

預覽與啟用本身0次HTTP／0次模型推論。明確確認前及同一交易中重新檢查selection、membership、Key／設定、完整active規則、最新比較、預期舊版本與review指紋；任一變更要求重讀。active指標、不可變本機定義／比較hash綁定與共用action ledger在一個交易提交，失敗不留下半套權限。

只有啟用後，正式Intelligence／研究metadata入口才採用合併讀取。cache只屬於這次精確enrichment呼叫，結束即丟棄；不跨批次、Key、帳戶、模型、群組、operation或origin重用。相同失敗GET也只嘗試一次，不暗中重試。其他未選取的原規則保持原模式；沒有提案時保留所有既有逐項負載。

本機權限或scope無法確認時停止該組，不悄悄改成較多請求的另一種模式。失敗不刪My Models、成功觀測或last-good，不建立假的模型請求／My Uptime。試用的每次批次都有持久意圖、實際HTTP、結果與activation ID；取消／中斷及本機預算／冷卻／vault等待不算退化，重啟不重播。

使用者可經共用`/versions/undo`回復原模式，重載／重啟後仍可操作；stale Undo拒絕。明確同意的試用政策在同一activation連續三次真正發出讀取後的非本機等待失敗後，自動以CAS回復原版本並保留原action／失敗歷史，不改動規則、模型、價格或憑證。這只是保守停用試用，不宣稱失敗由最佳化造成，也不宣稱來源因此恢復正常。新試用有新的activation ID，不借上次的失敗或成功計數。

## 資料與驗證界線

Schema34→35先保存原一致快照。`extraction_versions`、`extraction_comparisons`、`extraction_active`、`extraction_bindings`、`extraction_observations`都是本機執行／測量資料，不經Workspace sync或portable備份搬移執行權。全量本機資料庫備份沿用原明確恢復流程。未知站點的任意新schema、其他欄位映射改進及私人入口自動生成仍在原願望清單；本片只比較與確認精確公開規則的讀取方式。

Mac同源Go引擎包含後端，專用Mac／Android擷取比較UI仍未交付；本片正式操作與實圖範圍是VPS瀏覽器，不冒充原生流程、真站、台灣、目標VPS或實體裝置驗收。

已保存反例：缺正式API的404，race1.251s；本機比較表不可讀卻被誤列409，race2.082s，現改503且0額外HTTP。後端涵蓋真TLS三輪／正式caller／持久Undo、較晚負面測量不能借舊綠燈、Key與review變更、同列定義／比較hash變更、一次失敗共用、三次監測退回／新activation隔離、取消、checkpoint與重啟Unknown、原子綁定故障、stale Undo、schema34快照與sync隔離。

擷取＋原Connector／SiteRecipe聚焦race59.068s成功；最後監測／不可變證據聚焦9.376s成功。新增正式Browser14張圖，包含已commit但回覆遺失後只讀恢復、實際Intelligence的2→1次公開metadata讀取、重啟／手動Undo恢復1→2、三次單次失敗與自動回復、真飛行停止與1次已入場請求、last-good503、320／375／1280、深淺色／200%與48px可達操作。原有全部功能、測試、473張原Browser圖、原生測試、負載與timeout均保留；完整本機與同來源九項及實際產物仍待完成。

後續真反例：已保存提案的晚回應會重開已關閉視窗，Browser3.668s失敗；正式caller現使用open／版本guard，自己的控制項忙碌隔離，關閉不重開、比較關閉即取消；修後新Browser9.820s成功。另本機研究預算滿時0HTTP的拒絕被計入三次來源失敗而退回試用，race1.985s失敗；現保留LOCAL_WAIT、已入場計數與原核准／用量，不當來源退化或自動回復。

本機完整Go race350.310s成功，當時383個註冊案例／574個case與subcase（528pass／46明確opt-in skip），逐項保留全部原557結果；最後新增額度反例後14個擷取函式race17.020s成功。完整39 Browser race197.183s／487 PNG保留全部原473＋14，後端與畫面新變更各有聚焦驗證；vet、35發布回歸0.030s及原4個coverage checker成功。最終來源自己的384個完整Go案例及全部九項CI／九份實際ZIP／全部報告／實圖仍待驗，不借015M綠燈。
