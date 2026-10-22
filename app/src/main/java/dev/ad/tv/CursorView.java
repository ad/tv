package dev.ad.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

final class CursorView extends View {
    float cx = 0f;
    float cy = 0f;
    private final Path arrow = new Path();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean placed = false;

    CursorView(Context context) {
        super(context);
        float d = getResources().getDisplayMetrics().density;
        arrow.moveTo(0f, 0f);
        arrow.lineTo(0f, 22f * d);
        arrow.lineTo(6f * d, 17f * d);
        arrow.lineTo(10f * d, 26f * d);
        arrow.lineTo(14f * d, 24f * d);
        arrow.lineTo(10f * d, 15.5f * d);
        arrow.lineTo(17f * d, 15.5f * d);
        arrow.close();
        fill.setColor(Color.WHITE);
        fill.setShadowLayer(4 * d, 0f, d, 0x99000000);
        stroke.setColor(Color.BLACK);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1.5f * d);
        setFocusable(false);
        setClickable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        if (!placed && w > 0 && h > 0) {
            cx = w / 2f;
            cy = h / 2f;
            placed = true;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.save();
        canvas.translate(cx, cy);
        canvas.drawPath(arrow, fill);
        canvas.drawPath(arrow, stroke);
        canvas.restore();
    }
}
