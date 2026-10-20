# VPS 持續公開研究排程

VPS-RESEARCH-SCHEDULE-007；WISH-CRAWL-001 的第一階段，整體仍 PARTIAL。正式入口在 Models → 全球研究，或設定 → 研究的同一個工作區。維持單一 Go／SQLite 與現有研究 runner，沒有新增常駐服務。

## 實際操作

1. 在「研究來源」保存啟用的公開 HTTPS JSON／HTML 來源；或在設定保存 Brave Search／AA 的獨立整合 Key。
2. 按「新增排程」，設定名稱、15–10080 分鐘間隔、每輪 1–200 次 HTTP、每日 1–2000 次 HTTP、公開來源及探索語言。可選網路探索、AA 榜單與已保存的 LMSpeed 線索。
3. 保存後保持停用，再按「啟用排程」。目前服務會立即檢查，離開網頁仍由該服務執行；程序必須持續運作。App 內嵌引擎停止後不宣稱有 OS 背景能力。只補最新到期輪次，不把離線期間每個錯過時段補跑一遍。
4. 卡片顯示上輪／下次檢查、執行／等待／暫停／額度原因、每輪及今日用量、研究摘要。排程紀錄顯示最近 100 輪並可打開原始工作／結果；全部歷史繼續保留。
5. 「暫停本輪」保留進度，程序重啟仍暫停；「接續本輪」或「立即研究」明確接續。「停用排程」會暫停該排程所有未完成工作並取消正在進行的 HTTP，重啟不會自行再啟用。舊的通用研究恢復 API 也不能繞過停用狀態。
6. 停用後才能編輯。保存新定義會結束尚未完成的舊輪次，保留任務、結果與用量；編輯不清掉當日計數。「取消研究」只結束該輪；要停止後續輪次請停用排程。

公開資料只有未驗證主張時，繼續留在完整漏斗，不能直接變成 VERIFIED 或加入 My Models。普通 eligibility 仍要求 RPD ≥100 **且** TPD ≥1,000,000；AA 最新有效 top-4 例外與 Unknown、free／trial／來源 scope 規則不變。

## 請求與持久性

| 控制 | 實際語意 |
| --- | --- |
| 預設狀態 | 新建／遷移沒有任何已啟用排程；最多保存 32 份，可停用後編輯重用 |
| 全部排程每日上限 | 預設 100，範圍 1–2000，獨立於手動研究及 metadata sync；各排程每日上限與此上限同時成立 |
| 計數時間 | UTC 00:00 重設，台灣 08:00；以前日期的計數不刪除 |
| 送出前保留 | 每次 HTTP 前以同一個 SQLite transaction 檢查執行意圖、來源指紋、每輪／每日上限，再保留用量。取消、連線失敗或程序中斷不退還已保留次數；UI 不把保留次數冒充精確完成請求數 |
| 到期去重 | 建立 ResearchRun、更新 next_at 與唯一 schedule_id＋scheduled_for 關係原子提交；全服務自動排程同時最多一輪。不降低原手動／Playground flights |
| 每輪耗盡 | 保留舊輪所有結果，下一個間隔以新輪及明確 continued_from 接續未執行公開工作；避免前面的來源一直吃掉小上限。接續完成後才開始下一次完整掃描 |
| 每日耗盡 | 不發送超額請求，也不為未發送請求產生來源失敗證據；保存本輪 checkpoint，下一個 UTC 日在相同輪次繼續 |
| Brave／AA 整合限制 | 保留 Brave 原每日 admission cap、AA 獨立固定 24 小時 window／站方 remaining。已達整合限制等待其重設，不每 30 秒空轉重試；不宣稱已實作所有逐憑證費用／推論 quota |
| 重啟接續 | 只有此主機已確認的本機排程且 durable intent=1 才自動恢復；中斷工作最多三次重播，已完成的工作不重做；單一多頁工作（例如 AA）若尚未完成，重啟／接續時會從該工作重新讀取，尚未提供逐頁 checkpoint，用量不退款；手動暫停／取消與一般手動 ResearchRun 不自動恢復 |
| 讀取失敗 | 清單與表單分開；保留 last-good 和草稿。保存已提交但清單更新失敗，只重讀清單，不重送保存請求；過期 revision 拒絕覆寫 |

這階段只做公開 discovery／inspect 與可選整合資料讀取；**不接受模型連線 Selections、MinimalInference 或 ProbeBudget**。整合 Key 保持既有 vault 與固定 origin；不送給新發現的網站。HTTP 200、模型名稱、free 字樣或最小回覆都不是免費／身份／能力的充分證明。

## 同步與來源確認

schema19 以三張新增表保存定義、輪次意圖與每日保留量。這些表和全排程上限只屬於目前引擎，不隨 workspace 或 portable 設定備份匯入另一端；完整 SQLite 備份仍包含它們。研究候選、證據、歷史、來源與版本仍按原同步契約保存。

每個本機排程只執行本機保存／啟用時已確認的來源定義。來源被本機或另一台裝置更改、停用或移除後，tick 及每次 HTTP reservation 都會檢查，停止自動執行並要求重新確認。改過來源後重新啟用會建立新輪，不能重播舊來源快照。來源不合法時需先修正。

從別台裝置匯入的研究歷史只作觀測，不能變成可執行佇列。若同步改到本機排程所屬輪次／任務／選項，原排程停止，舊工作只保留作紀錄；本機確認後由新輪開始。自己送出資料的正常同步 ACK 不會誤停自己擁有的排程。

每輪建立時讀取目前已明確啟用的研究 query／source weight 版本，再凍結實際任務；tentative 提案不會自行啟用。沿用現有反思紀錄，不將它說成已完成跨輪品質評估或自動修改程式碼。

## 驗證與仍待完成

`research_schedule_test.go` 使用正式 Handler／SQLite、真 TLS upstream，驗證預設關閉、到期／並發去重、每輪接續／每日及全域上限、UTC 跨日、完整關閉再開啟 DB、手動暫停意圖、真 HTTP 取消、來源改動、兩台引擎的真 HTTPS 同步／自己的 ACK、策略正式啟用與 v18 遷移前備份。`research_schedule_browser_test.go` → `e2e/research-schedules.cjs` 走實際 UI／worker／HTTP／候選與上限，包含等待防重、提交後刷新失敗、草稿、紀錄、停用／編輯保留當日量，以及 320／375／1280 深淺色、200% 共 8 圖。

code `b1e0c669ffea48575875e0c16b7da6980aca5552` 已同 SHA VPS 四項＋Android 兩項 SUCCESS；完整 race／vet、真 systemd、14 個新增後端情境、23 Browser／303圖及原 expanded workload 已通過，Android 976／0 failure／0 error／5 原 skip。新增8圖已目視檢查。詳細 job／artifact／SHA256／完整 workload 在 `docs/verification/20261020/vps-scheduler-ci-baseline.json`。後續 HEAD／驗收文件提交各自重新六項 CI，最新結果以 PR #20 checks 為準；不借用前一 SHA 的綠燈。

完整 WISH-CRAWL-001 尚缺每輪／每日推論與費用、實際逐憑證 quota、地區／可調並行、429／502 自動退避及更完整的高價值／過期重驗策略；Mac／Android 原生排程設定與各自 OS 生命週期尚未對應。未知站點 Connector、具品質／成本對照的 Reflection、authenticity、真站長跑／目標 VPS／實機驗收全部保留。通知仍預設 OFF。
