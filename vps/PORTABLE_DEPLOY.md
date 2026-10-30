# 通用部署、持久資料與 HTTPS 入口

VPS-PORTABLE-OPS-016，2026-10-30。使用者要求先完成 VPS，並明確指定部署能力要通用，Muse 只是目前的第一個目標。下列所列範圍已由來源`7053b1e76e0c15f808640375fec5c8ebf511cb1a`同源十一項CI、十一份真ZIP及實圖驗證；目標環境另行驗收。已公開的 `v0.8.5-preview.1` 不包含這批新命令，不將舊 Release 冒充本片套件。

## 選擇執行方式

| 環境 | 共用執行方式 | 必須提供 |
| --- | --- | --- |
| Debian／Ubuntu VPS、有 root | 原 `deploy/install.sh`、`update.sh`、`manage.sh` 與 systemd | 持久磁碟、固定金鑰、受信任 HTTPS 入口 |
| 一般 Linux、無 root、雲端電腦 | 同一份 binary 的 `portable init`／`portable run` | 私密持久目錄、可常駐的 supervisor、相容 HTTPS ingress |
| Docker／Compose／容器平台 | 本文容器映像、相同 Go 後端與私密設定檔 | 單 instance、本機持久 volume、正確的 UID 與 ingress |
| Muse | 依實際能力選上述方式 | 仍須確認平台是否保留程序、磁碟及允許固定入口 |
| 靜態網站或不相容執行環境 | 將後端放在符合上述契約的位置 | 不將靜態頁面、短命工作或 ephemeral 磁碟當完整服務 |

產品沒有 Muse 專屬模式，也不依主機廠商改寫資料、備份或認證。每個實際環境各自記錄證據；一次 CI 成功不代表所有平台皆相容。

## 免 root 的 binary

先核對套件架構、外層 SHA256、`SHA256SUMS.txt`、`VERSION.json` 及 binary `version`。正式執行不需要 Go、Node、Python 或 Docker。以下以 AMD64 為例，ARM64 使用對應 binary；`/private/modelatlas/state` 代表自己建立的持久絕對路徑，父目錄必須已存在。

```sh
./bin/modelatlas-vps-linux-amd64 portable init \
  --directory /private/modelatlas/state \
  --public-origin https://atlas.example.com \
  --listen 127.0.0.1:8787

./bin/modelatlas-vps-linux-amd64 portable run \
  --directory /private/modelatlas/state
```

`portable init` 只產生一次管理密碼與 32-byte master key。設定在 `state/config/modelatlas.env`（0600），DB 在 `state/data/atlas.sqlite`，本機備份工作目錄在 `state/backups`，目錄皆為0700。相同設定再次初始化會保留原值；並行初始化不能換掉另一程序的金鑰。既有資料卻缺少設定檔時拒絕產生新 key，須找回原設定。

登入名稱為 `admin`；密碼只從自己的安全終端查看私密設定，不貼入聊天、Git、網址或 log。另行安全保存設定及備份復原材料，見 [CLOUD_BACKUP.md](CLOUD_BACKUP.md)。資料目錄放在版本目錄之外，不把正在寫入的 SQLite 放進雲端硬碟同步目錄。

`portable run` 是正常前景程序，以 `exec`／SIGTERM 配合平台 supervisor 或自己的程序管理器。僅在互動終端執行不保證關閉終端後常駐；是否24/7由實際主機契約決定。已有私密 env 的部署也可使用：

```sh
./bin/modelatlas-vps-linux-amd64 serve --env-file /private/modelatlas.env
```

env 檔以資料解析，沒有 shell 展開或執行；必須獨立包含 DB、admin token 及 master key，不借用同機其他服務的憑證。資源選項由 supervisor 設定，原 `GOMEMLIMIT=192MiB` soft limit、原工作量與並行保留。

## 容器與 Compose

repository 以根目錄為 build context，使用 `vps/deploy/Dockerfile` 建置相同 Go 程式。完整 Linux 套件則已含 `deploy/Dockerfile`，直接複製該套件內校驗過的架構 binary，無須另裝 compiler。兩種方式使用同一 Compose 服務、資料 volume、非 root UID10001及認證流程；建置 context 採明確白名單，不傳入主機設定、資料或 Git 歷史。

在 repository root 或解開的套件 root 操作：

```sh
export MODELATLAS_PUBLIC_ORIGIN=https://atlas.example.com
# repository：git rev-parse HEAD；套件：VERSION.json 內已核對的40字元 commit。
export MODELATLAS_SOURCE_COMMIT=已核對的來源commit

docker compose -f deploy/compose.yaml -f deploy/compose.https.yaml up -d --build
```

上例為套件路徑；repository 改用 `-f vps/deploy/compose.yaml -f vps/deploy/compose.https.yaml`。origin 和來源 commit 不是秘密。網域須指向目標主機，且主機允許80／443；HTTPS overlay 由 Caddy 申領及保留憑證，不能在沒有網域／入口權限時憑空產生可用網址。

Compose 先以同一非 root 使用者完成一次初始化，成功後才啟動 app。`modelatlas-data` named volume 保存設定、DB／WAL／SHM、升級副本、備份帳本及暫存；root filesystem唯讀，暫存使用持久 volume，沒有新增小型 tmpfs 限制原完整 context 或備份大小。Caddy 的憑證及設定各有獨立 volume。

基礎 Compose 不發布 backend HTTP 埠。HTTPS overlay 只發布代理的80／443；若已有同機 HTTPS 代理，改用 `compose.loopback.yaml`，只發布 `127.0.0.1:8787`。平台自己的 ingress 可直接連容器內8787，但必須隔離該明文埠。

```sh
docker compose -f deploy/compose.yaml -f deploy/compose.https.yaml ps
docker compose -f deploy/compose.yaml exec -T app \
  modelatlas-vps ops ready --env-file /data/state/config/modelatlas.env
```

`docker compose down` 後以同一 project／volume再啟動會保留資料。更新時保留 project名稱與 volume，先完成一致備份／新 binary預檢，再建置已驗來源並重建 app。`down --volumes` 會刪資料，不作為日常更新命令。bind mount需由操作者提供正確私密權限與UID；不要為了啟動而將設定改為全員可讀。

雲端備份可選設定 `MODELATLAS_BACKUP_DESTINATION=/data/state/config/backup.json` 後重建 app；它只是私密目的地檔的路徑，沒有把雲端密碼放進 image／Compose環境。實際連線方式見 [CLOUD_BACKUP.md](CLOUD_BACKUP.md)。

## HTTPS 與網址

固定 origin 必須是實際 `https://host[:port]`，不帶帳密、path、query或fragment。反向代理保留外部Host、覆寫單一 `X-Forwarded-Proto: https`，移除不可信Forwarded／X-Forwarded-Host，並支援長連線與SSE。套件的 `modelatlas-proxy.caddy` 為容器入口共用範本；同機binary代理將 upstream改為實際loopback位址。

正式程式在認證前驗HTTPS／Host／Origin，之後仍須Basic或Bearer認證；錯HTTPS訊號回426、錯Host回421、跨origin寫入回403。只有免認證的 `/healthz` 可作內部probe，不含provider／key／歷史。直接開放HTTP backend會讓客戶端偽造代理header，因此隔離由主機／平台落實。

登入後可由設定→資料核對實際服務commit、schema及vault；再實際建立資料、重啟、重新部署並查回完整紀錄。僅 `/healthz` 成功不足以證明認證、磁碟持久化或外部網址可用。

## 更新與復原

原systemd部署沿用 [UPDATE.md](UPDATE.md) 的一致備份、副本遷移、版本檢查、原子切換與中斷復原，不改既有安全門檻。

前景／平台部署先停止自己的supervisor，以原binary保存完整一致快照；用新binary的 `ops preflight` 在副本試跑遷移，確認後讓supervisor啟動新binary及原私密env。`ops ready` 核對新版本、認證、DB與vault。資料庫啟動遷移前仍保存原schema快照；失敗不回報更新成功。退回舊binary必須配對舊schema快照，不能直接打開較新schema。

若服務仍運作，可先使用本片的 `ops online-backup` 建立一致快照，不接管writer／不啟動第二個worker。停止服務後的舊 `ops backup`／`restore` 仍要求原writer lock，沒有為部署放寬。

## 驗收邊界

本片最後來源7053的普通Go race455入口／683結果（626pass、57明確opt-in skip），本機clean450.496秒、CI533.560秒，原600秒上限保留；原660結果逐項相同。額外真rclone驗加密、回讀、完整還原、排程、取消與SIGKILL；45Browser258.241秒／原300秒、566PNG，包含原ID恢復、CAS草稿、63筆紀錄與9新圖。134相關視圖／34接觸表與9張額外原尺寸備份圖自行檢視，無第二位獨立reviewer。

同來源十一項包含原生AMD64／ARM64 portable jobs：各22個portable結果21pass＋另階段容器skip，再由真容器契約PASS補驗。容器package各86.427／95.512秒，實際Docker／Compose、來源及套件映像、Caddy／信任測試CA、未完成前SSE、volume重建、vault及加密還原皆通過。受控TLS及本機crypt backend不冒充真雲端帳戶、ACME公網網域或Muse部署。完整證據見`docs/verification/20261030/vps-portable-baseline.json`；首輪Android逾時根因仍UNKNOWN，同源完整重驗通過不稱修復。

目前未取得Muse管理／終端入口、使用者指定的雲端目的地及固定HTTPS入口，因此還沒有真Muse部署、真雲端帳戶回讀或個人使用網址。取得後沿同一流程驗收，不繞過平台限制，也不影響既有工作。個人部署授權不等於發布新Release或合併Draft PR；完整產品其餘願望與平台驗收仍逐項保留。
