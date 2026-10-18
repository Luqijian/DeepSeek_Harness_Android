package io.github.luqijian.harnessandroid.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import io.github.luqijian.harnessandroid.R

/**
 * Draggable floating ball with an expandable action menu.
 * The ball shows a plus icon; when the menu is open it becomes a collapse (X) button.
 * Releasing the ball near a horizontal edge docks it: the ball shrinks to a
 * translucent half-sphere attached to the edge. Tapping a docked ball undocks it.
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
    private val menuColumn: LinearLayout
    private var expanded = false

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragged = false
    private var dockedSide = 0 // 0 = free, -1 = left, 1 = right
    private var normalized = false

    init {
        isClickable = true
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
            menuColumn.addView(view)
        }
        addView(
            menuColumn,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL),
        )

        ball = ImageView(context).apply {
            setImageResource(R.drawable.ic_plus)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xE6111C2E.toInt())
                setStroke(dp(1), 0xFF14B8A6.toInt())
            }
            val pad = dp(14)
            setPadding(pad, pad, pad, pad)
        }
        addView(
            ball,
            LayoutParams(ballSize, ballSize, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL),
        )
    }

    /** Replace layout_gravity positioning with explicit margins so dragging is stable. */
    private fun normalizePosition() {
        if (normalized) return
        val parent = parent as? ViewGroup ?: return
        val lp = layoutParams as MarginLayoutParams
        if (lp is LayoutParams) lp.gravity = Gravity.NO_GRAVITY
        if (lp.leftMargin <= 0 && lp.rightMargin > 0 && lp.leftMargin >= 0) {
            lp.leftMargin = parent.width - width - lp.rightMargin
        }
        lp.rightMargin = 0
        normalized = true
        layoutParams = lp
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                normalizePosition()
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
        val parent = parent as? ViewGroup ?: return
        val lp = layoutParams as MarginLayoutParams
        val half = width / 2
        lp.leftMargin = (lp.leftMargin + dx.toInt()).coerceIn(-half, parent.width - half)
        lp.bottomMargin = (lp.bottomMargin - dy.toInt()).coerceIn(dp(24), parent.height - height)
        dockedSide = 0
        ball.alpha = 1f
        layoutParams = lp
    }

    private fun snapToEdge() {
        val parent = parent as? ViewGroup ?: return
        val lp = layoutParams as MarginLayoutParams
        val centerX = lp.leftMargin + width / 2
        dockedSide = if (centerX < parent.width / 2) -1 else 1
        lp.leftMargin = if (dockedSide < 0) -(width - halfVisible()) else parent.width - halfVisible()
        ball.alpha = 0.45f
        layoutParams = lp
    }

    /** Pixels of the ball that stay visible when docked (a bit less than half). */
    private fun halfVisible(): Int = width / 2 - dp(4)

    private fun undock() {
        val parent = parent as? ViewGroup ?: return
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
        ball.setImageResource(R.drawable.ic_close)
        menuColumn.visibility = VISIBLE
    }

    private fun collapse() {
        if (!expanded) return
        expanded = false
        ball.setImageResource(R.drawable.ic_plus)
        menuColumn.visibility = GONE
    }

    private fun circle(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
