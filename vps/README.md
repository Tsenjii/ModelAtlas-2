# ModelAtlas VPS — Go / SQLite

這是與 Android 同 repo、**獨立維護**的 VPS Web 服務。源自 Android 已驗收的產品契約（`docs/DECISIONS.md`、`docs/PRODUCT_SPEC.md`、`docs/RESEARCH_POLICY.md`），不是把 Android APK 改名當伺服器，也不會憑空聲稱全部同等功能。

## 架構及安全

- Go 1.26、純 Go SQLite driver、單一 Linux binary（AMD64 / ARM64），沒有常駐 Node.js、沒有 Java Runtime。
- SQLite WAL + 單連線順序化 + FK + 交易；Provider、Account、Credential、Catalog、My Models、Sessions、Messages、Telemetry、研究 Candidate/Evidence/Run、行動審核與版本化 Recipe 皆落地。
- 單一明確 Provider × Account × Credential × Model × Group × Endpoint scope；不自動換 Key、不 Fallback、不提供無授權的 API Gateway。
- AES-256-GCM 加密 API Key，master key **僅環境變數**；資料庫不寫 plaintext key，GET API 不回傳 key。管理端 HTTP Basic/Bearer 使用長隨機密碼，服務強制 loopback 監聽。
- Web UI 僅 Playground／Providers／Models 三個底部分頁，設定在右上角；繁中、teal、深淺色、48px 觸控與窄螢幕適配。

## 目前可用功能

- Quick / Advanced 新增、可恢復 Review、批次匯入、多 Account / Key、啟用／停用、加密的自訂 header/query、Key version／rollback。
- Catalog 與 My Models 分開；canonical/alias、多維分類、搜尋、favorite/watchlist、hidden/archive、詳情與來源／歷史；加入後自動排入 durable metadata enrichment。
- 真實 Chat Completions：文字與 reasoning SSE、完整對話、停止與新對話；圖片／PDF／WAV／MP3 每種一份且各限 4 MiB，exact-scope 能力重新驗證。
- Manual Test / Compare Live 都走同一推論與 telemetry；預設 7d，可切 1h/24h/30d/All；取消排除 My Uptime，rolling-60s 用量、TTFT/TTFB/TPS 各自有語義。
- New API rc.25 status/notice/pricing/PAT account/inference token/perf；保留精確價格／group 倍率／版本／來源，nominal price 不冒充 effective charge。
- Dedicated adapter registry、LMSpeed 固定正主單頁匯入與持久化 budget/cache；官方、站方、本地、外部不互相覆蓋。
- Check-in 按設定時區本地日期；通知預設 OFF；無 Key 的設定匯出與原子還原（credential 必須重填再啟用）。
- 研究 candidate/evidence、可逆 action preview/confirm/undo、recipe version/rollback 保留既有 API。

功能核對、修復清單、Android 主線本身尚未完成的能力，以及測試限制見 [PARITY.md](PARITY.md)。多模態 raw 附件不入 DB；服務重啟後，含媒體的舊對話必須另建新對話。沒有支援的 transport 會明確拒絕，不能藉設定混送 protocol。

## 本機測試與編譯

```sh
cd vps
go mod download
go test -mod=readonly -race ./...
go vet ./...
CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o modelatlas-vps .
```

## VPS 部署（Debian / Ubuntu，systemd）

1. 從 GitHub Actions `VPS Go Build and Integration` 下載對應 Linux AMD64／ARM64 artifact；也可以自行 `go build`。
2. 使用 VPS 內安全的方式建立 root-only `/etc/modelatlas/modelatlas.env`，例如：

   ```sh
   sudo install -d -m 0700 /etc/modelatlas
   sudo sh -c 'umask 077; : > /etc/modelatlas/modelatlas.env'
   ```

   在只由 root 可讀的編輯器內設定：

   ```text
   MODELATLAS_ADMIN_TOKEN=<獨立、足夠長的隨機密碼>
   MODELATLAS_MASTER_KEY=<32 bytes 隨機值的 Base64>
   ```

   `openssl rand -base64 32` 可用來產生 32 bytes 隨機金鑰的 Base64；不要放進 Git、CI Log、聊天或 URL。

3. 執行 `sudo sh vps/deploy/install.sh ./modelatlas-vps-linux-amd64`。
4. `curl http://127.0.0.1:8787/healthz` 驗證；以 SSH port forward `ssh -L 8787:127.0.0.1:8787 user@vps` 連線，再瀏覽 `http://127.0.0.1:8787/`，用 HTTP Basic `admin` + 上述管理密碼登入。
5. 如需公開網域，必須透過認證完整的 HTTPS reverse proxy / access control，轉送本機 `127.0.0.1:8787`，**切勿改為直接開放明文 HTTP 公網監聽**。

部署需要使用者的 VPS SSH 權限，本 GitHub PR 的 CI 只能證明編譯／測試，**不能冒充已遠端部署**。

## 備份

SQLite 資料庫、WAL、SHM 與 `MODELATLAS_MASTER_KEY` 必須有相互一致、分開保管的備份。請使用 SQLite backup API 或關閉服務後才複製 DB，避免不一致。丟失 master key 後無法復原 encrypted API Key。

## API 摘要

`GET /healthz` 公開只回服務健康；其餘 API 必須管理認證。路徑前綴 `/api/v1`：

- Providers / Accounts / Credentials：`GET, POST /providers`、`/accounts`、`/credentials`
- Models：`GET, POST /models`、`POST /models/sync`、`GET, POST, DELETE /my-models`
- Playground：`GET, POST /sessions`、`GET /messages?session_id=`、`POST /infer`（text/event-stream）
- Telemetry：`GET /telemetry`
- Intelligence：`GET /models/detail`（完整七維 selected scope）、`POST /intelligence/sync`、`GET /intelligence/history`
- Models 操作：`POST /models/{metadata,capabilities,facts,lifecycle,test,compare}`
- Onboarding：`GET /onboarding`、`POST /onboarding/{preview,retry,confirm,import}`
- 憑證：`POST /credentials/{toggle,rotate}`；自訂秘密只存在 encrypted vault
- New API：`POST /newapi/{status,notice,pricing,account,token,summary,performance}`、`GET /newapi/snapshots`
- LMSpeed：`POST /lmspeed/{key,sync}`、`GET /lmspeed/models`
- 設定：`GET,POST /settings`、`GET,POST /checkins`、`GET /backup`、`POST /backup/restore`
- Media transport：`POST /infer/media`（與 Android 已註冊 image-generation/edit/audio transcription 相同；結果 UI 尚未驗收）
- 停止：`POST /infer/cancel`，body 為 `session_id`
- Research：`GET, POST /research/candidates`、`/research/evidence`、`/research/runs`、`GET /research/eligibility`、`POST /research/probe`
- Assistant：`POST /assistant/preview`、`/assistant/confirm`、`/assistant/undo`
- Recipes：`GET, POST /recipes`、`POST /recipes/rollback`

勿在 API 網址 query string 傳遞 Token／Key。

## 路徑與 Group

Provider Config 的自訂 endpoint path 以 origin-root 為基準，例如 `/v1/chat/completions`；留空則沿用 Base URL + adapter default。所有攜带憑證的 URL 保持同一 HTTPS origin，拒絕 redirect。Group 在 Credential 建立時明確綁定，只有相同 group 的選定對話可發出；不附加杜撰的 `X-Group` header。

## Browser CI

Node.js / Playwright 僅供開發與 CI，VPS 產品 runtime 不需要 Node.js。測試執行正常 `Server.Handler`、SQLite 與 TLS fixture provider，實際操作 Review／model／chat／hide/restore，不以 HTML 字串或 mock DOM 當 browser 驗收。
