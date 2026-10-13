# ModelAtlas Public — ACTIVE TASK

更新：2026-10-13，公開 repo `Tsenjii/ModelAtlas-2`。

## Live state
- **MODE：PLAY-004 / Files（PDF）第一個 production attachment 切片**。此分支為候選；需 PR 同 SHA build + device SUCCESS、合併後 main 重跑全綠才算完成。
- **main verified full-green：** [`069b541a`](https://github.com/Tsenjii/ModelAtlas-2/commit/069b541a486e032cf19431a54ec668652a7142cc) 是 [PR #16](https://github.com/Tsenjii/ModelAtlas-2/pull/16) squash merge；[Actions 38397137563](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38397137563) **build SUCCESS + device-tests SUCCESS（同 SHA）**。FEATURE FREEZE OFF；若後續任何 main HEAD 變紅，立即重開 red-head lock。
- **Issue #14 新對話已 merge 並經 main full-green：** `AtlasViewModel.startNewConversation` 只清當前選定連線的 UI/wire history；串流時拒絕，保留 samples/telemetry；UI 草稿與 picker late-result 按 reset generation 隔離；Android Room→VM→HTTP + Compose 已測。跨連線 A/B 的新增精確證明可持續強化。
- **本輪單一 slice：** 真正接上 `FileRefPart` PDF（用本機 content URI、4 MiB 上限、PDF signature/mime/filename gate、精確 FILES 能力、無額外 credentials；未知能力 fail-closed）。保留 image/text/history/busy/cancel/telemetry 路徑，測試不可因 PDF 加入而降低。整體 PLAY-004 仍 **PARTIAL**，尚缺音訊／工具／生成結果 UI。
- **上次 correctness 修復：** [PR #15](https://github.com/Tsenjii/ModelAtlas-2/pull/15) `3925ff9d`，[Actions 38392145254](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38392145254) build/device 同 SHA full-green；PK27 observer / MyModels join race 已修。歷史失敗 [38324922179](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38324922179) 不視為成功。
- Public repo 不帶 private repo Git history / sensitive token；舊 private CI 非新主線驗收證據。

## 下一片（唯一生產功能入口）
- **PLAY-004：圖片 Vision [PR #8](https://github.com/Tsenjii/ModelAtlas-2/pull/8)、安全邊界 [PR #9](https://github.com/Tsenjii/ModelAtlas-2/pull/9)、多輪歷史 [PR #13](https://github.com/Tsenjii/ModelAtlas-2/pull/13) 已合併**。修正針對整批待送歷史檢查 Vision 並保留同 scope 能力，若撤銷則 HTTP 前拒絕；主線 PK27 observer crash 已由 PR #15 修復且全綠；新對話 [#14](https://github.com/Tsenjii/ModelAtlas-2/issues/14) 已合併並 main 同 SHA 全綠；目前處理 PDF Files 真實接線，後續才是音訊／工具／結果 UI，PLAY-004 整體仍 PARTIAL。
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
