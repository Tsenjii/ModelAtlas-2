# ModelAtlas 公開版工程規範

> Principal Engineer / Coding Agent 必讀。本檔繼承原 ModelAtlas 的工程紀律，不複製私人交接紀錄或 Git 歷史。

## 強制讀取順序

1. `docs/ACTIVE_TASK.md`：唯一 live 狀態、目前允許的 slice、全綠基線及 feature-freeze。
2. `docs/DECISIONS.md`：公開版已拍板的持久產品決策。
3. `docs/PRODUCT_SPEC.md`：產品母規格，並參考使用者原文 `docs/ASTRA_MASTER_SPEC.md`。
4. Provider/Model/Pricing/Quota/FREE/UI/Assistant Research 領域必讀 `docs/PROVIDER_MODEL_INTELLIGENCE_CONTRACT.md`。
5. Crawler/eligibility 必讀 `docs/RESEARCH_POLICY.md`。
6. `docs/TRACEABILITY.md`、`docs/IMPLEMENTATION_PLAN.md`、相關 Kotlin production/tests。
   跨平台接續亦讀 `docs/CROSS_PLATFORM_STRENGTHS.md`，保留各平台優點與尚未補齊情境。
7. 同一 repo 最新 main code SHA、Actions build + device job；不能用文件「已測」取代目前 repo 的 CI。

衝突時：本輪使用者明確指示 > `DECISIONS` 較新適用條目 > Intelligence 專門契約的已拍板內容 > `PRODUCT_SPEC` > Master Spec 原始提示詞 > implementation/status/historical docs。不能因摘要漏寫而刪除既有需求。

## Full-green / Red-head lock

- 同一 **Commit SHA** 下 GitHub Actions 的 `build`（JVM tests + debug APK + androidTest APK）與 `device-tests`（Android emulator tests）皆 SUCCESS，才是 **FULL GREEN**。
- 完整 CI 缺失、in_progress、infra red 或 test red 一律不稱 full-green；觀察到問題需先調查性質。
- 最新 main code HEAD 未 full-green 時 FEATURE FREEZE=ON：只處理本片 regression、review、必要測試及文件 freshness；禁止疊新階段。
- 每次整合只能有一個可追蹤 slice；測試與 production caller 都是真實可用才可 ACCEPTED。禁止刪測試、改低 expectation、套 mock 假裝通過。
- 每次更改都要記錄 SHA、Actions run、build 與 device 結果、尚未驗收事項。
- 同一 repo 的 public `main` 為新工程主線；私人舊庫只作歷史來源，不自動 cherry-pick 舊 Git 歷史。

## 永久產品鐵律

- Unknown != 0、Unknown != Free；`FREE_NOW` 不等於 recurring-free。
- 資料 provenance/evidence 真實且分 source；official / local / external / manual 不互相冒充。
- Provider × Account × Credential × Model × Group × Endpoint/Operation × Time 不互借 scope。
- Trial/welcome bonus 不冒充 recurring free；Crawler 主結果 ordinary gate 為 RPD >= 100 **且** TPD >= 1,000,000；AA 動態 top-4 特例及灰區見 Decisions/Research Policy。
- Catalog != My Models；只有明確使用者/Assistant/Explore 加入才進 My Models；測試失敗不取消成員資格。
- sync 一次失敗不能抹 last-good/model/price；取消不算 My Uptime。
- User/Provider/External performance 與 effective/nominal price、quota/free/access 狀態各自分開。
- raw API key/PAT/Auth/Cookie 不進 AI prompt、Git、log、telemetry；憑證只在 Android 安全層解密使用。
- 不因為難而砍掉 Phase 8/9/10；不自行啟用尚未接真 runner 的背景工作。
- 目前不要實作多供應商自動路由、Fallback 或 App-as-API Gateway。

## UI：延續原版寫法，禁止另起設計系統

- 依 D-009，本輪先將 VPS 介面及功能對齊 Mac，Android 介面接續。
- Android 未改版部分**延用** `AtlasTheme.kt` 的 Jetpack Compose + Material 3、Light/Dark、teal accent、`AtlasSpace` / `AtlasRadius`、`AtlasCard`、`AtlasBadge`、`AtlasEmptyState`。
- 導航始終只有 Playground / Providers / Models；Settings 右上；Playground 像聊天產品，不像 debug console。
- Default zh-TW + Android string resources；保留 i18n。正確運用 icons（非 emoji）、48dp touch targets、IME/safe insets、可捲動、窄螢幕/大字/深色適配。
- Provider/Model 主清單可掃描、少文字、整卡可點；重要 Evidence/History/詳細效能留在 Detail。沒有可靠 Balance/Quota 值時不硬塞 `Unknown` 進卡片。
- VPS 採 Mac 中性色與可選重點色、桌面側欄／行動底導覽；Android 追加 UI 維持 `Flow`→VM→Compose reactive。
- UI 可由現有 composables 增量擴展。**不得宣稱 PLAY-004 已完成**：目前 transport 有能力，不代表 Composer/Result UI 全接線。

## 安全與公開協作

- 不將 private 交接、Token、歷史敏感 git commit、debug payload、帳號資料複製到 public。
- 對一般程式碼修改可直接提交/開 PR（使用者已授權），但 branch merge 必須符合全綠 gate。
- Public docs 都用繁體中文；不可把過期 status/猜測寫成已驗收的產品能力。
