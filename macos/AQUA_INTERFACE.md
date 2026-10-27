# Mac Aqua 水光介面

設定 → 一般 → 外觀提供「經典」與「Aqua · 水光玻璃」。Aqua可選Kotoha／伊蕾娜兩張原圖及各自的Q版，預設保持經典。外觀、背景與重點色只保存在這台Mac的非敏感偏好，不修改模型scope／聊天草稿、啟用研究或發送推論。

四張圖片與已驗收VPS版相同。兩張JPEG及兩張先前生成的透明PNG保留原檔bytes，檔案／hash見Resources/Wallpapers/manifest.json；PNG與VPS無損WebP的解碼RGBA逐byte相同。圖片隨App打包，僅選用Aqua才讀取所選圖片，設定預覽共用有界快取，不由外部網址取得。

沿用RootView、原三主導航／右上設定與正式ChatView／ProvidersView／ModelsView及表單。原圖滿版裁切，透明Q版置於右下；聊天、側欄及卡片採可讀水光底色。非法介面值退回經典、非法背景值退回Kotoha；不把偏好當作檔案路徑。設定另提供「減少背景透明度」；使用者選用或系統啟用「減少透明度」時，背景改為實色並隱藏插畫，遵循Apple對accessibilityReduceTransparency的定義：https://developer.apple.com/documentation/swiftui/environmentvalues/accessibilityReduceTransparency 。

本片MAC-AQUA-015W仍候選。新增獨立第四個原生階段，實際點選正式設定按鈕，檢查四張圖、scope／聊天及供應商草稿、經典往返、實際第二次啟動保留偏好與實際App減少透明度按鈕，共12張新圖。原89核心、104張原生圖及原smoke／parity／research的所有情境與上限保留；新階段另設180秒外層與兩次啟動共150秒。系統偏好為唯讀，不透過測試覆寫或更改主機系統偏好；由相同正式實色渲染路徑檢查App選項。自己的同源九項、實際產物及108相關視圖前不接受，不宣稱Android原生Aqua或整版已完成。
