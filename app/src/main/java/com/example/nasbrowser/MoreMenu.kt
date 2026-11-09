package com.example.nasbrowser

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView

/** One row in the long ES overflow menu. The click action is LAST so trailing lambdas work. */
data class MoreItem(
    val label: String,
    val icon: Int,
    val run: () -> Unit
)

/**
 * ES shows a tall, scrollable icon+label popup anchored above the bottom-right 更多 button.
 * A plain PopupMenu cannot show icons reliably, so this builds the popup directly — no new
 * dependency and it can hold all sixteen entries without being clipped.
 */
object MoreMenu {

    fun show(ctx: Context, anchor: View, items: List<MoreItem>) {
        val d = ctx.resources.displayMetrics.density
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
        }

        lateinit var popup: PopupWindow
        items.forEach { item ->
            val row = LayoutInflater.from(ctx).inflate(R.layout.item_more_menu, box, false)
            row.findViewById<ImageView>(R.id.imgMore).setImageResource(item.icon)
            row.findViewById<TextView>(R.id.tvMore).text = item.label
            row.setOnClickListener {
                popup.dismiss()
                item.run()
            }
            box.addView(row)
        }

        val scroll = ScrollView(ctx).apply {
            addView(box)
            setBackgroundColor(Color.WHITE)
            isVerticalScrollBarEnabled = true
        }
        val width = minOf((250 * d).toInt(), ctx.resources.displayMetrics.widthPixels - (16 * d).toInt())
        val height = minOf((540 * d).toInt(), ctx.resources.displayMetrics.heightPixels * 3 / 5)
        popup = PopupWindow(scroll, width, height, true).apply {
            isOutsideTouchable = true
            elevation = 8 * d
            setBackgroundDrawable(ColorDrawable(Color.WHITE))
        }
        popup.showAtLocation(
            anchor, Gravity.BOTTOM or Gravity.END,
            (8 * d).toInt(), (66 * d).toInt()
        )
    }
}
