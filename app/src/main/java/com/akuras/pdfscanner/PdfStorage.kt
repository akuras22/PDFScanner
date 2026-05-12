package com.akuras.pdfscanner

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import java.io.File
import java.io.IOException

data class SavedPdf(
    val name: String,
    val uri: Uri,
    val isExternal: Boolean = false,
)

object PdfStorage {
    fun savePdfToDownloads(context: Context, sourceUri: Uri, pattern: String): Uri? {
        val resolver = context.contentResolver
        val fileName = "${resolveFileName(pattern)}.pdf"
        val destinationUri = resolver.insert(
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/PDFScanner")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        ) ?: return null

        return try {
            resolver.openInputStream(sourceUri).use { input ->
                resolver.openOutputStream(destinationUri).use { output ->
                    if (input == null || output == null) {
                        resolver.delete(destinationUri, null, null)
                        return null
                    }
                    input.copyTo(output)
                }
            }
            resolver.update(destinationUri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
            destinationUri
        } catch (_: IOException) {
            resolver.delete(destinationUri, null, null)
            null
        }
    }

    fun querySavedPdfs(context: Context): List<SavedPdf> {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val items = mutableListOf<SavedPdf>()
        resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.MIME_TYPE}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf("application/pdf", "${Environment.DIRECTORY_DOWNLOADS}/PDFScanner/"),
            "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIdx)
                val name = cursor.getString(nameIdx) ?: "scan_$id.pdf"
                items += SavedPdf(
                    name = name,
                    uri = ContentUris.withAppendedId(collection, id)
                )
            }
        }
        return items
    }

    fun mergePdfsToDownloads(context: Context, sourceUris: List<Uri>, pattern: String): Uri? {
        if (sourceUris.size < 2) return null
        val resolver = context.contentResolver
        val fileName = "${resolveFileName(pattern)}_merged.pdf"
        val destinationUri = resolver.insert(
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/PDFScanner")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        ) ?: return null

        val mergedDocument = PdfDocument()
        return try {
            var outputPageNumber = 0
            sourceUris.forEach { sourceUri ->
                outputPageNumber = appendPdfPages(context, mergedDocument, sourceUri, outputPageNumber)
            }
            if (outputPageNumber == 0) {
                resolver.delete(destinationUri, null, null)
                return null
            }
            resolver.openOutputStream(destinationUri)?.use { output ->
                mergedDocument.writeTo(output)
            } ?: run {
                resolver.delete(destinationUri, null, null)
                return null
            }
            resolver.update(destinationUri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
            destinationUri
        } catch (_: Exception) {
            resolver.delete(destinationUri, null, null)
            null
        } finally {
            mergedDocument.close()
        }
    }

    fun reorderPdfPages(context: Context, targetUri: Uri, pageOrder: List<Int>): Boolean {
        if (pageOrder.isEmpty()) return false
        val tempFile = try {
            File.createTempFile("reordered_", ".pdf", context.cacheDir)
        } catch (_: IOException) {
            return false
        }
        val document = PdfDocument()
        return try {
            val sourceFd = context.contentResolver.openFileDescriptor(targetUri, "r") ?: return false
            sourceFd.use { fd ->
                PdfRenderer(fd).use { renderer ->
                    if (renderer.pageCount != pageOrder.size) return false
                    var outputPageNumber = 0
                    pageOrder.forEach { sourcePageIndex ->
                        if (sourcePageIndex !in 0 until renderer.pageCount) return false
                        renderer.openPage(sourcePageIndex).use { page ->
                            val width = maxOf(page.width, 1)
                            val height = maxOf(page.height, 1)
                            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                            val pageInfo = PdfDocument.PageInfo.Builder(width, height, outputPageNumber + 1).create()
                            val newPage = document.startPage(pageInfo)
                            newPage.canvas.drawColor(Color.WHITE)
                            newPage.canvas.drawBitmap(bitmap, 0f, 0f, null)
                            document.finishPage(newPage)
                            bitmap.recycle()
                            outputPageNumber++
                        }
                    }
                }
            }
            tempFile.outputStream().use(document::writeTo)
            context.contentResolver.openOutputStream(targetUri, "wt")?.use { output ->
                tempFile.inputStream().use { input -> input.copyTo(output) }
            } ?: return false
            true
        } catch (_: Exception) {
            false
        } finally {
            document.close()
            tempFile.delete()
        }
    }

    fun getPdfPageOrder(context: Context, uri: Uri): List<Int> {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                PdfRenderer(fd).use { renderer -> List(renderer.pageCount) { it } }
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun renderPdfThumbnail(context: Context, uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                PdfRenderer(fd).use { renderer ->
                    if (renderer.pageCount == 0) return null
                    renderer.openPage(0).use { page ->
                        val width = 300
                        val height = (width * page.height.toFloat() / page.width).toInt()
                        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    fun sharePdf(context: Context, uri: Uri, name: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_pdf_chooser)))
    }

    fun openPdf(context: Context, uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.open_pdf_chooser)))
        } catch (_: Exception) {
            Toast.makeText(context, R.string.no_pdf_app, Toast.LENGTH_SHORT).show()
        }
    }

    fun deletePdf(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.delete(uri, null, null) > 0
        } catch (_: SecurityException) {
            false
        }
    }

    private fun appendPdfPages(
        context: Context,
        targetDocument: PdfDocument,
        sourceUri: Uri,
        startPageNumber: Int,
    ): Int {
        var pageNumber = startPageNumber
        try {
            context.contentResolver.openFileDescriptor(sourceUri, "r")?.use { fd ->
                PdfRenderer(fd).use { renderer ->
                    for (pageIndex in 0 until renderer.pageCount) {
                        renderer.openPage(pageIndex).use { page ->
                            val width = maxOf(page.width, 1)
                            val height = maxOf(page.height, 1)
                            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                            val pageInfo = PdfDocument.PageInfo.Builder(width, height, pageNumber + 1).create()
                            val newPage = targetDocument.startPage(pageInfo)
                            newPage.canvas.drawColor(Color.WHITE)
                            newPage.canvas.drawBitmap(bitmap, 0f, 0f, null)
                            targetDocument.finishPage(newPage)
                            bitmap.recycle()
                            pageNumber++
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return pageNumber
    }
}
