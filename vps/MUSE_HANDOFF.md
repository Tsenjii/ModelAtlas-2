# 交給 Muse 或其他部署代理的指令

把本指令連同整份部署ZIP交給能執行Linux命令的代理。先讀根目錄 `DEPLOY_AND_BACKUP.md`，不要把包內舊公開版本號當成本次來源SHA。

```text
請依附件 DEPLOY_AND_BACKUP.md，把 ModelAtlas 部署成我能登入的 HTTPS 服務，並接好我指定的雲端加密備份。

這是通用 Linux／容器部署，Muse只是現在的執行環境。請直接處理可做的工作；先檢查 CPU架構、持久磁碟、程序常駐／supervisor及HTTPS入口，選Compose或免root binary。若平台不能常駐或重建不保留磁碟，明確說出缺少條件，不把短期預覽當24/7服務。

使用附件已校驗的AMD64／ARM64完整套件，程式固定commit為00b6a67ef112c86b750a588503de7b05bfa0bb12。驗根目錄和包內SHA256SUMS、bundle-check及version，再啟動。不要自行換latest／main，也不要拿不含新命令的舊公開Release替代。

資料與私密設定放持久目錄／volume，程式目錄可替換。保留同一master key、管理密碼及資料庫；原SQLite不放雲端同步掛載。用真正的HTTPS ingress、原認證與SSE，給我可使用的網址。

請按說明連接我指定的雲端服務／資料夾；未指定時只詢問這個必要資料，不自行借用別的帳戶。需要Google或其他雲端登入時，停在正式授權步驟讓我完成，再接續操作。使用專用rclone設定與crypt加密；先引導我在安全位置另存復原材料，之後才確認key recovery，不要替我虛報已另存。

先做一次備份、真正回讀驗證，再在隔離state／新volume做還原演練。找回非敏感驗收註記或原歷史後，再依我的需求設定排程。不要自動刪除DB、volume、raw歷史或雲端版本；保留last-good與失敗紀錄。

更新及回退照說明使用一致快照、離線副本preflight與匹配schema的舊版還原，不重建key、不清資料。不要因為要上線而關掉認證、Host／Origin檢查或備份回讀驗證。

完成後回報：HTTPS網址、部署模式、實際來源SHA、state／volume位置、常駐方式、備份目的地標籤／最近成功ID與時間、還原演練結果、更新回退位置及尚未完成事項。密碼／master key／OAuth token只保存在私密位置，交接報告只說其保管位置，不貼內容。

請做到這些驗收完成再回報成功；環境或帳戶授權不足時，明確列出尚缺條件，其他不依賴該條件的工作繼續完成。
```
