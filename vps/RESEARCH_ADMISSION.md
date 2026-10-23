# 研究推論原子入場（RESEARCH-ADMISSION-015E）

本片修正既有研究推論的本機拒絕、預算保留及失敗恢復。完整每日／逐憑證token與USD限制、私人排程opt-in、逐級probe仍待後續完成；公開版本保持0.8.5-preview.1，PR #20維持Draft。

## 正式流程與保存

全球研究 → 開始研究／既有MINIMAL_INFERENCE worker → 共用execute。秘密內容、協定、附件及能力檢查先在本機完成；沒有通過便不核准HTTP或推論用量。

通過本機檢查後，在同一SQLite transaction中檢查原HTTP／probe預算及原排程控制，保留run／task用量，保存明確更新的工具設定、新對話訊息、RUNNING請求、receipt與訊息關聯。任何一項寫入或commit失敗全部回滾；不改舊對話、工具或既有用量，不留下不存在的sample ID，也釋放該次尚未保存的附件記憶體。已保存的其他附件及完整歷史保留。

成功入場後才呼叫原供應商，不新增自動重試。之後的503、取消、中斷或程序停止保留入場用量及原請求歷史；此數字是本機已核准嘗試，不能當作供應商實際收費證明，不能因沒有收到完整回答而退款。取消仍不進My Uptime分母。

程序重啟維持原本研究暫停／明確恢復規則；不因恢復預算而擅自重啟私人推論。失敗的本機保存可在原有剩餘預算內明確恢復。完整token／USD預算尚未完成，Unknown成本仍不當0或Free。

研究真實性報告ID改綁定run、task、attempt、精確selection、實際sample、outcome及suite。成功重試保存自己的新報告，舊失敗報告保持不可變；報告分析仍新增0 HTTP／0推論，不因成功而證明身份。

## 驗證與失敗保留

- 真TLS／正式研究plan與execute反例0.062s、工作區恢復重現0.057s均FAIL：內容被本機拒絕、供應商HTTP=0，但run HTTP與probe都已扣1。
- 原協定／工具接續、請求收據與初始反例修後聚焦9.778s成功；前一次工作區未持久化前同範圍10.217s亦成功，恢復後以新來源重新驗。
- 擴充測試11.349s的失敗包含新fixture誤用重啟後未明確恢復、沒有遵守原4-flight上限，以及把run含catalog用量誤作推論task用量。保持產品原保護與全部20個工作，改由正式恢復入口／原並行上限內的客戶端佇列及分開meter斷言驗證，不放寬保護、不減工作量。
- 修正fixture後11.495s另確認真worker反例：本機保存失敗後明確恢復、推論成功，仍引用第一輪的空報告（usable=0、相同ID）。修為每次attempt／sample獨立不可變報告；前報告不覆寫。5個新後端函式加原Auth研究caller聚焦13.932s成功，之後再強化飛行中並行預算barrier，最終來源完整驗證另記。
- 六個真DB故障點：session_options、messages、telemetry、request_context、message_requests、research_task_usage；保留先前成功歷史與工具、附件不洩漏、重啟與明確恢復、剩餘預算可用、後續耗盡拒絕不增加用量。
- 20個真實對話工作遵守原4並行上限，保持3個TLS請求飛行中時，其餘17個必須因推論預算被拒。核對run／task兩個meter、RUNNING／完成請求、來源憑據、訊息關聯與重啟後持久量。
- 真TLS 503與飛行中取消都只核准一次、不隱式退款或重試；明確恢復後仍由剩餘probe預算拒絕下一次。正式worker另驗本機故障→原預算明確恢復→新成功證據，不多發catalog或推論。

最終強化barrier後5個新函式加原Auth研究caller的race13.795s、vet及27發布回歸0.029s成功。完整Go／32 Browser及自己的新來源九項／實際產物仍獨立驗證，不能把聚焦成功當驗收。

原正式Models真實性流程與原研究Browser合計11.432s成功；維持9張真實性實圖、原研究375px／200%與零隱式membership斷言。瀏覽器固定依賴與CJK字型已恢復，最終完整32流程仍另跑。

原全部Go race／vet、32 Browser／380圖、Android JVM／device、Mac兩架構、真systemd與expanded workload及5min測試／15min Browser job上限均保留。自己的同來源九項與實際產物驗證前仍為候選，不能借015D綠燈。沒有目標VPS或實體裝置驗收。
