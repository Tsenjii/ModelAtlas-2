# ModelAtlas 0.8.3 預覽版

以 **VPS → Mac → Android** 為使用與開發優先序。本次是三平台同一來源的預覽版，尚不代表完整願望清單已全部完成。

| 平台 | 下載 | 使用方式 |
| --- | --- | --- |
| VPS Linux x86_64 | modelatlas-vps-linux-amd64.tar.gz | 完整套件；先校驗再執行 install.sh／update.sh |
| VPS Linux ARM64 | modelatlas-vps-linux-arm64.tar.gz | aarch64／arm64 主機使用 |
| Mac Apple Silicon／Intel | ModelAtlas-macOS-Universal.dmg 或 .zip | 原生 Universal App；macOS 12+ |
| Android | ModelAtlas-Android-preview.apk | Android 8+；本次 CI debug 簽署的預覽版 |

VPS：單 Go／SQLite 服務、桌面／手機介面、完整對話與請求歷史／匯出、Provider／多帳戶／Key／精確模型連線、價格／額度／效能、媒體與工具、Assistant／研究工作區、明確啟停的公開研究排程及每日 HTTP 上限。安裝與更新沿用原資料庫、master key 及管理密碼；一致備份、副本遷移預檢、精確版本／認證健康驗證、失敗回復與中斷復原。沒有自動安裝新 HEAD。

Mac：SwiftUI／AppKit 原生介面、Keychain、本機內建相同來源的 Go 引擎與離線資料、模型／供應商管理、對話／媒體、圖表、研究及安全同步。App 與引擎均包含 arm64／x86_64。使用 ad-hoc 簽章，尚未取得 Developer ID 公證；不提供關閉 Gatekeeper 的指令。

Android：Compose／Room／Keystore 工作區及已驗收的推論／供應商／模型／安全同步。其原生介面與完整功能仍排在 VPS、Mac 之後，不代表 VPS 的全部新 UI 已移植。

## 這次改善

- 新對話在伺服器確認建立後立即可用，不再等待整個工作區刷新；只重新讀取對話清單，減少無關的Provider／Catalog等讀取。
- 每次明確建立操作有獨立UUID請求識別。即使瀏覽器在回應前斷線而重送相同HTTP請求，伺服器利用原有主鍵只保留同一筆對話；跨帳戶／模型／群組／端點或不同名稱重用識別會拒絕，不覆寫原紀錄。此機制只用於建立對話，不重試推論。
- 讀取清單失敗有持續提示及只讀重試；建立遭拒或回應不完整時保留原草稿、附件、工具與設定，不把未知結果宣稱成未寫入，也不由應用程式自動重播POST。
- 晚到的清單或建立回應不能切換使用者已選的對話／帳戶；原scope的歷史與分頁內草稿保留。手機提示卡、48px按鈕、深淺色及200%字級維持一致，輸入區可見或可捲動到達。
- 保留0.8.2工作區離線恢復、20秒JSON GET期限、三平台來源驗證，以及原研究排程／完整歷史／更新回復。schema仍19，不啟用新的背景工作。
- Mac版本0.8.3 build17，內建同源Go引擎；Android版本0.8.3-preview.1（8004）。原生介面及原測試保留，不把新的網頁流程冒充已移植到原生UI。

## 安裝與更新

先下載 SHA256SUMS.txt 與需要的檔案，以 sha256sum -c SHA256SUMS.txt --ignore-missing 或 macOS shasum -a 256 手動核對下載檔。VPS 套件另含外層 .sha256 與內層 SHA256SUMS.txt、VERSION.json、RELEASE.json、install／update／download 腳本及完整操作文件。首次安裝見套件 UPDATE.md；先備份私密環境檔，公開使用經 HTTPS 入口，預設服務只監聽 loopback。

Mac 更新前完全退出 App，以新版取代 Applications 中的 App，本機資料與 Keychain 保留。由新版 schema 退回舊版前須使用對應完整備份，不能把舊 binary 直接指向新版 DB。

**Android 簽署限制：** 預覽 APK 使用本次 CI 產生的 debug 憑證，簽署 SHA256 與版本在 ModelAtlas-Android.json。若與已安裝 App 憑證不同，Android 會拒絕直接覆蓋；請先保留本機資料，不要為安裝而直接移除舊 App。固定私密簽署金鑰尚未設定，因此不宣稱此 APK 已具備跨版本無縫升級。

## 驗證與完成界線

RELEASE-MANIFEST.json 記錄唯一來源 commit、同 SHA 九個必要 CI job、Actions run、原始 artifact ID／SHA256 與所有發行檔案雜湊。發布前驗證兩個 Linux 套件全部內外層校驗、實際 AMD64 CLI、Mac 版本／來源及 Android APK／實際憑證；所有平台檔案上傳完整且校驗吻合後才公開。

完整 P8 Crawler、可量測自我改進、未知站點 Connector、authenticity、同步大型分塊／部分原生呈現及實機／真站長跑仍有待辦。公開排程預設關閉，不自動使用模型 Key 推論。沒有使用者目標 VPS 的位址／登入資料，這次提供可安裝版本，不宣稱已部署到該主機。
