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

## Features

- Starts, replaces, and stops the overlay through a small JSON REST API.
- Replaces the current stream atomically when a newer camera request arrives.
- Removes the window immediately on stop, before releasing the media player.
- Uses interleaved RTP-over-RTSP/TCP for reliable Frigate/go2rtc playback.
- Runs as a foreground service and starts again after the TV boots.
- Keeps remote-control input focused on the underlying TV application.
- Uses Java for all application logic.

## TV setup

1. Install and open Camera on TV.
2. Select **Grant overlay permission** and enable **Display over other apps** for Camera on TV.
3. Return to the app and note the API address displayed on screen, for example
   `http://192.168.1.50:8787`.
4. Leave the foreground service running. It restarts after TV boot and continues when the activity
   is closed.

The TV and API client must be reachable on the same network. The API has no authentication and must
not be exposed to the public Internet. Use a trusted LAN, VLAN, or firewall rules to restrict it.

## REST API

All responses use JSON. Browser clients are supported through permissive CORS headers.

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

`PUT /stream` is accepted as an alternative to `POST /stream`.

Example:

```bash
curl -X POST http://TV_IP:8787/stream \
  -H 'Content-Type: application/json' \
  -d '{"url":"rtsp://user:password@CAMERA_IP:554/stream","muted":true}'
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

The response reports service state, overlay permission, playback state, mute state, active URL, and
the most recent playback error. The root endpoint (`GET /`) returns a short endpoint summary.

Example response:

```json
{
  "service": "running",
  "port": 8787,
  "overlayPermission": true,
  "playback": "playing",
  "muted": true,
  "url": "rtsp://camera.example/stream",
  "error": null
}
```

## Home Assistant and Frigate

The following example starts the stream only once, waits for Frigate's matching MQTT end event,
and then stops the overlay. It does not renew the video periodically. Because the automation uses
`mode: restart`, a new qualifying camera event replaces the active stream and becomes the only
event allowed to stop it.

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
        {{ event.get('type') in ['new', 'update']
           and after.get('label') == 'person'
           and after.get('id')
           and after.get('end_time') is none
           and zone is not none
           and zone in (after.get('entered_zones') or [])
           and zone not in (before.get('entered_zones') or []) }}
  actions:
    - variables:
        event_id: "{{ trigger.payload_json['after']['id'] }}"
        camera: "{{ trigger.payload_json['after']['camera'] }}"
        stream_url: >-
          {{ {
            'outside_left_camera': 'rtsp://FRIGATE_IP:8554/outside_left_camera',
            'outside_right_camera': 'rtsp://FRIGATE_IP:8554/outside_right_camera'
          }.get(camera) }}
    - action: rest_command.camera_on_tv_start
      data:
        video: "{{ stream_url }}"
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
- `BootReceiver` restores the foreground service after Android finishes booting.

## Operational notes

- The overlay is sized to approximately 36% of screen width with a 16:9 aspect ratio and sits flush
  against the top-right edges of the display.
- The overlay does not accept focus or touch input, so remote-control input continues to reach the
  underlying TV application.
- RTSP startup time still depends on camera responsiveness, codec initialization, and network
  latency. The player uses a deliberately short live buffer to reduce startup delay and forces
  interleaved RTP-over-RTSP/TCP for compatibility with Frigate/go2rtc streams.
- If the TV receives a different DHCP address, API clients must use the new address. A DHCP
  reservation for the Mi Box is recommended.
- Credentials embedded in RTSP URLs may appear in API status responses. Only trusted systems should
  call the API.

## Contributing

Bug reports and focused pull requests are welcome. Before opening a pull request, run the complete
Gradle verification command above and describe the Android TV model and Android version used for
device testing.
