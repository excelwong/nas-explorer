package com.example.nasbrowser

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * Renders the ES-style breadcrumb bar: `/ › storage › emulated › 0 › Download`.
 * Every segment is independently clickable; [onClick] receives its index so the caller can
 * rebuild the cumulative path and jump straight there.
 */
object PathBarHelper {

    fun render(
        ctx: Context,
        box: LinearLayout,
        segments: List<String>,
        onClick: (Int) -> Unit
    ) {
        box.removeAllViews()
        segments.forEachIndexed { index, label ->
            if (index > 0) {
                val sep = TextView(ctx).apply {
                    text = "›"
                    textSize = 16f
                    setTextColor(ContextCompat.getColor(ctx, R.color.text_hint))
                    gravity = Gravity.CENTER
                    setPadding(dp(ctx, 4), 0, dp(ctx, 4), 0)
                }
                box.addView(sep)
            }
            val tv = TextView(ctx).apply {
                text = label
                textSize = 14f
                setTextColor(
                    ContextCompat.getColor(
                        ctx,
                        if (index == segments.lastIndex) R.color.text_primary else R.color.grey_600
                    )
                )
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(ctx, 6), dp(ctx, 8), dp(ctx, 6), dp(ctx, 8))
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick(index) }
            }
            box.addView(tv, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            ))
        }
        box.visibility = View.VISIBLE
    }

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()
}
