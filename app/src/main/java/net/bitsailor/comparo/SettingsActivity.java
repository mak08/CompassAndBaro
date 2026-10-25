package net.bitsailor.comparo;

import android.os.Bundle;
import android.text.InputType;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.Locale;

public class SettingsActivity extends AppCompatActivity {
    private Prefs prefs;
    private EditText manualHeight;
    private TextView manualHeightUnit;
    private RadioGroup heightUnitGroup;
    private android.widget.CompoundButton recordingSwitch;
    private GestureDetector gestures;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (e1 == null) {
                    return false;
                }
                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();
                if (dx > 150 && dx > 2 * Math.abs(dy) && velocityX > 800) {
                    finish();
                    return true;
                }
                return false;
            }
        });
        prefs = new Prefs(this);
        androidx.core.view.WindowCompat.getInsetsController(getWindow(), findViewById(android.R.id.content))
                .setAppearanceLightStatusBars(true);

        RadioGroup calibration = findViewById(R.id.calibration_group);
        manualHeight = findViewById(R.id.manual_height);
        manualHeightUnit = findViewById(R.id.manual_height_unit);
        heightUnitGroup = findViewById(R.id.height_unit_group);
        RadioGroup pressureGroup = findViewById(R.id.pressure_unit_group);

        manualHeight.setInputType(InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);

        switch (prefs.getHeightMode()) {
            case MANUAL:
                calibration.check(R.id.calibration_manual);
                break;
            case GPS:
                calibration.check(R.id.calibration_gps);
                break;
            case WEATHER:
                calibration.check(R.id.calibration_weather);
                break;
            default:
                calibration.check(R.id.calibration_auto);
        }
        RadioGroup timingGroup = findViewById(R.id.timing_group);
        timingGroup.check(prefs.getCalibrationTiming() == Prefs.CalibrationTiming.CONTINUOUS
                ? R.id.timing_continuous : R.id.timing_start);
        timingGroup.setOnCheckedChangeListener((g, id) -> {
            prefs.setCalibrationTiming(id == R.id.timing_continuous
                    ? Prefs.CalibrationTiming.CONTINUOUS : Prefs.CalibrationTiming.AT_START);
            updateManualVisibility();
        });
        findViewById(R.id.calibrate_button).setOnClickListener(v -> {
            saveManualHeight();
            prefs.requestCalibration();
            android.widget.Toast.makeText(this, R.string.calibrating, android.widget.Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.help_button).setOnClickListener(v -> showHelp());
        heightUnitGroup.check(prefs.isImperial() ? R.id.height_imperial : R.id.height_metric);
        switch (prefs.getPressureUnit()) {
            case ATM:
                pressureGroup.check(R.id.pressure_atm);
                break;
            case MMHG:
                pressureGroup.check(R.id.pressure_mmhg);
                break;
            default:
                pressureGroup.check(R.id.pressure_pa);
        }
        showManualHeight();
        updateManualVisibility();

        RadioGroup historyGroup = findViewById(R.id.history_group);
        final int[] ids = {R.id.history_30m, R.id.history_1h, R.id.history_6h,
                R.id.history_12h, R.id.history_24h};
        final int[] minutes = {30, 60, 360, 720, 1440};
        for (int i = 0; i < ids.length; i++) {
            if (minutes[i] == prefs.getHistoryMinutes()) {
                historyGroup.check(ids[i]);
            }
        }
        historyGroup.setOnCheckedChangeListener((g, id) -> {
            for (int i = 0; i < ids.length; i++) {
                if (ids[i] == id) {
                    prefs.setHistoryMinutes(minutes[i]);
                }
            }
        });

        recordingSwitch = findViewById(R.id.record_switch);
        recordingSwitch.setOnCheckedChangeListener((b, checked) -> {
            if (checked == prefs.isBackgroundRecording()) {
                return;
            }
            if (checked) {
                prefs.startBackgroundRecording();
                PressureRecordingService.start(this);
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 2);
                }
            } else {
                PressureRecordingService.stop(this);
            }
            showRecordingInfo();
        });

        calibration.setOnCheckedChangeListener((g, id) -> {
            Prefs.HeightMode mode = Prefs.HeightMode.STANDARD;
            if (id == R.id.calibration_manual) {
                mode = Prefs.HeightMode.MANUAL;
            } else if (id == R.id.calibration_gps) {
                mode = Prefs.HeightMode.GPS;
            } else if (id == R.id.calibration_weather) {
                mode = Prefs.HeightMode.WEATHER;
            }
            prefs.setHeightMode(mode);
            if (mode != Prefs.HeightMode.STANDARD && mode != Prefs.HeightMode.MANUAL
                    && !HeightCalibrator.hasLocationPermission(this)) {
                requestPermissions(new String[]{
                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION}, 1);
            }
            updateManualVisibility();
        });
        heightUnitGroup.setOnCheckedChangeListener((g, id) -> {
            saveManualHeight();
            prefs.setImperial(id == R.id.height_imperial);
            showManualHeight();
        });
        pressureGroup.setOnCheckedChangeListener((g, id) -> {
            if (id == R.id.pressure_atm) {
                prefs.setPressureUnit(Prefs.PressureUnit.ATM);
            } else if (id == R.id.pressure_mmhg) {
                prefs.setPressureUnit(Prefs.PressureUnit.MMHG);
            } else {
                prefs.setPressureUnit(Prefs.PressureUnit.PA);
            }
        });
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        gestures.onTouchEvent(event);
        return super.dispatchTouchEvent(event);
    }

    private void showRecordingInfo() {
        TextView info = findViewById(R.id.record_info);
        if (prefs.isBackgroundRecording()) {
            info.setText(getString(R.string.record_active, android.text.format.DateFormat
                    .getTimeFormat(this).format(new java.util.Date(prefs.getBackgroundRecordingUntilMs()))));
        } else {
            info.setText(R.string.record_inactive);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The switch may have turned itself off after 24 hours.
        recordingSwitch.setChecked(prefs.isBackgroundRecording());
        showRecordingInfo();
    }

    private void showHelp() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.help)
                .setMessage(androidx.core.text.HtmlCompat.fromHtml(
                        getString(R.string.help_text),
                        androidx.core.text.HtmlCompat.FROM_HTML_MODE_COMPACT))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void updateManualVisibility() {
        int v = prefs.getHeightMode() == Prefs.HeightMode.MANUAL ? View.VISIBLE : View.GONE;
        findViewById(R.id.manual_height_row).setVisibility(v);
        boolean canCalibrate = prefs.getHeightMode() != Prefs.HeightMode.STANDARD
                && prefs.getCalibrationTiming() == Prefs.CalibrationTiming.AT_START;
        findViewById(R.id.calibrate_button).setEnabled(canCalibrate);
    }

    private void showManualHeight() {
        boolean imperial = prefs.isImperial();
        double h = prefs.getManualHeightMeters();
        manualHeight.setText(String.format(Locale.US, "%.0f", imperial ? Prefs.metersToFeet(h) : h));
        manualHeightUnit.setText(imperial ? R.string.unit_ft : R.string.unit_m);
    }

    private void saveManualHeight() {
        try {
            double value = Double.parseDouble(manualHeight.getText().toString().trim());
            // The unit currently shown in the field is the one stored in prefs.
            double meters = prefs.isImperial() ? Prefs.feetToMeters(value) : value;
            prefs.setManualHeightMeters((float) meters);
        } catch (NumberFormatException ignored) {
            // Keep the previous value.
        }
    }

    @Override
    protected void onPause() {
        saveManualHeight();
        super.onPause();
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right);
    }
}
