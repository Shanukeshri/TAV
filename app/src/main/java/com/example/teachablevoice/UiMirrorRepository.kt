package com.example.teachablevoice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A singleton repository that holds the latest captured third-party UI snapshot.
 * MainActivity observes this StateFlow to render the mirror.
 */
object UiMirrorRepository {
    private val _snapshotFlow = MutableStateFlow<UiSnapshot?>(null)
    val snapshotFlow: StateFlow<UiSnapshot?> = _snapshotFlow.asStateFlow()

    fun updateSnapshot(snapshot: UiSnapshot) {
        _snapshotFlow.value = snapshot
    }
}
