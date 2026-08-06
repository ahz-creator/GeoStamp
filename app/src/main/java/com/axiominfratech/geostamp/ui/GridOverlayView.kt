package com.axiominfratech.geostamp.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Draws a rule-of-thirds grid (2 vertical + 2 horizontal lines) directly on canvas.
 * Replaces the broken bg_camera_grid.xml drawable which cannot use % values in AAPT.
 */
class GridOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = Color.argb(80, 255, 255, 255)   // semi-transparent white
        strokeWidth = 1.2f * context.resources.displayMetrics.density
        style       = Paint.Style.STROKE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // Vertical lines at 1/3 and 2/3
        canvas.drawLine(w / 3f, 0f, w / 3f, h, paint)
        canvas.drawLine(2f * w / 3f, 0f, 2f * w / 3f, h, paint)

        // Horizontal lines at 1/3 and 2/3
        canvas.drawLine(0f, h / 3f, w, h / 3f, paint)
        canvas.drawLine(0f, 2f * h / 3f, w, 2f * h / 3f, paint)
    }
}
