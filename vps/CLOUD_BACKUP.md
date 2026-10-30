# 完整資料庫的加密雲端備份

第一次部署到雲端授權、備份、隔離還原及更新的完整操作入口，見 [DEPLOY_AND_BACKUP.md](DEPLOY_AND_BACKUP.md)；交給Muse或其他代理的執行指令見 [MUSE_HANDOFF.md](MUSE_HANDOFF.md)。

VPS-PORTABLE-OPS-016，來源7053所列範圍已工程驗證，2026-10-30。正式入口為設定→資料→加密雲端備份；主機先連接自己的備份目的地，網頁只管理已配置的目的地、排程及工作。此功能不依賴Muse或特定雲端硬碟。

## 保護哪些資料

正式SQLite留在本機持久磁碟。`VACUUM INTO` 建立一致的已提交資料庫快照，包含聊天、研究、遙測、版本及加密vault，不複製正在變動的DB／WAL檔案組合，也不停止現有writer。快照完成後驗integrity、foreign keys、schema、原master key及SHA256，再交給真rclone crypt加密檔案內容／檔名／目錄名。

每份雲端版本只新增，不雙向同步、不刪除、不覆寫不同內容。上傳後重新下載到獨立私密目錄，比對完整三個檔案、識別碼、雜湊、manifest及vault；只有這一步完成才是SUCCESS。程序exit0、只有上傳、HTTP200或讀不到結果都不能當備份成功。

master key不放入同一份備份。必須另行安全保存原應用程式master key、rclone crypt密碼及可選salt；也保存可恢復的管理密碼／目的地設定。只有DB卻遺失上述復原材料，無法恢復可用憑證。使用獨立密碼管理器或安全離線備份，不將秘密放進Git、聊天、指令參數、公開雲端資料夾或log。

原始附件仍沿產品既有規則僅供當次記憶體使用；完整DB備份不冒稱所有附件永久保存。下方「設定匯出」僅為可攜設定，不能代替完整備份。

## 一次連接自己的雲端

主機需可執行rclone。容器映像已提供，binary部署由主機管理員安裝可信rclone；正式服務不需要Go、Node或Python。以下使用操作者自己的私密絕對路徑，檔案0600、父目錄0700。

```sh
rclone --config /private/modelatlas/rclone.conf config
```

在自己的安全終端完成目的地服務的授權，建立例如 `cloud` 的儲存remote；再建立 `encrypted` crypt remote，指向該服務選定的專用備份目錄。維持檔案內容加密、standard檔名加密及目錄名加密，另存crypt密碼／salt。目的地可以是rclone支援且已授權的雲端硬碟或其他儲存；實際相容性以所選backend測試為準。

rclone設定內的obscured密碼不是私密設定檔的加密保護。專用設定檔仍須私密；產品必須能驗證選定crypt設定，因此不接受它無法檢查的整份加密INI格式。不自動借用ChatGPT或同機其他應用的帳號／環境變數。

```sh
./bin/modelatlas-vps-linux-amd64 backup configure \
  --env-file /private/modelatlas/state/config/modelatlas.env \
  --destination-file /private/modelatlas/state/config/backup.json \
  --rclone-config /private/modelatlas/rclone.conf \
  --remote encrypted --prefix ModelAtlas \
  --spool-directory /private/modelatlas/state/backups \
  --label 我的加密備份 --confirm-key-recovery

./bin/modelatlas-vps-linux-amd64 portable run \
  --directory /private/modelatlas/state \
  --backup-destination /private/modelatlas/state/config/backup.json
```

只有實際另存復原材料後才加入確認旗標。`configure`不覆寫既有檔、不會啟用排程；目的地描述只保存host檔案路徑及非秘密選項，憑證留在專用rclone設定。既有 `serve --env-file` 也接受 `--backup-destination`，或由自己的私密env設定 `MODELATLAS_BACKUP_DESTINATION`。

容器內對應binary為 `modelatlas-vps`，env為 `/data/state/config/modelatlas.env`，rclone executable為 `/usr/bin/rclone`。在同一volume內以runtime UID10001保存0600的設定；開啟排程前重新啟動配置了目的地路徑的app。不要將OAuth token或crypt密碼放入Compose檔、image build args或容器環境。

## 網頁操作

1. 重新讀取狀態，確認目的地及「備份密碼與金鑰已另存」。預設定時備份關閉。
2. 先按「立即備份目前資料」，等待狀態走完快照、加密上傳、回讀驗證。完成後顯示快照時間與驗證完成時間，兩者分開。
3. 需要時明確啟用排程，間隔15～10080分鐘；勾選是否在成功回讀後另保留本機快照，再保存。
4. 備份紀錄永久保留，可分頁查舊工作及其完整過程。失敗／取消／程序中斷不冒充成功；可取消正在執行的傳輸，或明確重試原備份。

關閉網頁不停止主機工作；每次由原30秒maintenance worker檢查到期，不新增高頻輪詢器。備份policy及工作帳本放在獨立的 `backup-state.sqlite`，不進metadata sync，也不隨完整應用DB複製授予另一部署自動傳輸權。

排程綁定本機DB路徑、master key、目的地及底層cloud設定；改雲端帳戶、資料夾、crypt身分等需重新確認。普通OAuth access token刷新不改目的地；refresh credential等穩定身分變更會使舊確認失效。先前部署的歷史仍顯示，但不能借為目前資料的最近成功備份。

保存／建立結果不明時保留草稿與原識別碼，先只讀查詢。已保存後GET失敗不重複POST；重送建立亦沿原ID去重。版本衝突時不把舊草稿偷偷套到新revision，須明確放棄草稿或重新確認。

成功且未選另留本機快照時，只清除該次已驗證的暫存副本，保留正式DB、帳本及全部雲端版本。失敗快照保留供同ID／同SHA重試；無完整可用快照的失敗會暫停新增快照，避免不停耗盡磁碟。保留本機快照或累積雲端版本仍會使用空間，沒有默默清除歷史的保留政策。

## 主機端下載及還原

可在自己的終端立即備份，輸出非秘密的job結果。記下原ID；同ID重送只讀已存在結果，明確 `--retry` 才重試失敗工作。

```sh
modelatlas-vps backup run --env-file /private/modelatlas.env \
  --destination-file /private/backup.json --id backup-my-unique-version

modelatlas-vps backup fetch --env-file /private/modelatlas.env \
  --destination-file /private/backup.json --id backup-my-unique-version \
  --output /private/new-recovery-directory
```

`fetch`只建立新的私密目錄，下載並驗完整資料／原key，**不替換正式DB**。先檢查結果和所需版本；真還原先停止自己的服務，再沿原安全入口：

```sh
modelatlas-vps ops restore --env-file /private/modelatlas.env \
  --snapshot /private/new-recovery-directory/atlas.sqlite
```

還原要求writer lock，保留舊DB／WAL／SHM，不刪舊歷史。重新啟動後以原密碼登入，核對完整聊天／研究／vault及版本。若本機工作帳本亦遺失，可用自己的crypt設定列出目的地版本，再按完整ID下載驗證；不能因缺帳本而重建或覆寫舊雲端版本。

## 證據與限制

測試使用真rclone crypt及本機儲存／受控TLS backend，驗ciphertext、上傳後回讀、錯key／損壞拒絕、完整歷史與vault還原、due排程、原ID去重、取消及父程序SIGKILL後子傳輸停止。真Browser包含63筆跨頁紀錄、逐job過程、CAS／草稿、回應遺失與malformed讀取的last-good。

這些不等於已連接使用者的雲端帳戶。目的地主機／服務未取得前，不聲稱已完成異地備份或零資料損失；實際能復原到最近一次成功上傳並回讀的快照。來源7053的完整十一項CI、十一真ZIP／hash／CRC及實圖已核對；原全部負載與timeout不減，精確證據見`docs/verification/20261030/vps-portable-baseline.json`。實際目標環境仍須另驗，完整產品及獨立review／發布門檻不因此完成。
