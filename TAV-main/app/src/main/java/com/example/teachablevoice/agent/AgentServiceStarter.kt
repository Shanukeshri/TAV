package com.example.teachablevoice.agent

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

fun Context.startAgentForegroundService(intent: Intent) {
    ContextCompat.startForegroundService(this, intent)
}
