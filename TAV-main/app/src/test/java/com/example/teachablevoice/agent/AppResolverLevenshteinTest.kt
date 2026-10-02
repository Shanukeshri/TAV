package com.example.teachablevoice.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppResolverLevenshteinTest {
    @Test
    fun levenshtein_identical() {
        assertEquals(0, AppResolver.levenshtein("ola", "ola"))
    }

    @Test
    fun levenshtein_typo() {
        assertTrue(AppResolver.levenshtein("uber", "ubre") <= 2)
    }
}
