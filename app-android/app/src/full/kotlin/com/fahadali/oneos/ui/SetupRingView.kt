package com.fahadali.oneos.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.annotation.ColorInt
import com.fahadali.oneos.R
import kotlin.math.min

/**
 * The determinate ring of Setting up (SettingUp.dc.html): a 12dp track and a
 * round-capped arc from the top, clockwise. Drawn here rather than with
 * SeslProgressBar's circle mode, whose size is capped by its style
 * (sesl_progress_circle_size) and whose stroke is derived from that size.
 * Decorative for TalkBack: the box around it carries the text.
 */
class SetupRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val stroke = 12f * resources.displayMetrics.density

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        color = context.getColor(R.color.oneos_ring_track)
    }

    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
        color = context.getColor(R.color.oneos_primary_text)
    }

    private val box = RectF()

    /** What is drawn now, 0 to 100 (animates towards the last [setPercent]). */
    private var shown = 0f
    private var target = 0f
    private var animator: ValueAnimator? = null

    fun setArcColor(@ColorInt color: Int) {
        if (arc.color == color) return
        arc.color = color
        invalidate()
    }

    /** Moves the arc to [percent] (0 to 100); animated unless animations are off or the view is not laid out yet. */
    fun setPercent(percent: Int) {
        val to = percent.coerceIn(0, 100).toFloat()
        if (to == target) return
        target = to
        animator?.cancel()
        if (!isLaidOut || !ValueAnimator.areAnimatorsEnabled()) {
            shown = to
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(shown, to).apply {
            duration = 400L
            addUpdateListener {
                shown = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (animator === animation) animator = null
                }
            })
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        shown = target
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = (width - paddingLeft - paddingRight).toFloat()
        val h = (height - paddingTop - paddingBottom).toFloat()
        val size = min(w, h) - stroke
        if (size <= 0f) return
        val cx = paddingLeft + w / 2f
        val cy = paddingTop + h / 2f
        box.set(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)
        canvas.drawOval(box, track)
        if (shown > 0f) canvas.drawArc(box, -90f, 360f * shown / 100f, false, arc)
    }
}
