# 原生帳戶與 Key 工作區：MAC-PROVIDER-012

2026-10-18。使用者要求繼續優化並保留其他平台的優點。跨平台清單見 `../docs/CROSS_PLATFORM_STRENGTHS.md`。0.6.1 build12 為候選；未通過新 HEAD 的九項 CI 與實圖前不交付。

基線 0.6.0 build11，`48ff43f083bc846ce3b8b2638c75b74f3534c4d0`／tree `ae900692ac27591f0808cd5e60406ef0052f3dff`：Mac39112956833 三項、VPS39112956835 四項、Android39112956891 兩項全 SUCCESS；雙架構各 70 核心、68 靜態實圖、14 frame，136 靜態圖與28 frame 已檢視並交付。兩台系統 Reduce Motion=true，不宣稱一般動畫流暢度已驗證。

## 本片正式行為

- 帳戶以精簡卡片分組，顯示啟用／全部 Key 計數；逐把顯示用途、群組、明確狀態與操作選單。啟用不等於 healthy、quota 可用或模型已測通。
- 新增帳戶與新增 Key 各為原生表單；Key 的推論／帳戶 PAT 有用途說明，額外來源／auth／優先序收在進階區。Provider 工作區擁有草稿與狀態，切換分區不靠暫時按鈕 State 保存。
- 新增 Key、逐把／批次啟停及秘密值更新共用同一帳戶鎖；相同表單不能重複提交，其他帳戶仍可操作。Bulk 僅送出 exact Provider／Account 下需改變且 enabled 已知的 Key；Unknown 不猜成停用。
- 每份表單及 Key／帳戶原處顯示等待、已保存、失敗、已保存但刷新失敗。後者只提供清單重新讀取，不要求再 POST。失敗保留草稿；網路結果不明先提示確認清單。新增草稿在失敗時保留 ID，避免每次重試變成不同項目；若資料已存在造成409，可確認清單或明確清空草稿開始另一份，不自動當成功。
- 已確認保存只清原提交那一份的秘密值與名稱；等待期間編輯的新稿保留，且使用新 ID，避免下一份變成同項目的重複提交。秘密只在記憶體，不寫本機偏好／log／AI prompt。
- 秘密值更新與恢復版本分開選擇，恢復版本時不送另一欄的秘密。再次顯示帳戶／Key；僅已確認回應的版本才顯示。保留原 metadata Review／confirm／Undo、綁定來源與後端 scope 防護。

不改 Android／VPS UI、DB schema、站方 private-version guard、背景排程或推論路由。Provider 設定／端點等其餘表單仍列接續事項，不稱全管理情境完成。

## 驗證要求

保留原 70 項原生核心，增加8項 ProviderManagementTests：同帳戶互斥與不同帳戶並行、舊 lease 不解鎖新請求、exact-account bulk／Unknown／重複 ID、同 Key ID 跨帳戶／Provider、分開恢復／秘密值與無效輸入、新 Key 的固定來源／role／custom auth、成功不抹新稿及新 ID、帳戶草稿重試／下一稿。

NativeParity 走正式 packaged engine／SQLite／API 與相同 production Controller／View。新增48之前的43張 parity全部保留，再加5張帳戶清單、保存失敗、版本恢復、處理中及已保存但刷新失敗畫面，共48 parity＋25 smoke＝73張／架構、146張實圖；原28 frame保持。驗精確帳戶、重複提交零額外寫入、真正 401 後草稿與重試、真 commit 後讀取失敗不重做、版本／跨帳戶隔離、原模型／對話／草稿與推論計數不變。原25秒串流、1249字 SHA256／閱讀位置與120秒 parity上限不變。

最低macOS12；實際CI仍macOS15兩架構。新HEAD九項／78核心／146靜態圖與28 frame檢視前保持候選／Draft。完整大綱仍 PARTIAL；不merge、不改base、不部署。
