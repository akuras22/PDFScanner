package com.akuras.pdfscanner

import android.content.ContentValues
import android.content.Context
import android.content.ContentUris
import android.content.Intent
import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.akuras.pdfscanner.ui.theme.PDFScannerTheme
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import java.io.IOException

class MainActivity : ComponentActivity() {

    private var onScanComplete: (() -> Unit)? = null

    private val scannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val scanningResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
        val pdfUri = scanningResult?.pdf?.uri

        if (pdfUri == null) {
            return@registerForActivityResult
        }

        val pattern = loadFileNamePattern(this)
        val saved = savePdfToDownloads(this, pdfUri, pattern)
        if (saved != null) {
            Toast.makeText(this, "Saved to Downloads/PDFScanner", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Unable to save PDF", Toast.LENGTH_LONG).show()
        }
        onScanComplete?.invoke()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            PDFScannerTheme {
                var refreshTrigger by rememberSaveable { mutableIntStateOf(0) }
                onScanComplete = { refreshTrigger++ }

                ScannerScreen(
                    refreshTrigger = refreshTrigger,
                    onScanClick = { startScan() },
                    onSettingsClick = {
                        startActivity(Intent(this, SettingsActivity::class.java))
                    }
                )
            }
        }
    }

    private fun startScan() {
        val options = GmsDocumentScannerOptions.Builder()
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .setGalleryImportAllowed(true)
            .setPageLimit(10)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
            .build()

        val scanner = GmsDocumentScanning.getClient(options)
        scanner.getStartScanIntent(this)
            .addOnSuccessListener { intentSender ->
                scannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }
            .addOnFailureListener {
                Toast.makeText(this, "Scanner unavailable", Toast.LENGTH_LONG).show()
            }
    }
}

@Composable
private fun ScannerScreen(
    refreshTrigger: Int,
    onScanClick: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    val background = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.surfaceContainer,
            MaterialTheme.colorScheme.surface
        )
    )

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "PDF Scanner",
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        bottomBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                                MaterialTheme.colorScheme.surface
                            )
                        )
                    )
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .navigationBarsPadding(),
                contentAlignment = Alignment.Center
            ) {
                Button(
                    onClick = onScanClick,
                    modifier = Modifier
                        .widthIn(max = 500.dp)
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(
                        Icons.Default.CameraAlt,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Scan Document", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(background)
                .padding(padding),
            contentAlignment = Alignment.TopCenter
        ) {
            HistoryPanel(
                refreshTrigger = refreshTrigger,
                modifier = Modifier
                    .widthIn(max = 900.dp)
                    .padding(horizontal = 16.dp)
                    .padding(top = 8.dp)
            )
        }
    }
}

private data class SavedPdf(
    val name: String,
    val uri: Uri,
)

@Composable
private fun HistoryPanel(refreshTrigger: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var items by remember { mutableStateOf(emptyList<SavedPdf>()) }
    val config = LocalConfiguration.current
    val isWide = config.screenWidthDp > 600
    val columns = if (isWide) 2 else 1

    LaunchedEffect(refreshTrigger) {
        items = querySavedPdfs(context)
    }

    Column(modifier = modifier.fillMaxSize().animateContentSize()) {
        Text(
            text = "Saved PDFs",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        if (items.isNotEmpty()) {
            Text(
                text = "${items.size} document${if (items.size != 1) "s" else ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(12.dp))

        if (items.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Description,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "No scanned documents yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Tap Scan Document below to get started",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                items(items, key = { it.uri.toString() }) { item ->
                    PdfHistoryCard(item, isWide = isWide) { deletedUri ->
                        items = items.filterNot { it.uri == deletedUri }
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfHistoryCard(item: SavedPdf, isWide: Boolean = false, onDeleted: (Uri) -> Unit) {
    val context = LocalContext.current
    val thumbnail = remember(item.uri) { renderPdfThumbnail(context, item.uri) }
    val thumbnailWidth = if (isWide) 100.dp else 80.dp
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete document?") },
            text = { Text("\"${item.name}\" will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    if (deletePdf(context, item.uri)) {
                        onDeleted(item.uri)
                        Toast.makeText(context, "Deleted", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Delete failed", Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { openPdf(context, item.uri) },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            // PDF thumbnail
            Box(
                modifier = Modifier
                    .width(thumbnailWidth)
                    .aspectRatio(0.707f) // A4 ratio
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail.asImageBitmap(),
                        contentDescription = "PDF preview",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        Icons.Default.Description,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    FilledTonalIconButton(
                        onClick = { openPdf(context, item.uri) },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open", modifier = Modifier.size(18.dp))
                    }
                    FilledTonalIconButton(
                        onClick = { sharePdf(context, item.uri, item.name) },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Share", modifier = Modifier.size(18.dp))
                    }
                    FilledTonalIconButton(
                        onClick = { showDeleteDialog = true },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

private fun renderPdfThumbnail(context: Context, uri: Uri): Bitmap? {
    return try {
        val fd: ParcelFileDescriptor =
            context.contentResolver.openFileDescriptor(uri, "r") ?: return null
        val renderer = PdfRenderer(fd)
        if (renderer.pageCount == 0) {
            renderer.close()
            fd.close()
            return null
        }
        val page = renderer.openPage(0)
        val width = 300
        val height = (width * page.height.toFloat() / page.width).toInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        page.close()
        renderer.close()
        fd.close()
        bitmap
    } catch (_: Exception) {
        null
    }
}

private fun savePdfToDownloads(context: Context, sourceUri: Uri, pattern: String): Uri? {
    val resolver = context.contentResolver
    val fileName = "${resolveFileName(pattern)}.pdf"

    val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    } else {
        MediaStore.Files.getContentUri("external")
    }

    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
        put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PDFScanner")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
    }

    val destinationUri = resolver.insert(collection, values) ?: return null

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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val finalizeValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(destinationUri, finalizeValues, null, null)
        }

        destinationUri
    } catch (e: IOException) {
        resolver.delete(destinationUri, null, null)
        null
    }
}

private fun querySavedPdfs(context: Context): List<SavedPdf> {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        return emptyList()
    }

    val resolver = context.contentResolver
    val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    val projection = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.MIME_TYPE,
        MediaStore.MediaColumns.RELATIVE_PATH,
    )

    val selection = "${MediaStore.MediaColumns.MIME_TYPE}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?"
    val args = arrayOf("application/pdf", "${Environment.DIRECTORY_DOWNLOADS}/PDFScanner/")

    val items = mutableListOf<SavedPdf>()
    resolver.query(
        collection,
        projection,
        selection,
        args,
        "${MediaStore.MediaColumns.DATE_ADDED} DESC"
    )?.use { cursor ->
        val idIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)

        while (cursor.moveToNext()) {
            val id = cursor.getLong(idIdx)
            val name = cursor.getString(nameIdx) ?: "scan_$id.pdf"
            val uri = ContentUris.withAppendedId(collection, id)
            items += SavedPdf(name = name, uri = uri)
        }
    }

    return items
}

private fun sharePdf(context: Context, uri: Uri, name: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    context.startActivity(Intent.createChooser(intent, "Share PDF"))
}

private fun openPdf(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/pdf")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    try {
        context.startActivity(Intent.createChooser(intent, "Open PDF"))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app can open PDFs", Toast.LENGTH_SHORT).show()
    }
}

private fun deletePdf(context: Context, uri: Uri): Boolean {
    return try {
        context.contentResolver.delete(uri, null, null) > 0
    } catch (e: SecurityException) {
        false
    }
}
