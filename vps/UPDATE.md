# VPS 更新：Mac 介面與功能對齊版

本版代號 VPS-MAC-ALIGN-001。套件同時含 Linux AMD64／ARM64 預編譯程式及完整 VPS 原始碼；對應 commit 與 SHA256 見套件 VERSION.json／SHA256SUMS.txt。正式執行只需單一 Go binary，不需 Node、Python 或瀏覽器測試套件。

## 已經部署在 Git 平台

Repository：`https://github.com/Tsenjii/ModelAtlas-2`

Branch：`feature/vps-rebuild-20261014`。在原平台重新部署此分支，或固定套件 VERSION.json 的 commit；不要改成 Android 的 main。

- Repo root 的 Build Command：`sh vps/deploy/build.sh`。
- Run Command：`sh vps/deploy/run.sh`。
- 原本的 MODELATLAS_DB、MODELATLAS_MASTER_KEY、MODELATLAS_ADMIN_TOKEN、MODELATLAS_PUBLIC_ORIGIN 與持久目錄保持原值。停止服務後先一致備份既有 SQLite 與獨立保存的 master key，再部署；不要建立新的金鑰或清空資料目錄。
- Go builder、HTTPS ingress、單 instance 與跨 redeploy 持久目錄仍按 PLATFORM_DEPLOY.md 設定；本次沒有取得平台控制權，也沒有替你遠端部署。

完成後重新整理網頁。若瀏覽器仍顯示舊畫面，可用 ⌘／Ctrl + Shift + R 重新載入資源。

## 已使用本專案 systemd 安裝方式

解壓套件後，在套件目錄執行 `uname -m`：x86_64 選 linux-amd64，aarch64／arm64 選 linux-arm64。先執行 `sha256sum -c SHA256SUMS.txt` 核對檔案。

以下範例只適用原有 service 與預設資料路徑，已自訂路徑者沿用原設定。

```sh
sudo systemctl stop modelatlas-vps.service
sudo install -d -m 0700 /var/backups/modelatlas
backup_stamp=$(date +%Y%m%d-%H%M%S)
sudo tar -C /var/lib -czf "/var/backups/modelatlas/data-${backup_stamp}.tgz" modelatlas
sudo cp -a /etc/modelatlas/modelatlas.env "/var/backups/modelatlas/env-${backup_stamp}"
sudo cp -a /opt/modelatlas/modelatlas-vps "/var/backups/modelatlas/binary-${backup_stamp}"
```

AMD64：

```sh
sudo install -m 0755 bin/modelatlas-vps-linux-amd64 /opt/modelatlas/modelatlas-vps
sudo systemctl start modelatlas-vps.service
curl --fail http://127.0.0.1:8787/healthz
```

ARM64 將上述 binary 名稱換成 `bin/modelatlas-vps-linux-arm64`。環境檔及原本的資料目錄不替換；本次也不需要再次執行首次安裝流程。

本版 schema17 在啟動時由舊版自動加上逐帳戶簽到表，保留原資料。若需要退回較舊 schema16 binary，須使用更新前一致備份；不能讓舊 binary 直接開啟新 schema。

## 更新後可以使用

- 桌面側欄、近期對話、較薄的輸入框；手機維持底導覽。設定可選石墨灰／鳶尾紫／靛藍及減少動態。
- ⌘／Ctrl K 搜尋完整模型連線；使用的帳戶、Key、群組與端點仍精確分開。模型尚未讀取完成時會顯示等待狀態。
- Provider「帳戶與 Key」依帳戶分組，可逐把或帳戶批次啟停；新增秘密值與還原版本分開。表單關閉重開保留記憶體草稿，保存成功但讀取失敗時可只重新讀取清單。
- Provider 概覽的逐帳戶簽到紀錄與待簽到篩選；「站方資訊」進入後自動讀取公告／計費規則／效能。每個來源獨立失敗、重試並保留 last-good。
- 模型「我的實測」「站方效能」「觀測歷史」：本機與站方分開，1／7／30 天與群組選擇、可用鍵盤／點選的趨勢及請求收據、全量 cursor 分頁。標價／實際費率、來源與單位各自繪圖，Unknown 不當 0 或免費。

站方 API 支援情況依實際 schema／權限，仍可能拒絕或失敗；公開介面相容不代表私人帳戶介面已驗證。Android 介面下一片接續，完整 Master Spec 仍 PARTIAL。
