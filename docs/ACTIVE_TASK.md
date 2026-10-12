# ModelAtlas Public — ACTIVE TASK

更新：2026-10-12，公開 repo `Tsenjii/ModelAtlas-2`。

## Live state
- **MODE：PLAY-004 VISION UI single-slice（未驗收 Draft）**；P8/P9/P10 尚未進入實作驗收。
- **FEATURE FREEZE：OFF**，最近正式主線 `b0286b01e7ef6f774db9d0e8165fba78a2d5a6fc` / Actions `38250063202` 兩個必要工作均 SUCCESS。
- **最近 verified full-green：** `b0286b0`，public [Android Build and Test run](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38250063202)，build SUCCESS、device-tests SUCCESS。
- 本 public repo 以新歷史提交；舊 private repo 的 full-green ledger 只作參考，不能當作 public CI 認證。
- 已合併：原始 Master Spec / Intelligence Contract / AtlasTheme UI 維護規範 PR #1；Playground scope PR #2；選定 credential 安全驗證 PR #5；群組歷史隔離 PR #7。

## 下一片（唯一生產功能入口）
- **PLAY-004：圖片 Vision 單切片 Draft PR #8**，包含 UI 選取/預覽/4 MiB 限制、signature 檢查、同 scope capability gating、ImageUrlPart 真正接上 VM→Engine→Transport 的請求、純 Kotlin + Compose + Android E2E 回歸測試。需以 PR #8 最新 SHA full CI 和 code review 決定是否合併；不等於 PLAY-004 全項 ACCEPTED。
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
