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
| PLAY-004 / Vision first slice | `PlaygroundComposer` local content picker → `AtlasViewModel.sendWithImage` → `InferenceEngine` → `OpenAiTransport`；`VisionImagePayloadTest`、`PlaygroundVisionGateTest`、`PlaygroundVisionPickerUiTest`、`PlaygroundVisionFlowDeviceTest`。 | [Draft PR #8](https://github.com/Tsenjii/ModelAtlas-2/pull/8)；**必須驗證最新 SHA 的 build+device 成功再合併** | IN_REVIEW；PLAY-004 仍 PARTIAL（檔案/音訊/工具/生成結果 UI 未完成） |

## 公開版新增 requirement 記錄規則

每片新增一行包含：Requirement ID、相關 Spec、main SHA、實際變更檔、確認的 production caller、測試 case、同 SHA Actions run、review finding、結果（PARTIAL/ACCEPTED/OPEN）。
