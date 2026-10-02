package com.example.teachablevoice.agent

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.util.Log

/**
 * Resolves a natural-language goal string to an installed app package name.
 *
 * Never silently falls back to Settings. Returns null when nothing matches so
 * the dialog manager can ask "Which app do you mean?"
 */
object AppResolver {

    private const val TAG = "AppResolver"
    private const val FUZZY_THRESHOLD = 0.35

    @Volatile
    private var cache: List<Pair<String, String>>? = null

    fun invalidateCache() {
        cache = null
    }

    fun rememberAlias(context: Context, utterance: String, packageName: String) {
        AppAliasStore(context).remember(utterance, packageName)
    }

    fun findTargetApp(context: Context, goal: String): String? {
        val lowerGoal = goal.lowercase().trim()
        if (lowerGoal.isBlank()) return null

        AppAliasStore(context).lookup(lowerGoal)?.let {
            Log.d(TAG, "Alias match: $it")
            return it
        }

        val apps = launcherApps(context)

        var bestPkg: String? = null
        var bestLen = 0
        for ((label, pkg) in apps) {
            if (label.isNotEmpty() && lowerGoal.contains(label)) {
                if (label.length > bestLen) {
                    bestLen = label.length
                    bestPkg = pkg
                }
            }
        }
        if (bestPkg != null) {
            Log.d(TAG, "Substring match: $bestPkg")
            return bestPkg
        }

        val goalWords = lowerGoal.split(Regex("\\s+"))
        var bestScore = Int.MAX_VALUE
        var fuzzyPkg: String? = null
        for ((label, pkg) in apps) {
            if (label.isBlank()) continue
            val tokenOverlap = tokenOverlapScore(goalWords, label.split(Regex("\\s+")))
            if (tokenOverlap >= 0.7) {
                Log.d(TAG, "Token overlap match: $pkg")
                return pkg
            }
            for (word in goalWords) {
                if (word.length < 3) continue
                val dist = levenshtein(word, label)
                val threshold = (label.length * FUZZY_THRESHOLD).toInt().coerceAtLeast(1)
                if (dist <= threshold && dist < bestScore) {
                    bestScore = dist
                    fuzzyPkg = pkg
                }
            }
        }
        if (fuzzyPkg != null) {
            Log.d(TAG, "Fuzzy match (dist=$bestScore): $fuzzyPkg")
            return fuzzyPkg
        }

        val aliasResult = semanticAlias(lowerGoal, apps)
        if (aliasResult != null) {
            Log.d(TAG, "Keyword match: $aliasResult")
        } else {
            Log.w(TAG, "No app match for: '$goal'")
        }
        return aliasResult
    }

    private fun launcherApps(context: Context): List<Pair<String, String>> {
        cache?.let { return it }
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val infos: List<ResolveInfo> = pm.queryIntentActivities(intent, 0)
        val mapped = infos.map { info ->
            info.loadLabel(pm).toString().lowercase() to info.activityInfo.packageName
        }
        cache = mapped
        return mapped
    }

    private fun semanticAlias(lowerGoal: String, apps: List<Pair<String, String>>): String? {
        fun firstContaining(vararg terms: String): String? =
            apps.firstOrNull { (label, _) -> terms.any { label.contains(it) } }?.second

        return when {
            lowerGoal.containsAny("message", "sms", "text message") ->
                firstContaining("message")
            lowerGoal.containsAny("call", "dial", "phone") ->
                firstContaining("phone", "dialer")
            lowerGoal.containsAny("browse", "browser", "website", "internet") ->
                firstContaining("chrome", "browser", "firefox", "edge")
            lowerGoal.containsAny("camera", "photo", "picture", "selfie") ->
                firstContaining("camera", "photo")
            lowerGoal.containsAny("music", "song", "playlist", "spotify") ->
                firstContaining("music", "spotify", "youtube music")
            lowerGoal.containsAny("map", "navigate", "direction") ->
                firstContaining("maps", "navigation", "waze")
            lowerGoal.containsAny("email", "mail", "gmail") ->
                firstContaining("gmail", "mail", "email", "outlook")
            lowerGoal.containsAny("calendar", "meeting", "schedule", "event") ->
                firstContaining("calendar")
            lowerGoal.containsAny("food", "order food", "deliver", "restaurant", "zomato", "swiggy") ->
                firstContaining("zomato", "swiggy", "uber eats", "food")
            lowerGoal.containsAny("cab", "taxi", "uber", "ola", "ride") ->
                firstContaining("uber", "ola", "rapido", "cab")
            else -> null
        }
    }

    private fun tokenOverlapScore(a: List<String>, b: List<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val sa = a.toSet()
        val sb = b.toSet()
        val inter = sa.intersect(sb).size
        return inter.toDouble() / minOf(sa.size, sb.size)
    }

    private fun String.containsAny(vararg terms: String): Boolean =
        terms.any { this.contains(it) }

    internal fun levenshtein(a: String, b: String): Int {
        val m = a.length
        val n = b.length
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j
        for (i in 1..m) for (j in 1..n) {
            dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1]
            else 1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
        }
        return dp[m][n]
    }
}
