# 研究 token／USD 預算（RESEARCH-RESOURCE-015G）

本片接續015F每日推論次數，提供正式VPS研究工作區的token與USD預留、逐請求結算、全域及穩定逐憑證上限。此處是本機核准政策；供應商可能回報超出預留，不能宣稱Provider帳單硬上限、官方剩餘quota或token估算。完整WISH-CRAWL-001及下一版仍PARTIAL。公開0.8.5-preview.1及發布請求／版本／tag／附件保持，PR #20維持Draft、不merge、不部署。

## 正式操作

Models → 我的模型 → 全球研究，或Settings的同一研究工作區 →「研究 token／USD 預算」。可設定每日全部研究及每份憑證預設的token／USD上限、每次研究預留額、精確Provider／Account／Credential覆寫及改用預設。上限留空代表未啟用，0明確停止新核准；每次預留必須大於0。初值上限均未啟用，每次預留256 token／0.01 USD；這些初值不是模型用量或價格預測。

token上限為0–1,000,000,000,000的整數，每次token預留1–1,000,000；USD為不超過1,000,000的非負十進位字串，每次USD預留須為正。寫入要求所有相關欄位明示，只有上限可用null停用。非數值、負值、分數token、遺漏欄位或未知欄位均拒絕，不以零代替。

穩定scope為Provider×Account×Credential，同一scope跨Key版本、模型、Group、端點及研究輪次共用。每日總上限同時約束全部憑證；不以新增輪次或Key輪換重置。這不是任意共用Account／Project／組織quota pool，其他引擎或客戶端耗用未計入。

介面分開呈現「已計入」與「供應商回報小計」、未知筆數、待結算及超出預留筆數。已計入包含有效回報與保留的預留，不能全部讀作已消費實測。完整回報總量只在所有請求該項均有可靠成功回報時提供，否則為null；已知小計仍可查。沒有請求時的0是已知空集合。原015F次數API的token_usage／currency_cost／provider_remaining仍為null，不能把次數乘預留當成供應商實際消費。

## 入場與結算

新CRAWLER_VERIFY推論在原內容／能力／協定檢查後，與原run／task HTTP／probe、每日推論次數、訊息／工具／請求紀錄同一SQLite transaction核准並預留。先檢查全域，再檢查精確憑證；當日已計入加本次預留超過任一已啟用上限便拒絕，沒有新增Provider HTTP或部分扣額。讀不到政策、覆寫、紀錄或無法寫入時拒絕。啟用token上限時，請求最大輸出亦不得高於本次token預留；輸入及其他費用仍可能超出預留，故不能保證實際用量不超標。

同一入場的UTC日與核准時間沿用015F已寫入的紀錄，不再次用牆鐘挑選另一日。結算始終歸原入場日，即使HTTP跨日才完成。每日UTC 00:00（台灣08:00）另起一天，舊紀錄保留。

請求telemetry成功保存後，另開transaction寫入不可變結算；與telemetry並非同一次commit。成功且可靠的單項回報可取代該項預留，釋放未用部分；另一項未知仍保留原預留。缺用量、幣別未明、無效／矛盾回報、失敗、取消、解析失敗及中斷不當作0。非成功結果至少保留原預留，較大可靠回報仍完整計入。進程中止或結算寫入失敗留下全額待結算預留，成功答案／原歷史仍保留，並標記UNAVAILABLE_RESERVATION_RETAINED。

超出預留保存未截斷的原回報，後續核准以這筆較大用量計算；仍由已啟用上限與下次預留判斷，不宣稱任何超出預留都會永久鎖住研究。結算重入不覆寫第一次結果。沒有背景重試結算、追溯退款或額外帳單查詢。

## 來源、單位與精度

token回報沿用同一實際請求的原協定資料，不採模型名稱、標價或字數推算。Chat Completions／Responses可用明確總量，或同時已知的輸入加輸出；總量小於已知必要組成、快取超過輸入、推理超過輸出或任一負值保持未知。推理已在上述輸出內，不重複相加。原生JSON數字保留十進位整數，不先轉IEEE754再轉回。

Gemini總量包含prompt、tool-use prompt、thoughts及candidate；要求明確totalTokenCount且不得小於已知各組成之和，缺總量保持未知。Anthropic input_tokens不含cache read／creation；只有輸入、輸出、兩種快取均明確時才可推得總量，缺任一快取欄位不猜零。原telemetry的輸入欄位仍保留供應商原語義，不把研究總量冒充原輸入欄位。自訂協定缺明確完整總量時也不猜總量。

USD只接受既有同請求provider_charge明示USD的資料；目前既有OpenRouter精確HTTPS origin契約可提供此單位。泛用cost數字、不同來源、未知幣別與名目／effective quote不換算成USD。精確0可結算為0；無效cost出現後不借另一片段有效值抹掉未知。金額採任意精度有理數與有限十進位輸出，0.1+0.2精確為0.3，1e-4096保持正值；累積token及API總量採十進位字串，超過JavaScript安全整數及int64加總範圍仍不溢位。

協定依據：[Gemini UsageMetadata](https://ai.google.dev/api/generate-content#UsageMetadata)、[Anthropic快取用量](https://platform.claude.com/docs/en/build-with-claude/prompt-caching#tracking-cache-performance)、[OpenRouter usage accounting](https://openrouter.ai/docs/cookbook/administration/usage-accounting)。這些來源支持欄位語義，不把本機fixture當成實際Provider長期帳單證據。

## 暫停、保存與升級

正式worker持久保存BUDGET_EXHAUSTED、受限項目／scope、已計入／預留／上限與重設時間。未發送的拒絕不消耗原三次網路重試。修改政策、預留額或跨日不自動恢復手動研究，仍須「恢復研究」。原公開排程禁止私人成本／模型推論、來源確認與4-flight限制保持；新增預算沒有授予私人排程權限。

日常聊天與重用同scope、來源、憑證版本有效觀測的零額外HTTP研究不扣這份預算。取消仍不計入My Uptime分母。沒有自動路由、fallback或輪換Key。

正式GET／POST為/api/v1/research/resource-budget。政策revision為全域CAS；過期409不重送。讀取失敗保留last-good與草稿，更新不偷偷改草稿CAS基線，使用者可明確捨棄重讀。保存成功後只重讀，紀錄寫入失敗則整筆設定回滾。憑證100筆、變更紀錄50筆分頁，舊scope與用量仍可查。48px操作、320／375／1280、深淺色與200%沿用原UI。

schema27→28先保存並驗證原一致快照，再新增五份本機政策／覆寫／入場／結算／變更表。schema28完整SQLite快照包含這些紀錄，workspace同步與portable allowlist排除執行授權。015F較舊入場不虛構成015G預留，API明示今日未追溯筆數，介面顯示本機啟用時間；不把這些請求當已知零。

## 驗證與失敗歷史

- 初始正式Handler反例0.066s FAIL404：尚無本片入口，不當作既有預算已可用。
- 擴充19.574s FAIL來自新fixture未明確加入My Models；改用既有正式會員操作，不放寬scope／membership。修後六個後端函式race21.594s成功。
- 追加原生協定真TLS反例1.117s FAIL：Responses大整數經float64丟精度、Gemini未核對tool-use prompt、Anthropic完整快取未能結算。已補保留數字精度及完整組成，缺失／矛盾仍未知；連同原工具續接／receipt等聚焦race40.246s成功。此後另補快取／推理子集矛盾案例，完整最終回歸另驗。
- 真午夜反例0.092s FAIL：015F在前一日入場，015G再次讀牆鐘跨日而借新日空額度；改用同transaction既有入場日，修後race2.356s成功。原日拒絕與新日一次HTTP均有精確斷言。
- 追加輸出預留範圍、無效cost後再回報0、截斷回應的較大／較小回報，聚焦race4.987s成功；失敗不釋放預留，也不截斷較大回報。另以兩筆int64最大回報驗證累積值18455751272964292663仍為精確字串。
- 正式Browser6.800s成功，在原每日流程內追加token／USD操作，原全部斷言、4 catalog／2 inference、9舊圖不減；新增草稿／last-good、CAS、獨立拒絕無扣額、明確恢復、unknown保留、覆寫／重設及6圖。完整33流程／395圖仍須最終來源驗證。
- 後端涵蓋真TLS飛行中原子預留、兩組各20工作／原4-flight、精確0及超額、Key輪換、DB入場／telemetry／結算／journal故障、503／取消／重啟／UTC日界、正式worker恢復與既有流量重用、真HTTPS同步／portable隔離、27→28一致升級、102憑證與55筆歷史分頁。

本機完整Go race412.629s、最終十個預算函式race41.098s、vet與27發布回歸0.037s成功；後者追加輸出預留、部分回報及int64累積上界，原測試不刪減。完整33 Browser／395圖正在原5min上限執行，最終來源九項及產物仍待驗。本片仍為候選；原生全部測試、原資源負載、同一來源九項CI及九份實際產物核對完成前不接受、不接下一片。之前015F或其他來源的綠燈不能替代本片。
