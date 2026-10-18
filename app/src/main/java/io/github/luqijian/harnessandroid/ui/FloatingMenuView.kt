package io.github.luqijian.harnessandroid.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import io.github.luqijian.harnessandroid.R

/**
 * Draggable floating ball with an expandable action menu.
 * Releasing the ball near a horizontal edge docks it to that edge (semi-hidden);
 * tapping a docked ball undocks it. Tapping (without dragging) toggles the menu.
 */
class FloatingMenuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    enum class Action { WORKSPACE, RELOAD, SETTINGS }

    var onAction: ((Action) -> Unit)? = null

    private val ballSize = dp(52)
    private val itemSize = dp(44)
    private val ball: ImageView
    private val statusDot: ImageView
    private val menuColumn: LinearLayout
    private val items = mutableMapOf<Action, ImageView>()
    private var expanded = false

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragged = false
    private var dockedSide = 0 // 0 = free, -1 = left, 1 = right

    init {
        clipChildren = false
        clipToPadding = false

        menuColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = GONE
        }
        listOf(
            Action.WORKSPACE to R.drawable.ic_workspace,
            Action.RELOAD to R.drawable.ic_refresh,
            Action.SETTINGS to R.drawable.ic_settings,
        ).forEach { (action, icon) ->
            val view = ImageView(context).apply {
                setImageResource(icon)
                background = circle(0xE6111C2E.toInt())
                imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                val pad = dp(10)
                setPadding(pad, pad, pad, pad)
                layoutParams = LinearLayout.LayoutParams(itemSize, itemSize).apply {
                    topMargin = if (menuColumn.childCount == 0) 0 else dp(10)
                }
                setOnClickListener {
                    collapse()
                    onAction?.invoke(action)
                }
            }
            items[action] = view
            menuColumn.addView(view)
        }
        addView(
            menuColumn,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL),
        )

        statusDot = ImageView(context).apply {
            background = circle(0xFF34D399.toInt())
            layoutParams = LayoutParams(dp(13), dp(13), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(2)
                rightMargin = dp(2)
            }
        }

        ball = ImageView(context).apply {
            setImageResource(R.drawable.ic_notification)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xE6111C2E.toInt())
                setStroke(dp(1), 0xFF14B8A6.toInt())
            }
            val pad = dp(14)
            setPadding(pad, pad, pad, pad)
        }

        addView(statusDot)
        addView(
            ball,
            LayoutParams(ballSize, ballSize, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL),
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            downX = event.rawX
            downY = event.rawY
            lastX = downX
            lastY = downY
            dragged = false
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                lastX = downX
                lastY = downY
                dragged = false
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - lastX
                val dy = event.rawY - lastY
                lastX = event.rawX
                lastY = event.rawY
                if (!dragged && (kotlin.math.abs(event.rawX - downX) > dp(6) ||
                        kotlin.math.abs(event.rawY - downY) > dp(6))
                ) {
                    dragged = true
                    collapse()
                }
                if (dragged) {
                    moveBy(dx, dy)
                    dockedSide = 0
                    ball.alpha = 1f
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (dragged) {
                    snapToEdge()
                } else {
                    if (dockedSide != 0) {
                        undock()
                    } else {
                        toggle()
                    }
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun moveBy(dx: Float, dy: Float) {
        val lp = layoutParams as MarginLayoutParams
        val parentWidth = (parent as? android.view.ViewGroup)?.width ?: return
        val parentHeight = (parent as? android.view.ViewGroup)?.height ?: return
        lp.leftMargin = (lp.leftMargin + dx.toInt()).coerceIn(0, parentWidth - width)
        lp.bottomMargin = (lp.bottomMargin - dy.toInt()).coerceIn(dp(24), parentHeight - height)
        layoutParams = lp
    }

    private fun snapToEdge() {
        val parent = parent as? android.view.ViewGroup ?: return
        val lp = layoutParams as MarginLayoutParams
        val centerX = lp.leftMargin + width / 2
        dockedSide = if (centerX < parent.width / 2) -1 else 1
        lp.leftMargin = if (dockedSide < 0) -width / 2 else parent.width - width / 2
        ball.alpha = 0.72f
        layoutParams = lp
    }

    private fun undock() {
        val parent = parent as? android.view.ViewGroup ?: return
        val lp = layoutParams as MarginLayoutParams
        lp.leftMargin = if (dockedSide < 0) dp(12) else parent.width - width - dp(12)
        dockedSide = 0
        ball.alpha = 1f
        layoutParams = lp
    }

    private fun toggle() {
        if (expanded) collapse() else expand()
    }

    private fun expand() {
        expanded = true
        menuColumn.visibility = VISIBLE
        menuColumn.animate().alpha(1f).setDuration(120).start()
    }

    private fun collapse() {
        expanded = false
        menuColumn.visibility = GONE
    }

    fun setStatus(@androidx.annotation.ColorRes color: Int) {
        statusDot.background = circle(
            androidx.core.content.ContextCompat.getColor(context, color),
        )
    }

    private fun circle(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
