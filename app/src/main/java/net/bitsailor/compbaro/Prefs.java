package net.bitsailor.compbaro;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/** Persistent settings plus unit conversion/formatting helpers. */
public final class Prefs {
    public static final float STANDARD_SEA_LEVEL_PA = 101325f;

    private static final String FILE = "settings";
    private static final String KEY_LEGACY_MANUAL = "manual_height";
    private static final String KEY_MODE = "height_mode";
    private static final String KEY_TIMING = "calibration_timing";
    private static final String KEY_CALIBRATE_REQUEST = "calibrate_request";
    private static final String KEY_LAST_HEIGHT_M = "last_calibrated_height_m";
    public static final float DEFAULT_HEIGHT_M = 100f;
    private static final String KEY_BG_UNTIL = "background_recording_until";
    public static final long BACKGROUND_RECORDING_MS = 24L * 60 * 60 * 1000;
    private static final String KEY_TRUE_NORTH = "true_north";
    private static final String KEY_DECLINATION = "declination_deg";
    private static final String KEY_HEIGHT_M = "manual_height_m";
    private static final String KEY_IMPERIAL = "imperial";
    private static final String KEY_PRESSURE_UNIT = "pressure_unit";
    private static final String KEY_HISTORY_MIN = "history_minutes";
    private static final String KEY_SEA_LEVEL = "sea_level_pa";
    private static final String KEY_SEA_LEVEL_SOURCE = "sea_level_source";
    private static final String KEY_SEA_LEVEL_TIME = "sea_level_time";
    private static final String KEY_CALIBRATED_FOR = "calibrated_for_m";

    /** How the sea-level reference pressure used for the height is obtained. */
    public enum HeightMode {
        STANDARD, MANUAL, GPS, WEATHER
    }

    /** When the sea-level reference is (re)determined for the GPS and weather modes. */
    public enum CalibrationTiming {
        AT_START, CONTINUOUS
    }

    public enum PressureUnit {
        // The stored name PA is kept for compatibility; values are shown in hPa.
        PA("hPa", 0.01, "%.1f", 1.0),
        ATM("atm", 1.0 / 101325.0, "%.5f", 0.001),
        MMHG("mmHg", 1.0 / 133.322387415, "%.1f", 1.0);

        public final String label;
        /** Tick spacing on the graph scale when space permits (one whole unit), in this unit. */
        public final double minScaleStep;
        private final double perPa;
        private final String format;

        PressureUnit(String label, double perPa, String format, double minScaleStep) {
            this.label = label;
            this.perPa = perPa;
            this.format = format;
            this.minScaleStep = minScaleStep;
        }

        public double fromPa(double pa) {
            return pa * perPa;
        }

        public String formatValue(double pa) {
            return String.format(Locale.US, format, fromPa(pa));
        }
    }

    private final SharedPreferences sp;

    public Prefs(Context context) {
        sp = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public HeightMode getHeightMode() {
        String name = sp.getString(KEY_MODE, null);
        if (name == null) {
            return sp.getBoolean(KEY_LEGACY_MANUAL, false) ? HeightMode.MANUAL : HeightMode.STANDARD;
        }
        try {
            return HeightMode.valueOf(name);
        } catch (IllegalArgumentException e) {
            return HeightMode.STANDARD;
        }
    }

    public void setHeightMode(HeightMode mode) {
        sp.edit().putString(KEY_MODE, mode.name()).apply();
    }

    public CalibrationTiming getCalibrationTiming() {
        try {
            return CalibrationTiming.valueOf(
                    sp.getString(KEY_TIMING, CalibrationTiming.CONTINUOUS.name()));
        } catch (IllegalArgumentException e) {
            return CalibrationTiming.CONTINUOUS;
        }
    }

    public void setCalibrationTiming(CalibrationTiming timing) {
        sp.edit().putString(KEY_TIMING, timing.name()).apply();
    }

    /** Asks the main screen to calibrate the height as soon as possible. */
    public void requestCalibration() {
        sp.edit().putBoolean(KEY_CALIBRATE_REQUEST, true).apply();
    }

    public boolean consumeCalibrationRequest() {
        if (!sp.getBoolean(KEY_CALIBRATE_REQUEST, false)) {
            return false;
        }
        sp.edit().putBoolean(KEY_CALIBRATE_REQUEST, false).apply();
        return true;
    }

    /** Manual reference height in metres: entered value, else last calibrated height, else 100 m. */
    public float getManualHeightMeters() {
        return sp.getFloat(KEY_HEIGHT_M, sp.getFloat(KEY_LAST_HEIGHT_M, DEFAULT_HEIGHT_M));
    }

    public void setManualHeightMeters(float meters) {
        sp.edit().putFloat(KEY_HEIGHT_M, meters).apply();
    }

    public boolean isPressureUp() {
        return sp.getBoolean("pressure_up", true);
    }

    public void setPressureUp(boolean up) {
        sp.edit().putBoolean("pressure_up", up).apply();
    }

    public boolean isImperial() {
        return sp.getBoolean(KEY_IMPERIAL, false);
    }

    public void setImperial(boolean imperial) {
        sp.edit().putBoolean(KEY_IMPERIAL, imperial).apply();
    }

    public PressureUnit getPressureUnit() {
        try {
            return PressureUnit.valueOf(sp.getString(KEY_PRESSURE_UNIT, PressureUnit.PA.name()));
        } catch (IllegalArgumentException e) {
            return PressureUnit.PA;
        }
    }

    public void setPressureUnit(PressureUnit unit) {
        sp.edit().putString(KEY_PRESSURE_UNIT, unit.name()).apply();
    }

    /** True while background recording is switched on and its 24 hours have not elapsed. */
    public boolean isBackgroundRecording() {
        return System.currentTimeMillis() < sp.getLong(KEY_BG_UNTIL, 0);
    }

    public long getBackgroundRecordingUntilMs() {
        return sp.getLong(KEY_BG_UNTIL, 0);
    }

    public void startBackgroundRecording() {
        sp.edit().putLong(KEY_BG_UNTIL, System.currentTimeMillis() + BACKGROUND_RECORDING_MS).apply();
    }

    public void stopBackgroundRecording() {
        sp.edit().putLong(KEY_BG_UNTIL, 0).apply();
    }

    public boolean isTrueNorthEnabled() {
        return sp.getBoolean(KEY_TRUE_NORTH, true);
    }

    /** Returns true once, so the location permission is requested only on the first start. */
    public boolean consumeFirstLocationPrompt() {
        if (sp.getBoolean("location_prompted", false)) {
            return false;
        }
        sp.edit().putBoolean("location_prompted", true).apply();
        return true;
    }

    public void setTrueNorthEnabled(boolean enabled) {
        sp.edit().putBoolean(KEY_TRUE_NORTH, enabled).apply();
    }

    /** Last computed magnetic declination in degrees (east positive), or NaN if unknown. */
    public float getDeclination() {
        return sp.getFloat(KEY_DECLINATION, Float.NaN);
    }

    public void setDeclination(float degrees) {
        sp.edit().putFloat(KEY_DECLINATION, degrees).apply();
    }

    /** Time span shown in the history graph, in minutes. */
    public int getHistoryMinutes() {
        return sp.getInt(KEY_HISTORY_MIN, 360);
    }

    public void setHistoryMinutes(int minutes) {
        sp.edit().putInt(KEY_HISTORY_MIN, minutes).apply();
    }

    public long getHistoryWindowMs() {
        return getHistoryMinutes() * 60_000L;
    }

    /** True if a calibrated reference exists that was obtained in the current mode. */
    public boolean hasReference() {
        HeightMode mode = getHeightMode();
        return mode != HeightMode.STANDARD && sp.contains(KEY_SEA_LEVEL)
                && mode.name().equals(sp.getString(KEY_SEA_LEVEL_SOURCE, null));
    }

    /** Sea-level pressure to use for the current mode (standard atmosphere until calibrated). */
    public float getReferencePa() {
        return hasReference() ? sp.getFloat(KEY_SEA_LEVEL, STANDARD_SEA_LEVEL_PA)
                : STANDARD_SEA_LEVEL_PA;
    }

    public long getReferenceTimeMs() {
        return sp.getLong(KEY_SEA_LEVEL_TIME, 0);
    }

    public void storeReference(float seaLevelPa, double heightM, long timeMs) {
        SharedPreferences.Editor e = sp.edit()
                .putFloat(KEY_SEA_LEVEL, seaLevelPa)
                .putString(KEY_SEA_LEVEL_SOURCE, getHeightMode().name())
                .putFloat(KEY_LAST_HEIGHT_M, (float) heightM)
                .putLong(KEY_SEA_LEVEL_TIME, timeMs);
        if (getHeightMode() == HeightMode.MANUAL) {
            e.putFloat(KEY_CALIBRATED_FOR, getManualHeightMeters());
        }
        e.apply();
    }

    /** True if the manual height has not yet been used to calibrate the reference. */
    public boolean needsManualCalibration() {
        return getHeightMode() == HeightMode.MANUAL
                && (!hasReference()
                || sp.getFloat(KEY_CALIBRATED_FOR, Float.NaN) != getManualHeightMeters());
    }

    public static double metersToFeet(double m) {
        return m / 0.3048;
    }

    public static double feetToMeters(double ft) {
        return ft * 0.3048;
    }

    /** Height as a number in the selected unit system, without the unit. */
    public String formatHeight(double meters) {
        return String.format(Locale.US, "%.0f", isImperial() ? metersToFeet(meters) : meters);
    }
}
