# 未知站點資料辨識與擷取草稿

2026-10-22，CONNECTOR-DISCOVERY-015C，內部候選。依D-015不另發小修版本，正式驗收以同來源九項與PR #20為準；完整WISH-CRAWL-002仍PARTIAL。

## 正式操作

Models → 我的模型 → 研究知識 → 辨識站點資料，選擇已加入的精確模型／帳戶／Key／群組／端點，再輸入同站公開頁面或metadata Path。這個步驟不建立Provider、不新增My Models；尚未註冊的站點先經既有Quick／Advanced／Assistant共用Onboarding Review。Assistant新增discover_connectors工具走相同生成、保存與Review路徑，不能自行啟用。

每次辨識最多6次公開GET、30秒、每份1MiB；Assistant最多使用剩餘4次web工具額度。只跟隨實際觀察到的同origin文件連結、字面endpoint線索與OpenAPI GET路徑，不執行網站指令、JS或任意程式。沿用DNS固定的公開client、禁止私網／proxy／redirect與共站冷卻；所選憑證只作本機scope綁定，不送入公開辨識或v2匿名connector。登入／PAT／帳戶私有資料保留待辦，不猜認證。

辨識紀錄顯示HTTP admission用量、來源hash、family／version線索、來源衝突、未判定欄位與生成的草稿。family／version仍是站方線索；HTTP200、模型名稱或樣本檢查不證明台灣資格、recurring-free、有效扣費或模型身份。

## 明確支援的形狀

固定宣告語法declarative-get-v2：JSON陣列或有界三層object中的陣列，最多1000列，精確model_id／model；OpenAI式object=model或明確models容器才接受id。額外支援單一model object。同一model／group有多個匹配列時拒絕自動映射，不選第一筆冒充確定結果。

可產生CATALOG、PRICING、QUOTA、PERFORMANCE、USAGE、BALANCE、NOTICE中有實際支持的草稿。除Catalog外，必須有明確MODEL_PROVIDER或MODEL_KEY_GROUP scope；群組還須精確匹配。ACCOUNT及沒有scope的公開資料不自動冒充所選Key或帳戶資料。

價格只接受明示USD/token、USD/1K tokens或USD/1M tokens；currency若另有聲明，必須一致。固定費沒有證據時保持未知，不設定fixed_charge_absent或effective。rpd／tpd／rpm／tpm等指標有各自field_units；reset_at只由明示reset_at_ms映射，success_rate還需RATIO標記，不能把秒當毫秒或99%當0.99。缺欄不猜為0或免費。

自動Mapping為刻意保守的確定性形狀辨識，不宣稱所有自訂schema、渲染頁、外站GitHub源碼或私人端點已相容；其他形狀顯示原因並保留手動宣告入口。後續可增補經fixture與真HTTP驗過的版本化形狀。

## 草稿、樣本與實測

生成前先驗schema／secret／origin。每份來源保留原始內容hash，但不保存任意原始JSON；樣本只投影已映射欄位與literal scope／unit檢查。已知vault秘密及常見raw-key型字串都去除，未映射secret／自由payload不進fixture、prompt或紀錄。

fixture保存精確數值、設定hash、來源hash、完整選取scope與本機context hash，重播相同extractConnector。雜湊先正規化JSON，避免struct／map順序造成偽差異；重讀fixture使用UseNumber，不透過浮點數損失quota或價格精度。fixture是樣本重播，不是獨立可信基準。

生成結果是DRAFT；Review呈現來源、時間、單位、範圍、已知值與缺項。先重播fixture，再以既有POST connectors/test作一次20秒／2MiB的真GET；不推論。v2重新檢查來源origin、root／row的literal單位及scope、欄位存在／數值形狀；改版／缺欄／錯單位／401會FAILED，歷史永久保留。更新Intelligence仍由原enrichConnectors正式caller保存獨立source observations，失敗不刪last-good。

實測成功還需本機connector_test_receipts，綁定設定hash、目前scope／憑證revision／origin與測試時間；同步匯入的VALIDATED字樣不是本機執行權。確認啟用時在同一transaction重查所有綁定、成員狀態、24h與更短freshness TTL、expected_active_id。舊v1已啟用規則保留；舊測試若沒有本機receipt，在下一次啟用前需明確重測。

## 保存、恢復與回退

schema25 additive新增connector_discoveries、connector_generated_versions、connector_test_receipts與connector_execution_bindings。實際生成的版本、來源proof、fixture與最終report同一transaction提交，儲存失敗不留半套草稿。固定請求ID先保存RUNNING，重送同ID只讀原結果，不重新連線；不同input不能借同ID。尚未完成的report用量為null，與完成後確知的0分開。程序中斷後不隱式重跑；保留未完成紀錄，使用者可讀取或明確開始新請求。

飛行中換Key、停用／移除模型、改group／origin／provider設定、取消或期限到達，不提交新草稿。HTTP admission數和fetch_attempts分開，共站等待不計HTTP。這是本機連線計數，不是站方billing或完整貨幣成本。

同站不同來源有不一致的同類資料時並列衝突，不無聲覆蓋。相同入口重新辨識保存新version，舊active pointer不變。既有applyVersion／action ledger／CAS Undo用於啟用；列表從本機ledger恢復activation_action_id，重新整理或重啟後仍可Undo。其他較新啟用之後，舊Undo被拒絕。

操作已保存但GET刷新失敗時，Review只提供重新讀取，避免再次啟用；來源辨識亦能以原request ID恢復。沿用原modal、48px操作與深淺色／大字，沒有新增poller。Mac內建同源Go引擎取得API與解析能力；Mac／Android專用原生辨識／Review介面仍待移植，不以網頁驗收替代。

本機fixture、request reservation、test receipt與啟用設定綁定不透過metadata sync或portable設定備份取得執行權；完整SQLite一致備份仍包含本機歷史。宣告定義仍可按原同步／匯入流程帶到另一端，但必須當地實測與確認。跨端辨識紀錄呈現仍待後續對應。

## 驗證與保留的失敗

- 修前正式Handler真TLS反例0.065s FAIL：自動辨識API為404。新增後16個後端測試函式含多個子情境，使用Handler／TLS／SQLite，涵蓋樣本→實測→正式Intelligence→重啟Undo、Unknown、精確單位、scope／憑證／origin、request重送、共站等待、衝突與版本、原子DB失敗、取消、Assistant web上限、schema24一致升級與同步隔離。
- 首次聚焦16.837s FAIL：新樣本空root map與讀回nil被當不同；另測試把cipher直接改成無法解密字串，實際應503而非測試期望的普通換Key。修正語意相等比較及測試使用真encrypt的輪替，沒有降低fixture或vault條件。
- 後續17.126s／16.171s聚焦FAIL：struct內嵌PriceLegs與讀回map的key順序造成hash偽差異，數值fixture又需UseNumber；改用正規化hash與精確重播。新source測試也曾把既有Intelligence正常認證讀取誤當匿名辨識，改成只在新公開入口斷言不傳秘密，原Intelligence流程保留。
- 18.724s聚焦成功；追加本機receipt／過期／成員檢查時20.740s FAIL，測試UPDATE了尚不存在的membership_state列，改成真正保存ARCHIVED再核對原409。後續21.725s聚焦成功。編譯期曾誤用雜湊helper參數型別及ToolFunctionCall名稱，已更正，均不當作產品驗收。
- 新Browser5.69s及原Knowledge Browser4.29s（合計11.000s）成功：正式辨識表單、POST已保存後503只讀恢復、fixture／真HTTP、確認一次啟用、Intelligence、來源單位改變、reload後Undo；320／375／1280深淺色、375的200%及改版後8圖已檢查。原30 Browser／363PNG及5m測試／15min job上限保留，新增後預期31／371；全部原生測試與expanded workload不減。
- 完整Go／Browser與同來源九項仍各自執行，不借015B或本機聚焦綠燈。真公開未知站點、目標VPS及實體裝置長跑尚未取得，不冒稱已驗。

- 完整Go race第一次372.366s成功；最後審查再以真TLS重現已啟用version row的mapping被改後，下一次Intelligence仍執行變更內容（1.136s FAIL）。同片增加本機connector_execution_bindings，在啟用transaction保存確認過的設定，metadata修改不能借舊active pointer；每次正式enrichment／Catalog執行前重查。升級先保存既有本機已啟用設定，保留舊v1能力；Undo沿用原設定綁定。修後16個後端函式與原DeclarativeConnector聚焦26.599s成功；最終來源完整Go／Browser及九項重新驗證，不借修前372.366s結果。

- 6e25c55候選本機完整Go race303.798s／vet／27發布回歸0.027s成功，但不能接受：CI Browser119899276577的舊策略監測流程34.27s失敗，畫面顯示啟用後0輪，之後全套在300.022s逾時；失敗附件12434849226（354PNG，SHA256 53c13420e1aeab163d7d78fbe83eb727c6c341e29dff4ee312a88ba06ac007f8）已獨立下載核對並檢視失敗原圖。本機同候選監測5.48s通過，但完整全套仍於30流程後300.022s逾時，原上限保留。
- 已以延後GET的既有Browser流程確定重現5.914s FAIL：按更新後舊版本卡片仍可開啟，Modal捕捉舊0輪，完成讀取不更新該Modal。修為只在最新讀取期間停用四個研究清單的舊卡片，標示aria-busy；成功後新卡片可用，失敗則原last-good卡片恢復。既有14HTTP／3輪退步／Undo斷言與timeout完全保留，追加明確pending gate反例。修後原Knowledge3.46s、新辨識4.92s、原策略監測5.37s（合計14.776s）成功；新來源重新完整Browser及全部九項，不借6e25結果。
