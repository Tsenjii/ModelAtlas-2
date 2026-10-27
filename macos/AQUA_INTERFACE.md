# Mac Aqua 水光介面

設定 → 一般 → 外觀提供「經典」與「Aqua · 水光玻璃」。Aqua可選Kotoha／伊蕾娜兩張原圖及各自的Q版，預設保持經典。外觀、背景與重點色只保存在這台Mac的非敏感偏好，不修改模型scope／聊天草稿、啟用研究或發送推論。

四張圖片與已驗收VPS版相同。兩張JPEG及兩張先前生成的透明PNG保留原檔bytes，檔案／hash見Resources/Wallpapers/manifest.json；PNG與VPS無損WebP的解碼RGBA逐byte相同。圖片隨App打包，僅選用Aqua才讀取所選圖片，設定預覽共用有界快取，不由外部網址取得。

沿用RootView、原三主導航／右上設定與正式ChatView／ProvidersView／ModelsView及表單。原圖依原始比例完整置於右側並柔化圖片邊緣，透明Q版置於右下；寬桌面為內容保留左側閱讀區，避免卡片遮住人物臉部。窄窗與實色模式使用完整內容寬度；聊天、側欄及卡片採可讀水光底色。非法介面值退回經典、非法背景值退回Kotoha；不把偏好當作檔案路徑。設定另提供「減少背景透明度」；使用者選用或系統啟用「減少透明度」時，背景改為實色並隱藏插畫，遵循Apple對accessibilityReduceTransparency的定義：https://developer.apple.com/documentation/swiftui/environmentvalues/accessibilityReduceTransparency 。

本片MAC-AQUA-015W仍候選。新增獨立第四個原生階段，實際點選正式設定按鈕，檢查四張圖、scope／聊天及供應商草稿、經典往返、實際第二次啟動保留偏好與實際App減少透明度按鈕，原12張新圖保留並加真系統實色圖，共13張。原89核心、104張原生圖及原smoke／parity／research的所有情境與上限保留；新階段另設180秒外層與三次啟動共150秒。正式App只讀系統偏好；CI以可逆fixture在三次獨立啟動前實際切換系統狀態，結束或失敗後還原精確原值，非注入SwiftUI環境值。自己的同源九項、實際產物及110相關視圖前不接受，不宣稱Android原生Aqua或整版已完成。

為快速定位新增原生操作，Aqua階段先行執行；成功後原核心、smoke、parity、research全部照跑，原上限不變。失敗保留逐步進度／視窗與marker幾何及失敗截圖，先拆除實際modal再走既有關閉流程；不將失敗或部分圖片數當作驗收。第三候選第一個設定操作失敗的精確根因仍待真原生診斷，不因七項成功改稱全綠。

官方GitHub macOS映像預設啟用減少透明度（runner-images 19874e7a／images/macos/scripts/build/configure-system.sh）。Aqua驗證因此於前兩次App啟動實際觀察系統false，測四張插畫、保存重啟與正式App實色選項；第三次觀察系統true且App選項false，檢查真正系統優先的實色背景。fixture以finally還原原鍵是否存在及布林值；只有CI或明確MODELATLAS_ALLOW_AQUA_SYSTEM_TEST=1才可執行，正式App不寫系統設定。發布稽核要求三個不同App／engine PID正常退出、false／false／true實際回報與還原收據，原12新圖不可被替換。

第五候選f581874功能九項已通過，實際ARM117圖／89核心及真系統false／false／true與完整還原收據已核對；自行檢視13個Aqua畫面後，原圖上方填滿裁切只剩頭頂／帽子，且Q版臉部被歡迎卡擋住，因此視覺未接受。現保留原比例、縮小Q版並在寬視窗保留右側插畫空間；正式三頁仍用同一view／caller，原13新圖與104舊圖／89核心、草稿／scope、150／180秒及原所有驗證不減，自己的新來源完整驗收前仍候選。
