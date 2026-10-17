# MAC-FEEDBACK-005 — 原生對話設定與失敗回饋（0.1.4 / build5）

接續已驗收0.1.3 `d719bb93db7a52546824a4699c329934e5667bac`，Mac39022862301、VPS39022862279、Android39022862284九項同SHA SUCCESS、42張實圖。使用者要求繼續，Mac仍優先，Android／VPS UI候選不整合。

## 問題與正式行為

原ChatView的設定讀取失敗只把錯誤放在sheet後方，面板一直顯示載入；Token數字填錯會報JSON格式錯誤。Save在await後依賴全域selectedSession的revision，且成功直接關閉，無清楚已儲存與下一份修改的區分。

`ChatSettingsView`與`ChatSettingsController`綁定打開時的session ID／完整scope／revision。讀取顯示loading或面板內error與手動retry；取消的讀取不發布過期結果。欄位／JSON／Token／名稱錯誤分別解釋，驗證失敗不發mutation；名稱仍遵守後端原200-byte與控制字元限制。工具定義僅在明確傳送時使用，沒有自動推論。

儲存捕捉當次draft，disabled重複提交，先保存對話參數，再使用原session revision改名；更新API未成功時保留原revision／輸入，不假裝已全部保存。部分成功會明說參數已存但名稱未改。成功面板留開、顯示已儲存；下一份編輯顯示尚未儲存，晚完成不重寫draft／不關閉另一個設定面板。refresh失敗由原全域錯誤呈現，不把已提交的mutation稱失敗。

表單可捲動、錯誤與Save固定在footer；深淺色使用既有原生元件。sheet開啟時停用會換工作區／新增對話／新增供應商／開另一個sheet的選單捷徑，背景composer的傳送／Escape停止也暫停，避免組字或設定期間誤觸；不改原Store推論、DB、網路權限或後端scope owner。

## 驗證

`ChatSettingsTests`新增三個核心測試：Token邊界及保留輸入、tools array／解析與typed arguments、UTF-8名稱bound與不可見控制字元。原15個核心全保留，現在每架構18項。

真NativeSmoke透過production controller與正式packaged engine驗：初始讀取、真401錯誤與同面板重試、invalid Token前後真API設定完全一致、A面板打開後選到B仍只保存A的system／777／streaming／title、B仍512且空system；成功後輸入888仍留面板但真API保持777，關閉回A歷史／原草稿不變。四張新增實際sheet為ready／validation／read error／saved＋next draft；原21張完整保留，每架構25／總50圖。測試不宣稱XCUI全點擊。

本提交為候選。須新HEAD九項全綠及50圖審閱後更新PR #21 canonical驗收與交付DMG。保留原120秒程序、4秒條件、500ms閱讀／25秒串流及完整1249字SHA256，不增timeout／不減舊workload。沒有merge、遠端部署、Apple Developer ID／公證。新增供應商與媒體結果的進一步表單／儲存體驗另片處理，不將本片宣稱為全App回饋完成。
