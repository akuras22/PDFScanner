package com.akuras.pdfscanner

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileNamePatternTest {

    @Test
    fun resolvesAllKnownTokens() {
        val value = resolveFileName("scan_{yyyy}{MM}{dd}_{HH}{mm}{ss}")
        assertFalse(value.contains("{"))
        assertTrue(value.startsWith("scan_"))
    }

    @Test
    fun keepsUnknownTokensUntouched() {
        val value = resolveFileName("doc_{unknown}_{yy}")
        assertTrue(value.contains("{unknown}"))
        assertFalse(value.contains("{yy}"))
    }
}
