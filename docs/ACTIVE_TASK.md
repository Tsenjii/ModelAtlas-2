# ModelAtlas Public — ACTIVE TASK

## 最新使用者指示：完成願望清單並優先解決跨平台同步（2026-10-19）

- `2220229f4b8c6102dc705ab4587e46e2ad9ee58e` 已六項同 SHA SUCCESS：VPS `39402144371` 四項、Android `39402144405` build `118559196703`／device-tests `118559196220`。正式 Go HTTPS↔原生 Room／Keystore 的雙向編輯、4 MiB 歷史、衝突與撤銷已通過。接續同片補原生→中心→第二原生裝置、同名 Key 的不同帳號／origin、匿名連線與 scoped membership 移除；新候選仍重新完整 gate，不借用本基線。
- 2026-10-20 接續核對：`fede52769a7cd76ae579d1097af3dd9739ffa7c7` 的 VPS `39356955335` 四項 SUCCESS、Android `39356955363` build SUCCESS／device-tests FAILURE（971 項、5 項既有 API skip、1 項真 Go↔Android 同步失敗）。Room migration 全部通過；憑證投影因 framework UPDATE 未引用 SQL 保留欄位 `group` 而失敗。本次修正引用欄位、補原生 insert/update/null/空字串回歸，投影失敗只保留不含參數／例外訊息的程式位置。新 SHA 必須重新六項 gate，仍不稱 SYNC-001 已驗收。
- 使用者已授權實作 Part V 全部需求，並明確要求解決 Mac／VPS／Android 資料同步。先處理唯一整合切片 **SYNC-001**，其後依序接研究排程、未知站點解析、可量測 Reflection 與 authenticity；不在只寫文件後結束。
- 同步採可選 VPS 中心＋各端本機離線資料、版本／游標增量、持久變更與明確衝突處理；裝置獨立配對／撤銷，Key 經認證 HTTPS 傳送並在目的端 vault 重新加密。秘密不進模型、log、同步 metadata 或 Git。
- 基準 `2f0af8296f3996bc83b1fa42b9596a61a2e79410`：VPS `39266118313` 四項、Android `39266118493` build／device 同 SHA SUCCESS。main 已重驗仍 `5009e0c8a733f1863adcf9a5b8ef8c450da76d3d`，`38408176782` build／device SUCCESS；FEATURE FREEZE=OFF。
- 最新授權允許同步所需的 Android／Mac 功能接線；保留原 UI／功能優點及兩分支既有修改，不合併／部署。新切片有 production caller、測試與同 SHA 必要 CI 前不升 ACCEPTED；實體裝置／目標主機未取得的驗收範圍如實保留。

## 最新使用者指示：補回原願望清單（2026-10-19）

- 本輪為文件整理：將完整功能盤點後的持續發現排程、未知站點解析／Connector、研究改進閉環、模型真實性與真站／跨平台／手機完整驗收，加入原 `ASTRA_MASTER_SPEC.md` Part V，保留全部原文與既有需求。
- 新增 WISH-CRAWL-001／002、WISH-REFL-001、WISH-AUTH-001、WISH-ACCEPT-001；全部 **OPEN：待完成／待驗收**，`PRODUCT_SPEC.md` 已接引用，`TRACEABILITY.md` 登錄正式缺口。未開新的 production 功能切片。
- 盤點及前片已驗基準 code `ae0849294fdcf4f48c0cf549de797e59afd53ecb`：VPS `39258806970` 四項、Android `39258806978` build／device-tests 同 SHA SUCCESS，20 Browser／281 PNG 已自行檢查；main 仍 `5009e0c8a733f1863adcf9a5b8ef8c450da76d3d`。本次文件 HEAD CI 另查 PR #20，不借用 code 基準宣稱新 HEAD 全綠。
- 完整 Master Spec 及新增五項均未整體驗收；PR #20 維持 Draft，Mac／Android 原狀態與暫停順序不變。後續實作依本檔單一切片與同 SHA gate 接續；本輪不 merge／部署。

## 前片 VPS-MODEL-WORKFLOW-005

更新：2026-10-19；唯一 live 切片 **VPS-MODEL-WORKFLOW-005**。使用者要求「新增一些功能以及功能優化及 UI 優化」；接續 VPS 的模型查找→比較→使用流程，不轉 Android。

- 前片 code `b7444fe9be8f6a397521422aa40e340a908807ca` 已確認 [VPS 39253363969](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/39253363969) 四項與 [Android 39253363984](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/39253363984) 兩項同 SHA SUCCESS。19 Browser／263 PNG 已自行檢視；原 expanded workload 服務峰值 208,445,440 bytes、待機 26,345,472 bytes，不含 OS／代理／瀏覽器。原始證據見 `vps/verification/20261019/b7444fe-baseline.json`，只代表該 code SHA。
- 本輪再確認 main `5009e0c8a733f1863adcf9a5b8ef8c450da76d3d` 的 build／device-tests SUCCESS；FEATURE FREEZE=OFF。
- 本片新增明確儲存／更新／刪除的瀏覽器本機模型檢視、可逐項移除的篩選條件、最近使用／追蹤快捷鍵，以及完整 scope 的連線／比較結果直達 Playground；沿用原 picker change path，切換不自動 inference／不新增對話，保留各對話草稿與歷史。正式 caller、限制與驗證見 `vps/MODEL_VIEWS.md`。
- 新 HEAD 仍為候選，須六項同 SHA SUCCESS、原十九 Browser 加新增第二十流程／實圖及原 expanded workload 才驗收；自行 review，無獨立外部 reviewer。PR #20 保持 Draft，不 merge、不冒充已遠端部署。Mac／Android 與完整 Master Spec 的 PARTIAL 狀態不變。

## 前片 VPS-MOBILE-ALIGN-004（已驗 code，較新 HEAD 另驗）

- `b7444fe9be8f6a397521422aa40e340a908807ca` 同 SHA 六項 SUCCESS／十九 Browser／263 PNG 已驗；手機頁面／分頁／表單與五種 dialog 的共用對齊、48px 操作、200% 大字與直橫安全區見 `vps/MOBILE_ALIGNMENT.md`。
- 驗證是 Chromium mobile／touch／safe-area override，不代表實體 iPhone／Safari／軟體鍵盤測試；不 merge、不遠端部署。

## 前片 VPS-MANAGEMENT-UX-003（已驗 code，較新 HEAD 另驗）

- 前片 code `b3c16e95b29a61d93d53a945fea78aa80a7dc31f` 已確認 [VPS 39218809855](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/39218809855) 四項與 [Android 39218809822](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/39218809822) 兩項同 SHA SUCCESS。17 Browser／134 PNG 已自行檢視，原 expanded workload 服務峰值 208,769,024 bytes、待機 26,451,968 bytes；不含 OS／瀏覽器，沒有獨立外部 reviewer。原始證據見 `vps/verification/20261019/b3c16-baseline.json`，只代表該 code SHA。
- 本片正式流程及界線見 `vps/MODEL_CONNECTIONS.md`。模型概覽直接列同 Canonical Model 各家完整 scope 的本機速度／上線率／樣本與明確比較；Providers 新增今日簽到進度、last-good／重試、晚 GET 不撤銷完成回條及跨日保護。
- 新 HEAD 仍為候選，須六項同 SHA SUCCESS、原十七 Browser 加新增第十八流程／實圖及原 expanded workload 才驗收；PR #20 保持 Draft，不 merge、不冒充已遠端部署。Mac／Android 與完整 Master Spec 的 PARTIAL 狀態不變。

## 前片 VPS-MAC-PARITY-002（已驗 code，較新 HEAD 另驗）

- 已交付 VPS `3aec65729b9daf5db7ac5629ecca69df4ef94afc`，VPS `39188438480` 四項及 Android `39188438452` 兩項同 SHA SUCCESS，16 Browser／124 PNG 已驗。這是第一輪對齊，不能宣稱完整 Mac parity。
- 固定 Mac 參考 `74e2c378c1117fda3bebdb9f8d50ead872aa5af7`／0.6.1 build12，使用獨立唯讀工作樹；原始五份 staged Mac 檔與暫停 Android UI 不動。
- 本輪重新確認 main `5009e0c8a733f1863adcf9a5b8ef8c450da76d3d`／`38408176782`，build `115333198754`、device `115333198701` SUCCESS；FEATURE FREEZE=OFF。
- 本片補導航／完整連線對話搜尋與草稿、帳戶／Key 獨立編輯、Provider Catalog／可讀設定、模型六分區／三來源效能／額度／完整資訊編輯與圖表控制；差異與 caller 見 `vps/MAC_PARITY.md`。
- 使用者追加資源效率與 CF／GitHub 免費部署評估；同片補完整 context 分段 JSON、5 筆輪詢／不重疊及通知 OFF 節流，保留原 expanded workload／六項 gate。架構、受控編碼對照與官方部署限制見 `vps/RESOURCE_HOSTING.md`；不假稱現有 Go／SQLite 可直接在免費 Pages／Workers 跑完整後端。
- 原 16 Browser 語意斷言、全量匯出與 expanded workload 保留；畫面改動只調整真實入口與幾何。新 HEAD 仍須六項同 SHA SUCCESS／實圖檢查才驗收。PR #20 保持 Draft；不 merge、不改 Mac PR base、不冒充已遠端部署。完整 Master Spec 仍 PARTIAL。

## 舊切片歷史（不代表目前優先序與新 HEAD 驗收）

# ModelAtlas Public — ACTIVE TASK

更新：2026-10-15（VPS 獨立分支），公開 repo `Tsenjii/ModelAtlas-2`。

## Live state
- **VPS 當前唯一切片：Git 平台部署相容性。** 使用者部署 Infrlo 並要求自行查核；以 `98bedbf0bd9689217c34f433a61d4be1bc437acd` 同 SHA 六項 SUCCESS 為基線（VPS `38753924366`、Android `38753924418`，十四 Browser／99 實圖已驗收），本輪重新查核 main `5009e0c8`／`38408176782` build/device SUCCESS。新增可直接使用的 build/run 腳本、明確 opt-in 平台 `PORT`／HTTPS origin／固定 master key／絕對 DB 設定與入口 guard、啟動失敗非零退出及 SIGTERM 收尾；真獨立程序＋HTTPS proxy＋SQLite/vault reopen 驗證。官方 Infrlo README 未提供 runtime／ingress／volume 契約，控制台在本環境顯示 Site Unavailable，不能冒充平台已確認／已遠端部署。詳見 `vps/PLATFORM_DEPLOY.md`；新 HEAD 六項 CI 全綠前為候選，下方 Provider 候選列為歷史。
- **Provider 錯誤遮擋實圖修復：** `fe65c24`／VPS `38753014920` 四項與十四Browser成功（含設定固定競態回歸）；99張實圖人工檢查發現Key JSON錯誤可能被sticky dialog-bar遮住。錯誤改捲到可見中央，新增account／Key JSON／Key503／config全部須在sticky標題下且dialog內的幾何斷言；375／1280／200%直接透過真invalid JSON submit拍錯誤，不用測試捲動代替產品行為。原斷言與workload不減，新HEAD重新六項與實圖；不以fe65的部分結果驗收最新HEAD。
- **設定草稿競態（本片必要回歸修復）：** `db21a33`／VPS `38751952139` Provider第十四流程與其他十二流程全過，既有workspace抓到timezone草稿UTC被慢`loadStatus`改回Asia/Taipei。改正式設定表單逐欄dirty guard、狀態讀取不再晚寫form、保存revision保護下一份草稿；同workflow加入真status回應／真settings commit後延遲barrier，原草稿斷言保留，再驗換頁／重開／保存中編輯、SQLite仍只存當次UTC與OFF預設。新HEAD仍六項同SHAgate，沒有以重跑掩蓋競態。
- **Provider 選單 a11y 回歸修復：** `cdc7b0b`／VPS `38750457348` 原十三Browser全過、Go／footprint／雙架構成功，新第十四流程精確選單label查找失敗；`705883d`／`38751411611`的更嚴格完整陣列差異證實空locator，實圖則有兩個選項。原因為包住select的label混入option文字；為帳戶／認證／用途／rotation補固定aria-label，原嚴格完整選項與scope斷言保留。晚回應測試另等待原真form完成finally後才驗證Q未被替換。不是加等待或force click掩蓋失敗；新HEAD重新六項gate。
- **VPS 當前唯一切片：Provider 表單與保存回饋。** 使用者「可以」授權繼續；以 `03307f1e9046f45af2ab52458a8762cd9f987079` 同SHA六項SUCCESS為基線（VPS `38735387218`／Android `38735387179`，十三Browser與實圖已驗收）。main仍`5009e0c8`／`38408176782` SUCCESS。帳戶／Key／端點／rotate／config原地更新、跨表單草稿與展開保留、form內JSON／HTTP錯誤、pending重複提交guard、同名Key帳戶scope、晚完成mutation guard；新增第十四個真Browser，不改schema／Android／API語意。詳見`vps/PROVIDER_FORMS_UI.md`；新HEAD六項同SHA與實圖通過前PR #20維持Draft，下方Catalog候選為歷史紀錄。
- **Catalog 實圖收尾：** `f8a7370`／VPS `38734782608` 十三Browser全過（含原management及真封存fixture）。實圖確認200%原生select會截短網址；補可展開完整帳戶／Key／Group／用途／URL，真文字Range不相交檢查，Catalog總覽截圖收合已驗證篩選區。仍為本片修復，最新HEAD重新六項CI；未借f8的部分綠燈驗收。
- **Catalog 首輪回歸修正：** `4eaf16e`／VPS `38734249142` Go race／vet／footprint／雙架構成功，原11個Browser成功；management因新舊分區同名「篩選模型」產生strict locator歧義，新第13流程fixture的UPDATE未建立封存state而初始化不符。改Catalog標題「篩選 Catalog」，fixture改走真`/models/lifecycle`建立封存；保留原1個active target及全部斷言，不加等待或force click。新HEAD仍重跑六項。
- **VPS 當前唯一切片：Catalog 探索與連線加入。** 基線 `62ac7908e58f6e66daddb5b5fb241d42cd054d7a` 已由 VPS `38711778298` 四項及 Android `38711778305` build/device 同SHA SUCCESS 驗收，十二Browser與實圖已通過。main仍`5009e0c8`／`38408176782`全綠。本片改善型錄搜尋／篩選／已加入狀態、Key／origin-bound endpoint選擇、缺設定引導、可見錯誤重試、manual draft與晚回應guard；新增第十三個真Browser。詳見`vps/CATALOG_UI.md`。新HEAD六項CI與實圖通過前為候選，PR #20維持Draft；下方詳情片候選紀錄為歷史。
- **錯誤可見性修復：** `4e48a09`／VPS `38710997731` 四項及十二Browser全過，standalone idle25.52／peak408.11MiB。實圖抓到初次讀取失敗訊息被append在概覽內容末尾；改prepend到各分區頂端，日期驗證也在表單內用role=alert顯示並捲到可見處，不倚賴dialog外的notice。新增兩個實際viewport可見斷言，新HEAD六項重驗；4e的Android未借作新HEAD驗收。
- **詳情反應性收尾：** 能力／分類保存後原概覽仍持有舊 snapshot；改用真 mutation 回傳值只更新識別／能力區，不重建或清除其他表單。Browser 直接確認 VISION、canonical／category 即時更新與 notes 草稿仍保留。屬本片 UI correctness repair，新 HEAD 重新六項 gate。
- **詳情大字實圖修復：** `cdbb36d`／VPS `38709934831` 四項及十二 Browser 全部 SUCCESS；人工檢視200%供應商欄位時抓到長 label 超出固定窄欄，與值相擠。改用隨字級／空間自動換行成上下排列的 intrinsic grid、繁中推論 Key label，新增每個可見 dt／dd 真文字 Range 相交檢查。不是下個功能 slice，為本片視覺／a11y修復；新HEAD仍六項gate。
- **本片回歸／視覺收尾：** `a7e9273`／VPS `38709192566` Go race／vet／footprint／雙架構 SUCCESS，新增第十二個詳情 Browser 與其他十流程成功；舊 management flow 在概覽尋找已移至設定的備註，補走真分區入口，保留原 notes Review／confirm／Undo 全部斷言。實圖也將主要操作只留概覽、其他分區少一整段重複控制、縮短價格標題，raw endpoint ID 收進識別資料。新 HEAD 仍重新六項 gate，不借部分綠燈。
- **VPS 當前唯一切片：詳情工作區與非同步操作正確性。** 使用者「繼續搞」；以已六項全綠 `c609cf2d65d7c598b5806dd2011ca8e75a45b819` 為基線（VPS `38695626679` 四項；Android `38695626675` build/device SUCCESS）。重新確認 main 仍 `5009e0c8`／`38408176782` SUCCESS。模型與 Provider 詳情分區、可讀價格／來源／分位數、原地時間窗更新與草稿保留、錯誤重試、晚回應／已關閉詳情 guard；原 schema、HTTP scope、Review／Undo、歷史與全部 V1 不變。新增第十二個真 Browser flow；本機 race／vet／JS syntax 已過，**新 HEAD 仍須六項同 SHA CI＋實圖檢查**；PR #20 期間保持 Draft。詳見 `vps/DETAIL_UI.md`；下列較早 slice 失敗與候選為歷史紀錄。
- **前片已驗收：** `c609cf2` 的工作區／聊天／清單／外觀及新對話同步 reset，十一 Browser 全過；Android gate 兩個必要 regression repair 亦由同 SHA 全套驗證，無需重做。前片 standalone idle 25.21 MiB／peak 376.48 MiB，不含 OS；新片 RAM 仍取本片 Actions workload，不把樣式改動推論為 RSS 降低。沒有 merge／目標 VPS 部署。
- **新對話 reset 時序回歸：** `5613943`／VPS `38694380170` 的 chat-experience 在 Meta+Shift+O 後，session ID 已換但舊「回到最新」仍短暫可見；其他十 Browser 通過。修正式 newConversation 成功後同步清 UI／新標題與閱讀狀態，force follow 立即隱藏按鈕，新段立即採空 system／512／streaming 預設；歷史與舊設定不刪。保留原 immediate hidden 斷言，新增真舊設定保存／立即重設／保留檢查，不改長等待或重跑。Android harness `5613943` 已由 `38694380099` build `116287408298`＋device `116287407961` 同 SHA SUCCESS 驗證；新 UI HEAD 仍須六項 gate，不借前片綠燈。
- **Android gate harness repair（本片必要回歸）：** `0e7acf6` VPS `38692621075` 四項／十一 Browser 全綠，Android `38692621025` build綠、device `116281827382` 的 `CredentialRollbackFlowTest.enablePathUnchanged` 15秒逾時（其餘無失敗、5個既有API29 skip）。保留該失敗報告；查到 harness 在非Main建立／清除VM與呼叫UI操作，且反覆 busy-loop `waitForIdleSync`。改真Main caller／單純 `state.first` 掛起等待、原15秒上限／原T1–T5全部斷言，IO測試旗標用AtomicBoolean，新增四輪真Room＋vault＋blocked/projection對照。只改 instrumentation harness，不改Android production／產品phase；新HEAD仍須六項CI，不將0e部分綠燈或重跑當完整驗收。
- **大字導覽幾何回歸：** `f4b9194`／VPS `38692212749` 前十 Browser 全過，新增第十一 flow 精確抓到 375px／200% 英文 nav label 超過各自 button；保留單字完整，改用有界 responsive 字級與比例 flex／緊湊間距，48px touch 不減；原幾何斷言維持。新 HEAD 重新跑六項。
- **本片視覺 QA 收尾：** `87df330`／VPS `38691391871` 四項與十一 Browser 全綠，真 screenshot 發現 desktop 不同內容卡片高度置中導致頂端不齊、200% 品牌／底 nav 文字拆行，及啟動成功 toast 遮住內容；只作本片樣式／啟動提示修整，新增幾何回歸斷言，仍需新 HEAD 六項全綠，不拿 `87df330` 部分驗收代替。
- **本片回歸處理：** 候選 `6b2e74e`／VPS `38690238103` 發現空 DB 沒 target 時 `loadChat` 提早返回，第一個 Provider CTA 沒顯示；工具／對話設定選單會遮傳送。補空態正式入口、選單完成／focusout／可用空間定位與大字頁面 reflow。Go connector 原測試以整份 facts JSON 搜尋 `999`，會誤判 provenance URL／隨機 TLS port；以真 `/v999/v1` TLS 路徑已穩定重現原斷言 false-positive，改為 exact scope SQL＋JSON rpd=0／tpd=1000000／origin／scope_kind，原 last-good／failed／Undo／Catalog 斷言全保留，不重跑掩蓋。新 HEAD 仍重驗六項。
- **VPS 2026-10-15 頁面重整切片（使用者明確要求改善外觀與功能）：** 基線 `5522c7f67545f7ee8ec11eac4fa77e26519540bf` 同 SHA 六項 SUCCESS：VPS `38686585333`／Android `38686585247`；main `5009e0c8`／`38408176782` 重新確認成功。保留 teal、三主分頁與全部 V1，以原生 HTML/CSS/SVG 重整工作區、卡片、聊天 composer／對話工具、設定分類。新增可鍵盤操作的互斥 tabs、模型快速篩選／完整重設／選取後批次操作、空狀態真入口、Catalog 同 provider/id 顯示名稱、瀏覽器外觀偏好。切換工作區不改 scope／不丟草稿／不自動推論，詳見 `vps/WORKSPACE_UI.md`。目前為本輪候選，必須最新 PR HEAD 六項 checks＋十一個真 Browser 全過並檢查實圖後驗收；下方是已完成歷史，不代表本片已通過。
- **VPS CI 必要相容性修復：** `979a8a6` 的 VPS 四項（含十個 Browser、2,000 deltas僅9次DOM寫入、完整18,250字元）全綠，Android build綠，但 `38685239940`／device `116258469234` 在 h4 測試抓到既有 `AtlasViewModel.requestMyModelRefresh:3167` 的 certified-operations Set 尚未初始化而 NPE（Main.immediate／constructor callback）。不以重跑掩蓋；將兩個 observers init 保持原內容／相對順序移至全部欄位後，新增 h5 真裝置反覆建構／certified endpoint／membership與零inference guard。這是本片 gate 的必要相容性 repair，Android main與產品phase不變；新HEAD仍須完整六項，不借 `979a8a6` 的部分綠燈。
- **VPS 2026-10-15 串流體驗切片：** 使用者授權繼續優化並參考其他專案；以已同 SHA 六項全綠的 `5c616053496f37e1a0af44d3b9614bc6432c3800` 為基線（VPS `38680781659`／Android `38680781661`）。參考 assistant-ui 閱讀位置與 Open WebUI 串流效能，實作原生 DOM batching／閱讀暫停跟隨與回最新／保留未變歷史 DOM／生成中草稿／IME-safe 快捷鍵與真 cancel；無新 production dependency／migration。`vps/CHAT_UX.md` 保存目標、caller、參考及驗收；新增第十個正式 Browser flow，以3,000 Provider deltas＋真 TLS／SQLite 檢查完整文字、DOM writes、閱讀位置、draft、clipboard／107則匯出、鍵盤與 socket cancel。此片驗收需 PR #20 本片 HEAD 的六項 checks 全綠，確切 SHA／run／指標以 PR body 為準；不能借下方舊基線冒充。
- **VPS 較早已驗收 code：** `57ef23e96c335413003b497b06cd99b7b8838b6e`，完整 V1＋對話管理／安全 Markdown／請求收據／明確重試／完整匯出。VPS `38631190515` 四項與 Android `38631190578` 兩項同 SHA SUCCESS；九個正式 Browser workflows 通過。擴充 standalone workload 峰值 **406.36 MiB**，待機 **25.36 MiB**；Kilo 真研究 `38629907160` 成功。交付見 `vps/DELIVERY.md`；最後文件 HEAD 仍查 PR #20 六項 checks。下方較早 VPS 進度為歷史紀錄，不能代替本條。未 merge／未部署，Android source 未改。
- **Android MODE：PLAY-004 繼續剩餘多模態切片**；Audio Input 已由 PR #18 合併並驗收。**VPS MODE：獨立 branch `feature/vps-rebuild-20261014` / PR #20**，不得誤當 Android 的 full-green slice；VPS 仍須另行全部測試。
- **最新 VERIFIED main FULL GREEN：** `5009e0c8a733f1863adcf9a5b8ef8c450da76d3d`，來自已合併 [PR #18](https://github.com/Tsenjii/ModelAtlas-2/pull/18) Audio Input；[main CI 38408176782](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38408176782) **build SUCCESS + device-tests SUCCESS（同 HEAD SHA）**。此 main HEAD 的 FEATURE FREEZE=OFF；下一次任何 main 紅燈即再上鎖。
- **PLAY-004 新對話 [#14](https://github.com/Tsenjii/ModelAtlas-2/issues/14) ACCEPTED：** [PR #16](https://github.com/Tsenjii/ModelAtlas-2/pull/16)／main `069b541a` [CI 38397137563](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38397137563) build/device 全綠。只清當前選定 scope 的 UI/wire history、draft/picker；telemetry、samples、quota、他連線不可刪。
- **PLAY-004 PDF 第一切片 ACCEPTED：** [PR #17](https://github.com/Tsenjii/ModelAtlas-2/pull/17) 候選 `f8984e1` [CI 38401105133](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38401105133) build/device SUCCESS，合併 `d7dd460a` [main 38403442111](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38403442111) build/device SUCCESS。原失敗 [38399650161](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38399650161) 不冒充綠燈。production PDF picker→LocalPdfPayload→VM FILES gate→InferenceEngine→OpenAiTransport 已驗。
- **已驗收音訊輸入片：** 延續現有 `AudioInputPart` 真實 Chat Completions wire，使用 Android `GetContent(audio/*)`，不請求麥克風權限、不錄音；本機 WAV／MP3 signature、4 MiB cap、safe filename、直接 VM 強制再驗，僅同選定 provider/account/credential/model/group/endpoint/operation 已確認的 AUDIO_INPUT 可發。歷史含音訊時也必須重新驗能力；撤銷後 HTTP 前拒發，new conversation 不重播；實際 Room→VM→Engine→HTTP 與 Compose/裝置測試已隨 PR #18/main 同 SHA CI 驗收。
- **PLAY-004 整體仍 PARTIAL**：Audio Input 之外仍缺 Audio Output／轉寫結果、Tool calls/工具結果 UI、Image Generation/Edit 結果展示。之後順序依單一 slice 實際驗收；再進入 P8/P9/P10。
- PK27／MyModels 問題 [PR #15](https://github.com/Tsenjii/ModelAtlas-2/pull/15) 已在 `3925ff9d` [main CI 38392145254](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38392145254) 同 SHA build/device 全綠；本輪後續 main 綠燈亦涵蓋原回歸。
- Public repo 不帶 private repo Git history／任何 raw key/PAT；old private CI 不得當作 public main 的驗收。

## 下一片（唯一生產功能入口）
- **PLAY-004：圖片 Vision [PR #8](https://github.com/Tsenjii/ModelAtlas-2/pull/8)、安全邊界 [PR #9](https://github.com/Tsenjii/ModelAtlas-2/pull/9)、多輪歷史 [PR #13](https://github.com/Tsenjii/ModelAtlas-2/pull/13) 已合併**。修正針對整批待送歷史檢查 Vision 並保留同 scope 能力，若撤銷則 HTTP 前拒絕；主線 PK27 observer crash 已由 PR #15 修復且全綠；新對話 [#14](https://github.com/Tsenjii/ModelAtlas-2/issues/14) 已合併並 main 同 SHA 全綠；PDF Files 已通過 PR #17/main CI，Audio Input 已驗收；後續另片處理音訊輸出／工具／結果 UI，PLAY-004 整體仍 PARTIAL。
- 必須先讀現有 `PlaygroundScreen.kt`、`AtlasViewModel.kt`、`InferenceEngine`、`MultimodalBlocks.kt`、`OpenAiMediaTransports.kt`，確認已存在 transport / state 與實際欠缺。
- 依現有 `AtlasTheme.kt` UI 節奏做增量 Compose，更動不能破壞 text streaming、selected credential、provider scoped telemetry。
- 先設計最小可驗收的單一 multimodal flow；支援輸入/回應真實 wiring、permission 與 size/security guards、取消與錯誤語意、UI+core tests。
- 完整 same-SHA build+device-tests 全綠，獨立 diff review 與 traceability 更新後才能標 PLAY-004 ACCEPTED；此前維持 PARTIAL。

## 後續順序
1. 下一個產品 UI/Intelligence correctness 與已知缺口（包含 NINT-005B，待契約整合）。
2. P8 Global Crawler 的真實 candidate→evidence→verify，quota gate/Taiwan/AA Top4 嚴守。
3. P9 App Assistant 的安全結構化工具、真實 UI 管理能力與 confirmation/undo。
4. P10 版本化、可回退的 provider recipe / research reflection。
5. 所有 phase 逐片保持同 SHA full-green，勿同時把大階段混成一片。

## 未驗收與限制
- 未驗收：PLAY-004 full UI、P8/P9/P10 的全部 production flow。
- X-011 週期背景同步仍停用，因真實 sync runner 尚未接線；不得直接改 `isPeriodicSchedulingEnabled` 為 true。
- 舊 private source 的實際憑證曾入 git 歷史：舊 PAT 仍需由持有人撤銷；不可將舊 Git 歷史推進新 public。


## VPS 獨立維護基線（2026-10-14）

- 舊 PR #19 已關閉，VPS 在 `feature/vps-rebuild-20261014`／[PR #20](https://github.com/Tsenjii/ModelAtlas-2/pull/20) 維護；不合併私人 Git 歷史，也不改 Android main。
- 前一片已驗收 code SHA `9a46804cde0052ae2b65710fba252fe1ee6a2d5f`：[VPS 38508548084](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38508548084) Go race／HTTP／vet、AMD64、ARM64、Browser 四項 SUCCESS；[Android 38508548193](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38508548193) build、device-tests SUCCESS，六項為同一 SHA。PR 曾轉為 Ready。
- 該片 production 入口與修復以 `vps/PARITY.md` 為準；原本缺少的 onboarding、多 endpoint、Vision/PDF/Audio Input、New API／Dedicated／LMSpeed 已有實際 caller 與驗證，舊缺口快照不再代表最新 VPS 狀態。
- 未驗收：Android 整體 PLAY-004、完整 P8／P9／P10；未取得使用者主機存取，沒有真 VPS 遠端部署證據。

## VPS 最新續作（2026-10-14）

使用者本輪指示：檢查既有 VPS 全部程式，修問題並追上目前 Android main。維持 `feature/vps-rebuild-20261014`／PR #20，對照 Android `5009e0c8`；不跳去未合併的 audio-output draft。具體 production 入口、修復與測試及限制以 `vps/PARITY.md` 為新版 VPS 交接，取代上方舊 VPS「仍缺 Vision/PDF/Audio 等」快照。Android 本身的 PLAY-004／P8–P10 尚未驗收狀態不變。

本機 Go race/HTTP + vet 已通過，Browser 交給 `vps-browser` Actions。依最新 PR HEAD 同 SHA build/device＋VPS 四工作結果驗收；未全部成功不合併，任何 regression 直接修復。

Browser 初次回歸已定位並修復（new conversation 前 composer lock），`a938fef` 的 VPS 四 job 全綠。最後 code-audit 已把 Android root 已註冊 media transport、interrupted recovery、資料庫單寫者、exact stats／custom window、stale Undo guard 接線；Final gate 仍查 PR 最新 HEAD，完整結果由 PR body 保存。

## VPS 本次繼續：媒體 Playground 結果切片

2026-10-14 再確認 Android main 仍 `5009e0c8`；使用者指示「繼續」。只新增 VPS 媒體 Playground，不改 Android 已驗收狀態，不疊 P8/P9/P10。

- `ui_js_media.go`：依 immutable endpoint 的 operation 顯示圖片生成／圖片編輯／音訊轉寫；獨立輸入表單、預覽／下載、繁中錯誤、停止；切換 scope 清草稿／附件／結果／Blob。Chat 不接收媒體 operation。
- `media.go`：共用最多四 flight 與 explicit request-id cancel；多行 transcript；完整 PNG/JPEG/WebP 解碼前先查像素，結果全體 4 MiB／4 MiPixels、HTTP JSON 8 MiB；不回傳自訂 secret echo；無效內容不記 SUCCESS。
- 原始媒體／轉寫內容不進 DB／telemetry，結果在頁面記憶體；URL-only 由使用者明確開啟，不自動向外部圖片站發請求或轉送 Key。
- `media_test.go`／Browser production flow 驗證真 TLS caller、三操作、能力撤銷、multipart MIME、零 usage、429、壞／截斷圖片、custom secret、取消與 shared budget。Browser 驗窄屏／深淺色／200% 大字。
- 本片先維持 Draft；新的 HEAD 必須重新通過 Android 兩項與 VPS 四項同 SHA gate；最終 SHA、Actions run、結論由 PR #20 最新 body 保存，不挪用 `9a46804` 結果。
- 下一個缺口仍是音訊輸出與工具互動 UI；本片不宣稱全部 PLAY-004／V1 已完成。

本片首輪 Browser `da00c79`／VPS run `38523911620`：Go test、AMD64、ARM64 已 SUCCESS；Browser 定位動態端點 select 的 label 含 options，無法以精確名稱操作。修正 production `aria-label` 與繁中端點類型名稱，不降低 Browser 精確選擇斷言；新 HEAD 重新跑六項 gate。

第二輪 `d1e2a3b` 的 Browser 已通過新端點操作，定位測試的「加入 My Models」同名按鈕 strict ambiguity；Catalog modal action 改為 `#detail` 範圍內精確選取，保留 strict/exact 斷言，未修改產品預期。仍以新 HEAD 六項 CI gate 驗收。

媒體切片 `36751cf`／VPS run `38524721426` 的四項皆 SUCCESS，Browser 三操作與取消全過；視覺 QA 發現 target 下拉仍顯示內部 Key ID，改用同 provider/account scope 的帳戶與 Key 名稱。補上實際下載 PNG bytes／多行 UTF-8 transcript 的檔案回讀，CSS snapshot 關閉 transition 以取得真正的深淺色定態。新 HEAD 六項仍須重驗。

最後操作 review：媒體「清除結果」改為只釋放結果 Blob，不會一起刪除已輸入描述或本機附件；只有切換 scope 才清全部草稿。Browser 保留輸入的斷言加入同一流程，下載內容與既有三操作已在 `179389e` 的 VPS 四項綠燈驗證，新 HEAD 仍查完整六項 gate。

## VPS 媒體切片最新已驗收基線（2026-10-14）

- Production code SHA：`00f3dc205e4a59057edcf15e2de4a2135b49d4d0`。
- [VPS CI 38526114795](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38526114795)：Go race／HTTP／vet／runtime smoke、AMD64、ARM64、Browser 四項 SUCCESS。
- [Android CI 38526114819](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38526114819)：build、device-tests 兩項 SUCCESS，與上述 VPS 為同一 SHA。裝置共 964 tests、0 failed，5 個通知權限測試依 API 29 條件 skip；不是刪除測試。
- **VPS-MEDIA-UI／SAFE／CANCEL 切片 ACCEPTED**：實際 UI 三操作、PNG／UTF-8 下載回讀、scope reset、結果清除保留輸入、阻塞取消、四 flight、影像驗證／secret echo 已驗。
- Browser 最終 artifact `11875811832`／SHA256 `b65041539532db6018fbe94ae43f1d527731ef92f41948bd503aac247ea32c7e`；已檢查圖片、轉寫、375／1024px、深淺色及 200% 大字畫面。
- 本次末尾提交僅補驗收與交接文件；新 doc HEAD 的完整六項 CI 結果仍以 PR #20 最新 body／GitHub checks 為準，不把 code 基線誤寫成任意較新 HEAD 的結果。
- 下一個 VPS 功能切片：音訊輸出或 tools 互動 UI，依單片新增真 caller／UI／測試後驗收；Android 全體 PLAY-004、完整 P8/P9/P10 仍未 ACCEPTED。VPS 獨立分支維護，無使用者主機遠端部署證據。


## 2026-10-14 使用者最新續作（VPS 全部 V1）

最新明確指示覆蓋之前只追 Android 当前片的範圍：全部完成，可以超過 Android；輕量從單程序／依賴／有界執行處理，不能裁功能。維持 VPS 獨立 branch / PR #20，不直接更動 Android main。

完整差距及 caller／驗收清單：`vps/V1_COMPLETION.md`。進行中的整合片為 VPS Playground 完整 protocol / tool calls / audio output / embedding / rerank。本機 TLS / Go race 與 JS syntax 通過；PR 保持 Draft，最新候選的 Browser／雙架構／Android build+device 同 SHA gate 尚待執行。第一片全綠後繼續 real Research Runner / AA、完整 Assistant / Reflection，再做管理／通知／資源驗收。不要在第一片完成就結束本輪。


VPS Playground 完整片 `29eff56` 已取得 [VPS 38550451082](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38550451082) 四項與 [Android 38550450921](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38550450921) 兩項同 SHA SUCCESS。當前整合片改為 Research runner／sources／funnel／budget／resume／AA V2。code 與本機 race 已落地，新增 Browser 真流程；以新 SHA 六項 gate 為準。後續仍須完整 Assistant／Reflection／管理／資源驗收，不在 Research 片全綠就結束本輪。

研究片 `fa7c291`：[VPS 38558907867](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38558907867) 四項 SUCCESS；[Android 38558907976 attempt 2](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38558907976) build/device SUCCESS。首輪既有 Android rapidDoubleToggle 15s 逾時，同 SHA 保留斷言重跑通過。當前整合為完整 App Assistant；本機 race/vet/JS syntax 已過，新增真 Browser，仍待新 SHA 六項 CI。下一片 Reflection／宣告式 connector，其後完整管理與資源驗收。

Assistant code `69652c9` 同 SHA 全綠：VPS `38567589731` 四項＋Android `38567589525` build/device SUCCESS。當前 Reflection／宣告式資料擷取與 credential-version 保護已本機 race/vet/syntax/TLS 回歸，新增 Browser；待新 SHA 六項 gate。20:56 使用者另授權 Kilo 免 Key 實測，live conversation/native tools/App Assistant 真實流程已通過（6 HTTP／26.31s，僅合成一次性資料），證據與限制在 `vps/V1_COMPLETION.md`。完整管理／filter-ranking／notifications／backup／multi-origin／CUSTOM／native speech／資源最終驗收仍要接續，不在本片全綠就結束。

Reflection code `293bd516` 同 SHA 六項全綠（VPS 38572762140、Android 38572762176）；本輪為管理/filter-ranking／背景規則通知／portable backup v2／SQL 全量統計與輕量 runtime 候選。測試與正式 UI 已接線，新增 Browser、standalone RSS workload；待新 SHA 六項 gate。Main 遠端 2026-10-14 再驗仍為 `5009e0c8`，未改 Android source。multi-origin／CUSTOM／native Gemini speech／最終交付仍接續。

管理／資源 code `8f069625` 同 SHA 六項全綠（VPS 38582129810，Android 38582129791）。22:37 新需求 Kilo 真 Provider discovery＋全程式 RAM＋續 UI；Kilo 已執行真 App tools，但本機 public DNS unavailable，實驗輸出保留，改獨立 opt-in Actions Linux，不放寬 SSRF 或冒充已找到證據。後續同一 V1 目標不變：multi-origin／CUSTOM／Gemini native speech／媒體 RAM／完整 UI 回歸。

`b7ecdaa` 同 SHA 六项 SUCCESS（VPS 38584571842、Android 38584572181）。Kilo opt-in Actions 38584572569 真實 Provider research 33.54s 通過：2 leads/3 evidence，Groq/OpenRouter讀取成功、Cerebras307失敗如實記錄；沒有現成credentials借用/eligibility推定。PR已還原一般標題，之後不默認耗用Kilo。當前 origin/scoped auth/Group/PAT/connector/backup＋App public source/匿名 onboarding＋Provider UI收合候選，本機race/vet/syntax/TLS與新Browser驗證；待新SHA六項gate。最終custom/native speech/session settings/媒體RAM與交付接續，不在這個slice結束。


多網址／Group epoch／Agent public source 片 `d3da38c50a90976de5eac95c764b7cac66f80ddb` 同 SHA 六項 SUCCESS：VPS `38590860925` test `115945054314`、amd64 `115945054916`、browser `115945054937`、arm64 `115945055188`；Android `38590861082` build `115945620723`、device `115945553268`。現在整合剩餘 Playground：原生 Gemini 語音 CURRENT／LEGACY／雙人 metadata、宣告式 CUSTOM 對話、逐段 system/max_tokens/streaming 設定；共用 scope／工具／取消／telemetry，Assistant 固定 system 不受手動設定覆寫。新增 TLS／Browser／safe backup 與全服務 heavy media RAM profile；本片待新 SHA CI，不把前片或未執行的 footprint 當驗收。


## VPS 完整 V1 code 驗收（2026-10-15）

`ce2ca91a8ff21606bcc335c76d3e6dd47bc0ada8` 同 SHA 六項 SUCCESS：VPS `38612813568` test `116020220644`、amd64 `116020220452`、arm64 `116020220414`、browser `116020220318`；Android `38612813557` build `116020220795`、device `116020219982`。七個 Browser workflows 均成功。剩餘 native speech／CUSTOM／manual session settings、Catalog exact-response 與 security／reflection recovery 均已接正式 caller 並驗收。完整功能、實測 RAM/Kilo、限制與安裝見 `vps/DELIVERY.md`；這不改 Android 本身 PLAY-004／P8–P10 的状态。無獨立外部 reviewer／無 merge／無目標 VPS 部署證據。

Standalone：idle 26,714,112、chat 30,040,064、research 30,273,536、images 97,619,968、peak 162,525,184 bytes（155 MiB），binary 13,439,138；完整保留10萬samples，4 chats／65 Catalog／4 simultaneous 4MiPixels images／4 simultaneous 4MiB audio。Live Kilo `38612880443` 真成功 41.19s、6 inference HTTP＋7 public HTTP、3 candidates/4 evidence/3 SUCCESS tasks；Groq/OpenRouter200，Cerebras307 FAILED線索也保留，不當eligibility。

末尾交付提交含資料與文件、200% decorative mark overflow 修整及 screenshot transition 固定；必須再查此新 HEAD 的六項 gate，不以 code 基線代替文件 HEAD。


## VPS V1 後續完整體驗（2026-10-15）

使用者最新授權：持續完善所有功能／UI，自行找有用的擴充；RAM 優先 ≤512 MiB，≤1 GiB 可接受。保持單 Go／SQLite，不裁功能，也不改 Android。Main 再確認 `5009e0c8`／38408176782 build/device SUCCESS。第一片為對話管理／全文搜尋／置頂／可恢復封存／完整 JSON 和 Markdown 串流匯出／安全 Markdown、程式碼複製和表格；schema14 保留所有歷史，過期編輯拒絕覆寫。正式 UI、TLS caller、1100 則完整匯出與安全回歸已落地，等待新 HEAD 六項 gate。後續完善每次請求的用量／價格／額度收據與 Detail，完成新的 standalone RAM profile；不以 aa7f001 的綠燈冒充新候選。


對話擴充 code `d3e1c84140306e80644a9f9acbf6d159035cd6c9` 已同 SHA 六項 SUCCESS：VPS 38621092030 test116048490678、arm64116048491082、amd64116048491368、browser116048491381；Android38621091550 build116049206203/device116049206550。八個正式 Browser workflows 均過，standalone 原媒體 profile 峰值164,564,992 bytes（156.94MiB）。

請求續片已驗收：schema16 請求／訊息連結、immutable 當次 pricing/quota before/after、完整用量與 actual/nominal/effective estimate 分離、scoped request detail／50列 UI／完整 NDJSON 匯出、手動 stale-safe retry 不重複 user message；修正 Catalog nominal 0 不推成 Key FREE，人工明確確認及經測試／Review 的 per-key/group connector 才可提供 effective pricing；通知使用新 proof basis。Raw attachment 與 header-name secrets guards、長 JSON 不逐 Key 重建、export 每批讀取後釋放 DB、快照選定 rowset 避免新增／狀態變更造成重複漏列。完整匯出由 sequence index／rowid 前進，修復 11,000 訊息 OFFSET 重掃逾時，未放寬 timeout 或縮減工作量。

`57ef23e` 同 SHA 六項 SUCCESS：VPS `38631190515` test `116082947267`、browser `116082947583`、arm64 `116082947595`、amd64 `116082947725`；Android `38631190578` build `116083047901`、device `116083048486`。實測 31 MiB×4 完整 wire、11,000 訊息／100,008 請求完整匯出，加原四 chats／研究65 models／四圖片／四語音，服務峰值 426,102,784 bytes（406.36 MiB）、待機 26,591,232（25.36 MiB），192 MiB 僅 Go soft limit；OS 另計。已自行 diff／實際截圖 review；證據見 `vps/verification/20261015/`。Kilo 含新 engine 的真研究 `a39fa2a`／`38629907160`／`116078568504` 20.06s 成功，6 inference＋7 public HTTP、3 candidates／4 evidence，非 eligibility 證明。最後文件 HEAD 以 PR #20 最新六項 checks／body 驗收後恢復 Ready；本輪不擴新功能片，不合併／部署。
