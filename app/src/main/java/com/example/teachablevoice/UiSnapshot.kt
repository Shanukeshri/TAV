package com.example.teachablevoice

/**
 * Represents a frozen state of a third-party application's UI.
 * 
 * Why snapshots are used:
 * We capture a "snapshot" so that when the user switches back to the Teachable Voice app,
 * we can render the last seen UI of the target application without it being overwritten 
 * by our own app's accessibility events.
 */
data class UiSnapshot(
    val packageName: String,
    val rootNode: NormalizedNode,
    val timestamp: Long
)
