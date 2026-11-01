# 公開資料配方、來源線索與本機確認

SITE-RECIPE-015L候選，承接已驗收015K來源`b8c2080b3e0063accdbc5974043825b61c661ce7`。目前不變更公開0.8.5-preview.1、發布請求或既有附件；本片自己的完整回歸、同來源九項CI與實際產物驗收前，不宣稱已接受。live狀態以`docs/ACTIVE_TASK.md`及Draft PR #20為準。

## 正式入口與行為

Models → 研究知識 → 辨識站點資料。選取目前已加入My Models的精確連線與公開Path；可另外指定最多2份HTTPS `raw.githubusercontent.com/owner/repo/ref/file`原始檔。只允許Go、JS、TS、JSON、Markdown或YAML文字檔，不執行程式碼或跟隨GitHub網頁。這些參照與站點公開GET共用原先每輪最多6次／30秒／每份1MiB上限，不傳送模型Key、PAT或Cookie，也不執行推論。

每份來源保存URL、SHA256、parser、判斷位置、family/version線索與信心。New API狀態形狀、明示軟體名稱、OpenAI相容清單與專案URL各自記錄；專案參照不證明執行中的站型，OpenAPI版本不當作部署版本。來源互相矛盾保存CONFLICTING，缺少部署版本保存Unknown。這些線索不能授權私人API、跳過既有New API版本認證或證明模型身份。

開啟已完成辨識紀錄 → 建立公開資料配方，選取同一份本機辨識的1至6個公開規則，每種資料最多一份。具體定義保存精確selection、origin、source hash、欄位／單位／scope／檢查條件／時效、未知與限制。生成只讀本機來源及已可重播fixture，不另發HTTP或呼叫LLM，不自動啟用。使用穩定請求ID；回應未收到可先讀已保存配方，相同意圖重試不會重複建立。

「逐項實測公開規則」明確逐項呼叫原有正式擷取測試，每項最多一次公開GET，整組最多6次。已通過目前scope／版本／時效的項目不重送；停止後續實測可取消飛行請求，已完成或已核准的實際請求與失敗歷史保留，沒有自動退款／重試。每筆回報保存實際HTTP入場次數；原私人或未計數舊規則為Unknown，不捏造0。完整貨幣成本一律Unknown，0次推論不代表免費。只通過fixture不能取得實測收據。

預覽整組啟用會彙整實際本機收據並產生唯讀差異與指紋，顯示每一筆舊／新規則、來源與沿用設定。啟用本身0次HTTP／0次推論；後續同步仍會讀取來源。確認前及同一交易中重查目前Key材料／版本、Provider設定、origin、membership、來源／定義、實測收據與舊active版本，任一變更必須重新預覽。一次交易保存配方、全部選取規則、不可變本機執行綁定與共用Undo紀錄；第二筆失敗不能留下第一筆或部分ledger。

生成配方沿用原先明確確認的聊天／目錄設定，不生成認證或推論端點變更。如果選取CATALOG規則，清單改由明確選取的公開擷取來源提供；不隱式新增My Models、取消成員、推升Free／資格／身份。原生Mac共用Go引擎可執行後端；本片正式操作與實圖驗證範圍是VPS瀏覽器，不能冒充專用Mac／Android配方UI完成。

## 持久回復與失敗處理

重載或重啟後，配方詳情保留「回復配方啟用前版本」。共用`/versions/undo`在一個交易恢復舊配方與選取規則的原版本；如任一規則已被獨立替換，拒絕stale Undo，保留現況及歷史。回應不確定時先讀保存結果，不自動再次啟用／回復。讀取503保留last-good畫面與資料。

配方歷史與本機執行權分開。生效定義須符合明確確認時的本機不可變綁定；同步／匯入對同一歷史列的更改不能借舊確認改變端點。Schema33一致升級前保存原快照，只為升級前已啟用的legacy版本建立綁定；歷史legacy版本仍需明確切換才能建立自己的綁定。生成marker不能經手動Recipe或legacy rollback繞過實測與Review。

草稿與新啟用核准限24小時；已啟用規則其後仍可取得新的觀測，各自沿用原parser、單位與facts時效。每次使用繼續驗證原精確連線與來源證據，不因切換其他手動設定或後來的部分配方而失去原group範圍限制。Key或來源變更、缺欄／單位變更保留last-good與失敗紀錄。模型清單的本機讀取故障回503，不誤走其他端點。

`recipe_execution_bindings`、`recipe_generated_versions`、`recipe_test_receipts`、`recipe_connector_action_changes`只屬本機，排除Workspace sync與portable backup。可同步／匯出的Recipe歷史仍保存，但其他裝置標示缺少本機證據，不能借來源裝置的實測、active或Undo權限；在目的端重新辨識與確認。

## 驗證與保留限制

真TLS反例已保留：原active recipe同列更改借用舊確認；清單讀取故障改走預設端點（1.327s FAIL，公開2／私人1）；手動設定移除原group scope（1.088s FAIL，原3次變5次）。修正後另驗實際caller、原子啟用／Undo故障、stale測試／Key／來源／定義、取消與實際入場、portable／sync隔離、schema32快照、草稿到期後正常讀取及503無部分寫入。早期HTTP未計數及取消fixture缺Content-Type的失敗紀錄亦保留，沒有降低斷言。

本機本片15個後端函式race12.896s；含原站點辨識17函式的聚焦37.072s成功。正式新增Browser及原Connector／Query三流程race14.652s成功，新增15張320／375／1280、深淺色與200%實圖已檢視。完整Go race466.545s、vet／原27發布回歸0.030s成功；完整37 Browser187.592s／458PNG（原443+新增15）成功；本片自己的同SHA九項／全部實際產物仍須完成，不能借前片綠燈。

本片完成的是具體公開資料規則的組合與正式確認流程；任意未知schema、私人站點入口／auth自動生成、可信的獨立身份／語意基準、全類型Reflection擷取品質／token／費用實測比較、分塊同步、專用原生流程、真站／目標主機／實體裝置及固定正式簽署仍按完整願望清單繼續處理，不稱完整下一版已完成。
