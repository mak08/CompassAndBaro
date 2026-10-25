package net.bitsailor.comparo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

public class CompassView extends View {
    private static final String[] CARDINALS = {"N", "E", "S", "W"};

    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint north = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pointer = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path pointerPath = new Path();
    private final Path markerPath = new Path();
    private float azimuth;
    private boolean hasData;

    public CompassView(Context context, AttributeSet attrs) {
        super(context, attrs);
        int fg = 0xFFEAF1F7;
        float density = getResources().getDisplayMetrics().density;
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2 * density);
        ring.setColor(fg);
        tick.setColor(fg);
        tick.setStrokeWidth(2 * density);
        text.setColor(fg);
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
        north.setColor(0xFFE5484D);
        north.setTextAlign(Paint.Align.CENTER);
        north.setFakeBoldText(true);
        pointer.setColor(0xFFC9A227);
    }

    /** @param degrees heading of the device in degrees clockwise from north */
    public void setAzimuth(float degrees) {
        azimuth = degrees;
        hasData = true;
        invalidate();
    }

    /** Triangle with its base on the ring at (x, y) and its tip pointing towards the centre. */
    private void drawMarker(Canvas canvas, float x, float y, float height, float width, Paint paint) {
        markerPath.reset();
        markerPath.moveTo(x - width / 2f, y);
        markerPath.lineTo(x + width / 2f, y);
        markerPath.lineTo(x, y + height);
        markerPath.close();
        canvas.drawPath(markerPath, paint);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = Math.min(cx, cy) * 0.88f;
        float unit = r / 10f;

        text.setTextSize(unit * 1.8f);
        north.setTextSize(unit * 1.8f);

        canvas.drawCircle(cx, cy, r, ring);

        canvas.save();
        canvas.rotate(-azimuth, cx, cy);
        for (int deg = 0; deg < 360; deg += 5) {
            canvas.save();
            canvas.rotate(deg, cx, cy);
            if (deg % 90 == 0) {
                // Cardinal triangle: same height as a major tick.
                drawMarker(canvas, cx, cy - r, unit * 0.9f, unit * 0.9f, deg == 0 ? north : tick);
            } else if (deg % 45 == 0) {
                drawMarker(canvas, cx, cy - r, unit * 0.6f, unit * 0.6f, tick);
            } else {
                boolean major = deg % 30 == 0;
                float len = major ? unit * 0.9f : unit * 0.45f;
                canvas.drawLine(cx, cy - r, cx, cy - r + len, tick);
            }
            canvas.restore();
        }
        for (int i = 0; i < 4; i++) {
            canvas.save();
            canvas.rotate(i * 90, cx, cy);
            Paint p = i == 0 ? north : text;
            canvas.drawText(CARDINALS[i], cx, cy - r + unit * 3.4f, p);
            canvas.restore();
        }
        canvas.restore();

        // Fixed pointer showing the heading direction.
        pointerPath.reset();
        pointerPath.moveTo(cx, cy - r - unit * 0.1f);
        pointerPath.lineTo(cx - unit * 0.7f, cy - r - unit * 1.1f);
        pointerPath.lineTo(cx + unit * 0.7f, cy - r - unit * 1.1f);
        pointerPath.close();
        canvas.drawPath(pointerPath, pointer);

        text.setTextSize(unit * 2.6f);
        String label = hasData
                ? Math.round((azimuth % 360 + 360) % 360) % 360 + "\u00B0"
                : "--";
        canvas.drawText(label, cx, cy + unit * 1f, text);
    }
}
