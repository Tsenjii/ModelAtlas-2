# ModelAtlas 已拍板產品決策（公開版）

> 由私有專案的產品決策整理，刻意不包含 worker runtime、帳號、Token 與私人交接細節。
> 適用優先順序以根目錄 AGENTS.md 為準；不能用文件摘要代替完整契約。

## D-001：全球免費強模型主結果門檻
- ordinary main eligibility **RPD >= 100 且 TPD >= 1,000,000**，兩者含等號。
- 不達標的 Candidate 不刪除，可留研究 funnel/grey-zone。
- 特例為 **Artificial Analysis 最新有效排名最高前 4 名**的動態 top-tier model，不以模型家族永久白名單判斷；需保存分數、排名、時間、證據。
- RPM-only、Dynamic Pool、Unlimited claimed、Public Pool、undisclosed 不能假造數字，無法證明 ordinary gate 時保留 grey-zone。

## D-002：全量持續資料
- Telemetry、evidence、snapshot、研究與價格歷史持續保留；不自動定期刪除。Cache TTL 是刷新時間，不是歷史刪除。
- Provider/Model/My Uptime/Price/Quota 各有獨立 Scope，缺值永遠 Unknown。

## D-003：My Models
- Catalog != My Models；只有 User / Assistant / Explore 的明確加入才列於主要 Models UI。
- 成功 inference **不是加入模型的條件**；加入後應自動觸發能取得的 Intelligence enrichment，局部失敗不影響成員資格。
- Hidden/Archived 不清歷史、samples、evidence；Purge 需明確 destructive confirmation。

## D-004：Performance / Free
- My Uptime＝使用者指定 Provider×Account×Credential×Group×Model×Endpoint 的真實觀測；User Cancel 排除。Provider-reported success rate / LMSpeed external 各自獨立。
- 預設 Performance 視窗 7d；可切 1h/24h/7d/30d/custom。Observed RPM/TPM 是 rolling 60s，不能偽稱 Provider 官方限額。
- effective charge、nominal price、quota/accessibility、recurring-free mechanism 是不同維度；不要依 `free` 字樣或 balance=0 判 FREE/PAID。
- 不做暴力 max RPM/TPM load test；使用者實際 Playground telemetry 優先。

## D-005：UI 與通知
- Bottom Nav 只有 Playground、Providers、Models；設定在右上。
- **延用** 原有 Material 3 / teal / AtlasTheme。首頁清爽、少字、整卡可點，細節留 Detail/History；Provider card 短介紹、模型與平台各自使用合適圖示。
- 所有通知預設 OFF，使用者從 Settings 逐項啟用。
- App Assistant 不把 Raw Key / PAT 交給 LLM；敏感破壞性操作需確認。

## D-006：工作流程與授權
- 既定工作可依優先序連續處理，不必每片徵詢；每片必須實作、測試、審查、同 SHA 全綠，再接下一片。
- Red-head lock：main 只要未獲同 SHA 全綠就不開新功能。
- V1 P1–P10 是完整交付目標，不因難度刪除 P8 Crawler／P9 Assistant／P10 Reflection；但 routing/fallback/gateway 仍是 future-only extension。

## D-007：Mac 原生外觀（2026-10-18 使用者明確更新）
- 使用者認為既有Mac UI過時，要求實際研究Magpie／其他好看軟體，也明確質疑固定藍綠按鈕；Mac不再被D-005的Android teal綁定。
- Mac預設石墨灰，中性色承載內容與清單；主要按鈕可在設定選石墨灰／跟隨macOS／鳶尾紫／靛藍，保留深淺色。只保存非敏感本機外觀偏好，不改引擎設定或排程。
- 沿用現有SwiftUI／AppKit元件，參考官方實圖的資訊層級、分組、字級與互動；不增加WebView／Electron或外部UI依賴。
- Android／VPS既有配色與未整合UI保持原狀；三工作區、scope／Unknown／last-good約束不變。

## D-008：保留跨平台優點（2026-10-18 使用者明確更新）
- 使用者要求繼續優化 Mac，並將其他平台做得更好的功能與操作保留、持續吸收，不能因跨平台外觀對齊而砍功能。
- `CROSS_PLATFORM_STRENGTHS.md` 保存實際來源、Mac 狀態、未驗情境及下一步；每片接續先讀，按正式 caller／測試與實圖更新，不以 API 存在冒充流程完成。
- Mac 仍優先，Android／VPS 現有優點不回退；完整 Master Spec／既有 retention、scope、Unknown、last-good 與全部安全／驗收約束繼續適用。

## D-009：跨平台對齊 Mac，VPS 優先（2026-10-19）
- 使用者最新明確指示：Android 與 VPS 的介面及功能對齊 Mac，先完成 VPS；取代 D-007／D-008 的 Mac 優先與其他平台配色不變限制。
- VPS 延續單 Go／SQLite 及原生瀏覽器元件，採石墨色、可選重點色、桌面側欄、薄輸入框及分區設定；手機保留底導覽與 48px 操作區。
- 移植逐帳戶簽到、完整觀測分頁、站方公開資料與圖表；保留六份 Provider 表單的草稿／展開／精確選取、平台部署、全量匯出與全部十四個原 Browser 流程。
- Android 本輪只同步 Mac 已驗收的必要 harness 修正，介面下一片處理。完整大綱仍 PARTIAL，不宣稱全面對齊。
- 新 VPS HEAD 同 SHA 四項 VPS＋兩項 Android 全綠且實圖檢查後才驗收；不 merge、不冒充已遠端部署。

## D-010：VPS 的完整 Mac 操作對齊（2026-10-19）
- 使用者要求 VPS 的介面與功能全部跟 Mac 一樣；D-009 的 Android 下一片改為先完成 VPS 的逐頁對照與缺口。
- 以已驗 Mac 0.6.1／74e2c37 的 production caller 為標準，對齊導航、表單、草稿、來源分區、圖表與管理操作；VPS 部署、完整匯出、窄螢幕與 48px 優點保留。
- OS 專屬 Keychain／Finder 使用平台對應的安全儲存與下載，不模仿假的原生系統能力。不將尚未完成的完整 Master Spec 項目偽稱為 Mac 既有能力。

## D-011 — VPS 同步補資源效率與部署評估（2026-10-19）

使用者在 VPS-MAC-PARITY-002 明確追加「優化邏輯省資源、考慮 CF 免費或 GitHub，先處理好全部」。同片先降低重複編碼與輪詢，不以截短上下文、刪歷史、削並行或減測試換 RAM。GitHub 用於建置／分發；現有完整 Go／SQLite 優先使用持久 Linux 主機，CF 作入口可另接。Workers／D1 原生版屬另片實際移植，不能只新增部署按鈕就宣稱完整免費；沒有授權自動部署或變更 DNS。具體來源與限制見 `vps/RESOURCE_HOSTING.md`。

## D-012：跨平台資料同步與完整研究目標（2026-10-19）

- 使用者要求實作願望清單全部新增項目，同時優先解決 Mac／VPS／Android 的資料同步；本輪不只登錄文件。
- VPS 作可選同步中心；各端保有本機資料與離線使用，恢復連線以持久游標和版本同步，不複製正在使用的 SQLite 檔，不用定期備份覆蓋另一端。
- 穩定 ID 與完整 scope 跨裝置保存。衝突保留雙方版本並明確處理；刪除有持久 tombstone，歷史／證據仍永久保留。
- 裝置獨立配對、認證與撤銷；同步 Key 是受控安全通道，在目的裝置重新封入本機 vault。TLS 傳輸及各端加密儲存不宣稱為中心無法解密的端對端加密；VPS 是受信任、可執行使用者 inference 的裝置。
- 研究策略／recipe 仍版本化、可審查、可回退；不能因同步而執行遠端程式、外部指令或放寬 secret／origin／destructive policy。

## D-013：VPS 可用性與後續更新優先（2026-10-20）

- 最新使用者指示先完整處理 VPS 可日常使用與之後更新；取代前片優先把原生同步對應全部補齊的順序。既有各平台優點及完整願望清單保留。
- 交付可驗證架構套件、實際版本／狀態、可重複安裝與更新。原 DB／master key／管理密碼保持原值；更新前完整一致備份、在副本試遷移、健康驗證失敗自動回復；中斷有持久日誌，不憑健康 probe 200 或編譯成功宣稱可用。
- VPS 單 Go／SQLite 與小資源部署優點保留；Git 平台須提供實際持久磁碟與 HTTPS 入口。不能自行替平台開放明文 backend，不能將沒有實際存取的主機宣稱部署完成。沒有安排自動安裝未驗證的新 commit。

## D-014：VPS 第一、Mac 第二、Android 第三與三平台同版發布（2026-10-20）

- 使用者明確要求 VPS 優先完整優化，因為可能先使用 VPS 版；Mac 第二、Android 第三。取代歷史的 Mac／Android 優先序，完整願望清單與各平台既有優點不刪減。
- 專案首頁更新為三平台實際能力、下載／安裝／更新與未完成界線，不再只介紹 Android，也不能把公開研究第一階段或反思提案冒充完整自我進化。
- 每次完整 Release 同時交付 VPS AMD64／ARM64、Mac Universal DMG／ZIP、Android APK，同一來源 commit 與九個原必要 CI job 全 SUCCESS；另驗發布邊界、全部內外層雜湊／版本／架構／實際簽署資訊。
- 使用者已授權建立三平台 Release；先完整 draft 上傳並核對，之後才公開 prerelease，不為此合併 PR／部署主機。不得以新版本覆寫已公開 tag 或附件，不自動安裝 HEAD。
- 第一版 Android 為 CI debug 簽署預覽 APK，固定私密正式簽署金鑰尚未設定；Mac 為 ad-hoc／未公證。下載頁明示限制，不公開 keystore、不假稱可無縫更新或已正式公證。
- VPS 的正式 package／固定資料與原更新回復流程保留；新增明確 tag 的下載／CPU 選擇／校驗入口，只下載到新目錄，執行 install／update 仍為明確操作。

## D-015：下一版以接近完整功能為發布門檻（2026-10-22）

- 使用者明確要求「目標是全部做完，下一版要完成幾乎所有功能」。下一個應用程式版本以完整日常流程與原願望清單主要功能為目標；不能再把單一恢復修補當作已達成此要求的下一版。
- 以已公開驗收的 0.8.5-preview.1 為基線，開發期間允許內部提交、Draft PR 與逐片 CI，暫不變更 release/request.json／既有 tag／附件。下一版工作與逐項證據集中在 NEXT_RELEASE.md；版本號在發布條件具備後才更新。
- 維持 VPS → Mac → Android；研究排程與預算、未知站點草稿與實測 Review、可量測改進閉環、真實性證據、完整同步與原生操作流程均須有正式入口及失敗恢復，不能以後端／schema／摘要代替功能。原始 28 條補充驗收條件與 P1–P10 全部保留。
- 同時只整合一個可追蹤工作片，完成正式 caller、必要驗證及同來源九項 CI 後才接受、再接下一片。整個版本最後另跑完整回歸及三平台產物驗證，不借內部中途 SHA 的綠燈。
- 目標主機與實體裝置證據必須真實取得；沒有存取權就保留未驗證，不把本機、模擬器或 CI 當作實機。不得靠刪需求、降低工作量、跳過測試或改寫歷史來宣稱「幾乎完成」。

## D-016：持續推進直到完整目標（2026-10-24）

使用者明確指示「繼續搞 全部都完成再說可以嗎 目前進度推進」。沿D-006／D-015，每片完成正式caller、回歸與同來源九項及實際產物的內部驗收後，直接推進下一個可獨立完成的缺口；不因小片完成就結束等待使用者重複說繼續。維持一次一片、red-head lock及原全部測試／負載／timeout，不將未驗當完成。先完成已授權且不依賴外部存取的實作，再集中列出真站／目標主機／簽署／實體裝置等實際缺少的條件；公開版本與發布門檻不降低。

## D-017：下一版必須穩定可用且完成（2026-10-24）

使用者最新明確指示「繼續 下一版一定要穩定能使用且完成 一定要」。下一公開版本必須依P1–P10及完整願望清單完成主要正式流程，並通過穩定性、失敗恢復與完整同來源驗收；不因一個小片全綠放寬成只有部分功能的完成版。未完成／未驗證不得勾選或宣稱完成，原需求與全部測試／負載／timeout不降低。維持VPS→Mac→Android，先把已授權可獨立推進的工作完成，再集中列明真站／目標主機／實體裝置／簽署等實際缺少的條件；不捏造結果、不逐片停下問是否繼續。D-015的公開版本／發布請求凍結及D-016連續推進仍適用。
