package com.example.teachablevoice

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap

// ──────────────────────────────────────────────
// Data class for a launchable app
// ──────────────────────────────────────────────

data class LaunchableApp(
    val label: String,
    val packageName: String,
    val icon: Drawable?
)

// ──────────────────────────────────────────────
// App list loading
// ──────────────────────────────────────────────

/**
 * Queries the device for all launchable apps (apps with a MAIN/LAUNCHER intent).
 * Excludes our own app from the list.
 */
fun getInstalledApps(context: Context): List<LaunchableApp> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
    }

    val resolvedApps: List<ResolveInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(intent, 0)
    }

    return resolvedApps
        .filter { it.activityInfo.packageName != context.packageName }
        .map { ri ->
            LaunchableApp(
                label = ri.loadLabel(pm).toString(),
                packageName = ri.activityInfo.packageName,
                icon = try { ri.loadIcon(pm) } catch (_: Exception) { null }
            )
        }
        .sortedBy { it.label.lowercase() }
        .distinctBy { it.packageName }
}

/**
 * Launches an app by package name.
 */
fun launchApp(context: Context, packageName: String) {
    val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
    if (launchIntent != null) {
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
    }
}

// ──────────────────────────────────────────────
// Colors
// ──────────────────────────────────────────────
private val LauncherBgStart = Color(0xFF0A0A14)
private val LauncherBgEnd = Color(0xFF12121F)
private val LauncherCardBg = Color(0xFF16162A)
private val LauncherCardBorder = Color(0xFF2A2A45)
private val LauncherAccent = Color(0xFF7C4DFF)
private val LauncherAccentBlue = Color(0xFF448AFF)
private val LauncherTextPrimary = Color(0xFFF0F0FF)
private val LauncherTextSecondary = Color(0xFFA0A0C0)
private val LauncherTextMuted = Color(0xFF606080)
private val LauncherSearchBg = Color(0xFF1E1E30)
private val LauncherSearchBorder = Color(0xFF3D3D5C)

// ──────────────────────────────────────────────
// App Launcher Screen Composable
// ──────────────────────────────────────────────

@Composable
fun AppLauncherScreen() {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    val allApps = remember { getInstalledApps(context) }
    val filteredApps = remember(searchQuery, allApps) {
        if (searchQuery.isBlank()) allApps
        else allApps.filter {
            it.label.contains(searchQuery, ignoreCase = true) ||
            it.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(LauncherBgStart, LauncherBgEnd)))
    ) {
        // Search bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(LauncherSearchBg)
                .border(1.dp, LauncherSearchBorder, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            BasicTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                textStyle = TextStyle(color = LauncherTextPrimary, fontSize = 14.sp),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                decorationBox = { inner ->
                    if (searchQuery.isEmpty()) {
                        Text("🔍  Search apps…", color = LauncherTextMuted, fontSize = 14.sp)
                    }
                    inner()
                }
            )
        }

        // App count
        Text(
            "${filteredApps.size} apps available",
            color = LauncherTextMuted, fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
        )

        // App list
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(filteredApps, key = { it.packageName }) { app ->
                AppListItem(app = app) {
                    launchApp(context, app.packageName)
                }
            }
            item {
                Spacer(modifier = Modifier.height(80.dp))
            }
        }
    }
}

@Composable
fun AppListItem(app: LaunchableApp, onLaunch: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(LauncherCardBg)
            .border(1.dp, LauncherCardBorder, RoundedCornerShape(10.dp))
            .clickable { onLaunch() }
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .animateContentSize()
    ) {
        // App icon
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF252540)),
            contentAlignment = Alignment.Center
        ) {
            val icon = app.icon
            if (icon != null) {
                val bitmap = remember(app.packageName) {
                    try { icon.toBitmap(96, 96) } catch (_: Exception) { null }
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = app.label,
                        modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp))
                    )
                } else {
                    Text("📱", fontSize = 22.sp)
                }
            } else {
                Text("📱", fontSize = 22.sp)
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // App info
        Column(modifier = Modifier.weight(1f)) {
            Text(
                app.label,
                color = LauncherTextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                app.packageName,
                color = LauncherTextMuted,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Launch button
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(
                    Brush.horizontalGradient(
                        listOf(LauncherAccent.copy(alpha = 0.2f), LauncherAccentBlue.copy(alpha = 0.2f))
                    )
                )
                .border(1.dp, LauncherAccent.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                .clickable { onLaunch() }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("Open ▶", color = LauncherAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}
