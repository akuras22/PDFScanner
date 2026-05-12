package com.akuras.pdfscanner

fun filterPdfsByQuery(items: List<SavedPdf>, query: String): List<SavedPdf> {
    val q = query.trim()
    if (q.isEmpty()) return items
    return items.filter { it.name.contains(q, ignoreCase = true) }
}
