package com.example.teachablevoice

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A singleton repository that holds the latest captured third-party UI snapshot
 * and its corresponding screenshot.
 */
object UiMirrorRepository {
    private val _snapshotFlow = MutableStateFlow<UiSnapshot?>(null)
    val snapshotFlow: StateFlow<UiSnapshot?> = _snapshotFlow.asStateFlow()

    private val _screenshotFlow = MutableStateFlow<Bitmap?>(null)
    val screenshotFlow: StateFlow<Bitmap?> = _screenshotFlow.asStateFlow()

    /** The real screen dimensions of the captured device */
    private val _screenDimensions = MutableStateFlow(Pair(1080, 2400))
    val screenDimensions: StateFlow<Pair<Int, Int>> = _screenDimensions.asStateFlow()

    @Volatile
    var lastContentChangeAt: Long = 0L
        private set

    @Volatile
    var lastSemanticHash: String = ""
        private set

    fun updateSnapshot(snapshot: UiSnapshot) {
        val hash = snapshot.rootNode.semanticHash()
        if (hash != lastSemanticHash) {
            lastSemanticHash = hash
            lastContentChangeAt = snapshot.timestamp
        }
        _snapshotFlow.value = snapshot
    }

    fun noteContentChangeEvent() {
        lastContentChangeAt = System.currentTimeMillis()
    }

    fun updateScreenshot(bitmap: Bitmap) {
        // Don't recycle old bitmap — Compose may still be rendering it; let GC collect
        _screenshotFlow.value = bitmap
        _screenDimensions.value = Pair(bitmap.width, bitmap.height)
    }
}
