# 完整 Go 回歸

在 `vps` 執行：

```sh
python3 -m unittest test_go_runner_test.py
python3 test_go.py
```

runner 先以原 `-mod=readonly -race` 編譯真正的測試 binary，從其註冊表列出全部 Test／Example／Fuzz seed，依排序交錯分到兩個獨立程序。每個案例及全部子案例各執行一次，`-test.count=1`，不使用測試結果快取。完整真實 Go JSON 事件原樣輸出，缺少、重複、未知案例、非零程序結果、race 或失敗事件均不接受。

兩個程序共同遵守原本整套 **600秒** 上限，各自也保留 Go 的10分鐘 timeout。編譯階段與原 `go test` 一樣不計入測試執行期限。沒有延長整套期限，也沒有把十分鐘分配給每一組後串行跑二十分鐘。完成報告 `test-results/go-suite.json` 保存來源、工作區dirty狀態、註冊表hash、全部選取／開始／完成案例與程序結果；CI把此報告放在既有footprint附件中。

原測試本文、每次新建資料庫的所有migration、SQLite／TLS fixture、HTTP、斷言、負載及timeout不變。沒有複製已建立的空資料庫、重用回覆／觀測或省略原遷移。原本研究fixture每程序最多兩個的隔離也保留；OS資源分離到不同程序，沒有共享帳戶、憑證、clock或權限。

此調整源自015M首候選0f60e209：其餘8項CI成功，但整套Go在600.032s逾時，當時仍在執行原fixture的真migration與新Kilo流程。原始stack／失敗來源保留，不當成驗收成功。單程序診斷仍可直接執行原命令：

```sh
go test -mod=readonly -race -count=1 ./...
```

Browser保留原有獨立job及5分鐘內部上限、兩個有界流程／真Chromium／剪貼簿隔離；沒有改用此runner。Systemd與standalone footprint保留各自實際執行與原期限，100000筆樣本、4個31MiB context、11000則訊息／100008筆匯出、65模型、4張2048²圖片及4個4MiB音訊全部保留。真站Kilo仍須既有明確opt-in，普通回歸不自行連外測模型。
