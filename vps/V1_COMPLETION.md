# VPS 完整 V1 實作與驗收

使用者於 2026-10-14 指示：繼續全部做完，可以超過 Android；輕量應從實作處理，功能不可刪減。Android 主線仍為 `5009e0c8`，VPS 基線 `c1b474a2`。

| 範圍 | 真實入口與完成條件 | 目前 |
|---|---|---|
| 完整 Playground | Chat / Responses / Anthropic / Gemini、多輪工具結果、語音生成播放下載、embedding / rerank；同一 scope / telemetry / cancel | 已驗 `29eff56` 六項 CI |
| 研究執行 | 持久佇列、逐步 probe、預算、取消／重啟恢復、來源與語言、完整漏斗、動態 AA top-4 | 已驗 `fa7c291` 六項 CI |
| App Assistant | 選擇任意已加入的模型、safe refs 與工具、實際讀取／研究／測試／新增／修改、共享 Review、confirm / undo / action log | 已驗 `69652c9` 六項 CI |
| Reflection | 每輪成效、可測試的提案、版本化知識、有限權重與 recipe、可逆啟用／回復；禁止改程式或安全政策 | 已驗 `293bd516` 六項 CI |
| 模型與管理 | 完整篩選／排序、notes / tags、背景同步與規則通知、匯出研究知識 | 本片已實作／待新 SHA 六項 CI |
| 輕量與交付 | 單一 Go binary / SQLite、bounded HTTP / worker / memory、真 Browser、race / vet、雙架構、同 SHA Android CI | 最終驗收 |

沿用既有功能，不重寫 Android；使用者金鑰僅由 privileged HTTP layer 使用。所有來源、失敗、歷史永久保留；Unknown 不推成 0 或 Free。未接真 caller 的功能不得寫完成。測試中的自建 TLS Provider 只驗 protocol / production flow，不冒充使用者帳號或外部實測。

每一整合片先測試與 CI 再續下一片；最新證據放 PR #20，避免以較早 SHA 綠燈取代目前版本。沒有目標 VPS 存取時，交付可安裝 binary 與部署文件，不聲稱已部署。

研究片：`fa7c291`／VPS run `38558907867` 四項 SUCCESS；Android run `38558907976` attempt 2 build `115846373240`、device `115846370638` SUCCESS。首輪 device 的既有快速連按測試 15 秒逾時；同 SHA 原斷言重跑通過，未變更 Android source/tests。Assistant 片含固定 system 邊界、安全參照、多輪工具、共用 onboarding Review、stale-safe confirm/Undo、取消及中斷日誌恢复；新增真 TLS 與 Browser 入口，尚待其新 SHA CI。

Assistant 片：`69652c9435985a8c5b62837586b4e1c454d0d1e7`／VPS `38567589731` 四項 SUCCESS，Android `38567589525` build `115864358311`、device `115864357829` SUCCESS。Browser artifact `11895842772`，digest `04be16a764c4d85a9021a6b4529febaa5f3f8b9a47efcb9471d811526c51aa1e`。前輪 Browser 找出 Assistant 混入 Playground 清單、過期完成訊息造成確認 race、200% 按鈕 overflow；產品已修，原斷言及 10 次媒體請求保留。

Reflection 候選：每輪反思、來源定義快照／版本、來源／語言優先序的可逆啟用、Auth／Endpoint／Discovery／Recipe／Verification 知識、宣告式唯讀 GET connector 草稿→真 HTTP 測試→確認→正式 Catalog／intelligence caller→Undo。價格單位／已知 0／缺值、精確 model/group、account PAT 角色分離；失敗保留 last-good。Credential version 由同一 auth snapshot 帶進 telemetry，研究不借舊 Key material 的測試。新增 Browser；尚待本片新 SHA 六項 CI，未宣稱完整 V1 驗收。

使用者於 20:56 指定可用 Kilo 免 Key 模型測試。`TestLiveKiloAnonymousConversationToolsAndAssistant` 以一次性 SQLite、合成資料、只允許無 Authorization 的 api.kilo.ai 請求，實測 `nvidia/nemotron-3.5-lightning:free` 成功：串流對話（2020ms）、原生工具呼叫／結果接續、真 App Assistant pending action→共享確認→持久 membership，共 6 次 HTTP／26.31s。時間為 2026-10-14 本片工作樹，非使用者資料；不以回報 model ID 當身份證明，也不推定 recurring free／台灣可用。測試需明確設 `MODELATLAS_LIVE_KILO_TEST=1`；一般 CI 不耗外部免費服務，外部可用性變動會如實 fail。官方入口依 https://kilo.ai/docs/gateway/authentication 與當時 live catalog 確認。

Reflection／connector／credential epoch：`293bd5169a9038769935389e054a6b653b21a119` 同 SHA 全綠。VPS run `38572762140`：browser `115881961928`、test `115881962099`、amd64 `115881962116`、arm64 `115881962239` SUCCESS；Android `38572762176`：build `115881963335`、device `115881962869` SUCCESS。Browser artifact `11897834419`／digest `94cc6a6954fc8689dfbde8f94877e3aa864f42a7c4eb4d37a6acda2be36b5189` 已檢視 375px／200% 深色研究工作台。

管理／資源候選：模型 fuzzy aliases、多條件 filter、價格／額度／AA 強度／最近使用排序，可設定強度／速度／額度的加權再套用可靠度，Unknown 不當 0。Provider ranking 僅計目前 enabled 的 My Models，Global Catalog 不借我的 Key 數據。Notes／tags 與 Provider 編輯共用可逆 action Review；能力更新 reactive；對話工具顯示修正。所有原始歷史完整保留，SQL 計算全量 mean/p50/p90/p95；只顯示 400 個 chart 樣本，其他 history／catalog／membership 有 API paging。預設 SQLite 2 MiB cache、檔案暫存與 192 MiB Go soft memory limit、2..4 flights；靜態 JS/CSS gzip/ETag。真正 standalone process（100,000 raw telemetry、完整分位數查詢、4 個 TLS inference）RSS 量測加入 CI；本機 proc namespace 無法讀取子行程 RSS，不以本機數值冒充實測。

定期 metadata/catalog sync 接真 queue，15..10080 分鐘、每輪 1..10 scopes，預設 OFF；不自動 inference 或 membership。Notification master + 7 rules 全部 OFF，依 credential epoch 保留確認值，價格／quota／outage／recovery transition 與日簽到 dedupe，持久事件與本機通知 caller。Safe backup v2 增加 aliases／notes／settings／收藏狀態／recipes／研究來源／版本知識／connector 定義；v1 可還原，完整 transaction、憑證禁用需重新輸入、網路工作不自動啟用，imported knowledge 需新 evidence-backed proposal，connector 需再真測。SQLite 全庫加密備份另保留完整聊天、觀測、研究 raw history；設定匯出不冒充全庫 backup。管理 Browser 與 standalone footprint 待候選 CI，剩餘 multi-origin／CUSTOM／native speech／最终回歸繼續。

管理前輪 `9d61ceb1`：VPS test／amd64／arm64 SUCCESS，既有四個 Browser workflow 通過；新增管理 Browser 在 multiword alias fixture 發現產品 alias validator 只容許 ID（不能含空格）。修正 search alias 可用安全人類名稱、拒絕控制字元，保持原始 model ID 不變；不降低 Browser assertion。VPS test run 38579671767 的 standalone workload 實測 binary 13,308,066 bytes、idle RSS 26,468,352、warm/peak RSS 29,188,096，十萬筆 raw history 全量 percentiles＋四個真 TLS inference 已過（22.07s）。此數據是指定工作負載，不宣称所有媒體峰值相同；管理整片仍待修正版六項 CI。

管理第二輪 `4acd725` 前四個 Browser 流程与新增管理流程前半通過；最後 Settings 的測試在展開 details 前等待內部欄位可見而逾時，已改為先等待可見時區，再實際展開背景設定，保留功能斷言。產品 review 另修 notification 不以缺 output/fixed price 當「已確認變更」、保留當前 epoch 的已確認值，事件時間避免重複轉換被永久 dedupe；新增 Unknown 回歸。上下文記憶體上限回傳明確 413／新對話提示，1025 訊息仍可完整分頁閱讀。

管理完整 review 新增 Model batch hide/archive/restore：共用 action Preview→confirm→Undo，2..100 exact scopes，一個 transaction；任一 scope 更新／移除／ABA 都以 membership revision 拒絕整批 stale 變更，已加真 API 回歸與 Browser；內部 revision 不進 Review UI。排序權重保存後重新載入且保護未提交輸入。

Ranking review 修正：搜尋／grouping aliases 與 manual canonical 不借 AA 身分或 top-4 特例；只比較實際 raw ID／AA ID／名稱及不移除版本後綴的 separator normalization。`winner-latest` 不自動當作 `winner`，exact AA raw model 仍正常；新增 regression。

管理／資源片 `8f0696256cff19a360b8b7e29e75932c2c3a054b` 同 SHA 全綠：VPS `38582129810` browser `115914470596`、test `115914471220`、amd64 `115914471212`、arm64 `115914470849` SUCCESS；Android `38582129791` build `115914470312`、device `115914470641` SUCCESS。Browser `11904166438` digest `a9fc914f9023b0d84b9e3a22b49c790000ce98f10ce8294521be830277803207` 已檢視管理 375px／200% 深色。最終本片 standalone artifact `11904471293`：binary 13,316,258 bytes、idle RSS 26,447,872、warm/peak RSS 29,134,848、SQLite 20,578,304 bytes；十萬筆 raw history＋四個 TLS streams，尚未把媒體負載當作已量測。

22:37 使用者新增：全部完成後量測程式 RAM，先讓 Kilo 找 Provider，再繼續 UI。`live_research_test.go` 以匿名 Kilo 真 Agent 工具嘗試 Groq／Cerebras／OpenRouter 公開官方來源與 durable research，未登入／註冊／動使用者憑證。當前 Work 執行環境無外部 direct DNS（實測 DNS unavailable），不降低 production DNS pin／SSRF 邊界；已保存失敗紀錄，改由獨立 opt-in Actions Linux workflow 執行。普通 deterministic CI 不耗免費服務。Provider leads 須實際頁面、source/evidence/history；不能以 LLM 文本或任務建立冒充擷取成功。

Kilo 研究 code `b7ecdaa976ed4f26cfdd2783f83e054500ef19c6` 同 SHA 全綠：VPS `38584571842` 四項 SUCCESS；Android `38584572181` build `115923005384`、device `115923006296` SUCCESS。明確 opt-in live run `38584572569`／job `115923745767` 真實成功（33.54s）：6 次匿名 Kilo HTTP、7 次 public HTTP，7 tasks 中 3 SUCCESS，2 個候選、3 evidence。Groq／OpenRouter HTTP 200；Cerebras 307 未跟隨，不冒充讀到資料。Artifact `11904961510` digest `cd43b898a23bd6318737bf8c7ed9518bf87aff31a88161df58b7d7d5003782f4`；本機 DNS 失敗歷程保留，沒有削弱 SSRF。沒有宣稱這些 leads 已過 ordinary gate 或新發現未見服務；模型建議只作文字，不以 Kilo anonymous account 借給其他 Provider。已關閉 PR title opt-in 標記，普通 CI 不再執行外部免費服務。

當前多 origin／App research 能力候選：immutable credential origins＋optional auth override，舊 Key／PAT／endpoint／session 不隨 primary URL 修改，secondary URL 要 explicit profile，primary change 先有新的 bound credential且共享 Review/Undo；已接 creation/onboarding/Assistant/PAT/connector/backup 與 Provider UI。Token Group 只用同 credential epoch/origin、最新 certified explicit group assertion；錯誤 Group 不借免費價格／不可 inference，rotation 不借舊 assertion。Nominal group quote保留獨立資料，不轉成此 Key effective/free。Assistant 能保存 public source與匿名 Provider Review（使用 DNS-pinned public client）；typed tool registry最多32definitions，單run12tools/4web budgets不變。來源抓取失敗仍保留FAILED lead与failureevidence、last-good不覆蓋。Provider表單收合、Review顯示具體scope，新TLS與Browser待新SHA gate。CUSTOM／native speech／session設定／最終媒體RAM仍接續。
