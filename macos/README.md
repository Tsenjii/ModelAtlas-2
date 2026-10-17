# ModelAtlas 原生 macOS App

SwiftUI／AppKit 原生介面，加上內建 Go／SQLite 本機引擎。沒有 WebView、Electron、常駐 Node 或外部 VPS 前置要求。與 Android／VPS 同 repo 分支維護；不把新 Mac 介面當成 Android phase 已完成。

## 安裝與版本

GitHub Actions `macOS Native App` 產出 `ModelAtlas-macOS-Universal.dmg`、`.zip` 與 `SHA256SUMS.txt`。DMG 內將 ModelAtlas 拖入 Applications 即可；亦可解壓 ZIP 後移入 Applications。App 與內建引擎都含 arm64／x86_64，Apple Silicon 原生執行，不要求 Rosetta。

最低部署目標 **macOS 12 Monterey**；使用 Swift tools 5.9+ 及 Go **1.26.4**。Go 1.26 是最後支援 macOS 12 的版本，不能未經判斷升到 1.27；查核 [Go 官方 release notes](https://go.dev/doc/go1.26)。CI 使用 [官方提供](https://docs.github.com/en/actions/reference/runners/github-hosted-runners)的 `macos-15` arm64 與 `macos-15-intel`，不冒充已在 macOS 12 實機驗證。

目前使用 ad-hoc code signing，會驗證 bundle／helper 的簽章完整性；**沒有 Apple Developer ID 簽署／Apple notarization／App Store 上架**。下載到自己的 Mac 後，Gatekeeper 可能要求持有人確認來源；不以移除 quarantine 或關閉系統保護的命令處理。正式公證需要持有人的 Apple 開發者簽署環境，App 本身不收集 Apple 帳密。

## 已接線的原生工作區

- Playground：⌘K 搜尋式模型選擇，依模型／供應商／帳戶／Key label／群組／端點搜尋、收藏與供應商篩選；↑／↓ 高亮、Enter 確認、Esc 關閉，輸入法組字期間保留選字操作；切換未送模型保留草稿／附件，舊對話 scope 不改。完整選定 Provider×Account×Key×Model×Group×Endpoint，真 SSE 文字／reasoning、停止、可持續輸入下一稿、永久對話、搜尋／置頂／命名／封存／恢復／分頁與完整 JSON／Markdown 匯出；系統指示、Token 上限、串流與工具定義／工具結果接續。
- 圖片／PDF／WAV／MP3 選檔與每種4MiB限制，沿用引擎 signature／能力再次驗證；影像生成／編輯、轉寫、語音、embedding／rerank 走同一正式媒體入口，原生圖片預覽、音訊播放、結果儲存，不自動抓遠端圖片。
- Providers：Quick／Advanced → 真檢查／可恢復 Review → 明確選模型確認；帳戶、Key／PAT、群組、啟停、rotate／版本恢復、額外加密認證欄位、immutable endpoints／CUSTOM 宣告式協定、網站／備註／簽到設定與 New API 資料查詢。
- Models：緊湊完整連線列，真實平均 TTFT／TPS、我的上線率附本機樣本數與時間窗；少量樣本提示僅為介面提示，不是統計信賴區間。My Models 與 Catalog 分開，搜尋／分類、完整 Catalog 搜尋與分批顯示、手動加入、精確 Key／端點連線、即時比較、模型詳情、來源／時間窗／保留歷史、能力確認、分類／別名／手動資料與收藏／觀察／隱藏／封存。
- Settings：原生外觀、時區、背景同步與通知偏好、通知紀錄與本機投遞、請求分頁／收據／完整 NDJSON、可攜設定匯出與明確匯入。
- 研究：來源管理、明確選來源／本輪預算／最小推論 opt-in、啟動／暫停／恢復／停止、完整候選漏斗、反思／知識檢視、Recipe 新版本與切回舊版本。
- Assistant：選定已確認工具能力的模型，真結構化工具 runner、獨立歷史、停止、變更前後 Review／確認／Undo；raw Key／PAT 仍只經本機 privileged API 使用。

部分進階宣告式協定、欄位對應與手動 metadata 以原生 JSON 編輯器呈現；不是完整的視覺化設計器。Gemini 原生語音目前的原生表單提供 CURRENT／LEGACY 單聲音；雙人逐句編輯、完整 connector／knowledge proposal 管理與 research integrations 的 Mac 專用表單仍須後續獨立切片，底層原有能力未刪除。沒有一般遠端 VPS 連線模式；此版為獨立本機 App。

正式 UI 不塞假模型或假費率；CI 截圖是獨立目錄內的合成 fixture，不能當真供應商費率／可用性證據。所有「已驗證」須以最新 PR 同 SHA 的完整 CI／實圖 review 為準，不能只憑本節功能接線描述標 ACCEPTED。

## 原生聊天閱讀續片（0.1.2 候選）

原生 Markdown 支援標題、段落、清單、引言、分隔線、fenced code 與簡單 pipe table；程式碼保留原始縮排並獨立複製，寬表格水平捲動。未知語法保留為文字，沒有 HTML／JavaScript renderer／自動下載遠端圖片。串流中的文字維持輕量顯示，完成後套用區塊排版。

聊天往上捲會暫停自動跟隨，回到最新或捲至底部恢復；新對話重設閱讀狀態。輸入區接受本機檔案拖放或選檔，每則最多圖片／PDF／音訊各一份，每份4MiB，整批失敗不取代既有附件；離開原對話／連線後的晚完成結果拒絕寫入。副檔名與magic header先驗，正式引擎仍驗完整格式／能力。訊息選單直接開啟該sample_id的當次請求收據，不以其他請求或估算零值代替。

驗收設計與限制見 `CHAT_EXPERIENCE.md`；完成狀態只查最新 PR exact SHA／CI／實圖，不把候選描述當已通過。

## 資料、安全與生命週期

`~/Library/Application Support/ModelAtlas/atlas.sqlite` 保存資料；目錄0700、SQLite／ready／log受限。master key與內部管理token由 `SecRandomCopyBytes` 產生，寫到 macOS Keychain 的 `dev.modelatlas.mac.engine`／`engine-v1`，不寫 UserDefaults／JSON／CLI參數／LLM。已有 DB 卻找不到原 Keychain 金鑰時拒絕重建，避免悄悄失去解密能力。

App 啟動自帶引擎，`desktop` 模式只能使用 `127.0.0.1:0` 的 OS 動態埠；私密 ready 檔只回實際 loopback URL 與 PID。native client 驗固定 loopback origin、禁轉址／cookie／cache／proxy；每個 API 仍經管理認證。`PORT`／public listener override 不能把桌面模式變成 LAN 服務。App 保留 stdin lifetime pipe，正常退出、crash 或父行程結束都會使引擎收尾；正常關閉等待 HTTP／worker／SQLite，遇到超時只終止自己啟動的 helper，沒有常駐 daemon／開機自啟。

一個資料庫只允許一個 writer。原始聊天／telemetry／evidence／研究永久保存，隱藏／封存不刪除。附件 raw 資料和媒體生成結果沿用既有記憶體生命週期；重啟後含舊附件的對話需要新對話，無法從 metadata 還原附件。

可攜設定匯出**不等於完整備份**。完整歷史搬移需在 App／引擎均關閉後一致備份 SQLite，並保留對應的 Keychain／master key；不能只複製 DB 就聲稱另一台 Mac 可解密。此版尚未提供密碼加密的完整跨機備份精靈。通知與背景預設OFF；系統通知只有明確啟用並授權後、App執行期間才投遞，不宣稱遠端Push或Mac休眠期間24/7可靠工作。

## 自行建置

在 Mac 安裝 Xcode／Command Line Tools、Go1.26.4 後，從 repo root：

```sh
bash macos/scripts/build.sh
```

SwiftPM 也可直接用 Xcode 開啟 `macos/Package.swift` 查看原生程式碼；獨立App需以上述bundle流程包含引擎與Info.plist。正式App執行時不需要Go／Swift toolchain。

## 驗證與交接

- `AtlasCoreTests`：完整 scope 區分、SSE Unicode／multiline／bounds、Keychain 真存取、只有 loopback origin、禁 redirect／截斷 SSE 不冒充完成。
- 兩架構測試同一份已建置 Universal artifact；native client → packaged production engine → 真 TLS fixture，驗 catalog≠membership、A/B同Key ID隔離、串流／完整歷史／實際cancel、備份下載、停機與固定金鑰 reopen、第二 writer拒絕、預設OFF。不是 mock transport。
- 實際啟動 `.app` 的 SwiftUI 視窗，以同一 store／API 寫入合成資料、完成 TLS chat，再拍三工作區、深淺色、窄視窗與選模／新增供應商 sheet 十一張PNG；正式 store caller 另驗未送草稿／附件保留、舊對話草稿恢復、重複模型與 Key ID 的完整 scope 搜尋及非法 scope 拒選。這是實際原生視窗 capture＋store caller 驗證，**不是 XCUI 全點擊覆蓋**。
- CI native integration 暫時信任一張每輪新產生、24小時到期的本機 fixture CA。結束將**這張 CA 明確設為 Deny**、按完整 fingerprint 刪除憑證，並用 Apple Security API 確認 exact admin Deny 與離線 OS trust 拒絕；任一步失敗 gate 失敗。這避開 [GitHub runner 已知 remove-trusted-cert 卡住問題](https://github.com/actions/runner-images/issues/12116)，不改 authorizationdb／不關 TLS 驗證。Deny-only trust record 留在可丟棄 runner 至 VM 銷毀；只供可丟棄 CI，或明確 `MODELATLAS_ALLOW_TEST_CERT=1` 的開發測試。正常App不安裝憑證，也不改TLS驗證。
- `process-footprint.json` 用 `ps RSS` 記錄該次原生 App＋其自帶引擎的啟動／TLS chat／十一畫面 capture，排除 fixture與test runner；不冒充完整VPS expanded workload或RSS硬上限。
- Linux Go新增 `TestDesktopDynamicLoopbackAndParentPipeLifetime`，真 binary 驗實際埠／0600 ready／401／parent EOF退出／ready移除／DB lock釋放，既有完整Go／VPS Browser／Android gate仍全部保留。

Mac 唯一 live slice／未驗收狀態見 `docs/ACTIVE_TASK.md`，正式 caller → 測試見 `docs/TRACEABILITY.md`；最新 PR body 保存 exact code SHA、全部九項 jobs、artifact、實圖 review 與限制。不要將缺 Apple 公證、未跑 macOS12 或後續專用表單寫成已完成。

