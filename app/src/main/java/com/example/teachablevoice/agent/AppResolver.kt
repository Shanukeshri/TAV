package com.example.teachablevoice.agent

import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent

object AppResolver {
    fun findTargetApp(context: Context, goal: String): String? {
        val pm = context.packageManager
        // Only query launchable apps to avoid background services
        val intent = Intent(Intent.ACTION_MAIN, null)
        intent.addCategory(Intent.CATEGORY_LAUNCHER)
        
        val apps = pm.queryIntentActivities(intent, 0)
        val lowerGoal = goal.lowercase()
        
        var bestMatchPkg: String? = null
        var longestMatch = 0
        
        for (resolveInfo in apps) {
            val appName = resolveInfo.loadLabel(pm).toString().lowercase()
            if (appName.isNotEmpty() && lowerGoal.contains(appName)) {
                if (appName.length > longestMatch) {
                    longestMatch = appName.length
                    bestMatchPkg = resolveInfo.activityInfo.packageName
                }
            }
        }
        
        // Semantic fallbacks for common intents
        if (bestMatchPkg == null) {
            if (lowerGoal.contains("message") || lowerGoal.contains("text ")) {
                // Try to find default SMS app or common messaging
                bestMatchPkg = apps.find { it.loadLabel(pm).toString().lowercase().contains("message") }?.activityInfo?.packageName
            } else if (lowerGoal.contains("call ") || lowerGoal.contains("dial ")) {
                bestMatchPkg = apps.find { it.loadLabel(pm).toString().lowercase().contains("phone") }?.activityInfo?.packageName
            }
        }
        
        return bestMatchPkg
    }
}
