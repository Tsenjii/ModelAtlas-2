# ModelAtlas Public — ACTIVE TASK

更新：2026-10-13，公開 repo `Tsenjii/ModelAtlas-2`。

## Live state
- **MODE：CORRECTNESS / 主線紅燈解除作業（PK27 + MyModels refresh race）**；P8/P9/P10 尚未完成正式驗收。
- **FEATURE FREEZE：ON（red-head lock）**。最新主線 `ec0464e5868aaddf20667cbd1c86bf8993abfc3f` 已合併 PR #13，但 [main Actions 38324128166](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38324128166) **build SUCCESS、device-tests FAILURE**，屬 PK-27 Compose multithreaded SnapshotStateObserver crash，整套測試僅跑約 60 項就中止。只允許修回歸並重新驗證，不開新產品功能。
- **PR #15 本輪修復：** [Actions 38324922179](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38324922179) build SUCCESS / device-tests FAILED，失敗為 `MyModelsMetadataRetryStateTest.c3aJoinFailureLeavesBudgetOpen`：follower 在 leader 退出後才競爭進入，故誤執行不應出現的第二次 fetch。已在 [4ec80d3](https://github.com/Tsenjii/ModelAtlas-2/commit/4ec80d3602ab92d1df8b3738941df9ba0ac6ec98) 改成 attach flight 即發 Joined 事件，並以 [bfefbf7](https://github.com/Tsenjii/ModelAtlas-2/commit/bfefbf703bbafa8b832c83d941abe5eab3e147c0) 修改 C1/C2/C3/D2/D4 回歸測試，等 join 完成才 release leader。最新 [Actions 38386332526](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38386332526) **尚待 build + emulator 真實驗收**，絕不提前標成功。
- **最近 verified main full-green：** `f32522c`，[main CI](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38273286105) build SUCCESS + device-tests SUCCESS。PR #13 候選 `767fdbd` [CI 38323252180](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38323252180) build + device SUCCESS；其 squash 後主線遇到上述不同測試的 Compose 崩潰，**不可冒稱 main 已 full-green**。
- 本 public repo 以新歷史提交；舊 private repo 的 full-green ledger 只作參考，不能當作 public CI 認證。
- 已合併：原始 Master Spec / Intelligence Contract / AtlasTheme UI 維護規範 PR #1；Playground scope PR #2；選定 credential 安全驗證 PR #5；群組歷史隔離 PR #7。

## 下一片（唯一生產功能入口）
- **PLAY-004：圖片 Vision [PR #8](https://github.com/Tsenjii/ModelAtlas-2/pull/8)、安全邊界 [PR #9](https://github.com/Tsenjii/ModelAtlas-2/pull/9)、多輪歷史 [PR #13](https://github.com/Tsenjii/ModelAtlas-2/pull/13) 已合併**。修正針對整批待送歷史檢查 Vision 並保留同 scope 能力，若撤銷則 HTTP 前拒絕；但新 main Android suite 的 PK-27 observer crash 尚待本輪修復。接著才處理 [#14](https://github.com/Tsenjii/ModelAtlas-2/issues/14) 新對話，及檔案／音訊／工具／結果 UI，PLAY-004 整體仍 PARTIAL。
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
