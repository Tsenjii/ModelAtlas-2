> 2026-10-26 MAC-RESEARCH-015R候選：原九項與九份實際ZIP／來源／producer／hash／CRC、42Browser／523圖、594核心＋423App／996device、原全部負載與公開凍結檢查保留。Go在原635事件上增加精確ID反例1pass及原生fixture明確opt-in skip，共420入口／637事件（587pass／50skip），仍共用原600秒。Mac以d261實際兩架構ZIP逐項保留原85核心與96圖，再增加4核心與8張第三原生程序真TLS／控制恢復圖；稽核要求89核心／104圖、精確7次TLS及3次控制、原ID／只讀恢復／程序退出。需實際檢視50個相關視圖後才驗收，不能借015Q綠燈。

> PRIVATE-MAPPING-015Q目前候選：418 Go入口／635結果（原015P604逐項保留）、42 Browser／523PNG（原41／509完整）、594核心／423App／996device（5原skip），兩Mac各85核心／96圖。九份同來源實際ZIP及14私人＋8同步＋兩Mac各6圖共34視圖皆須核對。另驗7d612a40原AA反例的實際523PNG ZIP；原根因未知，不借該來源其他成功項目。完整下一版PARTIAL及公開凍結不變。

> 當前發布／驗證器回歸為41項（原39完整＋兩個初始zygote缺少／歧義及deadline反例），512MiB與原90秒預檢deadline不變，沒有重試instrumentation。

> 目前稽核範圍WORKSPACE-CHUNKS-015P修正候選：407 Go入口／604結果、41 Browser／509PNG（015O原501完整）、594核心（015O585＋9）／423App／996device（015O985＋11，5原skip），兩Mac各85核心／96圖（原92＋4）。另驗首候選3ced失敗與原985逐項結果不變的持久證據；不借其成功項目驗收。新8Browser及兩Mac各6圖（含原Kilo2＋新同步4）共20視圖需要實際檢視。以下015O範圍留作歷史基線。

# 候選產物只讀稽核

`Candidate Artifact Audit` 只驗證開發候選，不發布、合併或部署。原 VPS 四項、Android 兩項、Mac 三項及所有 workload／timeout 完整保留；額外稽核等同來源九項成功後，才下載其各自生產 job 時間窗中的九份實際 ZIP，核對大小／SHA256／CRC、版本／架構／實際 APK 憑證、完整測試報告與畫面。

目前範圍為 FIELD-MAPPING-015O。固定原575個 Go case／subcase、384個函式與原984個 device case清單；新11個 Go函式與1個裝置反例加入後，須為395函式／586結果、40 Browser／501圖（原487完整）、585核心／423App JVM／985 device（5原skip）、兩架構各85核心／92圖。範圍隨正式新案例增加而明確修訂，不刪原案例或降低預算。受拒舊來源的 ZIP 僅用於原案例清單與負面證據，不能當作本候選綠燈。

腳本重用 `prepare.py` 的原來源與生產者檢查，只在工作目錄產生核對檔案。額外核對真 AMD64 包的版本、bundle-check、啟動15個Kilo模型、移除後重啟／明確加入仍14個，及無telemetry／session／研究／秘密副作用；離線代理拒絕對外連線。維持公開 tag／11附件不變並重新讀取 main 的 Android build＋device 全綠。39個發布／XML回歸含原35個；新反例拒絕重複case、缺失case或用綠色summary掩蓋失敗case。

成功時報告仍標示 `BYTE_AUDIT_PASS_PENDING_VISUAL_REVIEW`。實際14個新Browser與兩架構各2個Kilo畫面組成18個需人工檢視的視圖，保存在CI附件，並輸出相同bytes的JPEG聯絡表供工作環境中斷時讀取。未檢視實圖、九項有缺失、任何產物／報告不符時，都不能接受。完整下一版仍依 NEXT_RELEASE.md 為PARTIAL，不把本片或metadata核對當作全版完成。

本機或CI執行需要已授權的GitHub只讀token與精確source：`python3 release/audit_candidate.py --wait-minutes 32`。命令只做GET、下載與本機驗證；不呼叫發布API，不輸出token或真Key。
