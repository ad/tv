package dev.ad.tv

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/** Where the cursor's clicks and scrolls go: this app's window, or the whole system via accessibility. */
interface CursorHost {
    fun hover(x: Float, y: Float) {}
    fun click(x: Float, y: Float)
    fun longClick(x: Float, y: Float)
    /** dx/dy: -1, 0 or 1 — the direction the cursor is pushing past the screen edge. */
    fun scroll(x: Float, y: Float, dx: Int, dy: Int)
}

enum class CursorMode { OFF, LAUNCHER, EVERYWHERE }

/** An arrow pointer drawn on top of everything; it never takes touches or focus. */
class CursorView(context: Context) : View(context) {
    var cx = 0f
    var cy = 0f
    private val d = resources.displayMetrics.density
    private val arrow = Path().apply {
        moveTo(0f, 0f); lineTo(0f, 22f * d); lineTo(6f * d, 17f * d); lineTo(10f * d, 26f * d)
        lineTo(14f * d, 24f * d); lineTo(10f * d, 15.5f * d); lineTo(17f * d, 15.5f * d); close()
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; setShadowLayer(4 * d, 0f, d, 0x99000000.toInt()) }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 1.5f * d }

    init {
        isFocusable = false
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setLayerType(LAYER_TYPE_SOFTWARE, null) // shadow layer needs software rendering
    }

    private var placed = false

    // Start in the middle once the overlay knows its size (it may be shown before the first layout).
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (!placed && w > 0 && h > 0) {
            cx = w / 2f
            cy = h / 2f
            placed = true
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.translate(cx, cy)
        canvas.drawPath(arrow, fill)
        canvas.drawPath(arrow, stroke)
        canvas.restore()
    }
}

/**
 * Turns D-pad keys into cursor movement with acceleration. OK clicks (held: long press),
 * pushing against a screen edge scrolls what is under the cursor.
 */
class CursorController(private val view: CursorView, private val host: CursorHost, private val speed: Float) {
    private val handler = Handler(Looper.getMainLooper())
    private val d = view.resources.displayMetrics.density
    private var dirX = 0
    private var dirY = 0
    private var movingSince = 0L
    private var okDownAt = 0L
    private var longFired = false
    private var lastScrollAt = 0L

    var active = false
        private set

    fun show(active: Boolean) {
        this.active = active
        view.visibility = if (active) View.VISIBLE else View.GONE
        if (!active) stopMoving()
        view.invalidate()
    }

    /** Returns true when the key was used by the cursor. */
    fun onKey(event: KeyEvent): Boolean {
        if (!active) return false
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> setDir(down, -1, 0)
            KeyEvent.KEYCODE_DPAD_RIGHT -> setDir(down, 1, 0)
            KeyEvent.KEYCODE_DPAD_UP -> setDir(down, 0, -1)
            KeyEvent.KEYCODE_DPAD_DOWN -> setDir(down, 0, 1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> ok(down, event.repeatCount)
            else -> return false
        }
        return true
    }

    private fun setDir(down: Boolean, x: Int, y: Int) {
        if (down) {
            if (x != 0) dirX = x
            if (y != 0) dirY = y
            if (movingSince == 0L) {
                movingSince = SystemClock.uptimeMillis()
                handler.post(step)
            }
        } else {
            if (x != 0 && dirX == x) dirX = 0
            if (y != 0 && dirY == y) dirY = 0
            if (dirX == 0 && dirY == 0) stopMoving()
        }
    }

    private fun stopMoving() {
        dirX = 0
        dirY = 0
        movingSince = 0
        handler.removeCallbacks(step)
    }

    private val step = object : Runnable {
        override fun run() {
            if (dirX == 0 && dirY == 0) return
            // Accelerate from fine control to crossing the screen in about a second.
            val held = (SystemClock.uptimeMillis() - movingSince) / 1000f
            val v = (3f + 25f * held.coerceAtMost(0.8f) / 0.8f) * d * speed
            val w = view.width.toFloat()
            val h = view.height.toFloat()
            val nx = view.cx + dirX * v
            val ny = view.cy + dirY * v
            val edgeX = if (nx < 0) -1 else if (nx > w - 2) 1 else 0
            val edgeY = if (ny < 0) -1 else if (ny > h - 2) 1 else 0
            view.cx = nx.coerceIn(0f, w - 2)
            view.cy = ny.coerceIn(0f, h - 2)
            view.invalidate()
            host.hover(view.cx, view.cy)
            if ((edgeX != 0 || edgeY != 0) && SystemClock.uptimeMillis() - lastScrollAt > 120) {
                lastScrollAt = SystemClock.uptimeMillis()
                host.scroll(view.cx, view.cy, edgeX, edgeY)
            }
            view.postOnAnimation(this)
        }
    }

    private val longPress = Runnable {
        longFired = true
        host.longClick(view.cx, view.cy)
    }

    private fun ok(down: Boolean, repeat: Int) {
        if (down && repeat == 0) {
            okDownAt = SystemClock.uptimeMillis()
            longFired = false
            handler.postDelayed(longPress, 600)
        } else if (!down) {
            handler.removeCallbacks(longPress)
            if (!longFired) host.click(view.cx, view.cy)
        }
    }
}

/** Cursor inside one of our activities: real mouse events, so hover highlights work too. */
class ActivityCursor(private val activity: Activity) : CursorHost {
    private val decor = activity.window.decorView as ViewGroup
    private val view = CursorView(activity).apply { visibility = View.GONE }
    private val controller: CursorController
    private val handler = Handler(Looper.getMainLooper())

    init {
        decor.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        view.elevation = 1000f
        controller = CursorController(view, this, Prefs.get(activity).cursorSpeed)
    }

    /** Call from Activity.dispatchKeyEvent; returns true when the event was consumed. */
    fun onKey(event: KeyEvent): Boolean {
        val prefs = Prefs.get(activity)
        // In "everywhere" mode CursorService handles keys before they reach us.
        if (prefs.cursorMode != CursorMode.LAUNCHER.name) {
            if (controller.active) controller.show(false)
            return false
        }
        if (event.keyCode == prefs.cursorKey) {
            if (event.action == KeyEvent.ACTION_UP) toggle()
            return true
        }
        if (controller.active && event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            controller.show(false)
            return true
        }
        if (controller.active && event.keyCode == KeyEvent.KEYCODE_BACK) return true
        return controller.onKey(event)
    }

    fun toggle() {
        controller.show(!controller.active)
        view.bringToFront()
    }

    override fun hover(x: Float, y: Float) {
        decor.dispatchGenericMotionEvent(mouse(MotionEvent.ACTION_HOVER_MOVE, x, y, 0))
    }

    override fun click(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        decor.dispatchTouchEvent(mouse(MotionEvent.ACTION_DOWN, x, y, MotionEvent.BUTTON_PRIMARY, now))
        decor.dispatchTouchEvent(mouse(MotionEvent.ACTION_UP, x, y, 0, now))
    }

    override fun longClick(x: Float, y: Float) {
        // Synthetic right clicks need a hidden API, so find the long-clickable view under the cursor.
        longClickable(decor, x.toInt(), y.toInt())?.performLongClick()
    }

    private fun longClickable(v: View, x: Int, y: Int): View? {
        if (v === view || v.visibility != View.VISIBLE) return null
        val r = android.graphics.Rect()
        if (!v.getGlobalVisibleRect(r) || !r.contains(x, y)) return null
        if (v is ViewGroup) {
            for (i in v.childCount - 1 downTo 0) longClickable(v.getChildAt(i), x, y)?.let { return it }
        }
        return v.takeIf { it.isLongClickable }
    }

    override fun scroll(x: Float, y: Float, dx: Int, dy: Int) {
        val e = mouse(MotionEvent.ACTION_SCROLL, x, y, 0, axes = floatArrayOf(-dx.toFloat(), -dy.toFloat()))
        decor.dispatchGenericMotionEvent(e)
    }

    private fun mouse(action: Int, x: Float, y: Float, buttons: Int, down: Long = SystemClock.uptimeMillis(), axes: FloatArray? = null): MotionEvent {
        val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = if (action == MotionEvent.ACTION_DOWN) 1f else 0f
            if (axes != null) {
                setAxisValue(MotionEvent.AXIS_HSCROLL, axes[0])
                setAxisValue(MotionEvent.AXIS_VSCROLL, axes[1])
            }
        }
        return MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 1, arrayOf(props), arrayOf(coords), 0, buttons, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
    }
}
