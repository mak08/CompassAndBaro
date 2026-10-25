package net.bitsailor.comparo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.text.format.DateFormat;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Pressure history graph with pressure (left), height (right) and time (bottom) scales. */
public class PressureGraphView extends View {
    private static final long MIN = 60_000L;
    private static final long[] TIME_STEPS = {
            5 * MIN, 10 * MIN, 15 * MIN, 30 * MIN, 60 * MIN, 2 * 60 * MIN,
            3 * 60 * MIN, 4 * 60 * MIN, 6 * 60 * MIN};
    private static final double MIN_SPAN_PA = 400.0;

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float density;

    private List<PressureHistory.Sample> samples = new ArrayList<>();
    private Prefs.PressureUnit unit = Prefs.PressureUnit.PA;
    private boolean imperial;
    private float seaLevelPa = Prefs.STANDARD_SEA_LEVEL_PA;
    private long windowMs = 6 * 60 * MIN;

    public PressureGraphView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        int fg = 0xFFEAF1F7;
        int accent = 0xFF2EC4B6;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2 * density);
        line.setColor(accent);
        grid.setColor(fg);
        grid.setAlpha(45);
        grid.setStrokeWidth(density);
        text.setColor(fg);
        text.setTextSize(10 * density);
    }

    public void setData(List<PressureHistory.Sample> samples, Prefs prefs) {
        this.samples = samples;
        this.unit = prefs.getPressureUnit();
        this.imperial = prefs.isImperial();
        this.seaLevelPa = prefs.getReferencePa();
        this.windowMs = prefs.getHistoryWindowMs();
        invalidate();
    }

    private double heightM(double pa) {
        return 44330.0 * (1.0 - Math.pow(pa / seaLevelPa, 1.0 / 5.255));
    }

    private double paAtHeight(double m) {
        return seaLevelPa * Math.pow(1.0 - m / 44330.0, 5.255);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float left = 54 * density;
        float right = getWidth() - 46 * density;
        float top = 8 * density;
        float bottom = getHeight() - 18 * density;
        if (right <= left || bottom <= top) {
            return;
        }

        long now = System.currentTimeMillis();
        long t0 = now - windowMs;

        double minPa = Double.MAX_VALUE;
        double maxPa = -Double.MAX_VALUE;
        for (PressureHistory.Sample s : samples) {
            if (s.timeMs >= t0) {
                minPa = Math.min(minPa, s.pa);
                maxPa = Math.max(maxPa, s.pa);
            }
        }

        drawTimeAxis(canvas, left, right, top, bottom, t0, now);

        if (maxPa < minPa) {
            text.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(getContext().getString(R.string.collecting_data),
                    (left + right) / 2f, (top + bottom) / 2f, text);
            return;
        }
        if (maxPa - minPa < MIN_SPAN_PA) {
            double mid = (maxPa + minPa) / 2;
            minPa = mid - MIN_SPAN_PA / 2;
            maxPa = mid + MIN_SPAN_PA / 2;
        }
        double pad = (maxPa - minPa) * 0.05;
        minPa -= pad;
        maxPa += pad;

        // Pressure grows downwards so that the height (right) scale grows upwards.
        // Left axis: pressure, linear in the display unit.
        double vMin = unit.fromPa(minPa);
        double vMax = unit.fromPa(maxPa);
        double step = Math.max(niceStep((vMax - vMin) / 4), unit.minScaleStep);
        text.setTextAlign(Paint.Align.RIGHT);
        for (double v = Math.ceil(vMin / step) * step; v <= vMax; v += step) {
            float y = (float) (top + (bottom - top) * (v - vMin) / (vMax - vMin));
            canvas.drawLine(left, y, right, y, grid);
            canvas.drawText(formatTick(v, step), left - 4 * density, y + 4 * density, text);
        }

        // Right axis: height derived from pressure.
        double hMin = heightM(maxPa);
        double hMax = heightM(minPa);
        double k = imperial ? 1 / 0.3048 : 1.0;
        double hStep = niceStep((hMax - hMin) * k / 4);
        text.setTextAlign(Paint.Align.LEFT);
        for (double h = Math.ceil(hMin * k / hStep) * hStep; h <= hMax * k; h += hStep) {
            double pa = paAtHeight(h / k);
            float y = (float) (top + (bottom - top) * (pa - minPa) / (maxPa - minPa));
            canvas.drawText(formatTick(h, hStep), right + 4 * density, y + 4 * density, text);
        }

        path.reset();
        boolean pen = false;
        long prev = 0;
        for (PressureHistory.Sample s : samples) {
            if (s.timeMs < t0) {
                continue;
            }
            float x = left + (right - left) * (s.timeMs - t0) / (float) windowMs;
            float y = (float) (top + (bottom - top) * (s.pa - minPa) / (maxPa - minPa));
            // Break the line where the app was not recording.
            if (!pen || s.timeMs - prev > 1.5 * PressureHistory.BACKGROUND_INTERVAL_MS) {
                path.moveTo(x, y);
                pen = true;
            } else {
                path.lineTo(x, y);
            }
            prev = s.timeMs;
        }
        canvas.save();
        canvas.clipRect(left, top, right, bottom);
        canvas.drawPath(path, line);
        canvas.restore();
    }

    private void drawTimeAxis(Canvas canvas, float left, float right, float top, float bottom,
                              long t0, long now) {
        long step = TIME_STEPS[TIME_STEPS.length - 1];
        for (long s : TIME_STEPS) {
            if (windowMs / s <= 6) {
                step = s;
                break;
            }
        }
        long offset = TimeZone.getDefault().getOffset(now);
        java.text.DateFormat fmt = DateFormat.getTimeFormat(getContext());
        text.setTextAlign(Paint.Align.CENTER);
        long first = Math.floorDiv(t0 + offset + step - 1, step) * step - offset;
        for (long t = first; t <= now; t += step) {
            float x = left + (right - left) * (t - t0) / (float) windowMs;
            canvas.drawLine(x, top, x, bottom, grid);
            canvas.drawText(fmt.format(new Date(t)), x, bottom + 13 * density, text);
        }
        canvas.drawLine(left, bottom, right, bottom, grid);
    }

    /** Rounds a raw step up to 1, 2 or 5 times a power of ten. */
    private static double niceStep(double raw) {
        double pow = Math.pow(10, Math.floor(Math.log10(raw)));
        double f = raw / pow;
        double nice = f <= 1 ? 1 : f <= 2 ? 2 : f <= 5 ? 5 : 10;
        return nice * pow;
    }

    private static String formatTick(double v, double step) {
        int decimals = Math.max(0, (int) -Math.floor(Math.log10(step) + 1e-9));
        return String.format(Locale.US, "%." + decimals + "f", v);
    }
}
