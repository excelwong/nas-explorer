package com.example.nasbrowser

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.nasbrowser.model.ViewMode
import com.example.nasbrowser.smb.SmbEntry
import com.example.nasbrowser.util.ThumbnailLoader
import com.example.nasbrowser.util.formatSize
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ES-style file list shared by the SMB browser, the local browser and the recycle bin.
 *
 * Supports all nine ES view modes (图标 / 列表 / 详情 x 大 / 中 / 小). The scale is applied
 * programmatically so three row layouts cover all nine combinations. During multi-selection each
 * row shows a round checkbox on the right (empty circle / green tick) exactly like ES.
 */
class FileAdapter(
    private val viewMode: ViewMode,
    private val showThumbnails: Boolean,
    private val showNewFiles: Boolean,
    private val newThreshold: Long,
    private val isSelecting: () -> Boolean,
    private val onToggleSelect: (SmbEntry) -> Unit,
    private val onActivate: (SmbEntry) -> Unit,
    private val onLongPress: (SmbEntry) -> Unit,
    private val onOpen: (SmbEntry) -> Unit,
    private val loadThumb: (SmbEntry, ImageView) -> Unit
) : ListAdapter<SmbEntry, FileAdapter.VH>(DIFF) {

    private val selected = mutableSetOf<String>()
    private val timeFmt = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())

    fun setSelected(set: Set<String>) {
        selected.clear()
        selected.addAll(set)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(viewMode.layoutRes, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    private fun iconFor(e: SmbEntry): Int {
        if (e.isDirectory) return R.drawable.ic_folder_blue
        return when (e.name.substringAfterLast('.', "").lowercase()) {
            "txt", "md", "log", "csv", "json", "xml", "srt" -> R.drawable.ic_file_txt
            "pdf" -> R.drawable.ic_file_pdf
            "zip", "rar", "7z" -> R.drawable.ic_more_zip
            else -> R.drawable.ic_file_grey
        }
    }

    inner class VH(private val root: View) : RecyclerView.ViewHolder(root) {
        private val rowMain: View = root.findViewById(R.id.rowMain)
        private val imgIcon: ImageView = root.findViewById(R.id.imgIcon)
        private val tvName: TextView = root.findViewById(R.id.tvName)
        private val tvMeta: TextView = root.findViewById(R.id.tvMeta)
        private val tvType: TextView = root.findViewById(R.id.tvType)
        private val tvSize: TextView = root.findViewById(R.id.tvSize)
        private val tvTime: TextView = root.findViewById(R.id.tvTime)
        private val tvNew: TextView = root.findViewById(R.id.tvNew)
        private val btnOpen: ImageButton = root.findViewById(R.id.btnOpen)
        private val cbSelect: ImageView = root.findViewById(R.id.cbSelect)

        private var entry: SmbEntry? = null

        init {
            applyScale()
            rowMain.setOnClickListener {
                val e = entry ?: return@setOnClickListener
                if (isSelecting()) onToggleSelect(e) else onActivate(e)
            }
            rowMain.setOnLongClickListener {
                entry?.let(onLongPress)
                true
            }
            btnOpen.setOnClickListener { entry?.let(onOpen) }
            cbSelect.setOnClickListener { entry?.let(onToggleSelect) }
        }

        /** One layout per family; the three sizes only change dimensions. */
        private fun applyScale() {
            val d = root.resources.displayMetrics.density
            val s = viewMode.scale
            val iconSide = if (viewMode.isGrid) s.iconDp else s.rowIconDp
            imgIcon.layoutParams = imgIcon.layoutParams.apply {
                width = (iconSide * d).toInt()
                height = (iconSide * d).toInt()
            }
            if (viewMode.isGrid) {
                imgIcon.scaleType = ImageView.ScaleType.CENTER_CROP
                imgIcon.setBackgroundResource(R.drawable.bg_thumb)
            } else {
                root.layoutParams = root.layoutParams.apply {
                    height = (s.rowHeightDp * d).toInt()
                }
            }
            tvName.textSize = s.textSp
            cbSelect.layoutParams = cbSelect.layoutParams.apply {
                width = (26 * d).toInt()
                height = (26 * d).toInt()
            }
        }

        fun bind(e: SmbEntry) {
            entry = e
            val picked = selected.contains(e.path)
            val selecting = isSelecting()

            tvName.text = e.name
            tvName.setTextColor(ContextCompat.getColor(root.context, R.color.text_primary))
            tvName.visibility = View.VISIBLE

            val date = if (e.lastModified > 0L) timeFmt.format(Date(e.lastModified)) else "—"
            tvMeta.text = if (e.isDirectory) {
                root.context.getString(R.string.folder)
            } else {
                "${formatSize(e.size)}  ·  $date"
            }
            // ES large-icon tiles show only the (two-line) name under the thumbnail.
            if (viewMode.isGrid) tvMeta.visibility = View.GONE
            tvType.text = if (e.isDirectory) "drw" else "-rw"
            tvSize.text = if (e.isDirectory) "—" else formatSize(e.size)
            tvTime.text = date

            tvNew.visibility = if (
                showNewFiles && !e.isDirectory &&
                e.lastModified > 0L && e.lastModified >= newThreshold
            ) View.VISIBLE else View.GONE

            imgIcon.tag = e.path
            imgIcon.setImageResource(iconFor(e))
            if (viewMode.isGrid) {
                imgIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
            }
            if (showThumbnails && !e.isDirectory && ThumbnailLoader.isImage(e.name)) {
                imgIcon.scaleType = ImageView.ScaleType.CENTER_CROP
                loadThumb(e, imgIcon)
            }

            btnOpen.visibility = when {
                selecting || viewMode.isGrid -> View.GONE
                else -> View.VISIBLE
            }
            cbSelect.visibility = if (selecting) View.VISIBLE else View.GONE
            cbSelect.setImageResource(
                if (picked) R.drawable.ic_check_on else R.drawable.ic_check_off
            )
            root.setBackgroundColor(
                if (picked) 0xFFECECEC.toInt() else Color.WHITE
            )
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<SmbEntry>() {
            override fun areItemsTheSame(a: SmbEntry, b: SmbEntry) = a.path == b.path
            override fun areContentsTheSame(a: SmbEntry, b: SmbEntry) = a == b
        }
    }
}
