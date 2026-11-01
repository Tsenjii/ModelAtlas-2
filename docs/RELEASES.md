# 三平台版本與發布

目前使用與開發順序：**VPS 第一、Mac 第二、Android 第三**（D-014）。三平台由同一個來源 commit 交付；同一 Release 必須同時包含 VPS AMD64／ARM64、Mac Universal DMG／ZIP 與 Android APK，不以只有單一平台的產物標為完整 Release。

下載：[GitHub Releases](https://github.com/Tsenjii/ModelAtlas-2/releases)。本版準備 **0.8.5-preview.1**，改善AA逐頁持久恢復與配額的一次性核准；須新HEAD同SHA九項與實際套件／公開下載驗證。已公開[0.8.4-preview.1](https://github.com/Tsenjii/ModelAtlas-2/releases/tag/v0.8.4-preview.1)固定來源 `e6b06135510c63e6ccd65a2ce8928d2396ffcb19`，0.8.3及更早歷史下載保留。實際公開版本／來源／附件以Releases與manifest為準，CI中／失敗或只有草稿不算已發布。

## 取得與更新

| 優先序 | 平台 | 檔案／最低需求 | 更新方式 |
| --- | --- | --- | --- |
| 1 | VPS | modelatlas-vps-linux-amd64.tar.gz 或 arm64；一般安裝為 Debian／Ubuntu + systemd | 固定 tag 下載、校驗，再執行套件 deploy/update.sh；一致備份／預檢／健康驗證／失敗及中斷復原 |
| 2 | Mac | ModelAtlas-macOS-Universal.dmg／.zip；macOS 12+、Apple Silicon／Intel | 完全退出後替換 App；資料與 Keychain 保留，schema 回退須配套完整備份 |
| 3 | Android | ModelAtlas-Android-preview.apk；Android 8+ | 目前是 CI debug 簽署預覽版；先比對 ModelAtlas-Android.json 的 signer SHA256，異簽章不能直接覆蓋 |

Android 固定私密簽署金鑰尚未設定，不能宣稱預覽 APK 可跨版本無縫更新；不將私密 keystore 放進 Git／Release。Mac 仍是 ad-hoc 簽章、未 Developer ID 公證。這些狀態寫進下載頁，不藏在 CI 日誌。

## VPS 最短操作

依主機架構下載套件與對應 .sha256，核對可信 Release 的 commit／SHA256：

```sh
sha256sum -c modelatlas-vps-linux-amd64.tar.gz.sha256
tar --no-same-owner -xzf modelatlas-vps-linux-amd64.tar.gz
cd modelatlas-vps-linux-amd64
sha256sum -c SHA256SUMS.txt
sudo sh deploy/install.sh "$PWD"
# 已有同一套標準安裝時改執行：
# sudo sh deploy/update.sh "$PWD"
```

往後可使用套件 deploy/download.sh，指定明確 tag 與新的輸出目錄。它自動選 CPU 架構，只下載與校驗，不停止／安裝服務。例如：

```sh
sh deploy/download.sh v0.8.4-preview.1 ./modelatlas-0.8.4-preview.1
```

第一次產生的 /etc/modelatlas/modelatlas.env 必須另行安全備份；DB／master key／管理密碼不要放進 Git、網址或聊天。預設 listen 為 127.0.0.1:8787；用 SSH tunnel 或受信任 HTTPS 入口連接。完整操作及持久磁碟需求在套件 UPDATE.md、PLATFORM_DEPLOY.md。

## 可重複發布流程

1. 更新 release/version.properties、release/request.json 與 release/NOTES.md；tag 不重用、不移動。所有建置 checkout 真正的 PR head SHA，避免拿 merge-ref 的 binary 冒充 head。
2. 原四個 VPS、兩個 Android 及三個 Mac 工作必須在同一 SHA 全 SUCCESS；額外 release-checks 檢查發布邊界／套件完整性。原 race／vet／systemd／完整 context／歷史／媒體 workload、Browser 及原生測試／timeout 全部保留。
3. Three-platform Release 工作流只允許本倉庫 main／指定 VPS 交付分支的 push 或手動觸發發布，PR 事件只做檢查。只有 publish job 有 contents:write；等待最多32分鐘，不借舊 SHA／較舊綠燈或 skipped job。任一平台失敗即停止。
4. 從成功 CI 的固定 run 下載產物；每種套件／QA 檔依 workflow 的固定 producer 及成功 job 執行時間窗選取，同時精確核對 run／source。重跑保留的舊失敗檔不補新成功檔的缺口；不以 API 排序、較大 ID 或任意最新時間猜測，同窗多檔／過期／缺時間一律停止。對照 GitHub artifact digest、內外層 SHA256、CPU 架構、版本／來源／簽章資料與所有必要附件。RELEASE-MANIFEST.json 保存每個 job 的來源／時間及 artifact 的 producer job／建立時間。API 未直接提供 artifact 的 job ID，故依固定宣告與時間窗綁定，有歧義即拒絕。
5. 先建立 draft，全部附件上傳並核對 GitHub 回報 digest 後才公開為 prerelease；已公開版本不覆寫。再以正式公開 HTTPS 下載入口跑一次 VPS download／bundle-check。
6. 發布不等於部署；不更新主機、DNS 或自動安裝 HEAD。使用者明確執行 update.sh 才修改服務。

release/prepare.py 與 publish.py 僅需 Python 標準庫及 CI 既有 gh，VPS 執行時仍只需 Go 編譯的 binary。正式簽署／公證、完整研究願望與目標主機驗收繼續追蹤，不能因三平台同版發布而提前勾選。
