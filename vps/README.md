# ModelAtlas VPS（Go）

獨立於 Android 的輕量服務，先完成可部署、可測試的基礎，再逐片追平 Android 功能。

## 執行

需要 Go 1.22 或更新版本：

```sh
cd vps
go test ./...
go build -o modelatlas-vps .
./modelatlas-vps
curl http://127.0.0.1:8787/healthz
```

預設只監聽 `127.0.0.1:8787`，不直接對公網暴露。可透過 `MODELATLAS_LISTEN` 改變監聽位址，但必須先配置 TLS、身分驗證和防火牆。

## 對齊 Android 的開發順序

1. 基礎：SQLite migrations、受限服務設定、secret references、Provider/Account/Key/Group/Endpoint/Operation scope。
2. Providers：新增、檢視、狀態與獨立帳號／憑證管理。
3. Models：Catalog 與 My Models 分離；Unknown 不等於 Free；歷史不可因一次同步失敗消失。
4. Playground：真實 inference、串流、取消、對話歷史、telemetry 與 multimodal。
5. Intelligence：quota、pricing、provenance、recurring-free；P8 research evidence/run/checkpoint。
6. P9/P10：明確授權工具與可回退的研究改進。

目前只有 HTTP 服務、首頁、健康檢查和 Go 測試，**不宣稱**與 Android 功能等價。不得把 Android private key/PAT 複製到 VPS 或 GitHub。不得擅自啟用 fallback、routing 或 gateway。
