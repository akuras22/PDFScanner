package com.akuras.pdfscanner

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryFiltersTest {

    @Test
    fun returnsAllWhenQueryBlank() {
        val items = listOf(
            SavedPdf("Invoice.pdf", Uri.parse("content://a")),
            SavedPdf("Contract.pdf", Uri.parse("content://b")),
        )

        assertEquals(items, filterPdfsByQuery(items, " "))
    }

    @Test
    fun filtersCaseInsensitiveByName() {
        val invoice = SavedPdf("Invoice_March.pdf", Uri.parse("content://a"))
        val contract = SavedPdf("Contract.pdf", Uri.parse("content://b"))
        val items = listOf(invoice, contract)

        assertEquals(listOf(invoice), filterPdfsByQuery(items, "inVOICE"))
    }
}
