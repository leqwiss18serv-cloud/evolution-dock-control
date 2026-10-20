package cloud.leqwiss.dockcontrol

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max

class DockPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var isInteractive: Boolean = true

    var widthPercent: Float = DEFAULT_WIDTH_PERCENT
    var heightDp: Int = DEFAULT_HEIGHT_DP
    var leftMarginDp: Float = DEFAULT_HORIZONTAL_MARGIN.toFloat()
    var rightMarginDp: Float = DEFAULT_HORIZONTAL_MARGIN.toFloat()
    var topMarginDp: Float = DEFAULT_TOP_MARGIN.toFloat()
    var bottomMarginDp: Float = DEFAULT_BOTTOM_MARGIN.toFloat()
    var cornerRadiusDp: Float = DEFAULT_CORNER_RADIUS.toFloat()
    var dockColor: Int = Color.argb((255 * DEFAULT_OPACITY) / 100, 28, 28, 30)
    var dockGravity: String = GRAVITY_BOTTOM
    var glassStyle: String = GLASS_STYLE_LIQUID

    var onDockChanged: ((left: Int, right: Int, top: Int, bottom: Int, height: Int) -> Unit)? = null

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF101014L.toInt()
    }
    private val dockPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF2F2F7.toInt()
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF
    }
    private val dockRect = RectF()

    private var touchMode = TouchMode.NONE
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRoundRect(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            28f.dpToPx(),
            28f.dpToPx(),
            backgroundPaint
        )

        computeDockRect()
        val corner = cornerRadiusDp.dpToPx()
        when (glassStyle) {
            GLASS_STYLE_SOLID -> {
                dockPaint.shader = null
                dockPaint.color = dockColor
                canvas.drawRoundRect(dockRect, corner, corner, dockPaint)
            }

            GLASS_STYLE_MATTE -> {
                dockPaint.shader = LinearGradient(
                    dockRect.left,
                    dockRect.top,
                    dockRect.left,
                    dockRect.bottom,
                    intArrayOf(scaleAlpha(dockColor, 1f), scaleAlpha(dockColor, 0.94f)),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRoundRect(dockRect, corner, corner, dockPaint)
            }

            else -> {
                dockPaint.shader = LinearGradient(
                    dockRect.left,
                    dockRect.top,
                    dockRect.right,
                    dockRect.bottom,
                    intArrayOf(scaleAlpha(dockColor, 0.62f), scaleAlpha(dockColor, 0.86f)),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRoundRect(dockRect, corner, corner, dockPaint)
                dockPaint.shader = LinearGradient(
                    dockRect.left,
                    dockRect.top,
                    dockRect.right,
                    dockRect.bottom,
                    intArrayOf(0x59FFFFFF, 0x00FFFFFF, 0x21FFFFFF),
                    floatArrayOf(0f, 0.58f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRoundRect(dockRect, corner, corner, dockPaint)
            }
        }

        borderPaint.strokeWidth = if (glassStyle == GLASS_STYLE_LIQUID) {
            1.25f.dpToPx()
        } else {
            0.9f.dpToPx()
        }
        borderPaint.color = if (glassStyle == GLASS_STYLE_LIQUID) 0x5CFFFFFF else 0x26FFFFFF
        canvas.drawRoundRect(dockRect, corner, corner, borderPaint)

        val iconRadius = 13f.dpToPx()
        val iconCount = 4
        val spacing = dockRect.width() / (iconCount + 1)
        for (index in 1..iconCount) {
            val centerX = dockRect.left + spacing * index
            val centerY = dockRect.centerY()
            canvas.drawCircle(centerX, centerY, iconRadius, iconPaint)
        }

        if (isInteractive) {
            canvas.drawCircle(dockRect.centerX(), dockRect.top, 7f.dpToPx(), handlePaint)
            canvas.drawCircle(dockRect.centerX(), dockRect.bottom, 7f.dpToPx(), handlePaint)
            canvas.drawCircle(dockRect.left, dockRect.centerY(), 7f.dpToPx(), handlePaint)
            canvas.drawCircle(dockRect.right, dockRect.centerY(), 7f.dpToPx(), handlePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isInteractive) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                computeDockRect()
                touchMode = touchModeFor(event.x, event.y)
                if (touchMode == TouchMode.NONE) return false
                lastTouchX = event.x
                lastTouchY = event.y
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (touchMode == TouchMode.NONE) return false
                val deltaX = event.x - lastTouchX
                val deltaY = event.y - lastTouchY
                val density = resources.displayMetrics.density
                val deltaXDp = deltaX / density
                val deltaYDp = deltaY / density

                when (touchMode) {
                    TouchMode.MOVE -> {
                        leftMarginDp = leftMarginDp.coerceIn(0f, MAX_MARGIN_DP) + deltaXDp
                        rightMarginDp = rightMarginDp.coerceIn(0f, MAX_MARGIN_DP) - deltaXDp
                        if (dockGravity == GRAVITY_BOTTOM) {
                            bottomMarginDp = (bottomMarginDp - deltaYDp).coerceIn(0f, MAX_MARGIN_DP)
                        } else {
                            topMarginDp = (topMarginDp + deltaYDp).coerceIn(0f, MAX_MARGIN_DP)
                        }
                    }

                    TouchMode.RESIZE_LEFT -> {
                        leftMarginDp = (leftMarginDp + deltaXDp).coerceIn(0f, MAX_MARGIN_DP)
                    }

                    TouchMode.RESIZE_RIGHT -> {
                        rightMarginDp = (rightMarginDp - deltaXDp).coerceIn(0f, MAX_MARGIN_DP)
                    }

                    TouchMode.RESIZE_TOP -> {
                        val currentHeight = if (heightDp > 0) {
                            heightDp
                        } else {
                            (dockRect.height() / density).toInt()
                        }
                        heightDp = (currentHeight - deltaYDp).toInt().coerceIn(MIN_HEIGHT_DP, MAX_HEIGHT_DP)
                    }

                    TouchMode.RESIZE_BOTTOM -> {
                        val currentHeight = if (heightDp > 0) {
                            heightDp
                        } else {
                            (dockRect.height() / density).toInt()
                        }
                        heightDp = (currentHeight + deltaYDp).toInt().coerceIn(MIN_HEIGHT_DP, MAX_HEIGHT_DP)
                    }

                    TouchMode.NONE -> return false
                }

                leftMarginDp = leftMarginDp.coerceIn(0f, MAX_MARGIN_DP)
                rightMarginDp = rightMarginDp.coerceIn(0f, MAX_MARGIN_DP)
                topMarginDp = topMarginDp.coerceIn(0f, MAX_MARGIN_DP)
                bottomMarginDp = bottomMarginDp.coerceIn(0f, MAX_MARGIN_DP)
                lastTouchX = event.x
                lastTouchY = event.y
                invalidate()
                onDockChanged?.invoke(
                    leftMarginDp.toInt(),
                    rightMarginDp.toInt(),
                    topMarginDp.toInt(),
                    bottomMarginDp.toInt(),
                    heightDp
                )
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touchMode = TouchMode.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun touchModeFor(x: Float, y: Float): TouchMode {
        val edgeSize = 26f.dpToPx()
        val nearLeft = abs(x - dockRect.left) <= edgeSize
        val nearRight = abs(x - dockRect.right) <= edgeSize
        val nearTop = abs(y - dockRect.top) <= edgeSize
        val nearBottom = abs(y - dockRect.bottom) <= edgeSize

        return when {
            nearLeft -> TouchMode.RESIZE_LEFT
            nearRight -> TouchMode.RESIZE_RIGHT
            nearTop -> TouchMode.RESIZE_TOP
            nearBottom -> TouchMode.RESIZE_BOTTOM
            dockRect.contains(x, y) -> TouchMode.MOVE
            else -> TouchMode.NONE
        }
    }

    private fun computeDockRect() {
        val density = resources.displayMetrics.density
        val horizontalPadding = 18f.dpToPx()
        val verticalPadding = 18f.dpToPx()
        val availableWidth = max(0f, width - horizontalPadding * 2)
        val widthInset = availableWidth * (100f - widthPercent) / 200f
        val left = horizontalPadding + widthInset + leftMarginDp * density
        val right = width - horizontalPadding - widthInset - rightMarginDp * density
        val dockWidth = max(70f.dpToPx(), right - left)

        val dockHeight = if (heightDp > 0) {
            heightDp * density
        } else {
            96f.dpToPx()
        }

        val top = if (dockGravity == GRAVITY_TOP) {
            verticalPadding + topMarginDp * density
        } else {
            height - verticalPadding - bottomMarginDp * density - dockHeight
        }
        dockRect.set(left, top, left + dockWidth, top + dockHeight)
    }

    private fun Float.dpToPx(): Float = this * resources.displayMetrics.density

    private fun scaleAlpha(color: Int, factor: Float): Int {
        return Color.argb(
            (Color.alpha(color) * factor).toInt().coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
    }

    private enum class TouchMode {
        NONE,
        MOVE,
        RESIZE_LEFT,
        RESIZE_RIGHT,
        RESIZE_TOP,
        RESIZE_BOTTOM
    }

    companion object {
        const val GRAVITY_BOTTOM = "bottom"
        const val GRAVITY_TOP = "top"
        const val GLASS_STYLE_LIQUID = "liquid"
        const val GLASS_STYLE_MATTE = "matte"
        const val GLASS_STYLE_SOLID = "solid"
        const val DEFAULT_WIDTH_PERCENT = 78f
        const val DEFAULT_HEIGHT_DP = 0
        const val DEFAULT_CORNER_RADIUS = 34f
        const val DEFAULT_OPACITY = 82
        const val DEFAULT_TOP_MARGIN = 0
        const val DEFAULT_BOTTOM_MARGIN = 16
        const val DEFAULT_HORIZONTAL_MARGIN = 14
        const val MAX_MARGIN_DP = 120f
        const val MIN_HEIGHT_DP = 60
        const val MAX_HEIGHT_DP = 220
    }
}
