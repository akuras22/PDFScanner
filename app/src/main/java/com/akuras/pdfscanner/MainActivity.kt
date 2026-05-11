package com.akuras.pdfscanner

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import com.akuras.pdfscanner.ui.theme.PDFScannerTheme
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import java.io.File
import java.security.MessageDigest

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
            PDFScannerTheme {
                var refreshTrigger by rememberSaveable { mutableIntStateOf(0) }
                var availableUpdate by remember { mutableStateOf<AvailableUpdate?>(null) }
                var updateCheckDone by rememberSaveable { mutableStateOf(false) }
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

                ScannerScreen(
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
