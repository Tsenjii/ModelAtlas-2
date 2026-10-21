# ModelAtlas 0.8.2 預覽版

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

- 工作區首次載入、讀取失敗與離線狀態有明確提示及重新連線入口；保留上次成功資料、精確模型連線、目前對話及未送出的草稿。
- 手機頂部提供48px重新整理按鈕；延續Mac中性色、桌面側欄與手機底導覽，改善窄螢幕、深淺色、200%字級與鍵盤焦點。
- 同時刷新合併處理，寫入期間的舊快照不覆蓋新資料；讀取批次失敗時取消其餘讀取。瀏覽器JSON GET有涵蓋回應body的20秒期限，POST及推論串流不套此期限、不自動重送。
- 恢復連線／返回頁面只重新讀取工作區；不自動推論、研究或啟動同步工作。首次未取得設定前避免用預設值儲存，外觀設定與返回導航仍可使用。
- 三平台更新產物依固定producer、同run／來源及成功job時間窗唯一選取，拒絕把舊失敗QA當成成功重跑的證據。
- 保留0.8.1公開研究有限重試、完整紀錄與更新回復。Mac版本0.8.2 build16；Android版本0.8.2-preview.1（8003），兩者原生介面與原有測試保留。

## 安裝與更新

先下載 SHA256SUMS.txt 與需要的檔案，以 sha256sum -c SHA256SUMS.txt --ignore-missing 或 macOS shasum -a 256 手動核對下載檔。VPS 套件另含外層 .sha256 與內層 SHA256SUMS.txt、VERSION.json、RELEASE.json、install／update／download 腳本及完整操作文件。首次安裝見套件 UPDATE.md；先備份私密環境檔，公開使用經 HTTPS 入口，預設服務只監聽 loopback。

Mac 更新前完全退出 App，以新版取代 Applications 中的 App，本機資料與 Keychain 保留。由新版 schema 退回舊版前須使用對應完整備份，不能把舊 binary 直接指向新版 DB。

**Android 簽署限制：** 預覽 APK 使用本次 CI 產生的 debug 憑證，簽署 SHA256 與版本在 ModelAtlas-Android.json。若與已安裝 App 憑證不同，Android 會拒絕直接覆蓋；請先保留本機資料，不要為安裝而直接移除舊 App。固定私密簽署金鑰尚未設定，因此不宣稱此 APK 已具備跨版本無縫升級。

## 驗證與完成界線

RELEASE-MANIFEST.json 記錄唯一來源 commit、同 SHA 九個必要 CI job、Actions run、原始 artifact ID／SHA256 與所有發行檔案雜湊。發布前驗證兩個 Linux 套件全部內外層校驗、實際 AMD64 CLI、Mac 版本／來源及 Android APK／實際憑證；所有平台檔案上傳完整且校驗吻合後才公開。

完整 P8 Crawler、可量測自我改進、未知站點 Connector、authenticity、同步大型分塊／部分原生呈現及實機／真站長跑仍有待辦。公開排程預設關閉，不自動使用模型 Key 推論。沒有使用者目標 VPS 的位址／登入資料，這次提供可安裝版本，不宣稱已部署到該主機。
