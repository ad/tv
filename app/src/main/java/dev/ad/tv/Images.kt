package dev.ad.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Minimal image loader: memory LRU + disk cache capped at [DISK_LIMIT]. */
object Images {
    private const val DISK_LIMIT = 50L * 1024 * 1024
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private val memory = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    @Volatile private var trimmed = false

    fun load(view: ImageView, url: String?) {
        view.tag = url
        if (url == null) {
            view.setImageDrawable(null)
            return
        }
        memory.get(url)?.let {
            view.setImageBitmap(it)
            return
        }
        view.setImageDrawable(null)
        val context = view.context.applicationContext
        pool.execute {
            val bmp = try {
                fetch(context, url)
            } catch (e: Exception) {
                null
            } ?: return@execute
            memory.put(url, bmp)
            main.post { if (view.tag == url) view.setImageBitmap(bmp) }
        }
    }

    /** Warms the disk cache so the feed renders offline / instantly next time. */
    fun prefetch(context: Context, url: String) {
        pool.execute { runCatching { file(context, url).takeIf { !it.exists() }?.writeBytes(Http.getBytes(url)) } }
    }

    private fun fetch(context: Context, url: String): Bitmap? {
        val f = file(context, url)
        if (!f.exists()) {
            val tmp = File(f.path + ".tmp")
            tmp.writeBytes(Http.getBytes(url))
            tmp.renameTo(f)
            trim(context)
        } else {
            f.setLastModified(System.currentTimeMillis())
        }
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        return BitmapFactory.decodeFile(f.path, opts)
    }

    private fun dir(context: Context) = File(context.cacheDir, "img").apply { mkdirs() }

    private fun file(context: Context, url: String): File {
        val md = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
        return File(dir(context), md.joinToString("") { "%02x".format(it) })
    }

    private fun trim(context: Context) {
        if (trimmed) return
        trimmed = true
        try {
            val files = dir(context).listFiles()?.sortedByDescending { it.lastModified() } ?: return
            var total = 0L
            for (f in files) {
                total += f.length()
                if (total > DISK_LIMIT) f.delete()
            }
        } finally {
            main.postDelayed({ trimmed = false }, 60_000)
        }
    }
}
