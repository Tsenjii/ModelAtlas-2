# ModelAtlas Public — ACTIVE TASK

更新：2026-10-13，公開 repo `Tsenjii/ModelAtlas-2`。

## Live state
- **MODE：PLAY-004 / Issue #14 新對話（候選實作；尚待 PR 同 SHA CI）**。
- **FEATURE FREEZE：OFF（main 已恢復全綠；每個新 slice 仍須獨立驗收）**。主線 [`3925ff9d`](https://github.com/Tsenjii/ModelAtlas-2/commit/3925ff9dced2c97382438d0b72b9cda2e15526fd) 為 [PR #15](https://github.com/Tsenjii/ModelAtlas-2/pull/15) squash merge；[main Actions 38392145254](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38392145254) **build SUCCESS + device-tests SUCCESS（同一 HEAD SHA）**。
- **PK27 / MyModels 維修已在 main 全綠**：PR #15 修 Compose-owned semantics waits 避免 SnapshotStateObserver 跨執行緒崩潰，並修 follower join race（flight attach 即發 Joined，測試等待 join 才釋放 leader）；先前紅燈 [38324128166](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38324128166)／[38324922179](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38324922179) 不可當成功。
- **目前唯一可開發片：** [Issue #14](https://github.com/Tsenjii/ModelAtlas-2/issues/14) 新對話：同 scope 清除 UI/wire history、草稿與 pending attachment，其他連線與所有 telemetry/usage/evidence 不可刪；streaming 時拒絕清除；Vision 撤銷後新對話可發純文字。此分支是候選，未獲 build+device-tests 同 SHA 成功前不得合併或 ACCEPTED。
- 本 public repo 以新歷史提交；舊 private repo 的 full-green ledger 只作參考，不能當作 public CI 認證。

## 下一片（唯一生產功能入口）
- **PLAY-004：圖片 Vision [PR #8](https://github.com/Tsenjii/ModelAtlas-2/pull/8)、安全邊界 [PR #9](https://github.com/Tsenjii/ModelAtlas-2/pull/9)、多輪歷史 [PR #13](https://github.com/Tsenjii/ModelAtlas-2/pull/13) 已合併**。修正針對整批待送歷史檢查 Vision 並保留同 scope 能力，若撤銷則 HTTP 前拒絕；主線 PK27 observer crash 已由 PR #15 修復且全綠；目前單一切片處理 [#14](https://github.com/Tsenjii/ModelAtlas-2/issues/14) 新對話，接著才是檔案／音訊／工具／結果 UI，PLAY-004 整體仍 PARTIAL。
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
