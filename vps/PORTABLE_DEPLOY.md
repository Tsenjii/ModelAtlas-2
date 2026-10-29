# 通用部署、異地備份與個人使用網址

2026-10-30（台北），使用者要求先完成 VPS，並明確補充：「不要為了 muse 專屬去弄，而是要把這個功能變成所有東西都可以部署，muse 只是順帶的，只是目前我需要而已」。因此本片為 **VPS-PORTABLE-OPS-016**：統一部署、持久資料、可替換的異地備份及 HTTPS 入口；Muse 只是首個目標環境。

這是當前工作契約，不是所有平台已實測或新部署工具已完成的聲明。現有 Go／SQLite 產品不因主機廠商而分叉，原全部功能、測試、工作量和 timeout 保留。

## 一致的部署契約

| 部分 | 通用要求 | 平台相關設定 |
|---|---|---|
| 執行 | 同一份 Go 後端，單一寫入程序，正常接收 SIGTERM | CPU 架構、啟動命令、程序管理方式 |
| 資料 | 固定本機 DB 路徑，可靠檔案鎖，持久保存 DB／WAL／SHM／升級快照 | 持久磁碟或 volume 掛載路徑與 UID／GID |
| 秘密 | 固定管理密碼及 master key，私密環境設定，更新不重新產生 | 平台 secret／私密設定檔的注入方式 |
| 入口 | 有效 HTTPS、固定 origin、認證、Host／Origin 檢查及 SSE | 平台 ingress、自有反向代理或允許的固定通道 |
| 備份 | 一致快照、加密、版本與雜湊、只新增保留、重新下載及隔離復原 | 雲端硬碟或其他儲存的已授權目的地 |
| 維運 | 重啟保留、升級前預檢、錯誤可見及還原 | systemd、使用者程序管理、容器或平台 supervisor |

任何平台只需符合這份契約並提供相應設定，不以平台名稱決定產品行為，不硬編碼 Muse 路徑、帳號、token、網域或程序。依實際能力選部署方式，不以「能放靜態網頁」當成能執行 Go／SQLite／背景工作的證明。

| 環境 | 共用產品入口 | 仍需該環境證據 |
|---|---|---|
| Debian／Ubuntu VPS + systemd | 完整架構套件及現有 install／update／recover | 目標機重啟、外部 HTTPS、異地備份／還原 |
| 無 root 的 Linux VM／容器／雲端電腦 | 預編譯 binary、相同環境設定及前景程序 | 程序常駐、持久目錄、權限、允許的對外入口 |
| Git 建置／容器雲平台 | 相同 Go build／run 與平台 HTTPS 契約 | Go builder 或正確 binary、單 instance、持久 volume、SSE |
| Muse（目前第一個部署目標） | 使用上述 Linux 方式，不增設產品專屬模式 | 平台允許的程序／網路、實際資料保留及入口 |
| 不支援此執行契約的環境 | 列明缺少條件，提供可用的 backend 部署位置 | 不能把靜態託管或不同執行模型冒稱直接相容 |

現有一般 VPS 流程見 [UPDATE.md](UPDATE.md)，平台 build／run／HTTPS 契約見 [PLATFORM_DEPLOY.md](PLATFORM_DEPLOY.md)。容器範本、免 root 的完整安裝更新／程序管理、自動備份目的地與固定入口的整合仍須實作及驗證；不把文檔或環境變數清單當成功部署。

## 安裝與固定設定

最近已完成產品來源驗收的是 `a76636c8de149c090371c35f83a69fded256dc8c`，實際雙架構套件與同源證據見 [Android Aqua 基線](../docs/verification/20261029/android-aqua-baseline.json)。部署準備基於此來源，保留未驗收的 Mac 015Z 工作；不自動安裝未固定的開發 HEAD，也不更新公開 tag／附件。

每個部署都先核對 CPU 架構、套件 SHA256、`VERSION.json` 和 binary `version`。正式執行不需要 Go、Node 或 Python。資料與私密設定放在版本化程式目錄之外，既有資料先備份；首次產生一次管理密碼及 32-byte master key，以後重啟／更新沿用。

| 設定 | 共用語意 |
|---|---|
| `MODELATLAS_DB` | 已核實的本機持久絕對路徑，不是雲端硬碟掛載 |
| `MODELATLAS_ADMIN_TOKEN` | 固定長隨機管理密碼，登入名稱 `admin` |
| `MODELATLAS_MASTER_KEY` | 固定 32-byte Base64 master key，另行安全備份 |
| `MODELATLAS_DEPLOYMENT` | 外部 HTTPS 入口使用現有 `platform`；本機沿用原模式 |
| `MODELATLAS_PUBLIC_ORIGIN` | 實際固定 HTTPS origin，不帶路徑／query／帳密 |
| `MODELATLAS_LISTEN` | 同機代理限制 loopback；平台隔離容器依實際契約設定 |
| `PORT` | 平台自動注入時沿用，僅在明確平台模式生效 |

入口必須隔離 backend HTTP 埠、保留 Host、覆寫單一 `X-Forwarded-Proto: https` 並支援長連線／SSE；不能只靠偽造 header 開放公網明文。管理密碼及 key 透過平台或自己的安全終端設定，不放 Git、聊天、網址、建置指令或 log。新部署不得覆寫同機其他既有工作。

## 可替換的備份目的地

正式 DB 留在部署主機的可靠本機磁碟運作。雲端硬碟保存完成後的一致快照，不將正在寫入的 DB 放入同步目錄／雲端掛載。SQLite 官方說明[交易中直接複製可能造成不一致](https://sqlite.org/howtocorrupt.html)，以及[一致快照的備份方式](https://sqlite.org/backup.html)。

沿用 binary 既有離線 `ops backup`／`preflight`／`restore`，保留相同 writer lock 和 vault／integrity／schema 驗證。現有備份要求先暫停並停止服務；排程與服務管理透過部署方式接線，不能在包裝腳本中假裝已停機。下列示例路徑由各部署設定提供：

```sh
./modelatlas-vps ops backup --env-file /private/modelatlas.env --output /private/backup-unique.sqlite
./modelatlas-vps ops preflight --env-file /private/modelatlas.env --database /private/backup-unique.sqlite --output /private/preflight-unique.sqlite
```

這兩個命令本身不停止／重啟服務，也不加密或上傳。備份與預檢成功後先恢復服務並驗認證健康，再將已加密的 DB 快照、manifest 及對應版本資訊送到已設定的目的地。備份工具依設定選擇雲端硬碟／其他儲存；不把某一家 provider 寫成產品必要依賴，也不從現有帳號連線推定目的地。

上傳失敗保留本機快照和雲端舊版本，顯示最後成功時間及失敗狀態；不使用雙向同步的刪除語意。完整備份只能保護到最近成功上傳的時間，不宣稱零資料損失。

固定 master key 與必要私密設定另行加密保存，解密備份的方式須獨立於可能遺失的原主機；只有 DB 而遺失 key，無法恢復可用的供應商憑證。Safe settings export 不含全部聊天、研究及可用秘密，不能代替此流程。原始附件目前仍僅當次記憶體使用，不因部署或備份而宣稱全部附件永久保存。

首次交付須從目的地重新下載，核對雜湊並在獨立私密目錄驗 key、完整歷史與 vault，不呼叫供應商推論。正式還原先停止服務、核對版本後才執行：

```sh
./modelatlas-vps ops restore --env-file /private/modelatlas.env --snapshot /private/backup-unique.sqlite
```

此命令替換設定中指向的 DB 並保留舊檔；預檢不可指向正式 DB。退回舊 binary 時配對當時 schema 的快照，不讓舊 binary 直接開新 schema。

## 交付與首個環境

共用流程的完成條件：可重複安裝／更新、正常重啟與升級保留、真外部 HTTPS 登入／SSE、失敗可見、至少一次已加密上傳／重新下載／隔離復原，以及實際定時備份。每個環境分開記錄已驗及缺少條件，不用一次本機或 CI 成功外推所有平台。

Muse 僅作第一個實測目標：[Meta 官方架構](https://research.meta.ai/blog/security-and-safety-for-ai-agents-our-approach-with-muse)說明其為隔離 Linux 環境、由 Sentinel 管理外部網路，不能因此保證 24/7 或任意通道可用。尚未取得目標管理／終端入口、使用者指定備份服務與固定 HTTPS 入口；目前沒有真 Muse 部署、遠端備份或使用網址證據。不繞過平台限制，亦不影響其既有 OMP／圖片流程。

這輪對個人部署的授權不等於公開新 Release 或合併 Draft PR；完整產品及原全部驗收門檻保留。
