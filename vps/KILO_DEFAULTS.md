# Kilo 出廠預設模型

KILO-DEFAULTS-015M 依 D-018，為全新 VPS、原生 Mac 與 Android 工作區預載 Kilo 的匿名公開連線及 15 個模型。不需要申請帳號、填入 API Key 或開啟研究排程。初始選用 NVIDIA Nemotron 3.5 Lightning；已有有效選擇時保留原選擇。

## 使用與升級

- 新工作區正常啟動即在 Playground／My Models 顯示模型；使用者建立對話並傳送後才呼叫模型。
- 舊工作區不會自動加入。開啟「設定 → 一般 → Kilo 免Key預設模型」，按「加入Kilo免費預設」。Android 對應設定中的同名區塊。
- 加入操作以單一資料庫 transaction 保存 Provider、匿名 Account／連線、模型來源與成員資格。儲存失敗整組回滾；回覆遺失時先重新讀取已保存狀態，不自動重送。
- 重啟、重複加入或升級不會復活已移除／隱藏的模型。不覆寫原 Provider、帳戶、Key、偏好、對話或來源證據。保留 ID 與既有資料衝突時停止加入。
- VPS 的 `MODELATLAS_FACTORY_DEFAULTS=0` 可讓自行管理的工作區停用首次預載；不刪已保存的模型。Mac 正常啟動啟用；原驗收工作區與 Android test runner 明確隔離出廠資料，保留全部既有測試與網路負載。

## 來源與範圍

唯一公開快照是 `vps/defaults/kilo-anonymous.json`。Go 內嵌此檔；Android 打包同檔為 asset，沒有第二份獨立清單。2026-10-25T02:46:05Z 讀取官方 `https://ai-gateway.kilo.ai/api/v1/models`，401 個項目中 15 個明示 `isFree:true`、prompt／completion 零價，來源本文 SHA256：`1e1d1e69b6f4844deb8e8bc86ffbc67412813aea45f7653967b02bb6f4ce9ce6`。舊官方位址當次回覆相同資料；產品固定使用現行官方位址。

官方說明：

- <https://kilo.ai/docs/gateway/authentication>
- <https://kilo.ai/docs/gateway/models-and-providers>

預載 source 為 `BUNDLED_KILO_CATALOG`，價格／quota 為 UNKNOWN，不建立實測成功、收據、健康樣本或 verified 身份。能力／模態／context 是供應商公開聲明，不當成已驗證能力。Android aliases 以 CATALOG／LOW／未驗證保存。加入 My Models 是使用者要求的產品預設，沒有改變一般 Catalog 與成員資格的規則。

清單包含限期免費與上游免費別名；這些是 Kilo 的外部模型 ID，不是 ModelAtlas 實作跨供應商路由／fallback。清單不代表所有模型永遠可用，不證明 recurring-free、配額或模型真實身份。

## 每次發送前的核對

出廠連線只能使用固定 Kilo origin、原匿名帳戶、Chat Completions，不能借用私人 Key、自訂認證或改投付費型號。Android 在正式 TransportDispatch 先檢查 scope，避免更換協定繞過；正式 OpenAiTransport 再核對目錄。Go 共用正式 engine 在訊息／telemetry admission 前核對。

每次使用此連線時，先匿名 GET 官方目錄：20 秒、4 MiB、最多 4096 模型、不追蹤重新導向。所選 ID 必須唯一、`isFree` 為真正 boolean true、prompt 與 completion 明確為零；其他提供的價格欄除 discount 外也必須為零，且未到供應商的 expiration_date。缺少、轉付費、其他費用、目錄不可用／格式錯誤或連線設定改變，都在模型 POST 前停止。不清空草稿、歷史或 My Models；沒有自動重試／付費 fallback。

預載／啟動完全離線，不自動發送聊天、研究推論或健康檢查。上游每次回覆仍可能限流或改變價格；先前一次目錄的聲明不是供應商的跨請求價格鎖定或 SLA。Kilo 匿名來源可能保存對話並用於改善模型，產品在所選連線與設定顯示說明；請勿輸入個人或機密資料。

VPS schema34 的 `factory_defaults` 只保存本機首次安裝意圖，33→34 先建立一致升級快照。Android 沿用 Room schema16 的本機 runtime journal 模式，Room onCreate 的 PENDING 標記區分全新資料庫與既有空工作區，並可接續啟動中斷。這些本機執行標記不進入 workspace sync 或可攜設定，匯入資料不移植首次安裝授權。

## 驗證紀錄

- 正式 API 先重現未接線的404，0.910s失敗；實作後完整六個後端函式 race10.995s成功，涵蓋原子回滾、衝突、舊資料升級／快照、持久移除、真TLS正式 engine 與免費攔截。付費、額外費用、過長價格、缺價、消失／重複、過期、401／503及壞JSON均不發模型POST。
- 明確 opt-in `MODELATLAS_LIVE_FACTORY_KILO=1` 的真站測試8.472s成功：一次官方匿名目錄GET＋一次 Lightning 合成文字模型POST，正式 engine 保存成功telemetry。環境測試 client 使用此執行環境的網路proxy；產品公開 origin／無redirect限制沒有放寬。這是當次連通證據，不外推台灣、實體裝置、其他14模型或持續免費。
- 核心 JVM 完整585項成功（原579＋6），App JVM原423項亦成功，包含無Key真MockWebServer傳輸、取消、非免費／redirect拒絕、私人連線原負載與更換協定／帳戶／Key／Group／origin的分派前拒絕。
- 新 Browser 保留原37流程／458圖，新增第38流程／15圖：初始Lightning、匿名目錄＋真TLS聊天、付費拒絕／保留草稿、精確scope錯誤提示、狀態讀取失敗的last-good、移除與reload、舊工作區明確加入、已commit但回覆遺失後只讀恢復。320／375／1280、深淺色與200%。
- 新 Browser 最初錯用提示文字造成30秒等待；正式caller隨後暴露HTTP前置拒絕未本地化，已改為結構化錯誤。Basic Auth第一次401探測與實際加入分開計數，另以action ledger核對只加入一次。實圖發現固定錯誤提示遮按鈕後，改為相符聊天scope的inline提示與設定區塊本身的恢復狀態；來源說明可展開，不擠占聊天。這些失敗沒有當作驗收成功。
- 原生 Mac 增加三個測試與兩張專用實圖：真packaged engine首次啟動／重啟保留移除、舊帳戶明確加入、免費攔截的可讀錯誤；原82項／90圖完整保留。Android 增加八個真Room／asset測試；原976項及5個既有skip不變。本機App與androidTest兩份APK編譯成功，最後一次Gradle3m8s；自己的實際CI報告與產物前不宣稱這兩平台已驗收。

本機完整Go race580.494s、vet與原27發布回歸0.037s成功；原38 Browser完整162.741s成功，最後操作與實圖修正後再以乾淨目錄重跑。

本片仍待同一來源九項CI、九份實際ZIP／來源與簽署資訊／全部報告及實圖核對。公開0.8.5-preview.1、tag與11附件保持原樣，PR #20維持Draft，不merge／部署。此片驗收不等於整版完成；完整擷取效率、同步、Android PLAY-004與既有整版缺口繼續保留。

後續015M反例：原0f60e209的vps-test在600.032s逾時，其他8項CI成功也不接受；原stack與來源保留。正式研究caller在預算已滿時把本機拒絕誤分類成Kilo目錄失敗（0.068s），另真TLS semantic報告只列1次而實際目錄＋模型共2次（0.133s），兩者均已修正。保留原typed等待／明確resume及本機scope變動原因，取消不建立失敗模型樣本；每次probe從既有task meter計算實際本次HTTP，不把以前attempt累加成本次。擴大Factory／原Progressive／Conversation聚焦race47.992s、原完整單程序race463.795s成功（全部369 top-level，324pass／45明確opt-in skip）；兩個獨立程序完整runner仍保留全部原migration／HTTP／負載與整套600秒上限，詳[TESTING.md](TESTING.md)。新來源自己的九項、實際附件與實圖前仍候選。

完整兩程序race240.844s成功，369個註冊案例185／184各執行一次；與原單程序463.795s全部case／subcase結果逐項相同，沒有省略、重複或失敗。四項coverage checker拒絕漏測／重複／錯誤退出／壞事件的反例亦成功。整套仍共同600秒上限，新的同源九項仍待驗。

f7e2af0f九項CI成功後，實際473圖仍發現首次聊天殘留空白welcome而在375px露出截斷標題；正式Browser新增斷言2.055s重現。修正正式bubble在首個訊息移除native-welcome，沒有靠測試隱藏或捲動遮住；Kilo與原Production／ChatExperience／Conversations四流程race15.003s成功並重新檢視清楚的首輪對話。新來源完整38流程及九項／全部實際產物再驗，先前綠燈不借用。

首輪welcome修後完整38 Browser race186.450s成功，473圖保留原458＋15；真實聊天的native-welcome為0，375px首輪對話重新檢視清楚，沒有動到已保存訊息。兩次候選的原始CI／artifact metadata與不接受原因保存於`docs/verification/20261025/kilo-intermediate-candidates.json`。
