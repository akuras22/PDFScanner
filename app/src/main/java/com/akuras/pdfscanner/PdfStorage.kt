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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
                    if (pageOrder.isEmpty() ||
                        pageOrder.distinct().size != pageOrder.size ||
                        pageOrder.any { it !in 0 until renderer.pageCount }
                    ) return false
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
                            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
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
        } catch (_: Exception) {
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
                            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                            val baos = ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
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
        } catch (_: Exception) {
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
                            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
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
                    // [Content_Types].xml
                    zip.putNextEntry(ZipEntry("[Content_Types].xml"))
                    zip.write(buildContentTypesXml().toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                    // _rels/.rels
                    zip.putNextEntry(ZipEntry("_rels/.rels"))
                    zip.write(buildRootRelsXml().toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                    // word/_rels/document.xml.rels
                    zip.putNextEntry(ZipEntry("word/_rels/document.xml.rels"))
                    zip.write(buildDocRelsXml(pageBitmaps.size).toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                    // word/document.xml
                    zip.putNextEntry(ZipEntry("word/document.xml"))
                    zip.write(buildDocumentXml(pageBitmaps).toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                    // word/media/pageN.png
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
        } catch (_: Exception) {
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
        val maxWidthEmu = 5_486_400L // ~6 inches at 914 400 EMU/inch
        val pixToEmu = 9525L       // 96 dpi: 1 px = 914400/96 EMU
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
