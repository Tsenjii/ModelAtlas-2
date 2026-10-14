# VPS 完整 V1 實作與驗收

使用者於 2026-10-14 指示：繼續全部做完，可以超過 Android；輕量應從實作處理，功能不可刪減。Android 主線仍為 `5009e0c8`，VPS 基線 `c1b474a2`。

| 範圍 | 真實入口與完成條件 | 目前 |
|---|---|---|
| 完整 Playground | Chat / Responses / Anthropic / Gemini、多輪工具結果、語音生成播放下載、embedding / rerank；同一 scope / telemetry / cancel | 已驗 `29eff56` 六項 CI |
| 研究執行 | 持久佇列、逐步 probe、預算、取消／重啟恢復、來源與語言、完整漏斗、動態 AA top-4 | 已驗 `fa7c291` 六項 CI |
| App Assistant | 選擇任意已加入的模型、safe refs 與工具、實際讀取／研究／測試／新增／修改、共享 Review、confirm / undo / action log | 已實作，候選 Browser／六項 CI 待驗 |
| Reflection | 每輪成效、可測試的提案、版本化知識、有限權重與 recipe、可逆啟用／回復；禁止改程式或安全政策 | 待接線 |
| 模型與管理 | 完整篩選／排序、notes / tags、背景同步與規則通知、匯出研究知識 | 待補齊 |
| 輕量與交付 | 單一 Go binary / SQLite、bounded HTTP / worker / memory、真 Browser、race / vet、雙架構、同 SHA Android CI | 最終驗收 |

沿用既有功能，不重寫 Android；使用者金鑰僅由 privileged HTTP layer 使用。所有來源、失敗、歷史永久保留；Unknown 不推成 0 或 Free。未接真 caller 的功能不得寫完成。測試中的自建 TLS Provider 只驗 protocol / production flow，不冒充使用者帳號或外部實測。

每一整合片先測試與 CI 再續下一片；最新證據放 PR #20，避免以較早 SHA 綠燈取代目前版本。沒有目標 VPS 存取時，交付可安裝 binary 與部署文件，不聲稱已部署。

研究片：`fa7c291`／VPS run `38558907867` 四項 SUCCESS；Android run `38558907976` attempt 2 build `115846373240`、device `115846370638` SUCCESS。首輪 device 的既有快速連按測試 15 秒逾時；同 SHA 原斷言重跑通過，未變更 Android source/tests。Assistant 片含固定 system 邊界、安全參照、多輪工具、共用 onboarding Review、stale-safe confirm/Undo、取消及中斷日誌恢复；新增真 TLS 與 Browser 入口，尚待其新 SHA CI。
