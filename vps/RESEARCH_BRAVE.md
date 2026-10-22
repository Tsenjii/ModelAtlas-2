# Brave 分頁研究與恢復

2026-10-22，RESEARCH-BRAVE-015B，內部開發候選；不另發小修版本。正式入口為全球研究的「開始一輪研究」及持久研究排程，保存後仍須明確啟用。使用原Go runner、SQLite與一個介面poller。

## API 與界線

- 官方POST規格：https://api-dashboard.search.brave.com/api-reference/web/search/post
- 官方分頁說明：https://api-dashboard.search.brave.com/app/documentation/web-search/codes
- 已於2026-10-22查核：count上限20；本機維持每頁10筆；offset為頁號0–9，可能跨頁重疊。只有query.more_results_available=true才續頁。
- 新options：search_pages為1–10（0／舊客戶端省略仍1），search_country為明確支援的TW／ALL／US／JP／KR／DE／FR／GB／CA／AU／BR／IN（省略TW）。不是新增推論或自動路由。
- 使用原8語言與明確query；計畫／排程接續固定地區、頁數、策略版本及啟用時期。地區是搜尋偏好，不是台灣可註冊／可用的證明。不同設定的品質scope不混用；反思提案帶地區／頁數，評估重新核對。
- 沒有後續提示的多頁回應：已完成頁保留，停止並說明。到頁數上限只表示本輪上限，不表示搜完全球。搜尋頁面沒有站方跨頁snapshot isolation，可能隨時間改變；不宣稱同一瞬間快照。

## 持久化與原子邊界

schema24新增本機research_brave_checkpoints與research_brave_daily。checkpoint保存scope hash、加密憑證revision hash、24h期限、下一頁、每頁已核准attempt、已完成hash、最多100網址hash／候選及最多30項有界恢復journal。只在此引擎可執行，不進workspace sync或portable backup；同步的公開任務紀錄不能重建執行權。

每次HTTP前，單transaction核對run／schedule intent／來源確認／origin冷卻，並同時保留本輪HTTP、排程與全排程每日HTTP、Brave共享UTC日1000次本機保護上限、該加密Key版本本日用量、task品質receipt與page attempt。任何額度或儲存拒絕整筆回滾。換Key不重設共享cap；Key版本用量不是站方quota。HTTP送出後中斷仍保留admission；不是站方已計費次數的證明。

成功頁先安全驗證，再把候選、未驗SEARCH_DISCOVERY證據、本機不可變quality receipt、後續INSPECT與checkpoint放同transaction。重複網址只計duplicate_hits；同Provider仍不因語言或頁面重建成員。page commit失敗不留下半頁候選／evidence，不前進；完成commit後重播不發HTTP。任何credential revision／run pause／schedule intent／來源變更於commit再查，避免晚回應繞過控制。保存資料仍是candidate，不進My Models、不直接VERIFIED。

## 有界等待

429／502／503／504及傳輸中斷，每頁最多3次已核准連線（初次＋2次恢復），30／60秒穩定分散退避，Retry-After為下限，無阻塞sleep。所有語言／其他輪次共同尊重固定Brave origin的冷卻；本機等待不消耗HTTP或page attempt。超過24h的遠端等待不縮短，停止本task自動重試；其他新輪仍受同origin下限保護。

送出前即保存attempt與可能程序中斷後的等待下限；重啟或排程接續不重設attempt、期限或已完成頁。401／403、redirect、格式／續頁提示不符、過大回應、換Key或過期則停止，保留已完成證據。取消不作來源可用性失敗。原推論／私人catalog沒有自動重試。

## 介面與用量

正式表單可選地區與頁數；預設維持TW／1。執行紀錄呈現已保存頁、上限、線索／重複／略過數、等待時間／失敗歷史、預算／暫停／取消／終止原因。延用原modal、48px按鈕、深淺色與200%。新增欄位沒有額外poller。

Dashboard呈現LOCAL_ADMISSION的所有Key共享日用量與目前加密Key版本用量，未設定Key保持null；provider_remaining、tokens與currency_cost均null。每日UTC00:00重設計數查詢，歷史永久保留。不把本機上限／revision計數當provider account-wide usage或實際費用。

Mac內建同源後端取得此控制，Mac／Android專用新設定與進度UI尚未移植。完整WISH-CRAWL-001、逐scope推論／token／費用限制、候選重驗策略與實際主機／真站長跑仍待後續。

## 驗證與失敗歷史

- 修前真TLS：0.065s FAIL，原429直接FAILED，無持久等待。首新測試誤用POST runs／202而400，修成既有start／201；這是測試錯誤，不是產品bug。
- 新12個後端測試函式含多種子情境：真POST與跨重啟只補未完成頁、原子額度兩向回滾、共站限流、跨日／換Keycap、跨頁重複／complete重播、地區scope與反思、401／redirect／格式／提示／頁數上限、schedule接續、sync只讀、v23一致升級、飛行中換Key／pause／cancel、crash3attempt、body中斷、超長等待／期限／secret echo、admission／page的DB故障回滾與LMSpeed衍生搜尋的凍結設定。
- 首廣泛聚焦66.127s FAIL：新測試只預填global1／上限100而未填schedule cap1，錯誤假設會拒絕；另重播增加新的合法反思卻假設只一版本；crash模擬重啟後未先明確RESUME原手動pause。修測試實際前提／操作，原期待的額度回滾、永久反思歷史與3次上限不降低。修後Brave＋原多語搜尋／Knowledge Evaluation聚焦race33.910s成功，storage故障race3.034s成功。
- 首Browser31.825s FAIL：巢狀label包含所有選項文字，搜尋地區無精確可讀名稱。補aria-label後正式流程9.55s成功；不是降低locator／timeout。測試從UI保存停用→啟用→真page1／429page2→追加budget／pause→reload→續跑，只補page2；3個搜尋HTTP＋2個public inspect，日／revision／run／schedule用量一致，2份未驗證線索、1重複、0MyModels。
- 新8實圖320／375／1280深淺色、375大字及恢復後已檢查。原29 Browser／355圖、5m Go／15m job上限與所有原生／資源工作量保留，新增預期30流程／363圖。
- 完整本機、最終來源九項與CI實圖仍獨立驗證，精確來源／run／job／artifact在ACTIVE_TASK及PR #20。未完成不標ACCEPTED，不修改公開版本或附件。

- 最後審查補上LMSpeed衍生query沿用此輪明確地區／頁數，避免回到TW／1；正式caller新增聚焦race2.126s成功。此修正後最終來源的完整CI另驗，不借修正前本機全套。vet與27個發布回歸已通過（0.033s），公開release控制檔未變更。

- 9e75ff0初候選本機完整30 Browser289.058s成功；審查追加慢速回應（虛擬時鐘經過120秒才中斷）的真TLS反例1.227s FAIL：原從admission起算的30秒已過，worker在同次tick立即送第二次。修成每次觀察失敗後重算30／60秒下限，仍保留送出前的crash等待，timeout亦保存redacted失敗journal。修後Brave／原多語／Knowledge Evaluation聚焦race41.063s與vet成功。新來源仍需自己的全部CI，9e75ff0與其已跑結果只作被取代候選保留，不借綠燈。
