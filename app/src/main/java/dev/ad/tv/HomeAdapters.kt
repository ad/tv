package dev.ad.tv

import android.content.Context
import android.graphics.Outline
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

private fun View.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

/** Rounded corners for image tiles, clipped on the render thread (no bitmap work). */
private fun View.roundCorners(radiusDp: Int) {
    val r = radiusDp * resources.displayMetrics.density
    outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, r)
    }
    clipToOutline = true
}

private fun View.focusScale(scale: Float) {
    setOnFocusChangeListener { v, hasFocus ->
        v.animate().scaleX(if (hasFocus) scale else 1f).scaleY(if (hasFocus) scale else 1f).setDuration(120).start()
        v.translationZ = if (hasFocus) 8f else 0f
    }
}

class FavoritesAdapter(
    private val onClick: (String?) -> Unit,
    private val onLongClick: (String) -> Unit,
) : RecyclerView.Adapter<FavoritesAdapter.Holder>() {

    var packages: List<String> = emptyList()
        private set

    fun submit(context: Context, favorites: List<String>) {
        val next = favorites.filter { Apps.isInstalled(context, it) }
        packages = next
        notifyDataSetChanged()
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val banner: ImageView = view.findViewById(R.id.banner)
        val icon: ImageView = view.findViewById(R.id.icon)
        val label: TextView = view.findViewById(R.id.label)
        val fallback: View = view.findViewById(R.id.fallback)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_favorite, parent, false)
        v.findViewById<View>(R.id.tileClip).roundCorners(10)
        v.focusScale(1.1f)
        return Holder(v)
    }

    // An extra "+" tile when nothing is chosen yet.
    override fun getItemCount() = if (packages.isEmpty()) 1 else packages.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val ctx = holder.itemView.context
        val pkg = packages.getOrNull(position)
        if (pkg == null) {
            holder.banner.visibility = View.GONE
            holder.fallback.visibility = View.VISIBLE
            holder.icon.setImageResource(R.drawable.ic_apps)
            holder.label.text = ctx.getString(R.string.add_favorites)
            holder.itemView.setOnClickListener { onClick(null) }
            holder.itemView.setOnLongClickListener(null)
            return
        }
        val banner = Apps.banner(ctx, pkg)
        if (banner != null) {
            holder.banner.visibility = View.VISIBLE
            holder.fallback.visibility = View.GONE
            holder.banner.setImageDrawable(banner)
        } else {
            holder.banner.visibility = View.GONE
            holder.fallback.visibility = View.VISIBLE
            holder.icon.setImageDrawable(Apps.icon(ctx, pkg))
            holder.label.text = Apps.label(ctx, pkg)
        }
        holder.itemView.contentDescription = Apps.label(ctx, pkg)
        holder.itemView.setOnClickListener { onClick(pkg) }
        holder.itemView.setOnLongClickListener { onLongClick(pkg); true }
    }
}

/**
 * Feed row: the selected card is a wide backdrop with the caption below the row,
 * the rest are narrow posters. A trailing "More" card opens the list in Lampa.
 */
class FeedAdapter(
    private val onClick: (FeedItem?) -> Unit,
    private val onLongClick: (FeedItem) -> Unit,
    private val onSelected: (FeedItem?) -> Unit,
) : RecyclerView.Adapter<FeedAdapter.Holder>() {

    private var items: List<FeedItem> = emptyList()
    private var more = false
    private var selected = 0

    fun submit(list: List<FeedItem>, withMore: Boolean) {
        items = list
        more = withMore && list.isNotEmpty()
        selected = selected.coerceIn(0, (itemCount - 1).coerceAtLeast(0))
        notifyDataSetChanged()
    }

    fun selectedItem(): FeedItem? = items.getOrNull(selected)

    val selectedPosition: Int get() = selected

    fun select(position: Int) {
        selected = position
        notifyDataSetChanged()
    }

    fun selectKey(key: String) {
        val i = items.indexOfFirst { it.key == key }
        if (i >= 0) select(i)
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.image)
        val title: TextView = view.findViewById(R.id.title)
    }

    override fun getItemCount() = items.size + if (more) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_feed, parent, false)
        v.findViewById<View>(R.id.cardClip).roundCorners(10)
        return Holder(v)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val view = holder.itemView
        val item = items.getOrNull(position)
        val wide = position == selected
        view.layoutParams = view.layoutParams.apply { width = view.dp(if (wide) 300 else 113) }
        if (item == null) {
            holder.image.scaleType = ImageView.ScaleType.CENTER
            holder.image.setImageResource(R.drawable.ic_more)
            holder.image.tag = null
            holder.title.text = view.context.getString(R.string.more)
            holder.title.visibility = View.VISIBLE
        } else {
            holder.image.scaleType = ImageView.ScaleType.CENTER_CROP
            val url = if (wide) item.backdrop ?: item.poster else item.poster ?: item.backdrop
            Images.load(holder.image, url)
            // Title on the card only when there is no picture to show.
            holder.title.text = item.title
            holder.title.visibility = if (url == null) View.VISIBLE else View.GONE
        }
        view.setOnClickListener { onClick(item) }
        view.setOnLongClickListener { item?.let(onLongClick); item != null }
        view.setOnFocusChangeListener { v, hasFocus ->
            v.animate().scaleX(if (hasFocus) 1.04f else 1f).scaleY(if (hasFocus) 1.04f else 1f).setDuration(120).start()
            val pos = holder.bindingAdapterPosition
            if (hasFocus && pos != RecyclerView.NO_POSITION && pos != selected) {
                val old = selected
                selected = pos
                // Post: changing item size inside a focus callback confuses RecyclerView's layout pass.
                v.post {
                    notifyItemChanged(old)
                    notifyItemChanged(pos)
                }
                onSelected(items.getOrNull(pos))
            } else if (hasFocus) {
                onSelected(items.getOrNull(pos))
            }
        }
    }
}
