package dev.ad.tv

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * System-wide remote cursor. Accessibility is the only way a third-party app can see remote keys
 * in other apps and tap on their behalf; it does nothing unless the cursor mode is "everywhere".
 */
class CursorService : AccessibilityService(), CursorHost {
    private var view: CursorView? = null
    private var controller: CursorController? = null

    override fun onServiceConnected() {
        running = true
        val v = CursorView(this).apply { visibility = View.GONE }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        getSystemService(WindowManager::class.java).addView(v, lp)
        view = v
        controller = CursorController(v, this, Prefs.get(this).cursorSpeed)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val c = controller ?: return false
        val prefs = Prefs.get(this)
        if (prefs.cursorMode != CursorMode.EVERYWHERE.name) {
            if (c.active) c.show(false)
            return false
        }
        if (event.keyCode == prefs.cursorKey) {
            if (event.action == KeyEvent.ACTION_UP) c.show(!c.active)
            return true
        }
        return c.onKey(event)
    }

    override fun click(x: Float, y: Float) = tap(x, y, 40)

    override fun longClick(x: Float, y: Float) = tap(x, y, 800)

    override fun scroll(x: Float, y: Float, dx: Int, dy: Int) {
        // Pushing past the right edge means "show more on the right": swipe content to the left.
        val dist = 300f * resources.displayMetrics.density
        val sx = x - dx * 60f
        val sy = y - dy * 60f
        val path = Path().apply { moveTo(sx, sy); lineTo(sx - dx * dist, sy - dy * dist) }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 250)).build(), null, null)
    }

    private fun tap(x: Float, y: Float, durationMs: Long) {
        val path = Path().apply { moveTo(x, y) }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build(), null, null)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (SystemClock.elapsedRealtime() > pairUntil || event?.packageName?.toString() != TV_SETTINGS) return
        val texts = ArrayList<String>()
        collectText(rootInActiveWindow ?: return, texts)
        val code = texts.firstOrNull { CODE.matches(it) } ?: return
        val (host, port) = texts.firstNotNullOfOrNull { HOST_PORT.matchEntire(it) }?.destructured ?: return
        pairUntil = 0
        Thread {
            val error = runCatching { HomeSwitch.pair(this, host, port.toInt(), code) }.exceptionOrNull()
            val text = getString(R.string.adb_pair) + ": " + (error?.let { it.message ?: it.javaClass.simpleName } ?: getString(R.string.adb_paired))
            mainExecutor.execute { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
        }.start()
    }

    private fun collectText(node: AccessibilityNodeInfo, out: MutableList<String>) {
        node.text?.let { out += it.toString().trim() }
        for (i in 0 until node.childCount) node.getChild(i)?.let { collectText(it, out) }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        running = false
        view?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        view = null
        super.onDestroy()
    }

    companion object {
        @Volatile var running = false

        private const val TV_SETTINGS = "com.android.tv.settings"
        private val CODE = Regex("\\d{6}")
        private val HOST_PORT = Regex("(\\d{1,3}(?:\\.\\d{1,3}){3}):(\\d+)")
        @Volatile private var pairUntil = 0L

        /** For the next few minutes, pair with the code shown in the TV's "Pair device with pairing code" dialog. */
        fun armPairing() {
            pairUntil = SystemClock.elapsedRealtime() + 5 * 60_000
        }

        private fun component(context: Context) = ComponentName(context, CursorService::class.java).flattenToString()

        fun isEnabled(context: Context): Boolean =
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?.split(':')?.contains(component(context)) == true

        /** Needs WRITE_SECURE_SETTINGS (granted by the home-screen wizard or setup.sh). */
        fun setEnabled(context: Context, enabled: Boolean) {
            val cr = context.contentResolver
            val me = component(context)
            val list = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?.split(':')?.filter { it.isNotBlank() && it != me } ?: emptyList()
            val next = if (enabled) list + me else list
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next.joinToString(":"))
            if (enabled) Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        }
    }
}
