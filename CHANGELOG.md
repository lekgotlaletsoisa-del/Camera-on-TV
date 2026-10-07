# Changelog

All notable user-facing changes to Camera on TV are recorded here. Version numbers correspond to
the Android `versionName` in `app/build.gradle.kts`.

## 1.4

- Shortened the camera alert sound to half a second.
- Added a remote-friendly **How to use** guide inside the TV app.
- Shows the TV's live REST address alongside start, replace, stop, status, sound, and network-safety
  guidance.
- Expanded public API, setup, troubleshooting, security, and developer documentation.

## 1.3

- Replaced the vertical settings form with a light, two-column TV dashboard.
- Added live readiness state, compact API reference, high-contrast remote focus, and dedicated
  connection and preference cards.

## 1.2

- Added automatic detection of affected Xiaomi MiBox/Amlogic hardware.
- Added the software H.264 compatibility decoder preference to avoid conflicts with an underlying
  video app while the overlay closes.

## 1.1

- Added the optional request-controlled alarm using the `sound` field.
- Added persistent Follow API, Always on, and Always off TV settings.
- Exposed requested and effective sound state through `GET /status`.

## 1.0

- Initial Android TV release with REST-controlled RTSP overlay playback.
- Added atomic camera takeover, immediate stop, boot restoration, low-latency RTSP/TCP playback,
  and the synthesized ambulance-style alarm.
