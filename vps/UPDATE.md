# VPS 安裝與更新

最新交付依 D-014 優先 VPS；完整版本在 [三平台 Releases](https://github.com/Tsenjii/ModelAtlas-2/releases)，下載／發布規則見 [RELEASES.md](../docs/RELEASES.md)。同一 Release 皆為同 SHA 三平台，需九個必要 CI 全成功；VPS 執行時仍為單 Go binary／SQLite。

維運切片 VPS-OPS-006：完整套件、資料庫升級前快照、預檢、健康檢查與更新失敗回復。只使用通過該版本必要 CI 的套件；最新結果見 [PR #20](https://github.com/Tsenjii/ModelAtlas-2/pull/20)。這些流程不代表已替使用者部署到遠端主機，也不代表完整研究願望清單已完成。

## 一般 VPS：Debian／Ubuntu + systemd

優先從該版本 GitHub Release 下載對應 tar.gz 與 .sha256；Actions 只供正在驗證中的候選。在該 SHA 的 VPS Actions 亦可下載 `modelatlas-vps-linux-amd64` 或 `modelatlas-vps-linux-arm64` artifact。`uname -m` 為 x86_64 選 amd64；aarch64／arm64 選 arm64。解開 artifact ZIP 後會有單一 binary、完整 tar.gz 與外層校驗檔。正式運行不需要 Go、Node、Python 或瀏覽器。

以下以 amd64 為例，先在可信 GitHub Actions 頁核對 commit／artifact SHA256，再檢查並解壓完整套件：

```sh
sha256sum -c modelatlas-vps-linux-amd64.tar.gz.sha256
tar -xzf modelatlas-vps-linux-amd64.tar.gz
cd modelatlas-vps-linux-amd64
sha256sum -c SHA256SUMS.txt
```

校驗檔可發現損壞，但不能替代套件來源認證。VERSION.json、binary `version` 與設定→資料→服務與更新，均顯示同一 commit、建置時間、CPU 架構與 schema。

首次安裝：

```sh
sudo sh deploy/install.sh "$PWD"
```

會建立獨立 modelatlas 系統使用者、0700 資料目錄、root-only 0600 環境檔，產生一次性的隨機管理密碼與 master key，安裝並啟用服務。管理帳號為 `admin`；在自己的安全終端以 `sudoedit /etc/modelatlas/modelatlas.env` 查看管理密碼。首次產生後須將環境檔另行安全備份；不放入 Git、聊天或網址。資料預設 `/var/lib/modelatlas/atlas.sqlite`，binary 在 `/opt/modelatlas/modelatlas-vps`。

日後可從既有套件取得部署工具，以明確 tag 下載到新目錄，工具自動選架構並校驗完整套件，不會停止或安裝服務：

```sh
sh deploy/download.sh v0.8.0-preview.1 ../modelatlas-0.8.0-preview.1
```

選擇之後要更新的實際 tag，已存在的輸出目錄會拒絕覆寫。每次下載並解開新套件後，只需：

```sh
sudo sh deploy/update.sh "$PWD"
```

工具也能接手舊 install.sh 的標準安裝（原有 env 只包含 token／master key 亦可）。沿用原 DB／金鑰／管理密碼，不重新初始化。自訂 DB 可在 `/var/lib/modelatlas` 內；其他路徑、自訂 systemd drop-in 或無 root 的平台需用下節流程，不會猜測路徑後覆寫資料。

更新依序執行：

1. 核對全套校驗、版本及架構；取得部署鎖，排除同時更新。
2. 保存原環境檔、binary、unit 與原服務狀態；建立持久更新日誌。暫停管理／同步寫入和背景工作，停止服務。
3. 對完整 SQLite 做一致快照，檢查 integrity／FK／全部已知 vault 憑證；只在另一份私密副本試跑遷移。原備份不被預檢修改。
4. 原子切換 binary、啟動新版，透過真 HTTP 驗證管理認證、精確版本、DB schema 與啟動時 vault 檢查；通過後才解除暫停。不是只檢查 `/healthz` 的 200。
5. 若預檢或啟動失敗，自動恢復舊 binary／unit／原 DB，並驗證原服務重新可登入。失敗的 DB／WAL／SHM 另存，不刪除；每次更新備份永久保留於 `/var/backups/modelatlas/upgrade-*`。

更新需暫停服務；請預留至少數份 DB 的空間（正式 DB、快照、預檢副本、失敗保留），不是零停機。備份不自動刪除；可將完整備份移到自己的安全儲存。環境檔與 DB 快照同屬私密資料，跨主機備份時分開保管。

若主機斷電／程序遭 SIGKILL，下一次執行 install／update 會先讀持久日誌復原。也可單獨執行：

```sh
sudo sh deploy/manage.sh recover "$PWD"
```

已確認成功的階段會重新啟動並檢查目前版本；已成功或已完成還原的階段不重播舊 DB，避免抹去恢復服務後的新資料。若環境檔在更新中遭外部修改、服務停不下來或備份校驗不符，工具停止並保留日誌與所有資料；不猜測 master key。沒有安排自動下載或自動安裝未驗證的新 HEAD。

## 連線與日常檢查

預設只監聽 `127.0.0.1:8787`。以 SSH tunnel `ssh -L 8787:127.0.0.1:8787 user@your-vps` 連線，再開 `http://127.0.0.1:8787/` 登入。公開手機網頁與跨裝置配對需固定 HTTPS origin，設定見 [PLATFORM_DEPLOY.md](PLATFORM_DEPLOY.md)；勿直接開放明文公網 backend。

```sh
sudo systemctl status modelatlas-vps.service
curl --fail http://127.0.0.1:8787/healthz
sudo /opt/modelatlas/modelatlas-vps ops ready --env-file /etc/modelatlas/modelatlas.env --default-db /var/lib/modelatlas/atlas.sqlite
```

最後一個指令除了 HTTP，也核對所執行 binary 和服務版本、認證／資料庫／vault；管理密碼不在命令參數。設定頁「服務與更新」可手動重新檢查、保留 last-good 與重試；完整備份不會透過一般網頁下載。更改環境檔後需重啟，既有主金鑰不得隨意更換。

## Git 平台／其他持久主機

Repository `https://github.com/Tsenjii/ModelAtlas-2`，branch `feature/vps-rebuild-20261014`（main 是 Android 主線）。Root `.`，Build `sh vps/deploy/build.sh`，Run `sh vps/deploy/run.sh`。沿用固定的 MODELATLAS_DB／MASTER_KEY／ADMIN_TOKEN／PUBLIC_ORIGIN 及相同持久磁碟；平台契約見 [PLATFORM_DEPLOY.md](PLATFORM_DEPLOY.md)。

目前 schema19。啟動發現較舊 schema 時，**在任何 schema 寫入之前**，先在 DB 同目錄的 `upgrade-backups` 保存原 schema 一致快照與 SHA256 manifest，再做原有交易遷移；空 DB 首次建立不製造空備份。若快照無法保存，啟動失敗而不開始遷移；錯 master key 或較新 schema 也在遷移前拒絕。此功能不需要 root、不啟動額外常駐程序。平台須持久保存整個資料目錄，不能只保留單一 DB 檔。

平台重新部署的 binary／流量回復由平台控制，不宣稱有遠端平台 API。需要退回較舊 binary 時，先停止服務，再恢復對應 schema 的快照與舊 binary；不能只把舊 binary 指向新版 DB。若已有自己的私密單行環境檔（0600），可在停止服務後使用以下離線工具：

```sh
./modelatlas-vps ops backup --env-file /private/modelatlas.env --output /private/backup.sqlite
./modelatlas-vps ops preflight --env-file /private/modelatlas.env --database /private/backup.sqlite --output /private/preflight.sqlite
./modelatlas-vps ops restore --env-file /private/modelatlas.env --snapshot /private/backup.sqlite
```

env 必須包含絕對 MODELATLAS_DB、固定 master key 與管理密碼。只接受單行 NAME=value／引號格式；不執行 shell、不展開 `$()` 或環境變數、不把密碼當參數。`restore` 核對 manifest／完整性／原 master key，拒絕正在使用的 DB，保留被替換的原檔與 WAL／SHM；之後以所需舊版 binary 啟動。從舊快照復原僅包含該快照時間的資料，較新的原檔仍保留在同目錄 `.restore-*`。

Safe settings export 只保存可攜設定；不包含全部對話／請求／研究 raw history 或可用 Key，不能替代上述完整備份。原始附件沿用既有僅當次記憶體的語意，不因更新聲稱永久保存。

## 驗證界線

原 Go race／vet／完整 standalone workload、雙架構 build 與全部原 Browser 流程保留；新增真 systemd 安裝→TLS inference→更新→失敗回復→SIGKILL 復原→損壞套件拒絕，另驗升級前 schema17 快照、錯金鑰、完整多 MiB Unicode／精確價格／原秘密與歷史。新增手機／桌面／200% 狀態與 last-good Browser 驗證。本輪新 HEAD 尚須同 SHA 六項全綠；沒有目標主機 IP／登入方式，尚未實際部署。
