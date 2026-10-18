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
