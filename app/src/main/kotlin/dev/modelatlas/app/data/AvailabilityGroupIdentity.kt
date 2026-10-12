package dev.modelatlas.app.data

/**
 * CR-003 availability group identity（B3，Room v14）。
 *
 * lossless group identity：null（未知 scope）→ "n"；非 null（含空字串、Unicode、大小寫差異）
 * → "v" + 原值，不 trim、不 casefold。null 與 "" 必須不同（"n" ≠ "v"），空 group 不會被
 * 當成 wildcard 也不會與 null 混淆。此函式為鎖定契約：唯一可寫範圍是 Entity
 * `groupIdentity` 欄位與 DAO insert 正規化／byExactScope 讀取，不得另改語意。
 */
fun availabilityGroupIdentity(group: String?): String = if (group == null) "n" else "v" + group
