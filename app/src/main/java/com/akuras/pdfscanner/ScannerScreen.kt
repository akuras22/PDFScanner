package com.akuras.pdfscanner

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ScannerScreen(
    refreshTrigger: Int,
    onScanClick: () -> Unit,
    onSettingsClick: () -> Unit,
    historyViewModel: HistoryViewModel = viewModel(),
) {
    val background = MaterialTheme.colorScheme.surface
    val context = LocalContext.current
    val uiState = historyViewModel.uiState

    LaunchedEffect(refreshTrigger) { historyViewModel.refresh() }
    LaunchedEffect(historyViewModel) {
        historyViewModel.messages.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        stringResource(R.string.app_name),
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onScanClick,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.navigationBarsPadding()
            ) {
                Icon(
                    Icons.Default.CameraAlt,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(stringResource(R.string.scan_document), style = MaterialTheme.typography.titleMedium)
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
                uiState = uiState,
                onSetSearch = historyViewModel::setSearchQuery,
                onSetSort = historyViewModel::setSortOrder,
                onToggleMergeMode = historyViewModel::toggleMergeMode,
                onToggleSelection = historyViewModel::toggleSelection,
                onMergeSelected = { pattern -> historyViewModel.mergeSelected(pattern) },
                onShareSelected = historyViewModel::shareSelected,
                onDeleteSelected = historyViewModel::deleteSelected,
                onDelete = historyViewModel::delete,
                onRename = historyViewModel::rename,
                onReordered = historyViewModel::onReordered,
                modifier = Modifier
                    .widthIn(max = 900.dp)
                    .padding(horizontal = 16.dp)
                    .padding(top = 8.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryPanel(
    uiState: HistoryUiState,
    onSetSearch: (String) -> Unit,
    onSetSort: (PdfSortOrder) -> Unit,
    onToggleMergeMode: () -> Unit,
    onToggleSelection: (Uri) -> Unit,
    onMergeSelected: (String) -> Unit,
    onShareSelected: () -> Unit,
    onDeleteSelected: () -> Unit,
    onDelete: (Uri) -> Unit,
    onRename: (Uri, String) -> Unit,
    onReordered: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val config = LocalConfiguration.current
    val isWide = config.screenWidthDp > 600
    val columns = if (isWide) 2 else 1
    val selectedCount = uiState.selectedMergeUris.size
    var renameTarget by remember { mutableStateOf<SavedPdf?>(null) }
    var previewTarget by remember { mutableStateOf<SavedPdf?>(null) }
    var backupTarget by remember { mutableStateOf<SavedPdf?>(null) }
    val scope = rememberCoroutineScope()
    val backupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        val target = backupTarget
        if (uri != null && target != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    PdfStorage.copyPdfToUri(context, target.uri, uri)
                }
                Toast.makeText(
                    context,
                    if (ok) context.getString(R.string.toast_backup_success) else context.getString(R.string.toast_backup_failed),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        backupTarget = null
    }

    if (renameTarget != null) {
        RenamePdfDialog(
            item = renameTarget!!,
            onDismiss = { renameTarget = null },
            onRename = { newName ->
                onRename(renameTarget!!.uri, newName)
                renameTarget = null
            }
        )
    }
    if (previewTarget != null) {
        PdfPreviewDialog(
            item = previewTarget!!,
            onDismiss = { previewTarget = null }
        )
    }

    Column(modifier = modifier.fillMaxSize().animateContentSize()) {
        Text(
            text = stringResource(R.string.saved_pdfs),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        if (uiState.items.isNotEmpty()) {
            Text(
                text = stringResource(R.string.documents_count, uiState.items.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = uiState.searchQuery,
            onValueChange = onSetSearch,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.search_documents)) },
            leadingIcon = { Icon(Icons.Default.FindInPage, contentDescription = null) }
        )
        Spacer(modifier = Modifier.height(8.dp))
        var sortExpanded by rememberSaveable { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = sortExpanded,
            onExpandedChange = { sortExpanded = !sortExpanded }
        ) {
            OutlinedTextField(
                value = when (uiState.sortOrder) {
                    PdfSortOrder.DATE_DESC -> stringResource(R.string.sort_date)
                    PdfSortOrder.NAME_ASC -> stringResource(R.string.sort_name)
                    PdfSortOrder.SIZE_DESC -> stringResource(R.string.sort_size)
                },
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.sort_by)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = sortExpanded) },
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth()
            )
            ExposedDropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sort_date)) },
                    onClick = { onSetSort(PdfSortOrder.DATE_DESC); sortExpanded = false }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sort_name)) },
                    onClick = { onSetSort(PdfSortOrder.NAME_ASC); sortExpanded = false }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sort_size)) },
                    onClick = { onSetSort(PdfSortOrder.SIZE_DESC); sortExpanded = false }
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        if (uiState.items.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = onToggleMergeMode) {
                    Text(stringResource(if (uiState.mergeMode) R.string.cancel_merge else R.string.merge_pdfs))
                }
                if (uiState.mergeMode) {
                    Text(
                        text = stringResource(R.string.selected_count, selectedCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        enabled = selectedCount >= 2 && !uiState.isMerging,
                        onClick = { onMergeSelected(loadFileNamePattern(context)) }
                    ) {
                        Text(stringResource(if (uiState.isMerging) R.string.merging else R.string.merge_selected))
                    }
                    Button(
                        enabled = selectedCount >= 1,
                        onClick = onShareSelected
                    ) {
                        Text(stringResource(R.string.share_selected))
                    }
                    Button(
                        enabled = selectedCount >= 1,
                        onClick = onDeleteSelected
                    ) {
                        Text(stringResource(R.string.delete_selected))
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        if (uiState.items.isEmpty()) {
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
                        stringResource(R.string.no_scanned_documents),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.tap_scan_to_start),
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
                items(uiState.items, key = { it.uri.toString() }) { item ->
                    PdfHistoryCard(
                        item = item,
                        isWide = isWide,
                        selectionMode = uiState.mergeMode,
                        isSelected = uiState.selectedMergeUris.contains(item.uri.toString()),
                        thumbnailSeed = uiState.thumbnailSeed,
                        onToggleSelected = { onToggleSelection(item.uri) },
                        onDeleted = onDelete,
                        onRename = { renameTarget = it },
                        onPreview = { previewTarget = it },
                        onBackup = {
                            backupTarget = it
                            backupLauncher.launch(it.name)
                        },
                        onReordered = onReordered
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
    thumbnailSeed: Int = 0,
    onToggleSelected: () -> Unit = {},
    onDeleted: (Uri) -> Unit,
    onRename: (SavedPdf) -> Unit,
    onPreview: (SavedPdf) -> Unit,
    onBackup: (SavedPdf) -> Unit,
    onReordered: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val thumbnail = remember(item.uri, thumbnailSeed) { PdfStorage.renderPdfThumbnail(context, item.uri) }
    val pageCount by produceState<Int?>(initialValue = null, item.uri) {
        value = withContext(Dispatchers.IO) { PdfStorage.countPdfPages(context, item.uri) }
    }
    val thumbnailWidth = if (isWide) 100.dp else 80.dp
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showReorderDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.delete_document_title)) },
            text = { Text(stringResource(R.string.delete_document_text, item.name)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    onDeleted(item.uri)
                }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showReorderDialog) {
        ReorderPagesDialog(
            item = item,
            onDismiss = { showReorderDialog = false },
            onSaved = {
                showReorderDialog = false
                onReordered(true)
            },
            onFailed = { onReordered(false) }
        )
    }

    if (showExportDialog) {
        ScannerExportDialog(
            item = item,
            onDismiss = { showExportDialog = false }
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (selectionMode) onToggleSelected() else onPreview(item)
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
            Box(
                modifier = Modifier
                    .width(thumbnailWidth)
                    .aspectRatio(0.707f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail.asImageBitmap(),
                        contentDescription = stringResource(R.string.pdf_preview),
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
                if (selectionMode) {
                    Text(
                        text = stringResource(if (isSelected) R.string.selected_for_merge else R.string.tap_select_merge),
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
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(
                        R.string.file_meta,
                        formatSize(item.sizeBytes),
                        pageCount?.toString() ?: "-"
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilledTonalIconButton(
                        onClick = { onPreview(item) },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Icons.Default.FindInPage, contentDescription = stringResource(R.string.preview), modifier = Modifier.size(18.dp))
                    }
                    FilledTonalIconButton(
                        onClick = { PdfStorage.sharePdf(context, item.uri, item.name) },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.share), modifier = Modifier.size(18.dp))
                    }
                    FilledTonalIconButton(
                        onClick = { showReorderDialog = true },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = stringResource(R.string.pages), modifier = Modifier.size(18.dp))
                    }
                    FilledTonalIconButton(
                        onClick = { onRename(item) },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.rename), modifier = Modifier.size(18.dp))
                    }
                    FilledTonalIconButton(
                        onClick = { onBackup(item) },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Icons.Default.FileDownload, contentDescription = stringResource(R.string.backup_export), modifier = Modifier.size(18.dp))
                    }
                    FilledTonalIconButton(
                        onClick = { showExportDialog = true },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Icons.Default.FileDownload, contentDescription = stringResource(R.string.export_as), modifier = Modifier.size(18.dp))
                    }
                    FilledTonalIconButton(
                        onClick = { showDeleteDialog = true },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.delete),
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
private fun ScannerExportDialog(
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
        title = { Text(stringResource(R.string.export_as)) },
        text = {
            if (isExporting) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(stringResource(R.string.saving))
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ScannerExportOption(label = stringResource(R.string.export_png)) {
                        scope.launch {
                            isExporting = true
                            val count = withContext(Dispatchers.IO) {
                                PdfStorage.exportPdfAsPngs(context, item.uri, baseName)
                            }
                            isExporting = false
                            Toast.makeText(
                                context,
                                if (count > 0) {
                                    context.getString(R.string.export_png_success)
                                } else {
                                    context.getString(R.string.export_failed)
                                },
                                Toast.LENGTH_LONG
                            ).show()
                            onDismiss()
                        }
                    }
                    ScannerExportOption(label = stringResource(R.string.export_html)) {
                        scope.launch {
                            isExporting = true
                            val ok = withContext(Dispatchers.IO) {
                                PdfStorage.exportPdfAsHtml(context, item.uri, baseName)
                            }
                            isExporting = false
                            Toast.makeText(
                                context,
                                if (ok) {
                                    context.getString(R.string.export_html_success)
                                } else {
                                    context.getString(R.string.export_failed)
                                },
                                Toast.LENGTH_LONG
                            ).show()
                            onDismiss()
                        }
                    }
                    ScannerExportOption(label = stringResource(R.string.export_docx)) {
                        scope.launch {
                            isExporting = true
                            val ok = withContext(Dispatchers.IO) {
                                PdfStorage.exportPdfAsDocx(context, item.uri, baseName)
                            }
                            isExporting = false
                            Toast.makeText(
                                context,
                                if (ok) {
                                    context.getString(R.string.export_docx_success)
                                } else {
                                    context.getString(R.string.export_failed)
                                },
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
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun ScannerExportOption(label: String, onClick: () -> Unit) {
    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ReorderPagesDialog(
    item: SavedPdf,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    onFailed: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pageOrder by remember(item.uri) { mutableStateOf<List<Int>?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    LaunchedEffect(item.uri) {
        pageOrder = withContext(Dispatchers.IO) { PdfStorage.getPdfPageOrder(context, item.uri) }
    }

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text(stringResource(R.string.move_pages)) },
        text = {
            when (val order = pageOrder) {
                null -> Text(stringResource(R.string.loading_pages))
                else -> {
                    if (order.size <= 1) {
                        Text(stringResource(R.string.single_page_pdf))
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().height(280.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            itemsIndexed(order) { index, page ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(R.string.page_number, page + 1),
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    TextButton(
                                        enabled = index > 0 && !isSaving,
                                        onClick = {
                                            pageOrder = order.toMutableList().apply {
                                                val temp = this[index - 1]
                                                this[index - 1] = this[index]
                                                this[index] = temp
                                            }
                                        }
                                    ) {
                                        Text(stringResource(R.string.up))
                                    }
                                    TextButton(
                                        enabled = index < order.lastIndex && !isSaving,
                                        onClick = {
                                            pageOrder = order.toMutableList().apply {
                                                val temp = this[index + 1]
                                                this[index + 1] = this[index]
                                                this[index] = temp
                                            }
                                        }
                                    ) {
                                        Text(stringResource(R.string.down))
                                    }
                                    IconButton(
                                        enabled = order.size > 1 && !isSaving,
                                        onClick = {
                                            pageOrder = order.toMutableList().apply { removeAt(index) }
                                        }
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = stringResource(R.string.delete_page),
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
                            PdfStorage.reorderPdfPages(context, item.uri, order)
                        }
                        isSaving = false
                        if (saved) onSaved() else onFailed()
                    }
                }
            ) {
                Text(stringResource(if (isSaving) R.string.saving else R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun RenamePdfDialog(
    item: SavedPdf,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var value by remember(item.uri) { mutableStateOf(item.name.removeSuffix(".pdf")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text(stringResource(R.string.file_name_pattern)) }
            )
        },
        confirmButton = {
            TextButton(onClick = { onRename(value) }, enabled = value.isNotBlank()) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun PdfPreviewDialog(
    item: SavedPdf,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var page by remember(item.uri) { mutableStateOf(0) }
    val pageCount by produceState<Int?>(initialValue = null, item.uri) {
        value = withContext(Dispatchers.IO) { PdfStorage.countPdfPages(context, item.uri) }
    }
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, item.uri, page) {
        value = withContext(Dispatchers.IO) { PdfStorage.renderPdfPage(context, item.uri, page) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.preview)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(380.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap!!.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        CircularProgressIndicator()
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(stringResource(R.string.page_x_of_y, page + 1, pageCount ?: 0))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { if (page > 0) page-- }, enabled = page > 0) { Text(stringResource(R.string.up)) }
                    TextButton(
                        onClick = { if (pageCount != null && page < pageCount!! - 1) page++ },
                        enabled = pageCount != null && page < pageCount!! - 1
                    ) { Text(stringResource(R.string.down)) }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0
    return when {
        bytes >= gb -> String.format("%.2f GB", bytes / gb)
        bytes >= mb -> String.format("%.2f MB", bytes / mb)
        bytes >= kb -> String.format("%.1f KB", bytes / kb)
        else -> "$bytes B"
    }
}
