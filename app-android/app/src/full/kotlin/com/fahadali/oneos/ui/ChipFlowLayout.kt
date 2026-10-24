package com.fahadali.oneos.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import kotlin.math.max

/**
 * Lays its children out in lines that wrap, like CSS flex-wrap (the system
 * cards' name and badge, and their fact chips). Children keep their own size
 * (at most the line's width); [gap] is the space between them and between
 * lines; each child is centred vertically in its line. Mirrors in RTL. The
 * framework has no flow layout, and Material's ChipGroup needs a
 * MaterialComponents theme (it crashes under OneUITheme).
 */
class ChipFlowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {

    /** Space between items and between lines, in px (6dp by default). */
    var gap: Int = (6 * resources.displayMetrics.density).toInt()
        set(value) {
            field = value
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val unbounded = View.MeasureSpec.getMode(widthMeasureSpec) == View.MeasureSpec.UNSPECIFIED
        val maxWidth = (View.MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
        val childWidthSpec = if (unbounded) {
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        } else {
            View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST)
        }
        val childHeightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)

        var x = 0
        var y = 0
        var lineHeight = 0
        var widest = 0
        var any = false
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            c.measure(childWidthSpec, childHeightSpec)
            val w = c.measuredWidth
            if (x > 0 && !unbounded && x + gap + w > maxWidth) {
                y += lineHeight + gap
                x = 0
                lineHeight = 0
            }
            val left = if (x == 0) 0 else x + gap
            x = left + w
            lineHeight = max(lineHeight, c.measuredHeight)
            widest = max(widest, x)
            any = true
        }
        val contentHeight = if (any) y + lineHeight else 0
        setMeasuredDimension(
            View.resolveSize(widest + paddingLeft + paddingRight, widthMeasureSpec),
            View.resolveSize(contentHeight + paddingTop + paddingBottom, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        val maxWidth = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL

        // Lines first (their heights centre the children), then the children.
        val lines = mutableListOf<MutableList<View>>()
        val heights = mutableListOf<Int>()
        var x = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            val w = c.measuredWidth
            if (lines.isEmpty() || (x > 0 && x + gap + w > maxWidth)) {
                lines += mutableListOf<View>()
                heights += 0
                x = 0
            }
            x = (if (x == 0) 0 else x + gap) + w
            lines.last() += c
            heights[heights.lastIndex] = max(heights.last(), c.measuredHeight)
        }

        var top = paddingTop
        for ((n, line) in lines.withIndex()) {
            val lineHeight = heights[n]
            var left = 0
            for (c in line) {
                val w = c.measuredWidth
                val h = c.measuredHeight
                val start = if (left == 0) 0 else left + gap
                val cl = if (rtl) width - paddingRight - start - w else paddingLeft + start
                val ct = top + (lineHeight - h) / 2
                c.layout(cl, ct, cl + w, ct + h)
                left = start + w
            }
            top += lineHeight + gap
        }
    }
}
