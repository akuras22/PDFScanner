package com.akuras.pdfscanner

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HistoryUiState(
    val items: List<SavedPdf> = emptyList(),
    val allItems: List<SavedPdf> = emptyList(),
    val mergeMode: Boolean = false,
    val isMerging: Boolean = false,
    val selectedMergeUris: Set<String> = emptySet(),
    val thumbnailSeed: Int = 0,
    val sortOrder: PdfSortOrder = PdfSortOrder.DATE_DESC,
    val searchQuery: String = "",
)

class HistoryViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val _messages = MutableSharedFlow<Int>()
    val messages: SharedFlow<Int> = _messages

    var uiState by mutableStateOf(HistoryUiState())
        private set

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val allItems = withContext(Dispatchers.IO) {
                PdfStorage.querySavedPdfs(appContext, uiState.sortOrder)
            }
            val visibleItems = applySearch(allItems, uiState.searchQuery)
            uiState = uiState.copy(
                allItems = allItems,
                items = visibleItems,
                selectedMergeUris = uiState.selectedMergeUris.filterTo(mutableSetOf()) { selected ->
                    allItems.any { it.uri.toString() == selected }
                }
            )
        }
    }

    fun setSearchQuery(query: String) {
        uiState = uiState.copy(
            searchQuery = query,
            items = applySearch(uiState.allItems, query)
        )
    }

    fun setSortOrder(order: PdfSortOrder) {
        if (order == uiState.sortOrder) return
        uiState = uiState.copy(sortOrder = order)
        refresh()
    }

    fun toggleMergeMode() {
        val next = !uiState.mergeMode
        uiState = uiState.copy(
            mergeMode = next,
            selectedMergeUris = if (next) uiState.selectedMergeUris else emptySet()
        )
    }

    fun toggleSelection(uri: Uri) {
        val key = uri.toString()
        val selected = uiState.selectedMergeUris
        uiState = uiState.copy(
            selectedMergeUris = if (selected.contains(key)) selected - key else selected + key
        )
    }

    fun mergeSelected(pattern: String) {
        if (uiState.selectedMergeUris.size < 2 || uiState.isMerging) return
        viewModelScope.launch {
            uiState = uiState.copy(isMerging = true)
            val mergeUris = uiState.allItems
                .filter { uiState.selectedMergeUris.contains(it.uri.toString()) }
                .map { it.uri }
            val merged = withContext(Dispatchers.IO) {
                PdfStorage.mergePdfsToDownloads(appContext, mergeUris, pattern)
            }
            uiState = uiState.copy(isMerging = false)
            if (merged != null) {
                _messages.emit(R.string.toast_merge_success)
                refresh()
                uiState = uiState.copy(
                    mergeMode = false,
                    selectedMergeUris = emptySet(),
                    thumbnailSeed = uiState.thumbnailSeed + 1
                )
            } else {
                _messages.emit(R.string.toast_merge_failed)
            }
        }
    }

    fun shareSelected() {
        val selected = uiState.allItems.filter { uiState.selectedMergeUris.contains(it.uri.toString()) }
        if (selected.isEmpty()) return
        PdfStorage.sharePdfs(appContext, selected)
    }

    fun deleteSelected() {
        val uris = uiState.selectedMergeUris.map(Uri::parse)
        if (uris.isEmpty()) return
        viewModelScope.launch {
            var successCount = 0
            withContext(Dispatchers.IO) {
                uris.forEach { uri ->
                    if (PdfStorage.deletePdf(appContext, uri)) successCount++
                }
            }
            if (successCount > 0) {
                _messages.emit(R.string.toast_deleted)
                uiState = uiState.copy(
                    selectedMergeUris = emptySet(),
                    mergeMode = false,
                    thumbnailSeed = uiState.thumbnailSeed + 1
                )
                refresh()
            } else {
                _messages.emit(R.string.toast_delete_failed)
            }
        }
    }

    fun rename(uri: Uri, newName: String) {
        viewModelScope.launch {
            val renamed = withContext(Dispatchers.IO) {
                PdfStorage.renamePdf(appContext, uri, newName)
            }
            if (renamed) {
                _messages.emit(R.string.toast_renamed)
                refresh()
            } else {
                _messages.emit(R.string.toast_rename_failed)
            }
        }
    }

    fun delete(uri: Uri) {
        viewModelScope.launch {
            val deleted = withContext(Dispatchers.IO) {
                PdfStorage.deletePdf(appContext, uri)
            }
            if (deleted) {
                uiState = uiState.copy(
                    allItems = uiState.allItems.filterNot { it.uri == uri },
                    items = uiState.items.filterNot { it.uri == uri },
                    selectedMergeUris = uiState.selectedMergeUris - uri.toString(),
                    thumbnailSeed = uiState.thumbnailSeed + 1
                )
                _messages.emit(R.string.toast_deleted)
            } else {
                _messages.emit(R.string.toast_delete_failed)
            }
        }
    }

    fun onReordered(success: Boolean) {
        viewModelScope.launch {
            if (success) {
                _messages.emit(R.string.toast_pages_updated)
                refresh()
                uiState = uiState.copy(thumbnailSeed = uiState.thumbnailSeed + 1)
            } else {
                _messages.emit(R.string.toast_pages_update_failed)
            }
        }
    }

    private fun applySearch(items: List<SavedPdf>, query: String): List<SavedPdf> {
        return filterPdfsByQuery(items, query)
    }
}
