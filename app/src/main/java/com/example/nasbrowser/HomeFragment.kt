package com.example.nasbrowser

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.nasbrowser.databinding.FragmentHomeBinding
import com.example.nasbrowser.util.UiPrefs
import com.example.nasbrowser.util.formatSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Home page. v4.1.5: capacity in GB, no analyser / clean / cloud / log / cast entries,
 * and a real "today's new files" list restricted to music, video, images and documents.
 */
class HomeFragment : Fragment(), PageActions {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val badges = HashMap<Int, TextView>()

    private data class Cat(
        val label: Int,
        val icon: Int,
        val color: Int,
        val onClick: () -> Unit
    )

    private enum class NewKind(val label: Int, val icon: Int, val mime: String) {
        MUSIC(R.string.home_cat_music, R.drawable.ic_cat_music, "audio/*"),
        VIDEO(R.string.home_cat_video, R.drawable.ic_cat_video, "video/*"),
        IMAGE(R.string.home_cat_image, R.drawable.ic_cat_image, "image/*"),
        DOC(R.string.home_kind_doc, R.drawable.ic_file_txt, "*/*")
    }

    private data class NewFile(val file: File, val kind: NewKind)

    companion object {
        private val MUSIC_EXT = setOf(
            "mp3", "m4a", "aac", "flac", "wav", "ogg", "wma", "ape", "amr",
            "opus", "mid", "midi", "aiff"
        )
        private val VIDEO_EXT = setOf(
            "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "3gp", "ts",
            "m4v", "rmvb", "rm", "mpg", "mpeg"
        )
        private val IMAGE_EXT = setOf(
            "jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif",
            "tif", "tiff", "svg"
        )
        /** Exactly the document formats requested; all other extensions are hidden. */
        private val DOC_EXT = setOf(
            "txt", "docx", "pptx", "xlsx", "md", "json", "doc", "ppt", "xls", "html",
            "htm", "pdf"
        )
        private const val MAX_SCAN = 40000
        private const val MAX_DEPTH = 6
        private const val MAX_LIST = 40
    }

    private fun kindOf(ext: String): NewKind? = when (ext) {
        in MUSIC_EXT -> NewKind.MUSIC
        in VIDEO_EXT -> NewKind.VIDEO
        in IMAGE_EXT -> NewKind.IMAGE
        in DOC_EXT -> NewKind.DOC
        else -> null
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()
        val root = Environment.getExternalStorageDirectory()

        binding.storageCard.setOnClickListener {
            (activity as? MainActivity)?.openTab(TabType.LOCAL)
        }

        binding.swNewFiles.isChecked = UiPrefs.showNewFiles(ctx)
        binding.timeline.visibility =
            if (UiPrefs.showNewFiles(ctx)) View.VISIBLE else View.GONE
        binding.swNewFiles.setOnCheckedChangeListener { _, on ->
            UiPrefs.saveShowNewFiles(ctx, on)
            binding.timeline.visibility = if (on) View.VISIBLE else View.GONE
            if (on) refreshNewFiles(root)
        }

        val cats = listOf(
            Cat(R.string.home_cat_music, R.drawable.ic_cat_music, R.color.cat_music) {
                openPath(File(root, "Music"))
            },
            Cat(R.string.home_cat_video, R.drawable.ic_cat_video, R.color.cat_video) {
                openPath(File(root, "Movies"))
            },
            Cat(R.string.home_cat_apps, R.drawable.ic_cat_apps, R.color.cat_apps) {
                (activity as? MainActivity)?.openTab(TabType.APPS)
            },
            Cat(R.string.home_cat_image, R.drawable.ic_cat_image, R.color.cat_image) {
                openPath(File(root, "DCIM"))
            }
        )
        cats.forEachIndexed { i, c ->
            val v = layoutInflater.inflate(R.layout.item_home_tile, binding.catRow1, false)
            val bg = v.findViewById<View>(R.id.tileBg)
            (bg.background?.mutate() as? GradientDrawable)
                ?.setColor(ContextCompat.getColor(ctx, c.color))
            v.findViewById<ImageView>(R.id.tileIcon).setImageResource(c.icon)
            v.findViewById<TextView>(R.id.tileLabel).setText(c.label)
            val badge = v.findViewById<TextView>(R.id.tileBadge)
            badge.text = "0"
            badges[i] = badge
            v.setOnClickListener { c.onClick() }
            binding.catRow1.addView(v)
        }

        refreshStorage(root)
        refreshCounts(root)
        refreshNewFiles(root)
    }

    private fun openPath(f: File) {
        (activity as? MainActivity)?.openLocalPath(f.absolutePath)
    }

    /** Show used / total internal storage in GB, e.g. "98.4G / 256.0G". */
    private fun formatGB(bytes: Long): String =
        String.format(
            Locale.US, "%.1fG",
            bytes.coerceAtLeast(0L) / (1024.0 * 1024.0 * 1024.0)
        )

    private fun refreshStorage(root: File) {
        if (_binding == null) return
        val pct = try {
            val st = StatFs(root.absolutePath)
            val total = st.totalBytes
            val used = total - st.availableBytes
            binding.tvStorageDetail.text = getString(
                R.string.home_storage_detail, formatGB(used), formatGB(total)
            )
            if (total > 0L) ((used * 100L) / total).toInt().coerceIn(0, 100) else 0
        } catch (_: Exception) {
            binding.tvStorageDetail.text = getString(R.string.home_storage_detail, "—", "—")
            0
        }
        binding.tvRing.text = "$pct%"
    }

    private fun refreshCounts(root: File) {
        val ctx = requireContext()
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val counts = intArrayOf(
                count(File(root, "Music")),
                count(File(root, "Movies")) + count(File(root, "DCIM/Camera")),
                try {
                    @Suppress("DEPRECATION")
                    ctx.packageManager.getInstalledApplications(0).size
                } catch (_: Exception) {
                    0
                },
                count(File(root, "DCIM")) + count(File(root, "Pictures"))
            )
            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext
                counts.forEachIndexed { i, n -> badges[i]?.text = n.toString() }
            }
        }
    }

    private fun count(dir: File, depth: Int = 0): Int {
        if (depth > 3) return 0
        var n = 0
        try {
            dir.listFiles()?.forEach {
                if (it.isDirectory) n += count(it, depth + 1) else n++
                if (n > 9999) return n
            }
        } catch (_: Exception) {
        }
        return n
    }

    // ---- today's new files ---------------------------------------------------------------

    private fun todayStart(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Walk shared storage (bounded) and keep only today's whitelisted media/document files. */
    private fun scanToday(root: File): List<NewFile> {
        val start = todayStart()
        val out = ArrayList<NewFile>()
        var visited = 0
        val stack = ArrayDeque<Pair<File, Int>>()
        stack.add(root to 0)
        while (stack.isNotEmpty() && visited < MAX_SCAN) {
            val (dir, depth) = stack.removeLast()
            val children = try { dir.listFiles() } catch (_: Exception) { null } ?: continue
            for (c in children) {
                visited++
                if (visited >= MAX_SCAN) break
                if (c.isDirectory) {
                    // Skip hidden caches and the Android app-data tree (huge, not user media).
                    if (depth < MAX_DEPTH && !c.name.startsWith(".") && c.name != "Android") {
                        stack.add(c to depth + 1)
                    }
                } else if (c.lastModified() >= start && c.length() > 0L) {
                    val kind = kindOf(c.extension.lowercase(Locale.US)) ?: continue
                    out.add(NewFile(c, kind))
                }
            }
        }
        return out.sortedByDescending { it.file.lastModified() }.take(MAX_LIST)
    }

    private fun refreshNewFiles(root: File) {
        if (_binding == null || !UiPrefs.showNewFiles(requireContext())) return
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val list = scanToday(root)
            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext
                renderNewFiles(list)
            }
        }
    }

    private fun renderNewFiles(list: List<NewFile>) {
        val box = binding.boxNewFiles
        box.removeAllViews()
        binding.tvNewEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        val time = SimpleDateFormat("HH:mm", Locale.getDefault())
        list.forEach { n ->
            val row = layoutInflater.inflate(R.layout.item_home_new_file, box, false)
            row.findViewById<ImageView>(R.id.imgKind).setImageResource(n.kind.icon)
            row.findViewById<TextView>(R.id.tvFileName).text = n.file.name
            row.findViewById<TextView>(R.id.tvFileSub).text = getString(
                R.string.home_new_file_sub,
                getString(n.kind.label),
                formatSize(n.file.length()),
                n.file.parentFile?.name ?: getString(R.string.home_internal)
            )
            row.findViewById<TextView>(R.id.tvFileTime).text =
                time.format(n.file.lastModified())
            row.setOnClickListener { openNewFile(n) }
            box.addView(row)
        }
    }

    private fun openNewFile(n: NewFile) {
        val ctx = requireContext()
        try {
            val uri = FileOps.uri(ctx, n.file)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, n.kind.mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, n.file.name))
        } catch (_: Exception) {
            (activity as? MainActivity)?.openLocalPath(
                n.file.parent ?: n.file.absolutePath
            )
            Toast.makeText(ctx, R.string.home_open_folder, Toast.LENGTH_SHORT).show()
        }
    }

    // ---- PageActions --------------------------------------------------------------------

    /** No MORE on the home page: the internal-storage analyser is gone in v4.1.5. */
    override fun toolbarActions(): List<Action> =
        listOf(Action.NEW, Action.SEARCH, Action.REFRESH, Action.WINDOW)

    override fun onToolbarAction(a: Action): Boolean {
        if (_binding == null) return false
        return when (a) {
            Action.NEW -> { (activity as? MainActivity)?.showNewTabDialog(); true }
            Action.SEARCH -> { (activity as? MainActivity)?.openTab(TabType.LOCAL); true }
            Action.REFRESH -> {
                val root = Environment.getExternalStorageDirectory()
                refreshStorage(root)
                refreshCounts(root)
                refreshNewFiles(root)
                true
            }
            else -> false
        }
    }

    override fun onBackPressed(): Boolean = false

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
