package dev.ad.tv;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;

// Hides trailing children that do not fit; plain LinearLayout squeezes the last one instead.
public class FitRow extends LinearLayout {
    public FitRow(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int room = MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED ? Integer.MAX_VALUE
                : MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
        int unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            child.measure(unspecified, unspecified);
            MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();
            room -= child.getMeasuredWidth() + lp.leftMargin + lp.rightMargin;
            child.setVisibility(room >= 0 ? VISIBLE : GONE);
        }
        super.onMeasure(widthSpec, heightSpec);
    }
}
