package com.example.nasbrowser

import androidx.lifecycle.ViewModel
import com.example.nasbrowser.smb.SmbEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Shared between the file list and the wide-screen detail pane (Activity scope). */
class SelectionViewModel : ViewModel() {
    private val _selected = MutableStateFlow<SmbEntry?>(null)
    val selected = _selected.asStateFlow()

    fun select(entry: SmbEntry?) {
        _selected.value = entry
    }
}
