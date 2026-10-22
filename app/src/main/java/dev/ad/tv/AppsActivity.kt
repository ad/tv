package dev.ad.tv

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** All installed TV apps as a banner grid, alphabetical. Long press: favorite / app info. */
class AppsActivity : Activity() {
    private lateinit var grid: RecyclerView
    private var apps: List<AppEntry> = emptyList()

    private val cursor by lazy { ActivityCursor(this) }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean =
        cursor.onKey(event) || super.dispatchKeyEvent(event)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_apps)
        window.decorView.background = Backgrounds.drawable(Prefs.get(this).backgroundPreset)
        grid = findViewById(R.id.grid)
        grid.layoutManager = GridLayoutManager(this, 5)
        grid.itemAnimator = null
        grid.adapter = Adapter()
    }

    override fun onResume() {
        super.onResume()
        apps = Apps.installed(this)
        grid.adapter?.notifyDataSetChanged()
        if (grid.findFocus() == null) grid.post { grid.getChildAt(0)?.requestFocus() }
    }

    private fun showMenu(app: AppEntry) {
        val prefs = Prefs.get(this)
        val isFav = app.pkg in prefs.favorites
        val items = arrayOf(
            getString(if (isFav) R.string.remove_favorite else R.string.add_favorite),
            getString(R.string.app_info),
        )
        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        prefs.favorites = if (isFav) prefs.favorites - app.pkg else prefs.favorites + app.pkg
                        prefs.touch()
                    }
                    1 -> Apps.openAppInfo(this, app.pkg)
                }
            }
            .show()
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.Holder>() {
        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val banner: ImageView = view.findViewById(R.id.banner)
            val icon: ImageView = view.findViewById(R.id.icon)
            val label: TextView = view.findViewById(R.id.label)
            val fallback: View = view.findViewById(R.id.fallback)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_favorite, parent, false)
            val gap = (7 * resources.displayMetrics.density).toInt()
            v.layoutParams = (v.layoutParams as ViewGroup.MarginLayoutParams).apply {
                width = FrameLayout.LayoutParams.MATCH_PARENT
                topMargin = gap
                bottomMargin = gap
            }
            v.findViewById<View>(R.id.tileClip).apply {
                outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                clipToOutline = true
            }
            v.setOnFocusChangeListener { view, has ->
                view.animate().scaleX(if (has) 1.08f else 1f).scaleY(if (has) 1.08f else 1f).setDuration(120).start()
                view.translationZ = if (has) 8f else 0f
            }
            return Holder(v)
        }

        override fun getItemCount() = apps.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val app = apps[position]
            val banner = Apps.banner(this@AppsActivity, app.pkg)
            holder.banner.visibility = if (banner != null) View.VISIBLE else View.GONE
            holder.fallback.visibility = if (banner != null) View.GONE else View.VISIBLE
            if (banner != null) holder.banner.setImageDrawable(banner)
            else {
                holder.icon.setImageDrawable(Apps.icon(this@AppsActivity, app.pkg))
                holder.label.text = app.label
            }
            holder.itemView.contentDescription = app.label
            holder.itemView.setOnClickListener { if (Apps.launch(this@AppsActivity, app.pkg)) finish() }
            holder.itemView.setOnLongClickListener { showMenu(app); true }
            holder.itemView.mouseFriendly(onContextClick = { showMenu(app) })
        }
    }
}
