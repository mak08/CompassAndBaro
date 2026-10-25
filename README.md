# Bitsailor Compass & Barometer

An Android app that shows a compass and barometric data (pressure and height) on a single screen.

## Features

- **Compass** with a rotating dial and a live heading readout, using the rotation-vector sensor.
- **Pressure and height** fields, derived from the device barometer.
- **Pressure history graph** with pressure (left), height (right) and time (bottom) scales. The span is 30 minutes to 24 hours, and history is stored on the device.
- **Height calibration:** standard atmosphere, manual height, GPS altitude, or sea-level pressure from a weather service ([Open-Meteo](https://open-meteo.com)). Calibration can run at app start or continuously.
- **Background recording** of pressure (every 10 minutes) for up to 24 hours, even when the app is closed.
- **Units:** metric or imperial height; hPa, atm or mmHg pressure.
- **Status line** showing barometer availability, compass accuracy and the calibration state.

Swipe left on the main screen to open Settings, and swipe right to return. The Help button in Settings explains every option.

## Requirements

- Android 12 (API 31) or later
- A barometer sensor for pressure and height (the compass works without one)

## Build

Open the project in Android Studio, or run:

```
./gradlew assembleDebug
```

## Permissions

- **Location** (optional): only for the GPS and weather calibration modes.
- **Internet:** only for the weather calibration mode; approximate coordinates are sent to Open-Meteo.
- **Notifications / foreground service:** for background recording.
