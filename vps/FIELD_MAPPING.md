# 公開 JSON 欄位對照

FIELD-MAPPING-015O 候選。015N 所列擷取比較範圍已由來源 `faf614f84265a91d85063e5f1e16d91b88266e51` 同來源九項、九份實際產物及18實圖驗收；完整持久紀錄見 `docs/verification/20261025/extraction-baseline.json` 與 Draft PR #20。本片自己的完整回歸與同來源九項／全部實際產物前仍未接受。完整下一版仍 PARTIAL，公開版本與11附件保持凍結。

## 正式操作

Models → 研究知識 → 辨識站點資料，選擇已加入的模型及公開資料 Path。原辨識對未知欄位無法自動產生規則時，打開已保存紀錄的「對照公開資料欄位」。從下拉選單選資料清單、目前模型的識別欄位、資料種類、樣本明示的模型範圍，再對照一至四項資料與各自單位。使用原卡片、表單與捲動詳情，無須手寫 JSON 或程式碼。

這個入口只對照公開模型資料：匿名 NONE、既有明確 origin、MODEL_PROVIDER，價格／額度／用量／餘額／效能／公告。來源必須明示範圍與各數值的固定單位字串；欄位名稱可不同、可巢狀，不能自行猜帳戶／Key／群組權限或把 credits 當 USD。原宣告式 parser 與四項 row/root checks 上限保留，不支援的組合明確拒絕，原專家規則入口也保留。

樣本保存最多256KiB、不截出半份資料當完整清單；最多1000列、128個欄位與有界深度。清單內對照物件欄位，不把巢狀陣列的第一個元素當成完整模型清單。遇到過大、過深、缺欄、缺模型、重複模型或未知單位時保留未支援原因及原辨識歷史。這不是宣稱所有 schema／私人網站均已自動相容。

對照入口只讀取已保存的本機樣本，產生草稿新增0次HTTP／0次推論。它明確標示「由使用者確認欄位語意」，不冒充獨立站方驗證。原始來源 URL／原始回應 SHA256、經過 secret 清理後的樣本 SHA256、取得時間、完整 selection 與原辨識紀錄另存；樣本與原回應的 hash 分開，不能以清理後的內容冒充原始 bytes。

## 實測、確認與正式使用

產生草稿以本機 fixture 重新擷取結果，通過才保存不可變版本。原來源辨識紀錄與已啟用版本完全保留；新草稿另建衍生辨識紀錄及親來源參照，不附加修改原 report 而使既有配方失效。共用現有規則詳情先進行一次有界真 HTTP 測試，測試通過後才出現明確啟用。Fixture 通過不等於已連上來源。

測試與啟用都檢查目前 membership、Key material version／設定／origin、樣本及原 report hash、完整 config／fixture、實測收據。實測後樣本被修改也不能借舊綠燈啟用。新對照只接受24小時內的樣本；既有規則保留歷史樣本，仍須取得目前的實際 HTTP 收據，不能因歷史證據較舊而把已核准 reader 的權限無聲搬走。

啟用後沿用正式 Intelligence／研究資料讀取流程：每次從實際來源取得資料，逐列精確比對目前模型與 scope，重驗每個映射值、單位與 freshness。來源聲稱值保留其 provenance；欄位對照是使用者的語意解讀，兩者不冒充獨立驗證。沒有啟用推論、路由或付款 fallback。

遇到缺欄、單位改變或讀取失敗，新增失敗歷史，保留全部 last-good、模型資格及原啟用版本，要求重驗或修訂；不把失敗當0／免費。固定費未知、未提供的快取／輸出價、recurring／台灣資格及身份依原契約各自保留 Unknown。可從規則詳情使用共用持久 Undo，重載／程序重啟後仍可回復；stale Undo 沿原 CAS 檢查拒絕。

## 回應遺失與安全界線

每次保存有固定 request ID 與完整請求 hash。同一 transaction 先保留該 ID，再驗證樣本並提交衍生 report／版本／fixture／結果；任一儲存失敗整組 rollback，不留半套權限。相同 ID、相同請求只讀回既有版本，不重新連線來源；同 ID 不同內容拒絕。並行的原請求與明確重試亦只建立一個版本。

確定409／422未寫入時，保留選取並顯示可見錯誤；傳輸或儲存結果未確認時鎖住修改，優先「讀取欄位草稿結果」。另可明確以原 ID、原內容重試，沒有自動重 POST 或另建 ID。晚到的完成／錯誤不重開已關閉或已切換的詳情；重新打開來源仍能只讀恢復。

原有公開來源限制、DNS／origin、6次／30秒、讀取大小、固定 GET grammar 與 secret 邊界不變。Key／PAT／Cookie 不送到公開 mapping 來源；已知 secret echo、敏感 credential 欄位及常見 camelCase 變體不進樣本或下拉預覽。站點文字只當資料，不執行指令、程式碼或修改 prompt。

Schema35→36 先保存一致快照，新增 `connector_mapping_samples` 與 `connector_mapping_requests`。樣本、請求與已核准執行收據都只屬本機，不經 Workspace sync／portable 移植授權；規則定義仍沿原匯出／同步機制，目的端須重新實測、確認。全量資料庫備份沿原明確恢復流程。

## 驗證紀錄與未完成範圍

正式 API 尚未接線的真反例0.904s已保存；接線後正式來源→fixture→真TLS→明確啟用→Intelligence→單位改版保留last-good→重啟Undo race2.169s通過。後續真反例0.984s確認實測後樣本改動仍被啟用，已在啟用交易補上 fixture／來源重驗。七項後端race8.426s、含原ConnectorDiscovery擴大32.121s、十項最終後端11.771s通過；原全部測試及工作量保留。完整最終回歸仍待驗。

新增真 Browser 涵蓋未知巢狀欄位、錯誤單位零HTTP且保留草稿、真正提交後丟回應、原 ID 只讀恢復、關閉視窗晚回應不重開、真實測／啟用、正式Intelligence、單位改版／重載／Undo、320／375／1280、深淺色／200%及48px。首次腳本錯點折疊清單34.01s、錯誤關閉按鈕accessible name32.60s為harness錯誤，沒有改產品、加重試或延長timeout；改用真正可見的原辨識入口與原關閉控制。修後完整新流程race6.940s通過，所有原流程不刪減。

Mac同源Go引擎包含後端，但專用Mac／Android欄位對照流程尚未交付；不能用API或VPS實圖冒充原生驗收。私人站點認證／任意語意、完整Reflection其他更新類型、可信真站基準、大資料分塊同步、原生PLAY-004與實際目標主機／裝置／正式簽署，仍按完整下一版清單接續。

本機完整Go race318.399s成功：395註冊函式、586唯一case／subcase（539pass／47明確opt-in skip），逐項保留前片全部575結果；兩程序共同原600秒期限與全部原負載不變。完整40 Browser race214.815s成功；最後欄位限制／復原提示與大字新增情境另十項後端race12.024s、新Browser7.715s成功。501張完整PNG保留原487＋14；14新圖已檢視，單位全文、未選欄位、復原操作與200%保存按鈕可讀可達。vet、原35發布回歸0.041s及4個coverage checker成功。最後來源仍須自己的同源九項與全部實際產物，不以中途本機結果代替。

首候選6221b989另未接受：原辨識的scope飛行變更案例追加斷言0.946s失敗，因SCOPE_CHANGED歷史仍顯示未保存的sample ID。現清除無效ID並在正式UI只顯示已保存的對照入口；含原辨識／mapping後端race35.756s、兩Browser7.279s通過，原所有斷言與請求不減。新來源另跑自己的九項／全部實際產物；首候選metadata保存於docs/verification/20261025/field-mapping-initial-candidate.json。

同片穩定性審查追加兩個真反例。候選25588f93的八項成功不足接受：Android原enablePathUnchanged於15000ms逾時。37c7c34d保留全部原984案例／5skip／timeout，新增真Room／vault／VM的可控投影順序，實際985案例唯一失敗為停用後管理列由false變null，原六個rollback案例皆PASS。現只在真正寫入成功後沿原pending交接精確credential列；啟用仍等active與management兩邊接手，停用只等management接手即退休。反例同時驗立即重新啟用、交接去重與退休、刪除不復活；失敗寫入的原blocked／Room／vault保留語意不變。精確失敗候選metadata／實際ZIP hash及XML見docs/verification/20261025/field-mapping-android-candidate.json與field-mapping-regression-counterexamples.json。這個可達窗口已證明，不能據此補造255那一次被泛化隱藏的原錯誤類別。

真Browser另以正式API移除會員，使原樣本確實409，33.60s確認已提交卻丟回應的草稿沒有恢復入口。現先提供原ID原內容的只讀結果恢復，再讀目前樣本；來源不可用隱藏空表單，仍可GET找回相同version，不重POST或新增ID。其他來源有待確認請求時明確導回原來源。會員明確重新加入後才接續原fixture／真HTTP／啟用／正式Intelligence／last-good／持久Undo，原精確5次來源GET、2次mapping POST（含原拒絕）、1版／1模型／0telemetry保留。隱藏表單後harness原role預設不含hidden，isDisabled等待33.28s；改includeHidden:true保留同一disabled斷言並加空表單不可見斷言，沒有重試或延長timeout。最終聚焦race5.37s，含原辨識兩Browser7.946s及原辨識／十項mapping後端35.733s成功；501圖保留，更新的恢復實圖已檢視。修後新來源仍須自己的完整九項／全部實際產物，不借任何失敗候選的八項綠燈，完整下一版仍PARTIAL。

最新3ef96c39未接受：新立即啟用反例通過，但原PendingConnectionHandoffTest的停用eviction反例抓到active pending不應被復用於管理交接。現新增只供管理的暫存，恢復原pending AND與停用移除，管理暫存不進active／send／選取；management真投影接手即退休。原984案例及原PendingConnectionHandoffTest不變；新增第985例保留所有UI／Room／vault檢查，只將交接map輔助檢查改為管理專用，並追加管理交接不冒充active投影。八項成功不足接受，自己的新來源九項／九份實際ZIP／18實圖仍待驗。工作環境中斷後以release/CANDIDATE_AUDIT.md所列只讀CI稽核接續，不減負載／timeout、不發布或部署，完整下一版仍PARTIAL。


## PRIVATE-MAPPING-015Q：明確授權的私人 JSON（候選）

預設公開辨識仍不帶憑證。Models → 研究知識 → 辨識站點資料，展開「私人 JSON（明確授權）」後選擇目前連線的 Key，或此帳戶已保存、已啟用且同一 origin 的 PAT；填寫明確唯讀 Path，確認後只讀一次 JSON。修改連線、Path、角色或 PAT 會清除確認，不自動試另一把 Key 或 PAT，不讀登入頁、動作端點、程式碼參照或其他網址。已知匿名連線無私人 Key 時明確拒絕。

從保存紀錄選「對照私人資料欄位」。Key 資料必須明確選模型識別與群組識別欄位，僅顯示所選 model／group 的唯一資料列；帳戶 PAT 僅接受整份帳戶物件，不將任意模型清單當成帳戶餘額。範圍字串必須由樣本明示為 MODEL_KEY_GROUP 或 ACCOUNT，數值單位亦須明示，仍限一至四欄、每組最多四項固定檢查；缺少／重複／不支援時保持未知，不推斷 credits 為 USD 或宣稱所有私人 API 均相容。原進階規則保留。

取得最多1MiB，完整清理後樣本最多256KiB、不截斷。保存原始回應 SHA256 與清理後樣本 SHA256，原始秘密內容不保存；既有 Key／PAT role、明確 PAT ID、material version、origin、完整 selection 與原 report 綁定。欄位對照與建立草稿新增0 HTTP／0推論；每個私人範圍有獨立版本名稱，避免另一把 Key 的草稿覆蓋已啟用版本。

仍需不可變 fixture、真 HTTP 實測與明確確認啟用。正式 Intelligence 在讀取前後重新核對來源樣本、規則與私人 authority；其他 Key／帳戶／group 不能借用已核准的私人 reader。真測試記錄實際 HTTP、0推論與未知貨幣成本，不能把未知費用寫成免費。來源聲明與使用者欄位解讀分開，不冒充公開或獨立驗證。失敗不抹 last-good／會員資格；沿用共用持久 Undo。

同一 request ID 保存唯讀來源結果；相同請求重讀不再連線。若已取得來源但最終資料交易失敗，保留 RUNNING／未知計量的原 reservation，不顯示未提交 sample ID、不自動重送。Assistant 的 discover_connectors 不接受自行填入的私人授權，必須由本機介面明確選取。

這片原始真 TLS 缺口與原型觀察見 docs/verification/20261026/private-mapping-counterexample.json。候選新增實際 Key／PAT 端點、不同 group／Key、拒絕授權、scope／unit／group 漂移、資料保存失敗及重啟恢復；新增 Browser 實際操作與14相關圖。其完整回歸、同來源九項、九份實際ZIP與34視圖尚待驗，不能借015P綠燈或把模擬TLS當成任意真站相容證據。專用 Mac／Android mapping 仍未交付，完整下一版仍 PARTIAL，公開版本保持凍結。

首候選87b3a5fb未接受：原604 Go結果不變，但兩個新測試把正式Intelligence的既有匿名狀態探測混入私人計量。fixture現明確拒絕該公開狀態路徑、不傳Key／Cookie，並分開驗證私人與公開次數，不刪正式探測、不改原私人讀取次數；新Browser預期8次私人GET＋3次匿名狀態探測＝11次實際HTTP。原41Browser中40通過，兩個UI harness失敗分別為未展開私人details及原source_path的廣泛selector歧義；只修正實際操作定位、保留原斷言／timeout。來源、原始錯誤與結果逐項見private-mapping-initial-candidate.json；新候選另驗自己的完整九項及實際產物。
