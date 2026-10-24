> 2026-10-24接續：015I已由970d566同來源九項、九份實際產物與17實圖驗收所列範圍（verification/20261024/private-schedule-baseline.json）。唯一新片015J逐級研究／候選重驗及有效流量重用，自己的新來源完整九項前候選。D-017要求下一版穩定可用且完成主要正式流程，原完整願望、專用原生缺口與版本凍結不變；詳ACTIVE_TASK。

> 2026-10-24接續：015H已由9ba2905同來源九項、九份實際產物及16實圖驗收所列範圍（verification/20261024/research-pool-baseline.json）。唯一新片PRIVATE-RESEARCH-015I補明確私人排程／來源確認／排程每日預算／中斷不重播與本機確認歷史；Mac同源引擎取得後端，專用原生UI仍待補。自己的完整本機／九項與實際產物前候選；D-016要求片內驗收後連續推進，公開版本與原需求不變。詳ACTIVE_TASK、vps/RESEARCH_PRIVATE_SCHEDULES.md。

> 2026-10-24：015G已由abcffa0同來源九項及PR #20持久證據驗所列範圍；本輪重查九項仍SUCCESS。唯一新片015H共用研究額度池，正式VPS設定／原子用量與恢復；Mac內建同源引擎取得後端，Mac／Android專用原生呈現仍待補。完整本機與自己的九項／產物前候選；公開版本不變，原功能與願望保留。詳ACTIVE_TASK、RESEARCH_QUOTA_POOLS.md。

# ModelAtlas Research Policy

> 這是 Runtime Research Agent / Crawler 的當前 authoritative policy。
> `docs/DECISIONS.md` 的較新使用者決策可覆蓋本文件；任何 quota / model-strength 規則不得自行猜或按舊記憶套用。

## 1. Mission

持續建立「全球 recurring-free 強模型 API 情報圖譜」，不是一次性 web search 或短推薦清單。

目標：
- 找目前可用的強模型免費來源
- 優先 Coding / Agent / Reasoning
- 研究 recurring quota / rate limit / public/dynamic pools
- 驗證 model、free claim、API usability、authenticity
- 保存完整 Evidence、失敗與淘汰原因
- 累積可重用研究經驗

## 2. Main-result eligibility（最新使用者規則）

### 一般 Provider / 非 top-tier exception
主結果集 ordinary eligibility 必須同時：
- **RPD >= 100**
- **TPD >= 1,000,000**

兩者皆 inclusive hard gate。

另外模型能力必須達到使用者強模標準；一般以 DeepSeek V4.1 Flash-class 或更高作能力底線。未評分/未知模型只有在有可信 coding / agent / tool-use / strong external evaluation 時才可升級。

### Top-tier quota exception
例外**不是 GPT-family / Claude-family**，也不是任何固定模型家族。

當模型屬於 **Artificial Analysis（AA）最新有效榜單中，依使用者指定的最新總分最高前 4 名模型** 時，才具 top-tier quota exception 候選資格。

要求：
- 動態依最新 AA evidence 判定，不 hard-code 模型名字。
- 保存 ranking source / observedAt / score / rank。
- 進入 top 4 才取得例外；掉出 top 4 後恢復 ordinary gate。
- family-agnostic：GPT、Claude、Gemini、Grok、DeepSeek、GLM、Kimi、Qwen 或其他模型只看當下 AA top-4 條件。
- AA evidence 過期/缺失時不得猜排名。

### 無法量化 quota
RPM-only / public pool / dynamic pool / claimed unlimited / undisclosed quota：
- 不得當成 0。
- 不得當成 unlimited ground truth。
- 仍保存於完整 funnel，可進 HIGH_VALUE_GREY_ZONE / research backlog。
- 未證明 ordinary RPD+TPD gate 且又不符合 top-tier exception 時，不得冒充主結果集已符合硬門檻。

## 3. Recurring-free semantics

研究並分開保存：
- Daily Reset
- Rolling 24h / 7d
- Weekly / Monthly
- Per-model / Shared account
- RPM-only / TPM / TPD / RPD
- Public Pool / Dynamic Pool
- Unlimited claimed
- Unknown

ONE_TIME_TRIAL / WELCOME_BONUS / TEMPORARY_PROMOTION 不得冒充 recurring free。

## 4. Taiwan accessibility

驗證：
- 台灣能否正常註冊/取得
- QQ-only?
- 中國實名?
- 中國手機?
- 特定付款?
- Region blocked?
- *.edu.cn 等特殊資格限制

不可用於目標使用者時仍保存 candidate/evidence/rejection reason，不要從研究歷史消失。

## 5. Full funnel

CandidateState：
DISCOVERED / VERIFYING / VERIFIED / HIGH_VALUE_GREY_ZONE / STALE / FAILED / REJECTED。

每個 candidate 保存：
Provider / Website / Base URL / AccessMethod / Auth / Taiwan usability /
Models / Free claim / Quota claim / Sources / Evidence / Confidence / Status /
Rejection reason / Downgrade reason / Last checked。

UI / report 要能看到漏斗數量，不可「掃很多只展示最後幾個」。

## 6. Search strategy

禁止：
- 普通搜尋幾次就結束
- 只找 Groq/OpenRouter/HF
- 單信 aggregator
- 只看 `-free`
- 第一輪沒 quota 就說沒有
- public pool 當固定 daily quota
- external benchmark 當 local
- 同模型名稱就當真模型
- 猜缺資料

優先順序：
1. Live provider/model audit
2. LMSpeed / regional aggregator
3. Provider announcement
4. Live catalog / models
5. pricing/free-group/usage endpoints
6. frontend XHR / JSON
7. GitHub source / constants
8. direct API probe
9. ordinary web search

主動使用多語：
English / 中文 / Русский / 日本語 / 한국어 / Español / Português /
Bahasa Indonesia / 其他有效語言。

同一 Provider 不因不同語言重複計算。

## 7. LMSpeed

LMSpeed 是一級 Discovery Seed / enrichment，不是 ground truth。
可提供 provider/free offering/canonical/external TPS-TTFT/pricing-availability hint。
重要 free claim 必須獨立驗證；external 指標不能寫入 local telemetry。

## 8. Evidence-first

重要 claim 保存：
claim / source / source type / URL-or-endpoint / fetchedAt /
confidence / normalized evidence / verification status。

來源衝突保留衝突，不可無聲覆蓋。

## 9. Model authenticity

高價值模型考慮 fingerprint：
response metadata / provider metadata / context/capability behavior /
tool/reasoning behavior / known-good cross-provider comparison。

保存 confidence + probeVersion + timestamp；弱 evidence 不得宣稱 100% substitution。

## 10. Probe budget

Progressive：
DISCOVERED → passive → structured endpoint → minimal inference →
high-value deeper probe → VERIFIED。

正常 user traffic 優先作 benchmark；禁止暴力 load test 猜最大 RPM/TPM。

## 11. Research Run output

每輪至少輸出：
- scanned count
- high-signal count
- verified count
- grey-zone count
- rejected count
- new / updated providers
- important free/quota changes
- authenticity findings
- unresolved high-value candidates

完成後執行 Reflection。

## 12. Reflection

分析 source/query/language/provider-family/endpoint/auth/extraction/fingerprint/probe 成效。
輸出 versioned/reversible updates：
source weights / query patterns / provider recipes / endpoint/auth patterns /
alias rules / fingerprint strategies / candidate priorities / failed strategies。

禁止自改 system prompt、App source code、secret boundary、destructive permission policy。
