package com.akuras.pdfscanner

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Render PDF pages at this multiple of their native size to preserve quality (~216 DPI). */
private const val RENDER_SCALE = 3
private const val TAG = "PdfStorage"

enum class PdfSortOrder {
    DATE_DESC,
    DATE_ASC,
    NAME_ASC,
    NAME_DESC,
    SIZE_DESC,
    SIZE_ASC,
}

data class SavedPdf(
    val name: String,
    val uri: Uri,
    val isExternal: Boolean = false,
    val sizeBytes: Long = 0L,
    val dateAdded: Long = 0L,
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
        } catch (e: IOException) {
            Log.e(TAG, "Failed to save scanned PDF", e)
            resolver.delete(destinationUri, null, null)
            null
        }
    }

    fun querySavedPdfs(context: Context, sortOrder: PdfSortOrder = PdfSortOrder.DATE_DESC): List<SavedPdf> {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val items = mutableListOf<SavedPdf>()
        val sort = when (sortOrder) {
            PdfSortOrder.DATE_DESC -> "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            PdfSortOrder.DATE_ASC -> "${MediaStore.MediaColumns.DATE_ADDED} ASC"
            PdfSortOrder.NAME_ASC -> "${MediaStore.MediaColumns.DISPLAY_NAME} COLLATE NOCASE ASC"
            PdfSortOrder.NAME_DESC -> "${MediaStore.MediaColumns.DISPLAY_NAME} COLLATE NOCASE DESC"
            PdfSortOrder.SIZE_DESC -> "${MediaStore.MediaColumns.SIZE} DESC"
            PdfSortOrder.SIZE_ASC -> "${MediaStore.MediaColumns.SIZE} ASC"
        }
        try {
            resolver.query(
                collection,
                arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.DATE_ADDED
                ),
                "${MediaStore.MediaColumns.MIME_TYPE}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf("application/pdf", "${Environment.DIRECTORY_DOWNLOADS}/PDFScanner/"),
                sort
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val dateIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIdx)
                    val name = cursor.getString(nameIdx) ?: "scan_$id.pdf"
                    items += SavedPdf(
                        name = name,
                        uri = ContentUris.withAppendedId(collection, id),
                        sizeBytes = cursor.getLong(sizeIdx),
                        dateAdded = cursor.getLong(dateIdx)
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query saved PDFs", e)
        }
        return items
    }

    fun renamePdf(context: Context, uri: Uri, newNameRaw: String): Boolean {
        val newName = newNameRaw.trim().let { if (it.endsWith(".pdf", true)) it else "$it.pdf" }
        if (newName.isBlank()) return false
        return try {
            context.contentResolver.update(
                uri,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, newName)
                },
                null,
                null
            ) > 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rename PDF $uri", e)
            false
        }
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
        } catch (e: Exception) {
            Log.e(TAG, "Failed to merge PDFs", e)
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
        } catch (e: IOException) {
            Log.e(TAG, "Failed to create temp file for reordering", e)
            return false
        }
        val document = PdfDocument()
        val scaleMatrix = Matrix().apply { setScale(1f / RENDER_SCALE, 1f / RENDER_SCALE) }
        return try {
            val sourceFd = context.contentResolver.openFileDescriptor(targetUri, "r") ?: return false
            sourceFd.use { fd ->
                PdfRenderer(fd).use { renderer ->
                    if (pageOrder.distinct().size != pageOrder.size || pageOrder.any { it !in 0 until renderer.pageCount }) {
                        return false
                    }
                    var outputPageNumber = 0
                    pageOrder.forEach { sourcePageIndex ->
                        renderer.openPage(sourcePageIndex).use { page ->
                            val width = maxOf(page.width, 1)
                            val height = maxOf(page.height, 1)
                            val bitmap = Bitmap.createBitmap(width * RENDER_SCALE, height * RENDER_SCALE, Bitmap.Config.ARGB_8888)
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                            val pageInfo = PdfDocument.PageInfo.Builder(width, height, outputPageNumber + 1).create()
                            val newPage = document.startPage(pageInfo)
                            newPage.canvas.drawColor(Color.WHITE)
                            newPage.canvas.drawBitmap(bitmap, scaleMatrix, null)
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
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reorder PDF pages", e)
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
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read PDF page order", e)
            emptyList()
        }
    }

    fun countPdfPages(context: Context, uri: Uri): Int? {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                PdfRenderer(fd).use { it.pageCount }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to count PDF pages", e)
            null
        }
    }

    fun renderPdfPage(context: Context, uri: Uri, pageIndex: Int, width: Int = 900): Bitmap? {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                PdfRenderer(fd).use { renderer ->
                    if (pageIndex !in 0 until renderer.pageCount) return null
                    renderer.openPage(pageIndex).use { page ->
                        val clampedWidth = maxOf(width, 1)
                        val height = (clampedWidth * page.height.toFloat() / maxOf(page.width, 1)).toInt().coerceAtLeast(1)
                        Bitmap.createBitmap(clampedWidth, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to render PDF page", e)
            null
        }
    }

    fun renderPdfThumbnail(context: Context, uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                PdfRenderer(fd).use { renderer ->
                    if (renderer.pageCount == 0) return null
                    renderer.openPage(0).use { page ->
                        val width = 300
                        val height = (width * page.height.toFloat() / maxOf(page.width, 1)).toInt().coerceAtLeast(1)
                        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to render PDF thumbnail", e)
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

    fun sharePdfs(context: Context, items: List<SavedPdf>) {
        if (items.isEmpty()) return
        val uris = ArrayList(items.map { it.uri })
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "application/pdf"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_pdf_chooser)))
    }

    fun copyPdfToUri(context: Context, sourceUri: Uri, destinationUri: Uri): Boolean {
        return try {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                context.contentResolver.openOutputStream(destinationUri, "wt")?.use { output ->
                    input.copyTo(output)
                    true
                }
            } ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy PDF via SAF", e)
            false
        }
    }

    fun openPdf(context: Context, uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.open_pdf_chooser)))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open PDF", e)
            Toast.makeText(context, R.string.no_pdf_app, Toast.LENGTH_SHORT).show()
        }
    }

    fun deletePdf(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.delete(uri, null, null) > 0
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to delete PDF", e)
            false
        }
    }

    /**
     * Exports every page of the PDF as a separate PNG file into a sub-folder
     * Downloads/PDFScanner/<baseName>/.
     * Returns the number of pages exported, or -1 on failure.
     */
    fun exportPdfAsPngs(context: Context, uri: Uri, baseName: String): Int {
        val resolver = context.contentResolver
        var count = 0
        return try {
            resolver.openFileDescriptor(uri, "r")?.use { fd ->
                PdfRenderer(fd).use { renderer ->
                    for (pageIndex in 0 until renderer.pageCount) {
                        renderer.openPage(pageIndex).use { page ->
                            val w = maxOf(page.width, 1)
                            val h = maxOf(page.height, 1)
                            val bitmap = Bitmap.createBitmap(w * RENDER_SCALE, h * RENDER_SCALE, Bitmap.Config.ARGB_8888)
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                            val imgName = "${baseName}_page${pageIndex + 1}.png"
                            val imgUri = resolver.insert(
                                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                                ContentValues().apply {
                                    put(MediaStore.MediaColumns.DISPLAY_NAME, imgName)
                                    put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                                    put(MediaStore.MediaColumns.RELATIVE_PATH,
                                        "${Environment.DIRECTORY_DOWNLOADS}/PDFScanner/$baseName")
                                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                                }
                            )
                            if (imgUri != null) {
                                resolver.openOutputStream(imgUri)?.use { out ->
                                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                                }
                                resolver.update(imgUri, ContentValues().apply {
                                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                                }, null, null)
                                count++
                            }
                            bitmap.recycle()
                        }
                    }
                }
            }
            count
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export PDF as PNG", e)
            if (count > 0) count else -1
        }
    }

    /**
     * Exports the PDF as an HTML file with each page embedded as a base64 JPEG image.
     * Saved to Downloads/PDFScanner/<baseName>.html.
     */
    fun exportPdfAsHtml(context: Context, uri: Uri, baseName: String): Boolean {
        val resolver = context.contentResolver
        return try {
            val sb = StringBuilder()
            sb.append("<!DOCTYPE html><html><head><meta charset='UTF-8'>")
            sb.append("<title>").append(baseName).append("</title>")
            sb.append("<style>body{margin:0;background:#888;padding:12px}")
            sb.append("img{display:block;margin:10px auto;max-width:100%;")
            sb.append("box-shadow:0 2px 8px rgba(0,0,0,.4)}</style></head><body>")
            resolver.openFileDescriptor(uri, "r")?.use { fd ->
                PdfRenderer(fd).use { renderer ->
                    for (pageIndex in 0 until renderer.pageCount) {
                        renderer.openPage(pageIndex).use { page ->
                            val w = maxOf(page.width, 1)
                            val h = maxOf(page.height, 1)
                            val bitmap = Bitmap.createBitmap(w * RENDER_SCALE, h * RENDER_SCALE, Bitmap.Config.ARGB_8888)
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                            val baos = ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, baos)
                            bitmap.recycle()
                            val b64 = android.util.Base64.encodeToString(
                                baos.toByteArray(), android.util.Base64.NO_WRAP)
                            sb.append("<img src='data:image/jpeg;base64,").append(b64)
                                .append("' alt='Page ").append(pageIndex + 1).append("'>")
                        }
                    }
                }
            }
            sb.append("</body></html>")
            val htmlUri = resolver.insert(
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "$baseName.html")
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/html")
                    put(MediaStore.MediaColumns.RELATIVE_PATH,
                        "${Environment.DIRECTORY_DOWNLOADS}/PDFScanner")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            ) ?: return false
            resolver.openOutputStream(htmlUri)?.use { out ->
                out.write(sb.toString().toByteArray(Charsets.UTF_8))
            }
            resolver.update(htmlUri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export PDF as HTML", e)
            false
        }
    }

    /**
     * Exports the PDF as a DOCX file where every page is an embedded PNG image.
     * Saved to Downloads/PDFScanner/<baseName>.docx.
     */
    fun exportPdfAsDocx(context: Context, uri: Uri, baseName: String): Boolean {
        val resolver = context.contentResolver
        return try {
            val pageBitmaps = mutableListOf<Bitmap>()
            resolver.openFileDescriptor(uri, "r")?.use { fd ->
                PdfRenderer(fd).use { renderer ->
                    for (pageIndex in 0 until renderer.pageCount) {
                        renderer.openPage(pageIndex).use { page ->
                            val w = maxOf(page.width, 1)
                            val h = maxOf(page.height, 1)
                            val bitmap = Bitmap.createBitmap(w * RENDER_SCALE, h * RENDER_SCALE, Bitmap.Config.ARGB_8888)
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                            pageBitmaps.add(bitmap)
                        }
                    }
                }
            }
            if (pageBitmaps.isEmpty()) return false

            val docxUri = resolver.insert(
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "$baseName.docx")
                    put(MediaStore.MediaColumns.MIME_TYPE,
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                    put(MediaStore.MediaColumns.RELATIVE_PATH,
                        "${Environment.DIRECTORY_DOWNLOADS}/PDFScanner")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            ) ?: return false

            resolver.openOutputStream(docxUri)?.use { output ->
                ZipOutputStream(output).use { zip ->
                    zip.putNextEntry(ZipEntry("[Content_Types].xml"))
                    zip.write(buildContentTypesXml().toByteArray(Charsets.UTF_8))
                    zip.closeEntry()

                    zip.putNextEntry(ZipEntry("_rels/.rels"))
                    zip.write(buildRootRelsXml().toByteArray(Charsets.UTF_8))
                    zip.closeEntry()

                    zip.putNextEntry(ZipEntry("word/_rels/document.xml.rels"))
                    zip.write(buildDocRelsXml(pageBitmaps.size).toByteArray(Charsets.UTF_8))
                    zip.closeEntry()

                    zip.putNextEntry(ZipEntry("word/document.xml"))
                    zip.write(buildDocumentXml(pageBitmaps).toByteArray(Charsets.UTF_8))
                    zip.closeEntry()

                    pageBitmaps.forEachIndexed { i, bitmap ->
                        zip.putNextEntry(ZipEntry("word/media/page${i + 1}.png"))
                        val baos = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
                        zip.write(baos.toByteArray())
                        zip.closeEntry()
                        bitmap.recycle()
                    }
                }
            }
            resolver.update(docxUri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export PDF as DOCX", e)
            false
        }
    }

    private fun buildContentTypesXml(): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
        append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
        append("<Default Extension=\"png\" ContentType=\"image/png\"/>")
        append("<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>")
        append("</Types>")
    }

    private fun buildRootRelsXml(): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
        append("<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>")
        append("</Relationships>")
    }

    private fun buildDocRelsXml(pageCount: Int): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
        for (i in 1..pageCount) {
            append("<Relationship Id=\"rId$i\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/page$i.png\"/>")
        }
        append("</Relationships>")
    }

    private fun buildDocumentXml(pages: List<Bitmap>): String = buildString {
        val maxWidthEmu = 5_486_400L
        val pixToEmu = 9525L
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        append("<w:document")
        append(" xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"")
        append(" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"")
        append(" xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\"")
        append(" xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"")
        append(" xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">")
        append("<w:body>")
        pages.forEachIndexed { i, bmp ->
            var cx = bmp.width.toLong() * pixToEmu
            var cy = bmp.height.toLong() * pixToEmu
            if (cx > maxWidthEmu) {
                cy = (cy * maxWidthEmu / cx)
                cx = maxWidthEmu
            }
            val rId = "rId${i + 1}"
            val imgId = i + 1
            append("<w:p><w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">")
            append("<wp:extent cx=\"$cx\" cy=\"$cy\"/>")
            append("<wp:effectExtent l=\"0\" t=\"0\" r=\"0\" b=\"0\"/>")
            append("<wp:docPr id=\"$imgId\" name=\"page$imgId\"/>")
            append("<wp:cNvGraphicFramePr/>")
            append("<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">")
            append("<pic:pic><pic:nvPicPr>")
            append("<pic:cNvPr id=\"$imgId\" name=\"page$imgId\"/>")
            append("<pic:cNvPicPr/></pic:nvPicPr>")
            append("<pic:blipFill><a:blip r:embed=\"$rId\"/>")
            append("<a:stretch><a:fillRect/></a:stretch></pic:blipFill>")
            append("<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/>")
            append("<a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm>")
            append("<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom>")
            append("</pic:spPr></pic:pic>")
            append("</a:graphicData></a:graphic>")
            append("</wp:inline></w:drawing></w:r></w:p>")
            if (i < pages.lastIndex) {
                append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
            }
        }
        append("<w:sectPr/>")
        append("</w:body></w:document>")
    }

    private fun appendPdfPages(
        context: Context,
        targetDocument: PdfDocument,
        sourceUri: Uri,
        startPageNumber: Int,
    ): Int {
        var pageNumber = startPageNumber
        val scaleMatrix = Matrix().apply { setScale(1f / RENDER_SCALE, 1f / RENDER_SCALE) }
        try {
            context.contentResolver.openFileDescriptor(sourceUri, "r")?.use { fd ->
                PdfRenderer(fd).use { renderer ->
                    for (pageIndex in 0 until renderer.pageCount) {
                        renderer.openPage(pageIndex).use { page ->
                            val width = maxOf(page.width, 1)
                            val height = maxOf(page.height, 1)
                            val bitmap = Bitmap.createBitmap(width * RENDER_SCALE, height * RENDER_SCALE, Bitmap.Config.ARGB_8888)
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                            val pageInfo = PdfDocument.PageInfo.Builder(width, height, pageNumber + 1).create()
                            val newPage = targetDocument.startPage(pageInfo)
                            newPage.canvas.drawColor(Color.WHITE)
                            newPage.canvas.drawBitmap(bitmap, scaleMatrix, null)
                            targetDocument.finishPage(newPage)
                            bitmap.recycle()
                            pageNumber++
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to append PDF pages from $sourceUri", e)
        }
        return pageNumber
    }
}
