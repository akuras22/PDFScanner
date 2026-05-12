package com.akuras.pdfscanner

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import android.util.LruCache
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.akuras.pdfscanner.ui.theme.PDFScannerTheme
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val PAGE_ASPECT_RATIO = 0.707f
private const val PAGE_PREVIEW_WIDTH_PX = 220
private const val MIN_PAGE_DIMENSION_PX = 1
private const val THUMBNAIL_CACHE_MAX_ENTRIES = 80
/** Render PDF pages at this multiple of their native size to preserve quality (~216 DPI). */
private const val RENDER_SCALE = 3

class MainActivity : ComponentActivity() {

    private val githubOwner = "akuras22"
    private val githubRepo = "PDFScanner"

    private var onScanComplete: (() -> Unit)? = null
    private var updateDownloadId: Long? = null
    private var updateDownloadReceiver: BroadcastReceiver? = null

    private val scannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val scanningResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
        val pdfUri = scanningResult?.pdf?.uri

        if (pdfUri == null) return@registerForActivityResult

        val pattern = loadFileNamePattern(this)
        val saved = PdfStorage.savePdfToDownloads(this, pdfUri, pattern)
        if (saved != null) {
            Toast.makeText(this, getString(R.string.saved_to_downloads), Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, getString(R.string.unable_to_save_pdf), Toast.LENGTH_LONG).show()
        }
        onScanComplete?.invoke()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            PDFScannerTheme(darkTheme = resolveDarkTheme(this@MainActivity, loadThemeMode(this@MainActivity))) {
                var refreshTrigger by rememberSaveable { mutableIntStateOf(0) }
                var availableUpdate by remember { mutableStateOf<AvailableUpdate?>(null) }
                var updateCheckDone by rememberSaveable { mutableStateOf(false) }
                var showOnboarding by rememberSaveable { mutableStateOf(shouldShowOnboarding(this@MainActivity)) }
                onScanComplete = { refreshTrigger++ }

                LaunchedEffect(Unit) {
                    if (!updateCheckDone) {
                        availableUpdate = fetchAvailableUpdate(
                            currentVersionCode = getCurrentVersionCode(this@MainActivity),
                            currentVersionName = getCurrentVersionName(this@MainActivity),
                            owner = githubOwner,
                            repo = githubRepo,
                        )
                        updateCheckDone = true
                    }
                }

                availableUpdate?.let { update ->
                    UpdateAvailableDialog(
                        update = update,
                        onDismiss = { availableUpdate = null },
                        onDownload = {
                            startUpdateDownload(update)
                            availableUpdate = null
                        }
                    )
                }
                if (showOnboarding) {
                    AlertDialog(
                        onDismissRequest = {
                            markOnboardingSeen(this@MainActivity)
                            showOnboarding = false
                        },
                        title = { Text(getString(R.string.onboarding_title)) },
                        text = { Text(getString(R.string.onboarding_text)) },
                        confirmButton = {
                            TextButton(onClick = {
                                markOnboardingSeen(this@MainActivity)
                                showOnboarding = false
                            }) {
                                Text(getString(R.string.got_it))
                            }
                        }
                    )
                }

                com.akuras.pdfscanner.ScannerScreen(
                    refreshTrigger = refreshTrigger,
                    onScanClick = { startScan() },
                    onSettingsClick = { startActivity(Intent(this, SettingsActivity::class.java)) },
                )
            }
        }
    }

    override fun onDestroy() {
        updateDownloadReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: IllegalArgumentException) {
            }
        }
        updateDownloadReceiver = null
        super.onDestroy()
    }

    private fun startScan() {
        val options = GmsDocumentScannerOptions.Builder()
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .setGalleryImportAllowed(true)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
            .build()

        val scanner = GmsDocumentScanning.getClient(options)
        scanner.getStartScanIntent(this)
            .addOnSuccessListener { intentSender ->
                scannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }
            .addOnFailureListener {
                Toast.makeText(this, getString(R.string.scanner_unavailable), Toast.LENGTH_LONG).show()
            }
    }

    private fun startUpdateDownload(update: AvailableUpdate) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, getString(R.string.allow_install_unknown_apps), Toast.LENGTH_LONG).show()
            openInstallUnknownAppsSettings()
            return
        }

        if (!isTrustedApkUrl(update.downloadUrl, githubOwner, githubRepo)) {
            Toast.makeText(this, getString(R.string.update_source_not_trusted), Toast.LENGTH_LONG).show()
            return
        }

        val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val updateApkFile = getUpdateApkFile() ?: run {
            Toast.makeText(this, getString(R.string.unable_access_storage), Toast.LENGTH_LONG).show()
            return
        }

        if (updateApkFile.exists() && !updateApkFile.delete()) {
            Toast.makeText(this, getString(R.string.could_not_prepare_update_file), Toast.LENGTH_LONG).show()
            return
        }

        val request = DownloadManager.Request(Uri.parse(update.downloadUrl)).apply {
            setTitle(getString(R.string.update_download_title))
            setDescription(getString(R.string.update_downloading_version, update.versionName))
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
            setDestinationInExternalFilesDir(this@MainActivity, Environment.DIRECTORY_DOWNLOADS, UPDATE_APK_NAME)
        }

        updateDownloadReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: IllegalArgumentException) {
            }
        }

        val downloadId = downloadManager.enqueue(request)
        updateDownloadId = downloadId
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
                val completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (completedId != updateDownloadId) return

                try {
                    unregisterReceiver(this)
                } catch (_: IllegalArgumentException) {
                }
                updateDownloadReceiver = null

                val cursor = downloadManager.query(DownloadManager.Query().setFilterById(completedId))
                if (cursor == null) {
                    Toast.makeText(this@MainActivity, getString(R.string.download_status_query_failed), Toast.LENGTH_LONG).show()
                    return
                }

                cursor.use {
                    if (!cursor.moveToFirst()) {
                        Toast.makeText(this@MainActivity, getString(R.string.download_not_found), Toast.LENGTH_LONG).show()
                        return
                    }
                    val statusColumn = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    if (statusColumn == -1) {
                        Toast.makeText(this@MainActivity, getString(R.string.download_status_unavailable), Toast.LENGTH_LONG).show()
                        return
                    }
                    if (cursor.getInt(statusColumn) != DownloadManager.STATUS_SUCCESSFUL) {
                        Toast.makeText(this@MainActivity, getString(R.string.download_failed_or_cancelled), Toast.LENGTH_LONG).show()
                        return
                    }
                }

                installDownloadedApk(updateApkFile)
            }
        }

        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
        updateDownloadReceiver = receiver

        Toast.makeText(this, getString(R.string.downloading_update), Toast.LENGTH_SHORT).show()
    }

    private fun installDownloadedApk(apkFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, getString(R.string.allow_install_to_continue), Toast.LENGTH_LONG).show()
            openInstallUnknownAppsSettings()
            return
        }

        if (!isSafeUpdateApk(apkFile)) {
            Toast.makeText(this, getString(R.string.update_integrity_check_failed), Toast.LENGTH_LONG).show()
            return
        }

        val apkUri = try {
            FileProvider.getUriForFile(this, "$packageName.provider", apkFile)
        } catch (e: IllegalArgumentException) {
            Log.e("MainActivity", "Failed to create install URI for update APK", e)
            Toast.makeText(this, getString(R.string.could_not_open_update), Toast.LENGTH_LONG).show()
            return
        }

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            startActivity(installIntent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.no_installer_found), Toast.LENGTH_LONG).show()
        }
    }

    private fun isSafeUpdateApk(apkFile: File): Boolean {
        val flags = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
        val archiveInfo = packageManager.getPackageArchiveInfo(apkFile.absolutePath, flags) ?: return false
        if (archiveInfo.packageName != packageName) return false

        val currentInfo = packageManager.getPackageInfo(packageName, flags)
        val currentSigners = currentInfo.signingInfo?.apkContentsSigners?.map(::sha256Hex).orEmpty().toSet()
        val updateSigners = archiveInfo.signingInfo?.apkContentsSigners?.map(::sha256Hex).orEmpty().toSet()
        return currentSigners.isNotEmpty() && currentSigners == updateSigners
    }

    private fun sha256Hex(signature: android.content.pm.Signature): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(signature.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun getUpdateApkFile(): File? {
        return getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.resolve(UPDATE_APK_NAME)
    }

    private fun openInstallUnknownAppsSettings() {
        startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
}

@androidx.compose.runtime.Composable
private fun UpdateAvailableDialog(
    update: AvailableUpdate,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { androidx.compose.material3.Text(stringResource(R.string.update_available)) },
        text = {
            androidx.compose.material3.Text(stringResource(R.string.new_version_available, update.versionName))
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDownload) {
                androidx.compose.material3.Text(stringResource(R.string.download))
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                androidx.compose.material3.Text(stringResource(R.string.later))
            }
        }
    )
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

@Composable
private fun HistoryPanel(refreshTrigger: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf(emptyList<SavedPdf>()) }
    var externalMergeItems by remember { mutableStateOf(emptyList<SavedPdf>()) }
    var mergeMode by remember { mutableStateOf(false) }
    var isMerging by remember { mutableStateOf(false) }
    // List (not Set) so insertion order = merge order
    var selectedMergeUris by remember { mutableStateOf(listOf<String>()) }
    var thumbnailSeed by remember { mutableIntStateOf(0) }
    var showMergePreview by remember { mutableStateOf(false) }
    val config = LocalConfiguration.current
    val isWide = config.screenWidthDp > 600
    val columns = if (isWide) 2 else 1
    val thumbnailDispatcher = remember { Dispatchers.IO.limitedParallelism(2) }
    val thumbnailCache = remember { LruCache<String, Bitmap>(THUMBNAIL_CACHE_MAX_ENTRIES) }
    val thumbnailRenderMutex = remember { Mutex() }
    val mergeCandidates = (items + externalMergeItems).distinctBy { it.uri.toString() }
    val displayedItems = if (mergeMode) mergeCandidates else items
    val externalPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val resolver = context.contentResolver
        val startIndex = externalMergeItems.size + 1
        val added = uris.mapIndexed { index, uri ->
            try {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                Log.w("HistoryPanel", "Failed to persist read permission for $uri", e)
            }
            SavedPdf(
                name = queryPdfDisplayName(context, uri) ?: "External PDF ${startIndex + index}",
                uri = uri,
                isExternal = true,
            )
        }
        val newExternal = (externalMergeItems + added).distinctBy { it.uri.toString() }
        externalMergeItems = newExternal
        // Auto-select newly added external PDFs
        val newKeys = added.map { it.uri.toString() }
        selectedMergeUris = (selectedMergeUris + newKeys).distinct()
    }

    LaunchedEffect(refreshTrigger) {
        val refreshed = querySavedPdfs(context)
        items = refreshed
        selectedMergeUris = selectedMergeUris.filter { selected ->
            refreshed.any { it.uri.toString() == selected } ||
                externalMergeItems.any { it.uri.toString() == selected }
        }
    }

    // Merge preview dialog — shown before actually merging so the user can reorder
    if (showMergePreview) {
        val orderedItems = selectedMergeUris.mapNotNull { key ->
            mergeCandidates.find { it.uri.toString() == key }
        }
        MergePreviewDialog(
            orderedItems = orderedItems,
            thumbnailDispatcher = thumbnailDispatcher,
            thumbnailCache = thumbnailCache,
            thumbnailRenderMutex = thumbnailRenderMutex,
            onDismiss = { showMergePreview = false },
            onConfirm = { confirmedItems ->
                showMergePreview = false
                val mergeUris = confirmedItems.map { it.uri }
                scope.launch {
                    isMerging = true
                    val pattern = loadFileNamePattern(context)
                    val merged = withContext(Dispatchers.IO) {
                        mergePdfsToDownloads(context, mergeUris, pattern)
                    }
                    isMerging = false
                    if (merged != null) {
                        Toast.makeText(context, "PDFs merged", Toast.LENGTH_SHORT).show()
                        items = querySavedPdfs(context)
                        mergeMode = false
                        selectedMergeUris = emptyList()
                        externalMergeItems = emptyList()
                        thumbnailSeed++
                    } else {
                        Toast.makeText(context, "Merge failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
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
        if (items.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            mergeMode = !mergeMode
                            if (!mergeMode) {
                                selectedMergeUris = emptyList()
                                externalMergeItems = emptyList()
                            }
                        }
                    ) {
                        Text(if (mergeMode) "Cancel Merge" else "Merge PDFs")
                    }
                    if (mergeMode) {
                        Text(
                            text = "${selectedMergeUris.size} selected",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (mergeMode) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            enabled = !isMerging,
                            onClick = {
                                externalPicker.launch(arrayOf("application/pdf"))
                            }
                        ) {
                            Text("Add External PDFs")
                        }
                        Button(
                            modifier = Modifier.weight(1f),
                            enabled = selectedMergeUris.size >= 2 && !isMerging,
                            onClick = { showMergePreview = true }
                        ) {
                            Text(if (isMerging) "Merging..." else "Merge Selected")
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        if (displayedItems.isEmpty()) {
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
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                columns = GridCells.Fixed(columns),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                items(displayedItems, key = { it.uri.toString() }) { item ->
                    val key = item.uri.toString()
                    val mergePosition = if (mergeMode) selectedMergeUris.indexOf(key).takeIf { it >= 0 }?.plus(1) else null
                    PdfHistoryCard(
                        item = item,
                        isWide = isWide,
                        selectionMode = mergeMode,
                        isSelected = selectedMergeUris.contains(key),
                        mergePosition = mergePosition,
                        thumbnailSeed = thumbnailSeed,
                        thumbnailDispatcher = thumbnailDispatcher,
                        thumbnailCache = thumbnailCache,
                        thumbnailRenderMutex = thumbnailRenderMutex,
                        onToggleSelected = {
                            selectedMergeUris = if (selectedMergeUris.contains(key)) {
                                selectedMergeUris - key
                            } else {
                                selectedMergeUris + key
                            }
                        },
                        onDeleted = { deletedUri ->
                            items = items.filterNot { it.uri == deletedUri }
                            selectedMergeUris = selectedMergeUris - deletedUri.toString()
                            thumbnailSeed++
                        },
                        onReordered = {
                            items = querySavedPdfs(context)
                            thumbnailSeed++
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PdfHistoryCard(
    item: SavedPdf,
    isWide: Boolean = false,
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
    mergePosition: Int? = null,
    thumbnailSeed: Int = 0,
    thumbnailDispatcher: CoroutineDispatcher,
    thumbnailCache: LruCache<String, Bitmap>,
    thumbnailRenderMutex: Mutex,
    onToggleSelected: () -> Unit = {},
    onDeleted: (Uri) -> Unit,
    onReordered: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val thumbnailKey = remember(item.uri, thumbnailSeed) { "${item.uri}#$thumbnailSeed" }
    val thumbnail by produceState<Bitmap?>(initialValue = thumbnailCache.get(thumbnailKey), thumbnailKey) {
        if (value == null) {
            value = withContext(thumbnailDispatcher) {
                thumbnailRenderMutex.withLock {
                    thumbnailCache.get(thumbnailKey) ?: renderPdfThumbnail(context, item.uri)?.also {
                        thumbnailCache.put(thumbnailKey, it)
                    }
                }
            }
        }
    }
    val thumbnailWidth = if (isWide) 100.dp else 80.dp
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showReorderDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog && !item.isExternal) {
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

    if (showReorderDialog && !item.isExternal) {
        ReorderPagesDialog(
            item = item,
            onDismiss = { showReorderDialog = false },
            onSaved = {
                showReorderDialog = false
                onReordered()
            }
        )
    }

    if (showExportDialog) {
        ExportDialog(
            item = item,
            onDismiss = { showExportDialog = false }
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (selectionMode) {
                    onToggleSelected()
                } else {
                    openPdf(context, item.uri)
                }
            },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            // PDF thumbnail with optional merge-position badge
            Box(
                modifier = Modifier
                    .width(thumbnailWidth)
                    .aspectRatio(PAGE_ASPECT_RATIO)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                val thumbnailBitmap = thumbnail
                if (thumbnailBitmap != null) {
                    Image(
                        bitmap = thumbnailBitmap.asImageBitmap(),
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
                if (mergePosition != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .size(20.dp)
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "$mergePosition",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                if (selectionMode) {
                    Text(
                        text = if (isSelected) "Selected for merge" else "Tap to select for merge",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.isExternal) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "External PDF",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
                        onClick = { showReorderDialog = true },
                        enabled = !item.isExternal,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Icons.Default.Collections, contentDescription = "Pages", modifier = Modifier.size(18.dp))
                    }
                    FilledTonalIconButton(
                        onClick = { showExportDialog = true },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Icons.Default.FileDownload, contentDescription = "Export", modifier = Modifier.size(18.dp))
                    }
                    FilledTonalIconButton(
                        onClick = { showDeleteDialog = true },
                        enabled = !item.isExternal,
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

@Composable
private fun ExportDialog(
    item: SavedPdf,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isExporting by remember { mutableStateOf(false) }
    val baseName = remember(item.name) {
        item.name.removeSuffix(".pdf").take(60).ifBlank { "export" }
    }

    AlertDialog(
        onDismissRequest = { if (!isExporting) onDismiss() },
        title = { Text("Export as") },
        text = {
            if (isExporting) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        androidx.compose.material3.CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Exporting…")
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExportOption(label = "PNG — all pages as separate images") {
                        scope.launch {
                            isExporting = true
                            val count = withContext(Dispatchers.IO) {
                                PdfStorage.exportPdfAsPngs(context, item.uri, baseName)
                            }
                            isExporting = false
                            if (count > 0) {
                                Toast.makeText(context, "Pages saved to Downloads/PDFScanner", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(context, "Export failed", Toast.LENGTH_SHORT).show()
                            }
                            onDismiss()
                        }
                    }
                    ExportOption(label = "HTML — web page with embedded images") {
                        scope.launch {
                            isExporting = true
                            val ok = withContext(Dispatchers.IO) {
                                PdfStorage.exportPdfAsHtml(context, item.uri, baseName)
                            }
                            isExporting = false
                            Toast.makeText(
                                context,
                                if (ok) "HTML saved to Downloads/PDFScanner" else "Export failed",
                                Toast.LENGTH_LONG
                            ).show()
                            onDismiss()
                        }
                    }
                    ExportOption(label = "DOCX — Word document with page images") {
                        scope.launch {
                            isExporting = true
                            val ok = withContext(Dispatchers.IO) {
                                PdfStorage.exportPdfAsDocx(context, item.uri, baseName)
                            }
                            isExporting = false
                            Toast.makeText(
                                context,
                                if (ok) "DOCX saved to Downloads/PDFScanner" else "Export failed",
                                Toast.LENGTH_LONG
                            ).show()
                            onDismiss()
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isExporting) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun ExportOption(label: String, onClick: () -> Unit) {
    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun MergePreviewDialog(
    orderedItems: List<SavedPdf>,
    thumbnailDispatcher: CoroutineDispatcher,
    thumbnailCache: LruCache<String, Bitmap>,
    thumbnailRenderMutex: Mutex,
    onDismiss: () -> Unit,
    onConfirm: (List<SavedPdf>) -> Unit,
) {
    var items by remember { mutableStateOf(orderedItems) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Merge Order") },
        text = {
            Column {
                Text(
                    "Reorder PDFs before merging",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(items) { index, pdf ->
                        MergePreviewRow(
                            pdf = pdf,
                            position = index + 1,
                            canMoveUp = index > 0,
                            canMoveDown = index < items.lastIndex,
                            thumbnailDispatcher = thumbnailDispatcher,
                            thumbnailCache = thumbnailCache,
                            thumbnailRenderMutex = thumbnailRenderMutex,
                            onMoveUp = {
                                items = items.toMutableList().apply {
                                    val tmp = this[index - 1]; this[index - 1] = this[index]; this[index] = tmp
                                }
                            },
                            onMoveDown = {
                                items = items.toMutableList().apply {
                                    val tmp = this[index + 1]; this[index + 1] = this[index]; this[index] = tmp
                                }
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(items) }) {
                Text("Merge")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun MergePreviewRow(
    pdf: SavedPdf,
    position: Int,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    thumbnailDispatcher: CoroutineDispatcher,
    thumbnailCache: LruCache<String, Bitmap>,
    thumbnailRenderMutex: Mutex,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    val context = LocalContext.current
    val thumbnailKey = remember(pdf.uri) { pdf.uri.toString() }
    val thumbnail by produceState<Bitmap?>(initialValue = thumbnailCache.get(thumbnailKey), thumbnailKey) {
        if (value == null) {
            value = withContext(thumbnailDispatcher) {
                thumbnailRenderMutex.withLock {
                    thumbnailCache.get(thumbnailKey) ?: renderPdfThumbnail(context, pdf.uri)?.also {
                        thumbnailCache.put(thumbnailKey, it)
                    }
                }
            }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Position badge
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "$position",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        // Thumbnail
        Box(
            modifier = Modifier
                .width(44.dp)
                .aspectRatio(PAGE_ASPECT_RATIO)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            val bmp = thumbnail
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    Icons.Default.Description,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = pdf.name,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        IconButton(onClick = onMoveUp, enabled = canMoveUp, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.ArrowUpward, contentDescription = "Move up", modifier = Modifier.size(16.dp))
        }
        IconButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.ArrowDownward, contentDescription = "Move down", modifier = Modifier.size(16.dp))
        }
    }
}



@Composable
private fun ReorderPagesDialog(
    item: SavedPdf,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pageOrder by remember(item.uri) { mutableStateOf<List<Int>?>(null) }
    var pagePreviews by remember(item.uri) { mutableStateOf<List<Bitmap?>?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    LaunchedEffect(item.uri) {
        val order = withContext(Dispatchers.IO) { getPdfPageOrder(context, item.uri) }
        val pageCount = order.size
        pageOrder = order
        pagePreviews = withContext(Dispatchers.IO) { renderPdfPagePreviews(context, item.uri, pageCount) }
    }

    AlertDialog(
        onDismissRequest = {
            if (!isSaving) {
                onDismiss()
            }
        },
        title = { Text("Manage pages") },
        text = {
            when (val order = pageOrder) {
                null -> Text("Loading pages...")
                else -> {
                    if (order.isEmpty()) {
                        Text("Could not load pages.")
                    } else {
                        val previews = pagePreviews ?: emptyList()
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            itemsIndexed(order) { index, page ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .width(44.dp)
                                            .aspectRatio(PAGE_ASPECT_RATIO)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        val preview = previews.getOrNull(page)
                                        if (preview != null) {
                                            Image(
                                                bitmap = preview.asImageBitmap(),
                                                contentDescription = "Page ${page + 1} preview",
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = ContentScale.Fit
                                            )
                                        } else {
                                            Icon(
                                                Icons.Default.Description,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Page ${page + 1}",
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    IconButton(
                                        enabled = index > 0 && !isSaving,
                                        onClick = {
                                            pageOrder = order.toMutableList().apply {
                                                val temp = this[index - 1]
                                                this[index - 1] = this[index]
                                                this[index] = temp
                                            }
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Default.ArrowUpward, contentDescription = "Up", modifier = Modifier.size(16.dp))
                                    }
                                    IconButton(
                                        enabled = index < order.lastIndex && !isSaving,
                                        onClick = {
                                            pageOrder = order.toMutableList().apply {
                                                val temp = this[index + 1]
                                                this[index + 1] = this[index]
                                                this[index] = temp
                                            }
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Default.ArrowDownward, contentDescription = "Down", modifier = Modifier.size(16.dp))
                                    }
                                    IconButton(
                                        enabled = order.size > 1 && !isSaving,
                                        onClick = {
                                            pageOrder = order.toMutableList().apply { removeAt(index) }
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "Delete page",
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            val order = pageOrder
            TextButton(
                enabled = order != null && !isSaving,
                onClick = {
                    if (order == null) return@TextButton
                    scope.launch {
                        isSaving = true
                        val saved = withContext(Dispatchers.IO) {
                            reorderPdfPages(context, item.uri, order)
                        }
                        isSaving = false
                        if (saved) {
                            Toast.makeText(context, "Pages updated", Toast.LENGTH_SHORT).show()
                            onSaved()
                        } else {
                            Toast.makeText(context, "Could not update pages", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            ) {
                Text(if (isSaving) "Saving..." else "Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving) {
                Text("Cancel")
            }
        }
    )
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


private fun getPdfPageOrder(context: Context, uri: Uri): List<Int> {
    return try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
            PdfRenderer(fd).use { renderer ->
                List(renderer.pageCount) { it }
            }
        } ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }
}

private fun renderPdfPagePreviews(context: Context, uri: Uri, expectedPages: Int): List<Bitmap?> {
    return try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
            PdfRenderer(fd).use { renderer ->
                List(renderer.pageCount) { pageIndex ->
                    try {
                        renderer.openPage(pageIndex).use { page ->
                            val width = PAGE_PREVIEW_WIDTH_PX
                            // Some malformed pages can report zero dimensions; clamp to keep bitmap math valid.
                            val clampedPageWidth = maxOf(page.width, MIN_PAGE_DIMENSION_PX)
                            val clampedPageHeight = maxOf(page.height, MIN_PAGE_DIMENSION_PX)
                            val height = (width * clampedPageHeight.toFloat() / clampedPageWidth).toInt().coerceAtLeast(MIN_PAGE_DIMENSION_PX)
                            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                                bitmap.eraseColor(android.graphics.Color.WHITE)
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("ReorderPagesDialog", "Failed to render preview for page index $pageIndex", e)
                        null
                    }
                }
            }
        } ?: List(expectedPages) { null }
    } catch (_: Exception) {
        List(expectedPages) { null }
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

private fun mergePdfsToDownloads(context: Context, sourceUris: List<Uri>, pattern: String): Uri? {
    if (sourceUris.size < 2) return null

    val resolver = context.contentResolver
    val fileName = "${resolveFileName(pattern)}_merged.pdf"
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val finalizeValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(destinationUri, finalizeValues, null, null)
        }
        destinationUri
    } catch (_: Exception) {
        resolver.delete(destinationUri, null, null)
        null
    } finally {
        mergedDocument.close()
    }
}

private fun reorderPdfPages(context: Context, targetUri: Uri, pageOrder: List<Int>): Boolean {
    if (pageOrder.isEmpty()) return false

    val tempFile = try {
        File.createTempFile("reordered_", ".pdf", context.cacheDir)
    } catch (_: IOException) {
        return false
    }

    val document = PdfDocument()
    val scaleMatrix = Matrix().apply { setScale(1f / RENDER_SCALE, 1f / RENDER_SCALE) }
    return try {
        val sourceFd = context.contentResolver.openFileDescriptor(targetUri, "r") ?: return false
        sourceFd.use { fd ->
            PdfRenderer(fd).use { renderer ->
                if (pageOrder.isEmpty() ||
                    pageOrder.distinct().size != pageOrder.size ||
                    pageOrder.any { it !in 0 until renderer.pageCount }
                ) {
                    return false
                }
                var outputPageNumber = 0
                pageOrder.forEach { sourcePageIndex ->
                    if (sourcePageIndex !in 0 until renderer.pageCount) {
                        return false
                    }
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

        tempFile.outputStream().use { output ->
            document.writeTo(output)
        }

        context.contentResolver.openOutputStream(targetUri, "wt")?.use { output ->
            tempFile.inputStream().use { input ->
                input.copyTo(output)
            }
        } ?: return false

        true
    } catch (_: Exception) {
        false
    } finally {
        document.close()
        if (tempFile.exists()) {
            tempFile.delete()
        }
    }
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
    } catch (_: Exception) {
    }
    return pageNumber
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

private fun queryPdfDisplayName(context: Context, uri: Uri): String? {
    return try {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIdx < 0) return@use null
            cursor.getString(nameIdx)
        }
    } catch (_: Exception) {
        null
    }
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
private fun getCurrentVersionCode(context: Context): Int {
    return try {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode
        }
    } catch (_: PackageManager.NameNotFoundException) {
        1
    }
}

private fun getCurrentVersionName(context: Context): String {
    return try {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        packageInfo.versionName ?: ""
    } catch (_: PackageManager.NameNotFoundException) {
        ""
    }
}

private const val KEY_ONBOARDING_SEEN = "onboarding_seen"

private fun shouldShowOnboarding(context: Context): Boolean {
    return !context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(KEY_ONBOARDING_SEEN, false)
}

private fun markOnboardingSeen(context: Context) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_ONBOARDING_SEEN, true)
        .apply()
}
