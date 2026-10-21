# ModelAtlas｜AI 模型情報與 Provider 工作區

ModelAtlas 整合 **AI Provider／多帳戶與 API Key 管理、模型情報、Playground、研究與 App Assistant**，提供 VPS 網頁版、Mac 原生 App 與 Android App。現在的使用與開發優先序是 **VPS 第一、Mac 第二、Android 第三**。

**[三平台下載與版本紀錄](https://github.com/Tsenjii/ModelAtlas-2/releases)** · [安裝／更新與發布說明](https://github.com/Tsenjii/ModelAtlas-2/blob/feature/vps-rebuild-20261014/docs/RELEASES.md) · [目前完整開發狀態](https://github.com/Tsenjii/ModelAtlas-2/blob/feature/vps-rebuild-20261014/docs/ACTIVE_TASK.md)

## 選擇平台

| 優先序 | 平台 | 適合的使用方式 | 技術與下載 |
| --- | --- | --- | --- |
| 1 | **VPS** | 一個持續運行的工作區，電腦／手機瀏覽器使用，亦可作可信任的裝置同步中心 | 單 Go binary＋持久 SQLite；Linux AMD64／ARM64 完整安裝包 |
| 2 | **Mac** | 獨立本機工作區，原生輸入、鍵盤操作、圖表及媒體，不依賴外部 VPS 才能開啟 | SwiftUI／AppKit＋Keychain＋內建 Go 引擎；Universal DMG／ZIP，macOS 12+ |
| 3 | **Android** | 手機本機資料、Room／Keystore、推論與模型／Provider 管理及安全同步 | Kotlin／Compose／Material 3；Android 8+ APK |

三平台保留各自本機資料與離線使用能力，外部模型推論仍需要對應網路與帳戶權限。資料同步採裝置配對、增量游標、明確衝突處理與可撤銷授權；預設只同步 metadata，選擇同步秘密值時會在目的端重新加密。各平台的原生畫面仍有差異，不宣稱全部功能已完全相同。

## 現在可以做什麼

- **Playground**：精確選 Provider／帳戶／Key／群組／模型／端點；文字與 reasoning 串流、停止、使用量與實測效能。VPS／Mac 已接完整對話管理、搜尋／封存／匯出、多協定、工具接續與媒體流程；Android 的完整媒體與工具 UI 仍逐項補齊。
- **Providers**：Quick／Advanced onboarding、可恢復 Review、多帳戶／Key、角色與 origin 隔離、New API／專用整合、簽到與來源分區。帳戶 PAT 與推論 Key 分開，不自動換 Key 或跨供應商路由。
- **Models**：Catalog 與 My Models 分開；搜尋／分類、價格與額度來源、個人效能、站方／外部資料、詳情與歷史、明確加入及比較。未知價格／額度不當成零或免費。
- **VPS 日常維運**：固定資料庫／master key／管理密碼、完整一致備份、在副本試跑 schema 遷移、版本／認證／儲存健康檢查、更新失敗回復與中斷復原；之後使用明確版本套件更新。
- **VPS 公開研究排程**：保存後預設關閉，明確啟用才執行；每輪／每日 HTTP 上限、重啟接續、啟停與完整紀錄、來源變更確認；公開來源暫時故障的有限延後重試與恢復紀錄。關閉瀏覽器後仍由服務工作，不自動花用模型 Key。
- **研究與 Assistant 基礎**：來源／候選漏斗／證據、正式工具操作、Review／Undo、版本化知識／Recipe／反思提案；敏感資料由本機安全層處理，raw Key／PAT 不進模型提示或日誌。

VPS 詳細操作：[安裝與更新](https://github.com/Tsenjii/ModelAtlas-2/blob/feature/vps-rebuild-20261014/vps/UPDATE.md)、[排程](https://github.com/Tsenjii/ModelAtlas-2/blob/feature/vps-rebuild-20261014/vps/RESEARCH_SCHEDULES.md)。Mac：[原生 App 說明](https://github.com/Tsenjii/ModelAtlas-2/blob/feature/vps-rebuild-20261014/macos/README.md)。

## 版本與更新

每次完整 Release 必須包含 **VPS AMD64／ARM64、Mac Universal 與 Android APK**，並記錄同一來源 commit、各平台必要 CI、原始 artifact 與檔案 SHA256。任一平台未通過就不公開完整 Release。最新實際公開的版本與附件以 [Releases](https://github.com/Tsenjii/ModelAtlas-2/releases) 為準；編譯中或 draft 不算完成發布。

目前為預覽版本線：Mac 使用 ad-hoc 簽章、尚未 Developer ID 公證；Android APK 使用該次 CI 的 debug 簽章，憑證不同時無法直接覆蓋既有安裝，固定正式簽署金鑰尚未設定。更新前請保留本機資料並閱讀該版說明。VPS 更新腳本會保留原資料及私密設定，不自動安裝未驗證 HEAD。

## 完成狀態

**完整 V1 與原願望清單仍未全部完成。** 公開排程是持續研究的第一階段；全球爬蟲完整驗證／費用與 quota 管理、未知站點 Connector、可量測的自我改進閉環、模型真實性套件、同步的大型分塊／部分原生呈現，以及真站長跑／實機驗收仍有待辦。

完整需求與驗收保留在 [原願望清單](https://github.com/Tsenjii/ModelAtlas-2/blob/feature/vps-rebuild-20261014/docs/ASTRA_MASTER_SPEC.md)、[TRACEABILITY](https://github.com/Tsenjii/ModelAtlas-2/blob/feature/vps-rebuild-20261014/docs/TRACEABILITY.md)。CI fixture 不等於所有真實 Provider 已測，也不代表已部署到使用者的目標 VPS。

## 開發與驗證

三平台原始碼目前在 [PR #20](https://github.com/Tsenjii/ModelAtlas-2/pull/20) 的統一交付分支，完整發布後可 checkout 該 Release tag 自行建置。main 暫保留原 Android 基線；PR 的測試與發布不表示已合併程式主線。

VPS 需要 Go 1.26.4；Mac 建置使用 Swift 5.9+／Go 1.26.4；Android 使用 JDK 17／SDK 35／Gradle 8.14.4。安裝已建置的 VPS 套件不需要 Go、Node 或 Java。

```sh
# Android
gradle :core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon

# VPS
cd vps
go test -mod=readonly -race -count=1 ./...
go vet -mod=readonly ./...
```

完整發布還須通過四個 VPS 工作、Android build／device-tests、Mac Universal build／兩架構測試，以及發布完整性檢查；同一 SHA 全部成功才採用，不拿舊綠燈替代。

## 安全與授權

不要提交 API Key、PAT、OAuth Token、Cookie、keystore、私密環境或帳戶備份。請保管好固定 master key 與完整備份；只有資料庫檔不能替代解密金鑰。

目前**未另外授予開源授權**；託管平台自帶的閱覽權限不等於自由重用與散布授權。
