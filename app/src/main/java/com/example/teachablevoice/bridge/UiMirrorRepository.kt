package com.example.teachablevoice.bridge

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

    fun updateSnapshot(snapshot: UiSnapshot) {
        _snapshotFlow.value = snapshot
    }

    fun updateScreenshot(bitmap: Bitmap) {
        // Don't recycle old bitmap — Compose may still be rendering it; let GC collect
        _screenshotFlow.value = bitmap
        _screenDimensions.value = Pair(bitmap.width, bitmap.height)
    }
}
