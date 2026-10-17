# Mac 模型 Intelligence：MAC-INTELLIGENCE-008

候選 0.3.0（build 8）。基線為已交付 0.2.1 `f3700796`，本片新 HEAD 的九項 CI／100 張實圖通過前不驗收。完整 Master Spec 保持 PARTIAL。

## 正式操作

Models 任一已加入連線 → 模型詳情：概覽／價格／額度／效能／歷史／設定。

- 價格：引擎的 PriceState、最後成功標價／已確認實際扣款、input／output／cache／fixed，明示原始單位、來源與時間。只有顯式 fixed_absent 且 fixed 缺席才顯示無固定費用；其餘缺值為尚無資料。價格趨勢按 kind／source／完整 scope／origin／credential version／nominal-or-effective／unit 各自分開，圖上 Double 僅用於繪製，精確 decimal 字串仍可查看。
- 額度：人工 MODEL／CREDENTIAL／GROUP 等池與站方帳戶／Key 快照各自呈現，不加總、不平均、不因沒有總量而畫百分比。不將原始站方額度換算為 USD 或 Token。跨帳戶／Key／群組／端點不套用；較廣的帳戶資料顯示它自己的 scope。
- 效能：My Key／Provider／External 分開。My Key 有 TTFT／TPS／latency／success-outcome 的時間趨勢，平均與 p50／p90／p95、錯誤分類、樣本數／窗邊界與取消／中斷件數；rolling 60s RPM／TPM 與時間窗統計分區。未知不補零；非串流 TTFT 保留不適用。Chart 顯示最多最近400點，summary 使用全量 retained samples，歷史仍可查與匯出。
- 觀測歷史：依 source／kind 篩選，逐筆 status／time／scope／reason／evidence；失敗與 last-good 皆保留。此列表是引擎目前最近200筆觀測，不宣稱已分頁查詢全部 observation archive；原始永久保存不變。
- 載入有進度、失敗可重試、revision 防舊回應覆寫；detail.selection 必須精確相同才展示。查看上述資料只讀取本機引擎，不發上游 inference，不消耗測試配額。

## 工程與驗證

`ModelIntelligence` 是 read-only projection，不新增 FREE／可用性推斷、不改 DB。`performanceSQL` 的既有 bounded history 增加原 sample TTFT／latency／numeric TPS，保持相同 scope SQL、取消排除、400 cap與全量 aggregates。

6項 `ModelIntelligenceTests` 覆蓋錯帳戶 response、missing／exact zero／精確 decimal、來源／單位／扣款／version 分軌、資源池範圍、取消／未知 chart gaps、last-good與站方分離。Go 原1000樣本測試新增圖點原值／null guard；新測試驗 scoped half-open window、cancel、同Key另一account、非串流TTFT、known zero與non-numeric TPS。

NativeParity 保留原25 smoke＋20 parity圖與所有條件，增加5張正式 ModelDetailView 截圖：價格、額度、效能、觀測歷史、另一帳戶價格未知。使用真 API 寫兩種 quota pools、兩次價格、原 TLS 真 inference telemetry，驗 custom time、scope、零額外 inference／草稿與歷史不變。每架構38核心／50實圖，合計100圖；原120秒上限不變。

## 仍需後續完成

Provider 原生全量 inventory／多Key／簽到／stale 操作、Compare可讀並列、完整 Research／Assistant／Reflection 情境仍待後續。站方既有 groups 能讀成分組摘要，但其專用 series_schema 的全量互動圖與外部多單位轉換仍未擴稱完成。價格／觀測200筆以上的獨立分頁與全部新互動的XCUI點擊覆蓋亦不宣稱已完成。

KiosAPI 的公開研究與實測缺口見 `../docs/KIOSAPI_RESEARCH.md`；這裡的合成畫面不是 KiosAPI 帳戶或外部站點驗證。


## 同片串流回歸修復

5cd93e7 的 arm64原35核心／50圖通過，VPS四項與Android build/device通過；Intel原35核心通過後在既有 rich-stream 25秒完成界線失敗。固定數字診斷確認上游已送完1249字／finished=true，App只顯示1083字、仍generating且無problem，閱讀offset保持0。不是放寬測試或盲重跑。

AtlasClient 原本每個 SSE event 都 await MainActor callback，畫面排版較慢時網路解析跟著逐token等待。改為最多128 wire events／2MiB的 bounded delivery queue，只合併相鄰且只有text欄位的delta／reasoning；control／metadata／Unicode／順序保留。背壓時等待已排程的UI delivery，不丟內容，結束／取消／錯誤均等待delivery完成，避免舊callback污染下一次請求。新增3核心測試涵4096碎片、128事件／byte背壓、control／metadata順序、取消與返回前全部送達。原25秒、全文SHA256、閱讀位置、120秒與100實圖要求全部保留，新HEAD重新九項驗收。
