# ModelAtlas Public — ACTIVE TASK

更新：2026-10-12，公開 repo `Tsenjii/ModelAtlas-2`。

## Live state
- **MODE：CORRECTNESS / FULL-GREEN BASELINE ESTABLISHMENT → PLAY-004 UI**。
- **FEATURE FREEZE：OFF**，以公開 main `44c45ecb1842e1ec866e056e4b4447028411dd1e` / Actions `38238187606` 為第一個全綠基線。
- **最近 verified full-green：** `44c45ec`，public [Android Build and Test run](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38238187606)，build SUCCESS、device-tests SUCCESS。
- 本 public repo 以新歷史提交；舊 private repo 的 full-green ledger 只作參考，不能當作 public CI 認證。
- 本輪規格/工程文件 restoration branch 屬 docs-only，不能冒稱新 UI 已完成。

## 下一片（唯一生產功能入口）
- **PLAY-004：Playground 多模態 Composer/Results UI 的第一個真實端到端切片**。
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
