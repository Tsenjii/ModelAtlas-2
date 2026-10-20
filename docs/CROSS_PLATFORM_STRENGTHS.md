# 跨平台優點與持續補齊清單

2026-10-20 最新指示先 VPS 可用性與後續更新（VPS-OPS-006），原同步核心與各平台優點保留。VPS 增加完整架構套件、schema 前快照、預檢／健康／失敗及中斷復原；此輪只修改 VPS 及維運文件，未把結果外推為 Mac／Android 實機或目標主機部署。較舊優先序為歷史。


2026-10-20同步核心已由VPS／Android `c9d1d33` 六項、Mac `f67cfa4` 九項同SHA驗證；三端各保留本機與離線資料、真目的端vault、持久游標及明確衝突。原生呈現未對應、大型分塊及實機仍PARTIAL；詳細矩陣與證據見`SYNC_CONTRACT.md`及`verification/20261020/sync-ci-baseline.json`。

2026-10-20 最新優先序為 SYNC-001；依 D-012 接續 VPS／Android／Mac 的同步，取代下方歷史片的暫停順序。各平台既有優點和待辦保留；本片新候選同 SHA 九項前不外推全面互通。

2026-10-18，依使用者「其他平台比我們好的留下來繼續搞」建立。這份清單與完整 Master Spec 同時維護；既有功能與各平台優點保留，不能為了外觀一致而刪除。比較必須能指到正式 caller 或真實驗收，不能只用有無 API／選單判斷功能已完成。

依 D-010／D-011，先完成 VPS 的 Mac 0.6.1 正式 UI／操作對照，並保留 VPS 單 binary、持久 SQLite、完整歷史及低配置成本的優點；省資源與免費部署評估見 `vps/RESOURCE_HOSTING.md`。Android 保持暫停。狀態「候選」代表已實作或正在驗證，尚不能當作已交付。

| 優點或落差 | 已核對的來源 | Mac 處理與接續驗收 |
| --- | --- | --- |
| VPS：同頁多表單的草稿、展開與精確選取保留 | `vps/ui_js_provider_forms.go`、`vps/e2e/provider-forms.cjs`、`vps/PROVIDER_FORMS_UI.md` | 012 候選先改善帳戶／Key；表單移出長清單，草稿由 Provider 工作區在記憶體保存，關閉重開仍可接續，成功時不抹等待期間的新稿。端點／Provider 設定及 Review 的全部情境仍須接續 |
| VPS：每份表單有等待、保存成功、失敗及「已保存但刷新失敗」回饋 | 同上，正式 API commit 後刷新失敗的 Browser 驗證 | 012 候選加入局部回饋與只重新讀取清單；成功後不引導重複 POST。驗真 401、真 commit、讀取失敗、重試與關閉後晚完成 |
| Android：清楚標示操作使用的帳戶／Key／群組／模型／端點，不把啟用當成可用性 | `app/src/main/kotlin/dev/modelatlas/app/ui/ProviderDetailScreen.kt` 的 ActionScope／DetailAccountsCard／SelectedScope、`ProviderDetailLoader.kt` | 0.6.0 已改善名稱與 Catalog 操作；012 候選的更新／恢復再次顯示完整帳戶／Key。每把 Key 的 last success／failure、availability／quota／stale 原生摘要仍列高優先，不能由 enabled 推定 healthy |
| Android：busy 時保護互相衝突的動作，未選定 scope 有原因與指引 | 同檔 ProviderModelActions 與 scopeReady／busy guards | 012 候選將新增 Key／逐把啟停／帳戶批次啟停／秘密值更新串成帳戶共用鎖；另一帳戶仍可操作。模型測試／同步／Assistant 跨入口的全部情境仍需分項對照 |
| VPS：Catalog 加入、來源與完整長名稱可辨識，失敗不清草稿 | `vps/CATALOG_UI.md`、`vps/e2e/catalog-experience.cjs`、`macos/Sources/ModelAtlas/CatalogDetailView.swift` | 保留目前精確 Key／Endpoint 加入與既有成員資格；繼續對照長名稱、空結果、缺設定引導與大字，不用 raw UUID 作主要標籤 |
| VPS：永久觀測／請求歷史與完整匯出；大量資料有分頁與記憶體證據 | `vps/PARITY.md`、`vps/receipts.go`、`macos/CHARTS.md`、`ObservationArchiveController.swift` | Mac 已有原生分頁／圖表／收據；保留全量匯出及來源／scope，不為了動畫只保留最近資料。仍需持續驗大資料與各單位／來源的真可讀性 |
| VPS：獨立服務可持續運行；Android 與 Mac 的生命週期不同 | `vps/PLATFORM_DEPLOY.md`、`macos/MASTER_SCOPE.md` | 保留 VPS 部署能力；Mac 休眠不承諾 24/7。背景排程、通知、資格及來源時效按平台實際能力呈現 |
| Mac：原生 Keychain、薄輸入框、IME／Undo、鍵盤選模、分區來源與原生圖表 | `macos/MASTER_SCOPE.md`、`macos/PROVIDER_MOTION.md`、`NativeSmoke.swift`、`NativeParity.swift` | 原有 70 核心／136 實圖與 28 frame 全保留再增加驗證；其他平台改善時也不砍現有 scope、歷史、取消與 last-good |

## 下一步優先序

2026-10-19 接續：`b7444fe` 已同 SHA 六項 SUCCESS／19 Browser／263 PNG 核對，手機對齊與安全區保留。當前 VPS-MODEL-WORKFLOW-005 新增瀏覽器本機常用檢視、可移除篩選條件、最近使用／追蹤快捷鍵及精確連線直達（`vps/MODEL_VIEWS.md`）；屬新候選，不能外推前片驗收。這些流程可作後續 Mac／Android 的操作參考，尚未移植；保留兩平台現有優點與暫停狀態。

1. VPS-MAC-ALIGN-001 已由 3aec657 同 SHA 六項／16 Browser 驗收。本輪 VPS-MAC-PARITY-002 對照全部 Mac caller，補正式表單／完整側欄／模型分區與編輯／來源／比較／服務；新 HEAD 須完整六項／原16 Browser＋新增流程及實圖，不借前片結果。
2. 使用者最新要求本輪留在 VPS；Android 介面未開始，原有優點與待辦保留。
3. 私人站點 adapter 逐站 schema／認證、多 Key availability／quota／stale、研究／Assistant／Reflection 及完整大綱仍追蹤，P1–P10 仍 PARTIAL。

### 原 Mac 接續缺口（保留追蹤）

1. 完成 012 帳戶／Key 的同 SHA 九項驗證及實圖，才更新此片為已交付。
2. 高優先：Provider 設定／端點草稿與晚完成的局部回饋；私人帳戶 adapter 的逐站 schema／認證相容性；多 Key 的 availability、quota、stale 與同名 scope。不能略過版本／role／origin 限制冒充站點已支援。
3. 接續 Catalog 缺設定引導、研究／Assistant／Reflection 的可讀流程及完整大綱 scenario。以 `macos/MASTER_SCOPE.md`、`docs/TRACEABILITY.md` 追蹤完整 P1–P10；仍為 PARTIAL。

每次接續先讀此表與 ACTIVE_TASK，依新驗收證據更新來源、狀態與未驗情境。不得把別的平台未完成規劃當作既有能力；routing／fallback／自動 Key 輪替／Gateway 繼續 future-only。
