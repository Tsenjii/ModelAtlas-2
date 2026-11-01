> 2026-10-30通用部署接續：免root前景程序、Docker／Compose及加密備份見 [PORTABLE_DEPLOY.md](PORTABLE_DEPLOY.md) 與 [CLOUD_BACKUP.md](CLOUD_BACKUP.md)。下文保留Git平台原契約；只有實際平台證據才能主張已部署。

# Git 平台部署（Infrlo 等）

## 本輪查核與限制

2026-10-15，使用者提供的 Infrlo 部署頁有 Repository URL、Branch、Build Command、Run Command；畫面中的 Python 命令不適用於本服務。[官方公開 README](https://github.com/infrlo/Infrlo) 宣稱免費方案 512 MB RAM／2 GB storage，但沒有列出 Go SDK 版本、HTTP port、環境變數、HTTPS ingress 或跨 redeploy 的儲存契約。`golang` topic 也不能當作 Go runtime 已確認。

本環境直接開啟首頁與控制台只得到「Site Unavailable / Unable to access this site」，瀏覽器重新整理一次仍相同。這只證明目前此環境無法檢查其控制台，不表示使用者手機也無法使用。下列是**本 repo 已實作、可測試的部署設定**；不虛構平台 UI 欄位，也不宣稱已在 Infrlo 部署。Infrlo 的 Go／ingress／persistent storage 能力仍須以平台實際設定或官方契約確認；2 GB storage 不自動代表重新部署會保留 SQLite。

## 部署頁可填的命令

Repository URL：`https://github.com/Tsenjii/ModelAtlas-2`

Branch：`feature/vps-rebuild-20261014`（`main` 為 Android 主線）。

以下以 **repository root** 為工作目錄；Root Directory 若有此設定，留空或使用 `.`，不要再填 `vps`。

Build Command：

```sh
sh vps/deploy/build.sh
```

Run Command：

```sh
sh vps/deploy/run.sh
```

若平台已將 Root Directory 設為 `vps`，改成 `sh deploy/build.sh` 與 `sh deploy/run.sh`。兩個腳本都從自身位置找程式，不靠前一個 shell 的 `cd`。build 需要 Go 1.26+（CI 使用 1.26.4），輸出 `vps/modelatlas-vps`；純 Go／CGO 關閉，沒有 Python requirements，也沒有正式 Node runtime。編譯限制為兩個 Go 執行緒與單個套件 compiler，預設 build soft limit 256 MiB；這些不是 builder RSS 硬上限。

若平台只有 Python image 且沒有 `go`，build 會明確失敗。必須使用提供 Go 的 builder，或由平台支援的方式提供對應 Linux 架構的預編譯 binary；不能只改 run command 就聲稱已解決。GitHub Actions 提供含版本／校驗／安裝更新工具的雙架構完整套件，供一般 VPS 使用；沒有新增未驗證的下載器或平台 API。

## 平台環境變數

秘密應填入平台的私密環境變數設定，不要塞入 Build／Run Command、Git 或聊天。

| 變數 | 值／用途 |
|---|---|
| `MODELATLAS_DEPLOYMENT` | `platform`，明確啟用平台 ingress 模式 |
| `MODELATLAS_PUBLIC_ORIGIN` | 實際對外的 `https://你的網域`，只填 origin；不帶路徑、query、帳密 |
| `MODELATLAS_ADMIN_TOKEN` | 固定、獨立的隨機管理密碼，至少 24 字元；HTTP Basic 使用者名稱為 `admin` |
| `MODELATLAS_MASTER_KEY` | 固定的 32 bytes 隨機值之 Base64，例以自己的安全終端執行 `openssl rand -base64 32`；首次設定後備份，不隨啟動重生 |
| `MODELATLAS_DB` | **平台確定會持久保存**之目錄下的絕對檔案路徑；例如平台實際掛載 `/data` 時才填 `/data/modelatlas.db` |
| `PORT` | 平台若自動提供便沿用；沒有時本程式預設 `8787`，平台入口也須轉到相同埠 |
| `MODELATLAS_BUILD_COMMIT` | 可選、僅 build 使用；40 字元來源 commit。一般 git checkout 自動取得，無 Git 的來源封裝未提供時標示開發版本，不冒充正式 commit |
| `GOMEMLIMIT` | 可選；正式服務預設 `192MiB` soft limit |

平台模式預設監聽 `0.0.0.0:$PORT`。一般不要設定 `MODELATLAS_LISTEN`；若明確設定，它優先於 `PORT`，只接受數字 IP 與 1..65535 的埠。環境變數欄位中的 `$PORT` 不一定由 shell 展開，直接留給程式讀 `PORT`。

平台必須只跑**一個 instance**。SQLite 目錄及 DB／WAL／SHM／lock 檔都放同一個可靠的可寫持久目錄；不要把掛載目錄指向 binary 或 git checkout。相同 DB 的第二個 instance 會拒絕啟動，不能用增加 replica 解決。程式可以驗證絕對路徑、建檔及 writer lock，無法從檔案名稱證明平台會跨 redeploy 保留資料。

## HTTPS 入口契約

`platform` 是明確信任平台 HTTPS reverse proxy 的模式。平台必須：

- 以有效 HTTPS 提供上述固定 origin，HTTP 入口導向 HTTPS。
- 將流量轉發至本程式內部埠，保留對外 `Host`，**覆寫**用戶送來的 `X-Forwarded-Proto` 為單一 `https`，不要追加串列。
- 隔離 backend HTTP 埠，不能讓 Internet 直接連到它；程式本身不能替平台設定 firewall。
- 支援長連線與 SSE，避免對串流回應整段 buffering。

程式在管理頁／資源／API 的認證前先檢查設定 origin、Host 及 HTTPS 訊號；缺少 HTTPS 訊號時回 426，不發 Basic challenge；錯 Host 回 421；跨 origin 回 403。符合後仍必須原有 Basic／Bearer 認證、JSON／CSRF guard，HTTPS 回應帶 HSTS。`X-Forwarded-Proto` 本身不是身分驗證；若 backend 可由公網直接連線，客戶端能偽造此 header，因此不能將此模式當作直接開放明文 HTTP 的保護。

僅既有 `GET/HEAD /healthz` 供內部 health probe 免認證，無 Provider／Key／歷史內容。未設定 `MODELATLAS_DEPLOYMENT=platform` 時，即使系統有 `PORT`，仍維持原 `127.0.0.1:8787` 預設並拒絕 public listener。原 systemd 部署方式繼續可用。

## 驗證與備份

`deployment_test.go` 實際執行上述 build/run 腳本及獨立 binary，透過真 HTTPS reverse proxy 驗證登入、Host／Origin／明文拒絕、實際 API 寫入、加密 vault、SIGTERM 正常退出、同 DB／同 master key 再啟動。非法設定須非零退出且不輸出秘密。這是程序／代理契約驗證，**不是 Infrlo 實測**；同 SHA 全套六項 CI 以 PR #20 最新 body 為準。

部署後先看 `/healthz`，再從固定 HTTPS origin 使用 `admin`／管理密碼登入；建立一筆資料並重啟驗證，再依平台方式測試 redeploy 的資料保留。只有重啟保留不能證明 redeploy 保留。永久歷史備份需要停止服務後一致保存 SQLite 與獨立 master key；safe settings export 不包含原始聊天／全部 telemetry／可用的秘密。不要在啟動命令刪 DB 或每次產生新金鑰。

## 後續更新與資料庫升級

沿用全部固定環境值與同一持久磁碟，使用完整六項 CI 通過的 commit 重新部署。schema19 啟動遷移前自動將原 schema 的 SQLite 一致快照與校驗 manifest 保存到 DB 同目錄 `upgrade-backups`；快照失敗、錯 master key 或較新 schema 會拒絕啟動。空 DB 首次安裝不製造空備份。須保留整個目錄及足夠空間，平台不持久保存時仍會遺失資料，程式不能替平台建立 volume。

這不等於平台 binary 自動 rollback：原 root-only env 與所需舊 binary 也必須備份；較舊 binary 需配對舊 schema 快照，不能直接打開新 DB。離線 backup／preflight／restore、systemd 一指令更新與中斷復原見 [UPDATE.md](UPDATE.md)。設定→資料→服務與更新可核對實際服務 commit／schema／vault 狀態；`/healthz` 仍只作不含版本／私密內容的公開 probe。
