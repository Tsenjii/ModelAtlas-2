# ModelAtlas Public — ACTIVE TASK

更新：2026-10-14 02:33（Asia/Taipei），公開 repo `Tsenjii/ModelAtlas-2`。

## Live state
- **MODE：PLAY-004 / Audio Input 已驗收；下一個產品切片為 Audio Output（尚未驗收）**。main 同 SHA 全綠，feature freeze=OFF。不得將 response-side transport events 當作已接好的音訊播放 UI。
- **最新 VERIFIED main FULL GREEN：** `5009e0c8a733f1863adcf9a5b8ef8c450da76d3d`，由 [PR #18](https://github.com/Tsenjii/ModelAtlas-2/pull/18) WAV/MP3 Audio Input squash merge；[main CI 38408176782](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38408176782) **build SUCCESS + device-tests SUCCESS（同 HEAD SHA）**。FEATURE FREEZE=OFF；任何後續 main HEAD 未完整綠燈即重新上鎖。
- **PLAY-004 新對話 [#14](https://github.com/Tsenjii/ModelAtlas-2/issues/14) ACCEPTED：** [PR #16](https://github.com/Tsenjii/ModelAtlas-2/pull/16)／main `069b541a` [CI 38397137563](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38397137563) build/device 全綠。只清當前選定 scope 的 UI/wire history、draft/picker；telemetry、samples、quota、他連線不可刪。
- **PLAY-004 PDF 第一切片 ACCEPTED：** [PR #17](https://github.com/Tsenjii/ModelAtlas-2/pull/17) 候選 `f8984e1` [CI 38401105133](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38401105133) build/device SUCCESS，合併 `d7dd460a` [main 38403442111](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38403442111) build/device SUCCESS。原失敗 [38399650161](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38399650161) 不冒充綠燈。production PDF picker→LocalPdfPayload→VM FILES gate→InferenceEngine→OpenAiTransport 已驗。
- **PLAY-004 Audio Input ACCEPTED：** [PR #18](https://github.com/Tsenjii/ModelAtlas-2/pull/18) 最終候選 `fe72775b56482c6b2137ece05293cb3b16f1dc33`／[PR CI 38406852445](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38406852445) build+device SUCCESS；合併 main `5009e0c8`／[main CI 38408176782](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38408176782) build+device SUCCESS。GetContent(audio/*)／4 MiB／WAV-MP3 magic／filename+Base64 再驗／selected AUDIO_INPUT（含歷史）／撤銷後零 HTTP／Room→VM→Engine→Transport 皆在原 PR 測試，無麥克風權限。
- **PLAY-004 整體仍 PARTIAL**：Audio Output（使用者明確 opt-in／實際 wire／播放與轉寫結果）、Tool calls/工具結果 UI、Image Generation/Edit 結果展示仍未驗收；後續每個單一 slice 必須 production caller + 同 SHA 雙 job 綠燈。
- **平行未合併工作：** [PR #20](https://github.com/Tsenjii/ModelAtlas-2/pull/20) VPS Go/SQLite runtime 目前為 Draft，未進 main；不可當作 Android P8/P9/P10 或 VPS 部署已驗收。若它合併，先重新驗 main HEAD 的 build/device，並重新核對本文件。

- PK27／MyModels 問題 [PR #15](https://github.com/Tsenjii/ModelAtlas-2/pull/15) 已在 `3925ff9d` [main CI 38392145254](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38392145254) 同 SHA build/device 全綠；本輪後續 main 綠燈亦涵蓋原回歸。
- Public repo 不帶 private repo Git history／任何 raw key/PAT；old private CI 不得當作 public main 的驗收。

## 下一片（唯一生產功能入口）
- **PLAY-004：圖片 Vision [PR #8](https://github.com/Tsenjii/ModelAtlas-2/pull/8)、安全邊界 [PR #9](https://github.com/Tsenjii/ModelAtlas-2/pull/9)、多輪歷史 [PR #13](https://github.com/Tsenjii/ModelAtlas-2/pull/13) 已合併**。修正針對整批待送歷史檢查 Vision 並保留同 scope 能力，若撤銷則 HTTP 前拒絕；主線 PK27 observer crash 已由 PR #15 修復且全綠；新對話 [#14](https://github.com/Tsenjii/ModelAtlas-2/issues/14) 已合併並 main 同 SHA 全綠；PDF Files 已通過 PR #17/main CI，WAV／MP3 Audio Input 已在 PR #18 驗收；接著只處理 Audio Output／轉寫與播放結果，工具與圖片結果另片，PLAY-004 整體仍 PARTIAL。
- 必須先讀現有 `PlaygroundScreen.kt`、`AtlasViewModel.kt`、`InferenceEngine`、`MultimodalBlocks.kt`、`OpenAiMediaTransports.kt`、`OpenAiTransport.kt`，確認 response-side `AudioDelta`／`Transcription` 已在 core，但 Playground 目前只收 `Delta`，未能顯示／播放音訊；需要明確 opt-in 與同 scope AUDIO_OUTPUT gate。
- 依現有 `AtlasTheme.kt` UI 節奏做增量 Compose，更動不能破壞 text streaming、selected credential、provider scoped telemetry。
- 先設計最小可驗收的單一 multimodal flow；支援輸入/回應真實 wiring、permission 與 size/security guards、取消與錯誤語意、UI+core tests。
- 完整 same-SHA build+device-tests 全綠，獨立 diff review 與 traceability 更新後才能標 PLAY-004 ACCEPTED；此前維持 PARTIAL。

## 後續順序
1. 下一個產品 UI/Intelligence correctness 與已知缺口（包含 NINT-005B，待契約整合）。
2. P8 Global Crawler 的真實 candidate→evidence→verify，quota gate/Taiwan/AA Top4 嚴守。
3. P9 App Assistant 的安全結構化工具、真實 UI 管理能力與 confirmation/undo。
4. P10 版本化、可回退的 provider recipe / research reflection。
5. 所有 phase 逐片保持同 SHA full-green，勿同時把大階段混成一片。

## 本次 GitHub 實測基線（2026-10-14）
- [PR #18](https://github.com/Tsenjii/ModelAtlas-2/pull/18)：MERGED，head `fe72775b56482c6b2137ece05293cb3b16f1dc33`；[38406852445](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38406852445) build SUCCESS / device-tests SUCCESS。
- [main `5009e0c8`](https://github.com/Tsenjii/ModelAtlas-2/commit/5009e0c8a733f1863adcf9a5b8ef8c450da76d3d)：[38408176782](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38408176782) build SUCCESS / device-tests SUCCESS。
- 此文件修正不含新 Kotlin production 功能；下一個音訊輸出 PR 在同 SHA full-green 前只能 Draft。PR #20 是另一個獨立未驗收 Draft，禁止互相借用綠燈。

## 未驗收與限制
- 未驗收：PLAY-004 full UI、P8/P9/P10 的全部 production flow。
- X-011 週期背景同步仍停用，因真實 sync runner 尚未接線；不得直接改 `isPeriodicSchedulingEnabled` 為 true。
- 舊 private source 的實際憑證曾入 git 歷史：舊 PAT 仍需由持有人撤銷；不可將舊 Git 歷史推進新 public。
