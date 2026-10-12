# ModelAtlas｜AI 模型與 API Provider 管理器

ModelAtlas 是以 **Kotlin、Jetpack Compose、Material 3、Room** 開發的 Android 應用程式，專注於管理 AI 模型供應商、帳號、API 憑證、模型可用性、額度、價格與推論效能。

## 已實作的主要功能

- **Playground**：選取連線、文字串流聊天、取消請求、紀錄使用者實際推論效能，以及符合已確認 Vision 能力的本機圖片附件。
- **Providers**：Quick / Advanced onboarding、帳號與憑證管理、New API 和專用供應商整合、同步與簽到資料。
- **Models**：模型目錄與 My Models 分離、搜尋篩選、分類、價格／額度來源、模型詳細資訊及實測比較。
- **安全層**：Android Keystore 保存憑證、按帳號／金鑰／群組／Endpoint 隔離資料，未知額度和價格不偽裝成免費。

以上為目前已有的程式功能；不同供應商的實際可用性仍取決於 API 和個別帳號權限。

## 尚未完成

ModelAtlas V1 **尚未全部交付**。Playground 的完整檔案、音訊、工具呼叫及圖片生成／編輯 UI，P8 全球 Crawler、P9 App Assistant、P10 Reflection 的正式產品流程仍在逐片實作與驗收。背景定期同步在真實 runner 接線前保持停用。完整狀態以 [ACTIVE_TASK](docs/ACTIVE_TASK.md) 和 [TRACEABILITY](docs/TRACEABILITY.md) 為準。

## 建置

需要 **JDK 17、Android SDK 35、Gradle 8.14.4**。

```sh
gradle :core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
```

GitHub Actions 的 [Android Build and Test](.github/workflows/android.yml) 除了編譯 debug APK，也會在模擬器上執行 Android instrumentation tests。**只有同一 Commit 的 build 和 device-tests 都成功，才算 CI 全綠**。測試成功不代表所有真實 Provider 已經逐一驗證。

## 安全與公開版注意事項

不要提交 API Key、PAT、OAuth Token、Cookie、keystore、local.properties、私人操作紀錄或備份帳號資料。此公開儲存庫使用乾淨的 Git 歷史，不包含舊私人儲存庫的敏感歷史提交。舊憑證若曾曝光，仍須由持有人撤銷。

## 專案授權

目前**未另外授予開源授權**；託管平台自帶的閱覽權限不等於自由重用與散布授權。
