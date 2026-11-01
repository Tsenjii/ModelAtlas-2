# KiosAPI：Intelligence 驗證案例

研究日期：2026-10-18（Asia/Taipei）。使用者指定 `http://kiosapi.com/`，官方 HTTPS 首頁為 `https://kiosapi.com/`。這份紀錄是公開來源研究，不是認證連線驗收。

## 一手來源與目前可支持的結論

| 來源 | 可支持的內容 | 不能據此推定 |
|---|---|---|
| [官網](https://kiosapi.com/)／[價格頁](https://kiosapi.com/pricing) | 這次公開頁面讀取返回前端殼，未取得可核對的價目表內容 | 不能猜各模型目前價格、群組或API版本 |
| [官方公告頻道](https://t.me/s/kiosapi_official) | 站方公告免費群組須 Telegram 驗證／加入頻道，Key 需選對群組；免費模型可變動、部分為限時，穩定性及併發不保證。公告亦指明主 Base URL 使用 kiosapi.com | 不證明任一使用者已取得資格、selected-key effective charge=0、固定daily quota、台灣註冊成功或模型真實性 |
| [官方 GitHub](https://github.com/kiosapi)／[speedtest-ai](https://github.com/kiosapi/speedtest-ai) | 站方測速工具支援 OpenAI-compatible 與 Anthropic，README區分 TTFT／TPS／latency。可以參考報告的可讀性 | 工具相容性不等於 kiosapi.com 全部相同協定已通過；fallback字數估算不得混成 ModelAtlas 真 token usage；不傳 Key 到此工具 |

搜尋另出現 kiosapi.id，屬不同網域與實體；本案不引用其 API docs／quota／價格填入 kiosapi.com。第三方 LMSpeed 等只能保留 External source，不作站方或 My Key evidence。

這次公開 `/api/status`／`/api/pricing` 無可核對的 JSON 回應，未確認 New API 家族與支援版本。因此不把已知 New API 路徑、任意free後綴或公告模型名硬寫成此站已驗證資料。

## 後續真實驗證流程

1. 用既有 Quick Add／Research inspect 同源官網、status、docs、前端結構，確認版本與端點契約。暫時不可讀需記錄 failure，不當 removed。
2. 只有確認家族／版本後讀取其公開 pricing／performance；管理資料需精確 account safe reference，PAT 與 inference Key 分開。
3. 已加入的 My Model 才掛接對應 Provider×Account×Credential×Model×Group×Endpoint；整站 catalog 仍留 Catalog。比較 nominal 與 effective charge，不依 free group 名稱直接判FREE。
4. 使用者明確選定的 Key／Model 才做小型真 inference，保存 metadata、usage、TTFT／TPS／latency、費用與額度 before/after；無 credential 時保留未驗證，不假成功。
5. FREE NOW、recurring mechanism、RPD／TPD、Taiwan eligibility、authenticity各自證據化。RPM-only／limited promotion不能充當 ordinary RPD≥100且TPD≥1,000,000 的主結果資格。
6. 保存本次未知、失敗、來源／查證時間與下一步，不因資料缺席丟候選；有可驗證 recipe 後走 draft→test→review→activate／undo。

本次開發只補通用原生模型資料讀取與歷史功能，不替使用者建立 Kios 帳戶、不購買、不加入頻道、不設定未驗證 recipe，也沒有宣稱 KiosAPI 已可用或永久免費。
