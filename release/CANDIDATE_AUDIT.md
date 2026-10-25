# 候選產物只讀稽核

`Candidate Artifact Audit` 只驗證開發候選，不發布、合併或部署。原 VPS 四項、Android 兩項、Mac 三項及所有 workload／timeout 完整保留；額外稽核等同來源九項成功後，才下載其各自生產 job 時間窗中的九份實際 ZIP，核對大小／SHA256／CRC、版本／架構／實際 APK 憑證、完整測試報告與畫面。

目前範圍為 FIELD-MAPPING-015O。固定原575個 Go case／subcase、384個函式與原984個 device case清單；新11個 Go函式與1個裝置反例加入後，須為395函式／586結果、40 Browser／501圖（原487完整）、585核心／423App JVM／985 device（5原skip）、兩架構各85核心／92圖。範圍隨正式新案例增加而明確修訂，不刪原案例或降低預算。受拒舊來源的 ZIP 僅用於原案例清單與負面證據，不能當作本候選綠燈。

腳本重用 `prepare.py` 的原來源與生產者檢查，只在工作目錄產生核對檔案。額外核對真 AMD64 包的版本、bundle-check、啟動15個Kilo模型、移除後重啟／明確加入仍14個，及無telemetry／session／研究／秘密副作用；離線代理拒絕對外連線。維持公開 tag／11附件不變並重新讀取 main 的 Android build＋device 全綠。39個發布／XML回歸含原35個；新反例拒絕重複case、缺失case或用綠色summary掩蓋失敗case。

成功時報告仍標示 `BYTE_AUDIT_PASS_PENDING_VISUAL_REVIEW`。實際14個新Browser與兩架構各2個Kilo畫面組成18個需人工檢視的視圖，保存在CI附件，並輸出相同bytes的JPEG聯絡表供工作環境中斷時讀取。未檢視實圖、九項有缺失、任何產物／報告不符時，都不能接受。完整下一版仍依 NEXT_RELEASE.md 為PARTIAL，不把本片或metadata核對當作全版完成。

本機或CI執行需要已授權的GitHub只讀token與精確source：`python3 release/audit_candidate.py --wait-minutes 32`。命令只做GET、下載與本機驗證；不呼叫發布API，不輸出token或真Key。
