# CI 重跑來源回歸資料

`ci-rerun-provenance.json` 是 2026-10-21 從本公開倉庫 GitHub REST API 讀回的最小必要欄位，不含認證／下載網址。來源均為 `ffbb697dbd59372be2e93a874432f7e644794e40`：

- VPS run 39536170156：四個成功工作、四份產物。
- Android run 39536170293：兩個成功工作、兩份產物。
- Mac run 39536170097 attempt2：三個成功工作、四份產物；其中 Intel QA12318516718 是首輪失敗留下，12317604113 為成功重跑。只重跑 Intel，build／ARM 保留原執行時間；API 最新 job ID 可能改變，不以 job ID 與 artifact ID 的大小推論時間。

GitHub API 沒有在 artifact 回傳 producer job ID。本流程以固定 workflow 的 artifact→job 宣告、同 run／head SHA 及 job.started_at ≤ artifact.created_at ≤ job.completed_at 要求唯一符合，缺失或歧義即拒絕；manifest 的 producer 是依此約束綁定。這不是額外的簽章／attestation。

官方欄位與來源：

- https://docs.github.com/en/rest/actions/workflow-jobs?apiVersion=2022-11-28
- https://docs.github.com/en/rest/actions/artifacts?apiVersion=2022-11-28
- https://api.github.com/repos/Tsenjii/ModelAtlas-2/actions/runs/39536170097/jobs?filter=latest
- https://api.github.com/repos/Tsenjii/ModelAtlas-2/actions/runs/39536170097/artifacts

原失敗紀錄保留；不把重跑成功當成 Mac composer 偶發問題已修復。`debug-signing-certificate.pem` 是既有公開 debug 簽章解析測試用憑證，不是私密 keystore。
