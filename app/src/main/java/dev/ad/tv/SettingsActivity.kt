package dev.ad.tv

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * All app settings, operated with the remote. Pages are plain lists of rows rendered into one
 * container; BACK pops the page stack.
 */
class SettingsActivity : Activity() {
    companion object {
        const val EXTRA_PAGE = "page"
        private const val REQ_VIDEO = 1
    }

    private val scope = MainScope()
    private lateinit var prefs: Prefs
    private lateinit var rows: LinearLayout
    private lateinit var title: TextView
    private lateinit var hint: TextView
    private val stack = ArrayList<String>()
    private var homeLog = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.get(this)
        setContentView(R.layout.activity_settings)
        window.decorView.background = Backgrounds.drawable(prefs.backgroundPreset)
        rows = findViewById(R.id.rows)
        title = findViewById(R.id.pageTitle)
        hint = findViewById(R.id.pageHint)
        stack += "main"
        intent.getStringExtra(EXTRA_PAGE)?.let { stack += it }
        render()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (stack.size > 1) {
            stack.removeAt(stack.lastIndex)
            render()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    private fun open(page: String) {
        stack += page
        render()
    }

    /** Re-renders the current page and keeps focus on the same row index. */
    private fun render(keepFocus: Boolean = false) {
        val focused = if (keepFocus) (0 until rows.childCount).firstOrNull { rows.getChildAt(it).hasFocus() } else null
        rows.removeAllViews()
        hint.text = ""
        when (stack.last()) {
            "main" -> mainPage()
            "background" -> backgroundPage()
            "favorites" -> favoritesPage()
            "feed" -> feedPage()
            "hidden" -> hiddenPage()
            "home" -> homePage()
        }
        val target = rows.getChildAt(focused ?: 0) ?: rows.getChildAt(0)
        target?.requestFocus()
        if (!keepFocus) findViewById<ScrollView>(R.id.scroll).scrollTo(0, 0)
    }

    private fun changed() {
        prefs.touch()
        render(keepFocus = true)
    }

    private fun row(
        title: String,
        value: String? = null,
        mark: String? = null,
        icon: Drawable? = null,
        onLongClick: (() -> Unit)? = null,
        onClick: () -> Unit,
    ) {
        val v = LayoutInflater.from(this).inflate(R.layout.item_row, rows, false)
        v.findViewById<TextView>(R.id.rowTitle).text = title
        v.findViewById<TextView>(R.id.rowValue).apply {
            text = value
            visibility = if (value.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        v.findViewById<TextView>(R.id.rowMark).text = mark ?: ""
        v.findViewById<ImageView>(R.id.rowIcon).apply {
            setImageDrawable(icon)
            visibility = if (icon != null) View.VISIBLE else View.GONE
        }
        v.setOnClickListener { onClick() }
        if (onLongClick != null) v.setOnLongClickListener { onLongClick(); true }
        rows.addView(v)
    }

    private fun section(text: String) {
        val tv = TextView(this)
        tv.text = text
        tv.setTextAppearance(R.style.Text_Small)
        tv.setPadding(18, 18, 0, 8)
        rows.addView(tv)
    }

    private fun on(b: Boolean) = getString(if (b) R.string.on else R.string.off)

    // ---- Pages ----------------------------------------------------------------------------------

    private fun mainPage() {
        title.setText(R.string.settings_title)
        hint.text = getString(R.string.version, BuildConfig.VERSION_NAME)
        row(getString(R.string.background), backgroundName()) { open("background") }
        row(getString(R.string.city), prefs.cityName) { askCity() }
        row(getString(R.string.favorites), resources.getQuantityString(R.plurals.apps_count, prefs.favorites.size, prefs.favorites.size)) {
            open("favorites")
        }
        row(getString(R.string.feed), on(prefs.feedEnabled)) { open("feed") }
        row(getString(R.string.home_screen), getString(if (HomeSwitch.isOurs(this)) R.string.home_this else R.string.home_stock)) {
            open("home")
        }
    }

    private fun backgroundName(): String {
        val video = prefs.backgroundVideo ?: return getString(Backgrounds.presets.getOrElse(prefs.backgroundPreset) { Backgrounds.presets[0] }.name)
        return getString(R.string.video) + ": " + videoName(video)
    }

    private fun videoName(uri: String) = android.net.Uri.decode(uri.substringAfterLast('/')).substringBefore('?')

    private fun backgroundPage() {
        title.setText(R.string.background)
        hint.setText(R.string.background_hint)
        section(getString(R.string.colors))
        Backgrounds.presets.forEachIndexed { i, p ->
            val current = prefs.backgroundVideo == null && prefs.backgroundPreset == i
            row(getString(p.name), mark = if (current) "✓" else null, icon = Backgrounds.drawable(i)) {
                prefs.backgroundPreset = i
                prefs.backgroundVideo = null
                window.decorView.background = Backgrounds.drawable(i)
                changed()
            }
        }
        section(getString(R.string.video))
        for (uri in Backgrounds.bundledVideos(this)) {
            row(videoName(uri), getString(R.string.video_bundled), mark = if (prefs.backgroundVideo == uri) "✓" else null) {
                prefs.backgroundVideo = uri
                changed()
            }
        }
        row(getString(R.string.video_from_device), getString(R.string.video_from_device_hint)) { pickLocalVideo() }
        val url = prefs.lastVideoUrl
        row(getString(R.string.video_from_url), url.ifBlank { null }, mark = if (url.isNotBlank() && prefs.backgroundVideo == url) "✓" else null) {
            askText(getString(R.string.video_from_url), url, "https://…") { text ->
                if (text.isNotBlank()) {
                    prefs.lastVideoUrl = text
                    prefs.backgroundVideo = text
                }
                changed()
            }
        }
        if (prefs.backgroundVideo != null) {
            row(getString(R.string.video_off)) {
                prefs.backgroundVideo = null
                changed()
            }
        }
    }

    private fun favoritesPage() {
        title.setText(R.string.favorites)
        hint.setText(R.string.favorites_hint)
        val favorites = prefs.favorites
        if (favorites.isNotEmpty()) section(getString(R.string.order))
        favorites.forEachIndexed { i, pkg ->
            row("${i + 1}. ${Apps.label(this, pkg)}", icon = Apps.icon(this, pkg)) { reorder(pkg) }
        }
        section(getString(R.string.all_apps))
        for (app in Apps.installed(this)) {
            val on = app.pkg in favorites
            row(app.label, mark = if (on) "✓" else null, icon = Apps.icon(this, app.pkg)) {
                prefs.favorites = if (on) favorites - app.pkg else favorites + app.pkg
                changed()
            }
        }
    }

    private fun reorder(pkg: String) {
        val items = arrayOf(getString(R.string.move_up), getString(R.string.move_down), getString(R.string.remove_favorite))
        AlertDialog.Builder(this).setTitle(Apps.label(this, pkg)).setItems(items) { _, which ->
            val list = prefs.favorites.toMutableList()
            val i = list.indexOf(pkg)
            when (which) {
                0 -> if (i > 0) list.add(i - 1, list.removeAt(i))
                1 -> if (i < list.lastIndex) list.add(i + 1, list.removeAt(i))
                2 -> list.removeAt(i)
            }
            prefs.favorites = list
            changed()
        }.show()
    }

    private fun feedPage() {
        title.setText(R.string.feed)
        hint.setText(R.string.feed_hint)
        val source = FeedRepo.Config.from(prefs).source
        row(getString(R.string.feed), on(prefs.feedEnabled)) {
            prefs.feedEnabled = !prefs.feedEnabled
            changed()
        }
        row(getString(R.string.feed_source), sourceName(source)) {
            choose(getString(R.string.feed_source), FeedSource.entries, ::sourceName, source) {
                prefs.feedSource = it.name
                if (it != FeedSource.CUB && prefs.feedMode !in setOf(FeedMode.NOW.name, FeedMode.NEW.name)) prefs.feedMode = FeedMode.NOW.name
                FeedRepo.clearCache(this)
                changed()
            }
        }
        val modes = if (source == FeedSource.CUB) FeedMode.entries else listOf(FeedMode.NOW, FeedMode.NEW)
        val mode = FeedRepo.Config.from(prefs).mode
        row(getString(R.string.feed_mode), modeName(mode, source)) {
            choose(getString(R.string.feed_mode), modes, { modeName(it, source) }, mode) {
                prefs.feedMode = it.name
                changed()
            }
        }
        val kind = FeedRepo.Config.from(prefs).kind
        row(getString(R.string.feed_kind), kindName(kind)) {
            choose(getString(R.string.feed_kind), FeedKind.entries, ::kindName, kind) {
                prefs.feedKind = it.name
                changed()
            }
        }
        val openIn = runCatching { OpenIn.valueOf(prefs.openIn) }.getOrDefault(OpenIn.LAMPA)
        row(getString(R.string.open_in_setting), FeedOpener.appName(this, openIn)) {
            choose(getString(R.string.open_in_setting), OpenIn.entries, { FeedOpener.appName(this, it) }, openIn) {
                prefs.openIn = it.name
                changed()
            }
        }
        val hidden = prefs.hiddenFeedItems.size
        row(getString(R.string.hidden_items), hidden.toString()) { open("hidden") }
        row(getString(R.string.feed_refresh)) {
            FeedRepo.clearCache(this)
            changed()
            Toast.makeText(this, R.string.feed_refresh_done, Toast.LENGTH_SHORT).show()
        }
        section(getString(R.string.keys))
        row(getString(R.string.tmdb_key), mask(prefs.tmdbKey)) {
            askText(getString(R.string.tmdb_key), prefs.tmdbKey, "") { prefs.tmdbKey = it; FeedRepo.clearCache(this); changed() }
        }
        row(getString(R.string.kp_key), mask(prefs.kpKey)) {
            askText(getString(R.string.kp_key), prefs.kpKey, "") { prefs.kpKey = it; FeedRepo.clearCache(this); changed() }
        }
        row(getString(R.string.tmdb_api_host), prefs.tmdbApiHost.ifBlank { getString(R.string.default_value) }) {
            askText(getString(R.string.tmdb_api_host), prefs.tmdbApiHost, "https://apitmdb.example/3") {
                prefs.tmdbApiHost = it; FeedRepo.clearCache(this); changed()
            }
        }
        row(getString(R.string.tmdb_img_host), prefs.tmdbImageHost.ifBlank { getString(R.string.default_value) }) {
            askText(getString(R.string.tmdb_img_host), prefs.tmdbImageHost, "https://imagetmdb.com") {
                prefs.tmdbImageHost = it; FeedRepo.clearCache(this); changed()
            }
        }
    }

    private fun hiddenPage() {
        title.setText(R.string.hidden_items)
        hint.setText(R.string.hidden_hint)
        val hidden = prefs.hiddenFeedItems.sorted()
        if (hidden.isEmpty()) {
            row(getString(R.string.nothing_hidden)) {}
            return
        }
        row(getString(R.string.unhide_all)) {
            prefs.hiddenFeedItems = emptySet()
            changed()
        }
        for (entry in hidden) {
            row(entry.substringAfter('\t', entry), getString(R.string.unhide)) {
                prefs.hiddenFeedItems = prefs.hiddenFeedItems - entry
                changed()
            }
        }
    }

    private fun homePage() {
        title.setText(R.string.home_screen)
        val ours = HomeSwitch.isOurs(this)
        hint.text = getString(R.string.home_hint) + if (homeLog.isNotBlank()) "\n\n$homeLog" else ""
        row(getString(R.string.home_current), getString(if (ours) R.string.home_this else R.string.home_stock)) {}
        val paired = HomeSwitch.isPaired(this)
        row(getString(R.string.adb_pair), getString(if (paired) R.string.adb_paired else R.string.adb_pair_hint)) {
            askText(getString(R.string.adb_pair_code), "", "123456", numeric = true) { code ->
                if (code.isNotBlank()) runAdb(getString(R.string.adb_pair)) { HomeSwitch.pair(this, code) }
            }
        }
        if (ours) {
            row(getString(R.string.home_use_stock)) { runAdb(getString(R.string.home_use_stock)) { HomeSwitch.useStockLauncher(this, ::log) } }
        } else {
            row(getString(R.string.home_use_this)) { runAdb(getString(R.string.home_use_this)) { HomeSwitch.useThisLauncher(this, ::log) } }
        }
        row(getString(R.string.usage_access), on(RecentApp.hasUsageAccess(this))) {
            runAdb(getString(R.string.usage_access)) { HomeSwitch.grantPermissions(this, ::log) }
        }
    }

    private fun log(line: String) {
        runOnUiThread {
            homeLog = (homeLog.lines().takeLast(6) + line).joinToString("\n").trim()
            if (stack.last() == "home") hint.text = getString(R.string.home_hint) + "\n\n" + homeLog
        }
    }

    private fun runAdb(what: String, block: () -> Unit) {
        homeLog = getString(R.string.working)
        render(keepFocus = true)
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching(block) }
            homeLog = result.fold(
                { getString(R.string.done) },
                { e ->
                    if (e is HomeSwitch.WifiOwnedException) getString(R.string.wifi_owned, e.owners.joinToString())
                    else what + ": " + (e.message ?: e.javaClass.simpleName)
                },
            )
            prefs.touch()
            if (stack.last() == "home") render(keepFocus = true)
        }
    }

    // ---- Dialogs --------------------------------------------------------------------------------

    private fun askCity() {
        askText(getString(R.string.city), "", getString(R.string.city_hint)) { query ->
            if (query.isBlank()) return@askText
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { Geocoder.search(query) } }
                val cities = result.getOrElse {
                    Toast.makeText(this@SettingsActivity, getString(R.string.city_failed, it.message), Toast.LENGTH_LONG).show()
                    return@launch
                }
                if (cities.isEmpty()) {
                    Toast.makeText(this@SettingsActivity, R.string.city_not_found, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle(R.string.city)
                    .setItems(cities.map { it.details }.toTypedArray()) { _, which ->
                        val c = cities[which]
                        prefs.cityName = c.name
                        prefs.cityLat = c.lat
                        prefs.cityLon = c.lon
                        changed()
                    }
                    .show()
            }
        }
    }

    private fun askText(title: String, value: String, hintText: String, numeric: Boolean = false, onDone: (String) -> Unit) {
        val edit = EditText(this).apply {
            setText(value)
            hint = hintText
            isSingleLine = true
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSelection(text.length)
        }
        val box = LinearLayout(this).apply {
            setPadding(48, 16, 48, 0)
            addView(edit, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ -> onDone(edit.text.toString().trim()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        edit.requestFocus()
    }

    private fun <T> choose(title: String, options: List<T>, label: (T) -> String, current: T, onPick: (T) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(options.map(label).toTypedArray(), options.indexOf(current)) { d, which ->
                d.dismiss()
                onPick(options[which])
            }
            .show()
    }

    private fun mask(key: String) =
        if (key.isBlank()) getString(R.string.not_set) else key.take(4) + "…" + key.takeLast(4)

    private fun sourceName(s: FeedSource) = getString(
        when (s) {
            FeedSource.CUB -> R.string.source_cub
            FeedSource.TMDB -> R.string.source_tmdb
            FeedSource.KINOPOISK -> R.string.source_kp
        },
    )

    private fun modeName(m: FeedMode, source: FeedSource) = getString(
        when (m) {
            FeedMode.NOW -> if (source == FeedSource.CUB) R.string.mode_now else R.string.mode_trending
            FeedMode.NEW -> R.string.mode_new
            FeedMode.NEW_4K -> R.string.mode_new_4k
            FeedMode.HOT -> R.string.mode_hot
            FeedMode.TOP -> R.string.mode_top
        },
    )

    private fun kindName(k: FeedKind) = getString(
        when (k) {
            FeedKind.BOTH -> R.string.kind_both
            FeedKind.MOVIE -> R.string.kind_movies
            FeedKind.TV -> R.string.kind_series
        },
    )

    // ---- Local video ----------------------------------------------------------------------------

    private fun pickLocalVideo() {
        if (checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.READ_MEDIA_VIDEO), REQ_VIDEO)
            return
        }
        val videos = queryVideos()
        if (videos.isEmpty()) {
            Toast.makeText(this, R.string.no_local_videos, Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.video_from_device)
            .setItems(videos.map { it.second }.toTypedArray()) { _, which ->
                prefs.backgroundVideo = videos[which].first
                changed()
            }
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQ_VIDEO && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) pickLocalVideo()
    }

    private fun queryVideos(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val projection = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.RELATIVE_PATH)
        contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection, null, null,
            MediaStore.Video.Media.DATE_ADDED + " DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
                out += uri.toString() to "${c.getString(2) ?: ""}${c.getString(1)}"
            }
        }
        return out
    }
}
