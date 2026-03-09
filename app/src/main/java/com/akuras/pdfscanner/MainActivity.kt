package com.akuras.pdfscanner

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import com.akuras.pdfscanner.ui.theme.PDFScannerTheme
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {

    private var onScanResult: ((String) -> Unit)? = null

    private val scannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val scanningResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
        val pdfUri = scanningResult?.pdf?.uri

        if (pdfUri == null) {
            onScanResult?.invoke("Scan cancelled")
            return@registerForActivityResult
        }

        val saved = savePdfToDownloads(this, pdfUri)
        if (saved != null) {
            onScanResult?.invoke("Saved to Downloads/PDFScanner")
            Toast.makeText(this, "Saved: $saved", Toast.LENGTH_LONG).show()
        } else {
            onScanResult?.invoke("Could not save PDF")
            Toast.makeText(this, "Unable to save PDF", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            PDFScannerTheme {
                val windowSizeClass = calculateWindowSizeClass(this)
                var statusText by rememberSaveable { mutableStateOf("Tap Scan to start") }

                onScanResult = { statusText = it }

                ScannerScreen(
                    isTablet = windowSizeClass.widthSizeClass != WindowWidthSizeClass.Compact,
                    statusText = statusText,
                    onScanClick = { startScan() }
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
                onScanResult?.invoke("Scanner unavailable: ${it.localizedMessage}")
                Toast.makeText(this, "Scanner unavailable", Toast.LENGTH_LONG).show()
            }
    }
}

@Composable
private fun ScannerScreen(
    isTablet: Boolean,
    statusText: String,
    onScanClick: () -> Unit,
) {
    val background = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.surfaceContainer,
            MaterialTheme.colorScheme.surface
        )
    )

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("PDF Scanner") })
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(background)
                .padding(padding)
                .padding(16.dp)
        ) {
            if (isTablet) {
                TabletLayout(statusText = statusText, onScanClick = onScanClick)
            } else {
                PhoneLayout(statusText = statusText, onScanClick = onScanClick)
            }
        }
    }
}

@Composable
private fun PhoneLayout(statusText: String, onScanClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Scan docs into clean PDFs",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(12.dp))
        StatusCard(statusText)
        Spacer(modifier = Modifier.height(20.dp))
        Button(onClick = onScanClick) {
            Text("Scan Document")
        }
    }
}

@Composable
private fun TabletLayout(statusText: String, onScanClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize(),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "PDF Scanner",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Auto-detect, crop, enhance, and export documents to PDF.",
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onScanClick) {
                Text("Start New Scan")
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize(),
            verticalArrangement = Arrangement.Center
        ) {
            StatusCard(statusText)
        }
    }
}

@Composable
private fun StatusCard(statusText: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .width(420.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "Status", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = statusText)
        }
    }
}

private fun savePdfToDownloads(context: Context, sourceUri: Uri): Uri? {
    val resolver = context.contentResolver
    val fileName = "scan_${timestamp()}.pdf"

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

private fun timestamp(): String {
    val formatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    return LocalDateTime.now().format(formatter)
}
