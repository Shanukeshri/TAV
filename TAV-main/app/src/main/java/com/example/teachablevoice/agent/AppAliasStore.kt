package com.example.teachablevoice.agent

import android.content.Context

/**
 * Learned utterance → package aliases ("cab app" → in.olacabs.customer).
 * Persisted in SharedPreferences; never stores passwords or node ids.
 */
class AppAliasStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun remember(utterance: String, packageName: String) {
        val key = normalize(utterance)
        if (key.isBlank() || packageName.isBlank()) return
        prefs.edit().putString(key, packageName).apply()
    }

    fun lookup(utterance: String): String? {
        val key = normalize(utterance)
        if (key.isBlank()) return null
        prefs.getString(key, null)?.let { return it }
        // token overlap: if any stored phrase is contained in the utterance
        val all = prefs.all
        for ((stored, pkg) in all) {
            if (pkg is String && (key.contains(stored) || stored.contains(key))) {
                return pkg
            }
        }
        return null
    }

    companion object {
        private const val PREFS = "tav_app_aliases"
        fun normalize(s: String): String = s.lowercase().trim().replace(Regex("\\s+"), " ")
    }
}
