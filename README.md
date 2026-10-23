<p align="center">
  <img src="app/src/main/res/drawable-nodpi/camera_on_tv_icon.png" width="144" alt="Camera on TV icon">
</p>

# Camera on TV

Camera on TV is an Android TV application that displays an RTSP security-camera stream in a
non-interactive overlay at the top-right of the television. A small REST API on the TV controls
the stream, so home-automation systems and other devices on the local network can show or hide a
camera without interrupting the app currently being watched.

The player uses AndroidX Media3 with RTSP support. It accepts H.264 video and the other RTSP sample
formats supported by the installed Media3 version. Playback is muted by default so a camera does
not unexpectedly replace the television's current audio.

## Documentation map

- Open **How to use** in the TV app for the essential setup and API commands using the TV's current
  network address.
- Continue to [REST API](#rest-api) for the complete request and response contract.
- See [Home Assistant and Frigate](#home-assistant-and-frigate) for a two-camera automation.
- See [Build and install](#build-and-install) for developer installation and verification.
- Review [Troubleshooting](#troubleshooting) when the API, overlay, video, or sound is unavailable.
- Read [CHANGELOG.md](CHANGELOG.md) for version history.

## Features

- Starts, replaces, and stops the overlay through a small JSON REST API.
- Replaces the current stream atomically when a newer camera request arrives.
- Plays a rapid ambulance-style siren through a dedicated API request without changing the stream.
- Provides persistent API request, automatic stream-start, and never-play sound policies on the TV.
- Automatically enables a software-decoder compatibility mode on affected Amlogic MiBox hardware.
- Presents service health, the LAN API address, API shortcuts, and persistent preferences in a
  remote-friendly two-column TV dashboard.
- Includes an on-TV **How to use** guide with the live API address, essential commands, takeover
  behavior, sound defaults, and network-safety guidance.
- Removes the window immediately on stop, before releasing the media player.
- Uses interleaved RTP-over-RTSP/TCP for reliable Frigate/go2rtc playback.
- Runs as a foreground service and starts again after the TV boots.
- Keeps remote-control input focused on the underlying TV application.
- Uses Java for all application logic.

## TV setup

1. Install and open Camera on TV. The dashboard is designed to keep connection status, the API
   address, stream controls, and all preferences visible together on a 16:9 television.
2. Select **Grant overlay permission** and enable **Display over other apps** for Camera on TV.
3. Return to the app and note the API address displayed on screen, for example
   `http://192.168.1.50:8787`.
4. Choose the **Alarm sound** policy. **Alarm only by API request** is the default.
5. Review **MiBox compatibility decoder**. It is selected automatically on affected hardware and
   can be overridden manually.
6. Leave the foreground service running. It restarts after TV boot and continues when the activity
   is closed.

Navigate with the TV remote's directional pad and press **OK** to change an alarm or compatibility
setting. The currently focused control has a high-contrast amber outline, the selected option has a
teal background, and every change is saved immediately. **Stop current stream** removes an active
overlay, while **Refresh status** updates the permission, network, and service information shown on
the dashboard. Select **How to use** at any time for an on-TV quick-start guide and REST examples.

The TV and API client must be reachable on the same network. The API has no authentication and must
not be exposed to the public Internet. Use a trusted LAN, VLAN, or firewall rules to restrict it.

## REST API

All responses use JSON. Browser clients are supported through permissive CORS headers.

| Method | Path | Purpose | Success |
| --- | --- | --- | --- |
| `GET` | `/` | Discover the available commands | `200 OK` |
| `GET` | `/status` | Read service, permission, playback, sound, and decoder state | `200 OK` |
| `POST` or `PUT` | `/stream` | Start a stream or replace the active stream | `202 Accepted` |
| `POST` | `/alarm` | Play the short alarm without changing the stream | `202 Accepted` |
| `DELETE` | `/stream` | Stop the active stream immediately | `200 OK` |
| `POST` | `/stop` | Stop fallback for clients that cannot send `DELETE` | `200 OK` |
| `OPTIONS` | any path | CORS preflight | `204 No Content` |

Invalid JSON or an invalid RTSP URL returns `400 Bad Request`. A start request without overlay
permission returns `409 Conflict`; an unknown endpoint returns `404 Not Found`. Error responses use
`{"message":"..."}`. A successful start response also includes `"accepted":true`.

### Start or replace a stream

```http
POST /stream
Content-Type: application/json

{
  "url": "rtsp://camera.example/stream",
  "muted": true
}
```

`url` is required and must use the `rtsp://` scheme. `muted` is optional and defaults to `true`.
Starting a stream replaces any currently visible stream. A successful request returns HTTP 202
because player preparation continues asynchronously.

Stream requests are silent in **Alarm only by API request** mode. **Alarm on every stream** plays
the short alarm whenever a stream starts or is replaced, while **Never play alarm** suppresses all
alarms. `muted` controls only the RTSP stream's audio track.

`PUT /stream` is accepted as an alternative to `POST /stream`.

Example:

```bash
curl -X POST http://TV_IP:8787/stream \
  -H 'Content-Type: application/json' \
  -d '{"url":"rtsp://user:password@CAMERA_IP:554/stream","muted":true}'
```

### Play the alarm

```http
POST /alarm
```

The request schedules the half-second alarm without starting, replacing, or stopping an RTSP
stream. It works whether an overlay is visible or not. The response includes `"played":true` when
the sound policy permits the request, or `"played":false` when **Never play alarm** suppresses it.
Both outcomes are accepted commands and return HTTP 202. Playback runs asynchronously, so `played`
reports the policy decision rather than confirmation that audio was audible on the TV. A new alarm
request replaces any alarm still playing; stream replacement and stop requests do not cancel it.

```bash
curl -X POST http://TV_IP:8787/alarm
```

### Upgrading from 1.4

Clients that previously sent `"sound":true` with `/stream` must send a separate `POST /alarm`
request when an alert is needed. Version 1.5 ignores the legacy `sound` field on stream requests.
Existing alarm preferences are preserved: `follow_api` now means **Alarm only by API request**,
`always_on` means **Alarm on every stream**, and `always_off` means **Never play alarm**. Explicit
alarm requests are permitted in both `follow_api` and `always_on` modes.

### Stop a stream

```http
DELETE /stream
```

The overlay window is removed immediately; player cleanup follows on Android's main thread.
`POST /stop` is provided as an equivalent endpoint for clients that cannot send DELETE requests.

```bash
curl -X DELETE http://TV_IP:8787/stream
```

### Read status

```http
GET /status
```

The response reports service state, overlay permission, playback state, mute state, the most recent
alarm decision, the persistent sound policy, active URL, and the most recent playback error. The
root endpoint (`GET /`) returns a short endpoint summary. `soundRequested` is true when the most
recent alarm decision came from `POST /alarm`; `soundEnabled` records whether that decision allowed
the sound to play. Starting or replacing a stream also updates this decision according to the
automatic alarm policy. Stopping a stream preserves it. These fields describe the last decision,
not whether an alarm is currently audible.

Example response:

```json
{
  "service": "running",
  "port": 8787,
  "overlayPermission": true,
  "playback": "playing",
  "muted": true,
  "soundRequested": true,
  "soundEnabled": true,
  "soundMode": "follow_api",
  "compatibilityDecoder": true,
  "compatibilityDecoderRecommended": true,
  "url": "rtsp://camera.example/stream",
  "error": null
}
```

## Home Assistant and Frigate

The following example starts video when a camera's person occupancy changes from clear to occupied,
stops or falls back to the other camera when occupancy clears, and sounds the alarm independently
when a person first occupies either alert zone. Additional people on an already occupied camera or
zone do not retrigger that transition.

Replace `TV_IP`, `FRIGATE_IP`, the TV entity, camera names, and zone names with values from your
installation.

```yaml
# configuration.yaml
rest_command:
  camera_on_tv_start:
    url: "http://TV_IP:8787/stream"
    method: POST
    content_type: "application/json"
    timeout: 8
    payload: >-
      {{ {"url": video, "muted": true} | to_json }}

  camera_on_tv_alarm:
    url: "http://TV_IP:8787/alarm"
    method: POST
    timeout: 8

  camera_on_tv_stop:
    url: "http://TV_IP:8787/stream"
    method: DELETE
    timeout: 8
```

```yaml
# automations.yaml
- id: frigate_person_camera_on_tv
  alias: Frigate person live video on TV
  initial_state: true
  triggers:
    - trigger: state
      entity_id: binary_sensor.outside_left_camera_person_occupancy
      from: "off"
      to: "on"
      id: left_camera_occupied
    - trigger: state
      entity_id: binary_sensor.outside_left_camera_person_occupancy
      from: "on"
      to: "off"
      id: left_camera_clear
    - trigger: state
      entity_id: binary_sensor.outside_right_camera_person_occupancy
      from: "off"
      to: "on"
      id: right_camera_occupied
    - trigger: state
      entity_id: binary_sensor.outside_right_camera_person_occupancy
      from: "on"
      to: "off"
      id: right_camera_clear
    - trigger: state
      entity_id:
        - binary_sensor.left_zone_person_occupancy
        - binary_sensor.right_zone_person_occupancy
      from: "off"
      to: "on"
      id: alert_zone_occupied
    - trigger: state
      entity_id: remote.android_tv
      from: "on"
      id: tv_off
  actions:
    - choose:
        - conditions: "{{ trigger.id == 'left_camera_occupied' and is_state('remote.android_tv', 'on') }}"
          sequence:
            - action: rest_command.camera_on_tv_start
              data:
                video: rtsp://FRIGATE_IP:8554/outside_left_camera
        - conditions: "{{ trigger.id == 'right_camera_occupied' and is_state('remote.android_tv', 'on') }}"
          sequence:
            - action: rest_command.camera_on_tv_start
              data:
                video: rtsp://FRIGATE_IP:8554/outside_right_camera
        - conditions: "{{ trigger.id == 'alert_zone_occupied' and is_state('remote.android_tv', 'on') }}"
          sequence:
            - action: rest_command.camera_on_tv_alarm
        - conditions: "{{ trigger.id == 'left_camera_clear' }}"
          sequence:
            - if: "{{ is_state('binary_sensor.outside_right_camera_person_occupancy', 'on') and is_state('remote.android_tv', 'on') }}"
              then:
                - action: rest_command.camera_on_tv_start
                  data:
                    video: rtsp://FRIGATE_IP:8554/outside_right_camera
              else:
                - action: rest_command.camera_on_tv_stop
        - conditions: "{{ trigger.id == 'right_camera_clear' }}"
          sequence:
            - if: "{{ is_state('binary_sensor.outside_left_camera_person_occupancy', 'on') and is_state('remote.android_tv', 'on') }}"
              then:
                - action: rest_command.camera_on_tv_start
                  data:
                    video: rtsp://FRIGATE_IP:8554/outside_left_camera
              else:
                - action: rest_command.camera_on_tv_stop
        - conditions: "{{ trigger.id == 'tv_off' }}"
          sequence:
            - action: rest_command.camera_on_tv_stop
  mode: queued
  max: 10
```

## Build and install

The application logic is Java. The generated project retains Gradle's Kotlin DSL for build files.

Requirements:

- Android Studio with Android SDK 37, or a compatible command-line Android SDK installation.
- JDK 17 or later.
- ADB access to the Android TV device for command-line installation.

```bash
./gradlew clean assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

For wireless ADB, enable the TV's developer options and USB/network debugging, accept the TV's
authorization prompt, then use the TV address shown by Android's network settings:

```bash
adb connect TV_IP:5555
adb -s TV_IP:5555 install -r app/build/outputs/apk/debug/app-debug.apk
```

Wireless-debugging menus and pairing requirements vary by Android TV version. Keep ADB available
only on a trusted network and disable network debugging when maintenance is complete.

The application targets Android TV and supports Android 6.0 (API 23) and later. On Android 8.0 and
later, the API runs as a foreground media-playback service with a persistent low-priority
notification.

Run all local verification tasks with:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

For an update, install the checked APK with `adb install -r` to preserve the saved sound and decoder
preferences. Open Camera on TV once after installation to ensure its foreground API service is
running, then return to the app you were watching. Verify the installed `versionName` and
`versionCode`, and confirm that `GET /status` reports `"service":"running"`,
`"overlayPermission":true`, the expected compatibility-decoder preference, and `"error":null`.
`GET /` should advertise the `/alarm` endpoint. These read-only API checks do not start a camera
stream or sound an alarm.

## Architecture

- `MainActivity` provides setup, permission, service, and network-address status.
- `CameraOverlayService` owns the foreground service, overlay window, Media3 player, and API server.
- `CameraApiServer` validates REST requests and passes commands to the service controller.
- `AlarmPlayer` synthesizes the sweeping alert at runtime, so no external audio asset is needed.
- `SoundSettings` persists and applies the user's automatic, API-requested, or disabled alarm policy.
- `DecoderSettings` persists the optional H.264 software-decoder workaround.
- `BootReceiver` restores the foreground service after Android finishes booting.

## Operational notes

- The overlay is sized to approximately 36% of screen width with a 16:9 aspect ratio and sits flush
  against the top-right edges of the display.
- The overlay does not accept focus or touch input, so remote-control input continues to reach the
  underlying TV application.
- An enabled alarm uses the television's media-audio path and current volume. Camera audio remains
  muted when the API request uses `"muted": true`.
- **MiBox compatibility decoder** is automatically selected on Xiaomi MiBox-family devices with
  Amlogic hardware, while other TVs retain their normal decoder selection. Users can override the
  automatic choice. When enabled, it prefers Android's software H.264 decoder for the overlay and
  leaves YouTube and other applications free to use the hardware decoder. This avoids known
  decoder-reset conflicts at the cost of moderately higher CPU usage. Decoder fallback remains
  enabled if software initialization is unavailable.
- RTSP startup time still depends on camera responsiveness, codec initialization, and network
  latency. The player uses a deliberately short live buffer to reduce startup delay and forces
  interleaved RTP-over-RTSP/TCP for compatibility with Frigate/go2rtc streams.
- If the TV receives a different DHCP address, API clients must use the new address. A DHCP
  reservation for the Mi Box is recommended.
- Credentials embedded in RTSP URLs may appear in API status responses. Only trusted systems should
  call the API.

## Troubleshooting

### The API cannot be reached

- Confirm the dashboard says **READY** and **REST API service running**.
- Use the exact address currently displayed on the TV; DHCP may have changed it after a restart.
- Confirm the controller and television are on the same trusted network and that client isolation or
  a firewall is not blocking TCP port `8787`.
- Test discovery first with `curl http://TV_IP:8787/`, followed by
  `curl http://TV_IP:8787/status`.

### The API accepts the request but no overlay appears

- Confirm **Overlay permission granted** appears in the TV dashboard.
- Check the `playback` and `error` fields from `GET /status`.
- Verify that the camera URL starts with `rtsp://`, is reachable from the TV, and provides a format
  supported by Media3 and the device decoder. H.264 is the recommended format.

### The underlying video app becomes black after an overlay closes

Leave **MiBox compatibility decoder** enabled on affected Xiaomi/Amlogic devices. It is selected
automatically when recommended. On other devices, enable it only when hardware-decoder contention
is observed; software decoding uses more CPU.

### The alarm is silent

- Send `POST /alarm` and confirm the TV setting is **Alarm only by API request** or
  **Alarm on every stream**.
- Inspect the response's `played` field. A false value means **Never play alarm** suppressed it.
- Check the television's media volume and mute state. The app does not change system volume.
- Remember that a stream's `muted` value controls camera audio; the alarm endpoint is independent.

## Security and privacy

Camera on TV has no cloud service, account, telemetry, or remote relay. Preferences remain in the
app's private Android storage. The REST API intentionally has no authentication, uses plain HTTP,
and may report an RTSP URL containing camera credentials through `GET /status`. Run it only on a
trusted LAN or isolated camera VLAN. Never forward port `8787` from the Internet, and use firewall
rules when untrusted clients share the network.

## Contributing

Bug reports and focused pull requests are welcome. Before opening a pull request, run the complete
Gradle verification command above and describe the Android TV model and Android version used for
device testing.

## License

This repository does not currently include an open-source license. Its public visibility allows the
source to be viewed, but does not by itself grant reuse or redistribution rights. Add an explicit
license before offering the project for third-party redistribution.
