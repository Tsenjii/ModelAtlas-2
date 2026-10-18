# ModelAtlas 完整產品範圍與 Mac 驗收界線

- **0.6.0 build11候選修復：** 使用者三張實圖與HJMAPI實際回應優先；公開資料自動讀取、v0 schema、公告、站方曲線／群組／單位與實際502來源、連線可讀操作。70核心／136靜態圖＋28frame與九項新HEAD驗收前未交付；私人未知版本及完整大綱仍PARTIAL。詳../docs/HJMAPI_COMPATIBILITY.md。

目前唯一live為 **MAC-PROVIDER-MOTION-011／0.6.0 build11候選**，詳`PROVIDER_MOTION.md`。010已由3529736與Mac39105128601／VPS39105128591／Android39105128634九項、120圖驗收交付0.5.0。此片增加按帳戶的每日簽到／待簽到篩選、Provider概覽與短原生動態／Reduce Motion；須新HEAD九項、62核心／64圖每架構及動畫frame審查。完整大綱仍PARTIAL；下方為歷史界線。

目前唯一live為 **MAC-CHARTS-010／0.5.0 build10候選**，詳 `CHARTS.md`。009已由52d5bfd／Mac39090603877、VPS39090603936、Android39090603909九項與110圖驗收並交付0.4.0，可讀並列比較與石墨／可選accent已落地。本片增加圖表真選取／鍵盤／精確匯出／收據，以及超過200筆的價格／觀測分頁；須新HEAD九項及120實圖，完整大綱仍PARTIAL。下方切片敘述與表格保留原驗收時的歷史界線，不能覆蓋此最新狀態。

最新工作為 **MAC-NATIVE-009／0.4.0 build9候選**，詳 `NATIVE_POLISH.md`。使用者明確要求重新研究好看軟體並取消固定藍綠按鈕；此片整理原生外觀與可讀並列比較。008已由8eb68087／Mac39058446721、VPS39058446728、Android39058446846九項與100圖驗收並交付0.3.0。009須新HEAD九項及110圖；完整大綱與其他情境仍PARTIAL，下方切片文字保留歷史。

2026-10-18（台灣）。依使用者重新提供的 `astra_master_prompt_structured_final_zh-TW-1(1).txt`：Part I 50節、Part II Runtime Research、Part III App Assistant、Part IV Reflection，以及後續明確產品決策。

## 產品定位

**全球免費強模型 API Intelligence + LLM Provider Manager + Playground + App Control Assistant。**

這份原文是完整產品的需求大綱，細部資料模型、互動、錯誤與復原流程仍須補齊。管理所有使用者加入的 LLM API，不只免費連線；持續發現／驗證 recurring-free 強模型是獨立的重要 Intelligence 能力。Playground 是使用入口之一，不能用聊天首頁代替全產品。

以下三種證據必須分開：引擎有 API、Mac 有正式入口、完整產品流程已驗收。0.2.0 `dac3d26` 的九項綠燈／84原生圖只驗收 MAC-PARITY-006 與當時的跨平台既有功能對齊，**不宣稱原始大綱全部完成**。Android PLAY-004／P8–P10 的未完成驗收仍保留。

## 全域需求對照

| 大綱範圍 | 現有正式模組／Mac 入口 | 驗收界線或仍需處理 |
|---|---|---|
| 1–3、14：定位、完整 domain、三工作區 | `AtlasStore`／Playground／Providers／Models；自帶引擎與既有 SQLite schema | 共用引擎不代表每個管理情境都已完成原生 UX |
| 4：文字、reasoning、coding、串流、媒體、檔案、tools與 telemetry | `ChatView`／`MediaOptionsView`／`ChatTranscriptView` → infer/media／sessions／telemetry | 已有真TLS媒體、scope、取消與歷史驗證；不能擴稱 Android 全部媒體 UI 已驗收 |
| 5–9：Provider、Quick/Advanced、帳戶、多Key、自訂認證、availability、協定端點 | `ProvidersView`／`ProviderEditors`／`BatchImportView` → onboarding／credentials／endpoints | 批次Provider匯入不等於每種多Key管理情境都驗過；A/B/C Key availability與不同 quota 的完整原生操作仍需逐例驗收 |
| 10–12：Canonical、多維分類、全域搜尋、手動模型、filters、compare | `ModelsView`／`CatalogDetailView`／`ModelEditors`／`ModelFiltersView` | Catalog與My Models分離；Compare結果仍偏結構資料，需可讀的並列比較呈現 |
| 13、24–30：Dedicated adapters與Generic | 隨附引擎 `adapters.go`／`newapi.go` 等；Provider詳情與sync | Mac使用既有 adapter；逐站正式能力與上游可用性不能由fixture或選單存在推定 |
| 15：provenance、evidence、衝突／版本／scope | 模型與研究詳情、`ResearchDetails`、`KnowledgeView` | 許多進階資料仍用JSON tree；資料可讀不等於普通使用者能快速判斷 |
| 16–19、21：FREE NOW、recurring/trial、pricing history、quota pools | 引擎 `pricing.go`／`offer_summary.go`／resource pools；模型事實編輯與詳情 | nominal／effective／quota分離；原生額度池、價格歷史圖與可讀摘要仍需補齊，不以原始JSON當完成的儀表板 |
| 20、22：TTFT/TPS/RPM/TPM、健康度、樣本／時間窗 | 模型列／效能詳情／`RequestHistoryView` | 主列已顯示真 mean與樣本；詳情多項仍為JSON，需完整可讀趨勢與錯誤分布；不混站方與本機 |
| 23：簽到 | Provider設定與簽到API、時區設定 | 詳情有開啟與完成；主清單待簽到篩選／一致狀態仍列後續UX驗收 |
| 31–32：非標準資源、LMSpeed | 候選AccessMethod、`ServicesView`／Research／外部資料 | WEB_ONLY／CODING_AGENT不能假作INFERENCE_READY；外部reference不當本機觀測 |
| 33–39與Part II：完整crawler漏斗、多語、台灣資格、authenticity、probe budget、execution backend | `ResearchView`／`ResearchDetails` → research runtime、sources、candidates、evidence、tasks | 已驗部分正式worker與控制；完整多語發現→資格→真實模型辨識→候選分級的所有產品情境仍需分項驗收，不因有start按鈕判全部完成 |
| 40與Part IV：可持久、versioned、reversible研究經驗 | `KnowledgeView` → recipes／knowledge／connectors／reflection | 已驗connector與知識版本／stale／undo；完整每輪反思如何改善後續來源／query／規則仍需真實可追蹤驗收 |
| 41–43與Part III：App Control Assistant | `AssistantView`／`AssistantSecretEditor`／共享`ActionReviewView` → structured tool runner | 已有真caller與safe refs；大綱列出的全部讀取／操作與onboarding A情境需逐項scenario驗收，不能把聊天框當完成 |
| 44–46：history charts、通知、backup、background/stale、retention | Settings／通知紀錄／匯出／引擎背景與last-good／完整歷史 | Mac尚缺完整原生歷史圖；可攜設定不是完整加密跨機備份；休眠不承諾24/7背景；不自動清歷史 |
| 47：future extensions | 保留原架構邊界 | 依原文不實作routing、fallback、自動Key輪替、對外gateway；本機私有loopback是內建引擎IPC，不是向使用者提供API gateway |
| 48–50：工程流程、P1–P10、A–H產品驗收 | 既有CI、`TRACEABILITY.md`、各slice真caller／fixture／原生截圖 | 九項CI與原生圖是必要條件；仍須產品requirement逐項證據，不可宣稱大綱整體完成 |

## 先前切片：MAC-DESKTOP-007（0.2.1已驗收）

沿用既有SwiftUI／AppKit、teal與使用者喜歡的側欄式設定；不改引擎／DB／Android／VPS UI。管理主畫面收斂成原生分組列與一致字級。Playground移除口號／提示卡，改呈現實際選定模型與帳戶Key摘要；輸入區30pt起依AppKit真排版增高到144pt，超出可捲，清空後縮回；保留IME、Undo、附件與舊對話草稿。

新增⌘L聚焦輸入、⌘O附件、⌃⌘S側邊欄；模型右鍵提供使用／複製ID／連線摘要。模型清單只顯示引擎已知的價格狀態，FREE NOW cache另計費要明示；Provider可篩已有確認免費連線，不將Unknown或一次性額度當免費。

驗證保留29核心與原25＋17圖，另增長文輸入、隱藏側欄與exact-account已知免費三圖，共45／架構、90圖。新HEAD全部九項與實圖前為候選。這一片完成也不改上表未完成／未逐項驗收狀態。

首候選765247d九項同SHA已SUCCESS，兩架構29核心／45圖PASS；圖審發現首頁側欄還在搜尋的瞬間。Capture改等待真實sessionsLoading結束；選檔入口與新捷徑補上closing／modal guard。Provider免費篩選改每輪建立ID集合、同一body重用清單結果，避免逐Provider掃完整模型池，數量明確標模型連線。新HEAD保留全部90圖與條件，重新九項驗收。

816a399的arm64完整通過，Intel29核心通過後在原長串流25秒完成界線失敗（仍generating、902字、閱讀offset保持0、無API錯誤）。不刪字數／SHA256／時間／閱讀斷言，也不盲目重跑。輸入區排版只在自己的文字或寬度改變時測量，避免每次聊天視窗排版都重算；補真正的窄／寬窗自然換行與縮回驗證。fixture／失敗診斷另記已發字數與完成布林，PASS診斷記耗時，不記對話或秘密；新HEAD重新全九項。

設計參考僅用官方來源：[Apple macOS HIG](https://developer.apple.com/design/human-interface-guidelines/designing-for-macos)、[Bear](https://bear.app/)、[Magpie](https://github.com/yetone/magpie)。參考內容優先與原生資訊層級，不複製品牌資產或程式碼。


## 目前功能切片：MAC-INTELLIGENCE-008

依使用者要求繼續完整管理功能，先補價格／獨立額度池／本機趨勢與觀測歷史的原生讀取介面；詳細production caller、資料界線及驗證見 `INTELLIGENCE.md`，Kios案例研究見 `../docs/KIOSAPI_RESEARCH.md`。0.3.0 build8為新候選，待同HEAD九項與100實圖。這項新增不把上表其餘完整情境、Compare並列與Provider管理等缺口改成完成。
