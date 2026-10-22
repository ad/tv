package dev.ad.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateFormat
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

class HomeActivity : Activity() {
    private val scope: CoroutineScope = MainScope()
    private lateinit var prefs: Prefs

    private lateinit var root: FrameLayout
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var weatherBox: View
    private lateinit var favList: RecyclerView
    private lateinit var feedBox: View
    private lateinit var feedList: EntryFocusRecyclerView
    private lateinit var feedCaption: TextView
    private lateinit var topLabel: TextView
    private lateinit var btnInputs: ImageButton

    private lateinit var video: VideoBackground
    private val favAdapter = FavoritesAdapter(::onFavoriteClick, ::onFavoriteLongClick)
    private val feedAdapter = FeedAdapter(::onFeedClick, ::onFeedLongClick, ::onFeedSelected)

    private var appliedRevision = -1
    private var weatherJob: Job? = null
    private var feedJob: Job? = null
    private var feedItems: List<FeedItem> = emptyList()
    private var feedConfig: FeedRepo.Config? = null

    private val tick = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            updateClock()
            maybeRefreshWeather()
            maybeRefreshFeed()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (CrashWatchdog.tripped(this)) {
            startActivity(Intent(this, RescueActivity::class.java))
        }
        prefs = Prefs.get(this)
        setContentView(R.layout.activity_home)

        root = findViewById(R.id.root)
        clock = findViewById(R.id.clock)
        date = findViewById(R.id.date)
        weatherBox = findViewById(R.id.weatherBox)
        favList = findViewById(R.id.favList)
        feedBox = findViewById(R.id.feedBox)
        feedList = findViewById(R.id.feedList)
        feedCaption = findViewById(R.id.feedCaption)
        topLabel = findViewById(R.id.topLabel)
        btnInputs = findViewById(R.id.btnInputs)
        video = VideoBackground(findViewById(R.id.bgVideo))

        favList.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        favList.adapter = favAdapter
        favList.itemAnimator = null
        feedList.layoutManager = StartAlignedLayoutManager(this)
        feedList.adapter = feedAdapter
        feedList.entryPosition = { feedAdapter.selectedPosition }
        feedList.itemAnimator = null

        setupTopButton(btnInputs) { showInputs() }
        setupTopButton(findViewById(R.id.btnApps)) { startActivity(Intent(this, AppsActivity::class.java)) }
        setupTopButton(findViewById(R.id.btnAppSettings)) { startActivity(Intent(this, SettingsActivity::class.java)) }
        setupTopButton(findViewById(R.id.btnTvSettings)) {
            Apps.start(this, Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        updateClock()
        // Network and package queries happen after the first frame.
        root.post { Apps.seedFavorites(this); applySettings() }
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(tick, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
        updateClock()
        if (appliedRevision != -1) applySettings()
        video.start(prefs.backgroundVideo)
    }

    override fun onResume() {
        super.onResume()
        // Re-read icons/banners too: apps may have been installed or removed while away.
        if (appliedRevision != -1) favAdapter.submit(this, prefs.favorites)
        root.post { focusRecentApp() }
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(tick)
        video.stop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // HOME pressed while already home: jump back to the favorites.
        if (Intent.ACTION_MAIN == intent.action) {
            feedAdapter.select(0)
            feedList.scrollToPosition(0)
            favList.scrollToPosition(0)
            favList.post { favList.getChildAt(0)?.requestFocus() }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Home has nowhere to go back to.
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return true
        return super.dispatchKeyEvent(event)
    }

    // ---- Settings -------------------------------------------------------------------------------

    private fun applySettings() {
        if (appliedRevision == prefs.revision) return
        appliedRevision = prefs.revision
        root.background = Backgrounds.drawable(prefs.backgroundPreset)
        favAdapter.submit(this, prefs.favorites)
        btnInputs.visibility = if (Inputs.list(this).isEmpty()) View.GONE else View.VISIBLE

        val feedOn = prefs.feedEnabled
        feedBox.visibility = if (feedOn) View.VISIBLE else View.GONE
        applyLayoutMode(feedOn)

        showCachedWeather()
        maybeRefreshWeather(force = true)

        val config = FeedRepo.Config.from(prefs)
        if (feedOn) {
            if (feedConfig?.id != config.id) feedItems = emptyList()
            feedConfig = config
            FeedRepo.cached(this, config)?.let { showFeed(it.items) }
            maybeRefreshFeed(force = feedItems.isEmpty())
            if (feedItems.isNotEmpty()) showFeed(feedItems)
        }
    }

    /** Large clock when the feed is off; compact header when the feed takes the middle. */
    private fun applyLayoutMode(feedOn: Boolean) {
        clock.textSize = if (feedOn) 76f else 132f
        date.textSize = if (feedOn) 18f else 24f
        val spacer = findViewById<Space>(R.id.headerSpacer)
        spacer.visibility = if (feedOn) View.GONE else View.VISIBLE
        (spacer.layoutParams as LinearLayout.LayoutParams).weight = if (feedOn) 0f else 0.6f
        spacer.requestLayout()
    }

    // ---- Clock & weather ------------------------------------------------------------------------

    private fun updateClock() {
        val now = LocalTime.now()
        val pattern = if (DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm"
        clock.text = now.format(DateTimeFormatter.ofPattern(pattern))
        val locale = Locale.getDefault()
        val text = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM", locale))
        date.text = text.replaceFirstChar { it.titlecase(locale) }
    }

    private fun showCachedWeather() {
        WeatherRepo.cached(this, prefs.cityLat, prefs.cityLon)?.let(::showWeather)
    }

    private var weatherFetchedAt = 0L
    private var weatherFailedAt = 0L

    private fun maybeRefreshWeather(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (weatherJob?.isActive == true) return
        if (!force && now - weatherFetchedAt < WeatherRepo.REFRESH_MS) return
        if (!force && now - weatherFailedAt < 5 * 60_000) return
        val lat = prefs.cityLat
        val lon = prefs.cityLon
        weatherJob = scope.launch {
            try {
                val w = withContext(Dispatchers.IO) { WeatherRepo.fetch(this@HomeActivity, lat, lon) }
                weatherFetchedAt = w.fetchedAt
                showWeather(w)
            } catch (e: Exception) {
                weatherFailedAt = System.currentTimeMillis()
            }
        }
    }

    private fun showWeather(w: Weather) {
        weatherFetchedAt = maxOf(weatherFetchedAt, w.fetchedAt)
        weatherBox.visibility = View.VISIBLE
        findViewById<TextView>(R.id.weatherIcon).text = WeatherRepo.emoji(w.symbol)
        findViewById<TextView>(R.id.weatherTemp).text = signed(w.temp) + "°"
        val age = (System.currentTimeMillis() - w.fetchedAt) / 3_600_000
        val stale = if (age >= 3) " · " + getString(R.string.hours_ago, age.toInt()) else ""
        findViewById<TextView>(R.id.weatherFeels).text =
            getString(R.string.feels_like, signed(w.feels) + "°", w.wind) + stale
        findViewById<TextView>(R.id.weatherToday).text = getString(
            R.string.today_range, signed(w.today.min) + "…" + signed(w.today.max) + "°", precip(w.today.precipMm),
        )
        findViewById<TextView>(R.id.weatherCity).text = prefs.cityName

        val row = findViewById<LinearLayout>(R.id.forecast)
        row.removeAllViews()
        val inflater = LayoutInflater.from(this)
        val locale = Locale.getDefault()
        for (d in w.next) {
            val card = inflater.inflate(R.layout.item_forecast, row, false)
            card.findViewById<TextView>(R.id.fDay).text = d.date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
                .replaceFirstChar { it.titlecase(locale) }
            card.findViewById<TextView>(R.id.fIcon).text = WeatherRepo.emoji(d.symbol)
            card.findViewById<TextView>(R.id.fTemp).text = signed(d.min) + "…" + signed(d.max) + "°"
            row.addView(card)
        }
    }

    private fun precip(mm: Double): String =
        if (mm < 0.1) getString(R.string.no_precip) else getString(R.string.precip_mm, String.format(Locale.getDefault(), "%.1f", mm))

    private fun signed(t: Int) = if (t > 0) "+$t" else if (t < 0) "−${-t}" else "0"

    // ---- Feed -----------------------------------------------------------------------------------

    private var feedFetchedAt = 0L
    private var feedFailedAt = 0L

    private fun maybeRefreshFeed(force: Boolean = false) {
        if (!prefs.feedEnabled) return
        val config = feedConfig ?: return
        val now = System.currentTimeMillis()
        if (feedJob?.isActive == true) return
        val cachedAt = FeedRepo.cached(this, config)?.fetchedAt ?: 0L
        feedFetchedAt = maxOf(feedFetchedAt, cachedAt)
        if (!force && now - feedFetchedAt < FeedRepo.REFRESH_MS) return
        if (now - feedFailedAt < 10 * 60_000 && !force) return
        feedJob = scope.launch {
            try {
                val items = withContext(Dispatchers.IO) { FeedRepo.fetch(this@HomeActivity, config) }
                feedFetchedAt = System.currentTimeMillis()
                if (feedConfig?.id == config.id) showFeed(items)
            } catch (e: Exception) {
                feedFailedAt = System.currentTimeMillis()
                if (feedItems.isEmpty()) feedCaption.text = getString(R.string.feed_failed, e.message ?: "")
            }
        }
    }

    private fun showFeed(items: List<FeedItem>) {
        feedItems = items
        val config = feedConfig
        val more = config != null && config.source == FeedSource.CUB && prefs.openIn == OpenIn.LAMPA.name
        val focusedKey = feedAdapter.selectedItem()?.key
        feedAdapter.submit(FeedRepo.visible(this, items), more)
        if (focusedKey != null) feedAdapter.selectKey(focusedKey)
        onFeedSelected(feedAdapter.selectedItem())
    }

    private fun onFeedSelected(item: FeedItem?) {
        feedCaption.text = when {
            item == null && feedAdapter.itemCount > 0 -> getString(R.string.feed_more_in_lampa)
            item == null -> ""
            else -> listOf(item.title, item.year, getString(if (item.isTv) R.string.kind_tv else R.string.kind_movie))
                .filter { it.isNotBlank() }.joinToString(" · ")
        }
    }

    private fun onFeedClick(item: FeedItem?) {
        if (item == null) {
            feedConfig?.let { FeedOpener.openLampaList(this, getString(R.string.app_name), FeedRepo.cubLampaUrl(it)) }
            return
        }
        FeedOpener.open(this, item, OpenIn.valueOf(prefs.openIn))
    }

    private fun onFeedLongClick(item: FeedItem) {
        val targets = OpenIn.entries
        val labels = targets.map { getString(R.string.open_in, FeedOpener.appName(this, it)) } + getString(R.string.hide)
        AlertDialog.Builder(this)
            .setTitle(item.title)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < targets.size) FeedOpener.open(this, item, targets[which])
                else {
                    FeedRepo.hide(this, item)
                    showFeed(feedItems)
                    Toast.makeText(this, R.string.hidden_toast, Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    // ---- Favorites ------------------------------------------------------------------------------

    private fun onFavoriteClick(pkg: String?) {
        if (pkg == null) {
            startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_PAGE, "favorites"))
        } else {
            Apps.launch(this, pkg)
        }
    }

    private fun onFavoriteLongClick(pkg: String) {
        val items = arrayOf(getString(R.string.remove_favorite), getString(R.string.app_info))
        AlertDialog.Builder(this)
            .setTitle(Apps.label(this, pkg))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        prefs.favorites = prefs.favorites - pkg
                        prefs.touch()
                        applySettings()
                    }
                    1 -> Apps.openAppInfo(this, pkg)
                }
            }
            .show()
    }

    /** Puts focus on the tile of the app we just returned from, or the first tile. */
    private fun focusRecentApp() {
        val favorites = favAdapter.packages
        if (favorites.isEmpty()) {
            favList.getChildAt(0)?.requestFocus()
            return
        }
        val recent = RecentApp.last(this)
        val index = favorites.indexOf(recent).coerceAtLeast(0)
        favList.scrollToPosition(index)
        focusWhenLaidOut(index, attempts = 5)
    }

    private fun focusWhenLaidOut(index: Int, attempts: Int) {
        val holder = favList.findViewHolderForAdapterPosition(index)
        if (holder != null) holder.itemView.requestFocus()
        else if (attempts > 0) favList.post { focusWhenLaidOut(index, attempts - 1) }
    }

    // ---- Top buttons ----------------------------------------------------------------------------

    private fun setupTopButton(button: ImageButton, action: () -> Unit) {
        button.setOnClickListener { action() }
        button.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                topLabel.text = v.contentDescription
                topLabel.visibility = View.VISIBLE
            } else {
                v.post { if ((v.parent as View).findFocus() == null) topLabel.visibility = View.INVISIBLE }
            }
        }
    }

    private fun showInputs() {
        val inputs = Inputs.list(this)
        if (inputs.isEmpty()) {
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.inputs)
            .setItems(inputs.map { it.label }.toTypedArray()) { _, which -> Inputs.open(this, inputs[which]) }
            .show()
    }
}

/** Keeps the focused feed card at the left edge, so the wide "first" card is always the focused one. */
class StartAlignedLayoutManager(context: Context) : LinearLayoutManager(context, RecyclerView.HORIZONTAL, false) {
    override fun requestChildRectangleOnScreen(
        parent: RecyclerView, child: View, rect: android.graphics.Rect, immediate: Boolean, focusedChildVisible: Boolean,
    ): Boolean {
        val dx = child.left - parent.paddingLeft
        if (dx == 0) return false
        if (immediate) parent.scrollBy(dx, 0) else parent.smoothScrollBy(dx, 0)
        return true
    }
}
