# Mac 原生聊天體驗：MAC-CHAT-003

使用者明確要求繼續漂亮 UI、人性化互動與功能。基線 `2f0065234958b0683c6dbcd8824ba4c3ac74673b` 的九項CI與22實圖已驗收。維持同分支／PR #21、SwiftUI／AppKit／teal與三工作區。

## 正式行為

- `MessageMarkup` 以 Foundation 解析有限的 native Markdown block grammar；標題、段落、引言、ordered／unordered list、分隔線、反引號／波浪號fence、pipe table。短fence不關閉外層長fence、未閉合code仍保留內容、escaped pipe與inline code pipe不拆欄。不是完整CommonMark／GFM實作；巢狀清單／數學式／語法上色尚未支援，原始文字仍可完整複製。
- `ChatMarkdown`／`CodeBlockView`／`MarkdownTable` 直接用原生View顯示；程式碼可橫捲且複製原始code；表格可橫捲，深淺色使用system surface。只在完成的assistant內容解析，串流維持文字批次更新；Equatable隔離未變訊息的排版。HTML不執行、圖片不抓遠端，連結沿用Root HTTPS／userinfo guard。
- `ChatScrollProbe` 僅操作／觀察這個聊天的NSScrollView，以已layout的document與clip bounds合併捲至最新，不依賴LazyVStack中尚未完成layout的ScrollViewReader目標。live-scroll明確改閱讀狀態，因文件增長而改bounds不能自動恢復／暫停follow；離開最新才顯示回最新。新對話／切換對話重設，訊息固定id不因全文內容重建身分。保留歷史載入與停止控制。
- `LocalAttachment` 共用選檔與拖放的原子批次。僅本機regular file、允許副檔名、每份非空≤4MiB、bounded read、header signature；每個image／pdf／audio最多一份，任一失敗原tray保留。讀取在非主執行緒，晚結果需原session／scope／原tray仍相符且非generating。`LocalAttachment.requestValue`只送正式Attachment解碼器允許的kind／filename／mime／data四欄；size_bytes等顯示metadata保留本機。正式engine再驗完整格式／能力／scope。尚無剪貼簿圖片輸入或附件重啟恢復。
- 每個訊息的sample_id開啟`RequestReceiptView`，讀正式`/telemetry/detail?id=...`；顯示當次scope、結果、輸入／輸出tokens、TTFT／latency，缺值保留尚無資料；完整pricing／quota／來源留在展開區。錯誤可在sheet重試。

## 必要驗證（不是已完成證據）

- `ChatExperienceTests`：原文code／短長fence／未關fence／CRLF、pipe與非法table／長內容、HTML保留、閱讀state、檔案type／header／size／duplicate／remote URL拒絕與原子批次。
- 保留前片全部十一XCTest及store／keyboard／IME／scope／TLS／cancel／Keychain／lifetime guards。
- `NativeSmoke` 在獨立真engine／TLS fixture跑長回應，上捲後實際NSScrollView位置在後續字流維持、回最新、原生code／table內容、精確message request scope、正式附件caller與重複拒絕，再確認精確scope的FILES能力，以真正store.send→strict decoder→TLS fixture完成PDF推論，驗兩則訊息與附件metadata；最後恢復原rich對話且原始歷史未改。
- 長fixture以每8字20ms的小批次送出，在900字後等待明確release，閱讀位置固定後才繼續字流；沒有縮減長回應或位置斷言。捲動驗證使用真NSScrollView及live-scroll通知，未宣稱硬體觸控板／XCUI全點擊覆蓋。拖放走正式NSItemProvider／DropDelegate，回歸目前驗共用檔案caller，未宣稱Finder端到端drag coverage。
- 保留原十一圖，追加rich chat深淺／exact receipt sheet／attachment tray／窄窗rich chat，保留這16圖，再加表格深淺兩圖；兩架構各18圖、共36圖必須review。新HEAD九項CI全綠才可驗收，SHA／run／artifact／失敗由PR #21最新body保存。
- 版本0.1.2 build3；原未Apple公證／macOS12實機／部分進階表單限制不變。沒有新增DB migration、production dependency、模型HTTP探測或Android／VPS UI改動。

## 首輪失敗保留

`6f31d20`／Mac39011781130的Universal與兩架構15項核心測試通過，actual smoke arm64在resume-latest、Intel在start-real-rich-stream失敗。採上述native幾何與fixture握手修復；新HEAD須重驗。VPS39011781100與Android39011781245已成功，不能代替新HEAD的結果。failure-state只保存固定stage／數量／布林與幾何，不保存任何使用者文字。

附件wire review另發現size_bytes是native顯示metadata，正式引擎Attachment採DisallowUnknownFields；以明確四欄request projection修復，未放寬解碼器，新增原生真PDF sender驗證。

`7331726`／Mac39014935266的arm64固定診斷為provider在900字barrier等待，但native收到885字、generating=true、無problem且已在最新。正式streamGuard刻意保留可能跨chunk命中secret的尾端，不可移除此安全緩衝；測試保持原650字client觀測門檻，再查fixture自己的paused狀態確認900字barrier，release後要求完整SHA256及1249字原文相符。不是放寬字流或history／位置驗證。

`41eacb5`／Mac39015462610 arm64已通過prefix／paused握手，真視窗在後續字流把clip_y從手動閱讀位置0帶回678；generating=true、1113字、following=false。原生coordinator現在保存live-scroll確定的「距文件頂端閱讀offset」，內容frame或clip bounds更新後恢復該offset，明確resume才解除並到實際底部。保留<3px位置斷言、全部字流、全文SHA256與完整歷史；failure-state另記純數值reading offset。

`8fb4d0a` 同SHA九項成功（Mac39015983459、VPS39015983596、Android39015983539），兩架構15項核心／16圖全過。實圖review另發現arm64再次上捲未停在頂端：user live-scroll與非同步model回寫之間，舊值true的view update可能覆蓋native暫停。`setFollowing`記錄上次收到的model值，只有值改變才視為指令，同值舊update不能撤銷使用者動作。native smoke直接重放該舊model update，要求probe仍暫停且offset<3px；兩張原code截圖完成後仍須<3px。原16圖保留，追加表格深淺兩張；新HEAD九項與36圖重新驗收。
