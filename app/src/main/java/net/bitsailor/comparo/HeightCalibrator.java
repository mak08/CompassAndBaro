package net.bitsailor.comparo;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Date;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Determines the sea-level reference pressure for the height calculation according to the
 * selected {@link Prefs.HeightMode}. All methods must be called on the main thread.
 */
public class HeightCalibrator {
    private static final long GPS_INTERVAL_MS = 10 * 60_000L;
    private static final long WEATHER_INTERVAL_MS = 30 * 60_000L;
    private static final long RETRY_MS = 2 * 60_000L;
    private static final long FIX_TIMEOUT_MS = 30_000L;
    private static final float MAX_VERTICAL_ERROR_M = 50f;
    private static final float GPS_BLEND = 0.3f;

    public interface Listener {
        void onCalibrationStatusChanged();
    }

    private final Context context;
    private final Prefs prefs;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private float latestPa = Float.NaN;
    private boolean busy;
    // True until the calibration for this app start (or an explicit request) has succeeded.
    private boolean pending = true;
    private long nextAttemptMs;
    private String status = "";
    private CancellationSignal cancel;

    public HeightCalibrator(Context context, Prefs prefs, Listener listener) {
        this.context = context.getApplicationContext();
        this.prefs = prefs;
        this.listener = listener;
    }

    public static boolean hasLocationPermission(Context context) {
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    public String getStatus() {
        return status;
    }

    /** Forget pending work, e.g. when the mode changed or the activity is paused. */
    public void reset() {
        if (cancel != null) {
            cancel.cancel();
            cancel = null;
        }
        busy = false;
        nextAttemptMs = 0;
    }

    /** Called for every (smoothed) pressure reading in Pa. */
    public void onPressure(float pa) {
        latestPa = pa;
        Prefs.HeightMode mode = prefs.getHeightMode();
        String previous = status;
        if (prefs.consumeCalibrationRequest()) {
            pending = true;
            nextAttemptMs = 0;
        }
        switch (mode) {
            case STANDARD:
                pending = false;
                status = context.getString(R.string.status_standard);
                break;
            case MANUAL:
                if (pending || prefs.needsManualCalibration()) {
                    pending = false;
                    double h = prefs.getManualHeightMeters();
                    prefs.storeReference((float) seaLevelFor(pa, h), h, System.currentTimeMillis());
                }
                status = context.getString(R.string.status_manual, prefs.formatHeight(
                        prefs.getManualHeightMeters()), unitLabel());
                break;
            case GPS:
            case WEATHER:
                updateRemote(mode);
                break;
        }
        if (!status.equals(previous)) {
            listener.onCalibrationStatusChanged();
        }
    }

    private void updateRemote(Prefs.HeightMode mode) {
        if (!hasLocationPermission(context)) {
            status = context.getString(R.string.status_no_permission);
            return;
        }
        long now = System.currentTimeMillis();
        boolean continuous = prefs.getCalibrationTiming() == Prefs.CalibrationTiming.CONTINUOUS;
        if (!busy && now >= nextAttemptMs
                && (continuous || pending || !prefs.hasReference())) {
            busy = true;
            if (mode == Prefs.HeightMode.GPS) {
                requestFix(LocationManager.GPS_PROVIDER, this::onGpsFix);
            } else {
                requestFix(LocationManager.FUSED_PROVIDER, this::onWeatherLocation);
            }
        }
        if (busy) {
            status = context.getString(mode == Prefs.HeightMode.GPS
                    ? R.string.status_gps_wait : R.string.status_weather_wait);
        } else if (prefs.hasReference()) {
            status = context.getString(mode == Prefs.HeightMode.GPS
                            ? R.string.status_gps_ok : R.string.status_weather_ok,
                    DateFormat.getTimeFormat(context).format(new Date(prefs.getReferenceTimeMs())));
        }
    }

    private void requestFix(String provider, Consumer<Location> callback) {
        LocationManager lm = context.getSystemService(LocationManager.class);
        cancel = new CancellationSignal();
        final CancellationSignal signal = cancel;
        Consumer<Location> wrapped = location -> {
            if (signal.isCanceled()) {
                return;
            }
            cancel = null;
            if (location == null && !provider.equals(LocationManager.GPS_PROVIDER)) {
                location = lastKnown(lm);
            }
            callback.accept(location);
        };
        try {
            if (!lm.isProviderEnabled(provider)) {
                wrapped.accept(null);
                return;
            }
            lm.getCurrentLocation(provider, signal, context.getMainExecutor(), wrapped::accept);
            handler.postDelayed(signal::cancel, FIX_TIMEOUT_MS);
            handler.postDelayed(() -> {
                if (busy && cancel == signal) {
                    cancel = null;
                    callback.accept(provider.equals(LocationManager.GPS_PROVIDER)
                            ? null : lastKnown(lm));
                }
            }, FIX_TIMEOUT_MS + 500);
        } catch (SecurityException | IllegalArgumentException e) {
            wrapped.accept(null);
        }
    }

    private Location lastKnown(LocationManager lm) {
        Location best = null;
        try {
            for (String p : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) {
                    best = l;
                }
            }
        } catch (SecurityException ignored) {
            // Permission revoked meanwhile.
        }
        return best;
    }

    private void onGpsFix(Location location) {
        busy = false;
        long now = System.currentTimeMillis();
        if (location == null || !location.hasAltitude()
                || (location.hasVerticalAccuracy()
                && location.getVerticalAccuracyMeters() > MAX_VERTICAL_ERROR_M)
                || Float.isNaN(latestPa)) {
            nextAttemptMs = now + RETRY_MS;
            status = context.getString(R.string.status_gps_failed);
            listener.onCalibrationStatusChanged();
            return;
        }
        double altitude = location.getAltitude();
        if (Build.VERSION.SDK_INT >= 34 && location.hasMslAltitude()) {
            altitude = location.getMslAltitudeMeters();
        }
        float p0 = (float) seaLevelFor(latestPa, altitude);
        pending = false;
        boolean continuous = prefs.getCalibrationTiming() == Prefs.CalibrationTiming.CONTINUOUS;
        boolean recent = continuous && prefs.hasReference() && now - prefs.getReferenceTimeMs() < 60 * 60_000L;
        if (recent) {
            p0 = prefs.getReferencePa() + GPS_BLEND * (p0 - prefs.getReferencePa());
        }
        prefs.storeReference(p0, altitude, now);
        nextAttemptMs = now + GPS_INTERVAL_MS;
        listener.onCalibrationStatusChanged();
    }

    private void onWeatherLocation(Location location) {
        if (location == null) {
            failWeather();
            return;
        }
        final double lat = location.getLatitude();
        final double lon = location.getLongitude();
        new Thread(() -> {
            Float qnh = fetchSeaLevelPressure(lat, lon);
            handler.post(() -> onWeatherResult(qnh));
        }).start();
    }

    private void onWeatherResult(Float qnhPa) {
        if (!busy) {
            return; // reset() was called meanwhile
        }
        long now = System.currentTimeMillis();
        if (qnhPa == null) {
            failWeather();
            return;
        }
        busy = false;
        pending = false;
        double height = 44330.0 * (1.0 - Math.pow(latestPa / qnhPa, 1.0 / 5.255));
        prefs.storeReference(qnhPa, Float.isNaN(latestPa) ? 0 : height, now);
        nextAttemptMs = now + WEATHER_INTERVAL_MS;
        listener.onCalibrationStatusChanged();
    }

    private void failWeather() {
        busy = false;
        nextAttemptMs = System.currentTimeMillis() + RETRY_MS;
        status = context.getString(R.string.status_weather_failed);
        listener.onCalibrationStatusChanged();
    }

    /** Fetches the current mean-sea-level pressure in Pa from Open-Meteo (no API key needed). */
    private static Float fetchSeaLevelPressure(double lat, double lon) {
        HttpURLConnection c = null;
        try {
            URL url = new URL(String.format(Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f"
                            + "&current=pressure_msl", lat, lon));
            c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(10_000);
            c.setReadTimeout(10_000);
            if (c.getResponseCode() != 200) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line);
                }
            }
            double hpa = new JSONObject(sb.toString()).getJSONObject("current")
                    .getDouble("pressure_msl");
            return (hpa > 800 && hpa < 1100) ? (float) (hpa * 100) : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    /** International barometric formula, solved for sea-level pressure. */
    static double seaLevelFor(double pa, double heightM) {
        return pa / Math.pow(1.0 - heightM / 44330.0, 5.255);
    }

    private String unitLabel() {
        return context.getString(prefs.isImperial() ? R.string.unit_ft : R.string.unit_m);
    }
}
