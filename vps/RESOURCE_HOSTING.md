# 資源優化與 Cloudflare／GitHub 部署判斷

2026-10-19，VPS-MAC-PARITY-002。依使用者本輪要求，在 Mac 操作對齊內補資源效率；不切換 Android、不更換目前資料庫、不自動部署。每個 HEAD 的正式驗收仍以 PR #20 同 SHA 六項 checks、實圖及套件驗證檔為準。

## 本輪實際改動

- 推論 JSON 改用 `request_json.go` 分段編碼。不可變的一般文字直接引用原字串；需要 escaping 的部分按 32 KiB 邊界編碼，不拆開合法 UTF-8。先完成與原 `encoding/json` 相同的 wire、32 MiB 上限及秘密值檢查，才允許 HTTP；已知 Content-Length、獨立重送 reader、取消及所有協定保留。沒有將內容寫入暫存檔，也沒有新增常駐 writer goroutine。
- 秘密值仍檢查原始及四層 JSON 轉義，跨分段也會匹配；重複的掃描可共用結果。不同秘密值即使彼此為轉義版本，仍各自檢查足夠層數。附件仍經原本解碼與秘密值檢查。
- 可見頁面的同步輪詢只讀最新 5 筆，原預設讀取仍為 100，並可明確分頁。後端 SQL 先套 LIMIT/OFFSET，再解碼 summary；不刪任何歷史。保持原 10 秒更新節奏，加上未完成請求互斥，避免慢網路累積輪詢。通知關閉且未查看通知頁時，不再額外輪詢通知。
- 測試／同步後只刷新當前模型 facts、實測與身份資訊，保存設定表單與草稿；逐表單 pending／內嵌錯誤／已寫入後只重讀的恢復操作，減少重複提交。
- 草稿與附件只放在目前分頁的記憶體，依完整 scope／session 隔離。重試與工具接續不清除下一則草稿。新增共用附件入口、原子多檔選擇與拖放；任何一檔失敗都不部分覆寫既有附件。

沒有縮短上下文、降低並行數、抽樣 SQL percentiles、削減匯出、刪除永久紀錄或自動改用其他 Key。仍是單一 Go binary／SQLite／原生瀏覽器 UI，正式服務沒有 Node、Chromium 或額外 UI runtime。

## 如何重現量測

```sh
cd vps
go test -mod=readonly -race -count=1 ./...
go vet -mod=readonly ./...
go test -mod=readonly -run '^TestRequestJSON' \
  -bench '^BenchmarkInferenceJSONBody$' -benchtime=3x -benchmem -count=1
MODELATLAS_FOOTPRINT_TEST=1 MODELATLAS_FOOTPRINT_OUT=footprint.json \
  go test -mod=readonly -v -run TestStandaloneProcessFootprint -count=1 -timeout=3m
```

相同約 31 MiB Unicode context、完整消耗 JSON wire 的本機編碼對照：原 contiguous **56,809,346 B/op、173 allocs/op**；segmented **19,912 B/op、22 allocs/op**（Go 1.26.4、3 iterations）。這只代表編碼的額外配置，不是整個服務的 RAM 或端到端速度。CI 保留此對照與獨立程序 footprint 兩份 artifact。

原 expanded workload 完整保留：十萬 samples／全量 SQL percentiles、4 路 TLS 對話及 4 路同時完整 31 MiB context、11,000 訊息與 100,008 請求匯出、65 模型研究／Catalog、4 張 2048×2048 圖片與 4 路 4 MiB PCM→WAV。512 MiB 驗證門檻及原 timeout 不變。

舊候選 `25d4787` 的 CI peak 為 **393,785,344 bytes（375.543 MiB）**、idle **26,316,800 bytes（25.098 MiB）**，不是本次最終 HEAD 的量測。本機 sandbox 無法讀取子程序 `/proc/PID/status`，其 footprint 執行失敗沒有產生有效 RSS；不改低原測試要求，以最終 Linux CI 的真 PID 數據為準。`GOMEMLIMIT=192MiB` 是 Go soft limit，不能承諾整台 192／256 MiB 主機足夠；OS、TLS proxy、瀏覽器及 fixture 不包含在服務 PID 內。跨不同 runner 的 RSS 波動不能當作受控改善比例。

## 免費部署是否適合

以下限制於 2026-10-19 從官方文件確認；產品與價格可能更新，實際開通前應再核對。

| 方案 | 可以承載的部分 | 對目前完整版本的判斷 |
| --- | --- | --- |
| GitHub Pages | HTML／CSS／JavaScript 靜態網站、說明與下載入口 | 無法執行目前 Go／SQLite 後端、vault、私人用量或研究工作。拆出 UI 仍需另外提供相容 API／認證；目前不拆成兩套部署。 |
| GitHub Actions／Releases | 建置、完整測試、雙架構 binary 與版本分發 | 適合作為更新管線。GitHub-hosted job 每次最多 6 小時，不能作為本產品的常駐後端。 |
| Cloudflare Workers Free ＋ D1 | 重新移植後的小流量後端，有機會使用免費額度 | 不是上傳現有 Go binary 即可部署。Workers 為 128 MB／isolate、10 ms CPU／request、100,000 requests／day；D1 免費每 DB 500 MB、每列 2 MB、每天讀 500 萬列／寫 10 萬列。需移植 SQL／vault、研究任務、串流、backup／restore，並驗證永久歷史與大內容；目前沒有相容實作，不承諾完整功能免費。 |
| Cloudflare Containers | Linux container，可運行 Go 程序 | 需要 Workers Paid，起價為其 $5／月方案加用量；非免費後端。磁碟預設 ephemeral，sleep 後不保證目前 SQLite 持續保存；snapshots 不是每次寫入即持久化的替代品。未完成儲存設計前，不提供會遺失 DB 的一鍵範本。 |
| 現有小型 Linux VPS ＋ CF 入口 ＋ GitHub 建置／更新 | 目前完整 Go／SQLite 功能 | 本輪建議。延用持久磁碟、同一 master key、原更新與備份流程；CF 可作網域／HTTPS／入口層，但運算仍由 VPS 提供。沿用單 binary，避免為省主機費先引入另一套後端與資料遷移。 |

若未來決定做完整 CF 原生版，應另立可追蹤移植切片，先證明真實讀寫量、SSE 中斷／取消、完整匯出、任務 resume、scope、秘密邊界與現有 Browser 合約，再討論替換部署。此文只評估方案，沒有建立雲端資源、變更 DNS 或部署使用者站點。

官方來源：

- [Workers limits](https://developers.cloudflare.com/workers/platform/limits/)
- [D1 limits](https://developers.cloudflare.com/d1/platform/limits/) 與 [D1 pricing](https://developers.cloudflare.com/d1/platform/pricing/)
- [Containers pricing](https://developers.cloudflare.com/containers/platform/pricing/) 與 [Containers persistence FAQ](https://developers.cloudflare.com/containers/faq/)
- [GitHub Pages](https://docs.github.com/en/pages/getting-started-with-github-pages/what-is-github-pages) 與 [GitHub Actions limits](https://docs.github.com/en/actions/reference/limits)
