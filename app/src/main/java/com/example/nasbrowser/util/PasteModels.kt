package com.example.nasbrowser.util

import java.io.File

/** What to do when a file with the same name already exists in the destination. */
enum class ConflictDecision { OVERWRITE, SKIP }

/** [applyAll] = the ES-style "应用到全部" checkbox; the answer then becomes the default. */
data class ConflictAnswer(val decision: ConflictDecision, val applyAll: Boolean = false)

/** One staged clipboard item prepared for transfer. [path] is an SMB-relative or absolute local path. */
data class PasteTaskItem(
    val name: String,
    val path: String,
    val size: Long,
    val isDirectory: Boolean = false
)

/**
 * Everything the transfer engine needs. [targetLocal] picks the destination kind; NAS targets
 * use [targetSmbDir] (a path relative to the share root), device targets use [targetDir].
 */
data class PasteSpec(
    val move: Boolean,
    val fromLabel: String,
    val toLabel: String,
    val items: List<PasteTaskItem>,
    val targetLocal: Boolean,
    val targetSmbDir: String = "",
    val targetDir: File? = null,
    val skippedDirs: Int = 0
)

/** A snapshot of a running transfer. Posted to the UI from the IO thread. */
data class PasteProgress(
    val currentName: String,
    val currentBytes: Long,
    val currentTotal: Long,
    val doneItems: Int,
    val totalItems: Int,
    val doneBytes: Long,
    val totalBytes: Long
)

data class PasteSummary(
    val ok: Int,
    val skipped: Int,
    val failed: Int,
    val firstError: String?,
    val cancelled: Boolean
)

/** Thrown inside a chunked copy loop when the user taps 取消 in the progress dialog. */
class PasteCancelledException : RuntimeException("paste cancelled")
