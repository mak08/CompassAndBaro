package net.bitsailor.compbaro;

import android.app.ActivityOptions;
import android.content.Intent;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

public class MainActivity extends AppCompatActivity
        implements SensorEventListener, HeightCalibrator.Listener {
    private static final float PRESSURE_SMOOTHING = 0.2f;
    private static final float AZIMUTH_SMOOTHING = 0.15f;

    private SensorManager sensorManager;
    private Sensor rotationSensor;
    private Sensor pressureSensor;
    private Prefs prefs;
    private GestureDetector gestures;
    private float downX;
    private float downY;
    private boolean gestureCancelled;

    private CompassView compass;
    private PressureGraphView graph;
    private TextView heightView;
    private TextView pressureView;
    private TextView heightUnitView;
    private TextView pressureUnitView;
    private TextView baroStatusView;
    private TextView compassStatusView;
    private TextView calibrationStatusView;
    private HeightCalibrator calibrator;
    private boolean hasPressureData;
    private int compassAccuracy = -1;

    private final float[] rotation = new float[9];
    private final float[] remapped = new float[9];
    private final float[] orientation = new float[3];
    private float azimuth;
    private boolean azimuthInitialized;

    private float smoothedPa = Float.NaN;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        applyInsets();
        prefs = new Prefs(this);
        if (prefs.isTrueNorthEnabled() && !HeightCalibrator.hasLocationPermission(this)
                && prefs.consumeFirstLocationPrompt()) {
            requestPermissions(new String[]{
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION}, 1);
        }
        compass = findViewById(R.id.compass);
        graph = findViewById(R.id.graph);
        heightView = findViewById(R.id.height_value);
        pressureView = findViewById(R.id.pressure_value);
        heightUnitView = findViewById(R.id.height_unit);
        pressureUnitView = findViewById(R.id.pressure_unit);
        baroStatusView = findViewById(R.id.status_baro);
        compassStatusView = findViewById(R.id.status_compass);
        calibrationStatusView = findViewById(R.id.status_calibration);

        calibrator = new HeightCalibrator(this, prefs, this);
        sensorManager = getSystemService(SensorManager.class);
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE);

        compass.setOnClickListener(v -> {
            prefs.setTrueNorthEnabled(!prefs.isTrueNorthEnabled());
            updateDeclination();
        });
        findViewById(R.id.height_panel).setOnClickListener(v -> {
            prefs.setImperial(!prefs.isImperial());
            refreshReadings();
        });
        findViewById(R.id.pressure_panel).setOnClickListener(v -> {
            Prefs.PressureUnit[] units = Prefs.PressureUnit.values();
            prefs.setPressureUnit(units[(prefs.getPressureUnit().ordinal() + 1) % units.length]);
            refreshReadings();
        });
        graph.setOnClickListener(v -> {
            prefs.setPressureUp(!prefs.isPressureUp());
            refreshGraph();
        });

        gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null) {
                    return false;
                }
                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();
                if (dx < -150 && Math.abs(dx) > 2 * Math.abs(dy) && Math.abs(vx) > 800) {
                    openSettings();
                    return true;
                }
                return false;
            }
        });
    }

    /** Extends the navy title bar under the status bar so the system icons stay legible. */
    private void applyInsets() {
        View root = findViewById(R.id.root);
        View title = findViewById(R.id.title_bar);
        final int titleHeight = title.getLayoutParams().height;
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            title.setPadding(bars.left, bars.top, bars.right, 0);
            ViewGroup.LayoutParams lp = title.getLayoutParams();
            lp.height = titleHeight + bars.top;
            title.setLayoutParams(lp);
            v.setPadding(0, 0, 0, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        WindowCompat.getInsetsController(getWindow(), root).setAppearanceLightStatusBars(false);
    }

    private void openSettings() {
        ActivityOptions options = ActivityOptions.makeCustomAnimation(
                this, R.anim.slide_in_right, R.anim.slide_out_left);
        startActivity(new Intent(this, SettingsActivity.class), options.toBundle());
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        gestures.onTouchEvent(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                gestureCancelled = false;
                break;
            case MotionEvent.ACTION_MOVE:
                // A drag is a swipe, not a tap: cancel the pressed view so it does not click.
                if (!gestureCancelled && Math.hypot(ev.getX() - downX, ev.getY() - downY)
                        > ViewConfiguration.get(this).getScaledTouchSlop()) {
                    gestureCancelled = true;
                    MotionEvent cancel = MotionEvent.obtain(ev);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(cancel);
                    cancel.recycle();
                }
                break;
            default:
                break;
        }
        if (gestureCancelled) {
            return true;
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (prefs.isBackgroundRecording()) {
            PressureRecordingService.start(this);
        }
        if (rotationSensor != null) {
            sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME);
        }
        if (pressureSensor != null) {
            sensorManager.registerListener(this, pressureSensor, SensorManager.SENSOR_DELAY_NORMAL);
        } else {
            pressureView.setText(R.string.no_sensor);
            heightView.setText(R.string.no_sensor);
        }
        updateDeclination();
        smoothedPa = Float.NaN;
        hasPressureData = false;
        calibrator.reset();
        refreshUnits();
        refreshGraph();
        refreshStatus();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        updateDeclination();
    }

    private void updateDeclination() {
        if (!prefs.isTrueNorthEnabled()) {
            compass.setDeclination(Float.NaN);
            return;
        }
        compass.setDeclination(prefs.getDeclination());
        if (!HeightCalibrator.hasLocationPermission(this)) {
            return;
        }
        LocationManager lm = getSystemService(LocationManager.class);
        Location best = null;
        try {
            for (String provider : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(provider);
                if (l != null && (best == null || l.getTime() > best.getTime())) {
                    best = l;
                }
            }
            if (best != null) {
                applyDeclination(best);
            } else if (lm.isProviderEnabled(LocationManager.FUSED_PROVIDER)) {
                lm.getCurrentLocation(LocationManager.FUSED_PROVIDER, null, getMainExecutor(),
                        location -> {
                            if (location != null && prefs.isTrueNorthEnabled()) {
                                applyDeclination(location);
                            }
                        });
            }
        } catch (SecurityException | IllegalArgumentException ignored) {
            // No usable location: keep the last known declination, if any.
        }
    }

    private void applyDeclination(Location location) {
        float declination = new GeomagneticField((float) location.getLatitude(),
                (float) location.getLongitude(),
                location.hasAltitude() ? (float) location.getAltitude() : 0f,
                System.currentTimeMillis()).getDeclination();
        prefs.setDeclination(declination);
        compass.setDeclination(declination);
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensorManager.unregisterListener(this);
        calibrator.reset();
        PressureHistory.save(this);
    }

    @Override
    public void onCalibrationStatusChanged() {
        refreshGraph();
        refreshStatus();
    }

    private static CharSequence indicator(int color, String label) {
        SpannableString s = new SpannableString("\u25CF " + label);
        s.setSpan(new ForegroundColorSpan(color), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    private void refreshStatus() {
        int green = 0xFF43A047;
        int amber = 0xFFFFB300;
        int red = 0xFFE53935;

        if (pressureSensor == null) {
            baroStatusView.setText(indicator(red, getString(R.string.baro_missing)));
        } else if (!hasPressureData) {
            baroStatusView.setText(indicator(amber, getString(R.string.baro_waiting)));
        } else {
            baroStatusView.setText(indicator(green, getString(R.string.baro_ok)));
        }

        if (rotationSensor == null) {
            compassStatusView.setText(indicator(red, getString(R.string.compass_missing)));
        } else {
            switch (compassAccuracy) {
                case SensorManager.SENSOR_STATUS_ACCURACY_HIGH:
                    compassStatusView.setText(indicator(green, getString(R.string.compass_high)));
                    break;
                case SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM:
                    compassStatusView.setText(indicator(amber, getString(R.string.compass_medium)));
                    break;
                case SensorManager.SENSOR_STATUS_ACCURACY_LOW:
                    compassStatusView.setText(indicator(0xFFFB8C00, getString(R.string.compass_low)));
                    break;
                case SensorManager.SENSOR_STATUS_UNRELIABLE:
                case SensorManager.SENSOR_STATUS_NO_CONTACT:
                    compassStatusView.setText(indicator(red, getString(R.string.compass_unreliable)));
                    break;
                default:
                    compassStatusView.setText(indicator(amber, getString(R.string.compass_waiting)));
            }
        }
        calibrationStatusView.setText(calibrator.getStatus());
    }

    private void refreshUnits() {
        heightUnitView.setText(prefs.isImperial() ? R.string.unit_ft_long : R.string.unit_m_long);
        pressureUnitView.setText(prefs.getPressureUnit().label);
    }

    private void refreshReadings() {
        refreshUnits();
        if (!Float.isNaN(smoothedPa)) {
            pressureView.setText(prefs.getPressureUnit().formatValue(smoothedPa));
            float height = SensorManager.getAltitude(prefs.getReferencePa() / 100f, smoothedPa / 100f);
            heightView.setText(prefs.formatHeight(height));
        }
        refreshGraph();
    }

    private void refreshGraph() {
        graph.setData(PressureHistory.snapshot(this), prefs);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
            setCompassAccuracy(event.accuracy);
            updateCompass(event.values);
        } else if (event.sensor.getType() == Sensor.TYPE_PRESSURE) {
            updatePressure(event.values[0] * 100f);
        }
    }

    private void updateCompass(float[] values) {
        SensorManager.getRotationMatrixFromVector(rotation, values);
        float[] m = rotation;
        // Device lying flat: use the top edge. Held upright: use the back camera direction.
        if (Math.abs(rotation[8]) < 0.7f) {
            SensorManager.remapCoordinateSystem(rotation, SensorManager.AXIS_X,
                    SensorManager.AXIS_Z, remapped);
            m = remapped;
        }
        SensorManager.getOrientation(m, orientation);
        float target = (float) Math.toDegrees(orientation[0]);
        if (!azimuthInitialized) {
            azimuth = target;
            azimuthInitialized = true;
        } else {
            float diff = ((target - azimuth + 540f) % 360f) - 180f;
            azimuth += diff * AZIMUTH_SMOOTHING;
        }
        compass.setAzimuth(azimuth);
    }

    private void updatePressure(float pa) {
        smoothedPa = Float.isNaN(smoothedPa)
                ? pa : smoothedPa + PRESSURE_SMOOTHING * (pa - smoothedPa);

        calibrator.onPressure(smoothedPa);
        float seaLevelPa = prefs.getReferencePa();
        float height = SensorManager.getAltitude(seaLevelPa / 100f, smoothedPa / 100f);

        if (!hasPressureData) {
            hasPressureData = true;
            refreshStatus();
        }
        pressureView.setText(prefs.getPressureUnit().formatValue(smoothedPa));
        heightView.setText(prefs.formatHeight(height));

        if (PressureHistory.add(this, System.currentTimeMillis(), smoothedPa,
                PressureHistory.INTERVAL_MS)) {
            refreshGraph();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        if (sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
            setCompassAccuracy(accuracy);
        }
    }

    private void setCompassAccuracy(int accuracy) {
        if (accuracy != compassAccuracy) {
            compassAccuracy = accuracy;
            refreshStatus();
        }
    }
}
