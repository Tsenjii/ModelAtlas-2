# ModelAtlas 部署、雲端備份與還原說明

適用一般 Linux VPS、免 root 主機及容器平台。Muse 或其他部署代理都可以照本文件執行；沒有 Muse 專屬版本。更新日期：2026-10-30。

目標是交付能登入的 HTTPS 網址、重啟仍保留的資料，以及確實能下載還原的加密備份。需要使用者登入雲端帳戶時，交給使用者完成該次授權，再繼續其他步驟。

## 1. 先拿對套件

本次安裝包使用已驗證的固定程式來源：

```text
Repository: https://github.com/Tsenjii/ModelAtlas-2
Commit: 00b6a67ef112c86b750a588503de7b05bfa0bb12
資料庫格式: 38
Linux 套件 Actions run: 41042996530
```

**本文件及安裝包是已測候選的自部署交付，不是新公開 Release。舊公開 `v0.8.5-preview.1` 沒有這批部署／備份命令，不能拿它替代。** 不直接下載會變動的 `main` 或用「latest」猜版本。後續改用新版本時，重新記錄來源與雜湊。

安裝包內有本說明、`DEPLOYMENT_TRIAL.md`、兩種架構的原始完整套件、校驗檔及 `SOURCE.json`。兩個 tar.gz 保持原 CI bytes；說明放在它們外面。

| 主機結果 `uname -m` | 選用套件 |
| --- | --- |
| `x86_64` | `modelatlas-vps-linux-amd64.tar.gz` |
| `aarch64`、`arm64` | `modelatlas-vps-linux-arm64.tar.gz` |
| 其他架構、非 Linux | 先換成支援的 Linux 執行環境；不能直接執行這兩個 binary |

解開安裝 ZIP，進入它的根目錄。以下是 **Linux Bash** 範例，變數沿用同一個終端；換終端時重新設定實際絕對路徑。`atlas.example.com` 是範例，須換成真正能控制的 HTTPS 網址。首次解開到新的程式目錄；已有 `runtime` 時先核對既有部署，別重解覆寫正在執行的binary，更新改用另一個版本目錄。

```bash
set -eu
umask 077
if [ "$(uname -s)" != Linux ]; then
  printf '請在Linux執行環境操作，不能直接執行Linux binary\n' >&2
  exit 1
fi
ATLAS_DELIVERY="$(pwd)"
ATLAS_SOURCE=00b6a67ef112c86b750a588503de7b05bfa0bb12
case "$(uname -m)" in
  x86_64) ATLAS_ARCH=amd64 ;;
  aarch64|arm64) ATLAS_ARCH=arm64 ;;
  *) printf '不支援的 CPU 架構\n' >&2; exit 1 ;;
esac

sha256sum -c SHA256SUMS.txt
mkdir "$ATLAS_DELIVERY/runtime"
tar -xzf "$ATLAS_DELIVERY/packages/modelatlas-vps-linux-$ATLAS_ARCH.tar.gz" \
  -C "$ATLAS_DELIVERY/runtime"
ATLAS_BUNDLE="$ATLAS_DELIVERY/runtime/modelatlas-vps-linux-$ATLAS_ARCH"
ATLAS_BIN="$ATLAS_BUNDLE/bin/modelatlas-vps-linux-$ATLAS_ARCH"
(cd "$ATLAS_BUNDLE" && sha256sum -c SHA256SUMS.txt)
"$ATLAS_BIN" ops bundle-check --directory "$ATLAS_BUNDLE"
"$ATLAS_BIN" version
```

最後的 commit 必須與上方固定來源一致。不要把 ARM64 套件放進 AMD64 映像，或反過來。

如果只拿到本文件，沒有安裝 ZIP，可在已登入 GitHub CLI 的環境取得相同 Actions 套件；下載到新的空目錄：

```bash
gh run download 41042996530 --repo Tsenjii/ModelAtlas-2 \
  --name "modelatlas-vps-linux-$ATLAS_ARCH" --dir /absolute/empty/download-directory
```

該 artifact 同時有 standalone binary、完整 tar.gz 及其 `.sha256`；部署應解開完整 tar.gz。先驗外層 `.sha256`，再驗包內 `SHA256SUMS.txt`、`bundle-check` 和 `version`。Actions 有保存期限；安裝 ZIP 保存了套件，不依賴臨時下載網址。若必須重建，請從固定 commit checkout，以 Go 1.26.4／原 go.sum 執行 `vps/deploy/package.sh`，並保存新的建置雜湊；不要將重建檔冒充原 CI bytes。

## 2. 由執行環境選路徑

先確認 CPU、可用磁碟、程序常駐方式、持久目錄和 HTTPS 入口。程式及備份暫存必須放得下；磁碟需容納正式 DB／WAL、一致快照、回讀副本及更新前副本，不能把「2 GB」當成所有資料量都夠。

| 條件 | 採用 |
| --- | --- |
| 有 Docker Compose 和自己的網域，80／443 可用 | 路徑 A：容器＋Caddy |
| 有 Docker，已有同主機 HTTPS 代理 | 路徑 A 改用 loopback overlay |
| 雲端平台提供容器 ingress | 使用同一容器；持久 volume 掛 `/data`，私有 upstream 連 app 的 8787 |
| 無 Docker／無 root，但能跑 Linux binary | 路徑 B：portable binary＋平台 supervisor／ingress |
| 只有靜態網站、短命工具沙盒或不保留磁碟 | 可作短期測試；另找符合契約的後端，不能回報已具備長期常駐與持久保存 |

512 MiB 是原服務測試目標；已測服務 PID 峰值約198 MiB，不含 OS、代理、rclone及磁碟快取。正式目標的總記憶體要另量測。優先使用預編譯套件，不在小記憶體主機上編譯 Go。

## 3. 路徑 A：Docker Compose

### 3.1 固定專案與 HTTPS 設定

在主機建立只含部署選項的檔案，路徑可自行選；其中不放管理密碼、master key或OAuth token。

```bash
ATLAS_COMPOSE_ENV="$HOME/.config/modelatlas/deployment.env"
mkdir -p "$(dirname "$ATLAS_COMPOSE_ENV")"
chmod 700 "$(dirname "$ATLAS_COMPOSE_ENV")"
cat > "$ATLAS_COMPOSE_ENV" <<'ENV'
MODELATLAS_PUBLIC_ORIGIN=https://atlas.example.com
MODELATLAS_SOURCE_COMMIT=00b6a67ef112c86b750a588503de7b05bfa0bb12
MODELATLAS_BACKUP_DESTINATION=
ENV
chmod 600 "$ATLAS_COMPOSE_ENV"
```

把上方網址換成實際網域，DNS指向主機，確認80／443由這個部署使用。已有同名專案或部署檔時，沿用並檢查原設定，別覆蓋既有值。

定義後續共用的命令。`-p modelatlas` 固定專案名稱；更新時保留相同名稱、Docker host和volume。

```bash
atlas() {
  docker compose -p modelatlas --env-file "$ATLAS_COMPOSE_ENV" \
    -f "$ATLAS_BUNDLE/deploy/compose.yaml" \
    -f "$ATLAS_BUNDLE/deploy/compose.https.yaml" "$@"
}
atlas up -d --build
atlas ps
atlas exec -T app modelatlas-vps ops ready \
  --env-file /data/state/config/modelatlas.env
```

容器使用非root UID/GID `10001:10001`，root filesystem唯讀。第一次由 initialize服務建立私密設定，成功後app才啟動；named volume保存 `/data`。Caddy的憑證另有持久volume。

如果已有同主機HTTPS代理，將上面函式的第二個 `-f` 改成 `compose.loopback.yaml`，代理upstream設為 `127.0.0.1:8787`；不要同時啟動本包Caddy占用80／443。平台自帶ingress則依實際網路接私有container upstream，保留本包相同Go產品與資料契約。

HTTPS代理須保留真正Host、覆寫 `X-Forwarded-Proto: https`、移除不可信 `Forwarded`／`X-Forwarded-Host`，支援SSE並關閉串流緩衝。範本為包內 `deploy/modelatlas-proxy.caddy`。對外只開HTTPS入口，明文backend隔離在loopback／私有容器網路。

### 3.2 登入與資料位置

| 項目 | 容器內位置 |
| --- | --- |
| 管理帳號 | `admin` |
| 管理密碼、固定master key、原資料路徑 | `/data/state/config/modelatlas.env`，0600 |
| 正式資料庫 | `/data/state/data/atlas.sqlite` |
| 備份設定 | `/data/state/config/backup.json`，配置後才存在 |
| 專用rclone設定 | 本說明使用 `/data/state/config/rclone.conf` |
| 備份暫存與本機工作帳本 | `/data/state/backups` |

管理密碼只由使用者在私密終端／平台的安全檔案介面查看。代理的部署結果報告只列憑證保管位置，不貼完整env、token或key。不要在容器重建時重新生成master key。

`atlas stop app`／重新啟動應保留資料。日常更新不要執行 `docker compose down --volumes`、刪volume或清除資料夾。

## 4. 路徑 B：免root binary／平台部署

先在**平台確認會持久保存的掛載路徑**建立父目錄，以下 `/persistent/modelatlas` 必須替換成實際位置。程式目錄與資料目錄分開。

```bash
ATLAS_STATE=/persistent/modelatlas/state
ATLAS_ORIGIN=https://atlas.example.com
mkdir -p "$(dirname "$ATLAS_STATE")"
chmod 700 "$(dirname "$ATLAS_STATE")"

"$ATLAS_BIN" portable init --directory "$ATLAS_STATE" \
  --public-origin "$ATLAS_ORIGIN" --listen 127.0.0.1:8787

GOMEMLIMIT=192MiB "$ATLAS_BIN" portable run --directory "$ATLAS_STATE"
```

這是前景程序；先看正常啟動，再把**實際binary絕對路徑與同一state路徑**交給平台supervisor／systemd／其他程序管理器。單純在工具終端啟動、`nohup`後離開或暫時取得網址，不證明重建後會常駐。

同主機反代可用loopback。平台ingress必須從其他容器連入時，第一次init改成 `--listen 0.0.0.0:8787`，或平台指定的數字埠；必須隔離外部直連backend。已有state不重跑init來換key；網址／埠改動由管理者在原私密設定檔調整，停止舊程序後重啟並重驗。

初始化會建立 `config/modelatlas.env`、`data`與`backups`。同設定重跑init保留原identity；有資料卻缺設定時應找回原設定及master key。env檔由程式當資料解析，**不要 `source` 或執行它**。

另一終端可執行：

```bash
"$ATLAS_BIN" ops ready --env-file "$ATLAS_STATE/config/modelatlas.env"
```

原systemd完整安裝、更新及自動回復另見套件內 `UPDATE.md`。不要在沒有root／systemd的平台硬套 `deploy/install.sh`。

## 5. 連接自己的雲端備份

### 5.1 先決定目的地與復原材料

正式SQLite留在本機持久磁碟；備份是一致快照加密後上雲，**不是把運作中的SQLite放進雲端同步掛載**。

在密碼管理器或安全離線位置，另存下列復原材料：

1. 原 `modelatlas.env`，尤其原master key與管理密碼。
2. 專用 `rclone.conf`，以及crypt原密碼／可選salt。
3. 目的地 `backup.json`、雲端帳戶／資料夾、最近成功備份ID與SHA256。

它們不放進本安裝包、Git或同一份DB備份目錄。DB雲端版本不包含master key，也不包含原始附件或本機排程授權帳本；單有DB不能救回遺失的key。只在真正完成另存後使用下方 `--confirm-key-recovery`。

### 5.2 rclone設定（Google Drive範例，其他backend先實測）

容器內已提供rclone；binary主機先安裝可信rclone。只為ModelAtlas建立一份專用設定，不借用同機其他服務的帳號。

容器：

```bash
atlas exec app sh -c 'umask 077; rclone --config /data/state/config/rclone.conf config'
```

binary：

```bash
umask 077
rclone --config "$ATLAS_STATE/config/rclone.conf" config
```

在互動設定中：

| remote | 選項 |
| --- | --- |
| `cloud` | 使用者選定的雲端儲存；Google Drive選 `drive`，由使用者完成OAuth並確認權限與帳戶 |
| `encrypted` | 類型 `crypt`；remote填 `cloud:ModelAtlasEncrypted` 或使用者選定的專用子目錄；檔名加密選 `standard`、目錄名加密開啟、檔案內容加密保持開啟；設定並另存密碼／salt |

Google Drive可設定選定資料夾的 `root_folder_id`（資料夾網址最後一段ID）。它是路徑設定，不會把OAuth權限自動縮小成僅該資料夾。`drive.file`有可見性限制；若使用它，先確認目標資料夾確實能存取，遇權限不足不要自行擴權。雲端資料夾保持私有，不必開公開分享。

若主機沒有瀏覽器，依rclone提示選不在該機開瀏覽器，並在使用者有瀏覽器的電腦執行**它實際顯示的** `rclone authorize ...` 指令；授權結果透過私密終端／安全檔案傳回。也可在自己的電腦完成專用設定後安全複製。這個登入步驟需要使用者操作，不能用假token略過。

設定檔維持一般私密INI、0600，父目錄0700；目前產品需要讀取並驗證crypt設定，不接受無法檢查的整份加密INI。rclone的obscured密碼不是設定檔的安全加密。

### 5.3 告訴ModelAtlas使用此目的地

容器（保留runtime使用者10001）：

```bash
atlas exec -T app modelatlas-vps backup configure \
  --env-file /data/state/config/modelatlas.env \
  --destination-file /data/state/config/backup.json \
  --rclone-config /data/state/config/rclone.conf \
  --remote encrypted --prefix ModelAtlas \
  --spool-directory /data/state/backups \
  --label 我的加密雲端備份 --confirm-key-recovery
```

將主機 `deployment.env` 的 `MODELATLAS_BACKUP_DESTINATION` 改成 `/data/state/config/backup.json`，再執行 `atlas up -d`，讓app重建並讀取目的地。`configure`不覆寫既有目的地，也不啟用排程。已有設定時先讀原設定，不反覆重建crypt密碼或改remote指向。

binary：

```bash
"$ATLAS_BIN" backup configure \
  --env-file "$ATLAS_STATE/config/modelatlas.env" \
  --destination-file "$ATLAS_STATE/config/backup.json" \
  --rclone-config "$ATLAS_STATE/config/rclone.conf" \
  --remote encrypted --prefix ModelAtlas \
  --spool-directory "$ATLAS_STATE/backups" \
  --label 我的加密雲端備份 --confirm-key-recovery
```

停止舊程序，將supervisor的啟動命令改成以下形式，再啟動同一個instance：

```bash
GOMEMLIMIT=192MiB "$ATLAS_BIN" portable run --directory "$ATLAS_STATE" \
  --backup-destination "$ATLAS_STATE/config/backup.json"
```

### 5.4 第一次備份與排程

登入 → 右上設定 → 資料 → 加密雲端備份：

1. 重新讀取，核對目的地及金鑰另存狀態。
2. 按「立即備份目前資料」，等待快照、加密上傳、**重新下載驗證**全部完成。只有最終SUCCESS／已回讀驗證算成功；記下ID、時間、SHA256。
3. 完成一次隔離還原演練後再啟用排程。可設15～10080分鐘；一般可先按需求選每日或每小時。小磁碟可在成功回讀後不另留本機快照；正式DB、雲端版本與工作紀錄仍保留。
4. 重新讀取確認已保存的排程，再重啟服務並核對設定。程式必須真的常駐，排程才能到期執行；停機期間不保證準點。

資料與雲端版本沒有自動清除政策；永久歷史仍會長大。備份失敗、磁碟滿或權限改變時保留原資料與失敗紀錄，先修原因；不要刪DB／換key／關驗證來消除錯誤。若保存回應遺失，先重新讀取原ID，不另建一份重送。

雲端帳戶、crypt密碼、目錄或master key變更會使原排程綁定失效，需要重新確認。金鑰與雲端目標不得偷偷替換。

## 6. 下載、隔離還原與換機

### 6.1 先下載驗證，不碰正式DB

選一個已成功的完整ID，例如 `backup-20261030-manual`。`--output`必須是**尚不存在**的私密絕對路徑，父目錄須存在。備份ID、路徑是範例，請換成實際值。

容器：

```bash
atlas exec -T app modelatlas-vps backup fetch \
  --env-file /data/state/config/modelatlas.env \
  --destination-file /data/state/config/backup.json \
  --id backup-20261030-manual --output /data/recovered-20261030
```

binary：

```bash
"$ATLAS_BIN" backup fetch \
  --env-file "$ATLAS_STATE/config/modelatlas.env" \
  --destination-file "$ATLAS_STATE/config/backup.json" \
  --id backup-20261030-manual --output /private/recovered-20261030
```

確認 `verified:true`、`database_replaced:false` 與SHA256。輸出目錄應完整保留 `atlas.sqlite`、`atlas.sqlite.json`、`backup.json` 三檔，別只搬其中一檔。

若帳本也遺失，以相同私密crypt設定執行 `rclone --config /private/rclone.conf lsf encrypted:ModelAtlas --dirs-only` 找回完整ID；此處的rclone設定路徑須換成自己那份。原始雲端檔名會被加密，不能直接用Drive頁面名稱猜版本。

### 6.2 還原演練與真正切換

先做隔離演練。容器可保留正式app運作，只把輸出指向全新的演練目錄；`--database` 明確指定演練DB，不替換原DB：

```bash
atlas exec -T app sh -c 'umask 077; mkdir /data/restore-check-20261030'
atlas exec -T app modelatlas-vps ops restore \
  --env-file /data/state/config/modelatlas.env \
  --database /data/restore-check-20261030/atlas.sqlite \
  --snapshot /data/recovered-20261030/atlas.sqlite
```

binary用相同步驟：在自己的私密父目錄建立全新演練目錄，原env提供原master key，`--database` 指向演練DB、`--snapshot`指向下載的快照。restore驗完整快照／schema／vault；另外核對復原DB的非敏感筆數與驗收註記。若啟動隔離服務查看畫面，另複製私密env，修改DB、maintenance、暫存與監聽路徑／埠，不對外、不啟用排程。演練成功不等於已切換正式服務。

真正還原時先停止正式app及其自動重啟來源，確保沒有writer；保留原資料與原套件，再執行：

容器（停止app後，以同一volume執行一次性離線命令）：

```bash
atlas stop app
atlas run --rm -T --no-deps --entrypoint modelatlas-vps app ops restore \
  --env-file /data/state/config/modelatlas.env \
  --snapshot /data/recovered-20261030/atlas.sqlite
atlas up -d
atlas exec -T app modelatlas-vps ops ready \
  --env-file /data/state/config/modelatlas.env
```

binary（先停止自己的supervisor）：

```bash
"$ATLAS_BIN" ops restore --env-file "$ATLAS_STATE/config/modelatlas.env" \
  --snapshot /private/recovered-20261030/atlas.sqlite
# 用原supervisor及相同state／key重新啟動，再執行ops ready。
```

restore會保留被替換的舊DB／WAL／SHM，不用刪資料來騰位置。登入後核對完整歷史、資料版本與vault狀態，再明確確認需要的備份排程。

**整台主機遺失時**：先拿回原私密env、crypt設定／密碼和目的地設定，在新主機準備相同路徑（容器可保留 `/data/state`）。在啟動initialize／app前先放回原env，不能讓全新初始化產生另一把key。恢復所需檔案0600、目錄0700、容器擁有者10001；必要時改正新主機的絕對路徑，重新建立目的地描述。再fetch、離線restore、啟動並驗收。新機不要直接複製舊工作帳本來取得自動傳輸權限。

## 7. 更新與回退

更新保留資料路徑、固定key、Docker project-volume、雲端目的地與原復原材料。先拿到**新版本已驗證的完整套件與來源SHA**，驗外層／內層checksum與version；原套件／容器image也留下。

1. 用舊版建立一致快照並確認雲端備份已回讀。binary範例：

   ```bash
   "$ATLAS_BIN" ops online-backup --env-file "$ATLAS_STATE/config/modelatlas.env" \
     --output "$ATLAS_STATE/backups/pre-update-20261030.sqlite"
   ```

2. 停止舊app。以新binary對原DB的**副本**試跑遷移：

   ```bash
   /absolute/new-bundle/bin/modelatlas-vps-linux-amd64 ops preflight \
     --env-file "$ATLAS_STATE/config/modelatlas.env" \
     --database "$ATLAS_STATE/backups/pre-update-20261030.sqlite" \
     --output "$ATLAS_STATE/backups/preflight-20261030.sqlite"
   ```

   `--database` 必須指定上一個步驟產生、帶有 `.json` manifest的已驗快照，不能直接指定沒有快照manifest的正式DB。換成實際架構與全新輸出路徑。容器對應先在舊app執行online-backup，停止app，保留舊image；切到新bundle／新source設定並build後，以 `atlas run --rm -T --no-deps --entrypoint modelatlas-vps app ops preflight --env-file /data/state/config/modelatlas.env --database /data/state/backups/pre-update-20261030.sqlite --output /data/state/backups/preflight-20261030.sqlite` 作相同步驟。

3. 副本預檢成功才讓supervisor切到新binary，或用同一project-volume啟動新容器。執行 `ops ready`、重新登入、核對歷史／vault／服務commit／備份狀態及SSE；新前端須重新載入。
4. 若需退回，先停止新版，以**舊binary／舊image**的 `ops restore` 還原更新前快照，再啟動舊版。不能讓舊binary直接開已升級的新schema。systemd管理版沿 `UPDATE.md` 的原子更新／rollback流程。

快照及preflight檔都使用新的名稱，不覆寫既有成功備份；不要重建key、刪volume、清歷史或拿不完整下載作回退。

## 8. 部署完成的驗收與結果

- 實際HTTPS網址可開啟，憑證有效；未登入看不到管理API，登入後能操作。
- `version`、`ops ready`和設定→資料顯示正確commit／schema／vault狀態。
- 建立一份非敏感驗收註記，重啟及重建app後仍存在，資料volume／持久目錄未變。
- 若原本有可用模型，可在使用者同意的正常對話確認SSE逐步到達與停止操作；單一模型故障不拿來假稱部署成功或免費／身份驗證。
- 雲端第一次備份最終SUCCESS；另下載到新目錄驗證，完成隔離restore並找回驗收註記／原歷史。
- 排程已保存、重啟仍可讀到；另列本機state／volume、設定路徑、supervisor、最近備份ID與回退位置。
- 回報「已完成／尚缺條件／尚未實測」以及可用網址、來源SHA、備份目的地標籤；不貼秘密。未取得雲端授權或平台不能常駐時，據實標示尚未完成該項。

## 9. 常見問題

| 現象 | 先處理 |
| --- | --- |
| 426／421／跨站403 | 查HTTPS、實際Host／Origin與代理header；保留認證和Host／Origin檢查 |
| 只有healthz成功，還不能登入 | 檢查對外HTTPS、原管理密碼與認證路徑；healthz不代表全部驗收 |
| 重建後資料不見 | 停止繼續寫入，核對同一project／volume／state及原key，從已驗快照恢復 |
| private permissions／permission denied | 檢查0700目錄、0600設定及runtime UID10001；不改777或讀別的服務憑證 |
| 無法解析目的地或crypt設定 | 核對專用INI、remote／prefix、加密選項、實際rclone路徑與原crypt身分 |
| OAuth過期／權限不足 | 在私密授權流程修復指定帳戶，檢查目的地是否改變；不是刪歷史或換雲端帳戶 |
| 備份失敗、回讀失敗、磁碟滿 | 保留原ID、完整錯誤碼與last-good；處理空間／網路／權限後明確重試，不跳過回讀 |
| 關閉Muse對話後服務停止 | 查平台supervisor與執行生命週期；暫時沙盒網址不算24/7部署 |

## 10. 已驗範圍與來源

固定程式來源已通過同源十一項必要CI及產物稽核，含原生AMD64／ARM64容器、持久volume、Caddy／TLS、SSE、完整歷史及真rclone crypt回讀還原。這不是已連上使用者Muse／雲端帳戶；真環境依上方清單驗收。本機完整回歸仍有600秒逾時紀錄，獨立CI同源697結果完成於598.426秒；不隱藏此限制，也不要求在小主機上跑開發測試才能安裝。

工程證據：[固定來源Actions](https://github.com/Tsenjii/ModelAtlas-2/actions/runs/41042996530)、[完整驗證紀錄](https://github.com/Tsenjii/ModelAtlas-2/blob/e818faf4e17aade58bc5a14a348142d0e9f05b90/docs/verification/20261030/assistant-mapping-baseline.json)。本輪不發布新公開Release、不合併PR；整體產品及獨立review狀態另見專案狀態文件。

核對於2026-10-30的官方參考：[rclone Google Drive](https://rclone.org/drive/)、[無瀏覽器授權](https://rclone.org/remote_setup/)、[Crypt](https://rclone.org/crypt/)、[Docker Compose](https://docs.docker.com/compose/how-tos/production/)、[gh run download](https://cli.github.com/manual/gh_run_download)。
