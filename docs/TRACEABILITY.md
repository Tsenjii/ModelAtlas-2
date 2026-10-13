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
| PLAY-004 / Vision chat follow-up | `AtlasViewModel.sendWithImage/send` 對全部對話歷史重算 Vision 需求；同 scope Vision 接續文字可正常傳輸，撤銷能力即拒絕；`PlaygroundVisionFlowDeviceTest`。 | [PR #13](https://github.com/Tsenjii/ModelAtlas-2/pull/13) 候選 `767fdbd` · [CI 38323252180](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38323252180) build+device SUCCESS；main `ec0464e` 的 [CI 38324128166](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38324128166) 因另一個 PK27 測試多執行緒崩潰為 RED | 候選修復驗收通過、已合併；主線全綠尚待修復回歸 |
| PK27 / Compose observer thread failure | `Pk27CheckinBindingTest` 改用 Compose Test rule 原生 `waitUntilAtLeastOneExists/waitUntilDoesNotExist`，避免 instrumentation thread 直接輪詢 semantics tree；保留全部 checkin A/B scope 斷言。 | 本輪主線紅燈修復 PR 候選；必須同 SHA build/device 全綠 | ACCEPTED：`3925ff9d` [main run 38392145254](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38392145254) build + device SUCCESS |

| MMR / refresh follower join race | `AtlasViewModel.requestMyModelRefresh` attach existing flight 即發 Joined；Android `MyModelsMetadataRetryStateTest` C1/C2/C3/D2/D4 確保 leader release 前已完成 join，同時保留零額外 fetch/扣額 assertion。 | [PR #15](https://github.com/Tsenjii/ModelAtlas-2/pull/15) 初輪 [38324922179](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38324922179) build 綠/device 紅；後續 [38386332526](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38386332526) 最新候選待驗 | ACCEPTED：`3925ff9d` [main run 38392145254](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38392145254) build + device SUCCESS |

| PLAY-004 / #14 new scoped conversation | `AtlasViewModel.startNewConversation`、`PlaygroundTab`／Composer 48dp action、`AtlasScreen` callback、Android Vision→text HTTP 回歸、Compose 草稿/Busy 驗證；僅清選定連線 history 與 UI，刷新草稿 generation，不碰 telemetry。 | 候選分支 `feature/play-004-new-conversation-14`；PR/build/device 尚待獨立驗收 | PENDING，PLAY-004 整體 PARTIAL |

## 公開版新增 requirement 記錄規則

每片新增一行包含：Requirement ID、相關 Spec、main SHA、實際變更檔、確認的 production caller、測試 case、同 SHA Actions run、review finding、結果（PARTIAL/ACCEPTED/OPEN）。
