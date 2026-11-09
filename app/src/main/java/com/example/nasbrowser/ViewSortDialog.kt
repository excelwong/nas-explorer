package com.example.nasbrowser

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.example.nasbrowser.model.SortMode
import com.example.nasbrowser.model.ViewKind
import com.example.nasbrowser.model.ViewMode

/**
 * ES File Explorer's single 视图 dialog.
 *
 * Top: a 3 x 3 matrix — 图标 / 列表 / 详情 families, each in 大 / 中 / 小.
 * Bottom: 排序 — four columns (名称 / 类型 / 大小 / 修改时间) x two rows (升序 / 降序).
 *
 * The dialog stays open while the user experiments: picking a view or a sort applies it at once
 * and only the 确定 button closes it, exactly like ES. The chosen cell gets a blue outline.
 */
object ViewSortDialog {

    private fun outline(on: Boolean): GradientDrawable = GradientDrawable().apply {
        cornerRadius = 8f
        setColor(if (on) 0xFFEAF3FF.toInt() else Color.TRANSPARENT)
        setStroke(if (on) 2 else 0, 0xFF2C7CF3.toInt())
    }

    fun show(
        ctx: Context,
        currentView: ViewMode,
        currentSort: SortMode,
        onView: (ViewMode) -> Unit,
        onSort: (SortMode) -> Unit
    ) {
        val root = LayoutInflater.from(ctx).inflate(R.layout.dialog_view_sort, null)
        val grid = root.findViewById<LinearLayout>(R.id.gridViews)
        val header = root.findViewById<LinearLayout>(R.id.sortHeader)
        val ascRow = root.findViewById<LinearLayout>(R.id.sortAsc)
        val descRow = root.findViewById<LinearLayout>(R.id.sortDesc)

        val dialog = AlertDialog.Builder(ctx)
            .setView(root)
            .setPositiveButton(R.string.ok, null)
            .create()

        var view = currentView
        var sort = currentSort
        val viewTiles = ArrayList<Pair<ViewMode, View>>()
        val sortCells = ArrayList<Triple<SortMode, View, TextView>>()

        fun refreshViews() {
            viewTiles.forEach { (m, tile) ->
                tile.background = outline(m == view)
                tile.findViewById<TextView>(R.id.tvViewLabel).setTextColor(
                    if (m == view) 0xFF2C7CF3.toInt() else 0xFF2E2E2E.toInt()
                )
            }
        }

        fun refreshSort() {
            sortCells.forEach { (m, cell, text) ->
                cell.background = outline(m == sort)
                text.setTextColor(if (m == sort) 0xFF2C7CF3.toInt() else 0xFF5A6472.toInt())
            }
        }

        // ---- 3 x 3 view matrix ----------------------------------------------------------
        val modes = ViewMode.values()
        for (row in 0..2) {
            val line = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            for (col in 0..2) {
                val m = modes[row * 3 + col]
                val tile = LayoutInflater.from(ctx).inflate(R.layout.item_view_tile, line, false)
                tile.findViewById<TextView>(R.id.tvViewLabel).text = m.label
                tile.findViewById<ImageView>(R.id.imgView).setImageResource(
                    when (m.kind) {
                        ViewKind.ICON -> R.drawable.ic_view_icon
                        ViewKind.LIST -> R.drawable.ic_view_list
                        ViewKind.DETAIL -> R.drawable.ic_view_detail
                    }
                )
                tile.setOnClickListener {
                    view = m
                    onView(m)
                    refreshViews()
                }
                line.addView(tile)
                viewTiles.add(m to tile)
            }
            grid.addView(line)
        }
        refreshViews()

        // ---- 4 columns x 2 directions ---------------------------------------------------
        val fields = SortMode.Field.values()
        fields.forEach { f ->
            header.addView(TextView(ctx).apply {
                text = f.label
                textSize = 13f
                setTextColor(0xFF2E2E2E.toInt())
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
        for (asc in listOf(true, false)) {
            val line = if (asc) ascRow else descRow
            fields.forEach { f ->
                val m = SortMode.of(f, asc)
                val cell = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    isClickable = true
                    background = outline(false)
                    setPadding(4, 8, 4, 8)
                    layoutParams = LinearLayout.LayoutParams(
                        0, (74 * ctx.resources.displayMetrics.density).toInt(), 1f
                    ).apply {
                        setMargins(3, 0, 3, 0)
                    }
                }
                cell.addView(TextView(ctx).apply {
                    text = if (asc) "↑" else "↓"
                    textSize = 22f
                    gravity = Gravity.CENTER
                    setTextColor(0xFF5A6472.toInt())
                })
                val label = TextView(ctx).apply {
                    text = ctx.getString(if (asc) R.string.sort_asc else R.string.sort_desc)
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setTextColor(0xFF5A6472.toInt())
                }
                cell.addView(label)
                cell.setOnClickListener {
                    sort = m
                    onSort(m)
                    refreshSort()
                }
                line.addView(cell)
                sortCells.add(Triple(m, cell, label))
            }
        }
        refreshSort()

        dialog.show()
    }
}
