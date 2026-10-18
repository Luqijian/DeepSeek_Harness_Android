package io.github.luqijian.harnessandroid.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import io.github.luqijian.harnessandroid.R

/**
 * A small floating control (52dp wide, tall enough for the menu) positioned by
 * absolute screen coordinates. It never covers the whole WebView: only the ball
 * and the menu strip are painted, and touches outside the interactive circles
 * fall through to the WebView.
 *
 * Collapsed: plus ball. Expanded: collapse (X) ball plus three action buttons.
 * Dragging is free in both axes; releasing near a horizontal edge docks the ball
 * as a translucent half-sphere attached to that edge.
 */
class FloatingMenuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Action { WORKSPACE, RELOAD, SETTINGS }

    var onAction: ((Action) -> Unit)? = null

    private val prefs = context.getSharedPreferences("float_ball", Context.MODE_PRIVATE)

    private val ballRadius = dp(26f)
    private val itemRadius = ballRadius
    private val itemGap = dp(12f)
    private val slop = dp(8f)
    private val pad = dp(2f)
    private val columnsWidth = ballRadius * 2 + pad * 2
    private val columnsHeight = ballRadius * 2 + pad * 2 + itemGap +
        3 * (itemRadius * 2) + 2 * itemGap

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xF2111C2E.toInt()
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.2f)
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }
    private val itemFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xF2111C2E.toInt()
    }

    private val icons = listOf(
        ContextCompat.getDrawable(context, R.drawable.ic_workspace),
        ContextCompat.getDrawable(context, R.drawable.ic_refresh),
        ContextCompat.getDrawable(context, R.drawable.ic_settings),
    )

    private var screenX = 0f // ball center X in parent coordinates
    private var screenY = 0f // ball center Y in parent coordinates
    private var dockedSide = 0 // 0 free, -1 left, 1 right
    private var expanded = false
    private var menuAbove = true
    private var positionReady = false

    private var dragging = false
    private var downX = 0f
    private var downY = 0f
    private var grabX = 0f
    private var grabY = 0f
    private var activeItem: Action? = null
    private var activeItemIndex = -1

    private val actions = listOf(Action.WORKSPACE, Action.RELOAD, Action.SETTINGS)

    init {
        setWillNotDraw(false)
        isClickable = false
        setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = columnsWidth.toInt()
        val h = columnsHeight.toInt()
        setMeasuredDimension(w, h)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post { ensurePosition() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post { ensurePosition() }
    }

    private fun ensurePosition() {
        val parent = parent as? ViewGroup ?: return
        if (parent.width == 0 || parent.height == 0) return
        if (positionReady) {
            clampToParent()
            applyPosition()
            return
        }
        val side = prefs.getInt("dock", 0)
        val fx = prefs.getFloat("x", -1f)
        val fy = prefs.getFloat("y", -1f)
        if (fy < 0f) {
            screenX = parent.width - ballRadius - dp(18f)
            screenY = parent.height - ballRadius - dp(96f)
            dockedSide = 0
        } else {
            dockedSide = side
            screenY = (fy * parent.height)
            screenX = when (side) {
                -1 -> 0f
                1 -> parent.width.toFloat()
                else -> fx * parent.width
            }
        }
        positionReady = true
        clampToParent()
        applyPosition()
    }

    private fun clampToParent() {
        val parent = parent as? ViewGroup ?: return
        screenX = screenX.coerceIn(ballRadius, parent.width - ballRadius)
        screenY = screenY.coerceIn(ballRadius, parent.height - ballRadius)
    }

    private fun localBallCenterY(): Float =
        if (menuAbove) height - ballRadius - pad else ballRadius + pad

    private fun localBallCenterX(): Float = width / 2f

    private fun applyPosition() {
        // Recompute above/below so the menu strip can always fit on screen.
        val parent = parent as? ViewGroup ?: return
        menuAbove = screenY > columnsHeight - ballRadius
        if (!menuAbove) {
            // Keep the whole strip inside the top edge when the menu drops below.
            screenY = screenY.coerceIn(ballRadius, columnsHeight - ballRadius)
        } else {
            screenY = screenY.coerceIn(columnsHeight - ballRadius, parent.height - ballRadius)
        }
        translationX = screenX - width / 2f
        translationY = screenY - localBallCenterY()
        invalidate()
    }

    private fun savePosition() {
        val parent = parent as? ViewGroup ?: return
        if (parent.width == 0 || parent.height == 0) return
        prefs.edit()
            .putFloat("x", (screenX / parent.width).coerceIn(0f, 1f))
            .putFloat("y", (screenY / parent.height).coerceIn(0f, 1f))
            .putInt("dock", dockedSide)
            .apply()
    }

    private fun itemCenters(): List<Pair<Float, Float>> {
        val cx = localBallCenterX()
        val cy = localBallCenterY()
        val step = itemRadius * 2 + itemGap
        return actions.indices.map { i ->
            val distance = ballRadius + itemGap + itemRadius + i * step
            val y = if (menuAbove) cy - distance else cy + distance
            cx to y
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (!positionReady) return
        val alpha = if (dockedSide != 0) 115 else 255
        val cx = localBallCenterX()
        val cy = localBallCenterY()

        if (expanded) {
            val centers = itemCenters()
            actions.forEachIndexed { index, _ ->
                val (x, y) = centers[index]
                itemFillPaint.alpha = 245
                canvas.drawCircle(x, y, itemRadius, itemFillPaint)
                icons[index]?.let { drawIcon(canvas, it, x, y, itemRadius * 0.82f) }
            }
        }

        fillPaint.alpha = alpha
        iconPaint.alpha = alpha
        canvas.drawCircle(cx, cy, ballRadius, fillPaint)

        if (expanded) {
            val r = ballRadius * 0.38f
            canvas.drawLine(cx - r, cy - r, cx + r, cy + r, iconPaint)
            canvas.drawLine(cx + r, cy - r, cx - r, cy + r, iconPaint)
        } else {
            val r = ballRadius * 0.42f
            canvas.drawLine(cx - r, cy, cx + r, cy, iconPaint)
            canvas.drawLine(cx, cy - r, cx, cy + r, iconPaint)
        }
    }

    private fun drawIcon(canvas: Canvas, drawable: Drawable, cx: Float, cy: Float, size: Float) {
        val half = size / 2f
        drawable.setTint(Color.WHITE)
        drawable.setBounds(
            (cx - half).toInt(),
            (cy - half).toInt(),
            (cx + half).toInt(),
            (cy + half).toInt(),
        )
        drawable.draw(canvas)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!positionReady) return false
        val localX = event.x
        val localY = event.y
        val ballX = localBallCenterX()
        val ballY = localBallCenterY()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (expanded) {
                    val centers = itemCenters()
                    for (i in actions.indices) {
                        val (x, y) = centers[i]
                        if (dist(localX, localY, x, y) <= itemRadius + slop) {
                            activeItem = actions[i]
                            activeItemIndex = i
                            return true
                        }
                    }
                }
                if (dist(localX, localY, ballX, ballY) <= ballRadius + slop) {
                    downX = event.rawX
                    downY = event.rawY
                    grabX = screenX
                    grabY = screenY
                    dragging = false
                    activeItem = null
                    activeItemIndex = -1
                    return true
                }
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                if (activeItem != null) return true
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (!dragging && (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop)) {
                    dragging = true
                    expanded = false
                    dockedSide = 0
                }
                if (dragging) {
                    val parent = parent as? ViewGroup ?: return true
                    screenX = (grabX + dx).coerceIn(0f, parent.width.toFloat())
                    screenY = (grabY + dy).coerceIn(ballRadius, parent.height - ballRadius)
                    applyPosition()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val action = activeItem
                if (action != null) {
                    val centers = itemCenters()
                    val (x, y) = centers[activeItemIndex]
                    if (dist(localX, localY, x, y) <= itemRadius + slop) {
                        expanded = false
                        invalidate()
                        onAction?.invoke(action)
                    }
                    activeItem = null
                    activeItemIndex = -1
                    return true
                }
                if (dragging) {
                    dragging = false
                    snapToEdge()
                    savePosition()
                } else if (dockedSide != 0) {
                    undock()
                    savePosition()
                } else {
                    expanded = !expanded
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                activeItem = null
                activeItemIndex = -1
                dragging = false
                return true
            }
        }
        return false
    }

    private fun snapToEdge() {
        val parent = parent as? ViewGroup ?: return
        val edge = dp(56f)
        dockedSide = when {
            screenX <= edge -> -1
            screenX >= parent.width - edge -> 1
            else -> 0
        }
        if (dockedSide != 0) {
            screenX = if (dockedSide < 0) 0f else parent.width.toFloat()
        }
        applyPosition()
    }

    private fun undock() {
        val parent = parent as? ViewGroup ?: return
        dockedSide = 0
        screenX = if (screenX < parent.width / 2f) {
            ballRadius + dp(10f)
        } else {
            parent.width - ballRadius - dp(10f)
        }
        applyPosition()
    }

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
