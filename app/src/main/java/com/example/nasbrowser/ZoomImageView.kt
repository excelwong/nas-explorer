package com.example.nasbrowser

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import android.view.ViewParent
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * ImageView with the gesture set people expect from an Android gallery:
 *
 *  - pinch with two fingers to zoom (1x = fitCenter, up to 4x), centred on the finger midpoint;
 *  - when zoomed in, drag with one finger to pan, with the image clamped to the viewport edges;
 *  - when NOT zoomed in, a horizontal drag is left to ViewPager2 so it still changes page;
 *  - double tap zooms to 2.5x (centred on the tap), double tap again returns to fitCenter;
 *  - single tap is reported to [onSingleTap] so the viewer can hide/show its bars.
 *
 * The transform is split into two matrices: a fit-to-view [base] matrix (recomputed whenever the
 * drawable or the view size changes) and a [supp] matrix holding the user's zoom/pan in VIEW
 * coordinates. The draw matrix is `supp * base` (preConcat), so touch coordinates can be used
 * directly as scale/pan centres and the displayed rect is already in view coordinates.
 */
class ZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    companion object {
        private const val MIN_SCALE = 1f
        private const val MAX_SCALE = 4f
        private const val DOUBLE_TAP_SCALE = 2.5f
        private const val ANIM_MS = 220L
    }

    /** Called on a confirmed single tap (a double tap does NOT fire it). */
    var onSingleTap: (() -> Unit)? = null

    private val supp = Matrix()
    private val base = Matrix()
    private val drawMatrix = Matrix()
    private val displayRect = RectF()
    private val values = FloatArray(9)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var scaleAnimator: ValueAnimator? = null
    private var lastX = 0f
    private var lastY = 0f
    private var panning = false

    init {
        // The XML scaleType is irrelevant: this view always draws through an explicit matrix.
        super.setScaleType(ScaleType.MATRIX)
    }

    override fun setScaleType(scaleType: ScaleType) {
        super.setScaleType(ScaleType.MATRIX)
    }

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.OnScaleGestureListener {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                cancelAnimation()
                disallowParentIntercept(true)
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val cur = currentScale()
                if (cur <= 0f) return true
                val wanted = (cur * detector.scaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)
                val factor = wanted / cur
                if (factor != 1f) {
                    supp.postScale(factor, factor, detector.focusX, detector.focusY)
                    applyMatrix()
                }
                correctBounds()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                // Pinching back below 1x snaps cleanly to fitCenter.
                if (currentScale() <= MIN_SCALE + 0.01f) resetZoom()
            }
        }
    )

    private val tapDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onSingleTap?.invoke()
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val target = if (currentScale() > MIN_SCALE + 0.05f) MIN_SCALE else DOUBLE_TAP_SCALE
                animateScaleTo(target, e.x, e.y)
                return true
            }
        }
    )

    // ---- public API ---------------------------------------------------------------------

    /** Replace the drawable and start at fitCenter (called when a ViewHolder is (re)bound). */
    fun setZoomDrawable(d: Drawable?) {
        cancelAnimation()
        supp.reset()
        setImageDrawable(d)
        applyMatrix()
    }

    /** Snap back to fitCenter; also used when a page is reused or the view is resized. */
    fun resetZoom() {
        cancelAnimation()
        supp.reset()
        applyMatrix()
    }

    // ---- matrix maths -------------------------------------------------------------------

    private fun drawableSize(d: Drawable): Pair<Float, Float> {
        val vw = width.toFloat()
        val vh = height.toFloat()
        val dw = if (d.intrinsicWidth > 0) d.intrinsicWidth.toFloat() else vw
        val dh = if (d.intrinsicHeight > 0) d.intrinsicHeight.toFloat() else vh
        return dw to dh
    }

    /** Recompute the fit-to-view base matrix and the final matrix actually used for drawing. */
    private fun buildMatrix(): Boolean {
        val d = drawable ?: return false
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) return false
        val (dw, dh) = drawableSize(d)
        if (dw <= 0f || dh <= 0f) return false
        val s = min(vw / dw, vh / dh)
        base.setScale(s, s)
        base.postTranslate((vw - dw * s) / 2f, (vh - dh * s) / 2f)
        drawMatrix.set(base)
        drawMatrix.preConcat(supp)
        return true
    }

    private fun applyMatrix() {
        if (!buildMatrix()) return
        imageMatrix = drawMatrix
        invalidate()
    }

    private fun currentRect(): RectF? {
        if (!buildMatrix()) return null
        val d = drawable ?: return null
        val (dw, dh) = drawableSize(d)
        displayRect.set(0f, 0f, dw, dh)
        drawMatrix.mapRect(displayRect)
        return displayRect
    }

    private fun currentScale(): Float {
        supp.getValues(values)
        val sx = values[Matrix.MSCALE_X]
        val sk = values[Matrix.MSKEW_Y]
        return sqrt(sx * sx + sk * sk)
    }

    /** Keep the picture against the viewport edges instead of letting it float into black space. */
    private fun correctBounds() {
        val r = currentRect() ?: return
        val vw = width.toFloat()
        val vh = height.toFloat()
        var dx = 0f
        var dy = 0f
        when {
            r.width() <= vw -> dx = (vw - r.width()) / 2f - r.left
            r.left > 0f -> dx = -r.left
            r.right < vw -> dx = vw - r.right
        }
        when {
            r.height() <= vh -> dy = (vh - r.height()) / 2f - r.top
            r.top > 0f -> dy = -r.top
            r.bottom < vh -> dy = vh - r.bottom
        }
        if (dx != 0f || dy != 0f) {
            supp.postTranslate(dx, dy)
            applyMatrix()
        }
    }

    private fun animateScaleTo(target: Float, focusX: Float, focusY: Float) {
        cancelAnimation()
        val from = currentScale()
        var last = from
        scaleAnimator = ValueAnimator.ofFloat(from, target).apply {
            duration = ANIM_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val v = anim.animatedValue as Float
                if (last > 0f) {
                    supp.postScale(v / last, v / last, focusX, focusY)
                    applyMatrix()
                    correctBounds()
                }
                last = v
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (target <= MIN_SCALE + 0.01f) resetZoom()
                    scaleAnimator = null
                }
            })
            start()
        }
    }

    private fun cancelAnimation() {
        val a = scaleAnimator ?: return
        scaleAnimator = null
        a.removeAllListeners()
        a.cancel()
    }

    // ---- touch handling -----------------------------------------------------------------

    /** Walk the parent chain (ViewPager2 keeps an inner RecyclerView) and toggle interception. */
    private fun disallowParentIntercept(disallow: Boolean) {
        var p: ViewParent? = parent
        while (p != null) {
            p.requestDisallowInterceptTouchEvent(disallow)
            p = p.parent
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Nothing loaded yet: let the pager handle the swipe normally.
        if (drawable == null) return false

        scaleDetector.onTouchEvent(event)
        tapDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelAnimation()
                lastX = event.x
                lastY = event.y
                panning = false
                if (currentScale() > MIN_SCALE + 0.01f) disallowParentIntercept(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount > 1) disallowParentIntercept(true)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // When one finger of a pinch lifts, rebase on the finger that stayed down so
                // the picture doesn't jump on the next MOVE.
                val keep = if (event.actionIndex == 0) 1 else 0
                if (keep < event.pointerCount) {
                    lastX = event.getX(keep)
                    lastY = event.getY(keep)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && event.pointerCount == 1) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    if (currentScale() > MIN_SCALE + 0.01f) {
                        if (!panning && abs(dx) + abs(dy) > touchSlop) {
                            panning = true
                            disallowParentIntercept(true)
                        }
                        if (panning) {
                            supp.postTranslate(dx, dy)
                            applyMatrix()
                            correctBounds()
                        }
                    }
                }
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                panning = false
                disallowParentIntercept(false)
                if (event.actionMasked == MotionEvent.ACTION_UP &&
                    currentScale() <= MIN_SCALE + 0.01f
                ) resetZoom()
            }
        }
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post { applyMatrix() }
    }

    override fun onDetachedFromWindow() {
        cancelAnimation()
        super.onDetachedFromWindow()
    }
}
