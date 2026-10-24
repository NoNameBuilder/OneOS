package com.fahadali.oneos.guest

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout

/**
 * A LinearLayout no wider than `guest_content_max_width` (520dp,
 * GuestReturn.dc.html): one centred column on the inner screen or a tablet,
 * the full width on the cover screen. The parent centres it.
 */
class MaxWidthLinearLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val maxWidth = resources.getDimensionPixelSize(R.dimen.guest_content_max_width)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = MeasureSpec.getMode(widthMeasureSpec)
        val spec = if (mode != MeasureSpec.UNSPECIFIED && MeasureSpec.getSize(widthMeasureSpec) > maxWidth) {
            MeasureSpec.makeMeasureSpec(maxWidth, mode)
        } else {
            widthMeasureSpec
        }
        super.onMeasure(spec, heightMeasureSpec)
    }
}
