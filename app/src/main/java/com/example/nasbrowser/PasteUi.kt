package com.example.nasbrowser

import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.example.nasbrowser.databinding.DialogOverwriteBinding
import com.example.nasbrowser.databinding.DialogPasteProgressBinding
import com.example.nasbrowser.util.ClipboardState
import com.example.nasbrowser.util.ConflictAnswer
import com.example.nasbrowser.util.ConflictDecision
import com.example.nasbrowser.util.FileClipboard
import com.example.nasbrowser.util.PasteProgress
import com.example.nasbrowser.util.PasteSpec
import com.example.nasbrowser.util.PasteTaskItem
import com.example.nasbrowser.viewmodel.BrowserViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CancellableContinuation
import kotlin.coroutines.resume
import java.util.Locale

/** Only one copy / move job may run at a time (the bottom bar stays tappable during a transfer). */
object PasteBusy {
    @Volatile
    var active: Boolean = false
}

private fun humanSize(bytes: Long): String {
    val b = bytes.coerceAtLeast(0L)
    return when {
        b < 1024L -> "$b B"
        b < 1024L * 1024L -> String.format(Locale.US, "%.2f KB", b / 1024.0)
        b < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f MB", b / (1024.0 * 1024.0))
        else -> String.format(Locale.US, "%.2f GB", b / (1024.0 * 1024.0 * 1024.0))
    }
}

private fun humanSpeed(bytesPerSec: Double): String {
    val b = bytesPerSec.coerceAtLeast(0.0)
    return when {
        b < 1024.0 -> String.format(Locale.US, "%.2f B/s", b)
        b < 1024.0 * 1024.0 -> String.format(Locale.US, "%.2f KB/s", b / 1024.0)
        else -> String.format(Locale.US, "%.2f MB/s", b / (1024.0 * 1024.0))
    }
}

private fun humanDuration(seconds: Long): String = when {
    seconds < 60L -> "$seconds 秒"
    seconds < 3600L -> "${seconds / 60L} 分 ${seconds % 60L} 秒"
    else -> "${seconds / 3600L} 小时 ${(seconds % 3600L) / 60L} 分"
}

/**
 * ES-style transfer dialog: 名称 / 总共 / 从 / 到 / 当前进度 / 总进度 / 剩余时间 / 速度,
 * with 取消 and 隐藏 actions. Also owns the per-file 覆盖 prompt and the
 * "应用到全部" policy. All methods are safe to call from the IO thread.
 */
class PasteProgressUi(
    private val activity: FragmentActivity,
    private val spec: PasteSpec
) {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var b: DialogPasteProgressBinding
    private var dialog: AlertDialog? = null
    private var conflictDialog: AlertDialog? = null
    private var pendingConflict: CancellableContinuation<ConflictAnswer?>? = null
    private var policy: ConflictDecision? = null
    private val startMs = System.currentTimeMillis()
    private val totalBytes = spec.items.fold(0L) { acc, item -> acc + item.size.coerceAtLeast(0L) }

    @Volatile
    private var lastPostMs = 0L

    @Volatile
    private var hidden = false

    @Volatile
    private var finished = false

    @Volatile
    private var cancelRequested = false

    fun isCancelled(): Boolean = cancelRequested

    fun show() {
        b = DialogPasteProgressBinding.inflate(LayoutInflater.from(activity))
        b.tvTitle.setText(if (spec.move) R.string.paste_title_move else R.string.paste_title_copy)
        val names = when (spec.items.size) {
            0 -> ""
            1 -> spec.items[0].name
            2 -> "${spec.items[0].name}，${spec.items[1].name}"
            else -> "${spec.items[0].name}，${spec.items[1].name} 等 ${spec.items.size} 项"
        }
        b.tvNames.text = activity.getString(R.string.paste_names, names)
        b.tvTotal.text = activity.getString(
            R.string.paste_total, spec.items.size, humanSize(totalBytes)
        )
        b.tvFrom.text = activity.getString(R.string.paste_from, spec.fromLabel)
        b.tvTo.text = activity.getString(R.string.paste_to, spec.toLabel)
        b.btnCancel.setOnClickListener {
            cancelRequested = true
        }
        b.btnHide.setOnClickListener {
            hidden = true
            try { dialog?.dismiss() } catch (_: Exception) {}
            Toast.makeText(activity, R.string.paste_hidden, Toast.LENGTH_SHORT).show()
        }
        val d = AlertDialog.Builder(activity)
            .setView(b.root)
            .setCancelable(false)
            .create()
        dialog = d
        d.show()
        d.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        render(PasteProgress("", 0L, 0L, 0, spec.items.size, 0L, totalBytes))
    }

    /** Throttled to ~8 fps; the actual work keeps running on the IO dispatcher. */
    fun postProgress(p: PasteProgress) {
        val now = System.currentTimeMillis()
        if (now - lastPostMs < 120L && p.doneItems < p.totalItems) return
        lastPostMs = now
        handler.post {
            if (!hidden && !finished && ::b.isInitialized) render(p)
        }
    }

    private fun render(p: PasteProgress) {
        val currentPct = if (p.currentTotal > 0L) {
            ((p.currentBytes * 100L) / p.currentTotal).toInt().coerceIn(0, 100)
        } else 0
        b.tvCurrent.text = activity.getString(R.string.paste_current, p.currentName)
        b.tvCurrentPct.text = "$currentPct%"
        b.pbCurrent.progress = currentPct

        val overallPct = when {
            p.totalBytes > 0L -> ((p.doneBytes * 100L) / p.totalBytes).toInt().coerceIn(0, 100)
            p.totalItems > 0 -> (p.doneItems * 100) / p.totalItems
            else -> 0
        }
        b.tvOverall.text = activity.getString(R.string.paste_overall, p.doneItems, p.totalItems)
        b.tvOverallPct.text = "$overallPct%"
        b.pbOverall.progress = overallPct

        val elapsedSec = ((System.currentTimeMillis() - startMs) / 1000.0).coerceAtLeast(0.3)
        val speed = p.doneBytes / elapsedSec
        b.tvSpeed.text = humanSpeed(speed)
        val left = p.totalBytes - p.doneBytes
        val remain = if (speed > 8.0 * 1024.0 && left > 0L) {
            humanDuration((left / speed).toLong())
        } else activity.getString(R.string.paste_calculating)
        b.tvRemaining.text = activity.getString(R.string.paste_remaining, remain)
    }

    /**
     * Suspend until the user picks 跳过 / 覆盖 for [name]. A previously chosen
     * "应用到全部" answer is returned immediately. Returns null when the job is torn down.
     */
    suspend fun askConflict(name: String): ConflictAnswer? = suspendCancellableCoroutine { cont ->
        handler.post {
            if (finished || activity.isFinishing || activity.isDestroyed) {
                if (cont.isActive) cont.resume(null)
                return@post
            }
            val chosen = policy
            if (chosen != null) {
                if (cont.isActive) cont.resume(ConflictAnswer(chosen))
                return@post
            }
            val vb = DialogOverwriteBinding.inflate(LayoutInflater.from(activity))
            vb.tvMessage.text = activity.getString(R.string.overwrite_msg, name)
            val d = AlertDialog.Builder(activity)
                .setView(vb.root)
                .setCancelable(false)
                .create()

            fun answer(decision: ConflictDecision) {
                val result = ConflictAnswer(decision, vb.cbApplyAll.isChecked)
                if (result.applyAll) policy = result.decision
                pendingConflict = null
                conflictDialog = null
                try { d.dismiss() } catch (_: Exception) {}
                if (cont.isActive) cont.resume(result)
            }
            vb.btnSkip.setOnClickListener { answer(ConflictDecision.SKIP) }
            vb.btnOverwrite.setOnClickListener { answer(ConflictDecision.OVERWRITE) }
            d.setOnDismissListener {
                if (conflictDialog === d) {
                    conflictDialog = null
                    pendingConflict = null
                    if (cont.isActive) cont.resume(ConflictAnswer(ConflictDecision.SKIP))
                }
            }
            pendingConflict = cont
            conflictDialog = d
            d.show()
            d.window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    fun close() {
        finished = true
        handler.post {
            try { dialog?.dismiss() } catch (_: Exception) {}
            try { conflictDialog?.dismiss() } catch (_: Exception) {}
            dialog = null
            conflictDialog = null
            val c = pendingConflict
            pendingConflict = null
            if (c?.isActive == true) c.resume(null)
        }
    }
}

/**
 * Start a Windows-style paste from either browser fragment. Runs on the Activity scope, so
 * switching tabs (or hiding the dialog) does not abort the transfer.
 */
fun Fragment.launchPaste(
    vm: BrowserViewModel,
    clip: ClipboardState,
    spec: PasteSpec,
    onFinished: () -> Unit
) {
    val act = activity ?: return
    if (PasteBusy.active) {
        Toast.makeText(act, R.string.paste_busy, Toast.LENGTH_SHORT).show()
        return
    }
    PasteBusy.active = true
    val ui = PasteProgressUi(act, spec)
    ui.show()
    act.lifecycleScope.launch(Dispatchers.Main) {
        try {
            val summary = withContext(Dispatchers.IO) {
                vm.runPasteTransfer(
                    clip, spec,
                    ask = { name -> ui.askConflict(name) },
                    report = { ui.postProgress(it) },
                    cancelled = { ui.isCancelled() }
                )
            }
            ui.close()
            if (!act.isFinishing && !act.isDestroyed) {
                val msg = when {
                    summary.cancelled -> act.getString(R.string.paste_cancelled, summary.ok)
                    summary.failed > 0 -> act.getString(
                        R.string.paste_partial,
                        summary.ok, summary.skipped, summary.failed, summary.firstError.orEmpty()
                    )
                    else -> act.getString(R.string.paste_done, summary.ok, summary.skipped)
                }
                Toast.makeText(act, msg, Toast.LENGTH_LONG).show()
                if (spec.skippedDirs > 0) {
                    Toast.makeText(
                        act,
                        act.getString(R.string.clip_dir_skipped, spec.skippedDirs),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            // The paste job is over: drop the staged clipboard so MainActivity.updateChrome()
            // rebuilds the normal ES toolbar instead of leaving 粘贴 / 新建 / 取消 on screen.
            // This applies to COPY as well as CUT, and to success / skip / failure / 取消 alike.
            if (isAdded) onFinished()
        } finally {
            ui.close()
            FileClipboard.clear()
            if (!act.isFinishing && !act.isDestroyed) {
                (act as? SelectionHost)?.refreshChrome()
            }
            PasteBusy.active = false
        }
    }
}

/** Convenience for building the item list of a local clipboard. */
fun localTaskItem(file: java.io.File): PasteTaskItem = PasteTaskItem(
    name = file.name,
    path = file.absolutePath,
    size = if (file.isDirectory) 0L else file.length(),
    isDirectory = file.isDirectory
)
