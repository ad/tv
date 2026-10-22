package dev.ad.tv

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.recyclerview.widget.RecyclerView

/**
 * When focus enters from outside, it lands on [entryPosition] instead of the geometrically
 * nearest child — so moving up into the feed lands on the selected (wide) card.
 */
class EntryFocusRecyclerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : RecyclerView(context, attrs) {
    var entryPosition: () -> Int = { 0 }

    override fun addFocusables(views: ArrayList<View>, direction: Int, focusableMode: Int) {
        if (!hasFocus()) {
            val entry = findViewHolderForAdapterPosition(entryPosition())?.itemView
            if (entry != null && entry.isFocusable) {
                views.add(entry)
                return
            }
        }
        super.addFocusables(views, direction, focusableMode)
    }
}
