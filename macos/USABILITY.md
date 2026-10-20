# MAC-USABILITY-004 — 原生 Mac 日常操作（0.1.3 / build4）

2026-10-17 使用者要求先把 Mac 做到可用，再考慮 Android／VPS。跨平台 UI 工作已保留在另一個未推送 worktree；本分支仍以已驗收的原生 0.1.2 `a00b038` 為基線，不引入其他平台候選。

- 對話搜尋改為停止輸入250ms後查詢正式本機API，改字／切換封存會取消前次 task。回應發布前檢查 cancellation與既有revision，舊結果不取代新結果；只改側欄清單，不切換目前session、scope、歷史或草稿。原本只在Enter觸發且忽略錯誤；現在顯示搜尋中、空結果、清除搜尋與讀取失敗的手動重試。
- 選模無論有無結果都能清除搜尋／收藏／供應商篩選，顯示符合數／總連線數。供應商僅列有可用連線者，同名以ID區分，原完整scope不合併；確認前重新驗證active scope，busy/closing禁止選擇。
- 既有三工作區、teal原生元件、深淺色、⌘K／↑↓／Enter／Escape／IME、不可變session scope、草稿／附件、Markdown／表格／收據與真正退出保留。

新增真App NativeSmoke對正式API的即時搜尋／封存／清除及保留目前session/history/draft測試；原15個核心測試與原18張畫面全部保留，另加filtered picker、empty picker、conversation empty三張，每架構21張、總42張。Native harness仍為4秒條件等待、120秒程序總上限、原500ms／25秒／完整1249字SHA256閱讀guard，不擴大timeout。

提交時是候選；須新HEAD的Mac Universal/arm64/Intel、VPS四項、Android build/device共九項SUCCESS，並檢查兩架構原生實圖後才交付。最終SHA、Actions IDs、artifact/hash、圖審和量測以PR #21最新body為準。沒有merge、目標VPS部署、Apple Developer ID或公證；CI為macOS15，最低部署macOS12。
