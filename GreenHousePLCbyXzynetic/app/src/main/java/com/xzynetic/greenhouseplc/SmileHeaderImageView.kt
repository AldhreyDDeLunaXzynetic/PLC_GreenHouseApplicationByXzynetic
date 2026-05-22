package com.xzynetic.greenhouseplc

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView

class SmileHeaderImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ImageView(context, attrs, defStyleAttr) {

    private val smilePath = Path()

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(244, 247, 245)
        style = Paint.Style.STROKE
        strokeWidth = dp(5f)
    }

    private fun dp(value: Float): Float {
        return value * resources.displayMetrics.density
    }

    init {
        scaleType = ScaleType.CENTER_CROP
        setWillNotDraw(false)
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        buildSmilePath(w.toFloat(), h.toFloat())
    }

    private fun buildSmilePath(w: Float, h: Float) {
        smilePath.reset()

        val topRadius = dp(28f)
        val sideY = h - dp(88f)
        val centerY = h - dp(3f)

        smilePath.moveTo(topRadius, 0f)

        smilePath.lineTo(w - topRadius, 0f)
        smilePath.quadTo(w, 0f, w, topRadius)

        smilePath.lineTo(w, sideY)

        smilePath.cubicTo(
            w * 0.86f, sideY,
            w * 0.76f, centerY,
            w * 0.50f, centerY
        )

        smilePath.cubicTo(
            w * 0.24f, centerY,
            w * 0.14f, sideY,
            0f, sideY
        )

        smilePath.lineTo(0f, topRadius)
        smilePath.quadTo(0f, 0f, topRadius, 0f)

        smilePath.close()
    }

    override fun onDraw(canvas: Canvas) {
        val save = canvas.save()
        canvas.clipPath(smilePath)
        super.onDraw(canvas)
        canvas.restoreToCount(save)
        canvas.drawPath(smilePath, borderPaint)
    }
}