# ModelAtlas Public — TRACEABILITY

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
