# Mac 工作區 UI：2026-10-17

本輪由使用者要求繼續並參考 Magpie，維持 SwiftUI／AppKit、teal、Light／Dark、Playground／Providers／Models 和右上 Settings。來源為 [yetone/magpie](https://github.com/yetone/magpie) 的官方 `site/public/img/panel-light.png`、`picker-light.png`、`add-light.png`；已直接檢視圖片。只參考短列、搜尋面板和主操作／進階設定分層，沒有複製商標、資產或程式碼，也沒有加入路由、Gateway 或第三方訂閱登入。

## 正式改動與 caller

- Providers：名稱、Base URL、描述、帳戶／啟用 Key／模型連線數集中短列，整列進入原 Provider Detail；搜尋空態可清除，不虛構健康度或餘額。
- Models：精確 scope 列取代巨大卡片；整列進詳情、獨立比較勾選、收藏篩選與完整搜尋。價格／額度／來源／歷史仍保留原 Detail，未知數不冒充 Free 或零。
- API 清單回應提供效能 mean，不含 p50。原 UI 讀 p50 導致有樣本仍空白；本片使用真 mean 並標「平均」。我的上線率附樣本及 API window_ms，少於20個樣本顯示「樣本較少」，其餘只稱「僅代表此時間窗」，不冒充統計信賴區間或站方可用性。
- Playground：緊湊 composer 與清楚的模型按鈕；⌘K、側欄及 composer 共用原生搜尋 sheet。只列已加入、ACTIVE、enabled 的模型，依完整 Provider×Account×Credential×Model×Group×Endpoint identity，顯示帳戶／Key label／群組與端點。只有使用者選擇才切換，不自動推論。
- 搜尋只索引明確允許的模型／scope／顯示 label／別名／canonical／vendor／category，不索引任意 config、secret 或 credential payload。所有查詢詞均須符合，大小寫／全半形／重音不影響匹配。
- `chooseModel` 重新檢查現存可用的 exact scope；未送出的草稿與附件原樣保留。已有對話先以既有機制保存草稿，再開新對話，不覆寫原 scope／history。串流中不能選模。
- 沒有新增 production dependency、服務、背景探測、資料庫 migration 或 Android／VPS UI 變更。

## 驗證與限制

起點 `97f9c64` 的九項已全綠；本片必須在自己 HEAD 重新通過，結果／失敗／人工實圖 review／下載 artifact 保存於 PR #21 最新 body。不能把下列驗收設計當作完成證據。

- `AtlasCoreTests` 新增全詞／width／case／accent／別名搜尋、禁止任意 secret 索引與完整 scope 不合併；原八項真 Keychain／engine／TLS／SSE／cancel 等測試全保留。
- Opt-in `NativeSmoke` 在獨立合成 DB、真 packaged engine／TLS 上執行：未送草稿／附件選模保留；既有對話選模產生新 draft，再恢復舊 draft／history；非法 scope 拒選；跨帳戶／provider 同模型同 Key ID 查詢精確命中。
- 每個架構保留原六張三工作區／深淺色／窄窗 PNG，增加深淺選模 sheet、窄窗 Models／Providers、新增供應商 sheet，總十一張。Capture 實際 AppKit frame view／attached sheet，不使用 HTML 或設計稿冒充執行畫面。
- 兩架構執行同一 Universal artifact，所有十一張必須生成且 smoke scope／draft guard PASS；人工檢視22張後才驗收。這是原生 view capture＋store caller，不宣稱 XCUI 全點擊覆蓋或已實測 macOS12。
- 保留原同 SHA 九項 gate、可取消／退出／engine 收尾及精確 CA 撤銷要求。RAM 報告僅 App＋owned engine 的本輪有限 workload RSS，排除 fixture／runner，不是全功能硬上限。
- 尚未 Apple Developer ID／公證、macOS12實機與進階專用表單的原有限制不變，見 `README.md`。
