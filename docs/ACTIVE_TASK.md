# ModelAtlas Public — ACTIVE TASK

更新：2026-10-12，公開 repo `Tsenjii/ModelAtlas-2`。

## Live state
- **MODE：PLAY-004 Vision 子功能已合併，下一片為本機圖片輸入 ViewModel 邊界防護**；P8/P9/P10 尚未完成正式驗收。
- **FEATURE FREEZE：待最新主線驗證**。主線 `cd313947f5ef4750efaa5e681454752ec585c281` 為 PR #8 的 squash merge，完整主線 [Actions 38269841531](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38269841531) 需確認 build + device-tests 同 SHA 成功後才能解鎖新 phase。
- **最近已確認的功能 PR 全綠：** `6a4f173`，[PR #8 CI](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38261796603) build SUCCESS + device-tests SUCCESS；正式主線全綠須以新 main SHA 的獨立 CI 再驗證。
- 本 public repo 以新歷史提交；舊 private repo 的 full-green ledger 只作參考，不能當作 public CI 認證。
- 已合併：原始 Master Spec / Intelligence Contract / AtlasTheme UI 維護規範 PR #1；Playground scope PR #2；選定 credential 安全驗證 PR #5；群組歷史隔離 PR #7。

## 下一片（唯一生產功能入口）
- **PLAY-004：圖片 Vision 第一切片 [PR #8](https://github.com/Tsenjii/ModelAtlas-2/pull/8) 已合併**，包含 UI 選取/預覽/4 MiB 限制、signature 檢查、scope capability gating、VM→Engine→Transport 真實請求及單元／裝置測試。後續針對直接呼叫 VM 時也必須再次驗證本機圖片 URI／大小，避免繞開 Composer guard。Playground 的檔案／音訊／工具／圖片生成結果 UI 仍 PARTIAL。
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
