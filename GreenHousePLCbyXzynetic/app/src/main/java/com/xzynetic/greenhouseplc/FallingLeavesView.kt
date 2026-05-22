package com.xzynetic.greenhouseplc

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.sin
import kotlin.random.Random

class FallingLeavesView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private data class Leaf(
        var baseX: Float,
        var y: Float,
        var size: Float,
        var speed: Float,
        var angle: Float,
        var spin: Float,
        var alpha: Float,
        var phase: Float,
        var swayAmp: Float,
        var swaySpeed: Float
    )

    private val random = Random(System.currentTimeMillis())
    private val leaves = mutableListOf<Leaf>()
    private val leafPath = Path()

    private val leafPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val stemPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.1f)
        strokeCap = Paint.Cap.ROUND
    }

    private var lastFrameMs = 0L
    private var running = false

    private val frameRunnable = object : Runnable {
        override fun run() {
            updateLeaves()
            invalidate()

            if (running) {
                postOnAnimation(this)
            }
        }
    }

    init {
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        alpha = 0.55f
    }

    private fun dp(value: Float): Float {
        return value * resources.displayMetrics.density
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        return false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        lastFrameMs = SystemClock.uptimeMillis()
        postOnAnimation(frameRunnable)
    }

    override fun onDetachedFromWindow() {
        running = false
        removeCallbacks(frameRunnable)
        leaves.clear()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        leaves.clear()

        if (w <= 0 || h <= 0) return

        val leafCount = 26

        repeat(leafCount) {
            leaves.add(
                Leaf(
                    baseX = random.nextFloat() * w,
                    y = random.nextFloat() * h,
                    size = dp(randomRange(8f, 18f)),
                    speed = dp(randomRange(16f, 42f)),
                    angle = randomRange(0f, 360f),
                    spin = randomRange(-35f, 35f),
                    alpha = randomRange(0.18f, 0.52f),
                    phase = randomRange(0f, 6.28f),
                    swayAmp = dp(randomRange(12f, 38f)),
                    swaySpeed = randomRange(0.7f, 1.8f)
                )
            )
        }
    }

    private fun randomRange(min: Float, max: Float): Float {
        return min + random.nextFloat() * (max - min)
    }

    private fun updateLeaves() {
        val now = SystemClock.uptimeMillis()
        val delta = ((now - lastFrameMs) / 1000f).coerceAtMost(0.033f)
        lastFrameMs = now

        if (width <= 0 || height <= 0) return

        leaves.forEach { leaf ->
            leaf.y += leaf.speed * delta
            leaf.angle += leaf.spin * delta

            if (leaf.y > height + leaf.size * 3f) {
                resetLeaf(leaf)
            }
        }
    }

    private fun resetLeaf(leaf: Leaf) {
        leaf.baseX = random.nextFloat() * width
        leaf.y = -randomRange(20f, 160f)
        leaf.size = dp(randomRange(8f, 18f))
        leaf.speed = dp(randomRange(16f, 42f))
        leaf.angle = randomRange(0f, 360f)
        leaf.spin = randomRange(-35f, 35f)
        leaf.alpha = randomRange(0.18f, 0.52f)
        leaf.phase = randomRange(0f, 6.28f)
        leaf.swayAmp = dp(randomRange(12f, 38f))
        leaf.swaySpeed = randomRange(0.7f, 1.8f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val time = SystemClock.uptimeMillis() / 1000f

        leaves.forEach { leaf ->
            val windX = sin(time * leaf.swaySpeed + leaf.phase) * leaf.swayAmp
            val x = leaf.baseX + windX

            drawLeaf(canvas, leaf, x, leaf.y)
        }
    }

    private fun drawLeaf(canvas: Canvas, leaf: Leaf, x: Float, y: Float) {
        val s = leaf.size

        leafPaint.color = Color.argb(
            (leaf.alpha * 255).toInt().coerceIn(0, 255),
            115,
            220,
            133
        )

        stemPaint.color = Color.argb(
            (leaf.alpha * 230).toInt().coerceIn(0, 255),
            180,
            245,
            160
        )

        leafPath.reset()

        leafPath.moveTo(0f, -s * 0.62f)
        leafPath.cubicTo(
            s * 0.72f, -s * 0.52f,
            s * 0.72f, s * 0.42f,
            0f, s * 0.68f
        )
        leafPath.cubicTo(
            -s * 0.72f, s * 0.42f,
            -s * 0.72f, -s * 0.52f,
            0f, -s * 0.62f
        )
        leafPath.close()

        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(leaf.angle)

        canvas.drawPath(leafPath, leafPaint)

        canvas.drawLine(
            0f,
            -s * 0.45f,
            0f,
            s * 0.45f,
            stemPaint
        )

        canvas.restore()
    }
}