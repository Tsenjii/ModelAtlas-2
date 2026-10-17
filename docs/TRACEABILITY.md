# ModelAtlas Public — TRACEABILITY

## Mac 原生入口（2026-10-17 候選，獨立於 Android／VPS 驗收）

| ID | 正式 caller／測試 | 驗收條件 |
|---|---|---|
| MAC-NATIVE-001 | SwiftUI `ModelAtlasApp` → `RootView`／Playground／Providers／Models／Settings／Research／Assistant → `AtlasStore` → `AtlasClient` → bundled production engine；原生視窗smoke與十一實圖 | Universal arm64／x86_64，macOS12部署目標；同一artifact在兩種Mac架構執行，不用WebView／mock transport，不能以Linux交叉編譯稱Mac執行 |
| MAC-UI-002 | `ModelPickerView`／⌘K／緊湊三工作區 → `ModelSearchEntry`／`AtlasStore.chooseModel` → 原 full-scope API；真單元＋native store caller／十一實圖 | 搜尋全部詞、全半形／大小寫／別名；AppKit ↑↓／Enter／Esc、完整 scope cursor、空結果拒選及 marked-text guard（真 field-editor key events＋單元測試）；同 model/key 跨帳戶／provider 不合併；未送草稿／附件保留，舊對話草稿恢復；非法未加入 scope 拒選。雙架構同 artifact／全部九項同 SHA gate 與 22 PNG review 前 PENDING，詳 `macos/UI_REVIEW.md` |
| MAC-CHAT-003 | `ChatTranscriptView`／`ChatScrollProbe` → `ChatReadingState`；`ChatMarkdown` → `MessageMarkup`；file picker／`ComposerFileDrop` → `AtlasStore.addAttachments` → `LocalAttachment`；訊息 sample_id → `RequestReceiptView` → `/telemetry/detail`；核心回歸＋正式TLS串流／真NSScrollView／附件批次／same-scope收據／18 PNG | 本輪候選 PENDING；新 HEAD 同 SHA 九項 CI與雙架構36圖 review 前不得 ACCEPTED；保留舊11圖、全部scope／keyboard／cancel／lifetime／Keychain斷言，詳 `macos/CHAT_EXPERIENCE.md` |
| MAC-LIFETIME-001 | `EngineController` → `desktop` config → OS動態loopback listener／0600 readiness／stdin EOF → HTTP／worker／SQLite收尾 | 真Go程序測試＋兩Mac程序restart／writer拒絕；App無常駐daemon，不繼承PORT對外開埠；保持原平台／本機模式認證 |
| MAC-SECRET-SCOPE-001 | `KeychainVault` → Security.framework／SecRandom、完整`ModelScope`、禁redirect/cookie/cache/proxy的native client | 真Keychain roundtrip／missing-vault拒絕重生、A/B同KeyID隔離、SSE與實際cancel／history／vault reopen、Unknown不當0／Free、預設背景通知OFF |

候選須同SHA九項CI（Mac build／arm64 test／Intel test＋原VPS四項＋Android兩項），實際PNG人工review後才驗收；exact SHA／run／artifact由Mac PR body保存。Apple Developer ID／公證、macOS12實機與部分進階專用表單不在本片已驗範圍，詳`macos/README.md`。

> 此檔是公開 repo 新起點的粗粒度驗收銜接；**不是**原 private repo 85 項逐項驗收 ledger 的完整轉儲。原始完整 ledger 保留於 private repo。後續 public slices 的 acceptance 逐項追加，未經新 repo 完整 CI 不得標 accepted。

| Phase | 來源 Private Ledger（僅歷史參考） | 公開版驗收 |
| --- | --- | --- |
| P1 Foundation | 7 / 7 ACCEPTED | Migrated baseline full-green `44c45ec` |
| P2 Playground | 4 / 5 ACCEPTED；PLAY-004 PARTIAL | PLAY-004 **OPEN** |
| P3 Providers | 10 / 10 ACCEPTED | Migrated baseline full-green `44c45ec` |
| P4 New API | 10 / 10 ACCEPTED | Migrated baseline full-green `44c45ec` |
| P5 Dedicated Adapters | 7 / 7 ACCEPTED | Migrated baseline full-green `44c45ec` |
| P6 Models | 9 / 9 ACCEPTED | Migrated baseline full-green `44c45ec` |
| P7 LMSpeed | 5 / 5 ACCEPTED | Migrated baseline full-green `44c45ec` |
| P8 Global Crawler | 14 entries未標 ACCEPTED | NOT ACCEPTED |
| P9 App Assistant | 10 entries未標 ACCEPTED | NOT ACCEPTED |
| P10 Self-Improvement | 8 entries未標 ACCEPTED | NOT ACCEPTED |

## Verified same-SHA run

- Public initial snapshot: `44c45ecb1842e1ec866e056e4b4447028411dd1e`.
- Actions [run 38238187606](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38238187606)：build SUCCESS；device-tests SUCCESS。
- 功能一律以 production caller + executable tests + same-SHA complete green + code review 才能承認 ACCEPTED。

## 已驗收的公開版 correctness repair

| Requirement | Production caller / 修復 | 主線 SHA / 完整 CI | 結果 |
| --- | --- | --- | --- |
| SEC-001 / selected auth | `AtlasViewModel.sendWithImage/send` → `loadProviderModelAuth`；ACCOUNT PAT/其他帳號 ref 在 HTTP 前拒發；`AtlasAnonymousTargetTest`。 | `8f8fba6` · [Run 38246838016](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38246838016) build+device success | ACCEPTED（權限邊界） |
| PLAY-002 / selected group | Playground 真實 `InferenceTarget` 帶 credential/group/canonical/endpoint/operation，`PlaygroundInferenceTargetTest`、`AtlasAnonymousTargetTest`。 | `f0e7bc6` · [Run 38242610860](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38242610860) build+device success | ACCEPTED（scope mapping） |
| PLAY-002 / chat scope isolation | `AtlasViewModel.applySelectedContext` 清 conversation + cancel in-flight，`AtlasAnonymousTargetTest` group A→B 正反流程。 | `b0286b0` · [Run 38250063202](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38250063202) build+device success | ACCEPTED（聊天隔離） |
| PLAY-004 / Vision first slice | `PlaygroundComposer` local content picker → `AtlasViewModel.sendWithImage` → `InferenceEngine` → `OpenAiTransport`；`VisionImagePayloadTest`、`PlaygroundVisionGateTest`、`PlaygroundVisionPickerUiTest`、`PlaygroundVisionFlowDeviceTest`。 | [PR #8](https://github.com/Tsenjii/ModelAtlas-2/pull/8) 已合併 `cd31394`；[候選 SHA 6a4f173 CI](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38261796603) build + device 成功；[主線 CI 38269841531](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38269841531) build + device SUCCESS | Vision UI 已合併；整體 PLAY-004 PARTIAL（檔案／音訊／工具／結果 UI 未完成） |

| PLAY-004 / Vision local payload guard | `VisionImagePayload.isValidForSend` 與 VM 前置拒絕非法圖片／遠端 URL；`VisionImagePayloadTest`、`PlaygroundVisionFlowDeviceTest`。 | `f32522c` · [Run 38273286105](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38273286105) build+device success | ACCEPTED（邊界防護）；PLAY-004 整體仍 PARTIAL |
| PLAY-004 / Vision chat follow-up | `AtlasViewModel.sendWithImage/send` 對全部對話歷史重算 Vision 需求；同 scope Vision 接續文字可正常傳輸，撤銷能力即拒絕；`PlaygroundVisionFlowDeviceTest`。 | [PR #13](https://github.com/Tsenjii/ModelAtlas-2/pull/13) 候選 `767fdbd` · [CI 38323252180](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38323252180) build+device SUCCESS；main `ec0464e` 的 [CI 38324128166](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38324128166) 因另一個 PK27 測試多執行緒崩潰為 RED | 主線已由 PR #15 修復並在 `3925ff9d`／[38392145254](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38392145254) 與後續 `069b541a`／[38397137563](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38397137563) build/device 全綠 |
| PK27 / Compose observer thread failure | `Pk27CheckinBindingTest` 改用 Compose Test rule 原生 `waitUntilAtLeastOneExists/waitUntilDoesNotExist`，避免 instrumentation thread 直接輪詢 semantics tree；保留全部 checkin A/B scope 斷言。 | 本輪主線紅燈修復 PR 候選；必須同 SHA build/device 全綠 | ACCEPTED：`3925ff9d` [main run 38392145254](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38392145254) build + device SUCCESS |

| MMR / refresh follower join race | `AtlasViewModel.requestMyModelRefresh` attach existing flight 即發 Joined；Android `MyModelsMetadataRetryStateTest` C1/C2/C3/D2/D4 確保 leader release 前已完成 join，同時保留零額外 fetch/扣額 assertion。 | [PR #15](https://github.com/Tsenjii/ModelAtlas-2/pull/15) 初輪 [38324922179](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38324922179) build 綠/device 紅；後續 [38386332526](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38386332526) 最新候選待驗 | ACCEPTED：`3925ff9d` [main run 38392145254](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38392145254) build + device SUCCESS |

| PLAY-004 / #14 new scoped conversation | `AtlasViewModel.startNewConversation`、`PlaygroundTab`／Composer 48dp action、`AtlasScreen` callback、Android Vision→text HTTP 回歸、Compose 草稿/Busy 驗證；僅清選定連線 history 與 UI，刷新草稿 generation，不碰 telemetry。 | [PR #16](https://github.com/Tsenjii/ModelAtlas-2/pull/16) `495cf315` [PR CI 38394507003](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38394507003) build/device SUCCESS；main `069b541a` [CI 38397137563](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38397137563) build/device SUCCESS | ACCEPTED（新對話切片）；PLAY-004 整體 PARTIAL |

| PLAY-004 / Files PDF local first slice | `LocalPdfPayload.prepare/isValidForSend`（4 MiB、PDF magic、安全檔名、無 remote fileId）；`PlaygroundComposer` content picker／48dp PDF、`AtlasViewModel.sendWithFile` exact FILES gate → `InferenceEngine` → OpenAI wire，保留 scope & telemetry；`LocalPdfPayloadTest`／`PlaygroundVisionGateTest`／`PlaygroundVisionPickerUiTest`／`PlaygroundVisionFlowDeviceTest.localPdfRoomVmEngineHttpAndRevokedFileCapability`。 | [PR #17](https://github.com/Tsenjii/ModelAtlas-2/pull/17) `f8984e1` [PR CI 38401105133](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38401105133) build/device SUCCESS；main `d7dd460a` [CI 38403442111](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38403442111) build/device SUCCESS | ACCEPTED（PDF 第一切片）；PLAY-004 整體 PARTIAL |

| PLAY-004 / Audio Input local WAV/MP3 | `LocalAudioPayload.prepare/isValidForSend`（WAV/MP3 header、4 MiB cap、Base64＋路徑/filename guard）；`PlaygroundComposer` Android GetContent audio/*（不用 RECORD_AUDIO）＋窄屏自適應 media row、可取消/失敗狀態；`AtlasScreen` exact AUDIO_INPUT，`AtlasViewModel.sendWithAudio` 保留歷史音訊能力 gate／scope auth，透過正式 `InferenceEngine`/OpenAiTransport；`LocalAudioPayloadTest`／`PlaygroundVisionGateTest`／`PlaygroundVisionPickerUiTest`／`PlaygroundVisionFlowDeviceTest.localAudioRoomVmEngineHttpAndRevokedAudioCapability`。 | 分支 `feature/play-004-audio-input-20261013`，PR/head build/device 與 merge 後 main CI 尚未驗收 | PENDING；Audio Output、Tool UI、Generated Result UI 仍開放 |

## 公開版新增 requirement 記錄規則

每片新增一行包含：Requirement ID、相關 Spec、main SHA、實際變更檔、確認的 production caller、測試 case、同 SHA Actions run、review finding、結果（PARTIAL/ACCEPTED/OPEN）。


## VPS 重建版 2026-10-14（**不得與 Android 已驗收 P1–P7 混算**）

- 舊 MVP [PR #19](https://github.com/Tsenjii/ModelAtlas-2/pull/19) 已關閉且工作分支重置（沒有合併）；全新 [PR #20](https://github.com/Tsenjii/ModelAtlas-2/pull/20) 從已驗證的 main `5009e0c8a733f1863adcf9a5b8ef8c450da76d3d` 開始。
- `vps/store.go`、`schema.go`：Go/SQLite WAL、FK、durable migration schema；`server_test.go` SQLite reopen persistence。
- `vps/server.go`、`catalog.go`：auth/loopback/AES-GCM vault、Provider/Account/Credential Scope、Catalog != My Models、真 provider models sync；`server_test.go` auth/secret isolation/scope test。
- `vps/playground.go`：真實上游 HTTP chat SSE streaming、history、telemetry、TTFB/TTFT、user cancellation/parse failure classification、per-session in-flight race lock；`integration_test.go` 實際 TLS HTTP protocol fixture，非假的 production caller。
- `vps/research.go`、`research_gate.go`：candidate/evidence/run 與真 provider catalog probe；Unknown ≠ Free，實際 quota 類 evidence 不足不宣稱 main eligible；AA ingest / automatic crawler **NOT ACCEPTED**。
- `vps/assistant.go`：可逆的 My Models explicit preview/confirm/undo；完整 LLM Assistant **NOT ACCEPTED**。
- `vps/recipe.go`：allowlisted recipe version / explicit rollback + action ledger；自主 Reflection **NOT ACCEPTED**。
- `vps/ui.go`、`ui_css.go`、`ui_browser.go`、`ui_js_views.go`、`ui_js_chat.go`：teal 繁中輕量 Web UI，三分頁、深淺色、窄屏、48px、安全文字渲染。
- `vps/deploy/`：systemd unit + installer，未具使用者 VPS 遠端存取，不標「已部署」。
- 中間候選 `9b55edb41a84ac0ae5b120b2b0e8c20a06ed20eb` [CI 38444562106](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38444562106)：**vps-test + vps-linux-amd64 + vps-linux-arm64 success**；新回歸 commits 必須由新 SHA 同等測試並另外驗 Android build/device。
- **整體 VPS Android parity = PARTIAL**。VPS 分支不可因已有 schema/mock/form 就當成 Android 功能全追平，PR 先維持 Draft，只有同 SHA 雙平台全綠且 product slice acceptance 後才可合併。

| VPS-NEWAPI-001 | `vps/newapi.go` version-gated rc.25 read-only HTTP with separate account credential and inference credential, allowlisted persisted facts; `vps/newapi_test.go` TLS integration | CANDIDATE; await exact HEAD CI, further New API coverage pending |

## VPS 續作 2026-10-14

| Requirement | Production caller / tests | 狀態 |
|---|---|---|
| VPS-PARITY-001 | `vps/playground.go`、`attachments.go` → `/infer`；`parity_test.go` 的 media-history/cancel/104-message/zero-usage tests | IMPLEMENTED；latest PR HEAD CI gate |
| VPS-PARITY-002 | `onboarding.go`、`credentials.go`、`config.go` → Preview/Confirm/Batch/custom-auth/rollback；TLS / SQLite tests | IMPLEMENTED；latest PR HEAD CI gate |
| VPS-PARITY-003 | `newapi.go` → rc.25 exact pricing + group + perf；nil-map regression / PAT role tests | IMPLEMENTED；latest PR HEAD CI gate |
| VPS-PARITY-004 | `adapters.go`、`intelligence.go`、`lmspeed.go` → durable enrichment / scoped sources；real TLS caller / budget tests | IMPLEMENTED；latest PR HEAD CI gate |
| VPS-PARITY-005 | `manage.go`、`backup.go` → check-in / settings / safe export + atomic disabled-ref restore | IMPLEMENTED；latest PR HEAD CI gate |
| VPS-PARITY-006 | `ui*.go` → real Browser API flow；`browser_test.go`、`e2e/browser.cjs` → onboarding/chat/lifecycle/narrow/dark | CANDIDATE；必須 `vps-browser` 成功，禁止將本機 Chrome 限制失敗說成已驗收 |

目前 Android 主線本身尚未 production ACCEPTED 的 PLAY-004 剩餘結果 UI / P8 / P9 / P10 不在這些 entry 內偽升格。詳情 `vps/PARITY.md`；同 SHA Actions evidence 由 PR #20 最新 body 保存。

VPS 本片回歸補充：Browser `38503882118` 發現 new-session/composer race，修復 `a938fef` 後 `38504248117` 的 Go test／amd64／arm64／browser 全 SUCCESS。其後源碼核對補上 Android 已註冊 media transport、RUNNING→INTERRUPTED、single writer、Unknown empty traffic／相同分位／custom window、last-good>200 failures、stale assistant undo；**最終 HEAD 仍必須重新取得雙平台同 SHA CI，不能沿用 `a938fef` 綠燈。**


## VPS 媒體結果 UI 續片（2026-10-14）

上一個 parity 基線 `9a46804` 已由 VPS run `38508548084` 四項與 Android run `38508548193` 兩項同 SHA 全綠；本片由使用者「繼續」授權，僅 VPS。

| Requirement | Production caller／回歸 | 狀態 |
|---|---|---|
| VPS-MEDIA-UI-001 | Models endpoint／能力確認 → `ui_js_media.go` → `/infer/media` → PNG/JPEG/WebP 預覽與下載、multipart edit、multiline transcript 下載；Browser 三操作真 TLS／375/1024／dark/light/200% | IMPLEMENTED；最新 HEAD 六項 gate 由 PR #20 body 保存 |
| VPS-MEDIA-SAFE-001 | `media.go` 完整 raster 驗證／先限像素與 byte、所有選定 secret echo、operation-file guard；`media_test.go` 壞／截斷／SVG／custom secret／revoked-cap tests | IMPLEMENTED；不借舊 SHA 綠燈 |
| VPS-MEDIA-CANCEL-001 | request-id → shared flight／cancel registry → upstream socket close → durable CANCELLED；真 TLS 阻塞／四 flight／Chat 共用／uptime 排除回歸 | IMPLEMENTED；不借舊 SHA 綠燈 |

原始媒體與轉寫結果僅在記憶體；URL-only 不自動抓取、不轉送 Key。Android 整體 PLAY-004 與 P8/P9/P10 acceptance 不變。下一片為音訊輸出或 tools，需單獨接 caller/UI 與新 SHA 完整驗證。


VPS-MEDIA-UI-001／SAFE-001／CANCEL-001 **ACCEPTED（僅 VPS 切片）**：code `00f3dc205e4a59057edcf15e2de4a2135b49d4d0`，[VPS run 38526114795](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38526114795) 四項 SUCCESS＋[Android run 38526114819](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38526114819) build/device SUCCESS。Media UI、實際下載 bytes、多行 transcript、input-preserving clear、strict modal／a11y selectors、narrow/dark/200%、selected-scope／cancel／flight budget 全部覆蓋。末尾 doc-only HEAD 仍查 PR #20 當前同 SHA checks，不能借 code 基線結果冒充它。


| VPS-PLAY-FULL-001 | `protocols.go` / `tools.go` / `audio.go` / `vectors.go` → existing `/infer` / `/infer/media` → 4 conversation protocols / tool-result continuation / speech / embedding / rerank；`protocols_test.go` 真 TLS / exact scope / zero usage / truncation / signature / secret guard，Browser 實際新入口 | IMPLEMENTED，待本候選同 SHA 六項 CI；不能借 Android 或前片驗收 |


VPS-PLAY-FULL-001 **同 SHA CI 已驗**：`29eff56`／VPS `38550451082` 四項＋Android `38550450921` 兩項 SUCCESS；TLS native/tool、多模態結果及真 Browser 入口已驗。

| VPS-RESEARCH-RUN-001 | `research_jobs.go` / `research_execute.go` / `research_runtime.go` / `public_fetch.go` → `/research/start,control,sources,funnel,review` → 真 worker / bounded requests / complete funnel / sources / evidence / manual review / scoped traffic reuse；`research_runtime_test.go` 真 TLS / budget / socket pause / durable restart / no implicit membership；`e2e/research.cjs` 正式 UI | IMPLEMENTED，待新 SHA CI |
| VPS-RESEARCH-AA-001 | `aa.go` / `integration_budget.go` → AA V2 free API、完整同版本分頁、dynamic top4／ordinary regression／expiry、fixed-window budget、last-good；AA TLS tests | IMPLEMENTED，待新 SHA CI；不硬編 family，不借舊榜排名 |

VPS-RESEARCH-RUN-001／AA-001 同 SHA CI 已驗：`fa7c291`／VPS `38558907867` 四項、Android `38558907976` attempt 2 兩項 SUCCESS；未聲稱 Android P8 完整驗收。

| VPS-ASSISTANT-FULL-001 | `/assistant/config,run,secrets,actions` → `assistant_runtime.go` / allowlisted tools / common engine / shared onboarding Review / stale-safe reversible metadata與憑證參照操作；`assistant_runtime_test.go` 真 TLS／多工具／vault boundary／確認與 Undo／不中斷重播／真 socket cancel，`e2e/assistant.cjs` 正式 UI | IMPLEMENTED，待新 SHA 六項 CI |

VPS-ASSISTANT-FULL-001 同 SHA CI 已驗：`69652c9`／VPS `38567589731` 四項、Android `38567589525` 兩項 SUCCESS；最新候選仍查新 SHA。

| VPS-REFLECTION-001 | `reflection.go` / `knowledge.go` → durable per-run report / source snapshots / bounded learned priorities / version activation / stale-safe Undo / exact alias rules / source-as-data；`reflection_test.go` / 真 Browser | IMPLEMENTED；待新 SHA 六項 CI |
| VPS-CONNECTOR-001 | `connectors.go` → safe GET / bounded JSON Pointer / role + exact model/group / draft / real test / approved activation / official-data source namespace / common Catalog & enrichment / failure history / Undo；TLS＋Browser | IMPLEMENTED；待新 SHA 六項 CI |
| VPS-CREDENTIAL-EPOCH-001 | `endpointAuth` 同一查詢取得 cipher/custom fields/version，shared inference/media telemetry；research reuse 限當前 credential version，rotation TLS 回歸 | IMPLEMENTED；待新 SHA CI |
| VPS-LIVE-KILO-001 | 明確 opt-in 的 anonymous real catalog + common engine + native tools + shared App Assistant action Review；合成資料 6 次 HTTP／26.31s 已通過 | LIVE WORKTREE VERIFIED；不以外部結果取代 deterministic 回歸或冒充 recurring-free／Taiwan／identity proof |

VPS-REFLECTION-001／CONNECTOR-001／CREDENTIAL-EPOCH-001 同 SHA CI 已驗：`293bd516`／VPS `38572762140` 四項、Android `38572762176` 兩項 SUCCESS。

| VPS-MANAGE-FULL-001 | `ui_js_models.go` + `offer_summary.go` + settings／control actions → 真 filter/ranking/notes/tags/Provider 編輯，scope/external unit guard；`management_test.go` 與 `e2e/management.cjs` | IMPLEMENTED；待新 SHA CI |
| VPS-BACKGROUND-001 | `background.go` → metadata queue／bounded period／全 OFF 規則／持久 transition events／本機 notifications caller；TLS queue 與 epoch/dedupe tests | IMPLEMENTED；待新 SHA CI |
| VPS-BACKUP-V2-001 | `backup.go` / `backup_extra.go` / export → safe v1/v2 definitions、完整 transaction／invalid rollback／停用 credentials & network／重新验证 imported rules；roundtrip 與 Browser 真下載 | IMPLEMENTED；待新 SHA CI |
| VPS-RESOURCE-001 | `performance_sql.go` 全量保留統計／bounded chart、SQLite cache/temp／Go soft memory／flight limits／paging／gzip，1000-history invariants＋`footprint_test.go` 100000 samples + standalone TLS inference real RSS | IMPLEMENTED；CI 實測待驗，不以軟限制冒充 hard RSS 保證 |

VPS-MANAGE-FULL-001／BACKGROUND-001／BACKUP-V2-001／RESOURCE-001 同 SHA CI 已驗：`8f069625`／VPS `38582129810` 四項＋Android `38582129791` 两項 SUCCESS。SSR 单一執行檔十萬歷史＋四推論 profile peak 29,134,848 bytes，媒體負載仍待最終量測。

| VPS-LIVE-RESEARCH-001 | opt-in `live_research_test.go` / isolated `vps-live.yml` → actual anonymous Kilo model→App tools→public official sources→durable worker/evidence，fresh catalog price/tools guard，10 model HTTP/12 source HTTP budgets，no signup/credentials/implicit My Models | CANDIDATE；本機模型工具已跑，但 direct DNS fail，Actions 待驗 |

VPS-LIVE-RESEARCH-001 真實 opt-in已驗：`b7ecdaa`／Actions `38584572569`／job `115923745767`，2 candidates/3 evidence/3 SUCCESS tasks，未宣稱自動通過eligibility；普通同SHA六項全綠。

| VPS-MULTI-ORIGIN-001 | `origins.go` / schema11 / shared credential auth / immutable endpoint bindings / selected PAT / connector snapshot / safe backup → sameProvider多URL、shared Review/Undo不搬Key；`origins_test.go` realTLS與`e2e/origins.cjs` | IMPLEMENTED；待新SHA CI |
| VPS-AGENT-SOURCE-001 | static `save_research_source` / anonymous `onboard_provider` → public definition/durable runner/shared Review，public DNS-pinned client、noRawSecrets/noImplicitMembers；TLS/public boundary/registry regression | IMPLEMENTED；待新SHA CI |
| VPS-GROUP-EPOCH-001 | certified explicit token group（origin＋currentmaterialversion＋freshness），matchingGroup／inference／offerguard、失敗來源留evidence/lead | IMPLEMENTED；待新SHA CI |


多網址／Group epoch／Agent public source 片 `d3da38c50a90976de5eac95c764b7cac66f80ddb` 同 SHA 六項 SUCCESS：VPS `38590860925` test `115945054314`、amd64 `115945054916`、browser `115945054937`、arm64 `115945055188`；Android `38590861082` build `115945620723`、device `115945553268`。現在整合剩餘 Playground：原生 Gemini 語音 CURRENT／LEGACY／雙人 metadata、宣告式 CUSTOM 對話、逐段 system/max_tokens/streaming 設定；共用 scope／工具／取消／telemetry，Assistant 固定 system 不受手動設定覆寫。新增 TLS／Browser／safe backup 與全服務 heavy media RAM profile；本片待新 SHA CI，不把前片或未執行的 footprint 當驗收。


## VPS 完整 V1 code 驗收（2026-10-15）

`ce2ca91a8ff21606bcc335c76d3e6dd47bc0ada8` 同 SHA 六項 SUCCESS：VPS `38612813568` test `116020220644`、amd64 `116020220452`、arm64 `116020220414`、browser `116020220318`；Android `38612813557` build `116020220795`、device `116020219982`。七個 Browser workflows 均成功。剩餘 native speech／CUSTOM／manual session settings、Catalog exact-response 與 security／reflection recovery 均已接正式 caller 並驗收。完整功能、實測 RAM/Kilo、限制與安裝見 `vps/DELIVERY.md`；這不改 Android 本身 PLAY-004／P8–P10 的状态。無獨立外部 reviewer／無 merge／無目標 VPS 部署證據。

Standalone：idle 26,714,112、chat 30,040,064、research 30,273,536、images 97,619,968、peak 162,525,184 bytes（155 MiB），binary 13,439,138；完整保留10萬samples，4 chats／65 Catalog／4 simultaneous 4MiPixels images／4 simultaneous 4MiB audio。Live Kilo `38612880443` 真成功 41.19s、6 inference HTTP＋7 public HTTP、3 candidates/4 evidence/3 SUCCESS tasks；Groq/OpenRouter200，Cerebras307 FAILED線索也保留，不當eligibility。

末尾交付提交含資料與文件、200% decorative mark overflow 修整及 screenshot transition 固定；必須再查此新 HEAD 的六項 gate，不以 code 基線代替文件 HEAD。


## VPS 對話／請求完整體驗驗收（2026-10-15）

以下僅屬 VPS；Android source 仍 `5009e0c8`，Android PLAY-004／P8–P10 的驗收狀態不變。最新 code **`57ef23e96c335413003b497b06cd99b7b8838b6e`** 同 SHA 六項 SUCCESS：[VPS 38631190515](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38631190515) test `116082947267`、browser `116082947583`、amd64 `116082947725`、arm64 `116082947595`；[Android 38631190578](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38631190578) build `116083047901`、device `116083048486`。

| Requirement | Production caller／測試 | Review 與結果 |
|---|---|---|
| VPS-CONVERSATION-001 | `conversations.go`／`history_export.go` → `/sessions,update,export` → `ui_js_conversations.go`；`conversations_test.go` 1,100 則完整歷史、revision／busy guard、archive／restore／secrets；正式 Browser 真搜尋／管理／下載 | ACCEPTED；只查手動對話、不自動加入 My Models、不刪資料；完整匯出採 sequence index／rowid cursor、每批釋放 DB，修復大量 OFFSET 重掃逾時 |
| VPS-MARKDOWN-001 | DOM-only renderer → chat／older／read-only；Browser 真 TLS Markdown、整段訊息與程式碼 clipboard、HTML／危險 URL／remote requests guards，375／1024／深淺／200% | ACCEPTED；解析有界，超限保留純文字，不使用 innerHTML／執行腳本／抓遠端圖 |
| VPS-REQUEST-RECEIPT-001 | common chat／Test／Compare／Assistant／media → `startRequestRecord`／`completeReceipt`／message links → `/telemetry/detail` 與 `ui_js_receipts.go`；真 TLS／Browser actual vs estimate、零／未知、rotation／before-after／cache | ACCEPTED；價格依當次 material epoch／origin／scope／時間凍結，站方實際／nominal／effective estimate 分開，缺值不杜撰，不額外發 billing HTTP |
| VPS-EFFECTIVE-PRICE-001 | `offer_summary.go`／`pricing.go`／`intelligence.go`／`connectors.go`／`background.go` → Models FREE／manual price／connector Review／notifications；TLS＋Browser zero 未確認／明確確認的 reactive regression | ACCEPTED；Catalog nominal zero 不轉 Key FREE；effective 只收同 Key／Group 的確認證據，connector 需 INFERENCE／真測試／Review；通知全 OFF 預設不變 |
| VPS-REQUEST-RETRY-001 | explicit `/infer retry_id` → latest failed same manual session／scope／lineage → shared engine；TLS 兩次失敗後成功、stale／success 零 HTTP；Browser 429→明確重試→成功／reload | ACCEPTED；只一次 user input、三個獨立 attempts、不可隱式 fallback；archived／busy／撤銷能力仍拒絕 |
| VPS-REQUEST-EXPORT-001 | `/telemetry` scoped filters／50-row UI → `/telemetry/export` full NDJSON；SQLite TEMP rowset snapshot／rowid pages；501+／concurrent mutation tests＋100,008 standalone export | ACCEPTED；固定選取集合、不漏／不重複、結尾件數完整；只清暫存索引，永久 raw history 不動；串流錯誤中止下載 |
| VPS-RESOURCE-EXPANDED-001 | `footprint_test.go` 真 stripped binary／PID RSS：100k samples、4 chats、4×31 MiB 完整 TLS context、11k 訊息／100,008 請求匯出、65 Catalog、4×4 MiPixels 圖片、4×4 MiB PCM→WAV | ACCEPTED；保持原 workload／timeout，峰值 426,102,784 bytes（406.36 MiB）≤512 MiB，待機 25.36 MiB；192 MiB 只是 soft limit，OS／target VPS 未量測 |

本輪自行 diff review 與實際截圖 review 已完成；沒有獨立外部 reviewer。九個 Browser workflows 全過，artifact `11925724015` digest `7067477b6d9489978eba1ded1d2cc25b1320f9980e8fcdd28032295a8a41a4ca`；expanded footprint artifact `11926546113` digest `050c01c61e50ed7b365d83f871551d72987719f018a7b505206b31dc36f4d933`。精簡數據／job／已檢視畫面索引在 `vps/verification/20261015/`。

含新收據 engine 的真匿名 Kilo Provider research：code `a39fa2a`、Actions `38629907160`／job `116078568504`，20.06s SUCCESS，6 Kilo HTTP＋7 public HTTP、3 candidates／4 evidence／3 SUCCESS tasks。Groq／OpenRouter 200，Cerebras 307 不跟隨並保留 FAILED；不是 recurring-free／Taiwan／strength／identity eligibility 證明。Marker 已關閉。最後文件 HEAD 的六項 CI 另依 PR #20 最新 checks／body，不把 code SHA 結果外推；無 merge／無使用者主機部署。


## VPS 串流／閱讀與快捷操作切片（2026-10-15）

基線 `5c616053496f37e1a0af44d3b9614bc6432c3800` 已同 SHA 六項全綠（VPS `38680781659`、Android `38680781661`）。本片獨立驗收，不借基線結果；最新 HEAD／run／job／Browser DOM 指標以 PR #20 checks／body 為準，同 SHA 六項全成功才 ACCEPTED。Android 僅含下述必要初始化修復，main／產品phase不變，無 merge／無目標主機部署。

| Requirement | 正式 caller／驗證 | 驗收條件／review |
|---|---|---|
| VPS-STREAM-DOM-001 | `sendChat`／`streamText` → common `/infer` SSE；原生 frame／timer batching、appendData、終止 flush／reader cleanup；`chat_experience_test.go`／`e2e/chat-experience.cjs` 3,000 真 TLS deltas | 第一個2,000 delta burst DOM writes <500、完整文字替換=0；clipboard／107則 JSON export 與完整原文相等；不裁字、不加入 framework、不增加模型 HTTP |
| VPS-CHAT-READING-001 | `bindChatExperience`／`chatChanged`／`olderMessages`／`loadChat` → 調整 scroll follow、明確回最新、ResizeObserver／keyboard／touch；真 Browser 上讀後再接1,000 deltas | 閱讀位置不變；初次／換對話到最新；未變 metadata refresh 保留原訊息 node／較早頁面；375／1024／深色／200% screenshot，隱藏頁面有 timer／最終 flush |
| VPS-CHAT-COMPOSER-001 | 真 start 後可草擬下一則；global shortcuts／IME／repeat／busy／modal guards、Escape → existing explicit cancel API／fetch abort | 生成中不重複送出、不切 scope；draft 完成後仍保留；IME／Shift+Enter／Ctrl／Meta 測試，modal Escape 不停模型，下一次 Escape 真關上游並保留 CANCELLED；共2次模型 HTTP |

設計參考與原生 API 官方連結在 `vps/CHAT_UX.md`；實作自行撰寫，無第三方 source 複製／新 production dependency。測試 fixture 只提供合成資料與分階段 barrier，全部 inference 經正式 Handler／engine／vault scope／SQLite；不是攔截假前端回應。服務 RAM 仍跑原有擴充 standalone profile，不把 DOM 指標當成 RSS 改善。


| ANDROID-VM-INIT-001 | `AtlasViewModel` observers 在全部 instance fields 完成後才啟動，兩個原 init body／相對順序完整保留；h5 四次 Main.immediate 建構、已存 membership／certified endpoint、exact operation／不charge／不新增inference或membership | 修復 `979a8a6`／Android `38685239940` device `116258469234` 的真 NPE；不加delay／drop emission／catch吞錯，h4 原斷言保留。新 HEAD 需完整同 SHA 六項 CI |


## VPS 工作區與頁面重整（2026-10-15）

基線 `5522c7f`／VPS `38686585333`＋Android `38686585247` 同 SHA 六項全綠。本片 production／review／畫面證據見 `vps/WORKSPACE_UI.md`，最新 HEAD 六項 gate 以 PR #20 checks／body 為準，不能借基線全綠。

| Requirement | 正式 caller／回歸 | 驗收條件 |
|---|---|---|
| VPS-WORKSPACE-UI-001 | `ui.go`／`ui_css.go`／`ui_js_workspace.go` → 三主分頁內的互斥 tabs、原生 icon／dialog／popover、清單與設定分類；`workspace_test.go`／`e2e/workspace.cjs`＋原十 Browser | IMPLEMENTED，待新 SHA 全六 CI／十一 Browser；375／1280／dark／200% 真截圖；arrow/Home/End、aria selected、native Esc、草稿與 session 保留 |
| VPS-COLLECTION-UX-001 | 真 My Models／Catalog／provider data → 名稱 index、圖示、同來源已知 metrics、快速 FREE／最愛、完整 filter reset、選取時批次工具列、Global 精確匹配分頁與真空狀態入口 | IMPLEMENTED；Unknown 不當零／Free，My Models 與 Catalog 不混；新 Browser 選取／清除／filter／source guard，原 compare／Review／confirm／Undo 全保留 |
| VPS-APPEARANCE-001 | `appearance` → 原有 teal tokens、系統／淺／深 theme，localStorage 僅非敏感外觀偏好，storage 不可用仍可切換 | IMPLEMENTED；瀏覽器 reload 保留 explicit theme／恢復 system，無 server setting／排程／通知變更，原 OFF defaults 與 safe backup 測試保留 |

不增加 production dependency／migration／模型 HTTP；新增 Browser 使用合成名稱與 samples，絕不冒充真 Provider 能力與服務績效。Android 僅沿用基線的 init-order 修復，本片不改 Android source／phase；未 merge／未部署。

| ANDROID-ROLLBACK-HARNESS-001 | `CredentialRollbackFlowTest` 真Main建構／清除／操作、state.first掛起等待取代busy idle loop、AtomicBoolean fixture flag、四輪enable/disable的Room／vault／blocked／兩個投影一致性、safe timeout階段診斷 | 修復本片gate遇到的 `0e7acf6`／Android38692621025 device116281827382 15秒timeout；不改production、不增加timeout／skip／重試，不刪T1–T5斷言；新HEAD完整六項待驗 |

| VPS-CHAT-SESSION-RESET-001 | `newConversation` 成功時同步清 UI／新標題／新段預設與閱讀狀態，`chatChanged(force)` 立即隱藏舊 jump；chat-experience 真保存舊system/777→立即new→空system/512/true、舊設定完整保留，原 immediate hidden 斷言不改 | 修復5613943／38694380170 的跨session舊閱讀控制瞬現；history DB不刪／不加模型HTTP；新HEAD同SHA六項gate |

Android rollback harness `5613943`／[38694380099](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38694380099) build `116287408298`＋完整device `116287407961` 同SHA SUCCESS；新UI HEAD仍需獨立驗收，不能以舊綠燈代替。


## VPS 詳情工作區與非同步操作（2026-10-15，候選）

以 `c609cf2` 同 SHA 六項全綠為起點；此表不改 Android phase 的驗收。

| ID | 正式 caller／行為 | 驗證與狀態 |
|---|---|---|
| VPS-DETAIL-NAV | `ui_js_detail_workspace.go`／`ui_js_details.go`，模型及 Provider 各四分區，真 aria tablist／左右／Home／End；Provider 僅列該站已加入模型，端點保存後即更新清單 | 第十二個 `TestBrowserDetailWorkspaceWorkflow`；待新 HEAD 六項 CI＋實圖 |
| VPS-DETAIL-READ | 明確 loading／error／retry；同模型時間窗只更新實測區，保留 metadata／notes／capability 草稿；每次讀取有本視窗及 request 序號 guard | 真 Handler／SQLite，首讀503後重試、阻塞1h後7d先返回、有效自訂範圍、無效範圍HTTP前拒絕 |
| VPS-DETAIL-SOURCE | nominal／effective 明確分開且精確字串；last-good／latest／Provider／Account／external／pools 來源獨立；My Uptime 樣本與視窗、p50/p90/p95／60s observed獨立，不假造Unknown | 三有效＋一取消樣本，7d66.7%、自訂50.0%、他模型無樣本；失敗不清有效價格，保留全部四紀錄 |
| VPS-DETAIL-ACTION | 同scope metadata／caps／Key啟停仍真API；晚完成 lifecycle、preview 或 Catalog add 不重開／關掉新的詳情；Key rotate 後刷新當前投影 | 真 mutation 先提交後阻塞回應，換模型後釋放；原十一 Browser assertion 全保留，只調整新分區入口；待同SHA全部綠燈 |

產品接線、截圖來源及限制見 `vps/DETAIL_UI.md`。Exact SHA／Actions runs／jobs／footprint 由 PR #20 最新 body 記錄；沒有最新六項 SUCCESS 不標 ACCEPTED。

## VPS Catalog 探索與加入（2026-10-15候選）

- Caller：`ui_js_catalog.go`由正式`refresh`／`bindCatalog`接線；Provider空態直達同Provider Catalog；manual form與Catalog共用安全Key／origin-bound endpoint選擇。
- 真實驗證：`catalog_experience_test.go`／`e2e/catalog-experience.cjs`，第十三Browser；108候選、100列分頁、metadata-only篩選、精確membership、缺Key／端點真CTA、503重試、真commit晚回應、手動B-origin與草稿、歷史不刪、零推論。
- 本機race／vet及同SHA六項CI、實圖驗收與RAM以本片PR #20證據為準；不把外部模型派工或舊基線當CI。外部DeepSeek匿名401、Muse官方OpenCode CLI403均無任務成果，沒有將其標為審查通過。

## VPS Provider 表單與保存回饋（2026-10-15 候選）

| ID | 正式 caller／驗證 | 驗收條件 |
|---|---|---|
| VPS-PROVIDER-DRAFT-001 | `ui_js_details.go` 原六表單 → 帳戶／Key／toggle／endpoint／rotate／config 真 API → 局部 projection 更新；第十四 Browser 保留六原始 form nodes／草稿／disclosure／選取 | 不重建 dialog；只清自己已保存的敏感欄位；新帳戶更新選項，其他草稿可編輯；舊十三 Browser 全斷言保留 |
| VPS-PROVIDER-FEEDBACK-001 | `ui_js_provider_forms.go` → inline status／alert、generic JSON error、pending controls／WeakSet；真503重試與SQLite commit後延遲 | 錯誤 viewport 可見；非法JSON零POST／無secret片段；重複requestSubmit零額外寫入；stale config不能重開Q |
| VPS-PROVIDER-KEY-SCOPE-001 | rotation selector → 穩定Provider×Account×Credential值＋完整帳戶／Key／Group label；A/B同Key ID fixture → 真vaultrotate | 只改B ciphertext與兩版本，A ciphertext／零版本不變；membership／telemetry完整、零Provider HTTP；375／1280／dark／200%幾何與實圖 |

基線03307f1六項已綠；本片仍以 PR #20 最新 HEAD 的完整六項／十四Browser／實圖與原expanded workload為準。無merge／目標VPS部署，產品語意及Android phase不變。

| VPS-SETTINGS-DRAFT-RACE-001 | `refresh`／`loadStatus` → 逐欄dirty guard；`settings-form` save以revision確認是否仍同一稿；workspace真Handler回應／settings commit後延遲 | 修復db21a33／38751952139的舊workspace UTC→Asia/Taipei競態，原斷言保留；固定barrier驗晚status／重開保留UTC，保存途中下一稿Asia/Tokyo仍留UI，SQLite僅UTC、一次POST、背景／通知仍OFF。新HEAD全部六項gate |

## VPS Git 平台部署相容性（2026-10-15 候選）

| ID | 正式 caller／驗證 | 驗收條件 |
|---|---|---|
| VPS-DEPLOY-001 | repository-root `deploy/build.sh` → pure-Go binary → `deploy/run.sh` exec → `runApplication`；`deployment_test.go` 實際執行兩命令 | Go 1.26+；平台 opt-in 才讀 PORT／public bind；一般模式保留 loopback，非法設定與 bind 失敗非零退出；SIGTERM 正常關閉 HTTP／worker／SQLite |
| VPS-DEPLOY-HTTPS-001 | `deploymentConfig.handler` 包住正式 `Server.Handler`；真 TLS reverse proxy 保留 Host／覆寫 X-Forwarded-Proto | Host／Origin／HTTPS 前置拒絕仍保留原管理認證；healthz 只曝既有健康資料；固定 master key、絕對 DB，真 API mutation 後兩程序 reopen／vault decrypt，無 secret log／GET |

新 HEAD 須完整同 SHA 六項 CI；具體證據由 PR #20 body 保存。Infrlo 控制台在此環境不可讀，官方公開 README 無 Go／ingress／persistent volume 契約；不將本機代理測試、512 MB／2 GB 宣傳數字當作目標平台驗收，不標已部署。



## MAC-USABILITY-004 — 2026-10-17

| Requirement | Production caller / tests | 狀態 |
| --- | --- | --- |
| MAC-USABILITY-004 / conversation search | `RootView` task(id: query/state) → `AtlasStore.searchSessions` debounce/cancellation → real `/sessions`；`NativeSmoke.verifySessionSearch` 無 Enter 即出結果、archive/clear、current scope/history/draft 不變 | CANDIDATE，新 HEAD 九項同 SHA + 原生42圖 |
| MAC-USABILITY-004 / model picker | `ModelPickerView` reset/count/eligible providers/duplicate name IDs + revalidate exact active scope；原鍵盤/IME與新增filtered/empty實圖 | CANDIDATE；原18圖保留，兩架構各21圖 |

基線 `a00b038` 九項與36圖 ACCEPTED；Android/VPS新UI已依使用者指示暫存，沒有整合。詳 `macos/USABILITY.md`，最終驗收由最新PR #21 body記錄。


## MAC-FEEDBACK-005 — 2026-10-17

| Requirement | Production caller / tests | 狀態 |
| --- | --- | --- |
| MAC-FEEDBACK-005 / settings | Chat menu → `AtlasStore.openChatSettings` → `ChatSettingsView/Controller` → 真sessions settings/update；`ChatSettingsDraft` / `ChatSettingsTests`；NativeSmoke真401/retry/invalid前後API相同/A與B隔離/下一稿與history保留 | CANDIDATE，新HEAD九項與50圖驗收 |
| MAC-FEEDBACK-005 / modal actions | `ModelAtlasApp` commands及composer以`hasPresentedSheet`停用會切換context／傳送的快捷鍵；Store原scope與engine不變 | 本片原生code review＋原全部keyboard/IME regression |

詳 `macos/SETTINGS_FEEDBACK.md`；0.1.3基線d719bb9已ACCEPTED，其他平台UI仍暫存，最終evidence由PR #21最新body保存。


## MAC-PARITY-006 — 2026-10-17

| 範圍 | 正式 caller → 驗證 | 驗收 |
|---|---|---|
| 媒體完整選項與結果 | MediaOptionsView／MediaDraft／AtlasStore.send → infer/media → 真TLS fixture；空文字音訊、current/legacy/雙人、speech speed/format/instructions、dimensions/top_n、圖片生成與multipart編輯、完整下載 | 新HEAD候選；原18核心＋11新核心與原25圖＋17新圖／架構，九項同SHA及84圖review後接受 |
| 管理與精確來源 | ModelFilters／CatalogDetail／原生ModelEditors／BatchImport／ProviderEditors → 既有正式API；NativeParity exact-account批次confirm/undo、ABA stale、原對話、ALL/archive/scope清單及匯出；原history/IME/keychain/lifetime全部保留 | 不把Catalog當My Models、Unknown當零、不同origin或account的同Key ID合併 |
| 研究／知識／外部服務 | ResearchRequest／ResearchDetails／KnowledgeView／VersionController／Services → 正式來源、候選／證據、判讀、預算／控制、versions、connectors、integrations API；NativeParity真擷取測試→啟用→intelligence caller→復原、證據知識版本／stale／復原、實際佇列pause/resume/cancel及無推論預算、加密Key及安全Assistant參照 | caller不是只讀JSON；高階知識／CUSTOM資料保留與VPS同範圍的JSON定義。驗收查PR #21最新exact SHA/run/artifact |

對照基線 Android `5009e0c8`、VPS `026f58ba`；不據此聲稱 Android 本身 P8–P10／PLAY-004 整體已驗收，不 merge、不啟用未經使用者選擇的背景或外部推論。歷史失敗與修復均保留於 `macos/FEATURE_PARITY.md`。


## MAC-DESKTOP-007 — 2026-10-18 候選

完整產品定位與原文Part I–IV對照見 `macos/MASTER_SCOPE.md`；MAC-PARITY-006的既有功能聯集驗收不代表整份Master Spec已完成。已確認原生缺口包含完整可讀quota pools／價格與效能history charts、Compare並列結果與部分管理摘要，保持PARTIAL。

`ComposerTextEditor`依AppKit排版30–144pt、原生捲動／clear shrink／IME／Undo；`AtlasStore.focusComposer`→⌘L、⌘O選檔、側欄toggle保留scope/draft/history；`ModelsView`顯示引擎已知price_state與cache另計、Provider依已知免費連線篩選。保留29核心／25原smoke／17parity圖，增3圖及真AppKit adaptive/focus/sidebar／exact-account effective-price guard，合計90圖。新HEAD九項同SHA／圖審前不ACCEPTED，不改Android P8–P10狀態。
