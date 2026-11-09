package com.example.nasbrowser

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.net.Uri
import android.os.Build
import android.widget.EditText
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.nasbrowser.smb.SmbEntry
import com.example.nasbrowser.util.ZipUtils
import com.example.nasbrowser.util.formatSize
import com.example.nasbrowser.util.guessMime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small, dependency-free implementations behind the ES 更多 menu entries. */
object FileOps {

    private val timeFmt = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())

    fun properties(ctx: Context, e: SmbEntry) {
        val type = if (e.isDirectory) ctx.getString(R.string.folder) else
            e.name.substringAfterLast('.', "?").uppercase()
        val body = ctx.getString(
            R.string.props_body,
            e.name,
            type,
            if (e.isDirectory) "—" else formatSize(e.size),
            if (e.lastModified > 0) timeFmt.format(Date(e.lastModified)) else "—",
            e.path
        )
        AlertDialog.Builder(ctx)
            .setTitle(R.string.props_title)
            .setMessage(body)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    fun uri(ctx: Context, file: File): Uri =
        FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file)

    fun share(ctx: Context, file: File) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = guessMime(file.name)
                putExtra(Intent.EXTRA_STREAM, uri(ctx, file))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(Intent.createChooser(intent, ctx.getString(R.string.more_share)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Exception) {
            Toast.makeText(ctx, R.string.local_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    fun openWith(ctx: Context, file: File) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri(ctx, file), guessMime(file.name))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(Intent.createChooser(intent, ctx.getString(R.string.more_bind_icon)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Exception) {
            Toast.makeText(ctx, R.string.local_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /** Add a launcher shortcut that opens a local file (pinned shortcut on Android 8+). */
    fun addShortcut(ctx: Context, file: File, label: String) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri(ctx, file), guessMime(file.name))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = ctx.getSystemService(ShortcutManager::class.java)
            if (mgr != null && mgr.isRequestPinShortcutSupported) {
                val info = ShortcutInfo.Builder(ctx, "file_" + file.absolutePath.hashCode())
                    .setShortLabel(label.take(20))
                    .setIcon(android.graphics.drawable.Icon.createWithResource(ctx, R.drawable.ic_file_grey))
                    .setIntent(intent)
                    .build()
                mgr.requestPinShortcut(info, null)
                return
            }
        }
        @Suppress("DEPRECATION")
        val legacy = Intent("com.android.launcher.action.INSTALL_SHORTCUT").apply {
            putExtra(Intent.EXTRA_SHORTCUT_NAME, label)
            putExtra(Intent.EXTRA_SHORTCUT_INTENT, intent)
        }
        ctx.sendBroadcast(legacy)
        Toast.makeText(ctx, R.string.op_done, Toast.LENGTH_SHORT).show()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("nas_pinned", Context.MODE_PRIVATE)

    fun pinned(ctx: Context): Set<String> =
        prefs(ctx).getString("paths", "").orEmpty()
            .split('\n').filter { it.isNotEmpty() }.toSet()

    fun pin(ctx: Context, paths: Collection<String>) {
        val merged = pinned(ctx).toMutableSet().apply { addAll(paths) }
        prefs(ctx).edit().putString("paths", merged.joinToString("\n")).apply()
        Toast.makeText(ctx, ctx.getString(R.string.pinned_done, paths.size), Toast.LENGTH_SHORT).show()
    }

    /** Pick a destination directory: type a path, or tap one of the child folders. */
    fun chooseTargetDir(
        ctx: Context,
        current: String,
        children: List<String>,
        onPick: (String) -> Unit
    ) {
        val input = EditText(ctx).apply {
            hint = ctx.getString(R.string.move_target_hint)
            setText(current)
        }
        val folders = children.toTypedArray()
        AlertDialog.Builder(ctx)
            .setTitle(R.string.move_target_title)
            .setView(input)
            .setItems(folders) { _, i -> onPick(children[i]) }
            .setPositiveButton(R.string.ok) { _, _ -> onPick(input.text.toString().trim()) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Local 复制 / 剪切 into [target] (an absolute path or a child name of [current]). */
    suspend fun localMoveCopy(
        files: List<File>,
        current: File,
        target: String,
        move: Boolean
    ): Throwable? = withContext(Dispatchers.IO) {
        try {
            val dir = File(target).let { if (it.isAbsolute) it else File(current, target) }
            if (!dir.exists() && !dir.mkdirs()) throw IllegalStateException("无法创建目标目录")
            for (f in files) {
                val dest = File(dir, f.name)
                if (f.absolutePath == dest.absolutePath) continue
                if (move && f.renameTo(dest)) continue
                if (f.isDirectory) f.copyRecursively(dest, overwrite = true)
                else f.copyTo(dest, overwrite = true)
                if (move) {
                    if (f.isDirectory) f.deleteRecursively() else f.delete()
                }
            }
            null
        } catch (e: Exception) {
            e
        }
    }

    /** Local 压缩 into the current directory. */
    suspend fun localCompress(files: List<File>, current: File, zipName: String): Throwable? =
        withContext(Dispatchers.IO) {
            try {
                val out = File(current, zipName)
                ZipUtils.zipFiles(files, out)
                null
            } catch (e: Exception) {
                e
            }
        }
}
