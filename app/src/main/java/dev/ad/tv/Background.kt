package dev.ad.tv

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.SurfaceView
import android.widget.FrameLayout
import androidx.annotation.StringRes
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer

class BgPreset(@StringRes val name: Int, vararg val colors: Int)

object Backgrounds {
    val presets = listOf(
        BgPreset(R.string.bg_midnight, 0xFF0B1026.toInt(), 0xFF1B2A4A.toInt(), 0xFF0B1026.toInt()),
        BgPreset(R.string.bg_ocean, 0xFF02293A.toInt(), 0xFF0B5A6E.toInt()),
        BgPreset(R.string.bg_aurora, 0xFF0A1F1A.toInt(), 0xFF134E4A.toInt(), 0xFF3B1E54.toInt()),
        BgPreset(R.string.bg_sunset, 0xFF2A0F2E.toInt(), 0xFF7A2E3A.toInt(), 0xFFB5523B.toInt()),
        BgPreset(R.string.bg_forest, 0xFF0E1A12.toInt(), 0xFF1F3B2A.toInt()),
        BgPreset(R.string.bg_graphite, 0xFF151618.toInt(), 0xFF2B2D31.toInt()),
        BgPreset(R.string.bg_black, 0xFF000000.toInt()),
        BgPreset(R.string.bg_navy, 0xFF0D1B3D.toInt()),
        BgPreset(R.string.bg_plum, 0xFF2B1236.toInt()),
        BgPreset(R.string.bg_coffee, 0xFF2A1E17.toInt(), 0xFF4A3426.toInt()),
    )

    fun drawable(index: Int): GradientDrawable {
        val p = presets.getOrElse(index) { presets[0] }
        val colors = if (p.colors.size == 1) intArrayOf(p.colors[0], p.colors[0]) else p.colors
        return GradientDrawable(GradientDrawable.Orientation.TL_BR, colors)
    }

    /** Videos bundled into the `video` flavor (the repo's video/ folder becomes assets). */
    fun bundledVideos(context: Context): List<String> =
        context.assets.list("")?.filter { it.endsWith(".mp4", true) || it.endsWith(".mkv", true) || it.endsWith(".webm", true) }
            ?.map { "asset:///" + Uri.encode(it) } ?: emptyList()
}

/**
 * Muted looping video behind the UI, scaled to cover the screen. The player is created in
 * [start] and released in [stop] so the decoder is free while another app is in front.
 */
class VideoBackground(private val container: FrameLayout) {
    private var player: ExoPlayer? = null
    private var surface: SurfaceView? = null
    private var source: String? = null

    fun start(uri: String?) {
        if (uri == null) {
            stop()
            source = null
            return
        }
        if (player != null && uri == source) {
            player?.play()
            return
        }
        stop()
        source = uri
        val view = SurfaceView(container.context)
        container.addView(view, FrameLayout.LayoutParams(0, 0, Gravity.CENTER))
        surface = view
        player = ExoPlayer.Builder(container.context).build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            setVideoSurfaceView(view)
            addListener(object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) = cover(videoSize)
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    // A broken file or URL just leaves the colour preset visible.
                    view.visibility = android.view.View.GONE
                }
            })
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            play()
        }
    }

    fun stop() {
        player?.release()
        player = null
        surface?.let { container.removeView(it) }
        surface = null
    }

    private fun cover(size: VideoSize) {
        val view = surface ?: return
        if (size.width == 0 || size.height == 0) return
        val cw = container.width.toFloat().takeIf { it > 0 } ?: return
        val ch = container.height.toFloat()
        val videoRatio = size.width * size.pixelWidthHeightRatio / size.height
        val (w, h) = if (cw / ch > videoRatio) cw to cw / videoRatio else ch * videoRatio to ch
        view.layoutParams = FrameLayout.LayoutParams(w.toInt(), h.toInt(), Gravity.CENTER)
    }
}
