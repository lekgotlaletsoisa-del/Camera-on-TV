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
- Optionally plays a rapid ambulance-style siren when a stream starts or takes over.
- Provides persistent Follow API, Always on, and Always off sound policies on the TV.
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
4. Choose the **Alarm sound** policy. **Follow REST request** is the default.
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
  "muted": true,
  "sound": true
}
```

`url` is required and must use the `rtsp://` scheme. `muted` is optional and defaults to `true`.
`sound` is optional and defaults to `false`.
Starting a stream replaces any currently visible stream. A successful request returns HTTP 202
because player preparation continues asynchronously.

When `sound` is `true`, an accepted start or replacement plays the app's rapid sweeping siren
through the TV speakers. The alarm is independent of `muted`, which controls only the RTSP
stream's audio track. The TV settings screen can override requests with **Always play alarm** or
**Never play alarm**; **Follow REST request** preserves the request value.

`PUT /stream` is accepted as an alternative to `POST /stream`.

Example:

```bash
curl -X POST http://TV_IP:8787/stream \
  -H 'Content-Type: application/json' \
  -d '{"url":"rtsp://user:password@CAMERA_IP:554/stream","muted":true,"sound":true}'
```

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

The response reports service state, overlay permission, playback state, mute state, the requested
and effective sound states, the persistent sound policy, active URL, and the most recent playback
error. The root endpoint (`GET /`) returns a short endpoint summary.

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

The following example shows every new person tracked by either configured camera. It requests no
sound outside the corresponding alert zone, and requests sound when the person is already in or
newly enters that zone. It waits for Frigate's matching MQTT end event and never renews the video
periodically. Because the automation uses `mode: restart`, a newer qualifying event becomes the
only event allowed to stop the active stream.

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
      {{ {"url": video, "muted": true, "sound": sound | default(false) | bool} | to_json }}

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
    - trigger: mqtt
      topic: frigate/events
  conditions:
    - condition: state
      entity_id: remote.android_tv
      state: "on"
    - condition: template
      value_template: >-
        {% set event = trigger.payload_json | default({}, true) %}
        {% set before = event.get('before') or {} %}
        {% set after = event.get('after') or {} %}
        {% set camera = after.get('camera') %}
        {% set zone = 'left_zone' if camera == 'outside_left_camera'
           else 'right_zone' if camera == 'outside_right_camera' else none %}
        {% set is_new = event.get('type') == 'new' %}
        {% set entered_zone = event.get('type') == 'update'
           and zone is not none
           and zone in (after.get('entered_zones') or [])
           and zone not in (before.get('entered_zones') or []) %}
        {{ (is_new or entered_zone)
           and after.get('label') == 'person'
           and after.get('id')
           and after.get('end_time') is none
           and zone is not none }}
  actions:
    - variables:
        event_id: "{{ trigger.payload_json['after']['id'] }}"
        camera: "{{ trigger.payload_json['after']['camera'] }}"
        stream_url: >-
          {{ {
            'outside_left_camera': 'rtsp://FRIGATE_IP:8554/outside_left_camera',
            'outside_right_camera': 'rtsp://FRIGATE_IP:8554/outside_right_camera'
          }.get(camera) }}
        sound_enabled: >-
          {% set after = trigger.payload_json['after'] %}
          {% set zone = 'left_zone' if camera == 'outside_left_camera'
             else 'right_zone' %}
          {{ zone in (after.get('entered_zones') or []) }}
    - action: rest_command.camera_on_tv_start
      data:
        video: "{{ stream_url }}"
        sound: "{{ sound_enabled }}"
    - repeat:
        sequence:
          - wait_for_trigger:
              - trigger: mqtt
                topic: frigate/events
              - trigger: state
                entity_id: remote.android_tv
                from: "on"
            timeout: "01:00:00"
            continue_on_timeout: true
        until:
          - condition: or
            conditions:
              - condition: template
                value_template: >-
                  {{ wait.trigger is none or not is_state('remote.android_tv', 'on') }}
              - condition: template
                value_template: >-
                  {% set event = wait.trigger.payload_json | default({}, true)
                     if wait.trigger is not none and wait.trigger.platform == 'mqtt'
                     else {} %}
                  {% set after = event.get('after') or {} %}
                  {{ after.get('id') == event_id
                     and (event.get('type') == 'end'
                          or after.get('end_time') is not none) }}
    - action: rest_command.camera_on_tv_stop
  mode: restart
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

## Architecture

- `MainActivity` provides setup, permission, service, and network-address status.
- `CameraOverlayService` owns the foreground service, overlay window, Media3 player, and API server.
- `CameraApiServer` validates REST requests and passes commands to the service controller.
- `AlarmPlayer` synthesizes the sweeping alert at runtime, so no external audio asset is needed.
- `SoundSettings` persists and applies the user's REST-request override.
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

- Send `"sound":true` in the start request and confirm the TV setting is **Follow REST request** or
  **Always play alarm**.
- Check the television's media volume and mute state. The app does not change system volume.
- Remember that `muted` controls camera audio, while `sound` independently controls the alarm.

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
