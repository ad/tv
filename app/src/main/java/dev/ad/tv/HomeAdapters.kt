package dev.ad.tv

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Outline
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Mouse support: hovering moves focus (so selection follows the pointer like the remote),
 * right click does what a long press does.
 */
fun View.mouseFriendly(hoverFocus: Boolean = true, onContextClick: (() -> Unit)? = null) {
    setOnHoverListener { v, e ->
        // Only fully visible views: focusing a partly hidden one scrolls the list under the pointer.
        if (hoverFocus && e.actionMasked == android.view.MotionEvent.ACTION_HOVER_ENTER && !v.isFocused && v.fullyVisible()) {
            v.requestFocus()
        }
        false
    }
    setOnContextClickListener { onContextClick?.invoke(); onContextClick != null }
}

private fun View.fullyVisible(): Boolean {
    val r = android.graphics.Rect()
    return getGlobalVisibleRect(r) && r.width() >= width && r.height() >= height
}

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
            holder.itemView.mouseFriendly()
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
        holder.itemView.mouseFriendly(onContextClick = { onLongClick(pkg) })
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
    private var rv: RecyclerView? = null
    private var animator: ValueAnimator? = null
    /** In-flight widths while a selection change animates, by adapter position. */
    private val widths = HashMap<Int, Int>()

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
        // Rounded outline on the root too, so the focus shadow follows the card's corners.
        v.outlineProvider = v.findViewById<View>(R.id.cardClip).outlineProvider
        return Holder(v)
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        rv = recyclerView
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val view = holder.itemView
        val item = items.getOrNull(position)
        view.layoutParams = view.layoutParams.apply { width = widths[position] ?: view.dp(if (position == selected) WIDE else NARROW) }
        view.alpha = if (position == selected) 1f else DIMMED
        bindImage(holder, item, position == selected)
        view.setOnClickListener {
            // A mouse/touch click on a card that is not selected selects it; the remote always clicks the selected one.
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
            if (pos != selected) {
                animateSelection(selected, pos)
                onSelected(items.getOrNull(pos))
                it.requestFocus()
            } else {
                onClick(item)
            }
        }
        view.setOnLongClickListener { item?.let(onLongClick); item != null }
        // No hover focus here: the selected card slides away, which would select the next one under the pointer.
        view.mouseFriendly(hoverFocus = false, onContextClick = item?.let { { onLongClick(it) } })
        view.setOnFocusChangeListener { v, hasFocus ->
            v.pivotX = 0f
            v.pivotY = v.height / 2f
            // No frame: the selected card is the bright, wide one; focus adds a lift and a shadow.
            v.animate().scaleX(if (hasFocus) 1.03f else 1f).scaleY(if (hasFocus) 1.03f else 1f)
                .translationZ(if (hasFocus) v.dp(12).toFloat() else 0f).setDuration(150).start()
            val pos = holder.bindingAdapterPosition
            if (!hasFocus || pos == RecyclerView.NO_POSITION) return@setOnFocusChangeListener
            if (pos != selected) {
                // Post: resizing items inside a focus callback confuses RecyclerView's layout pass.
                v.post { animateSelection(selected, pos) }
            }
            onSelected(items.getOrNull(pos))
        }
    }

    private fun bindImage(holder: Holder, item: FeedItem?, wide: Boolean) {
        if (item == null) {
            holder.image.scaleType = ImageView.ScaleType.CENTER
            holder.image.setImageResource(R.drawable.ic_more)
            holder.image.tag = null
            holder.title.text = holder.itemView.context.getString(R.string.more)
            holder.title.visibility = View.VISIBLE
            return
        }
        holder.image.scaleType = ImageView.ScaleType.CENTER_CROP
        val url = if (wide) item.backdrop ?: item.poster else item.poster ?: item.backdrop
        Images.load(holder.image, url)
        // Title on the card only when there is no picture to show.
        holder.title.text = item.title
        holder.title.visibility = if (url == null) View.VISIBLE else View.GONE
    }

    /**
     * The old card narrows and the new one widens while the new one slides to the left edge,
     * all driven by one animator so the row never jumps.
     */
    private fun animateSelection(old: Int, new: Int) {
        val list = rv ?: return
        val lm = list.layoutManager as? LinearLayoutManager ?: return
        if (old == new) return
        animator?.end()
        selected = new
        val wide = list.dp(WIDE)
        val narrow = list.dp(NARROW)
        val startOffset = (list.findViewHolderForAdapterPosition(new)?.itemView?.left ?: list.paddingLeft) - list.paddingLeft
        (list.findViewHolderForAdapterPosition(old) as? Holder)?.let { bindImage(it, items.getOrNull(old), false) }
        (list.findViewHolderForAdapterPosition(new) as? Holder)?.let { bindImage(it, items.getOrNull(new), true) }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                widths[old] = (wide + (narrow - wide) * f).toInt()
                widths[new] = (narrow + (wide - narrow) * f).toInt()
                for (pos in intArrayOf(old, new)) {
                    list.findViewHolderForAdapterPosition(pos)?.itemView?.let { v ->
                        v.layoutParams = v.layoutParams.apply { width = widths[pos]!! }
                        v.alpha = if (pos == new) DIMMED + (1 - DIMMED) * f else 1 - (1 - DIMMED) * f
                    }
                }
                lm.scrollToPositionWithOffset(new, (startOffset * (1 - f)).toInt())
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    widths.clear()
                }
            })
            start()
        }
    }

    private companion object {
        const val WIDE = 300
        const val NARROW = 113
        const val DIMMED = 0.7f
    }
}
