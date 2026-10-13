# ModelAtlas VPS — Go / SQLite

這是與 Android 同 repo、**獨立維護**的 VPS Web 服務。源自 Android 已驗收的產品契約（`docs/DECISIONS.md`、`docs/PRODUCT_SPEC.md`、`docs/RESEARCH_POLICY.md`），不是把 Android APK 改名當伺服器，也不會憑空聲稱全部同等功能。

## 架構及安全

- Go 1.23、純 Go SQLite driver、單一 Linux binary（AMD64 / ARM64），沒有常駐 Node.js、沒有 Java Runtime。
- SQLite WAL + 單連線順序化 + FK + 交易；Provider、Account、Credential、Catalog、My Models、Sessions、Messages、Telemetry、研究 Candidate/Evidence/Run、行動審核與版本化 Recipe 皆落地。
- 單一明確 Provider × Account × Credential × Model × Group × Endpoint scope；不自動換 Key、不 Fallback、不提供無授權的 API Gateway。
- AES-256-GCM 加密 API Key，master key **僅環境變數**；資料庫不寫 plaintext key，GET API 不回傳 key。管理端 HTTP Basic/Bearer 使用長隨機密碼，服務強制 loopback 監聽。
- Web UI 僅 Playground／Providers／Models 三個底部分頁，設定在右上角；繁中、teal、深淺色、48px 觸控與窄螢幕適配。

## 目前可用功能

1. 真正的 Provider/Account/加密憑證 API，手動 Catalog 或向選定供應商執行 `GET /models` 實際同步；不同 Provider 的同名模型保持獨立。
2. My Models 僅明確加入；一次同步錯誤不刪 last-good Catalog／My Models。
3. Playground 已連到 HTTPS Chat Completions SSE 實際上游、持久化對話、建立 Telemetry；不把 User Cancel 算成功。
4. Research candidate/evidence/run、手動線索，真實 Catalog probe 只認證模型存在；缺 recurring quota 與 Taiwan 證據一律 Grey。RPD≥100 且 TPD≥1M 為同時門檻；AA 動態 top4 *尚需獨立正式來源接線*。
5. Assistant 提供本地允許清單的 My Models Preview → 明確 Confirm → Undo；**尚不包含 LLM 規劃器**。
6. Provider Recipes 限制設定欄位、保留每版歷史，明確確認才可 Rollback。

**限制：** 尚未追平 Android 所有 New API/Dedicated Adapters、Vision/PDF/Audio、Tool Calling、圖片生成、完整 quota/pricing、LMSpeed、自動 Crawler、實際 AA 排名匯入、完整 LLM Assistant、真實反思代理。**不要以已有資料表或接口偽稱功能已完成。** Group 非空而無已驗證的 provider adapter 時 HTTP 前拒發，不會悄悄切到預設 group。

## 本機測試與編譯

```sh
cd vps
go mod tidy
go test -race ./...
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
- Research：`GET, POST /research/candidates`、`/research/evidence`、`/research/runs`、`GET /research/eligibility`、`POST /research/probe`
- Assistant：`POST /assistant/preview`、`/assistant/confirm`、`/assistant/undo`
- Recipes：`GET, POST /recipes`、`POST /recipes/rollback`

勿在 API 網址 query string 傳遞 Token／Key。
