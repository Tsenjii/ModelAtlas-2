package dev.modelatlas.app.data

import androidx.sqlite.db.SupportSQLiteDatabase

/** Test-only cleanup of explicitly owned provider fixtures before deleting FK-parent evidence. */
internal fun deleteOwnProviderAccountUsageRows(sql: SupportSQLiteDatabase, providerIds: Collection<String>) {
    if (providerIds.isEmpty()) return
    val placeholders = providerIds.joinToString(",") { "?" }
    val args = providerIds.toTypedArray<Any?>()
    val scopes = ArrayList<Pair<String, String>>()
    sql.query(
        "SELECT DISTINCT scopeKey, kind FROM provider_account_usage_facts WHERE providerId IN ($placeholders)",
        args,
    ).use { cursor ->
        while (cursor.moveToNext()) scopes += cursor.getString(0) to cursor.getString(1)
    }
    sql.execSQL("DELETE FROM provider_account_usage_facts WHERE providerId IN ($placeholders)", args)
    for ((scopeKey, kind) in scopes) {
        sql.execSQL("DELETE FROM provider_account_usage_heads WHERE scopeKey = ? AND kind = ?",
            arrayOf<Any?>(scopeKey, kind))
    }
}
