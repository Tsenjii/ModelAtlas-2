# 模型真實性證據（AUTHENTICITY-015D）

本片是既有流量的版本化觀測，不等同完整 WISH-AUTH-001。公開版本保持 0.8.5-preview.1；PR #20 維持 Draft。自己的同來源九項與產物驗證完成前仍為候選。

## 正式操作

Models → 任一已加入模型 → 歷史 → 展開「模型真實性證據」。此區才讀取分析／參考歷史，沒有新增輪詢器或自動推論。

1. 「分析既有請求紀錄」保存 `traffic-fingerprint-v1` 的不可變快照。
2. 分開檢視可用性、能力與模型身份。觀測結果為資料不足、觀測一致或觀測有矛盾；身份一律仍為 `UNPROVEN`／低信心，沒有替換機率或100%身份保證。
3. 選擇另一 Provider、另一 origin、同一 Canonical Model 的實際連線，寫下來源確認理由，建立參考版本 Review。草稿尚未啟用；來源由使用者確認，不冒充自動驗證的官方身份。
4. 檢視完整來源、Key版本、條件、資料、衝突／未知及成本，明確確認啟用。CAS拒絕過期選取，Undo跨重整與引擎重啟保留；每版／每份報告永久保存並可分頁查閱。

已保存但後續GET失敗時，停用原操作，只讀恢復。分析／草稿使用固定request ID並核對完整內容，同識別重試不建立第二份；啟用或Undo的結果不明時先重讀版本，不隱式重發。

Assistant 的 `analyze_authenticity` 與 `preview_authenticity_reference` 走相同分析／Review API，不新增外部請求、不啟用自己建立的參考。研究的既有 MINIMAL_INFERENCE 完成／重用流量後也接相同零HTTP分析，記錄報告／suite／身份狀態；不因HTTP200提高身份或免費資格。

## 證據與比較規則

- 只取最近七日、Provider × Account × Credential × Model × Group × Endpoint × Operation × credential revision精確一致的最多32筆紀錄。超出本次檢視標示truncated，完整請求歷史不刪除。
- 共用推論管線保存本機來源憑據：實際使用的憑證密文材料指紋、設定／端點hash、最終telemetry hash及條件／行為計數。指紋不含明文秘密；不把prompt、回答、reasoning內容或tool arguments複製進此套件資料。
- 條件hash包含operation及完整編碼payload，只移除頂層model欄位，以便同Canonical來源比較；指示、歷史、媒體、工具、輸出上限及stream模式不同就不能相配。沿用分段JSON編碼，不複製整份長context成第二個wire body。CUSTOM任意mapping不產生可比較的條件hash。
- 本機憑據必須與目前材料、設定、端點、receipt來源／revision及原始紀錄相符。舊資料、外端同步紀錄、改寫資料及換Key後的舊流量不能借本機sample ID取得權威。取消／中斷／RUNNING另列排除，不進可用性分母。
- 回應名稱只與明確request model／Canonical／使用者別名比較。不同名稱保存弱衝突及sample ID，不能據此證明替換。站方／手動metadata聲明與實際觀測分開顯示。
- 工具只計入已提供名稱與現有格式驗證通過的實際呼叫。沒有呼叫不代表不支援；隱藏reasoning同樣不能當作不具推理能力。輸入位元組／站方回報token只代表觀察量，沒有評分理解能力或宣稱最大Context。
- 參考至少需3筆成功、2種條件及工具／可見reasoning／至少4096bytes輸入的一種觀測；3次相同Reply OK不足。參考有名稱聲明衝突則不能啟用。這是最低觀測覆蓋，不是統計顯著或身份可信的數值保證。
- 一致需要目標至少3筆相同條件觀測、2種條件及雙方一種上述觀測相配。相同條件下參考工具格式通過、目標INVALID_TOOL_CALL保存矛盾；其他網路／401等失敗仍只屬可用性。一般回答、隨機工具選擇與reasoning缺席不自動變成身份反證。
- 參考凍結suite、雙方完整context／metadata hash、原始樣本與dataset hash。草稿最多24h內審閱；啟用時同transaction重查目標及參考，之後每次分析重查參考七日有效性與原樣本。換Key／origin／group／endpoint／設定／Canonical／alias及來源紀錄變更時保留版本、標示STALE而停止比對。

## 成本與保存

每份報告明列新增HTTP=0、新增推論=0；處理貨幣成本未知。原請求成本分成站方USD回報、有效費率估計、標價估計，各自用精確有理數加總。部分未知時總額為null，另保留已知subtotal與已知／未知樣本數；沒有樣本也不回報0。UNKNOWN單位的usage.cost不能當USD；真0與未知分開。

schema26採additive migration及既有一致升級快照。四個authenticity表只存在本機，不進metadata sync／portable backup，也不移植啟用權；完整SQLite維運備份照常保存。同步原始流量在目的端沒有本機憑據，只能保留歷史／資料不足。原推論答案、membership、價格／配額及請求歷史不因輔助capture失敗而消失。

API：POST `/authenticity/analyze`，GET `/authenticity/reports`；POST `/authenticity/references/preview`、`/activate`，GET `/authenticity/references`；Undo使用既有 `/versions/undo`。GET均要求完整selection，支援limit／offset與指定ID；參考清單另帶目前active ID及持久action ID，即使目前版本在舊頁也可直接查閱。完整資料永久保留。

## 驗證與失敗歷史

- 初始真TLS／正式Handler 0.069s FAIL：推論成功後分析API不存在（404）。
- 第一輪擴充前的聚焦19.939s成功；擴充同步測試24.975s FAIL因新測試用不分享秘密的peer（產品會停用其Key），不能當可執行本機scope。改以真TLS分享且在目的端vault重新加密，再確認來源憑據仍不會同步；原安全規則沒有放寬。
- 初Browser0.449s因本機缺固定Chromium；安裝指定版本及CJK字型後，1.702s FAIL確定重現：共用action的finally把讀取失敗後的原操作重新開啟。此區改由自身的讀寫鎖決定按鈕狀態，不更動其他介面的共用行為；失敗實圖SHA256 `35d8d0e975337a829733c6c28786281770f5e4799170096f9b452e8c7bc874f2`保留。
- 修後新Browser4.110s成功；原Knowledge、新站點草稿、原策略監測及新authenticity合計13.831s成功。9張新增實圖涵蓋320／375／1280深淺色、200%文字與實際確認操作。原31流程／371圖保留，追加後32／380。
- 17個後端函式race33.416s成功：相同條件正例、名稱／工具反例、Reply OK／不同max_tokens不足、範圍與revision、同步／篡改／取消／中斷、跨重啟Undo、CAS／過期／alias／origin、飛行中換Key、精確真0與未知費用、秘密／prompt不複製、輔助DB失敗及v25一致升級。開發時兩次新測試欄位名錯誤與一次命令工作目錄錯誤已修正，不能當產品失敗或驗收。
- 27個發布控制回歸0.036s成功；來源審查前完整Go race398.230s成功，最後來源的origin／計時調整由本來源完整CI重新核對，不能借修前結果；完整Go、Browser、兩原生架構與同來源九項仍待本來源獨立驗證，不能借前片的結果。

- 來源審查追加0.113s真TLS反例：研究的舊最小推論證據錯借Provider主要URL，與所選Key綁定origin不同。改用同次endpointAuth的實際origin；分析與研究證據保持一致，沒有新增HTTP。新增第18個後端函式；含原生協定及舊研究重用的聚焦race45.531s／vet成功，最後來源完整驗證另核對。

## 最後入口審查（同片新候選）

b9e8dd3f3e697e65cb6e3467b57eae1d156937f8／tree7ce53f738394ac3d30fa1c529f5e8a8e48d660b7已同來源九項SUCCESS，全部九份QA／套件附件已驗hash、來源及原測試／實圖；精確證據見docs/verification/20261023/authenticity-candidate-b9.json。CI Go race356.726s、systemd41.805s、原expanded workload21.765s；32 Browser288.378s／380圖；本機最終Go343.606s及完整Browser288.402s成功。

但不作最終驗收：新增非文字模型正式Models入口的7.174s真Browser反例，圖片模型仍出現只支援文字對話的分析操作。改為明確呈現目前限制、保留原請求歷史；不提供不支援的操作，也不發兩個分析清單GET。原正例、矛盾／不足、失敗讀取恢復、啟用、Undo及全部畫面斷言不減，追加圖片入口與零讀取／零外部HTTP檢查。修後新Browser4.000s成功。失敗圖SHA256 8c40a88f55855d20361fb743cd5e1e4a309354f9e44e1ce762bcf97896e1539f保留；最後新來源另跑九項，不能借b9的綠燈。

## 尚未完成

沒有主動deeper probe、回答品質／語意benchmark、統計身份指紋或真站可信基準長跑；完整token／費用預算需後續切片。Mac內建同源Go引擎取得API，Mac／Android專用原生呈現尚未移植。沒有目標VPS或實體裝置驗收。全WISH-AUTH-001、Part V與下一版接近完整功能的門檻仍為PARTIAL，不能因本片局部成功提前勾完。
