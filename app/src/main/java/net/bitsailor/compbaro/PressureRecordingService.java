package net.bitsailor.compbaro;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.IBinder;
import android.os.SystemClock;
import android.text.format.DateFormat;

import java.util.Date;

/** Foreground service that records pressure samples while the app is closed. */
public class PressureRecordingService extends Service implements SensorEventListener {
    private static final String ACTION_STOP = "net.bitsailor.compbaro.STOP_RECORDING";
    private static final String CHANNEL_ID = "recording";
    private static final int NOTIFICATION_ID = 1;
    private static final int SAMPLING_PERIOD_US = 60_000_000;
    private static final int MAX_REPORT_LATENCY_US = 10 * 60_000_000;

    private SensorManager sensorManager;
    private Prefs prefs;
    private boolean registered;

    public static void start(Context context) {
        context.startForegroundService(new Intent(context, PressureRecordingService.class));
    }

    public static void stop(Context context) {
        new Prefs(context).stopBackgroundRecording();
        context.stopService(new Intent(context, PressureRecordingService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        prefs = new Prefs(this);
        // startForeground must be called even if we stop right away.
        startForeground(NOTIFICATION_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);

        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            prefs.stopBackgroundRecording();
        }
        Sensor pressure = getSystemService(SensorManager.class)
                .getDefaultSensor(Sensor.TYPE_PRESSURE);
        if (!prefs.isBackgroundRecording() || pressure == null) {
            prefs.stopBackgroundRecording();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!registered) {
            sensorManager = getSystemService(SensorManager.class);
            // Batching lets the processor sleep between deliveries.
            registered = sensorManager.registerListener(this, pressure, SAMPLING_PERIOD_US,
                    MAX_REPORT_LATENCY_US);
        }
        return START_STICKY;
    }

    private Notification buildNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                getString(R.string.record_notification_channel),
                NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, PressureRecordingService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE);
        String until = DateFormat.getTimeFormat(this)
                .format(new Date(prefs.getBackgroundRecordingUntilMs()));
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle(getString(R.string.record_notification_title))
                .setContentText(getString(R.string.record_notification_text, until))
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null,
                        getString(R.string.record_stop), stop).build())
                .setOngoing(true)
                .build();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!prefs.isBackgroundRecording()) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
        }
        // Batched events can be delivered late; use the time they were measured.
        long ageMs = (SystemClock.elapsedRealtimeNanos() - event.timestamp) / 1_000_000L;
        long timeMs = System.currentTimeMillis() - Math.max(0, ageMs);
        PressureHistory.add(this, timeMs, event.values[0] * 100f,
                PressureHistory.BACKGROUND_INTERVAL_MS);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    @Override
    public void onDestroy() {
        if (registered) {
            sensorManager.unregisterListener(this);
            registered = false;
        }
        PressureHistory.save(this);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
