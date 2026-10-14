# ModelAtlas Public — ACTIVE TASK

更新：2026-10-14（VPS 獨立分支），公開 repo `Tsenjii/ModelAtlas-2`。

## Live state
- **Android MODE：PLAY-004 繼續剩餘多模態切片**；Audio Input 已由 PR #18 合併並驗收。**VPS MODE：獨立 branch `feature/vps-rebuild-20261014` / PR #20**，不得誤當 Android 的 full-green slice；VPS 仍須另行全部測試。
- **最新 VERIFIED main FULL GREEN：** `5009e0c8a733f1863adcf9a5b8ef8c450da76d3d`，來自已合併 [PR #18](https://github.com/Tsenjii/ModelAtlas-2/pull/18) Audio Input；[main CI 38408176782](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38408176782) **build SUCCESS + device-tests SUCCESS（同 HEAD SHA）**。此 main HEAD 的 FEATURE FREEZE=OFF；下一次任何 main 紅燈即再上鎖。
- **PLAY-004 新對話 [#14](https://github.com/Tsenjii/ModelAtlas-2/issues/14) ACCEPTED：** [PR #16](https://github.com/Tsenjii/ModelAtlas-2/pull/16)／main `069b541a` [CI 38397137563](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38397137563) build/device 全綠。只清當前選定 scope 的 UI/wire history、draft/picker；telemetry、samples、quota、他連線不可刪。
- **PLAY-004 PDF 第一切片 ACCEPTED：** [PR #17](https://github.com/Tsenjii/ModelAtlas-2/pull/17) 候選 `f8984e1` [CI 38401105133](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38401105133) build/device SUCCESS，合併 `d7dd460a` [main 38403442111](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38403442111) build/device SUCCESS。原失敗 [38399650161](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38399650161) 不冒充綠燈。production PDF picker→LocalPdfPayload→VM FILES gate→InferenceEngine→OpenAiTransport 已驗。
- **已驗收音訊輸入片：** 延續現有 `AudioInputPart` 真實 Chat Completions wire，使用 Android `GetContent(audio/*)`，不請求麥克風權限、不錄音；本機 WAV／MP3 signature、4 MiB cap、safe filename、直接 VM 強制再驗，僅同選定 provider/account/credential/model/group/endpoint/operation 已確認的 AUDIO_INPUT 可發。歷史含音訊時也必須重新驗能力；撤銷後 HTTP 前拒發，new conversation 不重播；實際 Room→VM→Engine→HTTP 與 Compose/裝置測試已隨 PR #18/main 同 SHA CI 驗收。
- **PLAY-004 整體仍 PARTIAL**：Audio Input 之外仍缺 Audio Output／轉寫結果、Tool calls/工具結果 UI、Image Generation/Edit 結果展示。之後順序依單一 slice 實際驗收；再進入 P8/P9/P10。
- PK27／MyModels 問題 [PR #15](https://github.com/Tsenjii/ModelAtlas-2/pull/15) 已在 `3925ff9d` [main CI 38392145254](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38392145254) 同 SHA build/device 全綠；本輪後續 main 綠燈亦涵蓋原回歸。
- Public repo 不帶 private repo Git history／任何 raw key/PAT；old private CI 不得當作 public main 的驗收。

## 下一片（唯一生產功能入口）
- **PLAY-004：圖片 Vision [PR #8](https://github.com/Tsenjii/ModelAtlas-2/pull/8)、安全邊界 [PR #9](https://github.com/Tsenjii/ModelAtlas-2/pull/9)、多輪歷史 [PR #13](https://github.com/Tsenjii/ModelAtlas-2/pull/13) 已合併**。修正針對整批待送歷史檢查 Vision 並保留同 scope 能力，若撤銷則 HTTP 前拒絕；主線 PK27 observer crash 已由 PR #15 修復且全綠；新對話 [#14](https://github.com/Tsenjii/ModelAtlas-2/issues/14) 已合併並 main 同 SHA 全綠；PDF Files 已通過 PR #17/main CI，Audio Input 已驗收；後續另片處理音訊輸出／工具／結果 UI，PLAY-004 整體仍 PARTIAL。
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


## VPS 獨立維護基線（2026-10-14）

- 舊 PR #19 已關閉，VPS 在 `feature/vps-rebuild-20261014`／[PR #20](https://github.com/Tsenjii/ModelAtlas-2/pull/20) 維護；不合併私人 Git 歷史，也不改 Android main。
- 前一片已驗收 code SHA `9a46804cde0052ae2b65710fba252fe1ee6a2d5f`：[VPS 38508548084](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38508548084) Go race／HTTP／vet、AMD64、ARM64、Browser 四項 SUCCESS；[Android 38508548193](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38508548193) build、device-tests SUCCESS，六項為同一 SHA。PR 曾轉為 Ready。
- 該片 production 入口與修復以 `vps/PARITY.md` 為準；原本缺少的 onboarding、多 endpoint、Vision/PDF/Audio Input、New API／Dedicated／LMSpeed 已有實際 caller 與驗證，舊缺口快照不再代表最新 VPS 狀態。
- 未驗收：Android 整體 PLAY-004、完整 P8／P9／P10；未取得使用者主機存取，沒有真 VPS 遠端部署證據。

## VPS 最新續作（2026-10-14）

使用者本輪指示：檢查既有 VPS 全部程式，修問題並追上目前 Android main。維持 `feature/vps-rebuild-20261014`／PR #20，對照 Android `5009e0c8`；不跳去未合併的 audio-output draft。具體 production 入口、修復與測試及限制以 `vps/PARITY.md` 為新版 VPS 交接，取代上方舊 VPS「仍缺 Vision/PDF/Audio 等」快照。Android 本身的 PLAY-004／P8–P10 尚未驗收狀態不變。

本機 Go race/HTTP + vet 已通過，Browser 交給 `vps-browser` Actions。依最新 PR HEAD 同 SHA build/device＋VPS 四工作結果驗收；未全部成功不合併，任何 regression 直接修復。

Browser 初次回歸已定位並修復（new conversation 前 composer lock），`a938fef` 的 VPS 四 job 全綠。最後 code-audit 已把 Android root 已註冊 media transport、interrupted recovery、資料庫單寫者、exact stats／custom window、stale Undo guard 接線；Final gate 仍查 PR 最新 HEAD，完整結果由 PR body 保存。

## VPS 本次繼續：媒體 Playground 結果切片

2026-10-14 再確認 Android main 仍 `5009e0c8`；使用者指示「繼續」。只新增 VPS 媒體 Playground，不改 Android 已驗收狀態，不疊 P8/P9/P10。

- `ui_js_media.go`：依 immutable endpoint 的 operation 顯示圖片生成／圖片編輯／音訊轉寫；獨立輸入表單、預覽／下載、繁中錯誤、停止；切換 scope 清草稿／附件／結果／Blob。Chat 不接收媒體 operation。
- `media.go`：共用最多四 flight 與 explicit request-id cancel；多行 transcript；完整 PNG/JPEG/WebP 解碼前先查像素，結果全體 4 MiB／4 MiPixels、HTTP JSON 8 MiB；不回傳自訂 secret echo；無效內容不記 SUCCESS。
- 原始媒體／轉寫內容不進 DB／telemetry，結果在頁面記憶體；URL-only 由使用者明確開啟，不自動向外部圖片站發請求或轉送 Key。
- `media_test.go`／Browser production flow 驗證真 TLS caller、三操作、能力撤銷、multipart MIME、零 usage、429、壞／截斷圖片、custom secret、取消與 shared budget。Browser 驗窄屏／深淺色／200% 大字。
- 本片先維持 Draft；新的 HEAD 必須重新通過 Android 兩項與 VPS 四項同 SHA gate；最終 SHA、Actions run、結論由 PR #20 最新 body 保存，不挪用 `9a46804` 結果。
- 下一個缺口仍是音訊輸出與工具互動 UI；本片不宣稱全部 PLAY-004／V1 已完成。

本片首輪 Browser `da00c79`／VPS run `38523911620`：Go test、AMD64、ARM64 已 SUCCESS；Browser 定位動態端點 select 的 label 含 options，無法以精確名稱操作。修正 production `aria-label` 與繁中端點類型名稱，不降低 Browser 精確選擇斷言；新 HEAD 重新跑六項 gate。

第二輪 `d1e2a3b` 的 Browser 已通過新端點操作，定位測試的「加入 My Models」同名按鈕 strict ambiguity；Catalog modal action 改為 `#detail` 範圍內精確選取，保留 strict/exact 斷言，未修改產品預期。仍以新 HEAD 六項 CI gate 驗收。

媒體切片 `36751cf`／VPS run `38524721426` 的四項皆 SUCCESS，Browser 三操作與取消全過；視覺 QA 發現 target 下拉仍顯示內部 Key ID，改用同 provider/account scope 的帳戶與 Key 名稱。補上實際下載 PNG bytes／多行 UTF-8 transcript 的檔案回讀，CSS snapshot 關閉 transition 以取得真正的深淺色定態。新 HEAD 六項仍須重驗。

最後操作 review：媒體「清除結果」改為只釋放結果 Blob，不會一起刪除已輸入描述或本機附件；只有切換 scope 才清全部草稿。Browser 保留輸入的斷言加入同一流程，下載內容與既有三操作已在 `179389e` 的 VPS 四項綠燈驗證，新 HEAD 仍查完整六項 gate。

## VPS 媒體切片最新已驗收基線（2026-10-14）

- Production code SHA：`00f3dc205e4a59057edcf15e2de4a2135b49d4d0`。
- [VPS CI 38526114795](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38526114795)：Go race／HTTP／vet／runtime smoke、AMD64、ARM64、Browser 四項 SUCCESS。
- [Android CI 38526114819](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38526114819)：build、device-tests 兩項 SUCCESS，與上述 VPS 為同一 SHA。裝置共 964 tests、0 failed，5 個通知權限測試依 API 29 條件 skip；不是刪除測試。
- **VPS-MEDIA-UI／SAFE／CANCEL 切片 ACCEPTED**：實際 UI 三操作、PNG／UTF-8 下載回讀、scope reset、結果清除保留輸入、阻塞取消、四 flight、影像驗證／secret echo 已驗。
- Browser 最終 artifact `11875811832`／SHA256 `b65041539532db6018fbe94ae43f1d527731ef92f41948bd503aac247ea32c7e`；已檢查圖片、轉寫、375／1024px、深淺色及 200% 大字畫面。
- 本次末尾提交僅補驗收與交接文件；新 doc HEAD 的完整六項 CI 結果仍以 PR #20 最新 body／GitHub checks 為準，不把 code 基線誤寫成任意較新 HEAD 的結果。
- 下一個 VPS 功能切片：音訊輸出或 tools 互動 UI，依單片新增真 caller／UI／測試後驗收；Android 全體 PLAY-004、完整 P8/P9/P10 仍未 ACCEPTED。VPS 獨立分支維護，無使用者主機遠端部署證據。


## 2026-10-14 使用者最新續作（VPS 全部 V1）

最新明確指示覆蓋之前只追 Android 当前片的範圍：全部完成，可以超過 Android；輕量從單程序／依賴／有界執行處理，不能裁功能。維持 VPS 獨立 branch / PR #20，不直接更動 Android main。

完整差距及 caller／驗收清單：`vps/V1_COMPLETION.md`。進行中的整合片為 VPS Playground 完整 protocol / tool calls / audio output / embedding / rerank。本機 TLS / Go race 與 JS syntax 通過；PR 保持 Draft，最新候選的 Browser／雙架構／Android build+device 同 SHA gate 尚待執行。第一片全綠後繼續 real Research Runner / AA、完整 Assistant / Reflection，再做管理／通知／資源驗收。不要在第一片完成就結束本輪。


VPS Playground 完整片 `29eff56` 已取得 [VPS 38550451082](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38550451082) 四項與 [Android 38550450921](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38550450921) 兩項同 SHA SUCCESS。當前整合片改為 Research runner／sources／funnel／budget／resume／AA V2。code 與本機 race 已落地，新增 Browser 真流程；以新 SHA 六項 gate 為準。後續仍須完整 Assistant／Reflection／管理／資源驗收，不在 Research 片全綠就結束本輪。

研究片 `fa7c291`：[VPS 38558907867](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38558907867) 四項 SUCCESS；[Android 38558907976 attempt 2](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/38558907976) build/device SUCCESS。首輪既有 Android rapidDoubleToggle 15s 逾時，同 SHA 保留斷言重跑通過。當前整合為完整 App Assistant；本機 race/vet/JS syntax 已過，新增真 Browser，仍待新 SHA 六項 CI。下一片 Reflection／宣告式 connector，其後完整管理與資源驗收。

Assistant code `69652c9` 同 SHA 全綠：VPS `38567589731` 四項＋Android `38567589525` build/device SUCCESS。當前 Reflection／宣告式資料擷取與 credential-version 保護已本機 race/vet/syntax/TLS 回歸，新增 Browser；待新 SHA 六項 gate。20:56 使用者另授權 Kilo 免 Key 實測，live conversation/native tools/App Assistant 真實流程已通過（6 HTTP／26.31s，僅合成一次性資料），證據與限制在 `vps/V1_COMPLETION.md`。完整管理／filter-ranking／notifications／backup／multi-origin／CUSTOM／native speech／資源最終驗收仍要接續，不在本片全綠就結束。

Reflection code `293bd516` 同 SHA 六項全綠（VPS 38572762140、Android 38572762176）；本輪為管理/filter-ranking／背景規則通知／portable backup v2／SQL 全量統計與輕量 runtime 候選。測試與正式 UI 已接線，新增 Browser、standalone RSS workload；待新 SHA 六項 gate。Main 遠端 2026-10-14 再驗仍為 `5009e0c8`，未改 Android source。multi-origin／CUSTOM／native Gemini speech／最終交付仍接續。

管理／資源 code `8f069625` 同 SHA 六項全綠（VPS 38582129810，Android 38582129791）。22:37 新需求 Kilo 真 Provider discovery＋全程式 RAM＋續 UI；Kilo 已執行真 App tools，但本機 public DNS unavailable，實驗輸出保留，改獨立 opt-in Actions Linux，不放寬 SSRF 或冒充已找到證據。後續同一 V1 目標不變：multi-origin／CUSTOM／Gemini native speech／媒體 RAM／完整 UI 回歸。

`b7ecdaa` 同 SHA 六项 SUCCESS（VPS 38584571842、Android 38584572181）。Kilo opt-in Actions 38584572569 真實 Provider research 33.54s 通過：2 leads/3 evidence，Groq/OpenRouter讀取成功、Cerebras307失敗如實記錄；沒有現成credentials借用/eligibility推定。PR已還原一般標題，之後不默認耗用Kilo。當前 origin/scoped auth/Group/PAT/connector/backup＋App public source/匿名 onboarding＋Provider UI收合候選，本機race/vet/syntax/TLS與新Browser驗證；待新SHA六項gate。最終custom/native speech/session settings/媒體RAM與交付接續，不在這個slice結束。


多網址／Group epoch／Agent public source 片 `d3da38c50a90976de5eac95c764b7cac66f80ddb` 同 SHA 六項 SUCCESS：VPS `38590860925` test `115945054314`、amd64 `115945054916`、browser `115945054937`、arm64 `115945055188`；Android `38590861082` build `115945620723`、device `115945553268`。現在整合剩餘 Playground：原生 Gemini 語音 CURRENT／LEGACY／雙人 metadata、宣告式 CUSTOM 對話、逐段 system/max_tokens/streaming 設定；共用 scope／工具／取消／telemetry，Assistant 固定 system 不受手動設定覆寫。新增 TLS／Browser／safe backup 與全服務 heavy media RAM profile；本片待新 SHA CI，不把前片或未執行的 footprint 當驗收。
